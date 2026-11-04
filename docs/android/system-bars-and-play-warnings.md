# System bars and Android 15+ Play recommendations

## Source of the report

The signed Android 1.1.11 (66) bundle from build 41667540736 already calls
`enableEdgeToEdge()` and targets API 36. The Play recommendation is not evidence
that edge-to-edge is absent. Its insets and bar contrast still need device tests.

The mapping embedded in that exact AAB resolves the reported names:

| Reported caller | Source |
| --- | --- |
| `c.r.b` | AndroidX `EdgeToEdgeApi26.setUp` |
| `c.u.b` | AndroidX `EdgeToEdgeApi29.setUp` |
| `c.w.b` | AndroidX `EdgeToEdgeApi35.setUp` |
| `c.s.s` | AndroidX API-28 outline used for the pre-30 cutout backport |
| `df.t0.invoke` | R8-merged lambdas, including MainActivity's theme effect |

The app-owned theme effect wrote `Window.navigationBarColor` unconditionally.
That setter cannot supply a gesture-navigation background on Android 15+.
`applyKccSystemBars` now supplies explicit light/dark `SystemBarStyle` values to
the existing AndroidX `enableEdgeToEdge` helper. AndroidX owns transparent status
bars, the 25% theme-surface navigation protection, icon contrast and cutout policy.
The existing signed-out forced-dark and signed-in app-theme selection is retained.
Reapplying the style updates the protection rather than accumulating layers.

The AndroidX compatibility implementation itself contains the listed deprecated
calls, including transparent-color setup in its API-35 implementation. Removing
our setter does not guarantee Play will stop listing all library references.
Do not remove compatibility code, hide it with keep-rule tricks or disable
edge-to-edge to silence a static recommendation. Check the next uploaded bundle;
the existing Play artifact is immutable.

## Verification

`SystemBarsNativeTest` exercises real system bars with repeated light/dark
switches, navigation icon contrast, cutout insets, production map-sheet/footer
controls, and the actual chat keyboard/draft flow. Content comes from local
fixtures with no Firebase account or production writes.

The normal native suite provides the Android 14 compatibility gate. The focused
`Android system bars` workflow adds Android 15 (API 35) and Android 16 (API 36),
each in portrait and landscape, with gesture and three-button navigation and an
emulated display cutout.
The tests assert that the requested navigation mode and cutout are actually active.
Screenshots and test reports are retained as `system-bars-api-35/36` artifacts.
Record actual run IDs and results below after execution; source inspection alone
is not device acceptance or confirmation that Play has rescanned a new bundle.

## Primary references

- [Android 15 edge-to-edge behavior](https://developer.android.com/about/versions/15/behavior-changes-15#edge-to-edge)
- [AndroidX EdgeToEdge implementation](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/activity/activity/src/main/java/androidx/activity/EdgeToEdge.kt)
- [Compose window insets](https://developer.android.com/develop/ui/compose/system/insets)

### Local Android 14 compatibility check (2026-11-04)

The AGP 9.1.1 debug app and test APK compiled successfully. The three focused
native tests passed on a disposable API-34 emulator (3 tests, no failures).
The map fixture uses a stub map with production controls/sheets; this verifies
window/layout behavior, not remote map tiles. The first fixture run exposed a
wrong selector for the merged selected-tab semantics; the corrected test uses
the actual labelled clickable tab and checks the open sheet above the footer.
Landscape navigation-mode detection checks side insets as well as the bottom.
Android 15/16 results remain pending in CI.
