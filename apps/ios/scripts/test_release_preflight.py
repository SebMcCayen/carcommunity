"""Regression checks for preflight failures that can block signed releases."""
import importlib.util
import json
from pathlib import Path
import plistlib
import shutil
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("release-preflight.py")
SPEC = importlib.util.spec_from_file_location("release_preflight", SCRIPT)
preflight = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preflight)


class ReleasePreflightTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        source = SCRIPT.parents[1]
        for name in ("project.yml", "KCC/Info.plist", "KCC/KCC.entitlements",
                     "KCC/Resources/PrivacyInfo.xcprivacy", "KCC/Updates/changelog.json",
                     "KCC/Resources/Localizable.xcstrings"):
            dest = self.root / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / name, dest)

    def test_source_checks_allow_missing_credentials_but_release_requires_them(self):
        bundle = preflight.check_source(self.root)
        with self.assertRaisesRegex(preflight.PreflightError, "GoogleService-Info.plist"):
            preflight.check_release(self.root, bundle, {})

    def test_missing_apple_entitlement_is_rejected(self):
        path = self.root / "KCC/KCC.entitlements"
        data = plistlib.loads(path.read_bytes())
        del data["com.apple.developer.applesignin"]
        path.write_bytes(plistlib.dumps(data))
        with self.assertRaisesRegex(preflight.PreflightError, "Sign in with Apple"):
            preflight.check_source(self.root)

    def test_shipping_version_without_matching_changelog_is_rejected(self):
        path = self.root / "KCC/Updates/changelog.json"
        data = json.loads(path.read_text())
        data["entries"][0]["versionName"] = "999.0.0"
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(preflight.PreflightError, "Changelog version"):
            preflight.check_source(self.root)

    def test_missing_changelog_translation_is_rejected(self):
        notes = json.loads((self.root / "KCC/Updates/changelog.json").read_text())
        key = notes["entries"][0]["highlightKeys"][0]
        path = self.root / "KCC/Resources/Localizable.xcstrings"
        data = json.loads(path.read_text())
        del data["strings"][key]["localizations"]["en"]
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(preflight.PreflightError, "en translation"):
            preflight.check_source(self.root)

    def test_missing_privacy_reason_is_rejected(self):
        path = self.root / "KCC/Resources/PrivacyInfo.xcprivacy"
        data = plistlib.loads(path.read_bytes())
        data["NSPrivacyAccessedAPITypes"] = []
        path.write_bytes(plistlib.dumps(data))
        with self.assertRaisesRegex(preflight.PreflightError, "Privacy manifest lacks required reasons"):
            preflight.check_source(self.root)

    def test_release_rejects_wrong_firebase_bundle_before_checking_other_inputs(self):
        path = self.root / "KCC/GoogleService-Info.plist"
        path.write_bytes(plistlib.dumps({"BUNDLE_ID": "invalid.test"}))
        with self.assertRaisesRegex(preflight.PreflightError, "BUNDLE_ID"):
            preflight.check_release(self.root, preflight.check_source(self.root), {})

    def test_release_rejects_private_mapbox_token_without_echoing_it(self):
        # Synthetic values exist only in this temporary fixture and never ship.
        bundle = preflight.check_source(self.root)
        path = self.root / "KCC/GoogleService-Info.plist"
        path.write_bytes(plistlib.dumps({
            "BUNDLE_ID": bundle, "GOOGLE_APP_ID": "test-app", "PROJECT_ID": "test-project",
            "API_KEY": "test-key", "GCM_SENDER_ID": "test-sender"
        }))
        environment = {"DEVELOPMENT_TEAM": "TESTTEAM00", "MAPBOX_ACCESS_TOKEN": "sk.test-private-value"}
        with self.assertRaises(preflight.PreflightError) as caught:
            preflight.check_release(self.root, bundle, environment)
        self.assertNotIn(environment["MAPBOX_ACCESS_TOKEN"], str(caught.exception))
        environment["MAPBOX_ACCESS_TOKEN"] = "pk.test-public-runtime-value"
        preflight.check_release(self.root, bundle, environment)


if __name__ == "__main__":
    unittest.main()
