# Android text-based map links

CarCommunity opens a text-only map URI such as
`geo:0,0?q=Kungsbacka%20torg` in the existing map search. The decoded name is
prefilled, suggestions appear, and the member selects the intended place.
Neither one result nor several results are selected automatically.

Coordinate links retain their existing destination preview, including a decoded
label. Both `geo:` and `google.navigation:` use `GeoUriParser`; only incoming
`ACTION_VIEW` intents are accepted. No second geocoder or external navigation
launch is introduced by receiving a link.

## Delivery and lifecycle

- `MainActivity` publishes both `GeoUriTarget.Point` and `GeoUriTarget.Query` on
  cold launch and `onNewIntent`. Newer unhandled links replace older ones.
- `MapLinkNavigator` retains the target while sign-in, onboarding or the startup
  gate prevents the main shell from composing. Explicit sign-out clears it.
- Only a resumed authenticated shell consumes the target. This matters when an
  external app opens another Activity/task while an older shell remains stopped.
- Unconsumed target state is saved for Activity restoration, independently of
  later launcher/notification Intents. The original launch
  intent is not replayed after its target has been consumed.
- The search uses an explicit request ID, so opening the same text link again
  replaces an existing preview and shows choices again. Old navigation, saved-place
  edit and convoy-destination context cannot silently select the incoming place.

## Empty results and errors

A successful empty result shows the existing localized no-results hint. Transport,
HTTP and malformed-response failures show the existing search-error hint instead.
The query remains editable in either case. No token means the established
config-less no-network fallback; it is not treated as a production network error.

## Driver capability contract

Driver reads the boolean application metadata
`com.kungsbackacarcommunity.app.SUPPORTS_GEO_QUERY` before handing off a text-only
place. Its location button sends a package-targeted `ACTION_VIEW` with a
`Uri.encode`-encoded `geo:0,0?q=...` value. Coordinate handoff is independent of
this capability.

The capability must only be advertised after the receiving search flow works.
Do not move it to activity metadata or advertise it in an implementation that
only handles coordinate targets.

## Verification

`GeoUriParserTest`, `MapLinkNavigatorTest` and `HttpMapboxSearchClientTest` cover
Swedish Unicode/escaping, coordinate precedence and labels, retained/consumed
targets, HTTP/transport/schema errors and genuine empty results.

`GeoQueryHandoffTest` exercises real `MainActivity` Intent delivery and Activity
recreation. Instrumentation injects local auth/search repositories into the real
`AppRoot`/`AuthenticatedApp` flow; it does not use production accounts or data.
It covers cold launch through sign-in, explicit selection among multiple matches,
an unambiguous single match, warm query/coordinate replacement, identical repeated
links, empty results, network recovery, pending text/coordinates after an
unrelated Intent, and no replay of consumed launch intents.

The normal CI case delivers an external URI directly. To additionally verify the
actual Driver location button, install a current Driver Android fixture built from
`lib/main.dart` with `DRIVER_FIREBASE_ENABLED=false`, then run:

```sh
adb -s "$SERIAL" shell am instrument -w \
  -e class com.kungsbackacarcommunity.app.navigation.GeoQueryHandoffTest \
  -e verifyDriverHandoff true -e captureGeoEvidence true \
  com.kungsbackacarcommunity.app.test/androidx.test.runner.AndroidJUnitRunner
```

That case opens Driver, taps **Träffar → Visa eventets plats**, then requires the
prefilled CarCommunity search and both selectable results. Other cases exercise
the receiving URI without Driver. Screenshots are written beneath the target
app's external-files `geo-query` directory when capture is requested.

This verifies the real sender, metadata and Android handoff with deterministic
local search/auth seams. It does not claim a production Google sign-in, live
Mapbox data, or physical-device acceptance test.

Recorded local checks and inspected screenshots are in the
[2026-10-28 verification record](../android/evidence/2026-10-28-geo-query/README.md).
