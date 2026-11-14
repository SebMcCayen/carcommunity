# Native message reactions v1

Approved implementation, 2026-11-14. Android and iOS use the selected floating
message menu: one horizontal row of up to five personal frequent emoji and a
plus button, reaction chips under the message, existing actions beneath it.
The exact selected reference is in `docs/design/evidence/2026-11-14-chat-reactions/`.
The previous permanent composer emoji row remains only as a feature-off rollback.

## Ownership and callable contract

CarCommunity owns `chatReactions-set` / `chatReactions-getMine` for scopes
`community`, `convoy`, `dm`, `event`. Driver owns
`driver-clubsSetMessageReaction` / `driver-clubsGetMyMessageReactions` for scope
`club` only. All are authenticated, App Check enforcing in production, and require
`config/featureFlags.chatReactions === true` (default false). Driver keeps its
existing shared-club activation and canonical account/membership gates.

`set` input:

```json
{
  "scope": "community",
  "roomId": "global",
  "messageId": "message-id",
  "emojiId": "1f44d",
  "expectedVersion": 0,
  "operationId": "stable-unique-operation-id"
}
```

`emojiId` is a catalog ID or `null` to remove one's selection. IDs are lowercase
Unicode scalar hex values joined by hyphens, exactly matching the pinned fully
qualified catalog. Arbitrary text, multiple emoji strings, unknown fields, paths,
UIDs, authors and client counters are rejected. `operationId` is 16..160 ASCII
letters/digits/underscore/hyphen. Response: `{messageId, emojiId, version, counts}`.

`getMine`: `{scope, roomId, messageIds}` with 1..50 IDs in one room. Response:
`{items:[{messageId,emojiId,version}]}` for only the current authenticated actor.
There is no actor roster or cross-user history endpoint.

Canonical message roots are `communityChat/global/messages`,
`convoyChats/{id}/messages`, `conversations/{id}/messages`, `events/{id}/messages`,
and `driverClubs/{id}/messages`. One reaction per actor/message, including own
messages. Repeating the same selected emoji means desired state `null` in the
native UI; the backend accepts desired state, never an ambiguous toggle.

## Authority, atomicity and privacy

Each effect transaction checks current canonical account/product access, current
room membership/eligibility, the stored message author, both canonical block
edges, message visibility/expiry and the feature flag. DM membership/friendship,
accepted convoy membership and published event/going-or-maybe RSVP are retained.
Shared clubs use Driver's transaction-aware `requireSharedClubMember`; a stale
client role or cached membership never grants access. Public Community/event
pauses reject add/change. Own removal requires current account/read access and
is allowed during that public pause. Other domains retain their existing policy.

Private state is deliberately a server-only top-level collection rather than a
subcollection: `chatMessageReactions/{sha256(uid + NUL + canonicalPath)}` stores
`uid,parentPath,parentEpoch,roomKey,emojiId,version,updatedAt`. This makes canonical
account erasure bounded and retryable. `parentEpoch` binds state to the immutable
creation timestamp; a reused path cannot inherit an old message's selection.

The parent carries only sparse `reactionCounts` and `reactionCatalogVersion`.
Zero buckets are removed; clients cannot write either parent or private state.
Anonymous counts are room totals and can include earlier blocked actors; no
identity filtering or confidentiality promise is inferred from group display
filters. Hidden/deleted/expired parents reject interaction and expose no new UI.

Versions reject conflicting writes. `chatReactionOperations` receipts bind the
authenticated UID, target and complete payload fingerprint. Replay reauthorizes
and returns current state without applying an older selection or duplicating
counts. Quotas use one shared `chatReactionRateLimits/{uid}` in both backends:
20 committed changes and 120 authenticated read/mutation requests per shared
60-second window. Duplicate commits do not spend the change budget again;
replays/no-ops/own-state reads still update the request guard to bound abuse.

A changed mutation ordinarily writes private state, parent counts, guard and
receipt. The existing CarCommunity access wrapper can additionally initialize or
refresh its access mirror; four is not a claim about total platform writes.
No `createdAt`, unread, latest-message, push, points or transient convoy-alert
effects are added. Existing emoji-shaped messages remain ordinary history.

## Local catalog, ranking and cache

`emoji-catalog.json` contains 3,963 fully qualified Unicode Emoji 18.0 entries,
including variants. Stable tuple positions are ID, glyph, Swedish name,
Swedish/English search terms, Unicode group, English name. Unicode/CLDR data is
licensed under `UNICODE-LICENSE.txt`. Sources:

- https://unicode.org/Public/emoji/18.0/emoji-test.txt
- https://github.com/unicode-org/cldr-json/tree/main/cldr-json/cldr-annotations-full/annotations/sv
- https://github.com/unicode-org/cldr-json/tree/main/cldr-json/cldr-annotations-derived-full/annotationsDerived/sv

Catalog, labels, search and personal frequency/recency ranking are entirely local;
opening/selecting a category/searching writes no backend usage history. Initially
the quick row uses thumbs-up, heart, laughing, smiling and crown. Local history
then takes priority, ranked by count, recency and stable ID. Native glyph appearance
depends on the OS font; older systems may lack the newest Unicode glyphs.

History and up to 500 own-state cache entries are UID scoped in app-protected,
backup-excluded storage. Own-state cache expires after 60 seconds. Existing
bounded message listeners carry aggregate changes; own-state refreshes batch
visible IDs with debounce, no periodic polling and no per-reaction listener.
Cache never authorizes writes or reopens a private club without fresh membership.
Auth change/denial clears the applicable private state; confirmed operations are
counted locally once. Empty/unchanged choices avoid mutation requests.

The maximum JSON counter map with every catalog ID at the largest safe integer
is **145,056 bytes**, below the 256 KiB budget. Parent count-map indexing is
disabled. Busy-message contention, actual latency and reaction frequency remain
unmeasured; Finance explicitly marks the new variable usage uncosted instead of
inventing a zero estimate. Cleanup has an explicit idle scheduler baseline.

## Lifecycle, delivery and review

Account hard purge erases own choices with atomic count adjustment, receipts and
the shared guard after Auth deletion. Parent removal/hiding and shared-club
archival enqueue private cleanup; an hourly bounded sweep handles at most five
jobs and fifty rows per job. Cleanup never recreates missing parents or removes
a different message creation epoch. State needed for version ordering remains
as a null-choice tombstone until lifecycle cleanup. Receipt/guard TTL policies
and the parent cleanup query index are in CarCommunity's combined index file.

Deploy compatible Driver callables, CarCommunity endpoints/rules/indexes and TTL
policies before enabling the new flag and distributing clients. Older clients
ignore additive counts. Keep protections/cleanup when disabling the feature.
**No production deployment, flag change, store release or merge is authorized by
these review PRs.**

Tests cover catalog/schema limits, deterministic ranking, additive decoding,
transaction replay/conflicts, concurrent writes, account/product/block/pause and
membership denial, direct SDK denial, erasure and quotas. Native Compose fixture
uses an injected transport and captures the selected menu, local picker and
learned frequent choice; no fixture signs in to production. iOS model/cache tests
and macOS CI cover the corresponding native implementation.
