#!/usr/bin/env python3
# Copyright (c) 2026 LatentJam Project
# SPDX-License-Identifier: Apache-2.0
"""Write the AltStore/SideStore source that lists the IPAs attached to the GitHub releases."""
import argparse
import json
import plistlib
import re
import subprocess
from pathlib import Path

REPO = "Nikita-sud/latentjam"
RAW = f"https://raw.githubusercontent.com/{REPO}/main"
BUNDLE_ID = "io.github.nikitasud.latentjam.kmp"
TINT = "8E24AA"


def project_setting(pbxproj, key):
    values = set(re.findall(rf"\b{key} = ([^;]+);", pbxproj.read_text()))
    if len(values) != 1:
        raise ValueError(f"Expected one {key} in {pbxproj}; found {sorted(values)}")
    return values.pop().strip('"')


def plain(markdown):
    """First paragraph of a release body, without Markdown emphasis or link targets."""
    paragraph = markdown.replace("\r\n", "\n").strip().split("\n\n", 1)[0]
    paragraph = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", paragraph)
    paragraph = re.sub(r"(\*\*|__|`)", "", paragraph)
    return " ".join(paragraph.split())


def newest_tag(releases):
    published = [r for r in releases if not r.get("draft") and not r.get("prerelease")
                 and any(a["name"].endswith(".ipa") for a in r.get("assets", []))]
    return max(published, key=lambda r: r["published_at"])["tag_name"].removeprefix("v") if published else None


def versions(releases, min_os, build):
    entries = []
    for release in releases:
        if release.get("draft") or release.get("prerelease"):
            continue
        ipas = [a for a in release.get("assets", []) if a["name"].endswith(".ipa")]
        if not ipas:
            continue
        if len(ipas) != 1:
            raise ValueError(f"{release['tag_name']} has {len(ipas)} IPAs")
        entries.append({
            "version": release["tag_name"].removeprefix("v"),
            "date": release["published_at"],
            "localizedDescription": plain(release.get("body") or ""),
            "downloadURL": ipas[0]["browser_download_url"],
            "size": ipas[0]["size"],
        })
    entries.sort(key=lambda e: e["date"], reverse=True)
    if not entries:
        raise ValueError("No published release has an IPA")
    # Only the newest build's number and deployment target are known from the checkout.
    entries[0]["buildVersion"] = build
    entries[0]["minOSVersion"] = min_os
    return entries


def source(project, releases):
    ios = project / "iosApp"
    info = plistlib.loads((ios / "Info.plist").read_bytes())
    pbxproj = ios / "iosApp.xcodeproj/project.pbxproj"
    bundle_id = project_setting(pbxproj, "PRODUCT_BUNDLE_IDENTIFIER")
    if bundle_id != BUNDLE_ID:
        raise ValueError(f"Bundle identifier changed to {bundle_id}; update {Path(__file__).name}")
    if releases and project_setting(pbxproj, "MARKETING_VERSION") != newest_tag(releases):
        raise ValueError("The checkout's MARKETING_VERSION is not the newest release; run this on the released commit")
    entries = versions(releases, project_setting(pbxproj, "IPHONEOS_DEPLOYMENT_TARGET"),
                       project_setting(pbxproj, "CURRENT_PROJECT_VERSION"))
    metadata = project / "fastlane/metadata/android/en-US"
    icon = f"{RAW}/fastlane/metadata/android/en-US/images/icon.png"
    latest = entries[0]
    app = {
        "name": info["CFBundleDisplayName"],
        "bundleIdentifier": bundle_id,
        "developerName": "LatentJam Project",
        "subtitle": (metadata / "short_description.txt").read_text().strip(),
        "localizedDescription": re.sub(r"<[^>]+>", "", (metadata / "full_description.txt").read_text()).strip(),
        "iconURL": icon,
        "tintColor": TINT,
        "category": "entertainment",
        "versions": entries,
        "appPermissions": {
            "entitlements": [],
            "privacy": {k: v for k, v in info.items() if k.startswith("NS") and k.endswith("UsageDescription")},
        },
        # Older AltStore and SideStore builds read the newest version from these fields.
        "version": latest["version"],
        "versionDate": latest["date"],
        "versionDescription": latest["localizedDescription"],
        "downloadURL": latest["downloadURL"],
        "size": latest["size"],
    }
    return {
        "name": "LatentJam",
        "identifier": f"{bundle_id}.source",
        "subtitle": "Official source for the LatentJam iPhone app",
        "description": "Unsigned IPAs from the LatentJam GitHub releases. AltStore or SideStore signs "
                       "them with your Apple ID; nothing is rebuilt or modified.",
        "iconURL": icon,
        "website": "https://latentjam.com",
        "tintColor": TINT,
        "apps": [app],
        "news": [],
    }


def github_releases():
    out = subprocess.run(["gh", "api", "--paginate", "--slurp", f"repos/{REPO}/releases"],
                         check=True, capture_output=True, text=True).stdout
    return [release for page in json.loads(out) for release in page]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--releases", type=Path, help="GitHub releases JSON instead of calling gh")
    parser.add_argument("--output", type=Path, help="defaults to website/altstore.json")
    args = parser.parse_args()
    releases = json.loads(args.releases.read_text()) if args.releases else github_releases()
    output = args.output or args.project / "website/altstore.json"
    written = source(args.project, releases)
    output.write_text(json.dumps(written, indent=2, ensure_ascii=False) + "\n")
    print(f"{output}: {len(written['apps'][0]['versions'])} versions, newest {written['apps'][0]['version']}")


if __name__ == "__main__":
    main()
