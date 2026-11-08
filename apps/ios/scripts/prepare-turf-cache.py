#!/usr/bin/env python3
"""Cache the unchanged, checksum-pinned Turf archive via GitHub's public API.

The release download URL has returned repeated HTTP 504s. SwiftPM consumes the
same archive from its normal binary cache and still performs its own manifest
checksum check. The optional step-scoped CI token authenticates only to the API, never its
redirect CDN. No mirror, dependency override or extraction.
"""
import argparse
import json
from pathlib import Path
import sys
import time


sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts"))
from github_release_asset import prepare_archive, valid_archive as verify_archive


VERSION = "4.0.0"
ARCHIVE_URL = "https://github.com/mapbox/turf-swift/releases/download/v4.0.0/Turf.xcframework.zip"
API_URL = "https://api.github.com/repos/mapbox/turf-swift/releases/assets/212617961"
# mapbox/turf-swift v4.0.0 Package.swift, also verified against the API payload.
EXPECTED_SHA256 = "ce43384a6f875ab4becdd6bdb7ca60447e5e9133f2acf325dc57be381b52a34c"
EXPECTED_SIZE = 17_668_925
# SwiftPM BinaryArtifactsManager uses spm_mangledToC99ExtendedIdentifier.
CACHE_NAME = "".join(c if c.isascii() and (c.isalnum() or c == "_") else "_" for c in ARCHIVE_URL)


def verify_pin(lock):
    pins = [pin for pin in json.loads(lock.read_text())["pins"]
            if pin["identity"] == "turf-swift"]
    if len(pins) != 1 or pins[0]["state"].get("version") != VERSION:
        raise ValueError("Turf pin changed; review the trusted archive version and checksum")


def valid_archive(path):
    return verify_archive(path, EXPECTED_SIZE, EXPECTED_SHA256)


def prepare(cache_directory, opener=None, sleep=time.sleep):
    return prepare_archive(cache_directory, CACHE_NAME, API_URL, EXPECTED_SIZE,
                           EXPECTED_SHA256, opener=opener, sleep=sleep)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-directory", type=Path,
                        default=Path.home() / "Library/Caches/org.swift.swiftpm/artifacts")
    args = parser.parse_args()
    if sys.platform != "darwin":
        parser.error("The production binary cache preparation is for macOS/Xcode")
    ios = Path(__file__).resolve().parents[1]
    verify_pin(ios / "KCC.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved")
    verify_pin(ios / "NavigationSDK/Package.resolved")
    prepare(args.cache_directory)
    print("Verified Turf 4.0.0 archive is ready in the SwiftPM binary cache")


if __name__ == "__main__":
    main()
