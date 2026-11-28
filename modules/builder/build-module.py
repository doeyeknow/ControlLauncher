#!/usr/bin/env python3
import hashlib, json, os, re, subprocess, sys, tempfile, urllib.request
from pathlib import Path

ROOT = Path.cwd()
ALLOWED_EFFECTS = {"browserHomepage", "mutedApps", "channelLabels", "dock", "popupApps", "widgets"}

class NeedsUpdate(Exception):
    pass

def _effective(effects):
    out = {}
    for k, v in (effects or {}).items():
        if v is True or (isinstance(v, (list, dict)) and v) or (isinstance(v, str) and v):
            out[k] = v
    return out

def slug(text):
    s = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return (s[:48] or "module").strip("-")

def payload():
    body = os.environ.get("ISSUE_BODY", "")
    m = re.search(r"```json\s*(\{.*?\})\s*```", body, re.S)
    if m:
        return json.loads(m.group(1))
    return {"type": "create", "description": body.strip()}

PROVIDERS = {
    "openai": {"base": "https://api.openai.com/v1", "models": ["gpt-4o-mini"]},
    "xai": {"base": "https://api.x.ai/v1", "models": ["grok-4-fast"]},
    "perplexity": {"base": "https://api.perplexity.ai", "models": ["sonar"]},
    "anthropic": {"base": "https://api.anthropic.com/v1", "models": ["claude-3-5-haiku-latest"]},
    "meta": {"base": "https://api.meta.ai/v1", "models": ["muse-spark-1.3", "muse-wonder-1.0"]},
    "google": {"base": "https://generativelanguage.googleapis.com/v1beta/openai", "models": ["gemini-2.5-flash", "gemini-2.0-flash"]},
}

def _split(v):
    return [x.strip() for x in re.split(r"[,;\s]+", v or "") if x.strip()]

def _pool_keys():
    url = os.environ.get("AIKEYS_URL", "").rstrip("/")
    secret = os.environ.get("AIKEYS_BUILDER_SECRET", "")
    if not url or not secret:
        return []
    try:
        req = urllib.request.Request(url + "/v1/aikeys/pool",
            headers={"Authorization": "Bearer " + secret})
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read()).get("keys", [])
    except Exception:
        return []

def _report(key_id, ok):
    url = os.environ.get("AIKEYS_URL", "").rstrip("/")
    secret = os.environ.get("AIKEYS_BUILDER_SECRET", "")
    if not url or not secret or not key_id:
        return
    try:
        req = urllib.request.Request(url + "/v1/aikeys/report",
            data=json.dumps({"id": key_id, "ok": ok}).encode(),
            headers={"Authorization": "Bearer " + secret, "Content-Type": "application/json"})
        urllib.request.urlopen(req, timeout=15).read()
    except Exception:
        pass

