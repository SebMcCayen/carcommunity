# Paid live-map ring

The gold ring is a public cosmetic for Plus and Supporter, separate from the
Supporter-only profile crown. It never grants permission or reveals a purchase
token, receipt, price, store account, or renewal setting.

Backend-only `users/{uid}.membershipMapRingEligible` and
`membershipMapRingUntil` are written atomically with every entitlement outcome.
The latter is a public cosmetic display deadline (native Timestamp, or null
only for a perpetual manual grant), not a client-controlled entitlement. A
cancelled renewal retains the ring until the already-paid period ends. At paid
expiry it hides without the entitlement sweep's separate 72-hour outage
tolerance. Membership access and the existing Supporter crown are unchanged.

Owner-only boolean `showMembershipMapRing` defaults to visible when absent.
Renewal, cancellation, backfill and reconciliation never overwrite this choice.
Rules forbid other users, client admins and owners from forging the projection
or deleting/non-boolean-writing the preference. Existing clients remain valid.

## Client contract

Read only already-authorized marker users' public profile documents. Use bounded,
lifecycle-owned listeners independent of GPS frequency; do not open private
subscription reads or broaden map/convoy discovery. Missing or malformed
projection fails closed. A valid future display deadline must be re-evaluated
while displayed and on resume, so expiry needs no new position fix. The owner
visibility preference wins immediately. Club-logo overlays use the existing
backend-owned `clubBadge` projection; removal must remove the overlay too.

## Rollout and repair

Deploy reviewed additive Firestore rules and only the five existing changed
subscription entrypoints (`verify`, `grantEntitlement`, `handleRtdn`,
`expireLapsed`, `reconcileEntitlements`) before releasing the new Android client.
No provider, Pub/Sub, IAM, App Check, pricing or Play settings need changing.

The existing provider-gated bounded reconciliation sweep repairs both cosmetics
in one transaction against the current subscription and profile. It updates only
changed projections; no new scanner, schedule or per-GPS writes are added. To
make existing paid accounts visible sooner, invoke `reconcileSupporterBadge`
over a separately verified bounded set of existing subscription IDs. Do not
create missing profiles, rewrite subscription records/claims or enable a
disabled provider just for this repair. Record production execution separately
from emulator/CI evidence. Backout hides the ring (or rolls back the Android
client), without deleting subscription state, preferences or club data.
