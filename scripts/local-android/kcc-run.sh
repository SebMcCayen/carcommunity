#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# kcc-run.sh — boot the Android emulator, (optionally) start the seeded
# Firebase emulators, build+install the KCC debug APK, and launch the app.
#
# Requires the local Android toolchain at ~/android-toolchain (env.sh) and,
# for --fb, firebase-tools + JDK 21. See README.md in this directory.
#
# Usage:
#   ./kcc-run.sh              # emulator + build/install + launch (prod Firebase)
#   ./kcc-run.sh --fb         # ALSO start Firebase emulators, seed Sven, and
#                             #   build with -PuseFirebaseEmulator=true so the app
#                             #   talks to the local emulators + shows dev sign-in
#   ./kcc-run.sh --fb --no-build   # skip gradle, just reinstall the existing APK
# ---------------------------------------------------------------------------
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ANDROID="$REPO_ROOT/apps/android"
AVD="${KCC_AVD:-kcc_test}"
APPID="com.kungsbackacarcommunity.app"
JDK21="${JDK21_HOME:-/home/sebmccayen/android-toolchain/jdk-21.0.11+10}"

WITH_FB=0; DO_BUILD=1
for a in "$@"; do
  case "$a" in
    --fb) WITH_FB=1;;
    --no-build) DO_BUILD=0;;
  esac
done

# gradle + android tools use the toolchain JDK (17); firebase-tools needs JDK 21.
source ~/android-toolchain/env.sh
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# --- 1. boot the Android emulator if not already online ---
if ! adb devices | grep -q "emulator-5554[[:space:]]*device"; then
  echo ">> booting emulator $AVD ..."
  setsid emulator -avd "$AVD" -no-window -no-audio -no-boot-anim \
      -gpu swiftshader_indirect -no-snapshot > /tmp/kcc-emulator.log 2>&1 < /dev/null &
  disown || true
  adb wait-for-device
  echo ">> waiting for boot ..."
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
fi
echo ">> emulator online (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"

# --- 2. optional: Firebase emulators + seeded Sven Svensson ---
GRADLE_FLAG=()
if [ "$WITH_FB" = "1" ]; then
  if ! (ss -ltn 2>/dev/null | grep -q ':9099'); then
    echo ">> starting Firebase emulators (auth+firestore) ..."
    ( export JAVA_HOME="$JDK21"
      export PATH="$JAVA_HOME/bin:$HOME/.npm-global/bin:$PATH"
      cd "$REPO_ROOT"
      setsid firebase emulators:start --only auth,firestore \
        --project kungsbacka-car-community > /tmp/kcc-firebase.log 2>&1 < /dev/null & )
    until ss -ltn 2>/dev/null | grep -q ':9099'; do sleep 2; done
  fi
  echo ">> seeding Sven Svensson ..."
  node "$REPO_ROOT/scripts/local-android/seed-sven.js"
  GRADLE_FLAG=(-PuseFirebaseEmulator=true)
  echo ">> Firebase emulators up; app will use them (Sven: sven.svensson@example.com / Test1234!)"
fi

# --- 3. build + install the debug APK ---
APK="$ANDROID/app/build/outputs/apk/debug/app-debug.apk"
if [ "$DO_BUILD" = "1" ]; then
  echo ">> building debug APK ${GRADLE_FLAG[*]:-} ..."
  ( cd "$ANDROID" && ./gradlew --offline :app:assembleDebug "${GRADLE_FLAG[@]}" )
fi
echo ">> installing $APK"
adb install -r "$APK"

# --- 4. launch ---
echo ">> launching $APPID"
adb shell monkey -p "$APPID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 4
OUT="/tmp/kcc-screen.png"
adb exec-out screencap -p > "$OUT" 2>/dev/null || true
echo ">> done. screenshot: $OUT"
if [ "$WITH_FB" = "1" ]; then
  echo ">> tap 'Dev sign-in (Sven — emulator)' on the sign-in screen to log in."
fi
