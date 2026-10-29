# Android club creation: county and locality

New clubs require a county followed by a locality. Choices come from the bundled
`apps/android/app/src/main/assets/clubs/scb-localities-2023.tsv`; opening, searching,
and selecting places never calls an API. The asset is 73,883 bytes of UTF-8 text.

## Primary sources and reproducibility

Retrieved 2026-10-28 from SCB. These are statistical **tätorter**, not Sweden's 290
municipalities and not an exhaustive list of every village, address or named place.
SCB defines a statistical locality as continuous built-up area with at least 200
registered residents. The complete 2023 snapshot is retained.

1. [SCB: Statistiska tätorter 2023, befolkning och landareal per tätort och kommun](https://www.scb.se/hitta-statistik/statistik-efter-amne/boende-bebyggelse-och-mark/bebyggelseomraden/tatorter-och-smaorter/pong/tabell-och-diagram/statistiska-tatorter-2023-befolkning-och-landareal-per-tatort-och-kommun/)
   - Excel download, published 2024-11-28; its header records a 2025-11-24 update to
     locality designations decided by Lantmäteriet.
   - SHA-256: `d743b0df767ee267e715545e4809c0ea51c5102d56af12088183a9610e6793e9`
   - 2,144 locality/municipality rows, 2,017 distinct locality codes, 2,030 distinct
     county/locality memberships. All 13 localities in several counties remain
     selectable in **each** listed county, even where a component has no population.
   - Municipalities determine county membership and distinguish duplicate labels
     such as the two Ersmark localities in Västerbotten. Municipality names never
     become selectable localities merely by appearing in this column.
2. [SCB: Avregistrerade och nyregistrerade statistiska tätorter och småorter 2020 till 2023, ändring av koder och beteckningar](https://www.scb.se/hitta-statistik/statistik-efter-amne/boende-bebyggelse-och-mark/bebyggelseomraden/tatorter-och-smaorter/pong/tabell-och-diagram/avregistrerade-och-nyregistrerade-statistiska-tatorter-och-smaorter-2020-till-2023-andring-av-koder-och-beteckningar/)
   - SHA-256: `c94b88291221f92ee1e58412c9af983d1dbc44cc1c8905e91f01f6f3ffd9242d`
   - Supplies the official names/codes of all 21 counties, and **all 32** former
     localities with reason `Av2_T` (merged into a larger locality). These named
     parts are additional selectable options, explicitly labelled as part of their
     current locality. They do not inflate the 2,017 current-locality count.
   - Kungsbacka (`1384TC117`) is one such name, now part of Göteborg (`1480TC108`).
     Its source row has the literal text `Tätort 2013: 1480TC108` alongside retirement
     year 2023 and the merge reason. The importer uses the documented successor
     code and requires that it exists in the current 2023 catalog in Halland; it
     does not silently change the source year or make Kungsbacka a municipality
     fallback. The picker shows `Kungsbacka` with `Ingår i Göteborg`, and submits
     `Kungsbacka, Hallands län`. Åsa (`1384TB126`) is a current locality in Halland.

[SCB's open-data terms](https://www.scb.se/vara-tjanster/oppna-data/) specify CC0.
The bundled catalog is a derived extraction of the above workbooks, not an official
SCB product or partnership. No third-party city list or geocoding service is used.

The ordinary API table
[TAB5357 metadata](https://statistikdatabasen.scb.se/api/v2/tables/TAB5357/metadata?lang=sv)
assigns cross-boundary localities to their predominant county/municipality and also
contains historical localities. It alone cannot meet the membership requirement.
The municipal breakdown workbook is therefore the membership authority.

Rebuild or verify from the repository root using Python 3's standard library:

```sh
python3 scripts/import-club-localities.py
python3 scripts/import-club-localities.py --check
```

Each command makes two bounded public downloads (45-second timeout per download,
2 MB input limit), verifies the pinned SHA-256 digests, reads OOXML with bounded
uncompressed size, validates full counts, names, memberships, successor links and
all wire string lengths, then deterministically writes/checks the asset. Changed
upstream files fail before a write and require an explicit source review and
repin. No authentication or paid service is required.

For an offline rebuild, save the original two downloads as `tatorter-2023.xlsx`
and `changes-2023.xlsx` in a directory, then pass `--source-dir /absolute/path`.
The same digests and validation apply. Original workbooks are not app resources.

TSV schema (no header row except the format marker):

```text
# kcc-club-localities-v1; ...
C<TAB>countyCode<TAB>official county name
L<TAB>countyCode<TAB>current localityCode<TAB>locality name<TAB>municipality names
A<TAB>countyCode<TAB>former localityCode<TAB>former locality name<TAB>municipality name<TAB>current successorCode
```

County/locality pairs are the lookup keys. The first two digits of a locality code
indicate its primary municipality's county, **not all of its memberships**; never
filter exclusively by that prefix. The parser validates duplicate keys and alias
targets, sorts by Swedish collation, and rejects a partial/corrupt catalog.

## UI and backend compatibility

- A county change clears the locality. Selecting the same county keeps it. A
  locality callback from a different county is rejected, including a stale callback
  for a locality present in both counties.
- Locality search stays inside the selected county, matches name and municipality,
  folds case/diacritics, and splits whitespace. A lazy list handles large counties;
  municipality labels distinguish equal locality names. Search text is never a
  selectable fallback.
- Only a verified selected pair enables creation. The existing callable receives
  `location: "Ort, Län"` of 2–80 characters. No codes, extra DTO fields, cloud or
  backend schema changes are introduced.
- Catalog loading/failure disables creation; failure has a local retry. Inputs,
  picker controls, dismissal and saving are disabled while saving. Server errors
  are shown inside the editor, leaving form values and selections intact.
- Existing club editing intentionally keeps its editable location string. It does
  not guess a county, preselect a possible match or rewrite ambiguous legacy names
  on an unrelated name/description/visibility edit. New creation has no free-text
  location input.
- Driver sources, backend contracts and app/shell wiring are unchanged. The seven
  new labels are generated from the shared Swedish/English localization contracts;
  the generated iOS string catalog is synchronized without iOS feature changes.

## Localization

The following labels are included in the shared localization source and generated
Android resources. Regenerate both platform catalogs after changes to these keys.

| Key | Swedish | English |
| --- | --- | --- |
| `clubs_county` | Län | County |
| `clubs_chooseCounty` | Välj län | Choose county |
| `clubs_chooseCountyFirst` | Välj län först | Choose a county first |
| `clubs_chooseLocality` | Välj ort | Choose locality |
| `clubs_localityPartOf` | Ingår i %1$s | Part of %1$s |
| `clubs_locationLoading` | Läser in orter… | Loading localities… |
| `clubs_locationCatalogError` | Det gick inte att läsa in orterna. Försök igen. | Could not load localities. Try again. |

Existing keys reused: `clubs_location`, `clubs_invalidForm`, `clubs_error` and its
existing error mappings, `clubs_retry`, `clubs_cancel`, `garage_pickerSearch`,
`addressSearch_noResults` and existing form labels/actions. There is no hardcoded
Swedish UI fallback; proper names come from the catalog.

## Verification and remaining checks

Changed paths:

- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/clubs/SharedClubRoute.kt`
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/clubs/ClubMemberContent.kt`
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/clubs/ClubLocationCatalog.kt`
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/clubs/ClubLocationPicker.kt`
- `apps/android/app/src/main/assets/clubs/scb-localities-2023.tsv`
- `apps/android/app/src/test/java/com/kungsbackacarcommunity/app/clubs/ClubLocationCatalogTest.kt`
- `apps/android/app/src/testDebug/java/com/kungsbackacarcommunity/app/clubs/ClubLocationPickerTest.kt`
- `scripts/import-club-localities.py`
- `apps/android/app/src/androidTest/java/com/kungsbackacarcommunity/app/clubs/ClubLocationPickerInteractionTest.kt`
- Swedish/English localization contracts and generated Android/iOS string resources
- `docs/features/club-location-catalog.md`

Added pure JVM coverage in `ClubLocationCatalogTest` for full coverage and
cross-county memberships; official Kungsbacka successor/Åsa; county reset and stale
callbacks; case/diacritic search and duplicates; every option's wire contract;
legacy preservation; and corrupt/partial catalogs.

Added resource-aware Compose/Robolectric coverage in `ClubLocationPickerTest` for
the packaged asset, selection/reset/search, wire payload, busy state, inline server
errors, catalog failure/recovery and legacy editing.

Public-source importer `--check`, offline reproducibility, asset integrity checks,
localization key parity and `git diff --check` passed. Targeted JVM/Robolectric
validation passed **12 tests, zero failures/errors/skips** (7 catalog, 5 editor).
Debug and instrumentation APKs compile. Lint passed with zero errors. Native form interactions passed on Android 35
at both normal and 1.3 font scale, including real dialogs and the visible keyboard.
See [emulator evidence](../android/evidence/2026-10-28-club-location/README.md)
for screenshots, exact coverage and the limits of local fixture validation.
