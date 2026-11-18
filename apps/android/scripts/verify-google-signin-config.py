#!/usr/bin/env python3
"""Check Google Sign-In input and optimized APK/AAB resources without logging IDs."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

PACKAGE = "com.kungsbackacarcommunity.app"
RESOURCE = "default_web_client_id"


def configured_client_id(config):
    # Match Google Services' Android client selection and first type-3 OAuth ID.
    client = next(
        (entry for entry in config.get("client", [])
         if entry.get("client_info", {}).get("android_client_info", {}).get("package_name") == PACKAGE),
        None,
    )
    if client is None:
        raise ValueError("google-services.json has no matching Android package")
    oauth = next((entry for entry in client.get("oauth_client", []) if entry.get("client_type") == 3), {})
    value = oauth.get("client_id")
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]+\.apps\.googleusercontent\.com", value):
        raise ValueError("google-services.json is missing a valid web OAuth client (client_type 3)")
    return value


def verify_resource_dump(dump, expected):
    # Resource names matter: getIdentifier cannot find an unnamed/removed entry.
    in_resource = False
    found = False
    for line in dump.splitlines():
        if line.lstrip().startswith("resource "):
            in_resource = bool(re.search(rf"\bstring/{RESOURCE}(?:\s|$)", line))
            found |= in_resource
        elif in_resource and line.strip().startswith("() "):
            # aapt2 appends a source location for proto (AAB) resources.
            raw_value = line.strip()[3:]
            try:
                value, _ = json.JSONDecoder().raw_decode(raw_value)
            except ValueError:
                break
            if value == expected:
                return
            raise ValueError("packaged Google Sign-In client differs from google-services.json")
    detail = "has no default string value" if found else "was removed or renamed"
    raise ValueError(f"packaged {RESOURCE} {detail}")


def verify_package(aapt2, package, expected):
    with tempfile.TemporaryDirectory(prefix="google-signin-check-") as directory:
        target = package
        if package.suffix == ".aab":
            # aapt2 understands proto APKs. Present the base module's resource
            # table and proto manifest as an APK; no bundletool download needed.
            target = Path(directory) / "base.ap_"
            with zipfile.ZipFile(package) as source, zipfile.ZipFile(target, "w") as base:
                base.writestr("resources.pb", source.read("base/resources.pb"))
                base.writestr("AndroidManifest.xml", source.read("base/manifest/AndroidManifest.xml"))
        result = subprocess.run(
            [str(aapt2), "dump", "resources", str(target)],
            capture_output=True, text=True, check=False,
        )
        if result.returncode:
            # A resource dump can contain project config. Never echo it to CI.
            raise ValueError(f"aapt2 could not read {package.name}")
        verify_resource_dump(result.stdout, expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--aapt2", type=Path)
    parser.add_argument("packages", nargs="*", type=Path)
    args = parser.parse_args()
    if args.packages and args.aapt2 is None:
        parser.error("--aapt2 is required when checking packages")
    try:
        expected = configured_client_id(json.loads(args.config.read_text()))
        print("Google Sign-In web OAuth configuration is valid.")
        for package in args.packages:
            verify_package(args.aapt2, package, expected)
            print(f"Google Sign-In resource verified in {package.name}.")
    except (ValueError, OSError, KeyError, TypeError, AttributeError, zipfile.BadZipFile) as error:
        # Do not echo config contents or resource values, even on invalid input.
        message = str(error) if isinstance(error, ValueError) and not isinstance(error, json.JSONDecodeError) else type(error).__name__
        print(f"ERROR: Google Sign-In validation failed: {message}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
