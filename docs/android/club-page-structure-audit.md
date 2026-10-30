# Android club page: current-run audit and implementation

Scope: the member/owner club page on source `6756e8f`, native Compose, local
owner fixture with four members, one moderator, statistics and two events.
Product Design index, user-context, get-context and audit workflows were read;
preflight found no saved sources. The approved existing native design/tokens are
the target; no new visual direction, generated assets or cross-platform runtime.

## Accepted baseline evidence

Parent captured and passed the native fixture on a 1080x2400 emulator after
recovering a cold-boot System UI ANR. I opened and inspected all ten accepted
1080x2200 Compose captures from:
`/home/sebmccayen/Downloads/CarCommunity-live-club-qa-20261030/baseline-20261030/`.
Use `01-identity-light-sv-1_0 (1).png`; the unsuffixed light identity PNG is from
the rejected first attempt and is excluded. The remaining accepted files are
01 identity dark and 02 statistics / 03 calendar / 04 members-upper /
05 members-lower, each light and dark, with suffix `-sv-1_0.png`.

| Step and screenshot pair | Health and finding | Scoped change |
| --- | --- | --- |
| 01 Identity | Club name, logo, introduction and privacy state are present. Dark card renders all reading text gold; metadata competes with the introduction. | Neutral explicit surface/text colors; separate identity, supporting metadata and introduction within the existing card. |
| 02 Statistics | All three aggregate values and the membership-only privacy explanation remain visible. Gold dark body text and uniform value/label weight weaken hierarchy. | One combined statistics card with distinct values/labels and a wrapping layout at large font scales. |
| 03 Calendar | Month navigation, event dates and subscription affordance exist. Gold event text on ivory is visibly weak; calendar text inherits gold in dark mode. | Neutral reading/link text, restrained event-day accent, preserve month/event/subscription behavior. |
| 04 Members upper | Names and roles are visible but no avatars appear despite fixture avatar data. Each management action occupies its own persistent row, making a four-member roster unusually tall. | Compact avatar/name/role profile targets; long-press and labelled 48dp overflow for authorized member management. |
| 05 Members lower | Transfer/removal actions dominate the remaining roster; club-level destructive controls follow. Owner edit/logo controls sit between calendar and members in source. | One roster surface, confirmations retained, club management grouped after members instead of between core reading sections. |

Keep: existing page/back chrome, theme palette/type/spacing/radii, private access
proof and disposal, invitations, owner/moderator permissions, three-moderator cap,
server authority, error/busy states and pagination. Profile navigation is a
callback to the existing parent route host; it grants no new data access.

Baseline limits: these captures show appearance at scale 1.0 with local data.
The final code validation and after-capture audit are recorded below; neither
screenshot evidence nor fake-repository interaction tests certify production
permissions, live profile Storage data, TalkBack or physical-device readiness.

## Parent integration and QA

`SharedClubRoute` gains an additive named `onOpenProfile: (String) -> Unit`
parameter with a no-op default, passed to the current member content/rows.
Parent supplied and explicitly authorized the one RouteHost wiring change in
`d2b880684e0e7c57d4c7567ba7b829bebc645553`: `onOpenProfile =
onOpenMemberProfile`, preserving `onOpenEvent = openEventFromNotification`.
That wiring is committed. No profile/map/settings/backend/iOS, token, version or
release configuration changes are part of this club-page work.

## Implementation and handoff checks

Implemented: explicit neutral reading colors on club surfaces; identity/intro
separation; one adaptive aggregate-statistics card; neutral calendar reading text
with event-day accent; compact avatar/name/role rows; profile callback; accessible
48dp member overflow plus long-press; existing canonical operation payloads and
confirmations; cap/busy rechecks before confirm; management after the roster.
The capture fixture always renders current production SharedClubRoute and now
uses a neutral local-test-data banner, with captureRun separating before/after.
Two representative baseline and two accepted after PNGs are in the evidence
folder. After images are unmodified native-window captures, not Compose exports.

Added test classes:

- ClubMemberActionsTest: four pure permission/cap/wire-contract checks.
- ClubPageStructureTest: eleven production route/row/menu interaction checks,
  shared between JVM and native: profile name/avatar taps, labelled 48dp menu,
  long-press, confirmation/cancel, busy, cap, owner/mod/member authority, revocation,
  server-error retry, actual text-layout colors and 1.3x/2x font wrapping.
- ClubPageBaselineCaptureTest: existing baseline fixture now also captures the
  current implementation via a fresh after captureRun, both light/dark.

