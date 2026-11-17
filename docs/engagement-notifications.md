# Live activity and Epic crowns

`friend_live` and `epic_crown` are optional notification categories. Each has
independent in-app and push controls in Android and iOS settings. They use the
shared notification writer and existing FCM/APNs delivery pipeline; push still
requires the existing `pushNotifications` feature flag, registered device and
OS permission. Tapping either category opens the map.

For each recipient and category, one transaction enforces at least six hours
between notifications and at most two in a rolling 24-hour window. Concurrent
spawns or live starts share that budget. Opted-out, restricted or deleted
recipients and duplicate events do not consume it. Budgets live under
`userPrivate/{uid}/engagementBudgets`, are backend-only and are removed by the
existing recursive private-data account-deletion cleanup.

## Friends starting live

`live-onFriendLiveStarted` observes the first discovery position of a new live
session. Ordinary position refreshes are silent. Mutual friendship, blocks in
both directions, the source account and the current active session are checked
inside each delivery transaction. Session restarts are covered by the recipient
budget. The title, body, related entity and push data contain no sender name,
uid or session id; the event key is a digest. Expired/stopped/superseded sessions
are skipped.

## Nearby Epic spawns

`crownHunt-onEpicCrownSpawned` only considers new, live, unexpired `epic` spawns,
behind the existing Crown Hunt and spawn flags. It queries the existing nearby
geo-cell index and checks exact Haversine distance <= 3,000 metres against the
recipient's fresh live position or private last-known own position, rechecking
the source in the delivery transaction. A fresh live position takes precedence.
The discovery record must be active, unexpired and refreshed within five minutes.
The private `userPrivate/{uid}.epicNotificationLocation` stores only the latest
position and expires after 24 hours. Both apps update it at most every five
minutes while foregrounded and already authorized for location. No new
background location permission is requested. Disabling Epic in-app notices or
revoking location permission clears the position on the next foreground sync.
The existing daily notification sweep clears expired position fields without
deleting private user documents. Account deletion removes the private position.
No position or history from this feature is exposed to other members, added to
live discovery, or used as a crown-spawning activity signal.

Engagement pushes expire five minutes after the inbox item was created, including
when the delivery trigger itself was delayed. Epic push delivery additionally
checks that the crown is still live. Inbox entries remain as history.

## Epic catches in Community

`crownHunt-onEpicCrownClaimed` reads a successfully awarded Epic spawn claim and
creates one `communityChat/global/messages` entry per `(spawn, collector)`.
Failed attempts, non-Epic claims, restricted/deleted collectors and stale events
are skipped. Transactional create-if-absent prevents trigger retries or duplicate
claim keys from creating duplicate announcements. This does not fan out a push
to the whole community and does not create mention notifications.

The trusted `systemEvent: epic_crown_claimed` discriminator survives both live
listeners and callable pagination. Android and iOS render a distinct centered
CarCommunity system entry with a button on the collector's name, opening the
existing profile route, including when viewing one's own catch. The stored
sender uid is the collector, so existing block filtering and deletion handling
still apply. Client post schemas and message rules cannot forge system metadata.
Normal Community retention applies.

## Displayed Android waves

Only a wave displayed while the map is resumed calls `live-acknowledgeWave`.
The callable derives the recipient from auth and records `seen` on that wave.
It marks only the associated persistent wave notification read. The wave writer
and acknowledgement share a transaction fence, so an acknowledgement arriving
before the persistent notification is saved still creates it as read. A resumed
send preserves the receipt. Other unread notifications and the inbox's global
last-seen marker are not advanced. Background waves remain unread.

The Android avatar badge queries the newest **unread** item before comparing it
with the inbox last-seen marker. Deploy the `items(read ASC, createdAt DESC)`
composite index and the `userPrivate(epicNotificationLocation.geoCell ASC,
epicNotificationLocation.expiresAt ASC)` index together with the new
callables/triggers. Android needs a new app
build for the wave acknowledgement; both mobile apps need a new build for the
new settings, map routing and system-message rendering.
