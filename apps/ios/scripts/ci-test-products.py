#!/usr/bin/env python3
"""Transfer exact-commit Simulator test products without rebuilding them."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import plistlib
import re
import subprocess
import tarfile


ROOT = Path(__file__).resolve().parents[3]
LOCK = ROOT / "apps/ios/KCC.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved"
CONTEXT_FIELDS = ("checkout", "pr_head", "xcode", "sdk", "arch", "lock")
TARGETS = {"debug": {"KCCTests", "KCCUITests"}, "release": {"KCCUITests"}}


def command(*arguments, timeout=30):
    return subprocess.run(arguments, cwd=ROOT, check=True, capture_output=True,
                          text=True, timeout=timeout).stdout.strip()


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def context(kind):
    checkout = command("git", "rev-parse", "HEAD")
    if os.environ.get("GITHUB_SHA", checkout) != checkout:
        raise ValueError("Checkout differs from this workflow's commit.")
    pr_head = os.environ.get("PR_HEAD_SHA", checkout)
    if not re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", pr_head):
        raise ValueError("Invalid PR head commit.")
    sdk = "iphoneos" if kind == "device" else "iphonesimulator"
    return {"checkout": checkout, "pr_head": pr_head,
            "xcode": command("xcodebuild", "-version"),
            "sdk": sdk + ":" + command("xcrun", "--sdk", sdk, "--show-sdk-build-version"),
            "arch": platform.machine(), "lock": digest(LOCK)}


def cache_fingerprint(current):
    # Source changes get a new primary cache key in the workflow. Content-addressed
    # compilation may safely reuse unchanged inputs from the matching toolchain.
    inputs = {key: current[key] for key in ("xcode", "sdk", "arch", "lock")}
    return hashlib.sha256(json.dumps(inputs, sort_keys=True).encode()).hexdigest()[:24]


def destination(runtime=None):
    # Producers honor the CI runtime pin. Consumers must use the exact runtime
    # recorded in their artifact, even if the environment pin changes later.
    if runtime is None:
        runtime = os.environ.get("IOS_TEST_RUNTIME") or None
    # The first runtime inventory on a hosted image can scan for several minutes.
    devices = json.loads(command("xcrun", "simctl", "list", "devices", "available", "--json", timeout=180))["devices"]
    for key, values in devices.items():
        if runtime is not None and runtime != key:
            continue
        for device in values:
            if device.get("isAvailable") and device["name"].startswith("iPhone "):
                return key, device["udid"]
    raise ValueError("No available iPhone matches the prepared test runtime.")


def test_targets(data):
    if "TestConfigurations" in data:
        configurations = data["TestConfigurations"]
        if len(configurations) != 1 or configurations[0].get("IsEnabled", True) is not True:
            raise ValueError("Expected one enabled test configuration.")
        targets = configurations[0].get("TestTargets", [])
    else:
        targets = [{**value, "BlueprintName": name} for name, value in data.items()
                   if isinstance(value, dict) and name != "__xctestrun_metadata__"]
    names = []
    for target in targets:
        if (target.get("IsEnabled", True) is not True or target.get("SkipTestIdentifiers")
                or target.get("OnlyTestIdentifiers")):
            raise ValueError("Prepared products must retain the unfiltered test targets.")
        names.append(target.get("BlueprintName"))
    if any(not isinstance(name, str) or not name for name in names):
        raise ValueError("Invalid test target name.")
    if len(names) != len(set(names)):
        raise ValueError("Duplicate test targets.")
    return set(names)


def validate_products(products, kind):
    configuration = "Debug" if kind == "debug" else "Release"
    if not (products / (configuration + "-iphonesimulator") / "KCC.app").is_dir():
        raise ValueError("Prepared app has the wrong build configuration.")
    files = list(products.glob("*.xctestrun"))
    if len(files) != 1 or test_targets(plistlib.loads(files[0].read_bytes())) != TARGETS[kind]:
        raise ValueError("Expected exactly one complete test-run file for this scheme.")
    return files[0].name


def pack(products, output, kind, runtime, current):
    run_file = validate_products(products, kind)
    output.mkdir(parents=True, exist_ok=True)
    archive = output / "products.tar.gz"
    # Preserve executable modes and framework symlinks across artifact upload.
    with tarfile.open(archive, "w:gz", compresslevel=1, dereference=False) as bundle:
        bundle.add(products, arcname=".")
    manifest = {"schema": 1, **current, "kind": kind, "runtime": runtime,
                "products_path": str(products.resolve()), "xctestrun": run_file,
                "archive_sha256": digest(archive)}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


def validate_manifest(manifest, kind, products, current):
    if manifest.get("schema") != 1 or manifest.get("kind") != kind:
        raise ValueError("Wrong prepared-product format or build configuration.")
    if any(manifest.get(key) != current[key] for key in CONTEXT_FIELDS):
        raise ValueError("Prepared products differ from this commit, lockfile, SDK or toolchain.")
    if manifest.get("products_path") != str(products.resolve()):
        raise ValueError("Prepared test-product paths are not portable to this destination.")
    name = manifest.get("xctestrun", "")
    if not name.endswith(".xctestrun") or Path(name).name != name:
        raise ValueError("Invalid test-run filename.")


def unpack(archive_directory, products, kind, current):
    manifest = json.loads((archive_directory / "manifest.json").read_text())
    validate_manifest(manifest, kind, products, current)
    archive = archive_directory / "products.tar.gz"
    if digest(archive) != manifest.get("archive_sha256"):
        raise ValueError("Prepared test archive failed its integrity check.")
    if products.exists():
        raise ValueError("Refusing to combine prepared products with an existing build.")
    products.mkdir(parents=True)
    with tarfile.open(archive, "r:gz") as bundle:
        bundle.extractall(products, filter="data")
    if validate_products(products, kind) != manifest["xctestrun"]:
        raise ValueError("Restored test targets differ from the prepared manifest.")
    print("Verified " + kind + " test products for checkout " + current["checkout"][:12] + " (" + current["arch"] + ")")
    return manifest


def outputs(values):
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a") as stream:
            for key, value in values.items():
                if "\n" in str(value) or "\r" in str(value):
                    raise ValueError("Invalid multiline workflow output.")
                stream.write(str(key) + "=" + str(value) + "\n")
    else:
        print(json.dumps(values))


def check_summary(summary, expected):
    if (not isinstance(summary, dict) or expected <= 0
            or summary.get("passedTests") != expected or summary.get("totalTestCount") != expected
            or summary.get("failedTests") != 0 or summary.get("skippedTests") != 0):
        raise ValueError("Expected exactly " + str(expected) + " passed tests with no failures or skips.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("context", "pack", "unpack", "check-result"))
    parser.add_argument("--kind", choices=("debug", "release", "device"))
    parser.add_argument("--products", type=Path)
    parser.add_argument("--archive-directory", type=Path)
    parser.add_argument("--runtime")
    parser.add_argument("--results", type=Path)
    parser.add_argument("--expected-tests", type=int)
    args = parser.parse_args()
    if args.command == "check-result":
        if args.results is None or args.expected_tests is None or args.expected_tests <= 0:
            parser.error("Result validation requires a path and positive expected test count.")
        summary = json.loads(command("xcrun", "xcresulttool", "get", "test-results", "summary",
                                     "--path", str(args.results), timeout=60))
        check_summary(summary, args.expected_tests)
        print("Verified " + str(args.expected_tests) + " executed, passed tests with no skips.")
        return
    if args.kind is None:
        parser.error("Product preparation requires a build kind.")
    current = context(args.kind)
    if args.command == "context":
        values = {"cache": cache_fingerprint(current)}
        if args.kind != "device":
            runtime, device = destination()
            values.update(runtime=runtime, destination=device)
        outputs(values)
    elif args.kind == "device" or args.products is None or args.archive_directory is None:
        parser.error("Simulator pack/unpack requires product and archive directories.")
    elif args.command == "pack":
        if not args.runtime:
            parser.error("Packing requires the selected Simulator runtime.")
        pack(args.products, args.archive_directory, args.kind, args.runtime, current)
    else:
        manifest = unpack(args.archive_directory, args.products, args.kind, current)
        _, device = destination(manifest["runtime"])
        outputs({"destination": device, "xctestrun": str(args.products / manifest["xctestrun"])})


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.SubprocessError, tarfile.TarError) as error:
        raise SystemExit("Prepared Simulator products failed validation: " + str(error)) from None