Agent verification: localized XML parses, Swedish/English key sets match,
git diff --check passes. No agent Gradle/emulator run.

Parent validated code `4f7d48efb612084780d3921dd047a11b7e2d101d`:

- 131/131 filtered JVM tests, including eleven avatar cases and roster tests;
  `lintDebug`, `assembleDebug` and `assembleDebugAndroidTest` passed. Log:
  `/tmp/carcommunity-live-club-final-20261030.log` (BUILD SUCCESSFUL).
- Actual native `ClubPageStructureTest`: 11/11 passed. Log:
  `/tmp/carcommunity-club-structure-native-20261030.log` (OK, 11 tests).
- Two native capture invocations passed, one each at font scales 1.0 and 2.0,
  producing twenty light/dark PNGs. Logs:
  `/tmp/carcommunity-club-after-1.0-20261030.log` and
  `/tmp/carcommunity-club-after-2.0-20261030.log` (each OK, 1 test).

Final visual QA: **passed within the local fixture limits**. Reviewed the ten
settled native-window captures plus three independently captured adb pause
images. Complete light statistics and dark calendar states appear in adb;
complete dark members appears in the settled native window. These resolve the
initial missing-content concern without any production UI change. Not every
output is valid: the adb members image and settled light-statistics/dark-calendar
images retain partial drawing and are excluded from accepted evidence. Capture
completion alone must never substitute for opening the saved PNG.

Parent's test-only diagnostic change adds `clubPageCaptureMode=window`, a 250ms
real compositor settle and optional eight-second named-state pause. Reviewed and
included unchanged with explicit parent authorization. Test APK build passed in
38s (`/tmp/carcommunity-club-window-settle-build-20261030.log`); settled native
capture passed one test (`/tmp/carcommunity-club-settled-native-20261030.log`).
The independent XML exports were not parseable and provide no semantic proof.

See [the final visual QA report](evidence/2026-10-30-club-page/design-qa.md) for
accepted/rejected paths, comparison history, inherited outlined-button treatment
and limits. Intermittent capture/compositor timing is a diagnostic limitation;
this audit does not certify rendering stability outside these accepted states.

## Canonical member avatar follow-up

Driver's current roster callable supplies only `users.avatarUrl`; CC profile
uploads store `users.avatarPath`. The Android roster therefore renders immediately
from the existing callable, then hydrates each newly loaded page asynchronously
from the public `users` documents. No change to Driver, Firebase backend, profile
persistence, AuthenticatedApp or authorization contracts is involved.

- `ClubMember.avatarPath` is additive. The public-avatar projection retains field
  presence: a present CC path wins; an explicit null/invalid CC path clears any
  stored legacy URL. If that field is absent, the current public Driver avatarUrl
  is used. A removed/missing avatar URL on an existing document clears the image;
  an unavailable/missing profile is no opinion and preserves the callable copy.
- A page requests at most 25 distinct UIDs, in at most three document-ID queries
  (chunks of 10), with at most two avatar-page reads in flight per repository.
  There are no per-row Firestore RPCs/listeners, timers or unbounded profile scan.
  Each page checks the current auth UID and revalidates four fresh fixed documents
  (config/account/member/club) after the public reads. Failed profile I/O does not
  hide the roster; revoked access/account changes cannot return an overlay.
- Image paths resolve through the existing `rememberStorageImageUrl` and shared
  download-URL cache. Storage work never delays the roster/page. A path-owned image
  does not fall back to an old Driver URL while resolving. Avatar image failure
  state resets when its path/model changes; explicit removal renders the native
  fallback, without resurrecting a denormalized image.
- Full roster refreshes advance a generation and stale results are discarded.
  Membership/club/account-keyed composition disposal cancels avatar jobs; an
  explicit post-await cancellation check prevents even a non-cooperative late
  loader from painting a replacement composition.
- `ClubMemberAvatarHydrationTest` adds eleven focused mapping/batch/scope/removal
  and cancellation cases. The ordinary-member long-press behavior test now checks
  that an explicit click adds exactly one profile navigation, allowing Android's
  normal click fallback on long-press; no-menu/no-mutation assertions remain.

These changes are included in the parent-validated `4f7d48e` code above.
The local drawable avatars in captures demonstrate image placement/cropping,
not a production Firebase/Storage round trip. The eleven avatar tests provide
separate mapping, batching, removal and account/access-fence evidence.
