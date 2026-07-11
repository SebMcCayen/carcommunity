# Competitive analysis: Open Road (openroaddrive.com)

> **Status:** Competitive analysis — informational. Roadmap recommendations herein require explicit approval before any build.
> **Author:** Claude (delegated competitive teardown)
> **Date:** 2026-07-12

---

## 1. Executive summary

Open Road is **"Strava for driving"**, not a community platform. Its entire
product is the recorded drive: auto-logged GPS tracking, animated route
replay on a game-like 3D map, stats/streaks/segments, and live "Convoys"
with voice chat. Its social layer is a feed *around* drives. It has **no
events, no clubs, no chat channels, no partners, no marketplace, no
spotting, no build threads** — none of the things that make carcommunity a
community.

The strategic picture is unusually clean:

- **They are strongest exactly where we are thinnest.** Our drive recording
  works and is privacy-sound (`functions/src/drives/`,
  `apps/android/.../drives/`), but the *experience* is utilitarian — a list
  of saved drives with stat rows and a static route image. Open Road turns
  the same GPS trace into a celebration: replay, streaks, share cards,
  personal road map. That gap is the single biggest thing they do better.
- **They are absent exactly where we are strongest.** Events + RSVP +
  per-event chat, group drives, partners + offer redemption, Kronpoäng +
  badges, admin moderation and announcements, friends + DMs — Open Road has
  none of it. Against a community app they are complementary, not head-on;
  our job is to make sure members never need a second app.
- **Sweden is unpenetrated.** 4.7★ on the Swedish App Store but only **9
  ratings**; zero Nordic marketing or content curation. Their curated
  "famous roads" directory is US-heavy. A locally curated Swedish product
  wins that ground by showing up.
- **Android is their weak platform, and we are Android-first.** Their polish
  is all iOS (CarPlay, widgets, Dynamic Island). Android reviews report
  wildly wrong speed detection, broken friend-adding, broken theming, buggy
  sharing. Our entire MVP is native Android.

**Bottom line:** we should not chase Open Road's identity, we should absorb
its one great idea. Close the drive-*experience* gap (make a saved drive feel
like a memory, not a database row), elevate convoys (their most-loved
feature, already designed in our parked proposal), and press our moats:
community infrastructure, Swedish utility, and Android quality. We
explicitly do **not** copy their speed gamification (§4).

## 2. Teardown summary

Condensed from the 2026-07-12 research pass (sources in Appendix).

### Product and market
- GPS drive tracker that gamifies every drive. Tagline "Every road.
  Remembered." Deliberately evokes Forza ("real-life Forza" TikTok ads are
  the growth engine).
- OPENROAD LLC, small US indie (package `com.mikita.openroad`), no funding
  trail. Launched ~Feb 2026 (v2.0), now v4.6.8 (Jul 2026) — very fast
  release cadence.
- Scale claims: 187.4K+ drivers, 4.3M+ miles, 63.8K+ convoys; ~50K+ Play
  installs; iOS US 4.7★ (752 ratings). Active in 92 countries, 29 languages
  (incl. SV/DA/NO/FI) — but SE App Store has only 9 ratings.
- Freemium: Pro $9.99/mo, $29.99–39.99/yr (SE: 129 kr/mo, 399–499 kr/yr).
  No ads. Paywall contents not itemized publicly.

### Feature inventory
- **Drives (core):** auto-logging when the car moves, drive history/logbook,
  animated route replay on a 3D map, per-drive stat card (distance, time,
  avg/top speed, g-force, streak), live HUD, GPS speedometer, GPX
  viewer/export, favorite routes.
- **Roads/map:** cumulative personal map of every road ever driven, personal
  heatmap, curated famous-roads directory (15 roads, US-heavy) + city
  "best driving roads" SEO guides, built-in navigation, CarPlay/Android Auto.
- **Convoys (flagship):** live shared map of friends' cars, built-in
  hands-free voice chat ("walkie-talkie for road trips"), shared post-convoy
  stat card. Requires mutual in-app friends.
- **Gamification:** streaks + streak calendar, segments (personal bests
  only), "speed traps" (save fastest moments at points), speed
  zones/challenges, friends leaderboards (miles/streaks/drives),
  mile-based achievements, post-drive celebrations, driving score with
  casual/sport modes. No XP/levels/badges.
- **Social:** drive feed (friends or worldwide), private-by-default
  profiles, and the key viral loop: **pre-built IG/TikTok share cards with
  speed-colored route overlays**.
- **Garage:** multi-vehicle tracking only — a stub. No specs, mods, photos,
  build threads.

