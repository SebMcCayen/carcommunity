# Club chat handoff

Workspace: `/home/sebmccayen/Documents/ChatGPT/Kungsbacka Car Community/android-design-pass3-9K8W7y/club-chat-design`

Branch: `codex/android-club-chat-design`; untouched base: `b109701`.

## Status and capture readiness

The deterministic source fixture is ready for the parent's build/emulator slot. No Gradle, Kotlin compilation, emulator, screenshots, security scan, commit, push or PR has been run by this worker. `git diff --check` passes. Runtime, screenshot fidelity and test outcomes remain unverified.

`ClubChatDesignCaptureTest.captureClubAndCommunity` runs real Compose components with the same four local messages, fixed timestamps, two other senders and the current sender. Its default is Swedish, dark mode, font scale 1.0, Europe/Stockholm timezone. It uses the device viewport and density; keep those identical between captures. A visible local-test-data caption is included. Neither fixture repository performs remote operations; avatar placeholders intentionally avoid network image reads.

Instrumentation argument `clubChatSurface` selects:

- `before`: frozen Club route from `b109701`.
- `community`: frozen Community content from `b109701`.
- `after`: working-tree Club route.
- `community-current`: working-tree Community content, to check the shared-component extraction against its frozen baseline.

Arguments `redesignTheme=light`, `redesignLocale=en`, and `clubChatFontScale=2.0` allow the corresponding stress cases. No locale resources or contracts were changed.

Both compiled baseline previews are test-only copies. After reversing only their package/import/function-name changes and removing their one-line marker, their full source text exactly matches `git show b109701:<original path>`; this was checked with Python. Original source SHA-256 values:

- Club: `e3313bd62b596975d035c04ef5b6a5e965186d1901a06b29c2934446d5ad371e`.
- Community: `d5cf61fd81416faab472a0d540cc80637b0ef120186259018616c0eafaa8dc45`.

The parent can therefore capture the current-run baseline from this working tree without reverting production code. No previous screenshot is asserted as current evidence. Inspect the newly generated screenshots before making a Product Design audit or fidelity claim. The snapshots are capture scaffolding and can be removed once the parent has accepted and saved baseline evidence.

## Production changes

- `src/main/java/com/kungsbackacarcommunity/app/clubs/ClubChatRoute.kt`: reuses Community's chronological message presentation, day separators, own/other bubbles, avatar view, composer and IME/navigation-bar handling. Keeps Club's fresh membership gate, lifecycle epoch, retained live page queries, cursor logic, drafts on failed send and operation IDs. Report/block/hide remain Club callables, accessible from bubble long-press. Own-message hide is moderator/owner only. Selected dialogs resolve IDs against the currently visible message list.
- `src/main/java/com/kungsbackacarcommunity/app/clubs/ClubChatPresentation.kt`: filters hidden senders, deduplicates/sorts rows and hydrates through `ChannelThread.hydrate`/`LiveProfiles` without changing Club DTOs or wire contracts.
- `src/main/java/com/kungsbackacarcommunity/app/chatchannels/ChannelChatContent.kt`: makes the existing message list internal/reusable and extracts its composer. Community's mention tracking, optimistic sends, reply gate, block/report policy and callbacks are retained.
- `src/main/java/com/kungsbackacarcommunity/app/chatchannels/ChannelChatComposer.kt`: controlled shared presentation; each route owns validation, send policy and draft acknowledgement.

Club uses `FirebaseLiveProfileRepository.sharedOrEmpty` for the real Firebase Club repository, with a test injection seam. Profile requests contain distinct currently visible senders only, including retained pages. Cosmetic failures fall back to stored identity and never stop the Club stream. Collectors are scoped inside the verified membership/lifecycle composition. Rendering uses the existing Storage-path avatar pipeline, without introducing external avatar URLs or profile writes.

Club quick-emoji sends preserve any typed draft, have a separate retry operation ID, and disable with the input while a send is in flight. Input, paging and send labels reuse existing Community resources. Tests now address the Club input/list tags instead of the old text-field label or an ambiguous generic scroll matcher.

No `AuthenticatedApp`, profile model, Club member UI, Firebase Club upload/attendance, garage, iOS, version, Play or production files were edited. Calendar-subscription work is outside this patch.

## Tests and fixtures

- `src/test/java/com/kungsbackacarcommunity/app/clubs/ClubChatPresentationTest.kt`: dedup/order, unchanged message identity/body/time, live identity/avatar replacement and explicit removal, fallback identity, blocked retained senders.
- `src/androidTest/java/com/kungsbackacarcommunity/app/clubs/ClubChatRevocationTest.kt`: existing revocation/background/history/paging coverage retained; adds send retry/draft/operation ID, quick emoji draft retention, distinct filtered profile reads and collector disposal, profile-failure message continuity, moderator own-message hide, incoming block, report identity/reason mapping and removed-selection dismissal.
- `src/androidTest/java/com/kungsbackacarcommunity/app/clubs/ClubChatDesignCaptureTest.kt`: deterministic comparison capture and local-only Club repository.
- `src/androidTest/java/com/kungsbackacarcommunity/app/clubs/baseline/ClubChatBeforePreview.kt`: frozen Club baseline.
- `src/androidTest/java/com/kungsbackacarcommunity/app/chatchannels/baseline/CommunityChatBeforePreview.kt`: frozen Community baseline.

## Parent-only run examples, after granting a slot

Use the existing Android toolchain/environment. Run sequentially from `apps/android`:

```bash
./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest \
  --tests 'com.kungsbackacarcommunity.app.clubs.ClubChatPresentationTest' \
  --tests 'com.kungsbackacarcommunity.app.clubs.ClubContractTest' \
  --tests 'com.kungsbackacarcommunity.app.chatchannels.ChannelChatContentTest' \
  --tests 'com.kungsbackacarcommunity.app.chatchannels.ChannelChatCoordinatorTest'
./gradlew --no-daemon --max-workers=1 :app:assembleDebug :app:assembleDebugAndroidTest
```

Install the resulting app/test APKs on the parent's existing emulator. With that emulator explicitly selected, a capture invocation is:

```bash
adb -s "$PARENT_ANDROID_SERIAL" shell am instrument -w \
  -e class com.kungsbackacarcommunity.app.clubs.ClubChatDesignCaptureTest \
  -e clubChatSurface before -e redesignTheme dark -e redesignLocale sv \
  -e clubChatFontScale 1.0 \
  com.kungsbackacarcommunity.app.test/androidx.test.runner.AndroidJUnitRunner
```

Repeat for `community`, `after`, `community-current`, and light mode using the same viewport, density and font scale. Capture files are written to the target app's external-files `club-chat-design` directory, normally `/sdcard/Android/data/com.kungsbackacarcommunity.app/files/club-chat-design/`, named `<surface>-<theme>-<language>-<fontScale>.png`. Pull them with `adb pull` and inspect the saved images. The direct content comparison intentionally excludes the unrelated ChatHub selectors/header.

Run the existing plus extended instrumented suite with:

```bash
adb -s "$PARENT_ANDROID_SERIAL" shell am instrument -w \
  -e class com.kungsbackacarcommunity.app.clubs.ClubChatRevocationTest \
  com.kungsbackacarcommunity.app.test/androidx.test.runner.AndroidJUnitRunner
```

The parent owns full checks, screenshot audit, integration with the other active fixes, security review and eventual PR/merge. All files remain uncommitted.
