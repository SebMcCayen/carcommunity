#!/usr/bin/env python3
"""Seed the unchanged Gradle wrapper archive from its official release API."""
import hashlib
import os
from pathlib import Path

from github_release_asset import prepare_archive


URL = "https://services.gradle.org/distributions/gradle-9.3.1-bin.zip"
SHA256 = "b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06"
API_URL = "https://api.github.com/repos/gradle/gradle-distributions/releases/assets/347704202"
SIZE = 137_037_885


def cache_key(url):
    # Gradle PathAssembler: unsigned MD5 of the URL, rendered in base 36.
    number = int.from_bytes(hashlib.md5(url.encode("utf-8")).digest(), "big")
    result = ""
    while number:
        number, remainder = divmod(number, 36)
        result = "0123456789abcdefghijklmnopqrstuvwxyz"[remainder] + result
    return result or "0"


def verify_pin(path):
    props = dict(line.split("=", 1) for line in path.read_text().splitlines()
                 if line.strip() and not line.lstrip().startswith("#"))
    expected = {"distributionUrl": URL.replace(":", "\\:"),
                "distributionSha256Sum": SHA256,
                "distributionBase": "GRADLE_USER_HOME", "zipStoreBase": "GRADLE_USER_HOME",
                "distributionPath": "wrapper/dists", "zipStorePath": "wrapper/dists"}
    if any(props.get(key) != value for key, value in expected.items()):
        raise ValueError("Gradle wrapper pin changed; review the trusted asset and cache layout")


def main():
    root = Path(__file__).resolve().parents[1]
    verify_pin(root / "apps/android/gradle/wrapper/gradle-wrapper.properties")
    home = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    directory = home / "wrapper/dists/gradle-9.3.1-bin" / cache_key(URL)
    prepare_archive(directory, "gradle-9.3.1-bin.zip", API_URL, SIZE, SHA256)
    # The real wrapper still checks its committed checksum and unpacks it.
    # Do not manufacture its .ok marker or change distributionUrl.
    print("Verified Gradle 9.3.1 archive is ready in the wrapper cache")


if __name__ == "__main__":
    main()