### Design language
Dark, game-like aesthetic: 3D map with low-poly stylized car models,
self-drawing animated route trail, instrument-cluster HUD. The post-drive
flow is a designed moment: park → stat card → celebration/milestone → share.
Share cards are sized for IG stories/TikTok. Deep iOS polish; Android
visibly second-class.

### Sentiment
- **Loved:** route-recording accuracy, Convoy ("amazing"), UI/design
  quality, generous free tier, privacy-by-default.
- **Complaints:** crashes; phone overheating on 30-min CarPlay drives;
  CarPlay lag; scoring that punishes normal driving; Pro entitlement
  reliability; and a long Android-specific list (speed detection wildly
  wrong, can't add friends, broken theme switcher, broken Android Auto
  search, buggy sharing). Users request Waze-style hazard reporting, which
  they don't have.

## 3. Feature-gap matrix

"WE" = carcommunity as merged on `main` (Android app under
`apps/android/app/src/main/java/com/kungsbackacarcommunity/app/`, Cloud
Functions under `functions/src/`, admin web under `apps/admin/src/features/`).
Parked proposals in `docs/proposals/` are marked **proposed, not built**.

### 3a. THEY have / WE lack

| Open Road feature | Our status | Notes |
| --- | --- | --- |
| Auto-start drive detection | Missing | We require manual start in `drives/RecordDriveScreen.kt`. Battery/privacy trade-offs; optional at best. |
| Animated route replay on interactive map | Missing | Our saved-drive detail (`drives/DrivesScreen.kt`) shows stat rows + a static member-gated route image. This is the heart of the experience gap. |
| Post-drive "moment" (stat card → celebration → share) | Missing | We show a save prompt; no celebration, no shareable artifact. |
| Streaks / drive-count milestones | Partial-adjacent | We have backend Kronpoäng + badges (`functions/src/points/`, `functions/src/badges/`) but nothing drive-cadence-based. |
| Personal "roads I've driven" map / heatmap | Missing | All ingredients exist (route storage, Mapbox in `map/MapScreen.kt`). |
| Social share cards (IG/TikTok export) | Missing | Their #1 growth loop. A no-speed variant is safe for us (§4). |
| Convoy live map + voice chat | **Proposed, not built** — `docs/proposals/convoy-mode.md` | Our proposal covers their whole convoy feature incl. voice as a parked phase, plus event attachment they can't do. Security prerequisite (live-location RTDB rules) documented there. |
| Built-in navigation / Android Auto | Missing | Big investment, low differentiation — navigation is a solved problem (Google Maps). Not recommended. |
| GPX export/viewer | Missing | Cheap goodwill feature for enthusiasts; data is already ours. |
| Curated "best driving roads" directory | **Proposed, not built** — `docs/proposals/road-ratings-map.md` | Theirs is 15 US-heavy roads. Ours is community-rated + admin-curated Swedish roads — strictly stronger locally. |
| Segments / leaderboards / speed traps / driving score / g-force | **Deliberately absent** | See §4. Locked out by `docs/product-decisions.md` ("Toppfart och speed-baserad ranking ingår inte i MVP", Driving mode rules). |

### 3b. Both have — who does it better

| Feature | Winner today | Why |
| --- | --- | --- |
| Drive recording (GPS capture + stats) | **Open Road** on experience, **we** on integrity | Their capture is polished (auto-start, HUD, replay) but Android speed detection is reportedly broken. Ours is backend-authoritative and deliberately conservative: `functions/src/drives/drive-calculations.ts` computes distance/duration/avg speed server-side, explicitly no top speed, with implausible-point filtering. Solid foundation, thin presentation. |
| Group driving | **Split** | Their Convoy has live shared map + voice chat — best-in-class in-drive. Our group drives (`groupdrive/`, `functions/src/groupDrive/`) + live location sharing (`live/`, `functions/src/live/`) + per-event chat give the *social wrapper* (roster, event, chat history) they lack. Convoy-mode proposal merges both. |
| Friends / social graph | **We** (barely) | Friends + DMs just landed backend-authoritative (`functions/src/friends/`, `functions/src/dm/`); their Android friend-adding is broken per reviews. |
| Garage | **We**, decisively | Ours has real vehicle management (`garage/`, `functions/src/garage/`); theirs is a name-only stub. `docs/proposals/garage-build-threads.md` would extend the lead. |
| Map | **Open Road** on looks, **we** on purpose | Their 3D low-poly map is a brand asset. Our Mapbox map (`map/MapScreen.kt`) + map-first home shell carries live members, events, Kronjakt — content theirs can't show. Visual ambition is worth borrowing. |
| Gamification | **Different games** | Theirs: streaks/miles/speed. Ours: Kronpoäng + badges + Kronjakt crown hunt (`crownhunt/`, `functions/src/crownHunt/`) — community- and place-based, backend-authoritative, explicitly non-speed. Ours is defensible; theirs is legally exposed (§4). |
| Subscription | **We** on discipline | Both freemium. Ours: single `member_monthly` via Play Billing with backend entitlements (`functions/src/subscription/`); theirs has reported entitlement-reliability complaints. Their SE pricing (129 kr/mo) is a useful reference point. |
| Privacy defaults | **Tie on posture, we on substance** | Both private-by-default. We additionally have consent surfaces (`privacy/`), user blocking, account deletion, and no speed/g-force retention at all. |

