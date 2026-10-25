#!/usr/bin/env python3
"""Generate an unsigned TestFlight handoff. No credentials, signing, uploads or cloud calls."""
import argparse
import importlib.util
from pathlib import Path
import plistlib
import sys

SPEC = importlib.util.spec_from_file_location("release_preflight", Path(__file__).with_name("release-preflight.py"))
preflight = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preflight)


def prepare(root, output):
    bundle = preflight.check_source(root)
    spec = (root / "project.yml").read_text()
    version = preflight.setting(spec, "MARKETING_VERSION")
    build = preflight.setting(spec, "CURRENT_PROJECT_VERSION")
    output.mkdir(parents=True, exist_ok=False)
    options = {"method": "app-store-connect", "signingStyle": "manual",
               "teamID": "SET_AFTER_ENROLLMENT", "manageAppVersionAndBuildNumber": False,
               "provisioningProfiles": {bundle: "SET_DISTRIBUTION_PROFILE_NAME"}}
    (output / "ExportOptions.example.plist").write_bytes(plistlib.dumps(options))
    report = f"""# iOS TestFlight handoff — {version} ({build})

Bundle ID: `{bundle}`

**Source checks passed. Signing, production services and device acceptance are NOT verified.**
This preparation command does not contact Apple, Firebase or Mapbox, purchase services,
create signing resources, archive the app, or upload a build. No credentials are read.

## Owner actions after Apple enrollment

- [ ] Confirm the enrolled team and accept required agreements yourself.
- [ ] Register the bundle ID and Apple sign-in, APNs and App Attest capabilities.
- [ ] Supply the matching production Firebase plist; configure Apple Auth, App Check and APNs.
- [ ] Create the App Store Connect app record and decide whether paid plans ship in MVP.
- [ ] If selling plans: configure products, agreements and sandbox testers, then verify purchase,
      restore, expiry and refund behavior. Otherwise keep public purchases disabled.
- [ ] Explicitly approve any paid service, production operation, or artifact-storage cost.

## Signed build and review

- [ ] Run `release-preflight.py --release` locally with approved production inputs.
- [ ] Archive and validate the Release app using the enrolled team in Xcode Organizer.
- [ ] Inspect signed entitlements, privacy manifest, Firebase bundle ID and Mapbox runtime token.
- [ ] Upload only after owner approval; install the processed build on an iPhone with TestFlight.
- [ ] Execute `docs/ios-release-readiness.md`; record build, device, results and linked defects.
- [ ] Complete App Store metadata and privacy answers; provide working review access/instructions.

## First iPhone test session

1. Real Apple sign-in, cancellation, sign-out and session restoration after relaunch.
2. Map rendering, denied/granted GPS, layers, recenter, Partners and incident reporting.
3. Background recording/sharing, Stop and Hide; no live sharing merely by opening the map.
4. Two-account chat, reporting/blocking, photo uploads and deletion of a disposable account.
5. Push delivery/tap routing and account isolation; offline recovery and critical VoiceOver flows.

The local fake Apple account is not App Review access. Do not ship emulator endpoints,
dummy credentials or test fixtures. Turn-by-turn navigation is intentionally outside iOS MVP.

## Export options template — not ready for export

Replace the two `SET_...` values only after provisioning. This file does not create a profile.

```xml
{plistlib.dumps(options).decode().strip()}
```
"""
    (output / "Handoff.md").write_text(report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        prepare(Path(__file__).resolve().parents[1], args.output)
    except (preflight.PreflightError, OSError, ValueError) as error:
        message = str(error) if isinstance(error, preflight.PreflightError) else "Cannot create a new handoff directory"
        print("FAIL: " + message, file=sys.stderr)
        return 1
    print("PASS: unsigned TestFlight handoff prepared; no credentials, signing or uploads used")
    return 0


if __name__ == "__main__":
    sys.exit(main())
