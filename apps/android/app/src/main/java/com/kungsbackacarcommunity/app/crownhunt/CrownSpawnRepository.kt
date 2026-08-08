package com.kungsbackacarcommunity.app.crownhunt

/**
 * The auto-spawn half of Kronjakt's data access. Firebase-free so the layer's
 * logic — the query plan, the poll cadence, the flag gate — is unit-testable
 * with a fake.
 *
 * Two operations, and they use two different transports on purpose:
 *
 *  - [listNearby] reads `crownSpawns` DIRECTLY from Firestore. The security rule
 *    already restricts the collection to live, unexpired crowns for an active
 *    member, so a callable in front of it would add a cold start and a hop
 *    without adding a check. There is nothing secret about a crown's position —
 *    being visible on a shared map is the entire feature.
 *  - [claimSpawn] is the `crownHunt-claimSpawn` callable, because collecting is
 *    where every real check lives (stationary proof, radius, first-come, daily
 *    cap, risk) and none of it can be done by a client.
 */
interface CrownSpawnRepository {
    /**
     * Live crowns in [cellKeys], as of [nowMillis].
     *
     * The `expiresAt > now` filter is applied by the query itself so the
     * security rule's condition is satisfied — a query without it is REJECTED,
     * not silently narrowed, because Firestore rules validate the query shape
     * rather than the returned rows.
     *
     * An empty [cellKeys] returns an empty list WITHOUT touching the network:
     * Firestore rejects an empty `in` array, and there is nothing to ask for
     * anyway.
     */
    suspend fun listNearby(cellKeys: List<String>, nowMillis: Long): List<CrownSpawn>

    /**
     * `crownHunt-claimSpawn`. [previous] is the earlier of the two fixes proving
     * the member is dwelling; both are required and the server derives its own
     * speed from the pair.
     *
     * Returns an outcome for every REFUSAL (they are result codes, not errors);
     * throws only for a transport/auth failure the user should see as "something
     * went wrong", never as a judgement about them.
     */
    suspend fun claimSpawn(
        spawnId: String,
        current: CrownFix,
        previous: CrownFix,
        idempotencyKey: String,
    ): CrownSpawnClaimOutcome

    companion object {
        /**
         * Cap on the crowns one refresh will draw.
         *
         * The query is already bounded by the cell plan (at most
         * [CrownSpawnQuery.MAX_CELLS] cells, across at most
         * [CrownSpawnQuery.MAX_BATCHES] parallel `in` queries) and the spawner's
         * own density budget (at most 5 live crowns per cell). With the widened
         * town-sized plan this cap can now genuinely bind on a dense town, so it
         * is what keeps one pan from turning into an unbounded read and an
         * unbounded annotation redraw. Batches are merged and deduped by crown id
         * before this cap is applied.
         */
        const val MAX_SPAWNS_PER_QUERY: Long = 150
    }
}
