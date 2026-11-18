import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPT_DIR = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("signin_config", SCRIPT_DIR / "verify-google-signin-config.py")
check = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(check)
CLIENT_ID = "123456789012-ci-fixture.apps.googleusercontent.com"


class GoogleSignInConfigTest(unittest.TestCase):
    def setUp(self):
        self.config = json.loads((SCRIPT_DIR / "fixtures/google-services-signin.json").read_text())

    def test_matches_android_package_instead_of_first_client(self):
        other = copy.deepcopy(self.config["client"][0])
        other["client_info"]["android_client_info"]["package_name"] = "other.package"
        self.config["client"].insert(0, other)
        self.assertEqual(CLIENT_ID, check.configured_client_id(self.config))

    def test_rejects_android_only_oauth_client(self):
        self.config["client"][0]["oauth_client"][0]["client_type"] = 1
        with self.assertRaisesRegex(ValueError, "web OAuth client"):
            check.configured_client_id(self.config)

    def test_rejects_wrong_package(self):
        self.config["client"][0]["client_info"]["android_client_info"]["package_name"] = "wrong.package"
        with self.assertRaisesRegex(ValueError, "matching Android package"):
            check.configured_client_id(self.config)

    def test_rejects_blank_and_invalid_ids_without_printing_them(self):
        for value in ["", " ", "not-an-oauth-id", None]:
            self.config["client"][0]["oauth_client"][0]["client_id"] = value
            with self.assertRaisesRegex(ValueError, "valid web OAuth client"):
                check.configured_client_id(self.config)

    def test_accepts_default_value_from_apk_and_aab_dumps(self):
        for source in ["", " src=/generated/values.xml:12"]:
            dump = f'    resource 0x7f100001 string/default_web_client_id\n      () "{CLIENT_ID}"{source}\n'
            check.verify_resource_dump(dump, CLIENT_ID)

    def test_client_value_under_another_resource_does_not_pass(self):
        dump = f'    resource 0x7f100001 string/another_name\n      () "{CLIENT_ID}"\n'
        with self.assertRaisesRegex(ValueError, "removed or renamed"):
            check.verify_resource_dump(dump, CLIENT_ID)

    def test_named_but_empty_or_wrong_resource_does_not_pass(self):
        for value in ["", "wrong.apps.googleusercontent.com"]:
            dump = f'    resource 0x7f100001 string/default_web_client_id\n      () "{value}"\n'
            with self.assertRaisesRegex(ValueError, "differs"):
                check.verify_resource_dump(dump, CLIENT_ID)

    def test_missing_default_does_not_borrow_next_resource_value(self):
        dump = f'    resource 0x7f100001 string/default_web_client_id\n      (sv) "{CLIENT_ID}"\n    resource 0x7f100002 string/other\n      () "{CLIENT_ID}"\n'
        with self.assertRaisesRegex(ValueError, "no default string"):
            check.verify_resource_dump(dump, CLIENT_ID)

    def test_extracts_aab_base_module_for_aapt2(self):
        with tempfile.TemporaryDirectory() as directory:
            aab = Path(directory) / "release.aab"
            with zipfile.ZipFile(aab, "w") as archive:
                archive.writestr("base/resources.pb", b"resource-table")
                archive.writestr("base/manifest/AndroidManifest.xml", b"manifest")

            def dump_base(command, **kwargs):
                with zipfile.ZipFile(command[-1]) as base:
                    self.assertEqual(b"resource-table", base.read("resources.pb"))
                    self.assertEqual(b"manifest", base.read("AndroidManifest.xml"))
                class Result:
                    returncode = 0
                    stdout = f'    resource 0x7f100001 string/default_web_client_id\n      () "{CLIENT_ID}"\n'
                return Result()

            with patch.object(check.subprocess, "run", side_effect=dump_base):
                check.verify_package(Path("aapt2"), aab, CLIENT_ID)

    def test_aapt2_failure_does_not_echo_resource_contents(self):
        class Result:
            returncode = 1
            stdout = CLIENT_ID
            stderr = CLIENT_ID

        with patch.object(check.subprocess, "run", return_value=Result()):
            with self.assertRaises(ValueError) as raised:
                check.verify_package(Path("aapt2"), Path("release.apk"), CLIENT_ID)
        self.assertNotIn(CLIENT_ID, str(raised.exception))


if __name__ == "__main__":
    unittest.main()
