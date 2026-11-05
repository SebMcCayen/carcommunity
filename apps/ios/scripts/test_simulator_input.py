import copy
import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location(
    "simulator_input", Path(__file__).with_name("configure-simulator-input.py"),
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
DEVICE = "ABCDEF01-2345-6789-ABCD-EF0123456789"
OTHER = "11111111-2222-3333-4444-555555555555"


class SimulatorInputTests(unittest.TestCase):
    def test_fresh_preferences_enable_the_software_keyboard(self):
        original = {}
        configured = MODULE.configure_preferences(original, DEVICE)
        MODULE.verify_preferences(configured, DEVICE)
        self.assertEqual(original, {})
        self.assertIn(DEVICE, configured["DevicePreferences"])

    def test_other_preferences_and_other_devices_are_preserved(self):
        original = {
            "ConnectHardwareKeyboard": True,
            "ShowDeviceBezels": True,
            "DevicePreferences": {
                DEVICE: {"ConnectHardwareKeyboard": True, "WindowScale": 0.5},
                OTHER: {"ConnectHardwareKeyboard": True, "WindowScale": 0.75},
            },
        }
        before = copy.deepcopy(original)
        configured = MODULE.configure_preferences(original, DEVICE)
        MODULE.verify_preferences(configured, DEVICE)
        self.assertEqual(original, before)
        self.assertEqual(configured["DevicePreferences"][OTHER], original["DevicePreferences"][OTHER])
        self.assertEqual(configured["DevicePreferences"][DEVICE]["WindowScale"], 0.5)
        self.assertIs(configured["ShowDeviceBezels"], True)

    def test_configuration_is_idempotent(self):
        once = MODULE.configure_preferences({}, DEVICE)
        self.assertEqual(MODULE.configure_preferences(once, DEVICE), once)

    def test_malformed_preferences_are_rejected_without_repairing_other_data(self):
        for value in ([], {"DevicePreferences": []}, {"DevicePreferences": {DEVICE: "invalid"}}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                MODULE.configure_preferences(value, DEVICE)

    def test_invalid_device_identifiers_are_rejected(self):
        for value in ("", "not-a-simulator", None):
            with self.subTest(value=value), self.assertRaises(ValueError):
                MODULE.configure_preferences({}, value)

    def test_readback_requires_both_boolean_settings_to_be_disabled(self):
        for value in ({}, {"ConnectHardwareKeyboard": False},
                      {"ConnectHardwareKeyboard": False, "DevicePreferences": {DEVICE: {"ConnectHardwareKeyboard": True}}},
                      {"ConnectHardwareKeyboard": 0, "DevicePreferences": {DEVICE: {"ConnectHardwareKeyboard": False}}}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                MODULE.verify_preferences(value, DEVICE)


if __name__ == "__main__":
    unittest.main()
