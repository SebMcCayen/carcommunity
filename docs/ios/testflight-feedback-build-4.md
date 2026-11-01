# iPhone feedback — TestFlight 0.1.0 (4)

## Scope

- Projected markers now observe a per-camera-frame projection revision, independently of the rounded camera snapshot that throttles backend queries. Renderer attachment/removal and viewport resizing invalidate the same projection. No map recreation or marker identity resets.
- Keep the native map and all overlays at the same full size while reading a crown, incident or saved-place panel. Apply the measured dock clearance as camera padding at the viewport binding, so a selected marker is centered above the panel even on a short viewport. Clear the reading selection on tab/route changes as before.
- Android map rail order: incident reporting, layers, compass, available perks. Saved places opens the existing picker from the avatar menu, directly after My drives; management remains reachable from the picker and Settings. Live session start/manage/stop/hide remain available through Create and active-session controls.
- Shared community/convoy, direct-message and event-chat viewports open at the latest message, follow arrivals near the bottom and preserve history reading. Composers use a bottom safe-area inset and a bounded editor instead of competing with message content for height.
- Offer the existing While Using the App permission rationale once on the first authenticated foreground map entry, after the What's New announcement. No broadcasting/session starts as a side effect. Previously denied users are not nagged at startup.
- The profile warning listeners remain mounted, but no warning card/title/loading/error placeholder appears without a known active warning. Transient refresh failures preserve existing active warnings; expiry/revocation removes the card. Empty expired pages advance to later pages.
- Native dismissible read-only/selection sheets use a visible drag indicator and VoiceOver escape. Keep transactional Cancel/Save, non-dismissible drive-summary decisions, alert cancel actions, and the Close affordance on the inline map dock (which is not a native draggable sheet).
- Social gains Clubs when the same server-confirmed `config/sharedClubs` activation gate used on Android is enabled. Initial iOS scope: public directory/detail/statistics, current membership, create, join, leave and accepting incoming invitations using existing `driver-clubs*` callables. No backend deployment or activation change. Club chat, calendar, outgoing invitations and advanced roster/ownership management are not ported by this patch; owners with other members still need Android for ownership transfer before leaving. No private roster/chat is downloaded or cached.

## Map attribution

Remove the on-map Trafikverket corner badge; retain source information in incident details and layers. Trafikverket's traffic API terms license the data under CC0: <https://data.trafikverket.se/oauth2/Account/register>.

Keep the required native Mapbox wordmark, move it to the lower right, and keep both it and the SDK attribution/telemetry button above the measured reading dock. Mapbox permits moving the logo between corners but requires it to remain visible: <https://docs.mapbox.com/help/dive-deeper/attribution/>. The request to completely remove it is intentionally not implemented.

## Validation

- Unit regressions: observed marker projection after camera movement (including identical rounded snapshots), renderer lifetime and resize; first-map permission flow; club decoding/cooldown/gate revocation/idempotency.
- Native UI regression: config-less Debug fixture renders the actual shell/community coordinator/thread with synthetic messages. Test initial latest message, visible composer, real OS keyboard and send at normal/largest accessibility text size. The fixture cannot be selected in Release or in a Firebase-configured app.
- Update existing native sheet/menu tests to use the avatar saved-place picker and actual drag-to-dismiss gestures.
- Existing CI retains Debug unit/UI tests, optimized Release shell UI smoke, real SDK emulator acceptance and a generic device Release build. Do not weaken checks or merge on a failed/pending check.

## Device acceptance after upload

On build 4: pinch/pan/rotate around crowns and imported incidents; inspect their reading panels; use Saved places via the avatar; open Community chat and type/send with keyboard; review profile without an active warning; check native sheet dismissal; verify session start/privacy actions through Create. Test the first-map permission prompt on a fresh installation or after resetting privacy permissions.

No App Store Review/public release submission is part of this work.
