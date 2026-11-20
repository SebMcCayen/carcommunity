# Explicit Partner consent validation

Base: `db7532d9403a7f6eb4e3c43468f25d2b2b5ac1e8`. The PR is source/test work only. No production data, flags, deployments, app delivery or physical-device acceptance is changed by this record.

## Executed checks

| Area                    | Executed result                                                                                                              |
| ----------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| Backend units           | 151 files / 3,058 cases passed                                                                                               |
| Backend static checks   | Full test typecheck and ESLint passed; product build passed                                                                  |
| Real Firebase emulators | Seven files / 93 cases passed, zero failures/skips; guarded outer CLI exited zero                                            |
| Baseline control        | Fourteen selected assertions failed for the expected reasons against unchanged baseline; fourteen unrelated cases unselected |
| Android                 | 25 focused JVM/Robolectric cases passed, zero failures/skips; current-copy verification is included in PR evidence           |
| iOS tooling             | 67 tooling tests passed; Linux did not execute native XCTest                                                                 |
| Repository scripts      | 104 cases passed, zero failures/skips                                                                                        |

Real emulator coverage comprises onboarding/Auth 24, direct Insights transport 6, saved-drive heat 16, billboard transport 6, central consent/rules 28, admin analytics projections 12 and the operational Partner SDK/HTTP workflow 1. All use disposable demo services with inherited no-cloud guards. Auth transport tests use real Firebase SDK identities and callable HTTP; domain transaction tests are separately identified.

The same final central-consent test file ran against corrected and baseline source. The fourteen selected baseline failures cover nine legacy-default interaction types and five changes of actual consent/account-deletion authority after company validation and before opening the real transaction. Reached-stage and changed-record assertions precede the expected rejection assertion. No authorization result, business transaction or retry was fabricated. A preliminary run with weaker assertion ordering was superseded and is not the decisive baseline evidence.

Both local guarded emulator CLI lifetimes shut down cleanly. The baseline CLI exited one for its expected failures; the corrected CLI exited zero. No physical phones or production App Check attestation were involved. Full-index CI remains distinct from the local resource-bounded selected-export harness.

## Consent, reports and parity assertions

- Default-OFF provisioning, old/new onboarding inputs, explicit v1 evidence, idempotent replay and preservation of existing private choices.
- Every interaction type excludes missing/declined/legacy/malformed consent. Current grants record only the expected scoped event fields and constant consent-version provenance.
- Owner-only rules accept fresh atomic grants and legacy withdrawal; fabricated metadata, other-account updates and stale boolean-only regrant fail.
- Account restriction, pending deletion and purge progress are authoritative at collection time. No event survives those deterministic pre-write changes.
- Historical unmarked raw events/aggregates/heat never become eligible automatically. Every optional report slice enforces the minimum account-contributor floor; corrupt or stale records fail closed.
- Saved routes predating consent are rejected before download; withdrawal, regrant and purge during download/publication discard pending contributions. Published cells contain no owner index; provenance/freshness checks cap lifetime at 48 hours.
- Android and iOS render OFF until effective consent, lock malformed/cached/pending/error state, and use matching payloads and Swedish/English wording. Withdrawal/retained-result copy is in the rendered notice on both onboarding and Privacy.
- Operational reservations/redemptions and exact delayed totals continue without optional analytics consent.

## CI and remaining boundaries

All current-head registered GitHub checks must finish successfully, including the full native iOS plan. New native Privacy/Onboarding coverage contains 21 cases across those suites; source presence is not execution evidence. The final PR records actual CI outcomes and any infrastructure retry separately.

Physical Android/iPhone camera, GMS, configured Mapbox and online/offline acceptance stay open per user instruction. The [source security assessment](../security/partner-consent-review.md) and actual managed Codex Security execution are separate records. A launcher error or missing sealed report cannot be called a completed managed scan. Deployment, client delivery and activation require separate authorization.
