#!/usr/bin/env python3
"""Update EmberTV's Aukkhor keyboard entry only from a verified signed APK."""
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

PACKAGE="dev.soum91.aukkhor.tv"
ICON="https://raw.githubusercontent.com/soum91/aukkhor/main/ios/Assets.xcassets/AppIcon.appiconset/AukkhorIcon.png"

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--apk",required=True,type=Path)
    parser.add_argument("--build",required=True,type=int)
    parser.add_argument("--url",required=True)
    args=parser.parse_args()
    raw=args.apk.read_bytes()
    if not raw or len(raw)>256*1024*1024:
        raise SystemExit("Missing or oversized signed APK")
    path=Path("repo.json")
    feed=json.loads(path.read_text(encoding="utf-8"))
    app=next((a for a in feed.get("apps",[]) if a.get("packageName")==PACKAGE),None)
    if app is None:
        app={
            "name":"Aukkhor TV Keyboard",
            "packageName":PACKAGE,
            "developerName":"soum91",
            "subtitle":"Type on iPhone when a TV text field opens",
            "localizedDescription":"Native Android TV D-pad keyboard and paired iPhone remote typing. English, phonetic Bangla and direct Bangla, secure local-network pairing.",
            "category":"utilities",
            "iconURL":ICON,
            "versions":[]
        }
    app["iconURL"]=ICON
    version=f"0.3.0.{args.build}"
    details="Aukkhor TV keyboard: D-pad typing, English/phonetic Bangla/direct Bangla layouts, secure paired iPhone keyboard prompt while Aukkhor is open, focused-field text insertion, password-field protection, approved fused A/অ app icon."
    item={
        "version":version,
        "buildVersion":str(args.build),
        "versionCode":args.build,
        "date":datetime.now(timezone.utc).date().isoformat(),
        "localizedDescription":details,
        "changelog":details,
        "downloadURL":args.url,
        "size":len(raw),
        "sha256":hashlib.sha256(raw).hexdigest()
    }
    app["versions"]=[item]+[v for v in app.get("versions",[]) if v.get("version")!=version][:29]
    feed["apps"]=[app]+[a for a in feed["apps"] if a.get("packageName")!=PACKAGE]
    path.write_text(json.dumps(feed,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print("Verified Aukkhor TV catalog entry "+version)

if __name__=="__main__":
    main()
