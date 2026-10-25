"""Validate opt-in isolation without launching emulators or contacting cloud services."""
import copy
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("auth_tests", Path(__file__).with_name("auth-emulator-tests.py"))
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class AuthEmulatorRunnerTests(unittest.TestCase):
    def test_config_is_auth_only_and_loopback(self):
        emulators = runner.config()["emulators"]
        self.assertEqual(emulators["auth"], {"host": "127.0.0.1", "port": 19098})
        self.assertNotIn("functions", emulators)
        self.assertNotIn("firestore", emulators)
        self.assertFalse(emulators["ui"]["enabled"])

    def test_updates_only_unit_test_target_in_legacy_and_modern_formats(self):
        examples = [
            {"KCCTests": {"EnvironmentVariables": {"KEEP": "yes"}}, "KCCUITests": {}},
            {"TestConfigurations": [{"TestTargets": [
                {"BlueprintName": "KCCTests", "EnvironmentVariables": {"KEEP": "yes"}},
                {"BlueprintName": "KCCUITests"}
            ]}]}
        ]
        for data in examples:
            original = copy.deepcopy(data)
            result = runner.enable_test(data)
            if "TestConfigurations" in result:
                unit, ui = result["TestConfigurations"][0]["TestTargets"]
                self.assertEqual(ui, original["TestConfigurations"][0]["TestTargets"][1])
            else:
                unit = result["KCCTests"]
                self.assertEqual(result["KCCUITests"], original["KCCUITests"])
            self.assertEqual(unit["EnvironmentVariables"], {"KEEP": "yes", "KCC_RUN_AUTH_EMULATOR_TESTS": "1"})
            self.assertEqual(unit["TestingEnvironmentVariables"]["KCC_RUN_AUTH_EMULATOR_TESTS"], "1")

    def test_missing_or_ambiguous_target_fails_closed(self):
        for data in ({}, {"TestConfigurations": [{"TestTargets": [
            {"BlueprintName": "KCCTests"}, {"BlueprintName": "KCCTests"}
        ]}]}):
            with self.assertRaises(ValueError):
                runner.enable_test(data)

    def test_skipping_or_not_selecting_the_native_test_is_not_a_pass(self):
        passing = {"passedTests": 1, "failedTests": 0, "skippedTests": 0, "totalTestCount": 1}
        runner.check_summary(passing)
        for field, value in (("passedTests", 0), ("failedTests", 1), ("skippedTests", 1), ("totalTestCount", 0)):
            with self.assertRaises(ValueError):
                runner.check_summary({**passing, field: value})


if __name__ == "__main__":
    unittest.main()
