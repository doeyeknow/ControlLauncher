#!/usr/bin/env python3
import hashlib, json, os, re, sys, urllib.request
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
            "system": "You build safe data-only Control Launcher modules. Return JSON only.",
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
            {"role": "system", "content": "You build safe data-only Control Launcher modules. Return JSON only."},
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
            "Fields: id, name, version, description, effects.",
            "effects may only contain browserHomepage (https URL), mutedApps (package names), channelLabels (keys 1-50), dock (bool), popupApps (bool), widgets (bool).",
            "Never include code, scripts, URLs other than browserHomepage, or unsafe effects.",
            "For updates, keep the same id and bump the patch version.",
            "If the request cannot be done with the allowed effects, return {\"needsUpdate\": true} instead of an empty module."
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

def main():
    p = payload()
    mid = slug(str(p.get("moduleId", ""))) if p.get("type") == "update" else ""
    existing = None
    if mid:
        f = ROOT / "modules" / mid / "module.json"
        if f.exists(): existing = json.loads(f.read_text())
    try:
        m = validate(ai_module(p, existing) or fallback(p, existing))
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
    index_path = ROOT / "modules" / "index.json"
    # Rebuild the index from every module on disk so an entry
    # can never be lost by a racing or partial run.
    mods = []
    for mf in sorted((ROOT / "modules").glob("*/module.json")):
        try:
            other = json.loads(mf.read_text())
        except Exception:
            continue
        if other.get("id") and other["id"] != m["id"] and _effective(other.get("effects")):
            raw = mf.read_bytes()
            mods.append({"id": other["id"],
                         "name": other.get("name", other["id"]),
                         "version": other.get("version", ""),
                         "description": other.get("description", ""),
                         "usage": other.get("usage", ""),
                         "status": "completed",
                         "sha256": hashlib.sha256(raw).hexdigest(),
                         "url": "https://raw.githubusercontent.com/doeyeknow/ControlLauncher/main/modules/"
                                + other["id"] + "/module.json"})
    mods.append({"id": m["id"], "name": m["name"], "version": m["version"],
        "description": m["description"], "usage": m["usage"],
        "status": "completed", "sha256": sha,
        "url": "https://raw.githubusercontent.com/doeyeknow/ControlLauncher/main/modules/" + m["id"] + "/module.json"})
    mods.sort(key=lambda x: x["id"])
    index_path.write_text(json.dumps({"modules": mods}, indent=2) + "\n")
    print("built", m["id"], m["version"], sha)

if __name__ == "__main__":
    main()
