# Paid live-map ring

The gold ring is a public cosmetic for Plus and Supporter, separate from the
Supporter-only profile crown. It never grants permission or exposes a purchase
token, receipt, price, store account, renewal setting or paid-period date.

Only backend-owned `users/{uid}.membershipMapRingEligible` is public. Owner-only
boolean `showMembershipMapRing` defaults to visible when absent. Rules prevent
other users, client admins and owners from forging the eligibility projection.
Renewal, cancellation, backfill and reconciliation never reset the preference.
Older clients and the existing Supporter crown remain compatible.

## Private expiry

`subscriptions/{uid}.mapRingRefreshAt` is private scheduling metadata. Its
existing owner/admin read boundary and backend-only writes are unchanged.
The authoritative entitlement writer atomically updates the public boolean and
private due field. A cancelled renewal retains the ring for the paid period;
expiry/revoke notifications clear it. The cosmetic deadline adds none of the
separate entitlement backstop's 72-hour outage tolerance.

A cosmetic-only five-minute scheduler queries at most 200 due private fields
using a single-field index. Each transaction re-reads the current subscription
and profile; a concurrent renewal wins, and a deleted profile is never recreated.
After expiry it clears the boolean and removes only the private due field. It
never revokes claims or rewrites receipts. Failed repairs remain due for retry.
Normal updates are real time; missed notifications have a best-effort backstop
of about five minutes plus scheduler/retry delay, not an exact-time guarantee.
No exact subscription date is copied into public profile data or sent to a map
viewer. The existing bounded reconciliation repairs missing cosmetic fields.

The additional scheduler is one small Firebase job (288 bounded queries/day,
no per-GPS reads/writes); existing cloud quotas and scheduler billing apply.
No new third-party service or per-user paid integration is needed.

## Android contract

Watch only already-authorized drawn marker users' public profiles with bounded,
lifecycle-owned listeners independent of GPS frequency. Missing/malformed
eligibility fails closed. Boolean/preference changes repaint both nearby and
convoy markers without restarting a session. Club-logo overlays use the same
backend-owned `clubBadge` as profiles, including membership/logo removals.
No private subscription reads or broader location visibility are introduced.

## Rollout and repair

Deploy reviewed additive Firestore rules and only the six affected subscription
entrypoints (`verify`, `grantEntitlement`, `handleRtdn`, `expireLapsed`,
`reconcileEntitlements`, new `refreshMapRings`) before Android rollout. Preserve
existing identities, secrets, provider configuration, Pub/Sub and App Check.

For existing accounts, invoke `reconcileSupporterBadge` over a bounded verified
set of subscription IDs. It updates only changed cosmetics/private due metadata,
never preferences, verified receipt fields or claims. Do not enable a disabled
provider or recreate missing profiles. Record live deployment/repair separately
from emulator/CI evidence. No public deadline was deployed by this feature.
Backout disables the cosmetic scheduler and hides/rolls back the Android ring;
do not delete subscription state, preferences or club data.
