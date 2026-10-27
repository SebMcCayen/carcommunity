# Android Driver exact-post links (contract v1)

Companion receiver: [Driver PR #34](https://github.com/SebMcCayen/driver/pull/34).

## Matching applications and ID mapping

- Driver Android application ID: `com.kungsbackacarcommunity.driver`, verified
  in Driver `android/app/build.gradle.kts`.
- URI: `kcc-driver://post/{threadId}`. This is a new, jointly implemented
  contract, not a previously supported Driver route.
- `threadId` matches `^[A-Za-z0-9_-]{8,128}$`. It is the original Firestore
  `driverThreads/{threadId}` document ID, never an author UID, reply ID, local
  index, or CarCommunity event ID. Event mirrors use their full
  `carcommunity_event_{eventId}` thread ID without stripping the prefix.
- Mapping evidence: CarCommunity `functions/src/driverFeed/reader.ts` returns
  `id: doc.id`; `FirebaseDriverFeedRepository.feed` decodes that exact `id`.
  Detail verifies the returned ID equals its requested `threadId`.
  Driver `getNotificationThread` in `functions/src/index.ts` reads
  `driverThreads/{input.threadId}` and returns `id: thread.id`; its Flutter
  repository uses that ID unchanged.
- Example (synthetic ID): `kcc-driver://post/thread_12345678`.

## Sender and receiver

Each feed card and native detail sends its own `post.id` to `openDriverPost`.
The helper validates the ID and encodes it as one path component using
`Uri.Builder.appendPath`. `ACTION_VIEW` is explicitly restricted to the
verified Driver package, with no token, author identity, text, other extras,
ClipData, or permission grants. No package visibility permission is needed.

The companion Driver Android PR registers VIEW/DEFAULT/BROWSABLE for
`kcc-driver://post/`. `PostLinkContract` rejects other schemes/authorities,
queries, fragments, credentials, ports, additional segments, encoded path
separators, and invalid IDs. Flutter's automatic Android deep-link routing is
disabled so it cannot bypass the custom auth-gated handler.

The native activity retains only the latest ID during bootstrap/login/onboarding
and handles both cold starts and `onNewIntent`. The private MethodChannel
`com.kungsbackacarcommunity.driver/post_links` exposes `takePendingPost` and
signals `postAvailable`. No ID is logged. The Flutter inbox is mounted only
inside the existing successful auth/onboarding branch. Every external target
is reauthorized via the existing `getNotificationThread` callable, even when
cached in the feed. The returned ID must match. Linked details use a
session-owned Navigator, removed on logout/account switch; late or superseded
loads cannot open a route. Deleted, blocked, inaccessible, or mismatched targets
show a generic error with explicit retry. No Firebase backend changes or
permission bypass are introduced.

## Install/update fallback: gated pending owner URL

There is still **no verified download/update destination**. The prior Play
listing check returned HTTP 404, and private expiring Actions artifacts are not
public download pages. No such destination is shipped as a default.

`BuildConfig.DRIVER_DOWNLOAD_URL` is generated from the Gradle property of the
same name and defaults to an empty string. Configure it only after the owner
supplies an actual verified public HTTPS install/update page. Runtime validation
rejects malformed/non-HTTPS URLs, missing hosts, credentials, and fragments.
When the Driver handler is missing (including older Driver installations), the
helper can open that configured page through Android's browser/store handler.
It never appends the post ID, token, or identity to that public URL. Missing
browser/store or security rejection produces a localized recoverable error.
A blank/invalid setting also shows that error; it never sends the post URI to
an unrestricted resolver or opens the generic Driver home screen.

This fallback remains a release/integration gate until a verified URL is
provided. Installing/updating does not automatically resume the original link;
the user taps the post action again. The companion Driver handler must be
available in an installed Android version before exact-post navigation works.

## Validation and scope

CarCommunity tests cover exact per-card IDs, detail, internal navigation,
Swedish/English strings, production button-to-intent wiring, ID boundaries,
package restriction, gated fallback, unsafe URLs, and missing handlers.
Driver adds native parser tests plus headless Flutter inbox/session tests.
Gradle tests and native cold/warm launch validation await the parent's compile
slot; no emulator changes, Android app builds, deployments, releases, or merges
are authorized here.

The feature is Android-only. The owner separately approved mechanical
regeneration of `apps/ios/KCC/Resources/Localizable.xcstrings` from the canonical
sv/en contracts to keep localization CI consistent. No iOS feature/design code
is changed. Root npm checks previously could not run in this clean worktree
because workspace dependencies were absent; no root dependencies are changed.

Validation results for the paired receiver: `flutter analyze --no-pub` reports
no issues; the targeted six link tests and complete 66-test headless Flutter
suite pass. Both repositories pass `git diff --check`. CarCommunity's 11 new
JVM/Robolectric tests and Driver's native parser tests have not been rerun in
this iteration because the parent has not yet granted a Gradle slot. Static
checks verify matching URI/ID grammar, manifest registration, ID projection,
and disabled automatic Android routing. Android and approved iOS locale
regeneration are deterministic; the iOS diff adds only the two Driver keys.
