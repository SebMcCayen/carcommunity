# Partner consent security assessment

Baseline: `db7532d9403a7f6eb4e3c43468f25d2b2b5ac1e8`. This review covers the corrected optional consent flow and supporting Partner boundaries. Actual executed tests and current-head CI belong to the PR record. A source review is not a completed managed scan, production release approval or legal certification.

## Trust model and reviewed surfaces

- Firebase Auth/App Check and current account access protect callable entrypoints. Consent evidence belongs to `userPrivate/{uid}` and cannot be written on behalf of another account.
- Clients are untrusted. Boolean defaults, cached reads, stale onboarding retries and retained metadata cannot create an affirmative current choice. SDK grants need fresh rules-validated server time; the onboarding server stamps only a paired v1 choice on first completion.
- The shared event transaction is the collection boundary for both direct Insights and billboard producers. It rechecks consent and account-deletion state with the event write, preserving daily scoped deduplication and creating no raw UID or new cross-partner consent timestamp.
- Aggregation and administrative projections are separate boundaries. Both suppress unversioned history; every optional metric gets the account-contributor floor, including defensive read-time checks on corrupt or previously weaker records.
- Saved route files are personal source data. The heat job checks consent before downloads, excludes pre-consent drives, and rechecks epoch/account fences before a pure transactional publication. Published output contains no participant list and expires after 48 hours.
- Operational reservations/quotas/QRs, one-use enforcement, manager/redeemer scoping, canonical Partner erasure and redemption snapshots remain the separate reviewed contract in [the prelaunch assessment](partner-portal-prelaunch-review.md). No optional consent gate is added to operational redemption accounting.

## Validation expectations

Tests cover missing/malformed/legacy evidence, all nine interaction types, positive versioned consent, private event shape, owner-only rules, fabricated timestamps/versions, legacy revoke/regrant, onboarding replay and default-OFF provisioning. The retired backfill must fail before any Firebase access.

Deterministic authority-change tests run the real collector through company validation, verify its initial authority, change the actual stored consent/deletion state immediately before opening the real transaction, then assert that the reached stage and changed state are proven before asserting rejection. They do not fabricate transaction results or claim to force a pessimistic-lock conflict.

Heat tests exercise actual route downloads and publication-stage changes, not a production test-hook API. Historical raw/aggregate/heat fixtures must remain excluded. The existing SDK/HTTP Partner redemption workflow must still publish exact operational counts without optional analytics consent.

Android JVM/Robolectric and iOS unit/UI coverage account for OFF defaults, server-confirmed state, malformed/cache/pending locks, matching wording and payloads. Native XCTest is executed by macOS CI; this Linux host does not prove physical iPhone behavior.

## Residual limits and disposition

The [consent contract](../contracts/partner-analytics-consent-v1.md) defines prospective withdrawal, raw-event expiry eligibility, prior-valid aggregate retention and the 48-hour published heat limit. No new UID-linked analytics index is introduced to retrospectively identify aggregate participants. Scoped hashes remain pseudonymous and can be rederived by an actor already holding the UID; thresholding is not mathematical anonymity or Sybil resistance.

Legacy unknown-consent data is not backfilled, relabeled or exposed by the corrected readers. Existing production records are not modified by this source change. The optional consent gap is addressed in source; deployment and client delivery remain separate release work. Physical-device acceptance remains explicitly open.

Managed Codex Security execution is recorded through its own tools/artifacts. A failed launcher or missing sealed report must be disclosed; independent review and passing tests must never be represented as a completed managed scan. The source review can close only its stated surfaces and documented residual limits.
