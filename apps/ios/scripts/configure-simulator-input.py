#!/usr/bin/env python3
"""Enable the software keyboard on the selected, owned CI Simulator."""

import argparse
import copy
from pathlib import Path
import plistlib
import subprocess
import tempfile
import uuid


DOMAIN = "com.apple.iphonesimulator"
KEY = "ConnectHardwareKeyboard"


def configure_preferences(preferences, device):
    try:
        uuid.UUID(device)
    except (ValueError, TypeError, AttributeError) as error:
        raise ValueError("Expected a Simulator UUID.") from error
    if not isinstance(preferences, dict):
        raise ValueError("Simulator preferences must be a dictionary.")
    result = copy.deepcopy(preferences)
    devices = result.setdefault("DevicePreferences", {})
    if not isinstance(devices, dict):
        raise ValueError("DevicePreferences must be a dictionary.")
    selected = devices.setdefault(device, {})
    if not isinstance(selected, dict):
        raise ValueError("Selected Simulator preferences must be a dictionary.")
    result[KEY] = False
    selected[KEY] = False
    return result


def verify_preferences(preferences, device):
    if (not isinstance(preferences, dict) or preferences.get(KEY) is not False
            or not isinstance(preferences.get("DevicePreferences"), dict)
            or not isinstance(preferences["DevicePreferences"].get(device), dict)
            or preferences["DevicePreferences"][device].get(KEY) is not False):
        raise ValueError("Simulator software-keyboard settings did not persist.")


def run_defaults(*arguments):
    return subprocess.run(
        ["defaults", *arguments], check=True, capture_output=True, timeout=30,
    ).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("device")
    args = parser.parse_args()
    # Validate the identifier before making any preference changes.
    configure_preferences({}, args.device)
    try:
        run_defaults("write", DOMAIN, KEY, "-bool", "false")
        existing = plistlib.loads(run_defaults("export", DOMAIN, "-"))
        configured = configure_preferences(existing, args.device)
        with tempfile.TemporaryDirectory(prefix="kcc-simulator-input-") as directory:
            path = Path(directory) / "preferences.plist"
            path.write_bytes(plistlib.dumps(configured))
            run_defaults("import", DOMAIN, str(path))
        verify_preferences(plistlib.loads(run_defaults("export", DOMAIN, "-")), args.device)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        # Never dump the preferences domain or tool stderr into CI logs.
        raise SystemExit("Simulator input configuration failed: " + type(error).__name__) from None
    print("Software keyboard enabled globally and for Simulator " + args.device)


if __name__ == "__main__":
    main()
