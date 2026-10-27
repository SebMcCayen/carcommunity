# Admin renewal: first implementation increment

The approved sequence is security and correctness, member tools, public/event
moderation, Crown Hunt operations, navigation, then a separately approved rollout.
The member card, Crown Hunt overview and moderation queue designs remain the
visual targets. This increment prepares those flows; it does not implement the
remaining redesign or certify all admin functions.

## PR order

1. `codex/admin-profile-authority`: protect server-owned profile fields and retain
   each client's validated own-profile edits. Both native validation workflows
   run when the Firestore rules change.
2. `codex/admin-foundation` (based on the first branch): truthful automatic-spawn
   flag status, canonical reward preset names, event-report index/error recovery,
   and Crown Hunt statistics using the existing light/dark theme tokens. Native
   validation also runs for admin and index changes.

Keep these as separate PRs. Merge the security change first, then retarget the
foundation PR to main. No production deploy is part of these PRs.

## Verification and compatibility

| Boundary            | Evidence in this increment                                                                                                                                                     | Remaining acceptance                                                                                      |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------- |
| Profile permissions | Emulator denials for creation, deletion, replacement, authority/status/entitlement changes and same-session owner deletion; existing audited admin callables remain the writer | Native own-profile edit against the candidate rules and verification of stored roles before rollout       |
| Android profile     | Rule tests accept its display name/bio/social/avatar write payload; native build, unit/lint and instrumentation workflows run                                                  | App-to-backend profile edit and refresh with candidate rules                                              |
| iOS profile         | Rule tests accept its display name/bio/social/avatar write payload; simulator/unit/UI, native Auth emulator and unsigned-device workflows run                                  | App-to-backend profile edit and refresh with candidate rules                                              |
| Spawn status        | Shared effective-default mapping; loading/error/cache states never claim On/Off; live flag changes and unsubscribe/retry tests                                                 | Read-only preview with real flag permissions                                                              |
| Hand-placed rewards | Common/Uncommon/Rare/Legendary = 10/25/100/500; custom rewards and existing radii are preserved                                                                                | Confirm created points in both apps in an isolated environment                                            |
| Event reports       | Production log identified the missing `messageReports.createdAt` collection-group descending index; configuration guard and UI read/retry test                                 | Deploy index after rollout approval, wait for READY, then verify query against production/indexed preview |
| Statistics          | Existing semantic theme variables replace undefined variables/light fallbacks                                                                                                  | Visual light/dark acceptance with populated data                                                          |

The Firestore emulator creates indexes automatically. A passing emulator query
cannot prove a production index exists. The new override preserves collection
ascending/descending indexing and adds collection-group descending indexing.
No fallback query silently changes ordering or returns an arbitrary subset.

Automatic-spawn status describes eligibility for the scheduler, not proof that a
crown exists. Hand-placed crowns still store a reward and availability window;
choosing a preset does not assign an automatic rarity or lifetime.

## Next bounded PRs

- Server filtering/cursor pagination and complete collection history, retaining
  the distinction between hand-placed and automatically spawned crowns.
- Audited member display-name changes and idempotent point adjustments/history;
  retain the single points balance with clear XP/Kronpoäng labels.
- Expiring/revocable warnings with a private own-profile projection in both apps.
- Suspension and provider-identity blacklist contracts/enforcement. Confirm the
  shared Auth project's product boundary before changing Auth-wide access.
- Shared moderation queue and narrowly authorized event/public-message evidence
  and member-message lookup. Private messages and convoy chat remain out of scope.
- Approved member/operations/queue layouts and grouped navigation, preserving
  working routes and showing missing data as unavailable rather than zero.

Each feature still requires positive and negative backend cases, admin interaction
and actual Android/iOS acceptance. Native CI passing is recorded separately from
that end-to-end acceptance and from production readiness.
