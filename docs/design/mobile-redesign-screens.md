# Native redesign migration register

Source: 84 concept views in the user-supplied 2026-10-24 catalogue. All IDs are
design review IDs, not runtime route identifiers. This is a migration backlog,
not a statement that every view has been implemented or verified.

The first Android/iOS PR pair migrated the shared foundation and shell. Android
wave 2 implements the feature areas below; this does not establish rendered
acceptance of every catalogue state. Business behavior must remain reachable.
Driver reading is included; paid cosmetics are deferred. Current iOS feature
work is owned separately on the Mac mini, not by this Android wave.

## Android source audit — 2026-10-26 (pass 3)

**Integration follow-up:** PRs #1189 / #1190 now combine these lanes. Read
[the integrated validation results](android-redesign-validation.md#integrated-pass-3-checks--2026-10-26)
before treating this earlier audit's WIP/U labels as the latest execution
status. The integrated source also adds S07 filters, an S08 docked editor and
real owner-avatar shared headers; the owner-profile garage wiring is complete.
The table below is preserved as a source-audit snapshot, including its honest
contract gaps. A passing component fixture is not full-route/device acceptance
of an entire S-number.

This is a point-in-time **source review of all 84 catalogue states**, not a
rendered comparison, combined-build result or visual pass. All reference sheets
`catalogue-04.jpg` through `catalogue-24.jpg` were opened across this lane's
implementation and audit turns. Page numbers below identify those exact sheets.
Reference people, cars, prices, rewards, scenery and counters are not production
fixtures or permission to change product rules.

**Review** means the reference and associated native source/route were inspected;
it is not an exhaustive correctness/security review. **Changed** means local
source exists, **Retained** means an existing implementation was reviewed,
**Partial** records a concrete reference difference, **WIP** is uncommitted sibling
source, and **Guard only** is an existing fallback rather than a new page.
**U** means runtime and visual acceptance were unverified at this audit snapshot.
Its 82 Android-applicable rows were U; S81 is iOS-only and S82 is deferred.
For subsequent executed checks and intentional differences, use the
[integrated evidence report](android-redesign-pass3-evidence.md). These dated
audit labels must not be read as failures or pending execution of already-passed
tests. They also must not be upgraded wholesale from a component fixture.

### Audited source snapshots (not a combined app revision)

All sibling lanes start from `345a9f11693374d1e52a88f3ed4c20c1bc67edbd` (B).
This audit reads sibling worktrees; it does not merge, build or modify them.

| Key | Worktree / branch | Audited source |
| --- | --- | --- |
| B | `repo` / `main` | `345a9f11693374d1e52a88f3ed4c20c1bc67edbd`; retained code, not a new pass-3 test result. |
| M | `map-shell` / `codex/android-design3-map-shell` | Uncommitted source over B: `AuthenticatedApp.kt`, `incidents/IncidentUi.kt`, `shell/MapHome.kt`, `shell/MapDetailSheet.kt`, `shell/DockedMapSheetHost.kt`, `shell/MapboxMapSurface.kt`, `map/BrowsingInitialCamera.kt`. Provisional, still owned by main. |
| T | `chat` / `codex/android-design3-chat` | `10c5b4a698dcf621a14ca65dbc09c6951f1d547b` (includes `b63f025b71b9db7a98e5b461a092b10dd682e4ad`). |
| K | `crown` / `codex/android-design3-crown` | `fe42b13cff05533ed0ae5d78b5e90d94732713bf`. |
| E | `events` / `codex/android-design3-events` | `8569d9b4bcddb53d1fc7ac7aa11e65ce981b2324` (includes `dec77bee4cfb81794103dfc80a9bfa23fc181e2a` and `d14a9645b55fedc4a08b6881102231753e2f2596`). |
| G | `garage-profile` / `codex/android-design3-garage-profile` | `108c7e443137e96e0584454960f86db1d5472ed8` (includes `1cc7a13906ac489da7aa092465e6e03ee6a06756` and `5979d4dcfee274d0d7d80842e82f04525ceaa704`). Cover/responsive-ratio follow-up after the initial audit; owner RouteHost wiring remains main-owned. |
| S | `settings` / `codex/android-design3-settings` | `2dd71332c2f89adf6b0c8fabe74b49f00a919617`. |

M is mutable: it must be reviewed again after main commits/integrates it. The
seven listed M source files had composite SHA-256
`eb05aeda4229377ea1adb30db056bf1bfb4e144f66b1ad37637384a967602d18`
when read (sorted relative path + LF + file bytes + LF for each file).

Source paths in the matrix are relative to
`apps/android/app/src/main/java/com/kungsbackacarcommunity/app/`.
`@nav/` and `@noNav/` instead use the respective `src/nav/java/.../app/`
and `src/noNav/java/.../app/` roots. `@ios/` uses `apps/ios/KCC/` solely
to identify S81. A B/M or other combined key identifies source dependencies,
not a tested integration.

### Per-screen Android matrix

<!-- android-pass3-matrix:start -->
| ID | Catalogue state | Page | Source review | Implementation / concrete difference | Exact native source files | Runtime / visual evidence still required |
| --- | --- | --- | --- | --- | --- | --- |
| S01 | Hemkarta | 04 | Reviewed · M | WIP: single map, collapsed search/rail retained; browsing-camera restore and docked host edited. | `shell/MapHome.kt`, `shell/MapboxMapSurface.kt`, `map/BrowsingInitialCamera.kt`, `AuthenticatedApp.kt` | U: real tiles/markers, first camera, return camera, session continuity. |
| S02 | Sökning öppnad | 04 | Reviewed · B/M | Retained search/results/recents; WIP shared map hosting. No matching populated keyboard capture. | `navigation/NavigationSearchScreen.kt`, `shell/MapHome.kt` | U: first tap, IME, results/empty/error, selection/Back. |
| S03 | Kartlager | 04 | Reviewed · M | WIP: native layer rows and Day/Night/System chips, incident-age controls; flags retained. | `shell/MapHome.kt`, `shell/MapDetailSheet.kt` | U: layer callbacks, saved override, large text and sheet/footer bounds. |
| S04 | Platsmeny | 04 | Reviewed · B/M | Retained navigate/save/copy; shared host WIP. Reference Share is not a new action in this component. | `shell/PlaceActionsSheet.kt`, `shell/ShareLocationSheet.kt` | U: selected marker visibility, copy/navigation/save and separate friend-share entry. |
| S05 | Ruttförslag (Android) | 05 | Reviewed · B | Retained route suggestion sheet; capability depends on build/token. No new route-preview implementation claimed. | `navigation/NavigationSearchScreen.kt`, `navigation/NavigationController.kt` | U: route fetch/failure, sheet drag, route selection, native navigation handoff. |
| S06 | Navigation (Android) | 05 | Reviewed · B | Retained nav/noNav variants and existing maneuver/progress chrome; exact reference composition pending. | `@nav/navigation/turnbyturn/TurnByTurnNavScreen.kt`, `@noNav/navigation/turnbyturn/TurnByTurnNavScreen.kt`, `navigation/turnbyturn/NavChrome.kt` | U: both build variants, GPS, rail order, arrival/stop and camera restoration. |
| S07 | Sparade platser | 05 | Reviewed · B | Partial: native saved-place management exists; reference All/Home/Work/Favorites filter tabs are not claimed. | `navigation/SavedPlacesScreen.kt`, `navigation/PrefsSavedPlacesStore.kt` | U: long lists, rename/repoint/delete/share. Storage is device-local, not the reference's account-sync claim. |
| S08 | Spara plats | 05 | Reviewed · B | Partial: real Home/Work/Favorite naming AlertDialog exists, not the illustrated full docked editor. | `navigation/NavigationSearchScreen.kt`, `navigation/SavedPlaces.kt` | U: defaults, edit/remove, keyboard and truthful device-local persistence. |
| S09 | Rapportera händelse | 06 | Reviewed · M | WIP: incident picker uses shared reading host; actual supported categories/actions retained. | `incidents/IncidentUi.kt`, `shell/MapDetailSheet.kt` | U: current-position/map-placement, cancel, busy/error, exact icon grid and footer clearance. |
| S10 | Händelsedetalj | 06 | Reviewed · B/M | Retained detail/verification/removal distinctions; docked host is WIP. | `incidents/IncidentDetailsSheet.kt`, `shell/MapDetailSheet.kt` | U: member/imported, reporter/non-reporter, both counts, distance/permission failures. |
| S11 | Polisinformation | 06 | Reviewed · B/M | Retained existing police-pin verify/dispute/reporter removal; no new reporting policy is implied by the drawing. | `police/PoliceDetailsSheet.kt`, `shell/MapDetailSheet.kt` | U: flags/access, TTL/removed pin, duplicate taps and sheet geometry. |
| S12 | Sponsrad kartplats | 06 | Reviewed · B/M | Partial: sponsored headline/message and supported CTA exist. Illustrated partner hero/photo is absent from this popup's inputs. | `billboards/BillboardMapPopup.kt`, `shell/MapDetailSheet.kt` | U: sponsored label, real creative/CTA, dismissed/deleted pin. |
| S13 | Starta körning | 07 | Reviewed · M | WIP: existing vehicle selector and Single/Convoy callbacks in shared sheet; no invented carousel data. | `AuthenticatedApp.kt`, `live/StartDrivingCarPicker.kt`, `shell/MapDetailSheet.kt` | U: selected real car, empty garage, auth/permission gates, explicit start only. |
| S14 | Livekontroller | 07 | Reviewed · B/M | Partial: real management actions retained with WIP host; reference elapsed-time/photo hero is not in LiveSharePopup inputs. | `shell/MapHome.kt`, `live/LiveLocationScreen.kt` | U: Stop/Hide immediately, expiry, restricted account, background and long privacy text. |
| S15 | Föraröversikt | 07 | Reviewed · B | Retained permitted sharer identity/points and View profile. Exact photo-led reference card not accepted. | `live/LiveSharerPopup.kt`, `live/NearbyLiveOverlay.kt` | U: blocked/stale/restricted sharer removal, permitted photo data and profile navigation. |
| S16 | Konvoj på kartan | 07 | Reviewed · B/M | Retained convoy awareness/status/follow markers and explicit session controls; map host WIP. | `convoy/ConvoyMapAwarenessOverlay.kt`, `convoy/ConvoyStatusBar.kt`, `AuthenticatedApp.kt` | U: member/leader, edge arrows, destination, stop/hide, marker layering. |
| S17 | Kronjakten översikt | 08 | Reviewed · K | Changed: overview/map/statistics/shop navigation, actual season and recent ledger summary; shop flag respected. | `crownhunt/CrownHuntScreen.kt`, `crownhunt/CrownHuntRoute.kt` | U: populated/empty/error, shop revocation, account change and full hub layout. |
| S18 | Handplacerad kronpunkt | 08 | Reviewed · K/M | Changed: curated-point reading sheet uses shared host, independent of spawn claims. Host integration pending. | `crownhunt/CrownPointPopup.kt`, `shell/MapDetailSheet.kt` | U: point details/eligibility, distance, safe navigation and marker clearance. |
| S19 | Automatisk krona | 08 | Reviewed · K/M | Changed: native spawn details and claim-state presentation use shared sheet and existing rarity assets. | `crownhunt/CrownSpawnPopup.kt`, `shell/CrownMarkerBitmaps.kt`, `crownhunt/CrownMarkerStyle.kt` | U: GPS/stillness/range, ready/sending/taken/expired/limit, flags and contrast. |
| S20 | Krona insamlad | 08 | Reviewed · K | Changed: server-awarded reward summary retained; no sample reward/balance fabricated. | `crownhunt/CrownRewardSummary.kt`, `crownhunt/CrownPointPopup.kt`, `crownhunt/CrownSpawnPopup.kt` | U: clipped/zero/duplicate reward, confirmed timing and reduced motion; not a verified full-screen match. |
| S21 | Kronstatistik och historik | 09 | Reviewed · K | Changed: statistics/history switch, server rarity counts and bounded owner ledger. Source composition exists. | `crownhunt/CrownHuntScreen.kt`, `crownhunt/CrownHuntRoute.kt` | U: loading/failure/empty/history, actual counts and long locales; data is not screenshot acceptance. |
| S22 | Säsongsställning | 09 | Reviewed · K | Partial: actual season board remains inside Crown statistics. Dedicated illustrated hero/season-picker page is not implemented. | `crownhunt/CrownHuntScreen.kt` | U: current/absent season, own standing, longer boards and accessibility. |
| S23 | Så fungerar Kronjakten | 09 | Reviewed · K | Changed: native instruction cards/rarity assets use canonical rules; sample distance/budget never overrides backend. | `crownhunt/CrownHuntScreen.kt` | U: both themes/large text, flag-dependent text, navigation back; no sample hero asset added. |
| S24 | Communitytopplista | 09 | Reviewed · K | Changed: actual category/scope tabs and standings. Preserve current-month default and real categories. | `leaderboard/LeaderboardScreen.kt`, `leaderboard/LeaderboardRoute.kt` | U: opt-out/unknown identity, category/scope switching, empty/error and own standing. |
| S25 | Perkbutik | 10 | Reviewed · K | Changed: actual catalog/balance/inventory, native perk icons and purchase status. Illustrated car hero is not a production asset. | `crownhunt/CrownHuntScreen.kt`, `crownhunt/PerkIcon.kt` | U: real KP catalog/prices, insufficient balance, flag revoke, buy/retry. |
| S26 | Använd en perk | 10 | Reviewed · K/M | Changed: existing inventory/activation/deploy controls dock to shared host; no cosmetic purchase added. | `crownhunt/PerkDeployMenu.kt`, `crownhunt/PerkMapOverlays.kt` | U: owned/active/unavailable, activation errors and shared-host integration. |
| S27 | Egen utplacerad fälla | 10 | Reviewed · K/M | Partial: actual owner-only trap details/countdown and expiry dismissal exist; reference Remove CTA has no callback here. | `crownhunt/DeployedTrapPopup.kt`, `crownhunt/PerkMapOverlays.kt` | U: expiry/background/flag/account changes; no fabricated address or removal operation. |
| S28 | Kronpoängshistorik | 10 | Reviewed · K | Changed: credit/debit filters and localized month groups over actual ledger entries. | `points/PointsScreen.kt`, `points/Points.kt` | U: balances, empty/error, missing timestamps, signs, locale and scroll. |
| S29 | Chatt – startsida | 11 | Reviewed · T | Partial: compact source selector/channel strip and opaque reading hub implemented; illustrated landing tiles are not the current structure. | `chatchannels/ChatHubScreen.kt`, `chatchannels/ChatFeedSelector.kt`, `chatchannels/ChatChannelStrip.kt` | U: shell title/footer, tab restore, UID switch, unread and populated matching viewport. |
| S30 | Communitychatt | 11 | Reviewed · T | Changed host/chrome around retained native channel bubbles/replies/composer. | `chatchannels/ChannelChatContent.kt`, `chatchannels/CommunityChannelRoute.kt` | U: IME/composer, paging, quote focus, moderation/blocks and long messages. |
| S31 | Konvojkanaler | 11 | Reviewed · B/T | Retained actual convoy channel list in compact chat host. No sample convoy pictures/titles/counts seeded. | `chatchannels/ConvoyListScreen.kt`, `chatchannels/ChatHubScreen.kt` | U: invitations/ended channels, unread, empty/error and long list. |
| S32 | Konvojchatt | 11 | Reviewed · B/T | Retained native convoy channel and actual context in compact host; no synthetic plan/membership count. | `chatchannels/ConvoyChannelRoute.kt`, `chatchannels/ChannelChatContent.kt` | U: joined/removed/ended, send/retry, plan links, keyboard and Back. |
| S33 | Meddelandeinkorg | 12 | Reviewed · B/T | Partial: conversation list restored in compact host; illustrated search/new-conversation layout is not fully reproduced. | `dm/ConversationListScreen.kt`, `chatchannels/ChatHubScreen.kt` | U: unread, account change, profile images, scrolling, empty/error. |
| S34 | Privat konversation | 12 | Reviewed · B/T | Retained DM/reply/composer in shared reading context. Sample online/location/crown context pills are not new runtime fields. | `dm/ChatScreen.kt`, `dm/DmRoutes.kt`, `chatchannels/ChatHubScreen.kt` | U: friendship/block revoke, retry, history, keyboard and focus restoration. |
| S35 | Ny konversation | 12 | Reviewed · T/M | Changed: real friend picker/search uses shared reading sheet; no made-up online status or handles. | `dm/NewDialogueSheet.kt` | U: long/search-empty/error list, selected friend, blocked/restricted state and shared host. |
| S36 | Vänner och förfrågningar | 12 | Reviewed · T | Changed: native search, incoming/outgoing requests and responsive friend rows; approved points kept. Reference two-tab organization differs. | `friends/FriendsScreen.kt` | U: search/sort, accept/decline/cancel, confirm remove, message, large text. |
| S37 | Driver-inlägg i Chatt | 13 | Reviewed · B/T | Partial: native read-only original Driver feed/media/paging exists with From Driver metadata. Likes/save/create/report writes remain unsupported. | `driverfeed/DriverFeedRoute.kt`, `driverfeed/DriverFeedSessionOwner.kt`, `chatchannels/NativeDriverFeed.kt` | U: actual linked account/access/bootstrap, paging/expiry/logout/switch. Existing read capability is not deferred wholesale. |
| S38 | Driver – inläggsdetalj | 13 | Reviewed · B/T | Partial: original post/comments read path exists. Illustrated comment composer/reply/like/save writes are not implemented here. | `driverfeed/DriverFeedRoute.kt`, `driverfeed/DriverFeedCoordinator.kt` | U: real post/comments/media, denied/deleted/moderated content, reauthorization and Back. |
| S39 | Meddelandeåtgärder | 13 | Reviewed · T/M | Changed: selected-message preview and actual reply/report/block actions in shared sheet; capability/flag rules retained. | `moderation/MessageActionsSheet.kt` | U: own/other message, wired/unavailable moderation, confirmation, focus and footer clearance. |
| S40 | Dela plats till vän | 13 | Reviewed · B/M | Partial: real single-friend DM delivery in shared picker; illustrated map hero/search/selection-and-send page not implemented. | `shell/ShareLocationSheet.kt`, `location/ShareLocationCoordinator.kt` | U: friend list/empty/error, send/retry, blocked recipient, dismiss only on success. |
| S41 | Eventlista | 14 | Reviewed · E | Changed: date badges, actual schedule/location/RSVP count and create/filter hierarchy. Model has no sample event-photo field. | `events/EventsListScreen.kt`, `events/EventSchedule.kt`, `events/Event.kt` | U: upcoming/past/nearby/empty/error, long titles and list navigation; reference photo cards remain a model gap. |
| S42 | Eventdetalj | 14 | Reviewed · E | Changed: real schedule/organizer, RSVP and grouped chat/group-drive/share actions; paid attendee boundary retained. No sample hero/view count. | `events/EventDetailScreen.kt`, `events/EventsRoute.kt` | U: free/paid/creator/cancelled, RSVP/send failure, precise info and all destinations. |
| S43 | Skapa eller redigera event | 14 | Reviewed · E | Changed: grouped real date/time picker, fields and existing pin location control; author validation preserved. | `events/CreateEventScreen.kt`, `events/EventEditorTime.kt`, `events/EventLocationPicker.kt` | U: create/edit/draft, IME, date/time/location, busy/error and bounds; no invented publish/image capability. |
| S44 | Eventchatt | 14 | Reviewed · E | Changed: real event title forwarded by route; native message hierarchy/composer retained without new reactions/attendee data. | `chat/EventChatScreen.kt`, `chat/EventChatRoute.kt` | U: actual title, long messages, restricted send, replies, keyboard and return route. |
| S45 | Incheckning till event | 15 | Reviewed · E | Partial: real embedded check-in card/countdown/confirmation in event detail, not a standalone photographic page. | `events/EventDetailScreen.kt`, `events/CheckInCoordinator.kt`, `events/CheckInDwell.kt` | U: paid gate, area/time/dwell, GPS lost/regained, pending/success/error and attendee privacy. |
| S46 | Gruppkörning för event | 15 | Reviewed · E | Changed: already-loaded event context and real participation roster/actions. Seat capacity, car photos, route/arrival status absent from model. | `groupdrive/GroupDriveScreen.kt`, `groupdrive/GroupDriveRoute.kt` | U: RSVP eligibility, join/leave/busy/error and real route context; reference hero/tabs remain different. |
| S47 | Dela event | 15 | Reviewed · E | Partial: real event teaser/date/location preview in existing single-recipient friend share. Illustrated bulk-select delivery is not added. | `events/EventShareSheet.kt`, `events/EventShareCoordinator.kt` | U: friend/empty/error, send/retry, account switch and exact modal/footer geometry. |
| S48 | Social – huvudflik | 15 | Reviewed · B | Partial: real destination cards exist; reference upcoming-event dashboard/category tabs are not implemented by these lane commits. | `shell/ShellHubs.kt`, `AuthenticatedApp.kt` | U: route availability/callbacks and matching screen. Do not call navigation cards a verified event dashboard. |
| S49 | Mina konvojer | 16 | Reviewed · B | Partial: active/invitation/ended data and native list exist; photographic cards and reference filter-tab layout not newly implemented. | `convoy/ConvoyScreens.kt`, `convoy/ConvoyRoute.kt` | U: invitation/active/ended/empty/error, long roster and safe exits. |
| S50 | Skapa konvoj | 16 | Reviewed · B | Retained native create/invite fields and real selected-car path; no sample destination/car artwork added. | `convoy/ConvoyScreens.kt`, `convoy/ConvoyDestination.kt` | U: selected garage car, friend search/invite limits, create busy/error and Back. |
| S51 | Konvojdetalj | 16 | Reviewed · B | Partial: actual status/participants and role-aware leave/end precede roster; reference hero/destination/action-grid not a full match. | `convoy/ConvoyScreens.kt`, `convoy/ConvoyBar.kt` | U: leader/member/forming/active, last-member exit, handoff and confirmation. |
| S52 | Avslutad konvoj | 16 | Reviewed · B | Partial: ended recap/actual fields and roster exist inside detail; no dedicated reference hero/automatic-save assertion or invented ride link. | `convoy/ConvoyScreens.kt`, `convoy/ConvoyRecap.kt`, `drives/DriveSavedDialog.kt` | U: partial/absent recap, actual save/failure/history association and return routing. |
| S53 | Körhistorik | 17 | Reviewed · G | Changed: localized month headings/privacy text with existing bounded list/filter/paging/restoration. | `drives/DrivesScreen.kt`, `drives/DriveHistoryMonth.kt`, `drives/DrivesRoute.kt` | U: 5/90-day/unlimited windows, filter/sort, load-more, Back/restore. No per-drive KP field fabricated. |
| S54 | Sparad körning | 17 | Reviewed · G | Changed: actual route overview precedes saved title/facts; share/expand/confirmed delete retained. No new sample address/KP/playback slider. | `drives/DrivesScreen.kt`, `map/DriveRouteFullscreenDialog.kt` | U: route loading/unavailable/empty, real replay expansion, title/time/facts, deletion/retry. |
| S55 | Körstatistik | 17 | Reviewed · G | Changed: actual month aggregate leads lifetime statistics. New lifetime max-speed exposure removed; existing facts retained. No 7/30/90-day/category charts. | `drives/DriveStatsScreen.kt`, `drives/DriveHistoryRepository.kt` | U: free server aggregate, absent/month/lifetime/error values; reference chart/period controls remain unsupported. |
| S56 | Sparande och återhämtning | 17 | Reviewed · G | Partial: responsive actual distance/time in existing non-dismissible failure dialog; retry/permanent-refusal behavior retained, not the reference full page. | `drives/SessionSummaryDialog.kt`, `drives/DriveSavedDialog.kt` | U: retries, retained recording, permanent refusal, close and post-save/history. No sample KP or new discard path. |
| S57 | Mitt garage | 18 | Reviewed · G | Changed: 2.5:1 below 360dp effective reading width (available card width/font scale), 16:9 above. Source policy implemented; visual ratio acceptance still pending. | `garage/GarageScreen.kt`, `garage/GaragePhotoLayout.kt`, `garage/VehicleCard.kt` | U: SAME VIEWPORT compare two cars + Add at 320×640, normal/large text and 2/5/10 limits. New geometry/reachability test is unexecuted and does not prove simultaneous visibility. |
| S58 | Fordonsdetalj | 18 | Reviewed · G | Changed: real hero/counter → identity/facts → thumbnails/actions, adaptive facts and full-width actions. | `garage/VehicleDetailScreen.kt`, `garage/VehicleGallery.kt` | U: real photos/load/fail, ten-photo selection/reorder/remove, blank plate/legacy catalog, large text. |
| S59 | Lägg till eller redigera bil | 18 | Reviewed · G | Changed: existing photo controls below fields and loading fallback. Gallery/Main-car controls remain in existing list/detail, not fake editor switches. | `garage/VehicleFormScreen.kt`, `garage/GarageRoute.kt` | U: data/draft persistence, catalog cascade, plate disclosure, pick/crop/compress/upload, save/error/IME. |
| S60 | Beskär fordonsbild | 18 | Reviewed · B | Retained real gesture crop/rotation/compression pipeline; no replacement editor or sample vehicle introduced. | `media/ImageEditScreen.kt`, `media/ImageCrop.kt`, `media/ImageCompressor.kt` | U: real portrait/landscape photo, pan/rotate/resize/reset/cancel/save, EXIF stripping and downstream crop. |
| S61 | Min profil | 19 | Reviewed · G | Partial: optional coverImagePath renders a real vehicle cover behind avatar with existing Storage/Coil fallback. Main must wire already-loaded owner vehicles through RouteHost; full reference shortcut list remains absent. | `profile/ProfileScreen.kt`, `profile/ProfileVehicleHero.kt`, `garage/VehicleCard.kt`, `profile/ProfilePointsSection.kt`, `profile/ProfileStatsSection.kt` | U: actual cover/avatar load/cache/failure/change, Supporter visibility, edit mode, points/drives callbacks. Lazy profile garage observation can incur additional reads; see validation cost note. |
| S62 | Redigera profil | 19 | Reviewed · G | Retained native editor; fields disable while saving, failed drafts/social handles preserved. No unsupported TikTok field. | `profile/ProfileScreen.kt`, `profile/ProfileSocialLinksEditor.kt`, `profile/SupporterBadgeSetting.kt` | U: save/fail/cancel/IME, real avatar upload, canonical handles and existing Supporter preference destination. |
| S63 | Annan medlems profil | 19 | Reviewed · G | Changed: actual main-car cover selected only from authorized Loaded vehicles, native fallback, left identity, friend menu/Message before car carousel. No extra vehicle query or owner-only progress. | `memberprofile/MemberProfileScreen.kt`, `memberprofile/MemberProfile.kt`, `profile/ProfileVehicleHero.kt`, `garage/VehicleGallery.kt`, `garage/VehicleCard.kt` | U: real photo/cache/failure, absent main/photo, account/block withdrawal, friend/busy/confirmation, last car and large text. Storage URL/image misses can still require network requests. |
| S64 | Profilens badgvägg | 19 | Reviewed · G | Partial: larger canonical medallions, known dates and max-three-column expansion in profile. Dedicated category-tab page is not implemented. | `profile/ProfileBadgesSection.kt`, `badges/BadgeWall.kt`, `badges/BadgeShowcase.kt` | U: earned/locked/unknown counters, expansion/details, real IDs/tiers and date text. Sample badges/levels are not adopted. |
| S65 | Partners | 20 | Reviewed · S | Changed: native company tile, supplied address and exhaustive-offer count. Company model has no hero/photo URL. | `partners/PartnersListScreen.kt` | U: directory/saved-locked, missing address/count, paging/error and long company names. |
| S66 | Partner och medlemserbjudande | 20 | Reviewed · B | Partial: native details/member-content/code/bookmark actions retained; illustrated hero, phone/hours only if actual contract provides them. | `partners/PartnerDetailScreen.kt`, `partners/PartnersRoute.kt` | U: paid/free, reveal/save/retry and safe external destinations; no sample company/photo/discount/code. |
| S67 | Partneransökan | 20 | Reviewed · B | Retained actual required application fields/validation and IME-aware form; sample schema is not substituted. | `partners/PartnerApplicationScreen.kt`, `partners/PartnerApplicationRoute.kt` | U: field errors, duplicate/busy/fail/success, keyboard and exact real fields. |
| S68 | Integritet och statistik | 20 | Reviewed · B | Retained separate partner-consent/leaderboard-visibility cards with unresolved-read protection. | `privacy/PartnerStatsScreen.kt`, `privacy/PartnerStatsRoute.kt` | U: independent choices, loading/error/save/rollback and actual opt-in state. |
| S69 | Inställningar | 21 | Reviewed · B/S | Retained grouped real destinations/appearance/language/version. Settings lane adds availability/callback/scroll tests, not a new layout. | `shell/SettingsScreen.kt` | U: null callbacks, all routes, system/light/dark, Swedish/English, actual version and large text. |
| S70 | Notisinkorg | 21 | Reviewed · B | Partial: actual inbox/read/delete/respond/routing retained; reference All/Unread tabs not newly implemented. | `notifications/NotificationsScreen.kt`, `notifications/NotificationsRoute.kt` | U: live/stale/dead targets, unread/read/delete, friend decisions, timestamps and paging. |
| S71 | Notisinställningar | 21 | Reviewed · S | Changed: compact title and in-app/push columns, stacked at narrow/large text; essential preferences remain locked on. | `notifications/NotificationSettingsScreen.kt` | U: independent channels, mandatory rows, OS push prompt/settings and save/error across all rows. |
| S72 | Blockerade användare | 21 | Reviewed · S | Changed: explanation, native avatar fallback, real localized block date, unknown-name fallback; original UID callback preserved. | `blocking/BlockingScreen.kt` | U: missing identity, busy, confirmed unblock/cancel, long names and live block transitions. |
| S73 | Rapportera problem | 22 | Reviewed · B | Retained real feedback form/public-report warning; actual 80/4000 bounds override sample 100/500. | `feedback/FeedbackReportScreen.kt`, `feedback/FeedbackReportRoute.kt` | U: IME, validation/rate limit/retry, successful clear and support/ticket links. |
| S74 | Öppna ärenden | 22 | Reviewed · B | Partial: actual public ticket paging/+1/comments/GitHub links retained. Reference Closed tab has no supported read path here. | `feedback/OpenTicketsScreen.kt`, `feedback/OpenTicketsRoute.kt` | U: paging, interactions/failures, comment IME, external destination and privacy warning. |
| S75 | Radera konto | 22 | Reviewed · S | Changed: retention explanation and acknowledgement before existing second destructive confirmation; auth/coordinator untouched. | `account/AccountDeletionScreen.kt`, `account/AccountDeletionRoute.kt` | U: unchecked/busy/error/cancel, both confirms and real reauthentication; no deletion performed. |
| S76 | Vad är nytt | 22 | Reviewed · S | Changed: actual bundled versions/date timeline and existing update dialog; no sample release/version bump. | `whatsnew/WhatsNewScreen.kt` | U: actual release ordering, long notes/date, empty/loading and once-per-version dialog. |
| S77 | Logga in – Android | 23 | Reviewed · B | Retained existing bundled brand assets/Google sign-in/quote/recovery. Reference benefit/footer composition not fully reproduced. | `auth/SignInScreen.kt`, `MainActivity.kt` | U: Google/native account picker, no-account/add-account/failure, real auth and small viewport. |
| S78 | Onboarding | 23 | Reviewed · B | Partial: actual name/licence/terms/privacy, optional partner and map-app steps retained; no avatar uploader/progress capability invented. | `onboarding/OnboardingScreen.kt`, `onboarding/OnboardingForm.kt` | U: required/optional consents, IME, back/continue/failure; sample avatar-based composition differs. |
| S79 | Välkommen | 23 | Reviewed · B | Partial: existing skippable four-step welcome, not the illustrated single photographic shortcuts page. | `welcome/WelcomeScreen.kt` | U: skip/finish, membership/profile/add-car callbacks, restore and long text. |
| S80 | Medlemskap | 23 | Reviewed · B | Partial: real Community/Plus/Supporter, subscribe/restore/change/manage retained. Screen lacks formatted price input; never substitute sample prices. | `subscription/SubscriptionScreen.kt`, `subscription/SubscriptionRoute.kt` | U: Play localized checkout, pending/cancel/restore/revoke/upgrade/downgrade and reconciliation. |
| S81 | Logga in – iOS | 24 | Reviewed · iOS | N/A for Android. Catalogue is explicitly Apple sign-in, not a deferred Driver screen. | `@ios/Auth/SignInScreen.swift` | N/A: iOS owned on Mac mini; no iOS runtime/visual acceptance by this audit. |
| S82 | Anpassa profilram | 24 | Reviewed · B | Deferred: no frame catalog/ownership/equip/purchase picker. Existing Supporter decoration is not that system. | `profile/SupporterAvatarFrame.kt`, `profile/SupporterBadge.kt` | Deferred: separate approved cosmetic contract/assets/payment lifecycle needed; no acceptance claimed. |
| S83 | Begränsat konto | 24 | Reviewed · B | Guard only: existing authoritative profile/access restrictions and settings/support/account routes; no dedicated illustrated account-status page. | `profile/UserProfile.kt`, `AuthenticatedApp.kt`, `shell/SettingsScreen.kt` | U: restricted/deleted/account-change paths and permitted escape routes. Reference standalone composition remains absent. |
| S84 | Platsbehörighet | 24 | Reviewed · B | Guard only: native OS request/remedy and nonblocking location-access card; not the reference full-screen scene. | `location/LocationAccessPrompt.kt`, `location/LocationAccess.kt`, `AuthenticatedApp.kt` | U: first/denied/permanent denial/services-off/settings-unavailable; granting permission must not start sharing. |
<!-- android-pass3-matrix:end -->

The matrix is the current source inventory for this audit. See
[Android validation](android-redesign-validation.md#pass-3-source-audit--2026-10-26)
for the pending integration/capture plan and preserved historical evidence.
Do not replace the per-screen differences with an '84/84 implemented' claim.

## Historical wave-2 Android migration — 2026-10-25


"Implemented" refers to source and regression coverage, not full visual/device
acceptance. Exact app SHAs, executed jobs and actual capture limits are in
[Android validation](android-redesign-validation.md) and
[executed evidence](redesign-executed-evidence.md).

| IDs / area       | Android source status                                                                                                                   | Rendered evidence / remaining scope                                                                                                                                                           |
| ---------------- | --------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| S01-S08          | Existing single-map shell/search/tool rail retained; detail sheets/places/search refined in #1178.                                      | Earlier shell captures; wave-2 place sheet. Real map/navigation and viewport variants still require configured QA.                                                                            |
| S09-S16, S49-S52 | Incident/police/sponsored sheets, live and convoy presentation implemented in #1178. Exit actions remain reachable before long rosters. | Live preview and place sheet; not complete police/incident/convoy/GPS-state coverage.                                                                                                         |
| S17-S28          | Crown summaries, stats/rewards/perks/trap, ledger and leaderboard refined in #1179; claim rules unchanged.                              | Crown hub preview; real claim/budget/DST and all detailed reward states remain.                                                                                                               |
| S29-S40, S44     | Chats, DM/friends/new chat, event chat and read-only Driver feed/detail implemented in #1180; per-account state preserved.              | Chat, populated local Driver feed/detail; keyboard, real accounts/moderation/block transitions and accessibility matrix remain. Unsupported Driver writes/linking destination not fabricated. |
| S41-S43, S45-S48 | Social dashboard/events/editor/share/group drive refined in #1179 and wired in #1181.                                                   | Event list/detail previews; editor/check-in/group-drive and full variants not visually accepted.                                                                                              |
| S53-S59, S61-S64 | Garage/profile/awards/drives implemented in merged #1176, with compact accessible garage menus in #1182.                                | Profile and final garage preview; other editor/statistics/retry/award states still need visual/device coverage.                                                                               |
| S60              | Existing crop mechanics retained.                                                                                                       | No new crop behavior or dedicated rendered acceptance claimed.                                                                                                                                |
| S65-S80          | Partners, settings, notifications/account/help/auth and membership refined in #1177.                                                    | Settings/membership previews; real auth, Play lifecycle, partner codes and remaining screen variants still require validation.                                                                |
| S81              | iOS login, out of current Android implementation scope.                                                                                 | Owned by iOS lane.                                                                                                                                                                            |
| S82              | Paid profile-frame purchases deliberately deferred.                                                                                     | No payment/catalogue/ownership implementation claimed.                                                                                                                                        |
| S83-S84          | Existing restricted-account and native permission guards retained.                                                                      | No invented standalone replacement screen; real account/OS transitions remain to test.                                                                                                        |

The twelve wave-2 captures are native production components with local test
inputs, English/dark/normal text at 320 × 640 pixels. They are not a full
84-view, Swedish/light/large-text or configured-device acceptance pass.

## Historical initial allocation — not the current Android to-do list

The initial joint allocation below is retained to explain the foundation split.
Its "inherited"/"pending" labels are historical; current Android status is above.
Do not infer today's iOS progress from this initial table.

| IDs     | Catalogue page | Screens / states                                           | Historical initial implementation lane                   |
| ------- | -------------- | ---------------------------------------------------------- | -------------------------------------------------------- |
| S01-S04 | 4              | Map home, expanded search, layers, place menu              | Both native shells; visual comparison required           |
| S05-S08 | 5              | Android route suggestions/navigation, saved places/save    | Preserve native flows; Android tool-rail parity          |
| S09-S12 | 6              | Report, incident detail, police information, sponsored pin | Preserve actions; inherited surface styling only         |
| S13-S16 | 7              | Start drive, live controls, driver preview, convoy map     | Shell styling; preserve session lifecycle                |
| S17-S20 | 8              | Crown hub, curated point, spawn, reward                    | Preserve claims; tier/animation refinement pending       |
| S21-S24 | 9              | Crown stats/history, season, rules, leaderboard            | Inherited surfaces; detailed visual pass pending         |
| S25-S28 | 10             | Perk shop/use/trap, KP ledger                              | Preserve server behavior; detailed pass pending          |
| S29-S32 | 11             | Chat home, community, convoy list/chat                     | Both platforms: opaque Chat tab and existing channels    |
| S33-S36 | 12             | Inbox, DM, new conversation, friends                       | Preserve rules/deeplinks; inherited surfaces             |
| S37-S40 | 13             | Driver feed/detail, message actions, share location        | Driver native reading now; original rules preserved      |
| S41-S44 | 14             | Events list/detail/editor/chat                             | Preserve functionality; detailed visual pass pending     |
| S45-S48 | 15             | Check-in, group drive, share event, Social                 | Preserve paid/free boundaries; Social shell updated      |
| S49-S52 | 16             | Convoys, create/detail/ended                               | Preserve coordinator and sessions; detailed pass pending |
| S53-S56 | 17             | My drives, drive detail/stats, save/retry                  | Move entry to profile; preserve data and restoration     |
| S57-S60 | 18             | Garage, vehicle/editor, crop                               | Direct Garage tab; inherited surfaces                    |
| S61-S64 | 19             | Profile/edit/member/badge wall                             | My drives entry + surface styling; detail pass pending   |
| S65-S68 | 20             | Partners/offers/application/privacy                        | Preserve access/consent; detail pass pending             |
| S69-S72 | 21             | Settings, inbox/preferences/blocked                        | Inherited surfaces; accessibility regression checks      |
| S73-S76 | 22             | Feedback/tickets/deletion/What's new                       | Preserve recovery/account flows; detail pass pending     |
| S77-S80 | 23             | Android login, onboarding/welcome/membership               | Inherited tokens; real auth/billing checks pending       |
| S81-S84 | 24             | iOS login, frame picker, restricted account, permission    | Preserve OS/auth; S82 paid cosmetics deferred            |

## Follow-up PR requirements

Name individual IDs and the actual states changed. Include same-size reference
and rendered captures, locale/theme/text-scale coverage, tested paths and
unverified configured-device scenarios. Refine this register after a delivery;
never mark an entire family complete because one screenshot or token changed.

The Android report lives at `docs/design/android-redesign-validation.md`; the
iOS report at `docs/design/ios-redesign-validation.md`. They are created with the
respective implementation PRs and should distinguish automated results,
rendered evidence, manual device checks and dependencies.
