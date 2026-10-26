# Android pass-3 delivery and evidence

2026-10-26. PR [#1189](https://github.com/SebMcCayen/carcommunity/pull/1189)
contains the map/Crown foundation; stacked PR
[#1190](https://github.com/SebMcCayen/carcommunity/pull/1190) contains the feature
screens, shared chrome and this evidence. No release/version bump, backend or
cloud deployment, account creation or Play publication is included.

## Provenance and scope

- Final Android source: `0e4959288f58c3094440e04d54c84f0832849e21`;
  Android subtree `96a4cd7f002277dab0f10589bfedcb249224df7b`.
- Main capture source: `9c8c5cdf3fc6ae0da23f78d65f4f74990f5f4d73`;
  subtree `ff7ba2ed6ca1f09687fa224deb97d8add8ae5ef6`. The final follow-up
  corrects only the convoy fixture's `showDestination=false` option to match
  production, removes its duplicate test caption and fixes a stale code comment.
  That convoy is recaptured/tested separately at the final revision.
- Reattached catalogue SHA-256 is unchanged:
  `6dcd5b8436e8beb81508e741566b0517986659f8a1c67474e09574b58206e76c`.
  Pages 4–24 and the brief's behavior/typography contracts are the reference.
- Android API 34, Google APIs x86_64, density 480 dpi. Normal: 1170 × 2532 px,
  390 × 844 dp, font scale 1.0. Narrow: 960 × 1920 px, 320 × 640 dp,
  font scale 1.5. Swedish/dark and English/light are separate capture runs.
- All people, vehicles, messages, events, balances and rosters are local test
  inputs. Page fixtures exercise production Compose components, not complete
  authenticated route journeys. Map-shell geometry fixtures use an explicit
  `StubMapSurface`. No Firebase/Play account or production mutations are used.

## User-reported items

| Item | Implementation | Executed evidence / practical limit |
| --- | --- | --- |
| Chat controls too large | Compact source selector and horizontally scrolling channel strip; Back remains a 48dp action. | Populated native chat captures; JVM layout/text/action tests. Real keyboard/TalkBack remain device gates. |
| Crown map appearance | Transparent rarity crown glyphs, thinner outlines/rings and a new marker cache identity. Existing tiers/claim states retained. | Four bitmap exports and transparency/rarity tests. Actual Mapbox crown visibility remains a configured-device gate. |
| Perks, saved picker, incident picker and start chooser cover navigation | Shared activity-window portal, measured body viewport, pinned close and scrolling content; footer taps dismiss panels. | Six map states exercised with actual OS-injected footer taps and bounds checks; 320dp/large-text end actions also run. |
| Cold focus distance | Synchronous stored browsing zoom seeds the first camera, with and without a cached fix. Changing zoom does not rebuild the map. | Pure/Robolectric preference recreation and native store/surface regression checks; first real GPS fix remains a device check. |
| S03 layers | Native traffic/incidents/3D/Crown rows, Day/Night/System, supported age filters and focus-distance disclosure. | Local native callbacks, appearance selection, scrolling and footer clearance. All seven supported age choices are retained rather than the sample's three. |
| S16 live/convoy dock | Live and convoy slots share the bottom dock; measured height clears rail/reaction overlays and persistent navigation. | Eight native regressions include a four-member convoy. Final production-slot convoy retest passes; normal and large-text captures are separate. |
| Crown Hunt statistics, perks and standings | Overview/statistics/history/shop organization, actual season board, existing inventory/activation/receipt and ledger facts. | Production-component captures and unit suite. Claims, balances, budgets and expiry still need real-account/device acceptance. |
| S41–S44 events | Date/location hierarchy, RSVP and secondary actions, grouped real editor fields, actual event title in chat. | Free/paid/creator/edit/saving/check-in/share/group-drive/chat fixture states and unit regressions. No new hero-photo or view-count field is fabricated. |
| Other Android surfaces | Shared native action radius/targets, real owner-avatar headers, saved-place filters/editor, responsive garage/profile/awards/drives and settings/partner/account presentation. | Full source/reference mapping plus native component captures. Dirty editors/crop/pickers opt out of alternate profile exits. |

## Executed checks

Final config-less `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug
:app:assembleDebugAndroidTest` passes in 3m35s: 3,705 tests, zero failures/errors
and one pre-existing skip. Before the final
fixture/comment-only follow-up, the eight dedicated API-34 tests passed in
46.893 s (`DockedMapShellInteractionTest` +
`SavedPlacesManagementInteractionTest`). The final production-slot convoy method
passes again in 3.544 s. The prior integrated 50-test shell run is recorded in
the index with its own revision; it is not silently relabelled as a final-head run.

Android strings/tokens and the iOS String Catalog regenerate without a diff.
iOS app validation and Navigation-SDK/R8 checks are separate CI jobs. Current-head
check state must be read on GitHub before merging; an earlier successful run is
not approval for a newer SHA.

## Rendered evidence and comparison

100 primary native state captures: 70 Swedish/dark normal, 15 English/light
normal and 15 Swedish/dark narrow/large. These are **state variants, not 100
distinct routes or acceptance of all 84 concepts**. The final convoy slot has
two additional captures. The test-only caption stays visible. A selected,
unedited [six-image packet](evidence/2026-10-26/README.md) is committed with
file/APK hashes; the remaining local artifacts are in the workspace's
`artifacts/android-design-pass3/` directory.

All 69 initial normal-state images were swept in eight overview contact sheets;
focused source/native comparison inputs cover S03, S07/S08 editor/list family,
S09, S16 dock geometry, S26, S30 conversation, S41–S44 and S57. The reference crop
and native capture are presented together at a common 390px width, preserving
aspect ratio. This is qualitative hierarchy/control review, not pixel
equivalence: sample content, native status bars, outer-shell context and available
data differ. S29's landing tiles must not be compared to a populated S30
conversation and called a pass. S08's captured edit variant is not pixel
acceptance of the illustrated create state. The initial S16 single-session
geometry comparison is followed by a four-member convoy fixture using the
production slot options.

Corrections from rendered inspection: compact layer/incident controls, settled
and clipped sheet composition, shared action corners, owner headers, one-row
event actions, readable incoming-friend identities, one-line large-text footer
labels and contrasting official-event text in light theme. The explanatory
device-local saved-place copy intentionally replaces the illustration's cloud
sync claim. Native Roboto typography follows the brief rather than its
illustrative serif font.

### Configured map evidence is limited

Two isolated configured captures (single session and four-member convoy) pass a
loaded-style/native-FULL-frame fence, 13.979 s and 14.718 s. A runtime public
Mapbox token is temporarily supplied in an ignored local file and removed before
the final config-less unit/lint build. No private download token is committed.
Actual base-map geometry is visible in those captures.

This is **not** complete map visual acceptance: the headless emulator captures
still have incomplete label/marker/compositor presentation. An earlier
multi-case configured batch timed out waiting for a FULL frame on its second
new map instance; it is retained as failed evidence, not counted green. Isolated
fixtures avoid that artificial repeated-MapView startup path; production retains
one map across shell navigation. Real crown annotations, permitted live avatars,
GPS/camera behavior, marker selection and panel composition must be checked on a
configured phone before a release. Bitmap tests prove glyph construction, not
live-map visibility.

## Remaining acceptance

Full catalogue/OS acceptance stays blocked in `design-qa.md`. In particular:

- Real authenticated navigation, account/block changes, photos and image failures,
  session/convoy changes, GPS/permissions and notification links on a phone.
- Actual Crown rewards/limits/duplicate handling, expiry and purchases; Play
  restore/change/cancel lifecycle. No billing rules are changed by this pass.
- Keyboard/insets with real input, TalkBack and affordable-device performance.
- Reference details unsupported by current contracts (event hero/view counts,
  sample convoy facts, new feed writes and cosmetic-frame purchases) remain
  explicit differences in the all-84 source register, not invented UI facts.
- S81 is iOS-owned on the Mac mini; S82's cosmetic store is separately deferred.

These gates do not invalidate the specific passed checks above. Conversely,
green compilation, unit tests or fixture captures do not close these gates.
