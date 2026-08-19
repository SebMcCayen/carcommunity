package com.kungsbackacarcommunity.app.crownhunt

/**
 * The AUTO-SPAWNED half of Kronjakt, client side — the crowns that appear on the
 * map by themselves and are collected while parked.
 *
 * Sibling of [CrownHuntPoint], which is the hand-placed admin point collected
 * through `crownHunt.submitClaim`. The two are deliberately separate types with
 * separate result vocabularies, because they are separate backend flows with
 * separate limits: mixing them into one model would mean every screen had to
 * carry a "which kind is this" branch anyway, with the added risk of mapping a
 * result code onto the wrong flow's message.
 *
 * Everything here mirrors `functions/src/crownHunt/crown-spawn-core.ts`. Pure
 * Kotlin — no Android, no Firebase, no Mapbox — so the parts that MATTER (the
 * collect gate, the query plan, the result mapping) are JVM-unit-tested in CI
 * rather than eyeballed on one device.
 */

/**
 * A crown's rarity tier. [wire] mirrors the backend `CROWN_RARITIES` values and
 * [rewardPoints] mirrors `CROWN_RARITY_TABLE`.
 *
 * The reward is carried on the enum as the *expected* value for a tier, but the
 * value actually SHOWN comes from the crown document's own `rewardPoints`
 * (see [CrownSpawn.rewardPoints]). The server is authoritative about what a
 * particular crown pays; this table exists so a document with a missing or
 * malformed reward still renders an honest number instead of "0 KP", and so the
 * popup can be previewed without a backend.
 */
enum class CrownRarity(val wire: String, val rewardPoints: Int) {
    COMMON("common", 10),
    UNCOMMON("uncommon", 25),
    RARE("rare", 100),
    LEGENDARY("legendary", 500),
    ;

    /**
     * Whether a crown of this tier is collectable once per member and stays live
     * for others ([CrownCollectMode.SHARED]), or removed for everyone by the first
     * taker ([CrownCollectMode.EXCLUSIVE]).
     *
     * Mirrors `crownCollectMode` / `MIN_EXCLUSIVE_CROWN_RANK` in
     * `functions/src/crownHunt/crown-spawn-core.ts`: a tier at or above the RARE
     * rank is exclusive, everything below is shared — so today common/uncommon are
     * shared and rare/legendary exclusive. The cutoff is ONE comparison here, as it
     * is one constant on the backend, and [entries] is declared low→high so the
     * ordinal IS the rank (pinned by the wire-order test in `CrownMarkerStyleTest`).
     */
    val collectMode: CrownCollectMode
        get() = if (ordinal >= RARE.ordinal) CrownCollectMode.EXCLUSIVE else CrownCollectMode.SHARED

