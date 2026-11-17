# Three-second live publication: cost and battery review

The approved trial publishes a moving driver's accepted position at a nominal
three-second cadence on Android and iOS. Stationary sharing retains its existing
180-second heartbeat. The first accepted stopped sample may publish before the
moving deadline so viewers can stop prediction. These are publication rules,
not a promise that GPS or the network always supplies a sample on time.

## Workload change

For uninterrupted moving sharing, three seconds instead of five produces
1.67 times as many ordinary requests (1,200 rather than 720 per moving hour).
Stationary heartbeats remain 20 per hour, plus a bounded stop transition and
the existing bounded startup/retry behavior. Latest-only serial upload prevents
a slow request from creating an unbounded position backlog.

The existing `live.updatePosition` callable still authorizes each update with
a user-document read, then transacts on the ephemeral RTDB session/latest node.
No direct client RTDB write or durable position history is introduced. Discovery
Firestore refresh remains separately throttled to 60 seconds or a cell change;
crown activity remains separately throttled. Distance awards remain bounded by
the existing ledger. Enabled nearby trap scans can add query/transaction work;
their actual returned documents are not included in the simple examples below.

## Planning examples, not measured billing

The dated prices are the repository's 2026-11-12 inputs in
`functions/src/finance/pricing.ts`: RTDB download $1/GiB after a shared daily
360 MiB allowance; requests $0.40/million; CPU $0.000024/vCPU-second;
Firestore reads $0.03/100,000; model FX 9.93095 SEK/USD. No live billing or
production telemetry was queried for this change.

The following assumptions are illustrative: 30.4375 days/month, 2 KiB of
RTDB transaction/control download and 1 KiB delivered per viewer per update,
plus 100 ms allocated 1-vCPU execution per request. Actual serialized car data,
protocol overhead, retries, concurrency and cold starts must be measured.

| Scenario | 5-second requests/month | 3-second requests/month | 5-second RTDB GiB/month | 3-second RTDB GiB/month |
| --- | ---: | ---: | ---: | ---: |
| 30 drivers, 20 moving min/day each, 3 viewers per sharer | 219,150 | 365,250 | 1.05 | 1.74 |
| 30 drivers, 60 moving min/day each, 29 viewers per sharer | 657,450 | 1,095,750 | 19.44 | 32.39 |

In the first scenario, standalone live traffic remains below the RTDB daily
allowance under these assumptions. The additional 146,100 requests cost about
$0.058 in request charges, $0.351 in CPU charges and $0.044 in authorization
reads before shared free allowances, excluding memory, transfers and optional
game work. These figures are an incremental example, not the total app bill.

In the dense-convoy scenario, standalone RTDB cost after the daily allowance
is about $8.74/month at five seconds and $21.69/month at three seconds. Other
project activity consumes the same allowances. Consequently neither example
guarantees the complete project's SEK 500/month budget; observed payloads,
viewing fan-out and the shared-project baseline remain relevant.

## Battery and release acceptance

Android requests ordinary fused updates every three seconds instead of five,
with an independent three-second moving upload floor even if the provider
delivers a burst. iOS retains Core Location quality filtering and applies the
same publication floor. Faster provider sampling and additional radio wakeups
can increase battery use. No percentage battery claim is made without a matched
physical-device run. The stationary network heartbeat remains unchanged.

Before a separately authorized release, compare the same drive on both platforms:
moving publication gaps, observed stop propagation, stale-marker behavior,
turn-correction error, battery use and RTDB download/request counters. Include
a stationary segment and a dense convoy. This PR deploys no functions, changes
no billing configuration and enables no new paid service.
