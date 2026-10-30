#!/usr/bin/env python3
"""Add only signed Drift Android TV releases to EmberTV's public catalog."""

import hashlib
import json
import os
import re
import subprocess
import tempfile
import urllib.request
from pathlib import Path


DRIFT_RELEASES = "https://api.github.com/repos/soum91/embertv/releases?per_page=100"
# Drift v0.1.1+ is signed by the existing, permanent EmberTV release key.
# Legacy Drift build 2 used a different certificate and needs one uninstall.
DRIFT_CERT_SHA256 = "bc429067869a9bf11f16233dd29c54fe443290c4a76652a1d7ed1b2f3a15dfd2"
DRIFT_PACKAGE = "dev.soum91.drift"
TAG_PATTERN = re.compile(r"^android-v(\d+\.\d+\.\d+)\.(\d+)$")
APK_ASSET = "Drift-AndroidTV.apk"
MAX_APK_SIZE = 256 * 1024 * 1024


def request_json(url):
    headers = {"User-Agent": "EmberTV-Drift-Catalog", "Accept": "application/vnd.github+json"}
    token = os.environ.get("GITHUB_TOKEN", "")
    if token:
        headers["Authorization"] = "Bearer " + token
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as response:
        return json.load(response)


def run_tool(command):
    result = subprocess.run(command, check=True, text=True, capture_output=True)
    return result.stdout


def validate_apk(apk_path, expected_build):
    sdk = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "35.0.0"
    signature = run_tool([str(sdk / "apksigner"), "verify", "--verbose", "--print-certs", str(apk_path)])
    fingerprints = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9A-Fa-f]+)", signature)
    if [value.lower() for value in fingerprints] != [DRIFT_CERT_SHA256]:
        raise RuntimeError("Drift release certificate does not match the permanent EmberTV signing key")
    manifest = run_tool([str(sdk / "aapt2"), "dump", "badging", str(apk_path)])
    package = re.search(r"package:\s+name='([^']+)'\s+versionCode='(\d+)'", manifest)
    if not package or package.group(1) != DRIFT_PACKAGE or int(package.group(2)) != expected_build:
        raise RuntimeError("Drift release package name or versionCode does not match its release tag")


def main():
    releases = request_json(DRIFT_RELEASES)
    candidates = []
    for release in releases:
        match = TAG_PATTERN.fullmatch(release.get("tag_name", ""))
        if release.get("draft") or release.get("prerelease") or not match:
            continue
        assets = [asset for asset in release.get("assets", []) if asset.get("name") == APK_ASSET]
        if assets:
            candidates.append((int(match.group(2)), match.group(1), release, assets[0]))

    if not candidates:
        print("No EmberTV-signed Drift Android TV GitHub Release yet; catalog unchanged.")
        return

    build, base_version, release, asset = max(candidates, key=lambda row: row[0])
    path = Path("repo.json")
    feed = json.loads(path.read_text(encoding="utf-8"))
    entry = next((app for app in feed.get("apps", []) if app.get("packageName") == DRIFT_PACKAGE), None)
    if entry is None:
        raise RuntimeError("EmberTV catalog has no Drift entry; refusing to change unrelated apps")
    if build <= max((int(v.get("versionCode", 0)) for v in entry.get("versions", [])), default=0):
        print("EmberTV already lists this Drift Android TV version or newer.")
        return

    if not 0 < asset.get("size", 0) <= MAX_APK_SIZE:
        raise RuntimeError("Drift APK release asset has an unexpected size")
    url = asset["browser_download_url"]
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "EmberTV-Drift-Catalog"}), timeout=120) as response:
        apk_bytes = response.read(MAX_APK_SIZE + 1)
    if len(apk_bytes) > MAX_APK_SIZE or len(apk_bytes) != asset["size"]:
        raise RuntimeError("Drift APK downloaded size does not match the release metadata")

    with tempfile.TemporaryDirectory(prefix="embertv-drift-") as folder:
        apk = Path(folder) / APK_ASSET
        apk.write_bytes(apk_bytes)
        validate_apk(apk, build)

    description = (
        "Signed Drift Android TV file receiver. Supports iPhone/Windows companion transfers "
        "on trusted local Wi-Fi, Wi-Fi Direct and compatible Bluetooth LE. Native AirDrop is not implemented."
    )
    version = f"{base_version}.{build}"
    item = {
        "version": version,
        "versionCode": build,
        "buildVersion": str(build),
        "date": (release.get("published_at") or "")[:10],
        "localizedDescription": description,
        "changelog": description,
        "downloadURL": url,
        "size": len(apk_bytes),
        "sha256": hashlib.sha256(apk_bytes).hexdigest(),
    }
    entry["subtitle"] = "Direct file transfer to Android TV"
    entry["localizedDescription"] = description
    previous = [v for v in entry.get("versions", []) if int(v.get("versionCode", 0)) != build]
    entry["versions"] = [item] + previous[:29]
    path.write_text(json.dumps(feed, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"EmberTV catalog updated to verified Drift Android TV {version}.")


if __name__ == "__main__":
    main()
