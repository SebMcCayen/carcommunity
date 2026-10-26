# Android settings lane, design pass 3

Branch: `codex/android-design3-settings`

Base: `345a9f11693374d1e52a88f3ed4c20c1bc67edbd`

This is a source and fixture handoff for MAIN's serialized verification. No
Gradle, emulator, device capture, network/cloud operation, dependency install,
push, PR, merge, release change or deployment was run in this lane.

## References and scope

Opened and inspected the existing `catalogue-20.jpg` through `catalogue-24.jpg`
reference sheets. Read the repository mobile instructions, design specification
and screen migration register. No catalogue image is used as a runtime asset.

Only S65, S71, S72, S75 and S76 have production presentation changes. Source
inspection of the other rows below is not a visual acceptance claim. In
particular, the full catalogue and the light/dark, Swedish/English, large-text,
keyboard, OS-permission and account-transition matrix remain unverified here.

| ID | Source outcome in this lane | Preserved behavior and remaining evidence |
| --- | --- | --- |
| S65 | Changed: native storefront tile, supplied address, offer-count badge | No company photo exists in the model. No fabricated company/photo/discount. Counts still require an exhaustive offer snapshot; loading/capped/error states retain their existing behavior. Directory/saved membership gate and paging unchanged. |
| S66 | Retained: detail, member sections and existing external actions | Member detail/code/bookmark gates, loading/error/retry and safe destinations unchanged. Reference hero photos have no source field. Native render and live code reveal remain unverified. |
| S67 | Retained: existing required application fields and IME-aware form | Category/contact validation, duplicate errors, busy/success states unchanged. Reference sample fields are not substituted for the real contract. Native visual/keyboard comparison pending. |
| S68 | Retained: source-aligned separate privacy-choice cards | Partner consent and leaderboard visibility remain independent; unresolved reads cannot save a default. No consent default or save callback changed. Native comparison pending. |
| S69 | Retained: grouped Settings rows and existing appearance/language choices | No speculative layout churn. Added availability/callback/scroll regression tests. Null dependency callbacks still hide destinations. Radio choices and actual version remain intact; reference comparison pending. |
| S70 | Retained: existing inbox cards/actions | Read/delete, friend-request response, live convoy/event navigation, stale/dead labels and timestamps unchanged. No invented unread counts or new filters. Native visual comparison pending. |
| S71 | Changed: compact title plus two channel columns when space permits | Existing stacked variant retained for narrow screens or font scale above 1.3. Independent channels, essential locked-on preferences, runtime push prompt and save status unchanged. Added large-text reachability/role test. |
| S72 | Changed: blocking explainer, native avatar fallback, localized block date | Missing names use the existing unknown-user label rather than a private UID. Unblock still requires its dialog and uses the original ID callback; working disables it. Small/large-text layouts put the action below the name. Live block transitions pending. |
| S73 | Retained: IME-aware feedback form and public-report warning | Actual 80/4000 backend bounds, failure retry, successful form clearing and support routing unchanged. No reference 100/500 limits introduced. Native visual comparison pending. |
| S74 | Retained: public ticket list and native interactions | Paging, +1/comment state, IME handling and safe GitHub navigation unchanged. No fabricated closed-ticket API/tab. Native visual comparison pending. |
| S75 | Changed: retention explanation and explicit acknowledgement checkbox | Existing destructive action still opens a second confirmation before the original delete callback. Busy state disables the checkbox and deletion. Existing 30-day/retention policy copy reused; no false claim that all records are deleted immediately. Auth/coordinator and failure/cancel callbacks unchanged. |
| S76 | Changed: connected native timeline with version headings and subordinate dates | Actual bundled release entries/order/loading/empty states and the once-per-version update dialog unchanged. No new release/version or sample release content in production. |
| S77 | Retained: bundled login assets and Google/native recovery | No invented people or account prefilling. Forced dark brand presentation, random quote, in-progress state, no-account recovery and native add-account guard unchanged. Exact reference footer/benefit composition is not verified. |
| S78 | Retained: native name/consent form | Three required consents, empty display-name default, optional partner choice and optional default-map settings remain intact. No fake avatar/upload/progress capability added. Native keyboard/visual comparison pending. |
| S79 | Retained: existing skippable four-step welcome flow | Finish/skip and membership/profile/add-car callbacks remain intact. The reference single-screen shortcuts are not claimed implemented by this lane. |
| S80 | Retained: actual Community/Plus/Supporter presentation and lifecycle | No reference planning prices added. Play still supplies the localized checkout price; product-query result does not expose formatted prices to this screen. Restore/change/manage/backend entitlement checks unchanged. Inline price plumbing and configured Play lifecycle verification remain outside this presentation patch. |
| S83 | Existing account guards retained; no standalone replacement introduced | No shared `AuthenticatedApp`/profile guard edits. Real restricted-account transitions and reference composition remain for MAIN/device validation. |
| S84 | Existing native location guard retained; no standalone replacement introduced | No permission request/remedy or map/session hosting edits. No permission grant can start a session through this patch. Real OS transitions remain for MAIN/device validation. |

