#!/usr/bin/env python3
"""Cache the unchanged, checksum-pinned Turf archive via GitHub's public API.

The release download URL has returned repeated HTTP 504s. SwiftPM consumes the
same archive from its normal binary cache and still performs its own manifest
checksum check. No credentials, mirror, dependency override or extraction.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


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
    if path.is_symlink():
        raise ValueError("Refusing a symlink in the binary archive cache")
    if not path.is_file() or path.stat().st_size != EXPECTED_SIZE:
        return False
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(128 * 1024), b""):
            digest.update(block)
    return digest.hexdigest() == EXPECTED_SHA256


def prepare(cache_directory, opener=urlopen, sleep=time.sleep):
    cache_directory.mkdir(parents=True, exist_ok=True)
    target = cache_directory / CACHE_NAME
    if valid_archive(target):
        return target
    for attempt in range(3):
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(prefix=".turf-", dir=cache_directory, delete=False) as output:
                temporary = Path(output.name)
                request = Request(API_URL, headers={"Accept": "application/octet-stream",
                                                   "User-Agent": "CarCommunity-iOS-build"})
                # Anonymous public API request: no authorization can leak on a
                # redirect to GitHub's release CDN.
                with opener(request, timeout=60) as response:
                    count = 0
                    while True:
                        block = response.read(128 * 1024)
                        if not block:
                            break
                        count += len(block)
                        if count > EXPECTED_SIZE:
                            raise ValueError("Unexpected Turf archive size")
                        output.write(block)
            if not valid_archive(temporary):
                raise ValueError("Turf archive does not match the pinned manifest checksum")
            os.replace(temporary, target)
            return target
        except (HTTPError, URLError, TimeoutError) as error:
            retryable = not isinstance(error, HTTPError) or error.code in {408, 429, 500, 502, 503, 504}
            if not retryable or attempt == 2:
                raise
            print("Retrying the official Turf asset after a network error", flush=True)
            sleep(5 * (attempt + 1))
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    raise RuntimeError("Turf preparation did not complete")


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
