"""Regression coverage for token injection without exposing credential values."""
import importlib.util
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("mapbox-build-config.py")
SPEC = importlib.util.spec_from_file_location("mapbox_build_config", SCRIPT)
config = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(config)
TOKEN = "pk.test_public_payload.test_public_signature"


class MapboxBuildConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name) / "runtime.xcconfig"

    def test_public_token_is_injected_verbatim_with_private_permissions(self):
        self.output.write_text("old configuration")
        self.output.chmod(0o644)
        config.write_config(self.output, {"MAPBOX_ACCESS_TOKEN": TOKEN})
        self.assertEqual(self.output.read_text(), f"MAPBOX_ACCESS_TOKEN = {TOKEN}\n")
        self.assertEqual(stat.S_IMODE(self.output.stat().st_mode), 0o600)

    def test_rejects_absent_secret_and_injectable_values_without_overwriting(self):
        self.output.write_text("keep existing configuration")
        for token in ("", "sk.private.secret", "pk.placeholder", TOKEN + "\nOTHER = YES",
                      TOKEN + " // comment", TOKEN + "$(OTHER)", " " + TOKEN):
            with self.subTest(token_type="invalid"):
                with self.assertRaises(config.ConfigurationError) as caught:
                    config.write_config(self.output, {"MAPBOX_ACCESS_TOKEN": token})
                if token:
                    self.assertNotIn(token, str(caught.exception))
                self.assertEqual(self.output.read_text(), "keep existing configuration")

    def test_atomic_replacement_does_not_follow_existing_symlink(self):
        destination = Path(self.temp.name) / "other-file"
        destination.write_text("do not change")
        self.output.symlink_to(destination)
        config.write_config(self.output, {"MAPBOX_ACCESS_TOKEN": TOKEN})
        self.assertFalse(self.output.is_symlink())
        self.assertEqual(destination.read_text(), "do not change")
        self.assertEqual(self.output.read_text(), f"MAPBOX_ACCESS_TOKEN = {TOKEN}\n")

    def test_cli_never_prints_token_on_success_or_failure(self):
        for token, expected_status in ((TOKEN, 0), ("sk.do_not_echo.secret", 1)):
            result = subprocess.run([sys.executable, str(SCRIPT), "--output", str(self.output)],
                                    env={**os.environ, "MAPBOX_ACCESS_TOKEN": token},
                                    text=True, capture_output=True, check=False)
            self.assertEqual(result.returncode, expected_status)
            self.assertNotIn(token, result.stdout + result.stderr)

    def test_rejects_non_config_output_and_reports_io_failure_without_token(self):
        with self.assertRaises(config.ConfigurationError):
            config.write_config(self.output.with_suffix(".plist"), {"MAPBOX_ACCESS_TOKEN": TOKEN})
        result = subprocess.run([sys.executable, str(SCRIPT), "--output",
                                 str(self.output / "missing/runtime.xcconfig")],
                                env={**os.environ, "MAPBOX_ACCESS_TOKEN": TOKEN},
                                text=True, capture_output=True, check=False)
        self.assertEqual(result.returncode, 1)
        self.assertNotIn(TOKEN, result.stdout + result.stderr)
        self.assertEqual(list(Path(self.temp.name).glob(".mapbox-*")), [])


if __name__ == "__main__":
    unittest.main()
