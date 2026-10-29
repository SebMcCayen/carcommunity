# Android chat flood QA

Paths below are relative to the repository root unless a command changes directory. Use the supported Android toolchain and an explicitly selected local test device. Schedule builds and instrumentation in an available local QA slot.

## Verification boundary

Focused server policy/golden checks and Firestore transaction verification have passed for backend revisions `68d7f59` (CarCommunity) and `01504b5` (Driver). The five CarCommunity cases and one Driver case cover shared UID contention across the actual backends, concurrency, receipt replay and authorization, denied-send side effects, event-only and club-only cap overruns, and committed progressive pauses with unchanged active-pause state. The Firestore run used a disposable `demo-chat-flood` project with loopback-only transport. Reproduction commands and the shared error/state contract are in `contracts/chat-flood-v1/README.md`.

Full Android local verification is pending. Build, JVM/Compose, instrumentation and screenshot results must be recorded for the reviewed Android revision before accepting the client behavior. Server verification does not establish Android or production readiness.

## Client contract and behavior

Recognizes only normalized `resource-exhausted` plus `details.reason == chat-rate-limited`. A finite, safe nonnegative integer `retryAfterMs` is required and clamped to 600000 ms. Zero is expired; negative, fractional, missing, nonnumeric or nonfinite durations do not invent a cooldown. Other quota, authorization and access errors preserve their existing behavior.

The optional server timestamps are trusted only as a complete safe nonnegative integer pair with `blockedUntilMillis >= serverNowMillis` and exact delta equal to the original bounded retry duration. Invalid/inconsistent pairs are discarded. The local timer uses a monotonic clock, not the phone wall clock.

`ChatSendCooldown.apply` synchronizes the highest validated server deadline and local deadline. The highest server deadline remains recorded after local expiry. Duplicate/same/older pause responses are ignored, so a ten-minute response delivered again nine minutes later cannot restart ten minutes, and a response delivered after expiry cannot revive that pause. A genuinely later server penalty can replace/extend the local deadline. Only two numeric deadlines are stored in the shared per-sender gate; it contains no message, recipient or room data.

When the response has no trusted absolute pair, only the bounded relative contract is available. Such responses use the relative receipt-time delay; deduplicating their unknown server pause identity is impossible. That fallback is deliberately distinct from validated absolute-pair handling. Backend enforcement remains authoritative.

Community and convoy use the same gate/coordinator. DM, event and Club use that per-sender gate too. Text, emoji and manual failed-bubble retries pause together. The deadline only enables a manual action when it expires; no timer automatically sends or queues a message. Status entry/completion is a polite live region; changing seconds are readable text outside that live region. Resume recomputes remaining time against elapsed realtime.

Drafts are kept until acknowledgement, including mentions/reply metadata. A later user edit is never cleared by an earlier acknowledgement. An unchanged rejected attempt reuses its idempotency key. The operation key is saved alongside the existing private saved draft, so restoring a screen does not mint a new key or send anything. Confirmed failed-bubble retries clear a matching draft. A live delivered document beating a lost callable reply counts as acknowledgement, avoiding a duplicate retry.

Club retains `ClubException` with typed retry metadata. Its pure error mapper makes a changed session, unauthenticated result or permission denial win before reading cooldown details. Existing membership/lifecycle disposal, live historical pages, blocking and moderation are unchanged. Club input/message state still disappears on access loss.

The DM Firebase adapter also checks the shared send gate, covering existing event/location share send entry points. Additive `Failed.cooldown` metadata preserves their exhaustive result handling and existing manual retry keys. No share UI was redesigned.

## Eight-line message bodies

`ChatMessageBody` is shared by Community/convoy/Club bubbles, DM and visible event bodies. Only the existing body `Text` presentation was replaced. Its default maximum is eight lines, with localized Show more / Show less controls and accessible expansion state. Expanded state is keyed by message ID and body text. Short messages have no extra control.

The original full `AnnotatedString` is passed to `Text` in both modes; no substring/truncation/emoji counting is applied. Existing Community/DM annotation builders and event web-link builder are byte-for-byte unchanged. Mention styling, URL/location/event links, quote headers, long-press moderation and hidden/removed event-message policy remain in their current owners. No emoji ban or score, and no content-limit change.

## Event optional idempotency binding

The server schema at `functions/src/events/chat-core.ts` supports optional `clientId` matching `[A-Za-z0-9_-]{1,64}`. Its post binding uses that ID for the message document, checks existing author identity and deduplicates before the send guard. This client sends UUID keys through a backward-compatible repository overload; legacy two-argument fake repositories and absent-ID payloads still work. The corresponding backend change must be available before shipping the new event client binding.

