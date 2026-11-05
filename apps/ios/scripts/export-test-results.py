#!/usr/bin/env python3
"""Export existing XCTest failure evidence for inspection without Xcode."""

import argparse
import json
from pathlib import Path
import subprocess


def export(results, output):
    if not results.is_dir():
        print("No XCTest result bundle exists; no failure evidence to export.")
        return 0

    try:
        output.mkdir(parents=True, exist_ok=True)
    except OSError:
        print("Could not create the XCTest evidence output directory.")
        return 1

    status = {"schema": 1}
    commands = (
        ("attachments", ["xcrun", "xcresulttool", "export", "attachments",
                         "--path", str(results), "--output-path", str(output / "attachments")], 240),
        ("summary", ["xcrun", "xcresulttool", "get", "test-results", "summary",
                     "--path", str(results)], 60),
    )
    for kind, arguments, timeout in commands:
        try:
            result = subprocess.run(
                arguments, check=True, stdout=subprocess.PIPE if kind == "summary" else subprocess.DEVNULL,
                stderr=subprocess.PIPE, text=True, timeout=timeout,
            )
            if kind == "summary":
                summary = json.loads(result.stdout)
                if not isinstance(summary, dict):
                    raise ValueError("Expected a summary object.")
                (output / "summary.json").write_text(
                    json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
                )
            status[kind] = {"status": "success"}
        except subprocess.TimeoutExpired:
            status[kind] = {"status": "error", "reason": "timeout"}
        except subprocess.CalledProcessError as error:
            status[kind] = {"status": "error", "reason": "command_failed", "exit_code": error.returncode}
        except (ValueError, UnicodeError):
            status[kind] = {"status": "error", "reason": "invalid_summary"}
        except OSError:
            status[kind] = {"status": "error", "reason": "io_error"}

    failed = any(value.get("status") == "error" for value in status.values() if isinstance(value, dict))
    try:
        (output / "export-status.json").write_text(json.dumps(status, indent=2) + "\n", encoding="utf-8")
    except OSError:
        print("Could not write the XCTest evidence export status.")
        return 1
    print("XCTest evidence export " + ("had errors; see export-status.json." if failed else "completed."))
    return int(failed)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    return export(args.results, args.output)


if __name__ == "__main__":
    raise SystemExit(main())
