#!/usr/bin/env python3
"""Update EmberTV only from a verified, permanently signed BridgeDeck TV APK."""
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

PACKAGE = "com.soum91.bridgedeck.tv"
ICON = ("https://raw.githubusercontent.com/soum91/bridgedeck/main/"
        "ios/BridgeDeck/Assets.xcassets/AppIcon.appiconset/AppIcon.png")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--build", required=True, type=int)
    parser.add_argument("--url", required=True)
    args = parser.parse_args()
    raw = args.apk.read_bytes()
    if not raw or len(raw) > 256 * 1024 * 1024:
        raise SystemExit("Missing or oversized signed BridgeDeck TV APK")
    if not args.url.startswith("https://github.com/soum91/embertv/releases/download/bridgedeck-tv-v"):
        raise SystemExit("Unexpected Android TV release URL")
    path = Path("repo.json")
    feed = json.loads(path.read_text(encoding="utf-8"))
    apps = feed.setdefault("apps", [])
    app = next((a for a in apps if a.get("packageName") == PACKAGE), None)
    if app is None:
        app = {
            "name": "BridgeDeck TV",
            "packageName": PACKAGE,
            "developerName": "soum91",
            "subtitle": "Discover your TV from BridgeDeck on iPhone",
            "localizedDescription": "Background TV presence and discovery companion for BridgeDeck iPhone ADB manager. It cannot grant ADB authorization or execute privileged commands by itself.",
            "category": "utilities",
            "iconURL": ICON,
            "repositoryURL": "https://github.com/soum91/bridgedeck",
            "versions": [],
        }
    version = f"0.1.0.{args.build}"
    details = ("BridgeDeck TV presence companion: native Leanback interface, local-network "
               "discovery and status reporting, starts on boot when permitted. "
               "ADB functions require separately authorized ADB-over-TCP access.")
    item = {
        "version": version,
        "buildVersion": str(args.build),
        "versionCode": args.build,
        "date": datetime.now(timezone.utc).date().isoformat(),
        "localizedDescription": details,
        "changelog": details,
        "downloadURL": args.url,
        "size": len(raw),
        "sha256": hashlib.sha256(raw).hexdigest(),
    }
    app["versions"] = [item] + [
        v for v in app.get("versions", []) if v.get("version") != version
    ][:29]
    app["iconURL"] = ICON
    feed["apps"] = [app] + [a for a in apps if a.get("packageName") != PACKAGE]
    path.write_text(json.dumps(feed, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Verified signed BridgeDeck TV catalog entry " + version)


if __name__ == "__main__":
    main()
