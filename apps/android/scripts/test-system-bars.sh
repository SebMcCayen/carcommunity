#!/usr/bin/env bash
# Run from apps/android on one disposable emulator after both debug APKs compile.
# Keeping the script in a file avoids emulator-runner splitting shell quotations.
set -euo pipefail

serial=$(adb get-serialno)
case "$serial" in
  emulator-*) ;;
  *) echo 'System-bar acceptance requires a disposable Android emulator.' >&2; exit 1 ;;
esac
if [ "$(adb devices | awk '$2 == "device" {count++} END {print count+0}')" -ne 1 ]; then
  echo 'System-bar acceptance requires exactly one connected device.' >&2
  exit 1
fi

adb shell wm dismiss-keyguard
adb shell cmd overlay enable --user 0 com.android.internal.display.cutout.emulation.corner
for orientation in portrait landscape; do
  for mode in gesture threebutton; do
    if [ "$mode" = gesture ]; then
      overlay=com.android.internal.systemui.navbar.gestural
    else
      overlay=com.android.internal.systemui.navbar.threebutton
    fi
    adb shell cmd overlay enable-exclusive --user 0 --category "$overlay"
    test_status=0
    # The test host requests the real orientation before composing content and
    # asserts that orientation, cutout and navigation mode were actually applied.
    ./gradlew --no-daemon connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=com.kungsbackacarcommunity.app.design.SystemBarsNativeTest \
      -Pandroid.testInstrumentationRunnerArguments.navigationMode="$mode" \
      -Pandroid.testInstrumentationRunnerArguments.orientation="$orientation" \
      -Pandroid.testInstrumentationRunnerArguments.requireCutout=true || test_status=$?
    evidence="../../system-bars-evidence/$orientation/$mode"
    mkdir -p "$evidence"
    capture_status=0
    adb pull "/sdcard/Pictures/carcommunity-system-bars/$orientation-$mode" "$evidence/system-bars" || capture_status=$?
    cp -R app/build/outputs/androidTest-results "$evidence/" || true
    cp -R app/build/reports/androidTests "$evidence/" || true
    test "$test_status" -eq 0
    test "$capture_status" -eq 0
    # Missing screenshots must fail acceptance instead of silently uploading
    # reports only. Each successful combination captures nine real OS images.
    test "$(find "$evidence/system-bars" -type f -name '*.png' | wc -l)" -ge 9
  done
done
