#!/usr/bin/env python3
import hashlib, json, os, re, sys, urllib.request
from pathlib import Path

ROOT = Path.cwd()
ALLOWED_EFFECTS = {"browserHomepage", "mutedApps", "channelLabels", "dock", "popupApps", "widgets"}

def slug(text):
    s = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return (s[:48] or "module").strip("-")

def payload():
    body = os.environ.get("ISSUE_BODY", "")
    m = re.search(r"```json\s*(\{.*?\})\s*```", body, re.S)
    if m:
        return json.loads(m.group(1))
    return {"type": "create", "description": body.strip()}

def ai_module(p, existing):
    key = os.environ.get("OPENAI_API_KEY", "")
    if not key:
        return None
    prompt = {
        "request": p,
        "existingModule": existing,
        "rules": [
            "Return only JSON for one Control module.",
            "Fields: id, name, version, description, effects.",
            "effects may only contain browserHomepage (https URL), mutedApps (package names), channelLabels (keys 1-50).",
            "For updates, keep the same id and bump the patch version."
        ]
    }
    data = json.dumps({
        "model": os.environ.get("MODULE_AI_MODEL", "gpt-4o-mini"),
        "messages": [
            {"role": "system", "content": "You build safe data-only Control Launcher modules. Return JSON only."},
            {"role": "user", "content": json.dumps(prompt)}
        ],
        "response_format": {"type": "json_object"}
    }).encode()
    req = urllib.request.Request("https://api.openai.com/v1/chat/completions", data=data,
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        out = json.loads(r.read())
    return json.loads(out["choices"][0]["message"]["content"])

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
            cm = re.search(r"channel\s*(\d+)\s*(?:should be labeled|labeled|label)\s+([A-Za-z0-9 _-]+)", detail, re.I)
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
    m = validate(ai_module(p, existing) or fallback(p, existing))
    if mid: m["id"] = mid
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
        if other.get("id") and other["id"] != m["id"]:
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
        "description": m["description"], "sha256": sha,
        "url": "https://raw.githubusercontent.com/doeyeknow/ControlLauncher/main/modules/" + m["id"] + "/module.json"})
    mods.sort(key=lambda x: x["id"])
    index_path.write_text(json.dumps({"modules": mods}, indent=2) + "\n")
    print("built", m["id"], m["version"], sha)

if __name__ == "__main__":
    main()
