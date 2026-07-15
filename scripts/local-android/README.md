# Local Android runtime + emulator sign-in

Run the KCC Android app on a local emulator and sign in as a seeded test user
**without production Firebase or Google Sign-In**, using the local Firebase
emulators. All emulator wiring is **debug-only and off by default** — see
"Release is unaffected" below.

## Prerequisites
- Android toolchain at `~/android-toolchain` (`source ~/android-toolchain/env.sh`)
  providing the SDK, `adb`, `emulator`, and JDK 17 for Gradle.
- An AVD named `kcc_test` (or set `KCC_AVD`). Create one with:
  ```bash
  sdkmanager "platform-tools" "emulator" "system-images;android-34;google_apis;x86_64"
  avdmanager create avd -n kcc_test -k "system-images;android-34;google_apis;x86_64" -d pixel_6
  ```
- For the Firebase emulators: `firebase-tools` on PATH and **JDK 21** (set
  `JDK21_HOME` or `JAVA21_HOME` if it isn't auto-discovered; `kcc-run.sh` tries
  `java_home`, `update-alternatives`, and common install roots)
  (firebase-tools rejects JDK < 21). `functions/node_modules` installed
  (provides `firebase-admin` for the seed script).

### Platform note for `--fb`
`kcc-run.sh --fb` has been tested on **Linux**. It uses a couple of host
utilities with built-in fallbacks so it also works elsewhere (e.g. macOS):
- **Process daemonization**: `setsid` if present, otherwise `nohup`.
- **Port detection**: `ss`, else `lsof`, else `netstat` — at least one must be
  installed (the script fails fast with a clear message if none is found).
- **JDK 21 discovery**: `JDK21_HOME`/`JAVA21_HOME`, else `/usr/libexec/java_home`
  (macOS), `update-alternatives` (Debian/Ubuntu), or common install roots.
Plain `kcc-run.sh` (no `--fb`) only needs the Android toolchain + `adb`.

## One command
```bash
scripts/local-android/kcc-run.sh --fb
```
This boots the Android emulator, starts the Auth+Firestore emulators, seeds
**Sven Svensson**, builds the debug APK with `-PuseFirebaseEmulator=true`,
installs it, and launches the app. Then tap **"Dev sign-in (Sven — emulator)"**.

Plain `kcc-run.sh` (no `--fb`) builds a normal debug build that talks to
**production** Firebase and shows only Google Sign-In.

## What the emulator flag does
`-PuseFirebaseEmulator=true` sets `BuildConfig.USE_FIREBASE_EMULATOR = true`
in the **debug** build. Guarded by `BuildConfig.DEBUG && USE_FIREBASE_EMULATOR`:
- `KccApplication` points Firebase **Auth → 10.0.2.2:9099** and
  **Firestore → 10.0.2.2:8080** (the Android-emulator alias for the host).
- The sign-in screen shows a **"Dev sign-in (Sven — emulator)"** button that
  signs in with email/password against the Auth emulator.

Only Auth + Firestore are wired (that is what the seed covers). Other Firebase
services (Functions/Storage/RTDB/Messaging) still point at prod, so features
that need them won't work in this local mode — Home/profile do.

## Seeded user
| field | value |
|-------|-------|
| uid | `sven-svensson-test` |
| email | `sven.svensson@example.com` |
| password | `Test1234!` |
| `users/{uid}` | role `user`, activeMember `true`, suspended/deleted `false`, onboarding complete |
| custom claim | `activeMember: true` |

Re-seed at any time while the emulators run (works from any cwd):
```bash
node scripts/local-android/seed-sven.js
```
Emulator data is in-memory; re-run the seed after restarting the emulators
(or start them with `--import`/`--export-on-exit <dir>` to persist).

## Release is unaffected
- The `release` buildType hardcodes `USE_FIREBASE_EMULATOR = false`, and
  `BuildConfig.DEBUG` is false in release, so both the emulator wiring and the
  dev sign-in button are compiled out / never reachable.
- Google Sign-In remains the sole production auth path.
- A normal debug build (no `-PuseFirebaseEmulator`) also stays on prod.
