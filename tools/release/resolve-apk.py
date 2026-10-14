#!/usr/bin/env python3
# Copyright (c) 2026 LatentJam Project
# SPDX-License-Identifier: Apache-2.0
"""Print the APK selected by AGP's current artifact metadata, without installing it."""
import argparse
import json
from pathlib import Path


def resolve(project, variant, abi):
    build = (project / "androidApp/build").resolve()
    redirect = build / f"intermediates/apk_ide_redirect_file/{variant}/create{variant.capitalize()}ApkListingFileRedirect/redirect.txt"
    if redirect.is_file():
        lines = redirect.read_text().splitlines()
        values = [line.split("=", 1)[1] for line in lines if line.startswith("listingFile=")]
        if len(values) != 1:
            raise ValueError(f"Invalid AGP artifact locator: {redirect}")
        listing = (redirect.parent / values[0]).resolve()
    else:
        listing = build / f"outputs/apk/{variant}/output-metadata.json"
    listing.relative_to(build)
    metadata = json.loads(listing.read_text())
    matches = [element for element in metadata["elements"] if
               any(f.get("filterType") == "ABI" and f.get("value") == abi for f in element.get("filters", []))]
    if not matches:
        matches = [element for element in metadata["elements"] if not element.get("filters")]
    if len(matches) != 1:
        raise ValueError(f"Expected one {abi} APK in {listing}; found {len(matches)}")
    apk = (listing.parent / matches[0]["outputFile"]).resolve()
    apk.relative_to(build)
    if apk.suffix != ".apk" or not apk.is_file():
        raise ValueError(f"APK artifact is missing: {apk}")
    return apk


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--variant", choices=["release", "debug"], default="release")
    parser.add_argument("--abi", default="arm64-v8a")
    args = parser.parse_args()
    try:
        print(resolve(args.project_root, args.variant, args.abi))
    except (OSError, ValueError, KeyError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
