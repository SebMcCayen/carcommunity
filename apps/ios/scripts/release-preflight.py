#!/usr/bin/env python3
"""Read-only iOS source/release checks. Never prints configuration values."""
import argparse
import json
import os
from pathlib import Path
import plistlib
import re
import sys
import struct
from xml.parsers.expat import ExpatError


class PreflightError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise PreflightError(message)


def load_plist(path):
    try:
        with path.open("rb") as stream:
            return plistlib.load(stream)
    except (OSError, ValueError, plistlib.InvalidFileException, ExpatError) as error:
        raise PreflightError(f"Cannot read a valid plist: {path.name}") from error


def setting(spec, key):
    values = re.findall(rf"^\s*{re.escape(key)}:\s*(\S+)\s*$", spec, re.MULTILINE)
    require(len(values) == 1, f"Expected one {key} setting in project.yml")
    return values[0]


def check_app_icon(root):
    icon = root / "KCC/Assets.xcassets/AppIcon.appiconset"
    try:
        catalog = json.loads((icon / "Contents.json").read_text())
        images = catalog["images"]
        require(len(images) == 1 and images[0].get("size") == "1024x1024"
                and images[0].get("idiom") == "universal", "AppIcon must declare its universal 1024px image")
        filename = images[0].get("filename")
        require(isinstance(filename, str) and Path(filename).name == filename,
                "AppIcon must use a local image filename")
        data = (icon / filename).read_bytes()
        require(len(data) > 33 and data[:8] == b"\x89PNG\r\n\x1a\n"
                and data[12:16] == b"IHDR", "AppIcon must contain a valid PNG header")
        require(struct.unpack(">II", data[16:24]) == (1024, 1024), "AppIcon must be 1024x1024 pixels")
        require(data[25] == 2, "AppIcon must be opaque RGB without an alpha channel")
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise PreflightError("AppIcon image/catalog is missing or malformed") from error


def check_source(root):
    try:
        spec = (root / "project.yml").read_text()
        notes = json.loads((root / "KCC/Updates/changelog.json").read_text())["entries"]
        strings = json.loads((root / "KCC/Resources/Localizable.xcstrings").read_text())["strings"]
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise PreflightError("Cannot read project, changelog, or localization catalog") from error
    bundle = setting(spec, "PRODUCT_BUNDLE_IDENTIFIER")
    version = setting(spec, "MARKETING_VERSION")
    build = setting(spec, "CURRENT_PROJECT_VERSION")
    require(re.fullmatch(r"\d+\.\d+\.\d+", version), "Marketing version must use major.minor.patch")
    require(build.isdigit() and int(build) > 0, "Build number must be a positive integer")
    require(isinstance(notes, list) and notes, "Changelog entries must be a nonempty array")
    require(all(isinstance(entry, dict) and type(entry.get("buildNumber")) is int for entry in notes),
            "Every changelog entry must have an integer build number")
    builds = [entry["buildNumber"] for entry in notes]
    require(len(builds) == len(set(builds)), "Changelog contains duplicate build numbers")
    require(max(builds) == int(build), "Changelog must cover the current build and contain no future builds")
    shipping = next(entry for entry in notes if entry["buildNumber"] == int(build))
    require(shipping.get("versionName") == version, "Changelog version differs from shipping version")
    for entry in notes:
        for field in ("highlightKeys", "changeKeys"):
            keys = entry.get(field)
            require(isinstance(keys, list) and keys, f"Changelog {field} must be nonempty")
            for key in keys:
                require(isinstance(key, str), "Changelog localization keys must be strings")
                for language in ("sv", "en"):
                    unit = strings.get(key, {}).get("localizations", {}).get(language, {}).get("stringUnit", {})
                    require(unit.get("state") == "translated" and bool(unit.get("value")),
                            f"Changelog key {key} needs a {language} translation")
    info = load_plist(root / "KCC/Info.plist")
    supported_orientations = {
        "UIInterfaceOrientationPortrait", "UIInterfaceOrientationPortraitUpsideDown",
        "UIInterfaceOrientationLandscapeLeft", "UIInterfaceOrientationLandscapeRight",
    }
    orientations = info.get("UISupportedInterfaceOrientations")
    require(isinstance(orientations, list) and bool(orientations) and
            all(isinstance(value, str) and value in supported_orientations for value in orientations),
            "Supported interface orientations are missing or invalid")
    ipad_orientations = info.get("UISupportedInterfaceOrientations~ipad", orientations)
    require(isinstance(ipad_orientations, list) and len(ipad_orientations) == 4 and
            all(value in ipad_orientations for value in supported_orientations),
            "iPad multitasking requires all four interface orientations")
    require(info.get("CFBundleShortVersionString") == "$(MARKETING_VERSION)" and
            info.get("CFBundleVersion") == "$(CURRENT_PROJECT_VERSION)",
            "Info.plist versions must reference the build settings")
    require(info.get("MBXAccessToken") == "$(MAPBOX_ACCESS_TOKEN)", "Mapbox token must be injected")
    require(bool(info.get("NSLocationWhenInUseUsageDescription")), "Location purpose string is missing")
    require({"location", "remote-notification"}.issubset(info.get("UIBackgroundModes", [])),
            "Required background modes are missing")
    entitlements = load_plist(root / "KCC/KCC.entitlements")
    require(entitlements.get("com.apple.developer.applesignin") == ["Default"],
            "Sign in with Apple entitlement is missing")
    for key, variable in (("aps-environment", "APS_ENVIRONMENT"),
                          ("com.apple.developer.devicecheck.appattest-environment", "APP_ATTEST_ENVIRONMENT")):
        require(entitlements.get(key) == f"$({variable})", f"{key} must use its configuration setting")
        require(re.search(rf"Release:\s*\n(?:(?:[ ]{{10}}.*)?\n)*?[ ]{{10}}{variable}: production", spec),
                f"Release {variable} must be production")
    privacy = load_plist(root / "KCC/Resources/PrivacyInfo.xcprivacy")
    require(privacy.get("NSPrivacyTracking") is False and privacy.get("NSPrivacyTrackingDomains") == [],
            "Privacy manifest tracking declarations changed; review the privacy policy")
    accesses = {entry.get("NSPrivacyAccessedAPIType"): entry.get("NSPrivacyAccessedAPITypeReasons", [])
                for entry in privacy.get("NSPrivacyAccessedAPITypes", [])}
    for category in ("SystemBootTime", "UserDefaults"):
        require(bool(accesses.get(f"NSPrivacyAccessedAPICategory{category}")),
                f"Privacy manifest lacks required reasons for {category}")
    collected = {entry.get("NSPrivacyCollectedDataType") for entry in privacy.get("NSPrivacyCollectedDataTypes", [])}
    require({"NSPrivacyCollectedDataTypeCrashData", "NSPrivacyCollectedDataTypeOtherDiagnosticData"} <= collected,
            "Privacy manifest lacks crash/diagnostic declarations")
    check_app_icon(root)
    return bundle


