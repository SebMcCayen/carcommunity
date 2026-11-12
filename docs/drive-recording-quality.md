# Drive recording quality and diagnostics

Issue: [#1348](https://github.com/SebMcCayen/carcommunity/issues/1348).

A recorded drive can have correct endpoints and duration while its distance is
severely understated. The existing distance calculation excludes consecutive
segments requiring more than 55.6 m/s (about 200 km/h), but the saved map draws
all stored coordinates. A bad intermediate fix can invalidate both surrounding
segments. This change improves the input and preserves diagnostic evidence; it
does not replace the distance algorithm, redraw historical routes, or backfill
previously saved statistics.

## Client quality policy

Android's dedicated recording stream validates finite coordinates, reported
horizontal accuracy (0–50 m), freshness (at most 15 seconds old, with 5 seconds
of future-clock tolerance), increasing monotonic sample time, and plausible
movement from the last trusted fix. A rejected sample cannot become the next
trusted anchor. Route epoch timestamps are derived from the monotonic sample
clock within a run, with a fresh anchor after restoration. Ordinary map,
navigation, crown and live-publishing streams keep their own policies.

iOS's dedicated session provider already applies `SessionLocationFilter` to
both live sharing and recording. The recording event stream now exposes typed
rejection decisions to the recorder while keeping the same stationary,
slow-motion, movement-confirmation and recovery behavior. `CLLocation` does not
expose Android's monotonic sample timestamp; source timestamps retain the
existing freshness and chronology guards. Both clients use a monotonic receipt
clock to measure delivery silence, without persisting that clock across a
process/boot boundary.

## Evidence and persistence

The optional `recordingDiagnostics` save field has version 1 and algorithm
`recording-quality-v1`. It carries bounded totals for received/stored points,
invalid, inaccurate, stale, non-increasing-time and implausible-speed rejections,
capacity drops, intentional stationary/cadence drops, delivery gaps over 15
seconds, the largest delivery gap, and recording resumptions. Android currently
has no stationary/cadence suppression after its location request, so those two
counters are zero there. These counts distinguish intentional suppression from
faults; they do not measure how many real kilometres were lost.

Reception gaps measure silence **between consecutive observed raw deliveries**
within one continuous run. A final outage with no later delivery does not close
a gap: trailing silence between the last delivery and Stop is not measured.
Accepted-point gaps are a separate backend calculation and cannot reconstruct
a missing path.

Counters are checkpointed in the app-private recording journal and frozen with
the save request for retries. Legacy journals still restore; their previous
rejection history is unknown, so only their restored point count is seeded.
A crash can lose counters since the last checkpoint. Counts are diagnostic
observations, not an authoritative measurement or a driving-quality score.
No rejected coordinate trail, per-fix remote log, or unbounded diagnostic log is
created.

The backend accepts old requests without the new field. For newly saved drives
it derives separate `calculationDiagnostics` from the submitted points without
changing the statistics: excluded segment counts/reasons, timestamp gaps, and
whether the counted distance contradicts endpoint displacement beyond a bounded
tolerance. This identifies incomplete measurements; it never substitutes a
planned route or silently claims a reconstructed distance. Client counters and
server calculations remain distinguishable. Idempotent retries retain the first
saved document and its evidence.

Both summaries live on the existing owner-only `rides/{rideId}` record and
follow the drive's deletion/account-deletion lifecycle. Public history DTOs keep
their explicit field allowlist. No additional diagnostic collection or retention
sweep is introduced.

## Reports for a selected drive

Android and iOS have a **Report a problem with this drive** action in the saved
drive detail. It passes only that explicit `rideId` to feedback; the generic
report form never guesses the latest drive. The backend verifies ownership
inside the report transaction and attaches an allowlisted, coordinate-free
snapshot to the private feedback report. Missing and foreign drives cannot be
attached. Old drives without diagnostics remain reportable.

The public issue contains only a fixed, bounded technical summary, when
available. It never receives the drive ID, session ID, timestamps identifying a
journey, route geometry, coordinates, ownership data, or arbitrary diagnostic
strings. The existing report ID lets authorized support locate private evidence.
The ordinary free-text public-report notice remains visible on both clients.

## Validation and rollout

Regression coverage includes healthy motion, inaccurate/stale fixes,
non-increasing time, isolated GPS jumps with recovery to a trusted anchor,
delivery gaps, capacity handling, journal restoration and legacy compatibility,
save retry, selected-drive feedback, ownership checks and public-data isolation.
Native Android and iOS suites and their build checks remain CI gates. A green
source/emulator run does not establish physical-device GPS quality.

Before a future client release, deploy the additive `drives-save` and
`feedback-reportIssue` contract changes first: the previous strict backend
rejects unknown `recordingDiagnostics`/`rideId` fields. No function, Hosting,
release, store publication or historical-data migration is part of this PR.

Physical acceptance for the next authorized test build should include an open-sky
highway drive, slow traffic, stationary parking, a tunnel/signal interruption,
screen off/background recording, and a process restart on Android and iOS.
Compare the actual recorded route and measured distance; a Google Maps planned
route is a useful reference but is not the travelled-route ground truth.

Automatic issue creation for sustained quality problems, bounded GPS-stream
recovery, additional service/permission/battery state telemetry, historical
recalculation, unified gap rendering and Mapbox Map Matching remain follow-up
work. This PR first makes such decisions diagnosable without inventing a missing
route or adding unvalidated issue thresholds.