def _call_provider(provider, key, model, prompt):
    cfg = PROVIDERS[provider]
    if provider == "anthropic":
        data = json.dumps({
            "model": model, "max_tokens": 1024,
            "system": "You build Control Launcher modules. Return JSON only.",
            "messages": [{"role": "user", "content": json.dumps(prompt)}]
        }).encode()
        req = urllib.request.Request(cfg["base"] + "/messages", data=data,
            headers={"x-api-key": key, "anthropic-version": "2023-06-01",
                     "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=60) as r:
            out = json.loads(r.read())
        text = "".join(b.get("text", "") for b in out.get("content", []) if b.get("type") == "text")
        return json.loads(text)
    data = json.dumps({
        "model": model,
        "messages": [
            {"role": "system", "content": "You build Control Launcher modules. Return JSON only."},
            {"role": "user", "content": json.dumps(prompt)}
        ],
        "response_format": {"type": "json_object"}
    }).encode()
    req = urllib.request.Request(cfg["base"] + "/chat/completions", data=data,
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        out = json.loads(r.read())
    return json.loads(out["choices"][0]["message"]["content"])

def ai_module(p, existing):
    # Rotation across providers, keys, and models: repo-secret
    # keys first, then the shared pool. Any failure (out of
    # usage, refused, bad model) moves to the next candidate.
    attempts = []  # (provider, key, key_id_or_None)
    env_keys = {
        "openai": _split(os.environ.get("OPENAI_API_KEYS", "")) + _split(os.environ.get("OPENAI_API_KEY", "")),
        "xai": _split(os.environ.get("XAI_API_KEYS", "")),
        "perplexity": _split(os.environ.get("PERPLEXITY_API_KEYS", "")),
        "anthropic": _split(os.environ.get("ANTHROPIC_API_KEYS", "")),
        "meta": _split(os.environ.get("META_API_KEYS", "")),
        "google": _split(os.environ.get("GOOGLE_API_KEYS", "")),
    }
    for provider, keys in env_keys.items():
        for k in keys:
            attempts.append((provider, k, None))
    for entry in _pool_keys():
        if entry.get("provider") in PROVIDERS and entry.get("key"):
            attempts.append((entry["provider"], entry["key"], entry.get("id")))
    if not attempts:
        return None
    prompt = {
        "request": p,
        "existingModule": existing,
        "rules": [
            "Return only JSON for one Control module.",
            "Data module fields: id, name, version, description, effects.",
            "effects may only contain browserHomepage (https URL), mutedApps (package names), channelLabels (keys 1-50), dock (bool), popupApps (bool), widgets (bool).",
            "Code module alternative: return {\"codeModule\": {\"id\", \"name\", \"version\", \"description\", \"entryClass\", \"capabilities\", \"source\"}} where source is one Java class implementing app.control.launcher.moduleapi.ControlModule against ModuleApi only (registerSettingsSwitch, registerSettingsRow, addHomeSection, getPref/putPref/getBool/putBool, toast, openUrl, themeColor, refreshHome). capabilities is a subset of settings, home, prefs, toast, browser, theme.",
            "Code module source may import only app.control.launcher.moduleapi.*, android.content.Context, android.view.View, android.view.ViewGroup, android.widget.TextView, android.widget.LinearLayout, android.widget.Button, and java.util.*. No reflection, class loaders, files, network, intents, or system services.",
            "Never include scripts, URLs other than browserHomepage, or unsafe effects.",
            "For updates, keep the same id and bump the patch version.",
            "If the request cannot be done with the allowed effects or the ModuleApi surface, return {\"needsUpdate\": true} instead of an empty module."
        ]
    }
    for provider, key, key_id in attempts:
        models = _split(os.environ.get(provider.upper() + "_MODELS", "")) or PROVIDERS[provider]["models"]
        for model in models:
            try:
                result = _call_provider(provider, key, model, prompt)
                _report(key_id, True)
                return result
            except Exception:
                continue
        _report(key_id, False)
    return None

def fallback(p, existing):
    if existing:
        m = dict(existing)
        parts = str(m.get("version", "1.0.0")).split(".")
        try:
            parts[-1] = str(int(parts[-1]) + 1)
            m["version"] = ".".join(parts)
        except Exception:
            m["version"] = "1.0.1"
        detail = p.get("details", "")
        if detail:
            m["description"] = (m.get("description", "") + " Update: " + detail).strip()
            cm = re.search(r"channel\s*(\d+)\s*(?:should be labeled|labeled|label|called|named)\s+([A-Za-z0-9 _-]+)", detail, re.I) or re.search(r"(?:label|name|call)\s+channel\s*(\d+)\s+([A-Za-z0-9 _-]+)", detail, re.I)
            if cm:
                effects = dict(m.get("effects") or {})
                labels = dict(effects.get("channelLabels") or {})
                labels[cm.group(1)] = cm.group(2).strip()
                effects["channelLabels"] = labels
                m["effects"] = effects
        return m
    desc = p.get("description", "Requested module")
    mid = slug(desc)
    effects = {}
    low = desc.lower()
    if "dock" in low: effects["dock"] = True
    if "popup" in low: effects["popupApps"] = True
    if "widget" in low: effects["widgets"] = True
    cm = re.search(r"channel\s*(\d+)\s*(?:should be labeled|labeled|label|called|named)\s+([A-Za-z0-9 _-]+)", desc, re.I) or re.search(r"(?:label|name|call)\s+channel\s*(\d+)\s+([A-Za-z0-9 _-]+)", desc, re.I)
    if cm:
        effects["channelLabels"] = {cm.group(1): cm.group(2).strip()}
    return {"id": mid, "name": desc[:60] or mid, "version": "1.0.0",
            "description": desc, "effects": effects}

def usage_for(m):
    effects = m.get("effects") or {}
    parts = []
    if effects.get("dock"):
        parts.append("Install it and a dock appears at the bottom of the screen. Tap an icon to open or minimize the app; long-press to close it.")
    if effects.get("widgets"):
        parts.append("Install it, then on the home screen tap Add widget and pick a widget. Long-press a widget to remove it.")
    if effects.get("popupApps"):
        parts.append("Apps opened from the dock appear in a popup window.")
    if effects.get("channelLabels"):
        parts.append("Install it and the walkie channels get their labels.")
    if effects.get("mutedApps"):
        parts.append("Install it and the listed apps are muted.")
    if effects.get("browserHomepage"):
        parts.append("Install it and the browser homepage changes.")
    if not parts:
        parts.append("Install it from Modules; it takes effect right away.")
    return " ".join(parts)

def validate(m):
    if not isinstance(m, dict): raise ValueError("module is not an object")
    if m.get("needsUpdate"): raise NeedsUpdate("request needs a Control update")
    mid = slug(str(m.get("id", "")))
    if not mid: raise ValueError("module has no id")
    effects = m.get("effects") or {}
    if not isinstance(effects, dict): raise ValueError("effects is not an object")
    clean = {}
    for k, v in effects.items():
        if k not in ALLOWED_EFFECTS: continue
        if k in ("dock", "popupApps", "widgets") and isinstance(v, bool):
            clean[k] = v
        elif k == "browserHomepage" and isinstance(v, str) and v.startswith("https://"):
            clean[k] = v
        elif k == "mutedApps" and isinstance(v, list):
            clean[k] = [x for x in v if isinstance(x, str) and re.fullmatch(r"[A-Za-z0-9_.]+", x)]
        elif k == "channelLabels" and isinstance(v, dict):
            clean[k] = {str(kk): str(vv)[:40] for kk, vv in v.items() if str(kk).isdigit() and 1 <= int(kk) <= 50}
    clean = _effective(clean)
    if not clean:
        raise NeedsUpdate("no allowed effect applies to this request")
    return {"id": mid, "name": str(m.get("name", mid))[:80],
            "version": str(m.get("version", "1.0.0"))[:20],
            "description": str(m.get("description", ""))[:500], "effects": clean}

CODE_CAPABILITIES = {"settings", "home", "prefs", "toast", "browser", "theme"}
CODE_FORBIDDEN = ["reflect", "ClassLoader", "DexClassLoader", "loadClass", "forName",
    "Runtime.", "ProcessBuilder", "java.io", "java.net", "android.app", "android.os",
    "Intent", "getSystemService", "SharedPreferences", "ContentResolver", "Settings.",
    "PackageManager", "Thread", "System.exit", "native "]
CODE_ALLOWED_IMPORTS = ("app.control.launcher.moduleapi.", "android.content.Context",
    "android.view.View", "android.view.ViewGroup", "android.widget.TextView",
    "android.widget.LinearLayout", "android.widget.Button", "java.util.")

def _builder_dir():
    return Path(__file__).resolve().parent

def _find_d8():
    env = os.environ.get("D8", "")
    if env: return env.split()
    home = os.environ.get("ANDROID_HOME", "")
    if home:
        hits = sorted(Path(home).glob("build-tools/*/d8"))
        if hits: return [str(hits[-1])]
    r8 = Path.home() / "workspace/native-build/r8/r8-8.7.18.jar"
    if r8.exists(): return ["java", "-cp", str(r8), "com.android.tools.r8.D8"]
    return None

def _android_lib():
    env = os.environ.get("ANDROID_JAR", "")
    if env and Path(env).exists(): return env
    home = os.environ.get("ANDROID_HOME", "")
    if home:
        hits = sorted(Path(home).glob("platforms/*/android.jar"))
        if hits: return str(hits[-1])
    return str(_builder_dir() / "android-stubs.jar")

def build_code_module(cm):
    """Compile + dex + sign one AI code module. Returns
    (manifest_dict, dex_bytes). Raises NeedsUpdate for
    anything outside the ModuleApi surface; nothing
    publishes unless the source compiles and signs."""
    if not isinstance(cm, dict): raise NeedsUpdate("code module is not an object")
    mid = slug(str(cm.get("id", "")))
    entry = str(cm.get("entryClass", ""))
    source = str(cm.get("source", ""))
    caps = [c for c in (cm.get("capabilities") or []) if c in CODE_CAPABILITIES]
    if not mid or not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)+", entry):
        raise NeedsUpdate("code module has no valid entry class")
    if "implements ControlModule" not in source and "implements app.control.launcher.moduleapi.ControlModule" not in source:
        raise NeedsUpdate("code module does not implement ControlModule")
    for bad in CODE_FORBIDDEN:
        if bad in source:
            raise NeedsUpdate("code module needs API surface that does not exist: " + bad)
    for imp in re.findall(r"import\s+([\w.]+);", source):
        if not imp.startswith(CODE_ALLOWED_IMPORTS):
            raise NeedsUpdate("code module import outside the API surface: " + imp)
    api_jar = _builder_dir() / "control-module-api.jar"
    if not api_jar.exists(): raise NeedsUpdate("module API jar is missing")
    d8 = _find_d8()
    if not d8: raise NeedsUpdate("dex tooling is not available")
    pkg = entry.rsplit(".", 1)[0]
    cls = entry.rsplit(".", 1)[1]
    with tempfile.TemporaryDirectory() as td:
        src_dir = Path(td) / "src" / Path(*pkg.split("."))
        src_dir.mkdir(parents=True)
        (src_dir / (cls + ".java")).write_text(source)
        classes = Path(td) / "classes"; classes.mkdir()
        lib = _android_lib()
        r = subprocess.run(["javac", "-encoding", "UTF-8", "--release", "17",
            "-cp", str(api_jar) + os.pathsep + lib, "-d", str(classes),
            str(src_dir / (cls + ".java"))], capture_output=True, text=True)
        if r.returncode != 0:
            raise NeedsUpdate("code module does not compile against the ModuleApi")
        dex_dir = Path(td) / "dex"; dex_dir.mkdir()
        class_files = [str(p) for p in classes.rglob("*.class")]
        r = subprocess.run(d8 + ["--min-api", "26", "--lib", lib,
            "--output", str(dex_dir)] + class_files, capture_output=True, text=True)
        dex_file = dex_dir / "classes.dex"
        if r.returncode != 0 or not dex_file.exists():
            raise NeedsUpdate("code module could not be dexed")
        dex = dex_file.read_bytes()
    version = str(cm.get("version", "1.0.0"))[:20]
    dex_sha = hashlib.sha256(dex).hexdigest()
    canonical = "control-code-module-v1\n%s\n%s\n%s\n1\n%s\n%s" % (
        mid, version, entry, dex_sha, ",".join(sorted(caps)))
    seed_hex = os.environ.get("CONTROL_MODULE_SIGNING_KEY", "").strip()
    if not seed_hex:
        raise NeedsUpdate("code module signing is not configured")
    try:
        import nacl.signing
        key = nacl.signing.SigningKey(bytes.fromhex(seed_hex))
        sig = key.sign(canonical.encode()).signature.hex()
    except Exception:
        raise NeedsUpdate("code module signing is not configured")
    manifest = {"id": mid, "name": str(cm.get("name", mid))[:80],
        "version": version, "description": str(cm.get("description", ""))[:500],
        "type": "code", "entryClass": entry, "apiVersion": 1,
        "capabilities": sorted(caps), "dexSha256": dex_sha, "signature": sig,
        "usage": "Install it from Modules; it takes effect right away. Remove it any time."}
    return manifest, dex

def main():
    p = payload()
    mid = slug(str(p.get("moduleId", ""))) if p.get("type") == "update" else ""
    existing = None
    if mid:
        f = ROOT / "modules" / mid / "module.json"
        if f.exists(): existing = json.loads(f.read_text())
    ai_result = ai_module(p, existing)
    if isinstance(ai_result, dict) and ai_result.get("codeModule"):
        try:
            manifest, dex = build_code_module(ai_result["codeModule"])
        except NeedsUpdate as e:
            Path("needs-update.txt").write_text(str(e) + "\n")
            print("NEEDS_UPDATE", e)
            return
        if mid: manifest["id"] = mid
        folder = ROOT / "modules" / manifest["id"]
        folder.mkdir(parents=True, exist_ok=True)
        raw = (json.dumps(manifest, indent=2) + "\n").encode()
        (folder / "module.json").write_bytes(raw)
        (folder / "classes.dex").write_bytes(dex)
        rebuild_index(manifest, raw)
        print("built code module", manifest["id"], manifest["version"],
              hashlib.sha256(raw).hexdigest())
        return
    try:
        m = validate(ai_result or fallback(p, existing))
    except NeedsUpdate as e:
        Path("needs-update.txt").write_text(str(e) + "\n")
        print("NEEDS_UPDATE", e)
        return
    if existing and _effective(existing.get("effects")) == m["effects"]:
        Path("needs-update.txt").write_text("the requested change does not change any module effect\n")
        print("NEEDS_UPDATE no-change")
        return
    if mid: m["id"] = mid
    m["usage"] = usage_for(m)
    folder = ROOT / "modules" / m["id"]
    folder.mkdir(parents=True, exist_ok=True)
    raw = (json.dumps(m, indent=2) + "\n").encode()
    (folder / "module.json").write_bytes(raw)
    sha = hashlib.sha256(raw).hexdigest()
    rebuild_index(m, raw)
    print("built", m["id"], m["version"], sha)

def rebuild_index(m, raw):
    # Rebuild the index from every module on disk so an entry
    # can never be lost by a racing or partial run. Code
    # modules count by type; data modules by effective effects.
    mods = []
    for mf in sorted((ROOT / "modules").glob("*/module.json")):
        try:
            other = json.loads(mf.read_text())
        except Exception:
            continue
        if other.get("id") and other["id"] != m["id"] and (
                other.get("type") == "code" or _effective(other.get("effects"))):
            oraw = mf.read_bytes()
            mods.append({"id": other["id"],
                         "name": other.get("name", other["id"]),
                         "version": other.get("version", ""),
                         "description": other.get("description", ""),
                         "usage": other.get("usage", ""),
                         "status": "completed",
                         "type": "code" if other.get("type") == "code" else "data",
                         "sha256": hashlib.sha256(oraw).hexdigest(),
                         "url": "https://raw.githubusercontent.com/doeyeknow/ControlLauncher/main/modules/"
                                + other["id"] + "/module.json"})
    mods.append({"id": m["id"], "name": m["name"], "version": m["version"],
        "description": m["description"], "usage": m.get("usage", ""),
        "status": "completed",
        "type": "code" if m.get("type") == "code" else "data",
        "sha256": hashlib.sha256(raw).hexdigest(),
        "url": "https://raw.githubusercontent.com/doeyeknow/ControlLauncher/main/modules/" + m["id"] + "/module.json"})
    mods.sort(key=lambda x: x["id"])
    (ROOT / "modules" / "index.json").write_text(
        json.dumps({"modules": mods}, indent=2) + "\n")

if __name__ == "__main__":
    main()
