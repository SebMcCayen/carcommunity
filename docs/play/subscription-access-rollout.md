# Approved subscription access alignment

Product approval: 2026-09-05. This is an implementation and Internal Testing
checklist, **not proof of live deployment**. Prices and store product IDs stay
unchanged. Do not enable the legacy global membership switches.

## Access matrix

| Capability                                              | Community       | Plus            | Supporter            |
| ------------------------------------------------------- | --------------- | --------------- | -------------------- |
| Garage vehicles                                         | 2               | 5               | 10                   |
| History browsing                                        | Newest 5        | Rolling 90 days | All retained         |
| Published event details and RSVP                        | Yes             | Yes             | Yes                  |
| Personal event check-in                                 | No              | Yes             | Yes                  |
| Attendee identities                                     | No              | Yes             | Yes                  |
| Public partner information                              | Yes             | Yes             | Yes                  |
| Member partner details and discount codes               | No              | Yes             | Yes                  |
| Supporter profile-picture crown                         | No              | No              | Default on, optional |
| Read announcements                                      | Yes             | Yes             | Yes                  |
| Write manual announcements                              | Admin role only | Admin role only | Admin role only      |
| Crown Hunt participation                                | Yes             | Yes             | Yes                  |
| Crown Hunt daily KP allowance                           | 2,250           | 3,000           | 3,000                |
| Friends, DMs and community chat                         | Yes             | Yes             | Yes                  |
| Convoys/group drives, drive saving and incident actions | Yes             | Yes             | Yes                  |
| Lifetime and monthly driving statistics                 | Yes             | Yes             | Yes                  |

Signed-in account, suspension/deletion, ownership, blocks, moderation, location,
time, rate-limit and App Check restrictions still apply. Administrator access
for moderation is separate from personal paid check-in. Automatic backend
announcements may announce published member-created events; users cannot compose
manual announcements. Notification preferences still apply.

Other-user live-map visibility and saved-route replay are deliberately unchanged.
Do not describe the dormant live-map paywall as an active paid benefit.

## History is visibility, not retention

Cancelling renewal preserves access for the already-paid period. Once entitlement
ends and its lifecycle update is processed, Community sees the five newest
retained drives. Older drives are not deleted. Rejoining Plus reveals the latest
90 days; Supporter reveals all retained history. Statistics always aggregate all
retained owner drives and never return hidden drive IDs or routes. Undated legacy
drives contribute to lifetime totals, not calendar-month totals.

## Crown Hunt semantics

The allowance is Crown-Hunt-only, separate from other economy budgets. Daily KP
resets at Europe/Stockholm midnight (including DST). Existing crown-count limits
and their UTC boundaries remain unchanged. Apply multipliers and reward rounding
before clipping to remaining allowance. A zero-headroom attempt must not consume
the crown or a successful collection slot. Retries and simultaneous claims across
both collection paths must never exceed the allowance or count twice. Spending
does not replenish earned allowance. Upgrading increases headroom without
resetting the counter; downgrading never removes earned KP.

## Review and Internal Testing checklist

- [ ] Unit/type checks, shared contracts and generated Swedish/English strings agree.
- [ ] Free user can read published details and RSVP but cannot obtain attendee
      identities or check in through either the screen or direct requests.
- [ ] Paid user can check in only when existing time/location rules also pass.
- [ ] Free direct reads/callables cannot retrieve member offer details or codes;
      public partner pages still work. Missing/invalid subscription fails closed.
- [ ] Free social/convoy/incident/save flows work; blocked/restricted/unauthenticated
      users remain denied where appropriate.
- [ ] All tiers see identical statistics for identical retained data, including
      more than five drives and drives older than 90 days. Other owners stay private.
- [ ] Both Crown Hunt paths enforce the combined crown-only allowance atomically;
      test retries, concurrency, boost rounding, cap exhaustion and tier changes.
- [ ] Supporter crown appears on own and viewed member profiles, including avatar
      placeholders. Settings toggle persists across restart and devices.
- [ ] Cancellation while paid-through retains crown; effective downgrade/expiry/
      revocation removes it, preserving the user's visibility preference.
- [ ] Existing Supporters receive the projection through the reviewed repair/
      reconciliation path; no user can forge badge eligibility.
- [ ] Announcement reads work free; manual writes remain admin-only; automatic
      notices respect preferences and do not duplicate on retry/edit.
- [ ] Test the integrated build on the Internal Testing track before wider release.

Merge and deployment are explicit later steps. Review backend/rules and Android
rollout together: old Android builds may not explain newly enforced narrow gates.
Never roll back by allowing free access to private paid data or enabling broad
legacy membership gates. Pause affected UI entry points if an entitlement defect
requires investigation; preserve purchases, user data and points ledger records.