### 3c. WE have / THEY lack

| carcommunity capability | Where it lives |
| --- | --- |
| Events: list/detail/RSVP + per-event chat | `events/`, `chat/`, `functions/src/events/` |
| Partners + offer redemption (local business moat) | `partners/`, `functions/src/partners/`, partner insights for admins |
| Community points/badges economy (backend-authoritative) | `points/`, `badges/`, `functions/src/points/`, `functions/src/badges/` |
| Kronjakt map minigame (place-based, non-speed) | `crownhunt/`, `functions/src/crownHunt/` |
| Real garage + vehicle management | `garage/`, `functions/src/garage/` |
| Admin platform: moderation, feature flags, audit log, announcements, batch notifications, credential tracker | `apps/admin/src/features/`, `functions/src/admin/` |
| Notifications: inbox + prefs + FCM push | `notifications/`, `push/`, `functions/src/notifications/` |
| Trust & safety: blocking, chat-report moderation, consent, account deletion | `blocking/`, `privacy/`, `functions/src/blocking/` |
| In-app feedback → GitHub issue loop | `feedback/`, `functions/src/feedback/` |
| Billboards (community/partner surface) | `billboards/`, `functions/src/billboards/` |
| Swedish-first UI, 18+ community, local (Kungsbacka/Halland) identity | `docs/product-decisions.md` |
| Parked proposals extending the lead: car spotting game, car-care calendar (besiktning/däckbyte), build threads, road ratings map, convoy mode, vehicle reg lookup, nearby notifications, roadside SOS | `docs/proposals/*.md` — all proposed, not built |

## 4. What we deliberately do NOT copy

Open Road's speed gamification — **top-speed stat cards, g-force readouts,
"speed traps" (save your fastest moment at a point), speed zones/challenges,
and speed-colored share routes** — is their identity and their exposure. We
copy none of it, for three reasons:

1. **Legal risk in Sweden.** Publicly celebrating top speed and lateral g on
   named public roads is evidence-adjacent to **vårdslöshet i trafik** and
   fortkörning. An app that rewards "your fastest moment at this point" on
   Swedish roads invites exactly the scrutiny a local, real-identity club
   cannot survive. There are also plausible insurance implications for
   members whose apps retain speed histories.
2. **Brand risk.** carcommunity is an 18+, real-community, locally rooted
   club with partner businesses attached. Its reputation with municipality,
   partners, and members' neighbours is an asset; "the speeding app" is a
   one-headline way to lose it.
3. **It's already a locked product decision.** `docs/product-decisions.md`
   codifies it: no top speed / speed-based ranking, Driving mode must not
   encourage risky interaction, Kronjakt must not reward fastest-to-place
   behaviour. Our backend enforces it structurally —
   `functions/src/drives/drive-calculations.ts` neither computes nor stores
   top speed, and the existing proposals already take the same stance
   explicitly (`road-ratings-map.md`: quality/scenic ratings only, no times,
   no speed, no leaderboards; `convoy-mode.md`: safety-first, no racing
   mechanics).

**Our counter-position: gamify exploration and community, not speed.**
Roads discovered, drives shared, events attended, crowns hunted, spots
logged, builds documented, friends convoyed with. Everything in §5 is
consistent with that line — including the share cards, which for us show
route shape, distance, place names, and event context, never speed coloring.
Notably, Open Road's own users complain that its driving score "punishes
normal driving" — speed gamification is a treadmill we are better off never
stepping on.

## 5. Strategic response / leapfrog roadmap

> Recommendations only — **nothing below is approved for build.** Each item
> that involves new features must go through the normal proposal/approval
> flow; parked proposals stay parked until Seb green-lights them.

### 5.1 Close the drive-experience gap (highest leverage)

