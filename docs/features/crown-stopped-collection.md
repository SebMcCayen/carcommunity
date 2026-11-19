# Crown collection after stopping

Android, iOS and the automatic-crown callable use the same near-stationary rules:

- The crown card remains open and reflects incoming position updates. Reopening must not be required.
- Moving says to stop safely; confirming asks the member to stay still briefly; only ready enables collection.
- Automatic crowns require two in-range positions 4–12 seconds apart. The current position must be no more than 10 seconds old, measured from its provider timestamp, never its poll time.
- Derived movement must be at most 0.5 m/s. Two reported speeds over that threshold also reject the pair; one isolated reported-speed spike is tolerated when the coordinates support a stop.
- A corroborated departure clears the clients' old parked proof. Newer movement also overrides an older accuracy-ranked proof in the gate.
- Accuracy can buffer the geofence as before; it never subtracts away actual displacement or widens the movement tolerance.
- Hand-placed points retain their compatible single-fix API, with the same 0.5 m/s reported-speed threshold. The automatic-crown two-fix proof does not become a required field on this legacy endpoint.

## Shared regression scenarios

1. Keep the same crown open while approaching: moving → confirming → ready follows fresh positions without dismiss/select.
2. A parked pair with small coordinate scatter (0.5–1.5 m) and one isolated reported-speed spike remains eligible.
3. A car moving at 1 m/s is rejected, including when reported speed is absent or zero on the automatic endpoint.
4. Driving off invalidates a previously ready parked proof; another continuous stop can become ready without reopening.
5. A stale current fix, backwards/future timestamp or a proof spanning over 12 seconds cannot establish readiness.
6. No numeric speed target is shown to the member; the instruction is to stop somewhere safe.

GPS cannot establish mechanical standstill. Movement below the near-zero tolerance can be indistinguishable from sensor noise. Poor position quality can require fresh samples, and the legacy single-fix endpoint has a weaker movement signal than automatic crowns. Physical-phone verification must cover an actual stop, slowly rolling car, GPS scatter and foreground resume on both platforms.

This change intentionally keeps the four-second minimum. Sampling cadence and removal of avoidable extra waiting are handled separately. Deploying the stricter callable without the client updates may make older clients show an eager button before the server refuses stale or walking-speed evidence; deployment and mobile release need separate coordination.
