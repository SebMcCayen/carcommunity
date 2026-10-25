#!/usr/bin/env python3
"""Read-only checks for simulator identity and Firebase packaging. Never prints tokens."""
import argparse
import plistlib
from pathlib import Path
import struct
import subprocess
import sys
from xml.parsers.expat import ExpatError


class PackageError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise PackageError(message)


def simulator_identities(data):
    """Read Xcode's __TEXT,__entitlements in every thin/universal Mach-O slice."""
    require(len(data) >= 8, "Truncated executable")
    magic = data[:4]
    if magic in (b"\xca\xfe\xba\xbe", b"\xca\xfe\xba\xbf"):
        wide = magic[-1] == 0xbf
        count = struct.unpack_from(">I", data, 4)[0]
        width = 32 if wide else 20
        require(0 < count <= 32 and 8 + count * width <= len(data), "Invalid universal header")
        identities = []
        for index in range(count):
            position = 8 + index * width + 8
            offset, size = struct.unpack_from(">QQ" if wide else ">II", data, position)
            require(offset >= 8 + count * width and size > 0 and offset + size <= len(data),
                    "Invalid universal slice")
            require(data[offset:offset + 4] == b"\xcf\xfa\xed\xfe", "Expected a 64-bit simulator slice")
            identities.extend(simulator_identities(data[offset:offset + size]))
        return identities
    require(magic == b"\xcf\xfa\xed\xfe" and len(data) >= 32, "Expected 64-bit simulator Mach-O")
    count, commands_size = struct.unpack_from("<II", data, 16)
    end = 32 + commands_size
    require(count <= 4096 and end <= len(data), "Invalid Mach-O command table")
    position = 32
    identity = None
    platform = None
    for _ in range(count):
        require(position + 8 <= end, "Truncated load command")
        command, length = struct.unpack_from("<II", data, position)
        require(length >= 8 and position + length <= end, "Invalid load command size")
        if command == 0x32:  # LC_BUILD_VERSION; platform 7 is iOS Simulator.
            require(length >= 24, "Truncated platform command")
            platform = struct.unpack_from("<I", data, position + 8)[0]
        if command == 0x19:  # LC_SEGMENT_64
            require(length >= 72, "Truncated segment")
            sections = struct.unpack_from("<I", data, position + 64)[0]
            require(72 + 80 * sections <= length, "Invalid section table")
            for index in range(sections):
                section = position + 72 + 80 * index
                name = data[section:section + 16].rstrip(b"\0")
                segment = data[section + 16:section + 32].rstrip(b"\0")
                if name == b"__entitlements" and segment == b"__TEXT":
                    require(identity is None, "Duplicate simulator entitlements")
                    size, offset = struct.unpack_from("<QI", data, section + 40)
                    require(0 < size <= 1024 * 1024 and offset >= end and offset + size <= len(data),
                            "Invalid simulator entitlement section")
                    try:
                        value = plistlib.loads(data[offset:offset + size])
                    except (ValueError, plistlib.InvalidFileException, ExpatError) as error:
                        raise PackageError("Invalid simulator entitlement plist") from error
                    require(isinstance(value, dict), "Invalid simulator entitlement dictionary")
                    identity = value.get("application-identifier")
                    require(isinstance(identity, str) and bool(identity), "Missing simulator application identity")
        position += length
    require(platform == 7, "Executable is not built for iOS Simulator")
    require(identity is not None, "Missing simulator entitlements; rebuild with signing enabled")
    return [identity]


def check_package(app, firebase_mode="absent"):
    try:
        info = plistlib.loads((app / "Info.plist").read_bytes())
        bundle = info["CFBundleIdentifier"]
        executable = info["CFBundleExecutable"]
        require(isinstance(bundle, str) and bool(bundle), "Missing bundle identity")
        require(isinstance(executable, str) and executable not in ("", ".", "..") and
                Path(executable).name == executable, "Invalid executable name")
        identities = simulator_identities((app / executable).read_bytes())
        require(all(identity == bundle or identity.endswith("." + bundle) for identity in identities),
                "Simulator application identity does not match the bundle")
        config = app / "GoogleService-Info.plist"
        if firebase_mode == "absent":
            require(not config.exists(), "Config-less package contains Firebase configuration")
        else:
            firebase = plistlib.loads(config.read_bytes())
            require(bundle.endswith(".local"), "Demo package must have a separate local bundle ID")
            require(firebase.get("BUNDLE_ID") == bundle, "Demo Firebase bundle identity mismatch")
            require(firebase.get("PROJECT_ID") == "demo-kcc-ios", "Demo package must use demo-kcc-ios")
            require(firebase.get("API_KEY") == "AIza" + "0" * 35, "Demo package must use the dummy API key")
            require(firebase.get("DATABASE_URL") in (None, "https://demo-kcc-ios-default-rtdb.firebaseio.com"),
                    "Demo database configuration must not target a real project")
            require(firebase.get("STORAGE_BUCKET") in (None, "demo-kcc-ios.appspot.com"),
                    "Demo storage configuration must not target a real project")
            require(info.get("NSAppTransportSecurity", {}).get("NSAllowsArbitraryLoads") is not True,
                    "Demo package must not allow arbitrary network loads")
        return len(identities)
    except (OSError, ValueError, KeyError, TypeError, AttributeError, struct.error, ExpatError) as error:
        raise PackageError("Cannot read valid simulator package metadata") from error


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path)
    parser.add_argument("--firebase-mode", choices=("absent", "demo"), default="absent")
    args = parser.parse_args()
    try:
        count = check_package(args.app, args.firebase_mode)
        result = subprocess.run(["codesign", "--verify", "--deep", "--strict", str(args.app)],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        require(result.returncode == 0, "Simulator code signature verification failed")
    except (PackageError, OSError) as error:
        print("FAIL: " + (str(error) if isinstance(error, PackageError) else "Cannot run codesign"), file=sys.stderr)
        return 1
    print(f"PASS: simulator identity, entitlements, Firebase mode and signature ({count} slices)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
