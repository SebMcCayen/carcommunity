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
PREP_SPEC = importlib.util.spec_from_file_location("prepare_testflight", SCRIPT.with_name("prepare-testflight.py"))
prepare = importlib.util.module_from_spec(PREP_SPEC)
PREP_SPEC.loader.exec_module(prepare)


class ReleasePreflightTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        source = SCRIPT.parents[1]
        for name in ("project.yml", "KCC/Info.plist", "KCC/KCC.entitlements",
                     "KCC/Resources/PrivacyInfo.xcprivacy", "KCC/Updates/changelog.json",
                     "KCC/Resources/Localizable.xcstrings",
                     "KCC/Assets.xcassets/AppIcon.appiconset/Contents.json",
                     "KCC/Assets.xcassets/AppIcon.appiconset/AppIcon.png"):
            dest = self.root / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / name, dest)

    def test_missing_icon_is_rejected_before_upload(self):
        (self.root / "KCC/Assets.xcassets/AppIcon.appiconset/AppIcon.png").unlink()
        with self.assertRaisesRegex(preflight.PreflightError, "AppIcon"):
            preflight.check_source(self.root)

    def test_missing_orientations_are_rejected_before_upload(self):
        path = self.root / "KCC/Info.plist"
        data = plistlib.loads(path.read_bytes())
        data.pop("UISupportedInterfaceOrientations", None)
        data.pop("UISupportedInterfaceOrientations~ipad", None)
        path.write_bytes(plistlib.dumps(data))
        with self.assertRaisesRegex(preflight.PreflightError, "interface orientations"):
            preflight.check_source(self.root)

    def test_portrait_only_ipad_is_rejected_for_multitasking(self):
        path = self.root / "KCC/Info.plist"
        data = plistlib.loads(path.read_bytes())
        data["UISupportedInterfaceOrientations~ipad"] = ["UIInterfaceOrientationPortrait"]
        path.write_bytes(plistlib.dumps(data))
        with self.assertRaisesRegex(preflight.PreflightError, "iPad multitasking"):
            preflight.check_source(self.root)

    def test_universal_orientations_without_ipad_override_are_valid(self):
        path = self.root / "KCC/Info.plist"
        data = plistlib.loads(path.read_bytes())
        data["UISupportedInterfaceOrientations"] = data.pop("UISupportedInterfaceOrientations~ipad")
        path.write_bytes(plistlib.dumps(data))
        preflight.check_source(self.root)

    def test_wrong_size_or_alpha_icon_is_rejected(self):
        path = self.root / "KCC/Assets.xcassets/AppIcon.appiconset/AppIcon.png"
        original = path.read_bytes()
        for index, value, message in [(19, 1, "1024x1024"), (25, 6, "alpha")]:
            data = bytearray(original)
            data[index] = value
            path.write_bytes(data)
            with self.assertRaisesRegex(preflight.PreflightError, message):
                preflight.check_source(self.root)

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

    def test_release_rejects_local_demo_configuration_without_echoing_values(self):
        bundle = preflight.check_source(self.root)
        path = self.root / "KCC/GoogleService-Info.plist"
        for project, key, expected in (
            ("demo-kcc-ios", "test-key", "demo project"),
            ("real-project", "AIza" + "0" * 35, "dummy API key")
        ):
            path.write_bytes(plistlib.dumps({"BUNDLE_ID": bundle, "PROJECT_ID": project, "API_KEY": key}))
            with self.assertRaisesRegex(preflight.PreflightError, expected) as caught:
                preflight.check_release(self.root, bundle, {})
            self.assertNotIn(key, str(caught.exception))

    def test_handoff_is_source_only_and_preserves_existing_output(self):
        # A malformed local production config must neither be read nor copied.
        (self.root / "KCC/GoogleService-Info.plist").write_text("private-config-must-not-be-read")
        output = self.root / "handoff"
        report = prepare.prepare(self.root, output)
        self.assertIn("NOT verified", report)
        self.assertNotIn("private-config-must-not-be-read", report)
        options = plistlib.loads((output / "ExportOptions.example.plist").read_bytes())
        self.assertEqual(options["method"], "app-store-connect")
        self.assertEqual(options["teamID"], "SET_AFTER_ENROLLMENT")
        self.assertFalse(options["manageAppVersionAndBuildNumber"])
        with self.assertRaises(FileExistsError):
            prepare.prepare(self.root, output)
        self.assertEqual((output / "Handoff.md").read_text(), report)

    def test_handoff_rejects_invalid_source_before_creating_output(self):
        (self.root / "KCC/Updates/changelog.json").write_text("invalid")
        output = self.root / "handoff"
        with self.assertRaises(prepare.preflight.PreflightError):
            prepare.prepare(self.root, output)
        self.assertFalse(output.exists())

    def test_malformed_xml_does_not_escape_as_a_raw_parser_error(self):
        path = self.root / "KCC/GoogleService-Info.plist"
        path.write_text('<?xml version="1.0"?><plist><dict><key>private-config-marker</key>')
        with self.assertRaises(preflight.PreflightError) as caught:
            preflight.load_plist(path)
        self.assertNotIn("private-config-marker", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
