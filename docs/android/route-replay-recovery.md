# Android saved-drive route recovery

Scope: S54 drive detail and its replay reader. Android-only maintenance at the
owner's explicit request; iOS, Firebase, Storage rules, subscription policy,
Play, versioning and release artifacts are outside this patch.

## Root cause and data contract

The former message "The route map isn't available for this drive" was the
single outcome for all callable, download and decode failures. It could not
establish whether GPS was absent, access was refused, signing failed, or a
network request failed. The detail screen also ignored `SavedDrive.routeThumbnail`
on this path and offered no retry while a new drive's upload was finishing.
Its state was keyed only by ride ID, allowing a prior owner's/load's UI state to
survive a reader or owner replacement. The repository returned cached points
before calling the server again, skipping current authorization on repeat opens.

The audited production pipeline is already connected:

1. `AuthenticatedApp` creates `RouteUploadRunner(FirebaseMediaUploader)` and
   passes it to `SingleSessionRecording.start`. The holder supplies the
   per-account process scope and GPS fixes to `DriveRecordingCoordinator`.
2. `functions/src/drives/saveDrive.ts` computes authoritative statistics and a
   simplified encoded polyline (~64 points), creates the drive document, and
   returns `routePath`. It does **not** store the full route bytes.
3. The coordinator snapshots recorded fixes before releasing the recorder and
   launches `RouteUploadRunner` against that returned path, including after an
   early Keep. The runner encodes CCRB v1 + gzip and retries three times.
   Normal holder clear preserves upload scope; account changes cancel it.
4. `DrivesRoute` supplies `FirebaseRouteReplayRepository` and owner UID to
   `SavedDriveDetailScreen`. The reader calls `drives-routeUrl({rideId})`, never
   guesses a Storage path. The callable checks auth, stored account restrictions,
   ride ownership and the server's tier history window. It uses the stored path,
   or the canonical path for older documents, and signs a short-lived URL.
5. Replay downloads bounded bytes and decodes the same CCRB format used by the
   uploader. The existing codec accepts raw/gzipped data and rejects malformed,
   truncated, off-globe and excessive inflated payloads.

The parent is separately correcting the Android 12 paired FINE+COARSE request
and the publisher/location recovery effect keys. Those upstream capture paths
can explain a recording with no fixes, and are intentionally not edited here.
A replay repair cannot recover coordinates that were never recorded.

No AuthenticatedApp wiring change is required by this replay patch. Its existing runner injection and
`SingleSessionRecording.clearIfNotOwnedBy` auth teardown must remain intact when
the parent integrates the disjoint shell work.

This is source-contract evidence, not proof of a specific real drive's Storage
contents or deployed IAM state. No production records, files or logs were read
or written for this patch.

## Resulting behavior

- Full valid fixes still drive the interactive map and timestamp-based summary.
- A missing file is distinct from auth/access denial, missing/not-owned drive,
  temporary callable/signing failure, download failure and corrupt bytes.
- A visible drive's stored thumbnail may provide a clearly labeled, simplified
  overview when the full map cannot display. It never becomes a fabricated
  timestamped route or supplies speed/distance statistics.
- Fresh auth/access/not-found denial suppresses the thumbnail as well.
- Retryable failures expose Try again. Every retry obtains a new signed URL,
  including after an expired/403 download; missing files can be retried while
  a newly saved drive's background upload is still in progress.
- Each open rechecks backend access. Replay checks the current UID before the
  callable, before download, and after download. It does not retain a cross-open
  private route cache. Owner, ride and reader changes reset UI state and cancel
  the old effect; navigation away cancels loading. Ordinary recomposition does
  not reload the route.
- Swedish and English Android recovery strings are in separate, non-generated
  `drive_replay_strings.xml` files. Shared generated catalogs are untouched to
  honor the Android-only scope and avoid an iOS catalog regeneration requirement.

## Verification

Targeted Android JVM tests cover real coordinator auto-save/early-Keep → upload
→ signed-URL-reader → decode, existing upload retries and account-scope lifetime,
callable failure classification, auth switches, uncached authorization rechecks,
fresh signed URLs, corrupt payloads, cancellation and bounded loopback HTTP.
Shared Robolectric/native Compose tests exercise the actual S54 screen: missing full route
with/without a thumbnail, corrupt thumbnail, denial, retry/loading, distinguishable
errors, owner/drive/reader replacement, navigation cancellation and re-entry.

The local Gradle run was interrupted (SIGINT, exit 130) at the parent's request
because concurrent builds exhausted the shared 16 GB host. Compilation had not
completed. None of these JVM/native tests is claimed passing in this worktree;
parent validation is pending. No further Gradle build was started.

Parent validation command (from `apps/android`):

```sh
source /home/sebmccayen/android-toolchain/env.sh
./gradlew :app:testDebugUnitTest --tests '*drives.*' --max-workers=2 -Pkotlin.daemon.jvmargs=-Xmx3g
```

The root npm lint/typecheck/test/build checks require the separate Node workspace
dependencies (eslint/tsc/vitest/vite). They are not installed in this assigned
worktree; attempts fail with missing executables. No dependency files changed.

## Remaining limitations

- Old missing GPS cannot be regenerated. A thumbnail is only the simplified
  geometry already returned by authorized History reads.
- Uploads are still best-effort and process-scoped, with bounded retries. A
  process kill after save or an exhausted upload can leave a drive document and
  thumbnail without `route.bin`; this patch does not invent a durable upload
  spool, add automatic history retention, or overwrite old real-user data.
- The callable currently distinguishes missing-file and signing preconditions
  by their public message, without structured details. Android matches only
  the exact missing-file message; unknown preconditions safely remain retryable
  service failures. Do not infer missing GPS from a generic precondition.
- V4 signing requires the runtime service account's signBlob permission, as
  documented in `functions/src/drives/routeUrl.ts`. A production signing/IAM
  problem remains an operator configuration task; no IAM/deployment change was
  attempted. There is no direct-Storage bypass on failure.
- The shared native class for parent-coordinated verification is
  `com.kungsbackacarcommunity.app.drives.SavedDriveRouteReplayScreenTest`.
  No emulator install/run or viewport change was performed here.
- Native Mapbox GL, a configured authenticated device, real Storage upload and
  deployed callable signing still require parent/device validation. JVM tests
  do not establish release or production readiness.