## Localization/source scope

Android strings live in `apps/android/app/src/main/res/values/chat_flood_strings.xml` and `apps/android/app/src/main/res/values-en/chat_flood_strings.xml`, with an independent `chat_flood_` prefix. Review both translations and the coordinated backend/client release boundary.

## Focused Android checks

- `ChatSendCooldownTest`: strict discriminator and numeric validation, cap/zero/negative data, monotonic expiry, optional timestamp consistency, delayed same pause nine minutes later, same/older pause after expiry, genuinely later penalty, event clientId omission/validation.
- `ChannelChatCoordinatorTest`: cooldown gates text/emoji/retry; exact retry key; no dispatch at expiry; delivered snapshot beating a lost callable reply.
- `DmThreadCoordinatorTest`: retained failed content/key, gate at all send entry points, manual retry after expiry.
- `ChatCoordinatorTest`: optional event clientId retry during/after cooldown, legacy repository compatibility.
- `ClubContractTest`: retry metadata survives; auth/session/permission denial takes precedence.
- `ChatFloodDraftRetentionTest`: Community/convoy/DM/event acknowledgement behavior, newer edits preserved, quick emoji gated, no send on expiry or saved-state restoration, restored retry key.
- `ChatCooldownNoticeTest`: large text, no ticking live region, same-sender sharing/other-account isolation.
- `ChatMessageBodyTest`: 2000-emoji wall is eight lines; explicit expansion/collapse; full original text, compound emoji, mention/link annotations and large font retained; short messages compact.
- instrumented `ClubChatRevocationTest`: cooldown draft/send/emoji gate and membership loss still disposes the draft/status.

Run the focused JVM/Compose checks from `apps/android`:

```bash
cd apps/android
./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest \
  --tests 'com.kungsbackacarcommunity.app.chat.ChatSendCooldownTest' \
  --tests 'com.kungsbackacarcommunity.app.chat.ChatCoordinatorTest' \
  --tests 'com.kungsbackacarcommunity.app.chatchannels.ChannelChatCoordinatorTest' \
  --tests 'com.kungsbackacarcommunity.app.dm.DmThreadCoordinatorTest' \
  --tests 'com.kungsbackacarcommunity.app.clubs.ClubContractTest' \
  --tests 'com.kungsbackacarcommunity.app.chat.ChatFloodDraftRetentionTest' \
  --tests 'com.kungsbackacarcommunity.app.chat.ChatCooldownNoticeTest' \
  --tests 'com.kungsbackacarcommunity.app.chat.ChatMessageBodyTest'
./gradlew --no-daemon --max-workers=1 :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Also verify existing event/location share, chat reply/link/mention, blocking/moderation, and Club retained-history/revocation regression suites. Record the revision, exact commands, toolchain, test counts, skips and failures. These commands define the verification procedure; they do not imply a passing Android run.

## Deterministic screenshot fixture

`ChatFloodCaptureTest.captureCooldownAndEmojiWall` renders the actual message bodies/composers with 2000 local car emoji and a fixed 30-second status. Club uses its real route with a local repository and an injected frozen monotonic gate. There are no remote reads/writes. These are display stress fixtures, not claims that every surface accepts 2000 emoji in a new send; existing per-surface protocol limits are retained.

The capture accepts these instrumentation arguments:

- `chatFloodSurface`: `community` (default), `convoy`, `club`, `dm`, `event`.
- `redesignTheme`: `dark` (default) or `light`.
- `chatFloodFontScale`: a value from `1.0` (default) through `2.0`.

After building the APKs, install them and capture on the device selected by `ANDROID_SERIAL`. These commands continue from `apps/android`:

```bash
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$ANDROID_SERIAL" shell am instrument -w \
  -e class com.kungsbackacarcommunity.app.chat.ChatFloodCaptureTest \
  -e chatFloodSurface community -e redesignTheme dark -e chatFloodFontScale 1.0 \
  com.kungsbackacarcommunity.app.test/androidx.test.runner.AndroidJUnitRunner
```

Files are written under the target app external-files directory as `chat-flood/<surface>-<theme>-<fontScale>.png`. Pull them into a build-output directory:

```bash
mkdir -p app/build/reports/chat-flood
adb -s "$ANDROID_SERIAL" pull \
  /sdcard/Android/data/com.kungsbackacarcommunity.app/files/chat-flood \
  app/build/reports/chat-flood/
```

Repeat the capture for each surface in dark/light themes and at normal/large text sizes. Keep viewport and density fixed, record the device/API level and instrumentation arguments, and inspect the saved PNGs. Capture files alone are not acceptance evidence until their rendered content has been inspected. Android capture verification remains pending.
