#!/usr/bin/env python3
"""Fail the stable validate-ios check unless every required job succeeded."""

import json
import os
import sys


REQUIRED_JOBS = (
    "ios-preflight",
    "ios-build-debug",
    "ios-build-native",
    "ios-community-ui",
    "ios-simulator",
    "ios-build-release",
    "ios-release",
    "ios-native",
    "ios-device",
)


def validate_results(results):
    if not isinstance(results, dict) or set(results) != set(REQUIRED_JOBS):
        raise ValueError("Expected results for all nine required iOS jobs.")
    failed = [
        job for job in REQUIRED_JOBS
        if not isinstance(results[job], dict)
        or results[job].get("result") != "success"
    ]
    if failed:
        raise ValueError("Required iOS jobs did not succeed: " + ", ".join(failed))


def main():
    try:
        results = json.loads(os.environ.get("IOS_GATE_RESULTS", ""))
    except (ValueError, TypeError):
        print("::error::Missing or invalid iOS job results.")
        return 1
    try:
        validate_results(results)
    except ValueError as error:
        print(f"::error::{error}")
        return 1
    print("All required iOS jobs succeeded.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
