# Controlled Internal Testing release: Android 1.1.5 (60)

The owner approved signed build and publication to the existing Internal Testing
track with priority 5. Production, migration, strict Rules and Hosting are outside
that approval. No iOS version has been distributed, including TestFlight.

## Immutable input

- Signed-build workflow: `308749238`, run `40793833224`.
- Source: `27677adf03c1b41f9f5f51f832fdcf5d6cf02da6`, after PR #1249.
- Bundle artifact: `app-release-aab`, containing `app-release.aab`.
- Approved AAB SHA-256:
  `34f74aeb29a5aaee47ee31b02fd20ef45b87d1c2d0513376d7d20fd0e26f36a0`.
- Package `com.kungsbackacarcommunity.app`, version code `60`.

The signed build passed AAB signature-presence, full APK signature, native ELF and
APK ZIP 16 KB checks. Play validates the uploaded AAB against its upload key.
The publisher checks provider-confirmed version and SHA-256 before changing a track.

## Authentication

Use the existing main-restricted GitHub Workload Identity Federation provider and
service account through `google-github-actions/auth@v3`. Mint a short-lived
`androidpublisher` token without creating a key or exporting a credential file.
The workflow does not create accounts, enable APIs, change IAM or grant Play roles.
If required access is absent, stop and report it rather than broadening permissions.

The expected existing identity from `scripts/setup-wif.sh` is
`github-deploy@kungsbacka-car-community.iam.gserviceaccount.com`; the actual workflow
uses the existing `WIF_SERVICE_ACCOUNT` environment reference. Google Play must
authorize that identity for CarCommunity. Minimum access is app information and
releasing to testing tracks; Production publishing, financial data and administrator
rights are unnecessary. See [Google API setup](https://developers.google.com/android-publisher/getting_started)
and [release permissions](https://support.google.com/googleplay/android-developer/answer/9859348).

## Inspect, then publish

Run `Publish approved Android 60 to Internal Testing` from main with mode `inspect`
and the approved bundle hash. It validates the successful signed-build identity,
downloads only that artifact and verifies its bytes before authentication.
Inspection creates and discards only its own uncommitted Play edit.

The provider's [track documentation](https://developers.google.com/android-publisher/tracks)
names `qa`; existing integrations also use `internal`. The publisher lists existing
tracks and requires exactly one of these known aliases, with exactly one completed
release containing only version `59`. It never creates a track or silently selects
between ambiguous aliases. Production must still have maximum version `54`.
Unexpected or unfinished releases stop before upload or commit.

After successful inspection, mode `publish` uploads only the hash-bound AAB,
requires version `60` and the same provider-confirmed hash, and supersedes the
reviewed completed testing release with version `60`, status `completed`, priority
`5` and Swedish notes. It validates and commits that edit, then reads back the
testing release and verifies the Production snapshot is unchanged. No other track,
tester membership, listing, pricing, purchase or subscription is modified.

[Priority is fixed at rollout](https://developer.android.com/guide/playcore/in-app-updates/kotlin-java#check-priority).
An existing version `60` is accepted only if bundle hash, status and priority already
match. A failed readback after commit retains the commit-accepted fact and requires
operator review instead of claiming no publication. The sanitized JSON receipt is
retained; no access token is logged or uploaded.

## Validation boundary

All script tests run under `scripts/run-no-cloud.mjs` with in-memory fake Play
responses. They never request credentials or contact Google/Firebase. Credentialed
inspection and publication are approved release operations, not functional cloud
tests. Production app/Firestore/Functions probes remain excluded.
