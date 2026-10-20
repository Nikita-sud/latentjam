#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The AltStore source lists only published IPAs and matches the checked-out app."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("altstore_source", Path(__file__).with_name("altstore-source.py"))
altstore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(altstore)

PROJECT = Path(__file__).resolve().parents[2]
VERSION = altstore.project_setting(PROJECT / "iosApp/iosApp.xcodeproj/project.pbxproj", "MARKETING_VERSION")


def release(tag, date, assets, **extra):
    return {"tag_name": tag, "published_at": date, "body": f"**{tag}** fixes [#1](https://x/1).\n\n### Fixed\n- a",
            "assets": [{"name": name, "size": 10, "browser_download_url": f"https://dl/{name}"} for name in assets],
            **extra}


class AltStoreSourceTest(unittest.TestCase):
    def releases(self):
        return [
            release("v0.1.0", "2026-07-23T00:00:00Z", ["LatentJam-v0.1.0.apk"]),
            release(f"v{VERSION}", "2026-10-16T00:00:00Z", [f"LatentJam-v{VERSION}-arm64.apk", "new.ipa"]),
            release("v0.2.0", "2026-08-15T00:00:00Z", ["old.ipa"]),
            release("v9.9.9", "2026-12-01T00:00:00Z", ["draft.ipa"], draft=True),
        ]

    def test_newest_release_first_and_releases_without_ipa_skipped(self):
        app = altstore.source(PROJECT, self.releases())["apps"][0]
        self.assertEqual([VERSION, "0.2.0"], [v["version"] for v in app["versions"]])
        self.assertEqual("https://dl/new.ipa", app["downloadURL"])
        self.assertEqual(VERSION, app["version"])

    def test_only_newest_version_claims_checkout_build_settings(self):
        newest, older = altstore.source(PROJECT, self.releases())["apps"][0]["versions"]
        self.assertIn("minOSVersion", newest)
        self.assertIn("buildVersion", newest)
        self.assertNotIn("minOSVersion", older)
        self.assertNotIn("buildVersion", older)

    def test_description_is_plain_first_paragraph(self):
        newest = altstore.source(PROJECT, self.releases())["apps"][0]["versions"][0]
        self.assertEqual(f"v{VERSION} fixes #1.", newest["localizedDescription"])

    def test_privacy_strings_come_from_info_plist(self):
        privacy = altstore.source(PROJECT, self.releases())["apps"][0]["appPermissions"]["privacy"]
        self.assertIn("NSAppleMusicUsageDescription", privacy)

    def test_checkout_behind_newest_release_fails(self):
        releases = self.releases() + [release("v99.0.0", "2027-01-01T00:00:00Z", ["future.ipa"])]
        with self.assertRaisesRegex(ValueError, "MARKETING_VERSION"):
            altstore.source(PROJECT, releases)

    def test_two_ipas_in_one_release_fail(self):
        releases = [release(f"v{VERSION}", "2026-10-16T00:00:00Z", ["a.ipa", "b.ipa"])]
        with self.assertRaisesRegex(ValueError, "2 IPAs"):
            altstore.source(PROJECT, releases)


if __name__ == "__main__":
    unittest.main()
