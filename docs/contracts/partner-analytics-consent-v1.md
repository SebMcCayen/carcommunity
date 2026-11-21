# Optional Partner analytics consent v1

Operational offer-redemption totals and optional analytics are separate. Server-confirmed redemptions continue to count without optional consent. Reserving, inspecting or redeeming an offer never grants consent to behavioral or saved-drive analysis.

## Effective choice

An optional contribution requires all three owner-private fields:

| Field                                 | Required value                              |
| ------------------------------------- | ------------------------------------------- |
| `anonymousPartnerStatsOptIn`          | Boolean `true`                              |
| `anonymousPartnerStatsConsentVersion` | Numeric version `1`                         |
| `anonymousPartnerStatsConsentAt`      | Genuine positive Firestore server timestamp |

Legacy booleans cannot prove an affirmative choice because earlier provisioning and UI defaults were ON. Existing booleans are preserved; no migration, login, reinstall or new-device initialization upgrades them to consent. New accounts start OFF. The former opt-in backfill command is retired and refuses execution before initializing Firebase.

The Android and iOS onboarding switches start OFF. On first completion, a new client may send the optional boolean together with `anonymousPartnerStatsConsentVersion: 1`; the callable supplies the server timestamp. Old requests remain accepted, but a legacy `true` cannot grant consent. No choice leaves the provisioning default unchanged. Replayed onboarding requests never overwrite a later Privacy choice.

Privacy saves are targeted atomic owner updates of the boolean, version, server consent timestamp and `updatedAt`. Rules require a fresh `request.time` timestamp when enabling, including when an old version/timestamp remains after withdrawal. A legacy boolean-only `false` may revoke; a boolean-only false-to-true update cannot regrant. Mandatory onboarding audit timestamps remain backend-only.

Missing, legacy or unsupported consent evidence renders OFF. Present malformed fields, read errors, cache-only snapshots and pending writes are non-authoritative and lock saving/analytics. Both clients preserve pending drafts while awaiting a new server-confirmed read. Client clocks do not determine timestamp validity.

## Collection and reporting

Both Insights callable events and billboard interaction events share one collector. It reads current consent, user existence/restrictions, deletion request and purge progress in the same Firestore transaction as deduplication/event creation. A consent timestamp later than the interaction is not applied retroactively. Client gates are an optimization; the server remains authoritative.

Raw events add only the constant backend-assigned `consentVersion: 1`. They retain the existing partner-scoped hash and event time, but add no UID, cross-partner consent identifier or copied consent timestamp. They are pseudonymous, backend-only data, eligible for cleanup after seven days; physical deletion can lag.

Only versioned events enter corrected aggregates. Unmarked historical aggregates are suppressed at reads. When an eligible slice is empty, a bounded Firestore transaction removes only unmarked or unsupported-version records and leaves an existing v1 aggregate unchanged, including its update timestamp. Raw-event cleanup can therefore retain previously valid, thresholded results. Every optional interaction slice requires at least ten distinct account contributors; configuration may raise the floor, which is revalidated at read time. This is not a guarantee of distinct physical people or mathematical anonymity.

Withdrawal stops new contributions. Previously valid contributions may remain in the seven-day raw-event pipeline, and their thresholded aggregate results persist. Canonical account deletion has its existing analytics-hash retention boundary; this change does not claim immediate erasure of historical raw events or backups. Existing unknown-consent history is never relabeled as eligible.

## Saved-drive heat

Only completed drives starting after the latest effective consent timestamp qualify. Consent/account state is checked before Storage downloads and again transactionally before publication. Changed or withdrawn consent discards that participant's pending contribution; no Storage or Auth I/O runs inside a retried transaction.

Firestore and Storage are not one atomic service: a withdrawal after a successful pre-download check can overlap an already-started read. The publication recheck discards that pending contribution; this is not a promise that every in-flight Storage read is instantly cancelled.

Published documents contain only aggregate cells and provenance, with no participant index. Old unmarked, malformed, future-dated or older-than-48-hour snapshots are suppressed. A correctly consented published aggregate is retained until replaced or expired; withdrawal does not retrospectively identify and remove an individual from that anonymous output. The next rebuild excludes withdrawn accounts.

The job is bounded to 5,000 rides, 250 contributing accounts, 200,000 retained per-ride cell references, 2 MiB compressed routes and 20,000 decoded points per route. Exceeding a budget fails without publishing a partial replacement; the previous snapshot still expires normally.

## Release boundaries

Deploy the corrected rules/backend before delivering clients that send the new tuple. Legacy clients can continue ordinary app use and revoke optional analytics, but cannot enable v1 analytics with a boolean-only write. Optional reporting can remain empty until enough users actively consent to the new wording. No existing data is automatically promoted or backfilled.

Source merge, backend/rules deployment, mobile delivery and feature activation require separate authorization. Physical Android/iPhone camera, configured Mapbox, GMS and online/offline acceptance remain open per the user's instruction. Automated emulator/CI evidence must not be presented as physical-device acceptance.
