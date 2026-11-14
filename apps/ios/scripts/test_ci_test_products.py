import copy
import importlib.util
import io
import json
from pathlib import Path
import plistlib
import shutil
import stat
import tarfile
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("ci_test_products", Path(__file__).with_name("ci-test-products.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
CONTEXT = {"checkout": "a" * 40, "pr_head": "b" * 40, "xcode": "Xcode test-build",
           "sdk": "iphonesimulator:test-sdk", "arch": "test-arch", "lock": "c" * 64}
RUNTIME = "com.apple.CoreSimulator.SimRuntime.iOS-test"


class CITestProductsTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.products = self.root / "Build/Products"
        self.app = self.products / "Debug-iphonesimulator/KCC.app"
        self.app.mkdir(parents=True)
        self.executable = self.app / "KCC"
        self.executable.write_bytes(b"synthetic executable fixture")
        self.executable.chmod(0o755)
        (self.app / "executable-link").symlink_to("KCC")
        self.run_file = self.products / "KCC_test.xctestrun"
        self.data = {"KCCTests": {"TestBundlePath": "__TESTROOT__/KCCTests.xctest"},
                     "KCCUITests": {"TestBundlePath": "__TESTROOT__/KCCUITests.xctest"}}
        self.run_file.write_bytes(plistlib.dumps(self.data))
        self.archive = self.root / "archive"

    def prepare(self):
        MODULE.pack(self.products, self.archive, "debug", RUNTIME, CONTEXT)
        return json.loads((self.archive / "manifest.json").read_text())

    def test_round_trip_preserves_executable_and_relative_framework_symlinks(self):
        manifest = self.prepare()
        shutil.rmtree(self.products)
        restored = MODULE.unpack(self.archive, self.products, "debug", CONTEXT)
        self.assertEqual(restored, manifest)
        self.assertEqual(self.executable.read_bytes(), b"synthetic executable fixture")
        self.assertEqual(stat.S_IMODE(self.executable.stat().st_mode), 0o755)
        self.assertTrue((self.app / "executable-link").is_symlink())
        self.assertEqual((self.app / "executable-link").read_bytes(), self.executable.read_bytes())
        self.assertEqual(MODULE.test_targets(plistlib.loads(self.run_file.read_bytes())), {"KCCTests", "KCCUITests"})

    def test_each_commit_toolchain_sdk_architecture_and_lock_mismatch_is_rejected(self):
        manifest = self.prepare()
        for key in MODULE.CONTEXT_FIELDS:
            current = {**CONTEXT, key: "different"}
            with self.subTest(key=key), self.assertRaises(ValueError):
                MODULE.validate_manifest(manifest, "debug", self.products, current)

    def test_runtime_pin_selects_the_requested_runtime_instead_of_inventory_order(self):
        compatible = "com.apple.CoreSimulator.SimRuntime.iOS-26-2"
        affected = "com.apple.CoreSimulator.SimRuntime.iOS-26-4"
        inventory = json.dumps({"devices": {
            affected: [{"name": "iPhone 17 Pro", "udid": "affected", "isAvailable": True}],
            compatible: [
                {"name": "iPad Pro", "udid": "ipad", "isAvailable": True},
                {"name": "iPhone 17", "udid": "unavailable", "isAvailable": False},
                {"name": "iPhone 17 Pro", "udid": "compatible", "isAvailable": True},
            ],
        }})
        with patch.object(MODULE, "command", return_value=inventory), \
                patch.dict(MODULE.os.environ, {"IOS_TEST_RUNTIME": compatible}):
            self.assertEqual(MODULE.destination(), (compatible, "compatible"))
            # An artifact's recorded runtime takes precedence over a new pin.
            self.assertEqual(MODULE.destination(affected), (affected, "affected"))

    def test_unavailable_runtime_pin_fails_without_falling_back(self):
        inventory = json.dumps({"devices": {
            "com.apple.CoreSimulator.SimRuntime.iOS-26-4": [
                {"name": "iPhone 17 Pro", "udid": "affected", "isAvailable": True}
            ],
        }})
        with patch.object(MODULE, "command", return_value=inventory), \
                patch.dict(MODULE.os.environ, {"IOS_TEST_RUNTIME": "missing-runtime"}):
            with self.assertRaisesRegex(ValueError, "No available iPhone"):
                MODULE.destination()

    def test_wrong_configuration_schema_filename_and_product_location_are_rejected(self):
        manifest = self.prepare()
        for field, value in (("schema", 2), ("kind", "release"),
                             ("xctestrun", "../elsewhere.xctestrun"), ("products_path", "/different")):
            with self.subTest(field=field), self.assertRaises(ValueError):
                MODULE.validate_manifest({**manifest, field: value}, "debug", self.products, CONTEXT)
        with self.assertRaises(ValueError):
            MODULE.pack(self.products, self.root / "release", "release", RUNTIME, CONTEXT)

    def test_corrupted_archive_is_rejected_before_creating_products(self):
        self.prepare()
        shutil.rmtree(self.products)
        with (self.archive / "products.tar.gz").open("ab") as stream:
            stream.write(b"corruption")
        with self.assertRaisesRegex(ValueError, "integrity"):
            MODULE.unpack(self.archive, self.products, "debug", CONTEXT)
        self.assertFalse(self.products.exists())

    def test_existing_build_is_never_merged_or_overwritten(self):
        self.prepare()
        before = self.executable.read_bytes()
        with self.assertRaisesRegex(ValueError, "existing build"):
            MODULE.unpack(self.archive, self.products, "debug", CONTEXT)
        self.assertEqual(self.executable.read_bytes(), before)

    def test_filtered_or_disabled_targets_cannot_claim_full_suite_coverage(self):
        for field, value in (("SkipTestIdentifiers", ["someTest"]),
                             ("OnlyTestIdentifiers", ["someTest"]), ("IsEnabled", False)):
            data = copy.deepcopy(self.data)
            data["KCCTests"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                MODULE.test_targets(data)
        for data in ({"KCCTests": {}}, {**self.data, "UnexpectedTests": {}}):
            self.run_file.write_bytes(plistlib.dumps(data))
            with self.assertRaises(ValueError):
                MODULE.validate_products(self.products, "debug")

    def test_modern_test_configuration_retains_both_enabled_targets(self):
        data = {"TestConfigurations": [{"IsEnabled": True, "TestTargets": [
            {"BlueprintName": "KCCTests"}, {"BlueprintName": "KCCUITests"}]}]}
        self.assertEqual(MODULE.test_targets(data), {"KCCTests", "KCCUITests"})
        data["TestConfigurations"][0]["IsEnabled"] = False
        with self.assertRaises(ValueError):
            MODULE.test_targets(data)

    def test_unsafe_archive_members_cannot_escape_the_product_directory(self):
        manifest = self.prepare()
        shutil.rmtree(self.products)
        path = self.archive / "products.tar.gz"
        with tarfile.open(path, "w:gz") as bundle:
            entry = tarfile.TarInfo("../outside")
            entry.size = 1
            bundle.addfile(entry, io.BytesIO(b"x"))
        manifest["archive_sha256"] = MODULE.digest(path)
        (self.archive / "manifest.json").write_text(json.dumps(manifest))
        with self.assertRaises(tarfile.TarError):
            MODULE.unpack(self.archive, self.products, "debug", CONTEXT)
        self.assertFalse((self.products.parent / "outside").exists())

    def test_cache_scope_keeps_toolchains_separate_and_allows_content_revalidation(self):
        original = MODULE.cache_fingerprint(CONTEXT)
        self.assertEqual(MODULE.cache_fingerprint({**CONTEXT, "checkout": "d" * 40}), original)
        for key in ("xcode", "sdk", "arch", "lock"):
            with self.subTest(key=key):
                self.assertNotEqual(MODULE.cache_fingerprint({**CONTEXT, key: "different"}), original)

    def test_selected_scenarios_must_actually_execute_without_failures_or_skips(self):
        for expected in (1, 2):
            passing = {"passedTests": expected, "totalTestCount": expected, "failedTests": 0, "skippedTests": 0}
            MODULE.check_summary(passing, expected)
            for field, value in (("passedTests", 0), ("totalTestCount", 0),
                                 ("totalTestCount", expected + 1), ("failedTests", 1), ("skippedTests", 1)):
                with self.subTest(expected=expected, field=field), self.assertRaises(ValueError):
                    MODULE.check_summary({**passing, field: value}, expected)


if __name__ == "__main__":
    unittest.main()