def check_release(root, bundle, environment):
    # Local variables only: never echo a Firebase configuration or token.
    firebase = load_plist(root / "KCC/GoogleService-Info.plist")
    require(firebase.get("BUNDLE_ID") == bundle, "Firebase BUNDLE_ID does not match the app")
    require(not str(firebase.get("PROJECT_ID", "")).strip().lower().startswith("demo-"),
            "Release must not use a Firebase demo project")
    require(firebase.get("API_KEY") != "AIza" + "0" * 35,
            "Release must not use the simulator dummy API key")
    for field in ("GOOGLE_APP_ID", "PROJECT_ID", "API_KEY", "GCM_SENDER_ID"):
        value = firebase.get(field)
        require(isinstance(value, str) and bool(value.strip()) and "$(" not in value and
                not any(word in value.lower() for word in ("placeholder", "example", "replace", "your_")),
                f"Firebase {field} is missing or contains a placeholder")
    require(re.fullmatch(r"[A-Z0-9]{10}", environment.get("DEVELOPMENT_TEAM", "")),
            "Set DEVELOPMENT_TEAM to your 10-character Apple team ID")
    token = environment.get("MAPBOX_ACCESS_TOKEN", "")
    require(token.startswith("pk.") and len(token) > 20 and not re.search(r"\s|\$\(|[<>…]", token) and
            not any(word in token.lower() for word in ("placeholder", "example", "replace", "your_")),
            "Set MAPBOX_ACCESS_TOKEN to the public runtime token (pk.), never a private sk. token")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", action="store_true", help="Also require local Firebase config and signing/token inputs")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    try:
        bundle = check_source(root)
        if args.release:
            check_release(root, bundle, os.environ)
    except (PreflightError, KeyError, TypeError, AttributeError) as error:
        # Only authored validation errors are safe to print; raw parsing errors
        # could include credential values from a malformed config.
        message = str(error) if isinstance(error, PreflightError) else "Malformed release metadata"
        print(f"FAIL: {message}", file=sys.stderr)
        return 1
    print("PASS: iOS source release metadata")
    if args.release:
        print("PASS: local release inputs are present and structurally valid")
        print("Next: signed archive validation and physical-device checks (docs/ios-release-readiness.md)")
    else:
        print("Signing/configuration not checked. Run --release after Apple/Firebase setup.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
