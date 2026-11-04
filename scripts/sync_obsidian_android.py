#!/usr/bin/env python3
"""Publish a verified Obsidian TV Companion release to EmberTV's main catalog."""

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


PACKAGE = "com.soum91.obsidian.tv"
ICON = (
    "https://raw.githubusercontent.com/soum91/phosphor/main/"
    "Phosphor/Assets.xcassets/AppIcon.appiconset/icon.png"
)
DESCRIPTION = (
    "Obsidian TV Companion beta. Pair your Sony or Android TV to the Obsidian "
    "iPhone Live tab by scanning the QR code on your TV. On the TV, tap Start "
    "Live View and approve Android's screen-capture prompt. Encrypted local "
    "preview and experimental remote controls; screen capture does not include "
    "DRM-protected content or external HDMI sources."
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--build", type=int, required=True)
    parser.add_argument("--url", required=True)
    args = parser.parse_args()

    if args.build < 1:
        raise SystemExit("Invalid version code")
    expected_url = (
        "https://github.com/soum91/embertv/releases/download/"
        f"obsidian-tv-v0.1.0.{args.build}/Obsidian-TV-Companion.apk"
    )
    if args.url != expected_url:
        raise SystemExit("Unexpected Obsidian TV Companion release URL")

    apk = args.apk.read_bytes()
    if not apk or len(apk) > 256 * 1024 * 1024:
        raise SystemExit("Missing or oversized APK")

    path = Path("repo.json")
    feed = json.loads(path.read_text(encoding="utf-8"))
    apps = feed.setdefault("apps", [])
    app = next((a for a in apps if a.get("packageName") == PACKAGE), None)
    if app is None:
        app = {
            "name": "Obsidian TV Companion",
            "packageName": PACKAGE,
            "developerName": "soum91",
            "subtitle": "Live TV screen viewing in Obsidian for iPhone",
            "localizedDescription": DESCRIPTION,
            "category": "utilities",
            "iconURL": ICON,
            "repositoryURL": "https://github.com/soum91/phosphor",
            "versions": [],
        }

    version = f"0.1.0.{args.build}"
    item = {
        "version": version,
        "buildVersion": str(args.build),
        "versionCode": args.build,
        "date": datetime.now(timezone.utc).date().isoformat(),
        "localizedDescription": DESCRIPTION,
        "changelog": DESCRIPTION,
        "downloadURL": args.url,
        "size": len(apk),
        "sha256": hashlib.sha256(apk).hexdigest(),
    }
    app["versions"] = [item] + [
        old for old in app.get("versions", [])
        if old.get("version") != version
    ][:29]
    app.update({
        "name": "Obsidian TV Companion",
        "iconURL": ICON,
        "localizedDescription": DESCRIPTION,
        "version": item["version"],
        "buildVersion": item["buildVersion"],
        "versionCode": item["versionCode"],
        "downloadURL": item["downloadURL"],
        "size": item["size"],
        "sha256": item["sha256"],
        "versionDate": item["date"],
        "versionDescription": DESCRIPTION,
    })
    feed["apps"] = [app] + [
        previous for previous in apps
        if previous.get("packageName") != PACKAGE
    ]
    path.write_text(
        json.dumps(feed, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Catalog now lists Obsidian TV Companion {version}")


if __name__ == "__main__":
    main()
