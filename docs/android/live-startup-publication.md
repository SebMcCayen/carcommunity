# Live startup publication

A session being active is not proof that a viewer can discover its first position.
Android previously recorded a submission before awaiting its upload, so a failed
first call could suppress a stationary driver's next attempt for 180 seconds.
Session replacement on the same service could also retain the earlier throttle.

The session-owned publisher now serializes uploads, retains only the newest
quality-accepted fresh fix, and gives startup at most four extra attempts after
2, 5, 10 and 20 seconds. Recovery stops early only after an upload succeeds and
the owner-session discovery bookkeeping confirms a discovery write. Exhaustion
returns to the ordinary movement/heartbeat policy; it never creates a permanent
rapid write loop. A new session resets the state; stop, expiry and account changes
cancel pending dispatch. Already dispatched calls remain subject to server fences.

Discovery bookkeeping must be stamped atomically for the matching active session
ID and generation. An unconditional server update can otherwise attach an old
session's proof to its replacement. Ship the corresponding server correction
before relying on this readiness guarantee during Android rollout. No API
response, security rule or iOS client change is required for that correction.

Viewers seed each authorized RTDB listener with an absent value, so a slow first
snapshot cannot hold back other drivers. Nearby polling waits for the first
usable camera immediately; later pans keep the existing polling cadence.

These are source-backed failure paths, not a reconstruction of a production
incident. Accurate provider fixes, permissions, network and authorized/in-range
viewers are still required. Controlled clock/flow tests and CI do not replace a
physical two-device acceptance test on the published build.
