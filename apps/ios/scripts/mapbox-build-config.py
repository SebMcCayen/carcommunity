#!/usr/bin/env python3
"""Write a private xcconfig from the shared public Mapbox runtime token."""
import argparse
import os
from pathlib import Path
import re
import sys
import tempfile


class ConfigurationError(Exception):
    pass


def write_config(output, environment):
    token = environment.get("MAPBOX_ACCESS_TOKEN", "")
    # Restrict the xcconfig value to Mapbox's public, base64url token format.
    # This rejects secret tokens and build-setting/comment/newline injection.
    if not re.fullmatch(r"pk\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", token):
        raise ConfigurationError("MAPBOX_ACCESS_TOKEN must contain a public pk. runtime token")
    output = Path(output)
    if output.suffix != ".xcconfig":
        raise ConfigurationError("The output must be an .xcconfig file")
    temporary = None
    try:
        # NamedTemporaryFile is mode 0600. Atomic replacement also prevents a
        # pre-existing symlink from redirecting the token into another file.
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=output.parent,
                                         prefix=".mapbox-", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(f"MAPBOX_ACCESS_TOKEN = {token}\n")
        os.replace(temporary, output)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        write_config(args.output, os.environ)
    except ConfigurationError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    except OSError:
        # Raw exceptions could contain sensitive paths or input; do not echo.
        print("FAIL: cannot write Mapbox build configuration", file=sys.stderr)
        return 1
    print("PASS: public Mapbox runtime token configured (value hidden)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
