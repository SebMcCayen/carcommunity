# Android club locality picker — 2026-10-28

Actual Android 35 emulator screenshots, 360 dp phone, using the packaged SCB
catalog and production ClubEditor composable. These are local form fixtures;
Save collects the payload in the test and never calls Firebase or creates a club.
The normal and 1.3 font-scale runs both passed on the final source after list
text was changed to semantic onSurface/onSurfaceVariant colours for readability.

- [Locality search with keyboard](search-keyboard.png)
- [Selected county and locality](selected-location.png)
- [Locality search at 1.3 font scale](search-large-font.png)

Validation:

- Pinned public-source importer `--check`: reproducible 21 counties, 2,017 current
  statistical localities, 2,030 county memberships and 32 documented merged names.
- Targeted JVM/Robolectric: **12 tests, zero failures/errors/skips** (7 catalog,
  5 editor). Covers bundled asset integrity, cross-county places, duplicate names,
  Swedish Unicode/diacritic search, county reset/stale selection, string-only
  payloads, legacy preservation, catalog error/retry, busy state and server errors.
- `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`: passed on final source.
  Lint reported zero errors; no lint findings in the new ClubLocation files.
- Native `ClubLocationPickerInteractionTest`: **1/1 passed at each of font scales
  1.0 and 1.3**. Exercises real dialogs/IME, disabled pre-county locality selection,
  Åsa search with the keyboard visible, county change/reset, no-results handling,
  Kungsbacka selection and the submitted `Kungsbacka, Hallands län` payload.
- Independent read-only review found an API-29-only screenshot path in the native
  test; an API 26–28 app-private fallback was added before final compilation.

The screenshots are English-locale fixture captures; Swedish labels are generated
from the same contract. API 26–28 execution, physical devices, TalkBack and real
club creation/server/account permissions were not exercised in these local runs.
The existing backend authorization and location schema are unchanged.
