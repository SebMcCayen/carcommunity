# Android chat flood client handoff

Worktree: `/home/sebmccayen/Documents/ChatGPT/Kungsbacka Car Community/android-design-pass3-9K8W7y/android-chat-cooldown`

Branch: `codex/android-chat-cooldown`, stacked on isolated parity commit `037bba6` / draft PR #1265. This client patch remains separate from parity. The parent integration worktree has not been edited.

## Current validation boundary

No Gradle, Kotlin compilation, emulator, screenshots, push or PR has been run by this worker for this client patch. `git diff --check`, Android-only scope checks, XML parsing and delimiter checks pass. Independent Swedish/English resource files have matching keys. These are static checks, not test results. Parent owns the next build/QA slot and security review.

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

Backend working-copy schema at `chat-flood-protection/functions/src/events/chat-core.ts` supports optional `clientId` matching `[A-Za-z0-9_-]{1,64}`. Its post binding uses that ID for the message document, checks existing author identity and deduplicates before the send guard. This client sends UUID keys through a backward-compatible repository overload; legacy two-argument fake repositories and absent-ID payloads still work. The corresponding backend change must be available before shipping the new event client binding. No backend files were edited by this worker.

## Localization/source scope

New Android-only strings live in `src/main/res/values/chat_flood_strings.xml` and `src/main/res/values-en/chat_flood_strings.xml`, with an independent `chat_flood_` prefix. No generated canonical strings, iOS files, AuthenticatedApp, profile data classes, version, Play or production configuration were edited. Parent coordinates localization and rollout.

## Tests prepared, not run

- `ChatSendCooldownTest`: strict discriminator and numeric validation, cap/zero/negative data, monotonic expiry, optional timestamp consistency, delayed same pause nine minutes later, same/older pause after expiry, genuinely later penalty, event clientId omission/validation.
- Extended `ChannelChatCoordinatorTest`: cooldown gates text/emoji/retry; exact retry key; no dispatch at expiry; delivered snapshot beating a lost callable reply.
- Extended `DmThreadCoordinatorTest`: retained failed content/key, gate at all send entry points, manual retry after expiry.
- Extended `ChatCoordinatorTest`: optional event clientId retry during/after cooldown, legacy repository compatibility.
- Extended `ClubContractTest`: retry metadata survives; auth/session/permission denial takes precedence.
- `ChatFloodDraftRetentionTest`: Community/convoy/DM/event acknowledgement behavior, newer edits preserved, quick emoji gated, no send on expiry or saved-state restoration, restored retry key.
- `ChatCooldownNoticeTest`: large text, no ticking live region, same-sender sharing/other-account isolation.
- `ChatMessageBodyTest`: 2000-emoji wall is eight lines; explicit expansion/collapse; full original text, compound emoji, mention/link annotations and large font retained; short messages compact.
- Extended instrumented `ClubChatRevocationTest`: cooldown draft/send/emoji gate and membership loss still disposes the draft/status.

Parent should also run existing event/location share, chat reply/link/mention, blocking/moderation, and existing Club retained-history/revocation suites. No full-check pass is claimed here.

## Deterministic screenshot fixture

`ChatFloodCaptureTest.captureCooldownAndEmojiWall` renders the actual message bodies/composers with 2000 local car emoji and a fixed 30-second status. Club uses its real route with a local repository and an injected frozen monotonic gate. There are no remote reads/writes. These are display stress fixtures, not claims that every surface accepts 2000 emoji in a new send; existing per-surface protocol limits are retained.

After the parent grants the emulator/build slot, instrumentation arguments are:

- `chatFloodSurface`: `community` (default), `convoy`, `club`, `dm`, `event`.
- `redesignTheme`: `dark` (default) or `light`.
- `chatFloodFontScale`: `1.0` (default) or up to `2.0`.

Example, using the parent's explicitly selected device:

```bash
adb -s "$PARENT_ANDROID_SERIAL" shell am instrument -w \
  -e class com.kungsbackacarcommunity.app.chat.ChatFloodCaptureTest \
  -e chatFloodSurface community -e redesignTheme dark -e chatFloodFontScale 1.0 \
  com.kungsbackacarcommunity.app.test/androidx.test.runner.AndroidJUnitRunner
```

Files are written under the target app external-files directory `chat-flood/<surface>-<theme>-<fontScale>.png`, normally `/sdcard/Android/data/com.kungsbackacarcommunity.app/files/chat-flood/`. Keep viewport/density fixed, capture dark/light and large-text cases, pull files, and inspect the actual saved images before accepting screenshot evidence. None has been captured by this worker yet.