This is the one place they genuinely beat us, and it sits on infrastructure
we already own (recording, backend stats, member-gated route storage,
Mapbox). A sibling proposal is being written in parallel —
**`docs/proposals/drive-experience-upgrade.md`** — covering the concrete
design (route replay, post-drive moment, personal driven-roads layer,
no-speed share cards, drive-cadence badges). This analysis defers to that
document rather than duplicating it; the strategic point is that it should
be treated as the **primary response** to Open Road.

### 5.2 Elevate convoy-mode priority

Convoy is Open Road's **single most-loved feature** ("amazing" in reviews)
and the flagship of their marketing (63.8K+ convoys claimed). We already
have a full design: `docs/proposals/convoy-mode.md` (PR #333), which covers
everything theirs does — live shared map, event attachment they can't
match — and parks voice chat as an explicit later phase, exactly the piece
their users rave about. Recommendation: move it from "parked, unranked" to
the **top of the ideas board**, contingent on its two documented
prerequisites (live-location RTDB rules fix; FCM push send path).

### 5.3 Lean into our moats

They cannot follow us here without becoming a different company:

- **Events + partners:** keep events the center of gravity; deepen partner
  offers around drives (e.g. event-linked drives, partner stops on group
  drives).
- **Swedish utility:** `docs/proposals/car-care-calendar.md` (besiktning,
  däckbyte, service) creates daily practical value no US drive-tracker will
  ever build for Sweden — the strongest retention play on the board per its
  own analysis.
- **Community content:** `docs/proposals/garage-build-threads.md` turns our
  already-superior garage into living content between events; their garage
  is a stub, and their sharing loop is outbound-only (IG/TikTok) with no
  in-app community memory.

### 5.4 Exploit their weaknesses

- **Android quality as a stated goal:** their Android build is reportedly
  broken in basics (speed detection, friends, theming). We are native
  Android-first; ship the drive-experience work at high polish and we win
  every side-by-side on the platform that matters for our members.
- **Nordic curation:** `docs/proposals/road-ratings-map.md` with
  **admin-seeded local "best roads"** (Halland coast, curated by the club)
  beats their 15 US-famous roads for every Swedish user on day one. Their
  SEO guides have no Sweden content at all.
- **Community infrastructure:** every convoy, drive, and road rating we
  ship lands inside chat, events, moderation, and admin tooling they don't
  have. Bundle, don't unbundle.
- **Their thermal/stability problems** at the core use case (long recorded
  drives) reward our conservative, battery-respectful recording approach —
  keep it, and say so in store listings.

### 5.5 Suggested build order (IF approved — approval is Seb's)

1. **Drive-experience upgrade** (`docs/proposals/drive-experience-upgrade.md`) — closes their one real advantage; highest reuse, no new
   safety surface.
2. **Convoy mode, phase 1** (`docs/proposals/convoy-mode.md`) — their
   most-loved feature, inside our event/social wrapper; after its security
   prerequisites.
3. **Car-care calendar, phase 1** (`docs/proposals/car-care-calendar.md`) —
   Swedish daily utility; cheap, privacy-clean retention.
4. **Road ratings map, phase 1 + admin-seeded Halland roads**
   (`docs/proposals/road-ratings-map.md`) — Nordic curation moat; pairs
   naturally with the drive-experience work.
5. **Garage build threads, phase 1** (`docs/proposals/garage-build-threads.md`) — community content flywheel; extends a lead they
   can't contest.

Rationale for the order: 1 neutralizes their strength, 2 captures their
best idea inside our moat, 3–5 widen gaps they are structurally unable to
close. Car-spotting game (`docs/proposals/car-spotting-game.md`) remains a
strong gamification candidate but is orthogonal to this competitor and is
not ranked here.

## 6. Appendix — sources

Research pass 2026-07-12:

- https://openroaddrive.com (plus subpages: `/drive-tracker`, `/convoy`,
  `/turn-driving-into-a-game`, `/strava-for-driving`, `/speed-tracker`,
  `/roads`, `/gpx-viewer`, `/car-tracker`, `/best-driving-apps`,
  `/compare/open-road-vs-strava`)
- https://apps.apple.com/us/app/id6755834573 (US App Store)
- https://apps.apple.com/se/app/id6755834573 (Swedish App Store)
- https://play.google.com/store/apps/details?id=com.mikita.openroad
- NZ App Store version history (release cadence)
- mwm.ai listing (scale/metadata)

Internal references: `docs/product-decisions.md`, `docs/proposals/*.md`,
`apps/android/app/src/main/java/com/kungsbackacarcommunity/app/`,
`functions/src/`, `apps/admin/src/features/`.
