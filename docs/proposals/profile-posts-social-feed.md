# Plan: Profile posts, reactions & social feed ("Flödet")

> **Status:** Implementation plan / proposal — **NOT approved for build.**
> Parked on the Future Ideas board pending explicit go-ahead. This document
> does not change any application code.
>
> **Author:** Claude (delegated implementation plan, requested by Seb)
> **Date:** 2026-07-15
> **Recommendation (short version):** **Build Phase A (profile posts +
> replies + thumbs) on a single generic `posts` backbone, flag-gated,
> ~3.5 engineer-weeks; Phase B "Flödet" community feed ~1 week more;
> full vision ~5.5 weeks.** Nearly all required infrastructure (callable
> patterns, media pipeline incl. an existing client-side image
> compressor, report/moderation/audit loop, blocking, notifications,
> feature flags) already ships and is reused as-is. Images are
> **viable-cheap** with mandatory client-side compression (verdict in §6)
> — the whole community fits inside the Firebase Storage free tier.

## 1. What Seb asked for

Verbatim requirements, restated:

1. Users can make posts on their own profiles; visitors to a profile see
   those posts.
2. Other users can respond (reply) to posts and give a thumbs up/down.
3. Posts contain text and emojis. Images too **if** client-side compression
   keeps backend storage/bandwidth cheap (analysis in §6).
4. Later: surface posts in a social flow/feed like X/Twitter/Facebook.
5. Goal: make the app more social and interactive.

Emoji note up front: emojis are plain Unicode code points inside the text
field — the backend needs **no emoji handling at all** beyond a
grapheme-safe length check. The emoji picker is a stock Android IME
feature; a dedicated in-app picker is a UI nicety, not a requirement.

## 2. Current social infrastructure (surveyed 2026-07-15 on `main`)

The codebase has moved well past the 2026-07-10 inventory. What exists and
ships today:

- **Friends graph** (merged): `functions/src/friends/` —
  `users/{uid}/friends/{friendUid}` owner-readable mirror docs (written
  for both sides on accept) + hashed-ID `friendRequests/`, all writes via
  `friend.*` callables. Available for Phase C friends-first sorting.
- **DMs** (merged): `functions/src/dm/` — `DM_MESSAGE_MAX_LENGTH = 2000`,
  pages of 30, callable-only writes, **server-side both-ways block check
  re-verified inside the send transaction**, per-user unread counters kept
  in lock-step with a `userPrivate/{uid}.dmUnreadTotal` aggregate. The old
  `docs/product-decisions.md` line "Ingen privat DM … i MVP" has been
  superseded in practice; this plan follows the shipped posture, not the
  stale MVP line.
