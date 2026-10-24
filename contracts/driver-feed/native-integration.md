# Native Driver mounting and validation notes

Source inspected: `SebMcCayen/Driver` commit `d97583e`, CarCommunity base
`1b63f152`, and the implemented CarCommunity bridge in
`functions/src/driverFeed/{contract,index,reader}.ts` on
`codex/redesign-driver-read-api`. Design reference: brief v3 page 9 and catalog
S37/S38. The native sidecar includes shared foundation `41e60f05`.

## Mounting contract

- Android package `com.kungsbackacarcommunity.app.driverfeed`:
  `DriverFeedRoute(uid: String?, strings: DriverFeedStrings, modifier: Modifier = Modifier, isActive: Boolean = true, repository: DriverFeedRepository? = guardedFirebaseDefault())`.
  Construct `DriverFeedStrings(lookup = { key -> localizedValue(key) })`.
- iOS: `DriverFeedScreen(uid: String?, strings: DriverFeedStrings, isActive: Bool = true, repository: (any DriverFeedRepository)? = guardedFirebaseDefault())`.
  Construct `DriverFeedStrings(lookup: { key in localizedValue(key) })`.
- The default factory reuses the existing Firebase app, Auth UID, App Check, and
  `europe-west1`. With no Firebase configuration it returns nil; the component
  shows the unavailable-account state without touching the SDK.
- Both components own their post-detail navigation. Android handles system Back;
  iOS supplies an in-content Back action. Hosts mount in an opaque chat surface.
- Pass `isActive = false` while the Driver source is hidden, another route covers
  it, or the host tab is inactive. This cancels subscriptions and pending work,
  clears visible content, and retains the selected detail ID. Resuming reauthorizes
  content instead of displaying an old access snapshot. Native foreground state
  also gates reads. Inactive views can remain mounted for source continuity.
- Hosts resolve canonical localization keys. No generated catalogs, Shell,
  ChatHub, or AuthenticatedApp files are edited by this sidecar. No feed unread
  count is emitted or added to chat unread counts.

## Implemented backend binding

The original Driver API has no batch feed callable. Its protected single-post
callable, `driver-getNotificationThread`, permits 120 reads/hour; a native page
must not invoke it once per post. Driver's direct comment queries cannot enforce
reverse Driver blocks. The native repositories therefore use **only** the new,
implemented CarCommunity read bridge. There is no legacy/direct-content fallback.
See the bridge's [wire specification](read-api.md) and
[schema](../schemas/driver-feed.schema.json), delivered by the backend lane.

| Callable | Request | Response |
| --- | --- | --- |
| `driverFeed-list` | `{limit:20, cursor?:Cursor}` | `{posts:Post[], nextCursor:Cursor|null}` |
| `driverFeed-detail` | `{threadId, limit:20, cursor?:Cursor}` | `{post:Post, comments:Comment[], nextCursor:Cursor|null}` |

`Cursor` is `{seconds, nanoseconds, id}`. Feed order is timestamp/ID descending;
comment order is timestamp/ID ascending. Cursors advance over the **raw scanned**
page, including filtered empty pages, and preserve nanosecond precision. A full
terminal page can require one final empty call. Feed and detail cursors are
independent. Native wire decoders reject malformed cursors and oversized pages.

Each post contains original text, canonical author name, Driver handle, original
timestamp (`createdAtMillis`), persisted media URLs (maximum four), and read-only
like/reply counts. Detail returns the parent and its comment page together, so
one page requires one callable. Post IDs remain the original Driver IDs.

The server checks shared onboarding, canonical suspension/deletion, Driver
onboarding/status, active post/comment/author status, both directions of Driver
blocks, and canonical KCC symmetric visibility plus authoritative block edges.
The detail parent is reauthorized on every comment page in the same read-only
transaction snapshot. There is no staff/author bypass for hidden content in this
native reading surface. The bridge scans at most 20 candidates per call, rather
than filling hidden pages with an unbounded loop. Its shared page budget is
120 list/detail calls per UID per hour.

