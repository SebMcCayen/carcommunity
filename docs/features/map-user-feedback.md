# Map, live sharing and Crown Hunt feedback

Point 8 of the November 2026 recorded-map UX review. Depends on the stop gate
and readiness work in points 4 and 5. No backend contract, safety threshold,
entitlement, deployment or release change.

## User-facing behavior

- Ordinary copy describes the result or next action, rather than server work.
  Crown levels and badges appear when earned; collection failures explain how to
  recover. Background statistics calculation remains quiet.
- Successful crown receipt order on Android and iOS: success text, actual awarded
  amount, actual new balance when provided, daily remaining snapshot when valid.
  The crown's advertised base reward is never substituted for an absent or
  clipped award. Duplicate/refused claims never replay an award receipt.
- Older responses may omit balance/allowance. Invalid optional amounts (including
  fractional, boolean, negative or out-of-range values) are omitted rather than
  truncated. Allowance accepts the existing 2250/3000 caps and a valid reset
  instant; reset metadata remains in the model but is not printed in the receipt.
- Receipt and plan units are KP in Swedish and CP in English. Daily snapshot copy
  is explicitly at collection time, not a continually updated live budget. The
  instructions retain the real midnight-in-Sweden rule outside the reward.
- Automatic-crown instructions are normal, readable text outside the disabled
  Collect button. Stop safely, stay stopped briefly, waiting for position/signal,
  ready, and collecting remain distinct states. The button keeps its action name.
  Android exposes a polite live region; iOS queues discrete VoiceOver state
  changes. Neither announces each distance update or each second of dwell.
- Compass accessibility describes the next action and separately localizes the
  current mode. It does not read an internal enum such as `courseUp`.
- Live-sharing privacy copy states that signed-in users outside the convoy may
  also see a shared position, with bilateral blocks respected. It does not invent
  a paid-only visibility policy. Entitlement gating is currently globally off;
  active-account and block checks remain authoritative. Privacy/history and
  stop-sharing explanations remain intact.

## Scope and parity

Affected catalog screens: S01 map compass, S18/S19 crown details, S20 receipt,
S21 statistics, S23 instructions; plus existing live-sharing privacy and profile
badge fallback messages. Both native clients use the shared SV/EN localization
contract and existing colors, spacing, and royal-crown asset. Receipt order and
optional-field semantics match; native SwiftUI/Compose layout and accessibility
APIs differ. The four-second physical stop evidence and lifecycle cancellation
from points 4/5 are unchanged.

## Acceptance and evidence

Automated Android UI checks exercise status transitions in an already-open panel,
a readable polite status outside a disabled action, stable action labels,
actual/clipped/missing/duplicate rewards, receipt order, English units, missing
metadata and accessible compass action/state after a tap. Native parsing tests
reject malformed optional receipt fields. iOS adds equivalent parsing/format,
VoiceOver state-priority and large Dynamic Type receipt/status layout checks.

Local focused validation passed: 67 Android JVM/Robolectric tests (allowance 3,
gate 33, docked sheet 5, reward presentation 5, message mappings 20, compass 1),
production/unit/instrumentation Kotlin compilation, iOS source preflight, both
resource generators and 2,323-key SV/EN key/format parity. Instrumentation sources
compiled; device instrumentation was not executed for this point. Native
XCTest and device VoiceOver/TalkBack, configured map screenshots, and real GPS
behavior require their respective device/Xcode environment. A source build alone
does not establish visual or driving acceptance.
