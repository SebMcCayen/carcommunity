# Supporter crown rollout

Cosmetic projection: `users/{uid}.supporterBadgeEligible` is backend-owned;
`showSupporterBadge` is owner-owned, boolean, absent = true. Missing eligibility
means false. These fields are visible to authenticated profile readers; hiding
the crown is a display preference, not concealment of subscription eligibility.
No billing dates, tokens or private subscription reads are added to profiles.

`applyEntitlement` writes eligibility with the subscription in the same batch
on purchase, RTDN update, manual grant, expiry and revoke. It recomputes on
Supporter-to-Plus downgrade even though activeMember stays true. Eligibility
uses the existing active/grace 72-hour expiry tolerance; cancelled records end
at paid expiry. It never modifies the preference. Expiry/revoke retains the
choice for later reactivation. Missing/malformed records fail closed.

## Existing accounts — no manual live backfill required

After separately approved deployment, the existing provider-gated reconciliation
sweep repairs the cosmetic projection for every existing subscription it scans
(200 records per run, rotating cursor, every six hours). Each repair transaction
re-reads the latest subscription and profile, only writes changed eligibility,
and never changes claims, entitlement, or preference. Transaction conflicts
retry against the latest renewal/downgrade. Missing profiles are not recreated.
Manual subscriptions are covered when this existing sweep is enabled too.
With the Google provider disabled, the sweep remains disabled: no live setting
should be changed just for migration. Those accounts acquire the projection on
their next explicit entitlement update; a separately approved backfill may call
`reconcileSupporterBadge(uid)` over a bounded list of subscription IDs if needed.

Deploy backend/rules before the Android client. Until backfilled, older profiles
simply have no crown. Do not initialize/reset visibility on renewal or migration.
The scheduled projection can lag until its sweep; the client does not invent a
second billing clock or infer eligibility from activeMember. Own profile uses
its existing listener; member profiles observe only the public badge projection
while visible and dispose that listener on exit/blocking. Offline snapshots may
remain stale until reconnect. No live migration or deployment is part of this commit.