Client owner/access/parent listeners invalidate memory immediately and coalesce
bootstrap callbacks before reloading. Cancellation and generation fencing reject
late responses; the captured Firebase user object fences each callable against
account changes, including A/B/A transitions. Both platforms refresh on foreground.
No SDK messages, content, IDs, or tokens are logged. Reads reflect the authorized
server snapshot; another author's later private block change is reevaluated on
subsequent reads, not pushed as a new private block listener.

## Onboarding and consent

Reading has no paid membership entitlement gate. It requires the canonical
`users/{uid}.onboardingCompletedAt` and an existing
`driverUsers/{uid}.onboardingComplete == true`; restricted statuses are denied.
Error reasons distinguish `canonical-onboarding-required`,
`driver-onboarding-required`, `account-restricted`, and `thread-unavailable`.
Missing deployment, quota, and malformed data errors do not become fake empty
content or a false onboarding success.

Driver's separate **write** callable `driver-completeProfile` takes
handle/model/display-name/bio and changes the canonical profile. Its inspected
schema contains no standalone terms/consent acceptance field. The native reader
does not invoke it, create membership, or silently accept anything. A member
without Driver onboarding gets an accurate recovery explanation. Native Driver
profile creation is outside this read-only slice.

## Localization handoff

All keys below are under `driver.feed.`. Main owns adding them to both source
localization contracts and running the existing native generators.

| Suffix | Swedish | English |
| --- | --- | --- |
| title | Driver-inlägg | Driver posts |
| source | Från Driver | From Driver |
| loading | Hämtar inlägg… | Loading posts… |
| empty | Inga inlägg att visa. | No posts to show. |
| error | Det gick inte att hämta innehållet. | Couldn't load content. |
| retry | Försök igen | Retry |
| refresh | Uppdatera | Refresh |
| more | Visa fler | Load more |
| back | Tillbaka | Back |
| comments | Kommentarer | Comments |
| noComments | Inga kommentarer att visa. | No comments to show. |
| unavailable | Inlägget är inte tillgängligt. | This post is unavailable. |
| onboarding | Slutför din Driver-profil i Driver för att läsa inlägg här. | Complete your Driver profile in Driver to read posts here. |
| canonicalOnboarding | Slutför din profil i CarCommunity för att läsa Driver-inlägg. | Complete your CarCommunity profile to read Driver posts. |
| restricted | Driver är inte tillgängligt för ditt konto. | Driver is unavailable for your account. |
| image | Bild i inlägget | Post image |
| likes | Gilla-markeringar | Likes |

## Validation and integration

Android model/coordinator/wire JUnit tests cover filtered-page continuation,
deduplication, original detail IDs, independent cursors, cancellation, late
responses after account stop/refresh, invalidation, error/retry, exact payload
limits, cursor precision/bounds, oversized responses, and error-reason mapping.
Matching iOS XCTest coverage is supplied; running it requires the iOS toolchain.

All 17 isolated Android JUnit tests passed, including the host-pause test.
The first standalone SDK/Compose compiler attempt reached a missing
Checker Framework annotation in its temporary dependency classpath; that is not
evidence of a packaged app build or of a Gradle dependency failure. Main owns
the real Gradle build/emulator validation. The optional source-compile retry was
stopped to free resources for that actual Gradle build. Its local helper is not
part of the delivery or required CI path.

The platform integrators still need actual native builds, locale/text-size/theme
visual checks, and configured-device validation. The iOS integrator must
regenerate the Xcode project from `project.yml` to include new source/test folders.

The backend bridge must be merged/deployed intentionally before production reads
can work. Verify App Check for KCC app registrations and the combined rules for
owner/parent invalidation listeners. Source implementation does not establish
live deployment or production configuration. No Driver repository changes,
production deploy/write, write reactions, composer, Flutter embedding, or
cosmetic purchases are included.
