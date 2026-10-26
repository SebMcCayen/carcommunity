#!/usr/bin/env python3
"""Prepare/run the opt-in native SDK test against the loopback Auth emulator only."""
import argparse
import json
import os
from pathlib import Path
import plistlib
import subprocess
import sys

PROJECT = "demo-kcc-ios-ci"


def config():
    return {"emulators": {
        "auth": {"host": "127.0.0.1", "port": 19098},
        "hub": {"host": "127.0.0.1", "port": 14441},
        "logging": {"host": "127.0.0.1", "port": 14501},
        "ui": {"enabled": False}, "singleProjectMode": True
    }}


def enable_test(data):
    if "TestConfigurations" in data:
        targets = [target for configuration in data["TestConfigurations"]
                   for target in configuration.get("TestTargets", [])]
    else:
        targets = [{**value, "BlueprintName": name} for name, value in data.items()
                   if isinstance(value, dict) and name != "__xctestrun_metadata__"]
    matches = [target for target in targets if target.get("BlueprintName") == "KCCTests"]
    if len(matches) != 1:
        raise ValueError("Expected exactly one KCCTests target")
    target = matches[0]
    if "TestConfigurations" not in data:
        target = data["KCCTests"]
    for field in ("EnvironmentVariables", "TestingEnvironmentVariables"):
        target.setdefault(field, {})["KCC_RUN_AUTH_EMULATOR_TESTS"] = "1"
    return data


def check_summary(summary):
    if not (summary.get("passedTests") == 1 and summary.get("failedTests") == 0 and
            summary.get("skippedTests") == 0 and summary.get("totalTestCount") == 1):
        raise ValueError("Expected exactly one passed native Auth test, with no skipped or failed tests")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    configure = sub.add_parser("config")
    configure.add_argument("output", type=Path)
    run = sub.add_parser("run")
    run.add_argument("--products", type=Path, required=True)
    run.add_argument("--destination", required=True)
    args = parser.parse_args()
    if args.command == "config":
        args.output.write_text(json.dumps(config(), indent=2) + "\n")
        return 0
    path = args.products / "auth-emulator-acceptance.xctestrun"
    sources = [source for source in args.products.glob("*.xctestrun") if source != path]
    if len(sources) != 1:
        parser.error("Expected one build-for-testing xctestrun file")
    data = enable_test(plistlib.loads(sources[0].read_bytes()))
    path.write_bytes(plistlib.dumps(data))
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith(("GOOGLE_", "GCLOUD_", "CLOUDSDK_", "FIREBASE_", "SIMCTL_CHILD_FIREBASE_"))}
    results = args.products / "auth-emulator-tests.xcresult"
    status = subprocess.run([
        "xcodebuild", "test-without-building", "-xctestrun", str(path),
        "-destination", "platform=iOS Simulator,id=" + args.destination,
        "-parallel-testing-enabled", "NO",
        "-resultBundlePath", str(results),
        "-only-testing:KCCTests/FirebaseEmulatorIntegrationTests"
    ], env=environment).returncode
    if status:
        return status
    try:
        summary = subprocess.run(["xcrun", "xcresulttool", "get", "test-results", "summary", "--path", str(results)],
                                 check=True, capture_output=True, text=True)
        check_summary(json.loads(summary.stdout))
    except (ValueError, subprocess.CalledProcessError):
        print("FAIL: native Auth test was not recorded as one passed, non-skipped test", file=sys.stderr)
        return 1
    print("PASS: actual native Auth emulator test ran and passed (not skipped)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
