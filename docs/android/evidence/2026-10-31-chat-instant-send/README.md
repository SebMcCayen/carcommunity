# Immediate chat submission

## Product Design review

The user-supplied Android screenshot (31 October 2026, Community chat) shows a
pending message in the thread while the same text still occupies the composer.
The pending status correctly distinguishes sending from delivery, but waiting for
a remote acknowledgement to clear the composer makes the action feel unfinished.

The existing gold bubbles, typography, spacing, emoji controls and keyboard
behaviour remain the visual reference. This change concerns interaction feedback:

1. **Submit:** move the current text into a pending bubble and clear the input in
   the same UI action, before awaiting the backend.
2. **While sending:** keep the delivery status in the thread. The user may type a
   new draft; an old acknowledgement must never erase it. The existing in-flight
   send guard and server anti-spam rules still apply.
3. **Failure:** retain the message for explicit retry with its original operation
   ID. Restore rejected text only when this cannot overwrite a newer draft.
   Cooldown expiry must not automatically resend anything.
4. **Lifecycle:** restore recoverable content after recreation without automatic
   network dispatch. Account, room and membership boundaries remain private.

The screenshot alone cannot establish network timing, screen-reader behaviour or
lifecycle safety. Those require controlled tests of the real composables and
coordinators, plus native rendering checks. Native fixtures use local test data
and no production Firebase account.

## Verification

Regression coverage lives in `ChatInstantSendTest`, `ChatPendingOutboxTest`,
`EventInstantSendTest`, `ClubSentDraftControlTest` and the existing draft/flood/
room-isolation suites. `ChatInstantSendTest` runs on both JVM and Android.

`ChatInstantSendCaptureTest` renders the real Community composer and coordinator
with a deliberately suspended local repository response. Its `redesignTheme`
instrumentation argument accepts `dark` or `light`. It writes pending/empty-input
and failure/new-draft native-window PNGs under the test app's external-files
`chat-instant-send` directory. These captures contain labelled local fixture data;
the user's original screenshot and real account data are not committed.

Execution results belong to the PR's validation record. Source coverage alone
must not be presented as a completed emulator or physical-device test.

The native fixture explicitly gives the Compose composer sole ownership of IME
movement (`enableEdgeToEdge` plus `ADJUST_NOTHING` on the generic test Activity).
The production Activity already owns keyboard movement through edge-to-edge
insets. A default test Activity can additionally resize/pan the whole window,
which hides otherwise valid rows above the viewport and is not a valid visual
comparison. Visibility assertions still require rows on screen without test-driven
scrolling; composer clearing is asserted before the suspended response completes.
