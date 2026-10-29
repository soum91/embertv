#!/usr/bin/env python3
"""Update public catalog only after a corresponding GitHub Release exists."""
import argparse
import hashlib
import json
from datetime import date
from pathlib import Path


def main():
    p = argparse.ArgumentParser()
    p.add_argument("platform", choices=["tv", "ios"])
    p.add_argument("--version", required=True)
    p.add_argument("--build", type=int, required=True)
    p.add_argument("--url", required=True)
    p.add_argument("--artifact", type=Path, required=True)
    p.add_argument("--notes", type=Path, required=True)
    args = p.parse_args()
    path = Path("repo.json" if args.platform == "tv" else "source.json")
    with path.open(encoding="utf-8") as f:
        feed = json.load(f)
    icon_url = "https://raw.githubusercontent.com/soum91/embertv/main/assets/embertv-flame-tv-icon.png"
    feed["iconURL"] = icon_url
    notes = args.notes.read_text(encoding="utf-8").strip() or "Maintenance release"
    item = {
        "version": args.version,
        "buildVersion": str(args.build),
        "date": date.today().isoformat(),
        "localizedDescription": notes,
        "changelog": notes,
        "downloadURL": args.url,
        "size": args.artifact.stat().st_size,
    }
    if args.platform == "tv":
        item["versionCode"] = args.build
        item["sha256"] = hashlib.sha256(args.artifact.read_bytes()).hexdigest()
        base = {
            "name": "EmberTV",
            "packageName": "com.soum91.embertv",
            "developerName": "soum91",
            "subtitle": "GitHub APK browser and installer for Android TV",
            "localizedDescription": "Browse JSON catalogs, install signed APKs, and pair an iPhone companion.",
            "category": "utilities",
            "versions": [],
        }
    else:
        item["minOSVersion"] = "16.0"
        base = {
            "name": "EmberTV Companion",
            "bundleIdentifier": "com.soum91.embertv.companion",
            "developerName": "soum91",
            "subtitle": "Send GitHub APKs to your Android TV",
            "localizedDescription": "Browse APK feeds and pair with EmberTV on a local Wi-Fi network.",
            "category": "utilities",
            "versions": [],
        }
    for existing in feed["apps"]:
        key = "packageName" if args.platform == "tv" else "bundleIdentifier"
        if existing.get(key) == base[key]:
            base = existing
            break
    base["iconURL"] = icon_url
    old_versions = [v for v in base.get("versions", []) if v.get("version") != args.version]
    base["versions"] = [item] + old_versions[:29]
    feed["apps"] = [base] + [a for a in feed["apps"] if a.get("packageName" if args.platform == "tv" else "bundleIdentifier") != base["packageName" if args.platform == "tv" else "bundleIdentifier"]]
    path.write_text(json.dumps(feed, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
