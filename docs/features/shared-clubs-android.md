# Shared clubs: Android integration and verification

CarCommunity and Driver use one club ID in `driverClubs` and one server-owned
membership lock per canonical Firebase Auth UID. Club functionality is free for
eligible accounts. The API and authorization contract is in
[`../contracts/shared-clubs-v1.md`](../contracts/shared-clubs-v1.md).

## User flows

- Social → Club opens the current club, or a paginated directory with club logos,
  descriptions, open/closed access, invitations and creation.
- Membership is limited to one club across both apps. Open clubs permit self-join;
  closed clubs require recipient-accepted invitations. Leaving or removal starts
  a server-enforced 24-hour wait before creating or joining any club.
- Owners edit presentation, visibility and sanitized logo, transfer ownership,
  assign at most three moderators and disband. Owners/moderators manage eligible
  members and closed-club invitations. The native invitation picker uses existing
  friends, with name search; add someone as a friend before inviting them.
- The club page contains aggregate distance, time and saved-drive count. Only
  drives entirely within an authoritative membership interval qualify. No member
  routes, speed ranking or historical backfill is exposed.
- The private month calendar shows club event registrations. Owners/moderators
  register the club from the existing event picker or event detail; each event
  lists club names/logos without exposing individual attendance identities.
- Chat gains a Club section. Current members can send text/emoji, report content
  or block authors; owners/moderators can redact messages. Membership loss,
  changed account/club/membership epoch or unverified access disposes messages,
  pagination and drafts. Server rules enforce history access independently of UI.
- The club logo is a server-projected avatar affiliation badge; it is not a trust
  or moderator indicator and clients cannot assign it in a profile-edit payload.

## Cost and privacy limits

Directory, roster, calendar, messages and event participants load in bounded
pages of 25 with explicit additional-page actions. No avatar performs an extra
club lookup. Logos use the existing public-image sanitizer, downscale to 512 px
(or 384 px fallback), re-encode JPEG, strip identifying metadata and fail closed
above 256 KiB. Club writes go through Driver callables; no client membership,
role, statistics, attendance or message document writes are permitted.

The message cursor uses a fresh server document snapshot rather than a truncated
millisecond timestamp. Private views require server-confirmed current membership
and never open using only Firestore's offline cache. This cannot remotely erase
knowledge already read or externally captured; it prevents continued access in
the app. Previously loaded history is not persisted as Compose saveable state.

## Rollout dependencies — not deployment authorization

This Android integration is one part of a coordinated feature. Before activation:

1. Review and merge the shared Driver backend and the **combined CarCommunity**
   Firestore/Storage rules and indexes. Never deploy Driver's emulator-only rules
   as the shared project's production rules.
2. Run the backend's non-destructive legacy-membership preflight. Resolve accounts
   with multiple legacy memberships explicitly; never silently choose or delete.
3. Deploy the exact reviewed code/rules/indexes in an owner-approved operation;
   verify index readiness, deployed callable names, App Check and rule behavior.
4. Activate `config/sharedClubs` only after both app adapters and migration checks
   are ready. The flag is false when absent. Old mutation endpoints must fail
   closed rather than allow legacy multi-club joins while disabled.
5. Verify on two real authenticated accounts across CarCommunity and Driver:
   create/list, invitation acceptance, one-club race, moderator cap, removal/chat
   revocation, cooldown, logo projection, membership-period drive aggregation,
   and event registration/calendar visibility.
6. Publish Android builds separately after runtime acceptance. This work does not
   change versions, publish an app, or modify iOS implementation.

## Evidence status

- New JVM tests cover malformed DTOs/roles, logo URL safety, cooldown boundaries,
  management role restrictions and calendar alignment/leap days.
- New Compose instrumentation covers membership revocation clearing messages and
  drafts, cross-club state isolation, blocked-author visibility and text/emoji
  delivery. Execution results are recorded in the PR, not assumed from source.
- Backend transaction/emulator/rules tests and Driver Flutter verification are
  maintained in their respective PRs.
- Production activation and two-account cross-app verification remain rollout
  gates; a green local build does not establish them.