    companion object {
        /** Maps a backend wire value to a rarity, or null when unknown. */
        fun fromWire(value: String?): CrownRarity? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * How a crown may be collected — mirrors `CROWN_COLLECT_MODES` in
 * `functions/src/crownHunt/crown-spawn-core.ts`.
 *
 * [SHARED] crowns (common/uncommon) may each be collected ONCE by many distinct
 * members and stay `live` on the map to their TTL; a member's second attempt is
 * refused `already_collected`. [EXCLUSIVE] crowns (rare/legendary) are removed
 * for everyone the moment the first member takes one. This is what decides
 * whether a just-collected crown is kept-and-marked or dropped from the map.
 */
enum class CrownCollectMode { SHARED, EXCLUSIVE }

/**
 * `crownHunt.claimSpawn` result codes — mirror `CROWN_SPAWN_CLAIM_RESULTS`
 * exactly, including the ones a well-behaved client should never provoke, so a
 * backend that starts returning one is rendered rather than swallowed.
 *
 * These are RESULTS, not errors: the callable answers 200 with one of these and
 * a message. The mapping to localized copy lives in [CrownSpawnMessages].
 */
enum class CrownSpawnClaimResult(val wire: String) {
    AWARDED("awarded"),
    ALREADY_TAKEN("already_taken"),
    // A SHARED crown (common/uncommon) is collectable once per member but stays
    // on the map to its TTL for everyone else, so a member who already picked it
    // up will still see — and can still tap — the very same crown. The backend
    // answers that second tap with `already_collected`. This MUST be in the enum:
    // a missing code makes fromWire return null, the response fails to parse, and
    // the popup shows the generic "something went wrong" transport error for what
    // is a benign, expected re-tap (issue #874).
    ALREADY_COLLECTED("already_collected"),
    OUTSIDE_RADIUS("outside_radius"),
    MUST_BE_STATIONARY("must_be_stationary"),
    POSITION_TOO_OLD("position_too_old"),
    CROWN_EXPIRED("crown_expired"),
    DAILY_LIMIT_REACHED("daily_limit_reached"),
    RISK_REVIEW("risk_review"),
    FEATURE_DISABLED("feature_disabled"),
    NOT_ELIGIBLE("not_eligible"),
    ;

    companion object {
        fun fromWire(value: String?): CrownSpawnClaimResult? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One live crown on the map (`crownSpawns/{spawnId}`).
 *
 * Read straight from Firestore rather than through a callable: the security
 * rule already restricts the collection to `status == 'live'` AND
 * `expiresAt > request.time` for an active member, so a direct query is both
 * cheaper and impossible to widen from the client. There is no per-crown secret
 * to protect — a crown's whole purpose is to be visible on a shared map.
 *
 * @property collectRadiusMeters the crown's own radius, as the server stamped
 *   it. Read from the document rather than assumed to be
 *   [CrownSpawnLimits.COLLECT_RADIUS_METERS] so a server-side retune takes
 *   effect without an app release. Always a SANITIZED value: every parser puts
 *   the raw field through [CrownSpawnLimits.resolveCollectRadiusMeters], so a
 *   document that omits it — or carries a broken one — yields the mirrored
 *   constant rather than a number the popup would print and the gate ignore.
 */
data class CrownSpawn(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val rarity: CrownRarity,
    val rewardPoints: Int,
    val collectRadiusMeters: Double,
    val expiresAtMillis: Long?,
)

/**
 * One device position fix submitted with a claim.
 *
 * `crownHunt.claimSpawn` requires TWO of these — the stationary proof — and
 * derives its own speed from the pair, so a client cannot talk its way past the
 * stop rule by reporting `speed: 0`.
 */
data class CrownFix(
    val latitude: Double,
    val longitude: Double,
    val recordedAtMillis: Long,
    val speedMetersPerSecond: Double? = null,
    val accuracyMeters: Double? = null,
    /**
     * `Location.isMock` as the platform reported it.
     *
     * Sent as-is and never suppressed. It is a ONE-WAY signal on the backend
     * (true is penalised, false and absent are treated identically), so an
     * honest client loses nothing by being truthful and a dishonest one gains
     * nothing by lying — which is exactly why there is no reason to withhold it.
     */
    val isMock: Boolean? = null,
)

/** The parsed `crownHunt.claimSpawn` response. */
data class CrownSpawnClaimOutcome(
    val result: CrownSpawnClaimResult,
    val pointsAwarded: Int?,
    val newBalance: Int?,
    val rarity: CrownRarity?,
)

/**
 * Server-side limits mirrored so the UI can explain a refusal BEFORE making it —
 * a Collect button that is honestly disabled with "move closer, 120 m to go"
 * beats one that looks live and then refuses.
 *
 * Mirrored, not authoritative: every one of these is re-checked by
 * `crownHunt.claimSpawn` against server-computed distances and a server-derived
 * speed. If a constant here ever drifts from the backend the only consequence is
 * a button that is slightly too eager or slightly too shy — never a collection
 * that should not have happened.
 */
object CrownSpawnLimits {
    /** `COLLECT_RADIUS_METERS` — how close you must be. */
    const val COLLECT_RADIUS_METERS: Double = 75.0

    /**
     * `MAX_COLLECT_SPEED_MPS` — 2.0 m/s (7.2 km/h).
     *
     * The ONLY use of speed anywhere in this feature. It is a safety gate, not a
     * score: nothing is faster, better or worth more for having moved quickly,
     * and no speed value is ever shown to the user. See [CrownCollectGate].
     */
    const val MAX_COLLECT_SPEED_MPS: Double = 2.0

    /** `MIN_DWELL_SECONDS` — the two proof fixes must be at least this far apart. */
    const val MIN_DWELL_SECONDS: Long = 4

    /** `MAX_DWELL_SECONDS` — beyond this the earlier fix says nothing about now. */
    const val MAX_DWELL_SECONDS: Long = 300

    /**
     * `MAX_POSITION_AGE_SECONDS` — how old the CURRENT fix may be and still be
     * accepted. Mirrored from `MAX_POSITION_AGE_SECONDS` in
     * `functions/src/crownHunt/crownhunt-core.ts` (the value `isPositionFresh` in
     * `crown-hunt-geo.ts` enforces on `crownHunt.claimSpawn`).
     *
     * Only the CURRENT half is bound by this — the earlier proof fix is allowed to
     * be up to [MAX_DWELL_SECONDS] old, which is the whole point of the dwell
     * window. The client uses it to reject a genuinely STALE leftover fix (minutes
     * old, from before a long idle) while still treating a normal slightly-old
     * sample as usable, so the client's "is there a current position" answer
     * agrees with the server's rather than being stricter and re-blocking a
     * collect the server would accept.
     */
    const val MAX_POSITION_AGE_SECONDS: Long = 60

    /**
     * `MAX_STORED_COLLECT_RADIUS_METERS` — the widest gate a crown DOCUMENT is
     * allowed to ask for.
     *
     * Mirrored from the backend, and worth mirroring: every other way a crown
     * document can be wrong fails CLOSED (a non-numeric coordinate makes the
     * distance NaN, and `NaN > radius` is false in neither direction — the claim
     * is refused server-side), while an oversized radius is the one corruption
     * that fails OPEN, by widening the geofence. The spawner only ever writes
     * [COLLECT_RADIUS_METERS] and clients cannot write `crownSpawns` at all, so
     * a stored radius beyond this bound means the document is wrong — a console
     * edit, a migration, or a future bug.
     */
    const val MAX_STORED_COLLECT_RADIUS_METERS: Double = 250.0

    /**
     * The radius to actually use for a crown, from whatever its document holds.
     *
     * Mirrors `resolveCollectRadiusMeters` in
     * `functions/src/crownHunt/crown-spawn-core.ts`, upper bound included, so
     * the client agrees with the server about which stored radii are real.
     *
     * Applied at the PARSE boundary, once, rather than at each use — so
     * [CrownSpawn.collectRadiusMeters] is sanitized by construction. The
     * alternative, each consumer deciding for itself, is exactly how a popup
     * ends up printing "get within 0 m" off the raw field while
     * [CrownCollectGate] quietly enables the button at 75 m: two honest-looking
     * numbers that contradict each other, from one bad document.
     *
     * Null, non-finite, zero, negative and absurd all yield
     * [COLLECT_RADIUS_METERS], so a wrong document can only ever produce the
     * DEFAULT gate — never a wider one.
     */
    fun resolveCollectRadiusMeters(stored: Double?): Double =
        if (stored != null &&
            stored.isFinite() &&
            stored > 0.0 &&
            stored <= MAX_STORED_COLLECT_RADIUS_METERS
        ) {
            stored
        } else {
            COLLECT_RADIUS_METERS
        }

    /** Feature-flag key gating the whole automatic half. Contract default OFF. */
    const val SPAWN_FLAG_KEY: String = "crownHuntSpawn"
}
