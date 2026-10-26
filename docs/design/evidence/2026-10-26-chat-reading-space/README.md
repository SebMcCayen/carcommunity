# Android chat reading space — S29/S30

The screenshots show the real `ChatHubRoute` with local, test-only repository
messages. They are emulator component evidence, not a signed production session
or proof of real-message delivery. The capture harness adds the visible local
test-data caption and does not show the surrounding map-shell footer.

## Captured steps

1. [Before, dark](before-dark.png): readable, but extra wrapper padding consumes
   conversation space and the 280 dp bubble cap wraps longer messages early.
2. [After, dark](after-dark.png): the existing colors, source/channel controls,
   quick reactions and body typography remain; the conversation gains room.
3. [After, light](after-light.png): the same compact geometry stays legible in
   the existing light theme; no new palette or typography is introduced.

All three captures use the same API 34 emulator, 1170 × 2532 px at density 480
(390 dp wide), local messages and native Compose screens. Saved images were
opened and compared before accepting them. The 4 dp gap between the source
selector and channel strip is deliberate; removing it made the rows touch.

## Layout budget and checks

The normal community/convoy layout gains 28 dp of fixed message viewport:
16 dp from outer vertical padding, 8 dp from the two composer gaps and 4 dp from
the source wrapper. Without the source selector the gain is 24 dp. This does not
include any message-density gain from less wrapping or tighter multi-line bubbles.
The 320 dp cap still respects the available width, and bubbles retain a minimum
48 dp long-press target. Text remains 16 sp and follows system scaling.

- 166 focused chat-channel JVM tests passed; one existing navigation-inset test
  skipped because Robolectric reported a zero system-navigation inset.
- All four viewport cases were rerun after the final gap adjustment: Swedish
  dark and English light, 320/390 dp widths and font scales 1.0/1.5; all passed.
  They check readable body size, minimum viewport, accessible navigation targets,
  draft retention and source/back navigation.
- Debug lint, debug APK and instrumentation APK builds passed.
- Baseline capture and both final native emulator captures passed.
- A separate read-only agent review found no actionable regressions and checked
  the 28 dp layout calculation.

No iOS source, generated files, routes, backend, data access or feature flags
changed. This pass does not verify a physical phone, TalkBack traversal, the
keyboard-up production flow or complete 84-screen catalogue parity.