- **Chat stack** (PRs #391/#393, merged): event chat + `chatchannels/`
  (community town square `communityChat/global/messages` + convoy chat)
  with TTL via `expireAt`, **denormalized sender
  `displayName`/`avatarPath` on every message** (no per-message profile
  lookups — exactly what a feed wants), an O(1) per-user last-read marker
  (`userPrivate/{uid}.communityChatLastReadAt`) instead of unread fan-out,
  and a `createdAt`-cursor pagination convention (`before` ISO cursor,
  `limit(PAGE+1)`, `nextBefore`/`hasMore`). Community chat deliberately
  does **not** filter blocked authors server-side (documented design
  choice — client display concern).
- **Blocking**: `userBlocks/{blockerUid}/blocked/{blockedUid}`,
  backend-written, directional. There is **no shared block-check helper**
  — DM and friends each implement a local `isBlockedEitherWay(a, b)`
  (two doc reads). Posts should factor this into a shared helper or
  replicate it (§7.3).
- **Report → moderation loop**: `events/reportChatMessage.ts`
  (deterministic `${messageId}_${reporterUid}_${reason}` dedupe,
  backend-only queue, reasons `harassment | hate_or_abuse | spam |
  unsafe_driving | privacy | other`) → `moderateReports.ts` (admin
  bounded `collectionGroup` queue, status transitions, `adminAuditEvents`
  audit) → `removeChatMessage.ts` (soft-remove, auto-resolves reports).
  Admin web module: `apps/admin/src/features/event-chat/`. (A second,
  rules-only `moderationReports` collection with client-direct create and
  `targetType ∈ [user, message, event]` also exists — §7.1 explains why
  the events-style pipeline is the better clone target here.)
- **Notifications**: `functions/src/notifications/deliver.ts`
  (`writeInAppNotification`: eligibility, per-category opt-outs from
  `userPrivate/{uid}.notificationPreferences`, idempotent deterministic
  IDs). New categories are gated on product/security review per the
  FUTURE-categories comment in `notifications-core.ts` — this document is
  intended as that review input for §8 Phase C. Note: **actual FCM push is
  deferred to end-of-MVP** (only token hashes are stored today), so
  Phase C notifications are in-app inbox first, push automatically later.
- **Media pipeline**: `apps/android/.../media/` (`MediaUploader.kt`,
  `ImagePicker.kt` photo-picker with bounded reads,
  `ImageUploadCoordinator.kt`) uploading client-direct to prefixes ruled
  in `firebase/storage.rules` (`isImage()`, `isUnderMB(N)`; avatars 5 MB,
  vehicle photos 10 MB). Crucially, **`media/ImageCompressor.kt` already
  exists**: longest-side downscale + EXIF-orientation fix + JPEG q80
  re-encode (which drops all EXIF/GPS by construction) — currently wired
  for avatars only. Details and the extension plan in §6.
- **Counters**: two house patterns, no distributed counters anywhere
  (correctly, at this scale): `FieldValue.increment` deltas in a Firestore
  trigger (`events/onRsvpWrite.ts` maintaining `rsvpCounts`) and
  increments inside a callable transaction (DM unread + aggregate).
- **Rate limiting**: no shared helper; the `postChatMessage.ts` pattern is
  a bounded windowed `.count()` on a composite index (5 msgs/30 s,
  explicitly "best-effort, not a security invariant"). DM send and
  community-chat post currently have **no** rate limit.
- **Feature flags**: one flat `config/featureFlags` doc read via
  `readFeatureFlag(key)` with contract-default fallback; canonical keys in
  `functions/src/shared/featureFlags-core.ts` (`FEATURE_FLAG_DEFAULTS`) +
  `contracts/features/feature-flags.json`; audited admin toggle exists.
- **Account deletion**: `functions/src/account/deletion-core.ts` has an
  explicit purge plan (`PURGE_DOC_TREES`, `PURGE_OWNED_COLLECTIONS` by
  user-field query, storage prefixes) + collection-group sweeps of
  authored channel messages by `senderUid`. Posts slot into this plan
  mechanically (§7.4).
- **Profiles**: `users/{userId}` is authenticated-readable
  (`displayName`, `avatarPath`, `bio` — the public projection inside the
  membership), backend-provisioned, owner edits whitelisted display
  fields only. **Android gap:** `profile/ProfileScreen.kt` is *own*
  profile only — there is currently **no screen for viewing another
  member's profile** (friends list opens DMs directly). Phase A therefore
  includes a new member-profile route (§8, §9) — the data layer already
  permits it; only the screen is missing.
- **Android shell**: 5 bottom tabs — Map (default), History, Create,
  **Social**, Garage — with full-screen sub-routes (`ShellRoute` enum
  state machine in `AuthenticatedApp.kt`, no NavHost). The Social tab is
  the natural home for Flödet (§8 Phase B). List screens are uniformly
  `orderBy(createdAt DESC).limit(N)` snapshot listeners; no Paging 3, no
  `startAfter` cursors on Android today.

**Conclusion of the survey:** this feature is, like Byggloggar before it,
mostly *assembly of shipped parts*. No new GCP services, secrets, regions,
or external APIs.

## 3. Reconciliation with the Byggloggar proposal (#332) — one backbone, not two

`docs/proposals/garage-build-threads.md` (parked, unmerged) proposes
per-vehicle build-log entries under
`vehicles/{vehicleId}/buildEntries/{entryId}` plus a "Verkstan" feed.
Profile posts and build-log entries are structurally the same object:
authored text + photos, replies-or-not, reactions, reports, soft-remove,
feed surfacing. Building them as two parallel content systems would mean
two moderation queues, two deletion cascades, two reaction models, and two
feed queries.

**Recommendation: a single generic `posts` backbone.** A top-level
`posts/{postId}` collection with a context discriminator:

- `contextType: 'profile'`, `contextId: <authorUid>` — this plan, now.
- `contextType: 'vehicle'`, `contextId: <vehicleId>` (+ the Bygglogg
  `entryType` tag as an optional field) — what Byggloggar becomes **if**
  it is later approved.

What this buys, concretely:

- **One moderation pipeline** (one report shape, one admin queue, one
  audit path) instead of the Byggloggar plan's second clone of the chat
  moderation stack.
- **One feed.** "Flödet" (§8 Phase B) is a single indexed query on
  `posts`; a vehicle's build log is the same query filtered by context.
  Byggloggar's 30-vehicle `in`-query feed workaround becomes unnecessary —
  its entries would simply appear in Flödet.
- **One deletion cascade, one reaction system, one rate limiter, one
  contracts schema** (`posts.schema.json`).

Why top-level rather than Byggloggar's subcollection: profile posts have
no natural parent document to hang off (posts under `users/{uid}` would
work, but then vehicle posts under `vehicles/{id}` recreates the split and
forces collection-group queries with two different parents for every feed
and moderation scan). A top-level collection with denormalized
`contextType`/`contextId`/`authorUid` keeps every query single-collection
and index-cheap. The Byggloggar doc itself denormalized `vehicleId` into
entries "to enable the collection-group feed query" — the top-level
collection is that idea taken to its conclusion.

If Byggloggar is approved later, its delta over this backbone shrinks to:
the `vehicle` context type, the `entryType` tag, vehicle-follow +
notification fan-out, and its garage UI — roughly halving its backend
estimate. Its product analysis (content flywheel, cold start, no-comments
stance for build logs) remains its own; nothing here forecloses it. The
one deliberate divergence: this plan **does** include replies (Seb asked
for them explicitly); Byggloggar's phase-1 "no comments" stance was a
scope choice for that feature, not a house rule, and per-context reply
disabling is a one-line rules/validation switch on `contextType` if build
logs still want comment-free threads.

## 4. Data model

All collections: **backend-only writes** (callables — the house posture
for UGC), reads as shown. Text lengths follow the DM/chat conventions
(`DM_MESSAGE_MAX_LENGTH = 2000`).

```
posts/{postId}                              read: isAuthenticated()
  postId            auto-ID
  contextType       'profile'                (phase A; 'vehicle' reserved)
  contextId         string                   (== authorUid for 'profile')
  authorUid         string
  authorDisplayName string                   (denormalized, chat pattern)
  authorAvatarPath  string|null              (denormalized, community-chat pattern)
  text              string 1..2000           (Unicode incl. emoji; server-side
                                              grapheme-aware length check so a
                                              flag emoji ≠ 8 "characters")
  imagePaths        string[] 0..4            (each under postImages/{uid}/{postId}/,
                                              §6; [] when images deferred)
  thumbPaths        string[] 0..4            (client-generated, §6.3)
  replyCount        int                      (transactional counter)
  upCount           int                      (transactional counter)
  downCount         int                      (transactional counter)
  removed           boolean                  (admin soft-remove → tombstone)
  createdAt / editedAt   Timestamp (server)

posts/{postId}/replies/{replyId}            read: isAuthenticated()
  authorUid, authorDisplayName, authorAvatarPath
  text              string 1..1000
  removed           boolean
  createdAt         Timestamp
  — FLAT, exactly one level. No reply-to-reply.

posts/{postId}/reactions/{uid}              read: isOwner(uid)
  reactorUid        string                   (== doc ID; queryable field so the
                                              account-deletion collection-group
                                              sweep can find it, §7.4)
  value             'up' | 'down'
  updatedAt         Timestamp
  — doc ID = reactor UID → exactly one reaction per user per post,
    idempotent and toggleable by construction.

posts/{postId}/postReports/{reportId}       read/write: false (backend-only)
  — chat-report shape verbatim: reporterUserId, reason, details, status,
    reviewedByUserId, reviewedAt; deterministic ID
    `${postId}_${reporterUid}_${reason}` (chatReportDocId parity) so
    repeat reports upsert silently and never reset review state.
```

Design decisions, with reasoning:

- **Flat replies, one level.** Deep threading (reply-to-reply trees) is
  wrong for a 20–30-active-member community: threads will rarely exceed a
  handful of replies, tree UIs are the single most expensive part of
  comment systems (indentation, collapse state, pagination-within-branch,
  "load more replies" plumbing), and moderation of nested content is
  harder to reason about. X/Facebook-style visibility does not require
  threading. If a conversation outgrows a flat list, that is what DMs and
  event chat are for. **Recommendation: hard-commit to flat; do not build
  "parentReplyId" fields speculatively.**
- **Reactions: one doc per user, keyed by UID.** Calling the reaction
  callable with the current value removes it (toggle); with the other
  value, switches it. Counts (`upCount`/`downCount`) are updated **in the
  same transaction** as the reaction doc write (the DM
  unread-counter pattern; `events/onRsvpWrite.ts`'s trigger-side
  `FieldValue.increment` is the fallback shape if reply counting ever
  moves trigger-side) — backend-authoritative, never client increments.
  Individual reaction docs are readable only by their owner ("did *I*
  thumb this?"); everyone else sees only aggregate counts. No "who liked
  this" list in Phase A (stalking-adjacent surface, zero product need —
  same reasoning as Byggloggar's private follows).
- **Plain transactional counters, no distributed counters.** Distributed
  counter shards exist for docs written >1/sec sustained. The realistic
  peak here is a handful of reactions within seconds of a popular post —
  transactions retry and absorb that trivially. Firestore's 1-write/sec
  soft limit is about sustained load, not bursts. Building shards for a
  30-member community would be pure ceremony — and the survey confirms
  no distributed counter exists anywhere in the codebase today. Revisit
  only if the app goes multi-community.
- **Counts as fields, not `count()` aggregates, for the feed.** The feed
  renders N posts per page; issuing 2N aggregate queries per page is
  read-cost madness. Denormalized counters render for free with the post
  doc. `count()` remains the right tool for the rate limiter.
- **Edit window** on posts/replies: allow author edit/delete any time
  (own content), stamp `editedAt`, render "redigerad". No edit history
  (not worth the storage or the UI).
- **Per-user post cap** (e.g. 500) and **replies-per-post cap** (e.g. 500)
  enforced transactionally, purely as abuse bounds — the vehicle-cap
  pattern from `manageVehicle.ts`.

### 4.1 Firestore rules sketch

```
// posts: readable inside the membership, writable only by backend
match /posts/{postId} {
  allow read: if isAuthenticated();
  allow write: if false;                 // posts.* callables only

  match /replies/{replyId} {
    allow read: if isAuthenticated();
    allow write: if false;
  }
  match /reactions/{uid} {
    allow read: if isOwner(uid);         // "my reaction" only
    allow write: if false;
  }
  match /postReports/{reportId} {
    allow read, write: if false;         // backend-only queue
  }
}
```

Three composite indexes: `posts(contextType, contextId, createdAt DESC)`
for the profile tab, `posts(contextType, createdAt DESC)` for Flödet
(Phase B), and `posts(authorUid, createdAt DESC)` for the rate-limit
window and the deletion sweep.

### 4.2 Rate limiting (spam)

Reuse the `postChatMessage.ts` bounded count pattern, tuned for content
that is rarer than chat:

- `posts.create`: ≤ 5 posts / hour / user.
- `posts.reply`: ≤ 30 replies / hour / user.
- `posts.react`: ≤ 120 reaction ops / hour / user (toggles are cheap and
  bursty; the limit exists to bound counter-churn abuse).

All limits are constants in `posts-core.ts`, adjustable without schema
change. Combined with backend-only writes, App Check, and the report
pipeline, this is proportionate anti-spam for a paying, verified-18+,
20–30-member community.

## 5. Thumbs up / thumbs down

Implemented exactly as specced: both directions, one reaction per user
per post, toggleable, counts on the post doc (§4).

**One honest product caveat (Seb's call, flagged as an open question):**
public downvote counts in a small community where everyone knows everyone
behave differently than on X. A post at "3 👎" in a 25-person club is not
anonymous feedback — it is a visible social sanction, and dogpiling or
score-watching can chill exactly the posting culture this feature exists
to create. Reddit-scale anonymity absorbs this; Kungsbacka-scale does
not. Cheap mitigation options, in increasing order of intervention:
**(a)** ship as specced, watch, and rely on the flag; **(b)** show the
downvote *count* only to the post author (others see only the up-count
and their own thumb state — one client-side rendering rule, zero backend
change); **(c)** an admin-configurable flag (`postDownvotesPublic`) that
toggles between (a) and (b) at runtime. The backend model is identical in
all three, so this decision **does not block implementation** — the plan
defaults to (a) as specced, with (c) as the recommended cheap insurance.

## 6. Images — analysis and verdict

**Verdict: viable-cheap. Include images in Phase A**, with client-side
compression as a hard requirement (not an optimization). The math:

### 6.1 Client-side compression (mandatory — and mostly already built)

The compression utility **already ships**:
`apps/android/.../media/ImageCompressor.kt` does a bounds-only decode →
`inSampleSize` downsample → EXIF-orientation transform →
longest-side-capped scale → JPEG re-encode (`AVATAR_MAX_DIMENSION = 1024`,
`DEFAULT_JPEG_QUALITY = 80`), adopting the result only when it is smaller
than the source. It is currently wired **only for avatars**
(`AuthenticatedApp.kt` avatar path); vehicle photos upload uncompressed.
For posts:

- **Reuse `ImageCompressor` with a post-tuned dimension constant** —
  `POST_IMAGE_MAX_DIMENSION = 1600` px long edge, quality 80 (a 4032×3024
  camera original becomes ≈1600×1200). Typical output: **150–400 KB** per
  image vs 2–5 MB originals — a 10–20× reduction. JPEG stays the format
  (the compressor's existing choice; WebP would save ~20% more but is not
  worth a second encode path).
- **EXIF/GPS stripped by construction:** the compressor's decode →
  re-encode already produces a fresh container with no EXIF block, and it
  already applies the EXIF *orientation* transform first (the classic
  sideways-portrait bug is pre-solved). This also resolves the open EXIF
  question the Byggloggar proposal flagged — and while touching this
  code, **wire the compressor into the vehicle-photo path too** (a
  one-line adoption fixing an existing metadata leak; today vehicle
  photos upload with original EXIF/GPS intact).
- **One hardening change:** compression is currently *best-effort* (any
  failure falls back to uploading the original pick). For avatars that is
  benign; for posts the fallback must **fail the attach instead** —
  uncompressed originals would carry GPS EXIF and blow the §6.2 2 MB
  rule anyway. Small behavioral fork in the coordinator.
- **Client-generated thumbnail** per image: 320 px long edge, JPEG q75,
  ≈ 15–40 KB, encoded from the same decoded bitmap and uploaded
  alongside the full image. This is deliberately chosen **over**
  server-side thumbnailing: no Cloud Function on Storage finalize (cost +
  cold starts), no paid Resize extension (requires approval per
  `docs/firebase-cost-controls.md` — and the survey confirms no resize
  function/extension exists today), and the client already has the bitmap
  in hand. Feed and profile lists render thumbs via Coil (already the
  house image loader, with disk cache); full image loads only on tap.

### 6.2 Caps and quota

- **≤ 4 images per post** (validated server-side: `imagePaths.length ≤ 4`,
  each path under the caller's own `postImages/{uid}/{postId}/` prefix —
  the `isValidVehicleImagePath` pattern).
- **Storage rule** for the new prefix: owner + active member write,
  `isImage()`, and — because the client now compresses — a **2 MB**
  per-object cap (vs the 10 MB vehicle-photo rule), which makes bypassing
  client compression structurally impossible.
- **Per-user quota:** post cap (500) × 4 images × 2 MB worst-case already
  bounds a user at 4 GB theoretical / ~0.5 GB realistic. A separate
  byte-tracking quota system is not worth building at this scale; the
  post cap *is* the quota. Revisit if caps ever rise.

### 6.3 Cost math (per `docs/firebase-cost-controls.md`: 20–30 active users, SEK 500/month hard ceiling)

Generous assumptions: 30 actives, 8 posts/user/month, 1.5 images/post
average at 300 KB + 30 KB thumb:

- **Storage growth:** 30 × 8 × 1.5 × 0.33 MB ≈ **120 MB/month**, ≈
  1.4 GB/year. Firebase Storage free tier is 5 GB; paid rate beyond it
  ≈ $0.026/GB/month. Even year 3 is **pennies**.
- **Download (the real cost lever):** thumbs dominate. 30 users opening
  the feed daily, 50 fresh thumbs/day at 30 KB ≈ 45 MB/user/month ≈
  1.4 GB/month community-wide, plus occasional full-image taps ≈ well
  under the **1 GB/day free egress**. Coil's disk cache (already the
  house image loader) means repeat views cost nothing.
- **Firestore:** one write per post/reply, 1 transactional write per
  reaction op; feed reads paginated at 20/page. Noise at this scale.

Without client compression the same usage is 10–20× these numbers and
full-size feed images blow the daily egress tier — which is why
compression is a **gate, not a nice-to-have**. If the compression step
slips, ship Phase A text-only (the model already tolerates
`imagePaths: []`) and add images in a follow-up.

## 7. Moderation & safety (non-negotiable for UGC)

18+ paying members lower the base rate of abuse, but Google Play's UGC
policy and basic duty of care require the full loop regardless. All four
pillars are reuse:

1. **Reporting:** clone the events chat-report pipeline —
   `posts.reportPost` / `posts.reportReply` into
   `posts/{postId}/postReports/` with the deterministic-ID dedupe,
   backend-only queue, reason enum, and no-self-report rules from
   `reportChatMessage.ts`. (The alternative — extending the rules-only
   `moderationReports` collection with a `'post'` targetType — is
   rejected: it is client-direct-write, has no dedupe, no audit-event
   integration, and no per-domain admin queue; the events pipeline is
   the strictly better precedent and keeps the single-backbone story.)
2. **Admin review:** `apps/admin/src/features/posts/` as a near-copy of
   the `event-chat` reports module: bounded `collectionGroup` queue scan →
   resolve/dismiss → `posts.removePost` / `posts.removeReply` soft-remove
   (tombstone, auto-resolves open reports), every action through
   `adminAuditEvents`. Per the house pattern, **no "browse all posts"
   admin surface** — admins act on reports (posts are
   authenticated-readable anyway, so an admin can always follow a report
   link).
3. **Blocking:** two layers, matching the shipped designs. *Read time:*
   clients filter posts/replies authored by users the reader has blocked
   (the documented community-chat stance — no server-side filter, which
   would break cursor pagination). *Write time:* `posts.reply` and
   `posts.react` reject when either direction of a block exists between
   actor and post author — blocked users must not be able to *interact*.
   DM already enforces exactly this (`isBlockedEitherWay`, re-checked in
   the send transaction, neutral error that never reveals who blocked);
   since no shared helper exists yet, **factor DM's check into
   `functions/src/shared/`** rather than adding a third copy.
4. **Account deletion:** extend `deletion-core.ts`'s explicit purge plan:
   add `posts` to `PURGE_OWNED_COLLECTIONS` (query `authorUid == uid`;
   storage prefix `postImages/{uid}` joins `PURGE_STORAGE_PREFIXES`,
   deleted before docs — the storage-first ordering), sweep replies via
   `collectionGroup('replies').where('authorUid','==',uid)` and reactions
   via `collectionGroup('reactions').where('reactorUid','==',uid)` — the
   same shape as the existing `senderUid` channel-message sweep. Counter
   decrements on surviving parents ride along in the sweep batches.
   Replies and reactions on a *deleted user's own* posts die with the
   post (recursiveDelete) — simpler than re-parenting, matches how DM
   deletes whole conversations.

**Profanity/abuse stance:** no automated profanity filter. Swedish
profanity lists are poor, false-positive-prone, and trivially evaded;
at this community size, report + admin soft-remove + rate limits + the
membership being non-anonymous is both proportionate and more effective.
The feature flag is the emergency brake.

## 8. Phases

### Phase A — Profile posts (the ask)

Flag `profilePosts` (default **false**). Scope:

- **Backend** (`functions/src/posts/`): `posts-core.ts` (strict Zod,
  `garage-core` conventions) + callables `posts.create`, `posts.edit`,
  `posts.delete`, `posts.reply`, `posts.editReply`, `posts.deleteReply`,
  `posts.react`, `posts.reportPost`, `posts.reportReply`, admin
  `posts.listReports` / `posts.resolveReport` / `posts.removePost` /
  `posts.removeReply`. All `europe-west1`, `256MiB`, `enforceAppCheck`
  outside the emulator, `requireMemberActor`, `maxInstances` capped.
  Rules, storage rules, indexes, `contracts/schemas/posts.schema.json`,
  contract function entries.
- **Android:** two screens. **(a) A new member-profile route** — this
  does not exist today (the survey confirms `ProfileScreen.kt` is
  own-profile only and friends go straight to DM): a read-only
  `ShellRoute.MemberProfile(uid)` showing the already
  authenticated-readable `displayName`/`avatarPath`/`bio` plus the posts
  list, reachable from the friends list (and later from feed items). This
  is a prerequisite of the spec ("visitors to a profile see those posts")
  and is scoped into A2. **(b)** The "Inlägg" section itself: own profile
  gets composer + list; visiting view gets list + reply/react/report/
  block affordances. Image attach via §6.1; list = the house
  `orderBy(createdAt DESC).limit(N)` snapshot listener with a
  load-older `before` cursor (the backend chat convention — new to
  Android but established in the callables); tombstones for removed
  content; read-time block filtering; i18n strings.
- **Admin web:** posts reports queue module (event-chat clone).
- **Hardening:** account-deletion cascade + emulator e2e + privacy-policy
  sentence for post images.

**No notifications in Phase A** — a visitor sees posts when visiting;
that is the spec, and it keeps the new-notification-category review out
of the critical path.

### Phase B — "Flödet" community feed

Flag `communityFeed` (default **false**), independent of Phase A's flag.

- One screen: **reverse-chronological list of all members' profile
  posts.** Query: `posts` where `contextType == 'profile'` ordered
  `createdAt DESC`, snapshot listener on the newest page of 20 (the house
  list pattern) + `before`-cursor load-older, blocked authors filtered at
  read time, inline reply/react, tap-through to the member profile.
- **Unread affordance for free:** copy the community-chat model — a
  `userPrivate/{uid}.feedLastSeenAt` marker (owner-writable field, O(1),
  no fan-out) drives a "new posts" dot on the Social tab.
- **Deliberately NO ranking algorithm.** At 20–30 members,
  reverse-chronological is strictly better: transparent, zero
  infrastructure, no engagement-optimization pathologies, and nothing to
  tune. An algorithmic feed at this scale would reorder ~3 posts a day
  with extra reads. (Also explicitly out: reposts/quotes, hashtags,
  trending — see §10.)
- Placement: **the Social bottom tab already exists** — Flödet becomes a
  section/entry there alongside friends/chat, which sidesteps the
  Byggloggar tab-vs-section dilemma (no new tab, no home-screen fight).
  Cold-start stakes still apply: keep the flag off until Phase A has
  produced real posts (§9 checkpoint).

### Phase C — Engagement layer

- **Notifications:** new **non-essential** categories in
  `NOTIFICATION_CATEGORIES` — `post_reply` (someone replied to your post)
  and, optionally, `post_reaction`. **Recommendation: ship `post_reply`
  only.** Reaction notifications are the noisiest, lowest-information
  push in social apps; if wanted later, batch them ("3 nya reaktioner")
  rather than per-tap. Delivery through `writeInAppNotification`
  (deterministic ID `${replyId}_${postAuthorUid}`), per-category opt-out
  from day one, plus a new `open_post` entry in
  `NOTIFICATION_ACTION_TYPES` for deep-linking. In-app inbox only at
  first — FCM push is deferred to end-of-MVP repo-wide, and the category
  will ride the push rollout automatically. This section is the
  product/security review input the `notifications-core.ts`
  FUTURE-categories gate asks for. Android needs the three known manual
  edits per new category (`NotificationCategories.ACTIVE`,
  `categoryLabelRes` case, string resource — documented in the Byggloggar
  proposal §2.5).
- **Friends-first sorting (optional):** a "Vänner"-filtered feed view
  using the merged friends graph — client-side: fetch the reader's friend
  list (already owner-readable), then `posts where authorUid in
  [≤30 friends]` — the same `in`-cap reasoning as Byggloggar; fine at
  this scale. Not a ranking algorithm; a filter toggle.
- **Kronpoäng hooks — recommendation: none automatic.** The Byggloggar
  §4.6 analysis applies verbatim: per-post/per-reaction points are a
  direct spam pump that would force anti-fraud work on a feature whose
  value is authenticity. If engagement rewards are ever wanted: an
  admin-curated monthly badge ("Månadens inlägg", one new `BADGE_KEYS`
  entry, existing manual award flow) is ungameable and *generates*
  content. Reaction-threshold auto-points remain possible later but need
  their own review before enabling.

### Dependency order & independent shippability

`A-backend → A-android → A-admin` (admin can parallel android);
`B` requires only A-backend + its flag; `C-notifications` requires
A-backend; `C-friends-filter` requires B. Every phase ships dark behind
its own flag; nothing here blocks or is blocked by other open work.

## 9. Effort estimates

| Phase | Scope | Estimate |
| --- | --- | --- |
| **A1 Backend** | `posts-core.ts` + tests, ~13 callables, rules + storage rules + indexes, contracts, `profilePosts` flag | ~5–6 days |
| **A2 Android** | **new member-profile route** (spec prerequisite, §8A), profile posts section (own + visiting), composer, §6.1 compression extension (post dimension constant, fail-closed fallback, thumbnail encode, vehicle-photo adoption), replies + thumbs UI, report/block menus, pagination, i18n, repo fakes + tests | ~7–9 days |
| **A3 Admin web** | posts reports queue (event-chat clone) | ~2 days |
| **A4 Hardening** | deletion cascade, emulator e2e, privacy copy, compression QA on real photos | ~2 days |
| **Phase A total** | flag-gated, default off | **~3.5 engineer-weeks** |
| **B Flödet** | feed screen in Social tab + query/index + `feedLastSeenAt` marker + `communityFeed` flag | ~4–5 days |
| **C Engagement** | `post_reply` category end-to-end (backend + 3 Android edits + review), friends filter toggle | ~4–5 days |
| **Grand total** | | **~5.5 engineer-weeks** |

Estimates assume the shipped patterns are cloned, not redesigned, and are
consistent with actuals from comparable slices (chat stack, friends, DMs).

### Checkpoints / kill criteria

- **After A ships (flag on, seeded):** if < ~15 organic posts in the
  first month, **do not build B** — a community feed of tumbleweed is
  worse than no feed (the Byggloggar cold-start lesson). Profile posts
  alone still carry their weight as profile personalization.
- **Downvotes:** if dogpiling/score-anxiety is observed, flip to
  author-only downvote visibility (§5 option b/c) — a rendering change,
  not a migration.
- **Images:** if the compression step slips its estimate, ship A
  text-only and follow up — the model tolerates it (§6.3).
- **Any moderation fire:** the flag is the kill switch; soft-removed
  content stays soft-removed.

## 10. Explicitly NOT planned

- **No ranking algorithm** — reverse-chronological only (§8B).
- **No ads, no sponsored posts.**
- **No public/unauthenticated visibility** — posts are readable only by
  signed-in members, same posture as vehicles and profiles. Nothing here
  touches social *sharing* (`socialSharing` flag) or produces public URLs.
- **No deep threading** (§4), **no reposts/quote posts, no hashtags, no
  trending surfaces, no follower counts on people.**
- **No automatic Kronpoäng** (§8C).
- **No DM changes** — replies are public-by-design; private conversation
  already has a home.

## 11. Open product questions (for Seb)

1. **Downvote visibility** (§5): as-specced public counts (a), author-only
   (b), or admin-configurable flag (c)? Plan defaults to (a); (c)
   recommended as cheap insurance.
2. **Images in Phase A or fast-follow?** Analysis says viable-cheap
   (§6); text-first is still a legitimate de-risking choice.
3. **Feed naming:** "Flödet"? (Working name; internal identifiers stay
   generic `posts`/`feed` per brand-readiness convention.)
4. **Phase B home-section vs bottom-nav tab** at launch (plan assumes
   section first).
5. **Reply notifications** (Phase C): opt-out default-on, or opt-in?
6. **Post visibility for non-subscribed accounts:** posts follow the
   `vehicles` posture (any authenticated user reads; only active members
   write). Match, or gate reading behind subscription like event details?

## 12. Recommendation

**Approve Phase A on the single generic `posts` backbone; build after the
current release-critical work clears; Phase B on the §9 checkpoint.**
The engineering case mirrors Byggloggar's but stronger: the chat, DM, and
friends stacks that have merged since July 10 mean every hard sub-problem
(UGC moderation, blocking semantics, deletion erasure, counters, rate
limiting, media upload) already has a shipped, tested house pattern —
this plan introduces **zero new infrastructure** and one genuinely new
asset: a generic posts backbone that the Byggloggar proposal can later
adopt instead of building its own. Images are in, cheaply, provided the
client-compression gate holds. Total to the full X/Facebook-style vision
Seb described: **~5–5.5 engineer-weeks**, every phase independently
flag-gated and killable.

## Appendix A — files referenced

- `functions/src/friends/` (`friends-core.ts`, `manageFriends.ts`)
- `functions/src/dm/` (`dm-core.ts`, `manageDirectMessages.ts` — incl.
  `isBlockedEitherWay`, unread counters)
- `functions/src/chatchannels/` (`chat-core.ts`, `communityChat.ts` —
  denormalized sender profile, TTL, last-read marker, `before` cursor)
- `functions/src/events/postChatMessage.ts` (rate limit),
  `reportChatMessage.ts`, `moderateReports.ts`, `removeChatMessage.ts`,
  `onRsvpWrite.ts` (counter trigger)
- `functions/src/blocking/blocking-core.ts`, `manageBlocks.ts`
- `functions/src/notifications/deliver.ts`, `notifications-core.ts`
  (`NOTIFICATION_CATEGORIES`, `NOTIFICATION_ACTION_TYPES`, FUTURE gate)
- `functions/src/points/ledger.ts`, `functions/src/badges/badge-core.ts`
- `functions/src/account/deletion-core.ts`, `scheduled.ts`
  (`PURGE_DOC_TREES`, `PURGE_OWNED_COLLECTIONS`, `senderUid` sweeps)
- `functions/src/shared/memberActor.ts`, `access.ts`,
  `featureFlags-core.ts`, `featureFlags.ts`
- `functions/src/garage/garage-core.ts`, `manageVehicle.ts`
- `firebase/firestore.rules` (helpers, `users` read rule ~line 138,
  `moderationReports` ~line 727), `firebase/storage.rules` (`isImage()`,
  `isUnderMB()`, `vehicleImages` block)
- `apps/android/.../media/` (`ImageCompressor.kt`, `MediaUpload.kt`,
  `MediaUploader.kt`, `ImagePicker.kt`, `ImageUploadCoordinator.kt`,
  `StorageImageUrl.kt`)
- `apps/android/.../profile/ProfileScreen.kt` (own-profile only today),
  `.../shell/ShellNav.kt` (tabs + `ShellRoute`), `.../friends/`,
  `.../dm/FirebaseDmRepository.kt` (list pattern), `.../blocking/`,
  `.../notifications/` (`NotificationSettings.kt`)
- `apps/admin/src/features/event-chat/`, `.../feature-flags/`
- `contracts/schemas/*.schema.json`, `contracts/features/feature-flags.json`
- `docs/product-decisions.md`, `docs/firebase-cost-controls.md`,
  `docs/firebase-data-model.md`
- Adjacent parked proposal: `docs/proposals/garage-build-threads.md` (#332)
