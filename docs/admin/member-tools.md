# Member tools: profile, warnings and retry-safe points

This branch stacks on `codex/admin-pagination` (#1220). No production deployment,
backfill or shared Auth mutation is part of this PR.

## Contracts and authority

- `admin-getMember`: fresh active admin authority, canonical safe profile,
  authoritative wallet, existing lifecycle lastLoginAt. Login frequency is null
  because no suitable frequency history exists. Never reads userPrivate/Auth PII.
- `admin-updateDisplayName`: reviewed previous name, mandatory reason,
  actor-bound idempotency receipt. Rejects concurrent changes; updates the
  canonical live profile. Historical chat author snapshots stay unchanged.
- `admin-warnUser`: preserves the deployed legacy request contract; adds expiry
  (absolute ISO instant or server-relative integer days), optional private note,
  actor check and idempotency key. Backend clock decides active status.
- `admin-listWarnings`, `admin-revokeWarning`, `admin-setWarningExpiry`:
  admin-only paged lifecycle, version check and immutable audited decisions.
  Revoked warnings cannot be reactivated; expired warnings may be extended after
  explicit review. Existing legacy decisions are not made active retroactively.
- `account-listWarnings`: authenticated own-account only, including suspended
  members. Returns only active reason/creation/expiry/version and server-derived
  remainingMillis. Does not accept a target user, note or client clock.
- `accountWarningSignals/{uid}`: own get only; no reason or note. A change wakes
  the native profile reader. Private warnings and retry receipts have no client
  access; account erasure recursively removes both plus the signal.
- `points-adminAdjust`: optional actor-bound idempotency key. New web requests
  always provide it. Identical retries reuse one receipt; conflicting key reuse
  fails. Positive whole amounts 1–100000; one common KP/XP balance, no balance
  replacement. Existing legacy requests remain supported. Audit + wallet + entry
  remain atomic. Reversal remains the separately guarded compensation endpoint.

## Native own-profile presentation

Both Android and iOS own profiles use the same account-listWarnings contract,
never the public member profile. Pages are bounded and can load older active
warnings. A safe signal triggers refresh; iOS also refreshes every 30 seconds
while visible to recover missed updates. Network failure is shown explicitly.
Server remainingMillis is projected from request-start monotonic uptime, so
network delay consumes validity and changing device date/time does not extend
warnings. Reasons are removed locally when their deadline passes. Server time
remains authoritative after resuming/reloading. User-ID checks and task/component
lifecycles discard responses for another session.

## Rollout gates

Required warning indexes are declared in firestore.indexes.json. Emulator tests
cannot confirm deployed index readiness. Deploy rules/indexes/functions together
only with owner approval, then test an isolated staging admin → Android/iOS
member create/expiry/revoke round trip before production acceptance. Native CI
compilation and model tests are not device or deployed-backend acceptance.

The CarCommunity-only blacklist is a separate change: it must retain verified
Google/Apple provider identity matches across account deletion/re-registration,
without disabling shared Firebase Auth or modifying Driver access. Existing
shared suspension/deletion controls must not be advertised as that blacklist.
