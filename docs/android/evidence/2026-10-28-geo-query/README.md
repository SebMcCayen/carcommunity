# Android geo-query handoff verification — 2026-10-28

## Recorded checks

| Check | Result |
| --- | --- |
| JVM suite (`:app:testDebugUnitTest`) | 3,932 tests; zero failures/errors; one existing skipped test |
| Debug app and instrumentation APKs | Build successful |
| `:app:lintDebug`, including final instrumentation helper | Successful |
| Receiving flow before adding the capability metadata | 7/7 native tests successful |
| Initial native run with actual Driver handoff | 13/13 successful: 8 `GeoQueryHandoffTest` + 5 existing `NavigationSearchScreenTest` |
| Save-editor follow-up and actual Driver handoff | 2/2 targeted native tests successful; the new regression is the ninth `GeoQueryHandoffTest` |

The initial native output is preserved in [native-tests.txt](native-tests.txt).
The save-editor follow-up output is in [save-editor-follow-up.txt](save-editor-follow-up.txt).
The added regression verifies that a new external query dismisses the previous
save/edit dialog and that opening the editor again acts on the newly chosen place.
The application capability was added only after the receiving flow passed the
seven tests without that metadata. The final run also reads the installed
application metadata and requires the boolean to be true.

Environment: Android 14 / API 34, x86_64 emulator, physical display 1080 × 2400,
English system locale. The queries explicitly exercise Swedish Unicode and
spaces. No physical-device acceptance is claimed.

## Actual Driver sender and controlled receiving services

The sender was built from Driver commit
`d5c71f9d30080a20dd297b1db4add819af49d882`, using its current `lib/main.dart`
fixture entry with `DRIVER_FIREBASE_ENABLED=false` and the normal Android
`MainActivity`/`driver/event_location` channel. No Driver source changes were
needed. The test launches that installed app, taps **Träffar → Visa eventets
plats** in its real accessibility tree, and requires CarCommunity to receive
**Kungsbacka torg** as a prefilled query with both suggestions still unselected.
This exercises Driver's application-metadata lookup and package-targeted URI.
It does not substitute a direct URI in the Driver-enabled test branch.

CarCommunity instrumentation runs real `MainActivity.onCreate/onNewIntent` and
`AppRoot`/`AuthenticatedApp`, with local auth/search seams and `StubMapSurface`.
Assertions cover cold launch through sign-in, warm delivery, repeating the same
query, coordinate previews/labels, pending text and coordinates through Activity
restoration after an unrelated Intent, no replay of consumed Intents, single and
multiple matches, empty results, network errors and recovery. Selecting a row
must put that row's coordinates into the map overlay.

The map placeholder in the screenshots is intentional. These checks verify URI
routing, search UI and map-overlay commands, not live Mapbox tiles/geocoder data
or real Google authentication. Neither app uses production Firebase data for
this verification.

## Screenshots (inspected)

| State | Capture |
| --- | --- |
| Actual Driver handoff, two selectable results | [external-query-choices.png](external-query-choices.png) |
| Swedish query `Åsa torg & café`, two selectable results | [swedish-query-choices.png](swedish-query-choices.png) |
| Successful search without matches, text retained | [empty-results.png](empty-results.png) |
| Search transport failure, text retained and distinct error | [network-error.png](network-error.png) |

## Reproduce

Build and install a config-less CarCommunity debug app and its Android test APK.
Install Driver's current debug fixture:

```sh
flutter build apk --debug --target=lib/main.dart \
  --target-platform=android-x64 --dart-define=DRIVER_FIREBASE_ENABLED=false
```

Then run on the selected emulator:

```sh
adb -s "$SERIAL" shell am instrument -w \
  -e class com.kungsbackacarcommunity.app.navigation.GeoQueryHandoffTest,com.kungsbackacarcommunity.app.navigation.NavigationSearchScreenTest \
  -e verifyDriverHandoff true -e captureGeoEvidence true \
  com.kungsbackacarcommunity.app.test/androidx.test.runner.AndroidJUnitRunner
```

Require `OK (14 tests)`; an `am instrument` shell exit code alone is insufficient.
Normal CI omits `verifyDriverHandoff`: the receiver test then sends an Android
`ACTION_VIEW` directly, while still testing the metadata in a separate case.