## Tests and fixtures

New/extended Compose regressions cover Settings availability and exact callbacks,
real partner-address taps, independent notification channels, locked essential
channels, all-channel scrolling at 2x font scale, unknown blocked-user fallback,
unblock confirmation/callback/busy state, large-text name/action separation,
account acknowledgement semantics and both confirmation steps/cancel/busy state,
and release-heading/date/large-text scroll/empty states.

These Kotlin tests were added and source-reviewed, **not compiled or executed**.
MAIN should run the changed `testDebug` classes serially under its Android build
configuration. Existing partner tests continue to exercise paid/free member
detail and code restrictions; existing subscription tests exercise
restore/change/management. They were not rerun in this lane.

The test-APK-only entry point is `Pass3SettingsFixture(screen)` in
`com.kungsbackacarcommunity.app.redesign`. MAIN can call it from its screenshot
harness; the existing shared harness was not edited. MAIN owns the fixture
caption, viewport, theme, locale and screenshots.

Supported fixture keys:

- `S65`, `S65-saved-locked`
- `S66`, `S66-locked`
- `S67`
- `S68`, `S68-unresolved`
- `S69` (delegates to the existing local settings fixture)
- `S71` (granted push status; no real permission request needed)
- `S72`, `S72-working`
- `S75`, `S75-failed`, `S75-deleting`
- `S76`
- `S80`, `S80-plus`, `S80-supporter`, `S80-pending`

All sample names, addresses and release notes are confined to test sources.
Fixtures construct no repositories, Firebase clients, billing clients, network
calls or real mutations. Existing composable-local controls remain interactive;
navigation and mutation callbacks are inert.

## Executed checks

- Android and iOS string generators completed: 1,957 keys, both locales.
- Static comparison with the base: exactly five new keys per locale, restricted
  to `settings`, `blocking` and `whatsNew`; all prior values unchanged.
- Parsed generated Android XML and iOS JSON: all five additions present in both
  locales and Android text matches the canonical source.
- All string resource references in modified/new Kotlin resolve by resource name.
- Ownership check: no shared shell/map hosting, token, build, release-data or
  backend edits. iOS changes are generated strings only.
- `git diff --check` passed before the final commit.

These are static checks. No compilation, runtime test pass, screenshot fidelity,
Play purchase success or configured-device readiness is asserted.

## Changed paths

All paths are relative to this worktree root.

```text
apps/android/app/src/main/java/com/kungsbackacarcommunity/app/account/AccountDeletionScreen.kt
apps/android/app/src/main/java/com/kungsbackacarcommunity/app/blocking/BlockingScreen.kt
apps/android/app/src/main/java/com/kungsbackacarcommunity/app/notifications/NotificationSettingsScreen.kt
apps/android/app/src/main/java/com/kungsbackacarcommunity/app/partners/PartnersListScreen.kt
apps/android/app/src/main/java/com/kungsbackacarcommunity/app/whatsnew/WhatsNewScreen.kt
apps/android/app/src/main/res/values/strings.xml
apps/android/app/src/main/res/values-en/strings.xml
apps/android/app/src/testDebug/java/com/kungsbackacarcommunity/app/account/AccountDeletionScreenTest.kt
apps/android/app/src/testDebug/java/com/kungsbackacarcommunity/app/blocking/BlockingScreenTest.kt
apps/android/app/src/testDebug/java/com/kungsbackacarcommunity/app/notifications/NotificationSettingsRedesignTest.kt
apps/android/app/src/testDebug/java/com/kungsbackacarcommunity/app/partners/PartnersScreensTest.kt
apps/android/app/src/testDebug/java/com/kungsbackacarcommunity/app/shell/SettingsDestinationsTest.kt
apps/android/app/src/testDebug/java/com/kungsbackacarcommunity/app/whatsnew/WhatsNewScreenTest.kt
apps/android/app/src/androidTest/java/com/kungsbackacarcommunity/app/redesign/Pass3SettingsFixtures.kt
apps/android/app/src/androidTest/fixtures/settings-pass3/README.md
contracts/localization/sv.json
contracts/localization/en.json
apps/ios/KCC/Resources/Localizable.xcstrings
```
