# Manual Android handoff and admin activation

The owner uploads Android releases manually in Play Console. Do not retry the
GitHub Play publisher or request additional service-account access unless the
owner asks to use that route again. An upload, a draft, an Internal Testing
release and Production availability are separate states.

## Approved Android artifact

- Version: **1.1.5 (60)**, package `com.kungsbackacarcommunity.app`.
- Source: `27677adf03c1b41f9f5f51f832fdcf5d6cf02da6` (PR #1249).
- Signed build: [40793833224](https://github.com/SebMcCayen/carcommunity/actions/runs/40793833224).
- Play artifact: `app-release-aab`, containing `app-release.aab`.
- AAB SHA-256: `34f74aeb29a5aaee47ee31b02fd20ef45b87d1c2d0513376d7d20fd0e26f36a0`.
- A separate signed APK artifact is available for direct installation; it does
  not replace verification of the build actually distributed through Play.

The signed build passed its signature and 16 KB checks. Download the AAB from
the linked run, upload it to the existing Internal Testing track, and confirm
Play identifies version 60. Use the Swedish release notes below. Preserve the
existing tester list and unrelated releases. Record when Play reports the release
as available to testers, and verify a tester can obtain that version. Do not
record a saved draft or successful upload as published.

```text
Varningar med giltighetstid visas i din profil
Tydligare information när CarCommunity-åtkomsten är begränsad
Uppdaterad hantering av modererade chattmeddelanden
```

Internal Testing was approved. Production rollout remains a separate owner
decision; Internal Testing availability does not cover existing Production users.

## Mandatory-update decision remains open for manual publishing

The owner selected a mandatory Android update at strict activation. The existing
plan uses Play's `inAppUpdatePriority: 5`. Google documents that priority is set
through the Developer API when a release rolls out and cannot be changed later.
Manual upload by itself does not prove that field was set. Do not mark priority 5
or a mandatory update as verified from an upload confirmation.

Before activating strict access, record a reviewed way to satisfy the mandatory
update decision and make the compatible version available to Production users.
If a manually published release has no priority 5, review that policy explicitly;
do not silently replace it with a voluntary rollout or promise a later edit to
the same published release's priority.

Source: [Google's update-priority documentation](https://developer.android.com/guide/playcore/in-app-updates/native#check-priority).

## Preparation that can continue while the owner uploads

Under the existing production-preparation approval, an operator may refresh
read-only migration counts, record deployed rules/Hosting rollback references,
check index readiness and compare deployed backend metadata. These are real
production management reads, not functional tests. Record their time and source
revision; counts can change as people register.

Keep Functions and Hosting deployment workflows paused. Keep both preparation
policies unchanged. Do not invoke live callables or create synthetic live users,
messages, warnings or access restrictions to validate the release.

The default backfill command is read-only:

```sh
node functions/scripts/backfill-carcommunity-access.mjs \
  --project=kungsbacka-car-community \
  --database-url=https://kungsbacka-car-community-default-rtdb.europe-west1.firebasedatabase.app
```

Its dry-run `mirrorMismatches` counter is not a mirror-verification result: only
`--verify` reads and compares UID mirrors. Do not use a dry-run zero as acceptance.

## Remaining activation gates

1. Confirm compatible Android availability for affected Production users and the
   reviewed mandatory-update policy. No iOS version has been distributed, even
   through TestFlight, so there are no installed iOS clients to upgrade. Retain
   Android/iOS SDK, emulator/simulator and build evidence; first iOS distribution
   remains a separate release decision.
2. Obtain separate approval for the concrete migration window. Refresh the
   inventory immediately before apply, review unavailable Auth accounts and
   existing restrictions, build Functions from the reviewed source, and use the
   guarded `--apply` command described in [the access rollout](carcommunity-access.md#controlled-rollout--manual-release-gates).
   This writes product records and mirrors. It can invoke the deployed mirror
   triggers and incur ordinary production operation charges; it is not a test.
3. Require a successful `--verify` with zero missing initialized records and zero
   mirror mismatches. Review retained identity mirrors, shared restrictions,
   retry delivery, indexes and rollback references. Do not erase blacklist,
   warning, receipt or audit history or clear shared Auth/Driver restrictions.
4. With explicit cutover approval, deploy and verify the reviewed Firestore,
   RTDB and Storage rules. Set RTDB initialization enforcement to true before
   Firestore `enforceInitialized:true, rolloutState:'ready'`. Stop on a failed
   prerequisite; do not make policy permissive to bypass a failed decision.
5. With separate Hosting publication approval and all prior gates satisfied,
   publish the reviewed web build. Record the source, deployed Hosting version
   and previous release. Confirm the public Firebase/App Check configuration and
   the intended admin URL before calling the new admin page live.

Functional tests continue only against guarded local `demo-*` emulators. Live
functional acceptance, if later requested, needs its own explicit scope; existing
production-preparation approval does not authorize cloud-based tests. Green CI
does not imply store, physical-device or production acceptance.

Use [the release candidate guide](release-candidate.md#recovery) for recovery.
Keeping the previous Hosting release does not undo a committed product decision;
recover access through audited restore/unlist and mirror repair.
