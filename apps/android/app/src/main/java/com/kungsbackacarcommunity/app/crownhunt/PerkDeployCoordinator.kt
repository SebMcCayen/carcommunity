package com.kungsbackacarcommunity.app.crownhunt

import com.kungsbackacarcommunity.app.navigation.LatLng
import com.kungsbackacarcommunity.app.navigation.isValidWgs84Coordinate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why a deploy failed — selects the (localized) message the menu shows. */
enum class PerkDeployFailureReason {
    /**
     * A trap needs the caller's current GPS and none was available (permission
     * off, or no fix yet). Resolved CLIENT-side before the callable is invoked.
     */
    NO_LOCATION,

    /**
     * The shop/deploy path refused: the `crownHuntPerks` flag is off, the perk
     * is unknown, the member owns none, a trap cap (1 active / 3-per-day /
     * 300 m spacing) was hit, or the account cannot deploy. All arrive as the
     * server's `failed-precondition`; the deploy callable attaches no structured
     * discriminator, so they collapse to one "couldn't activate" message.
     */
    UNAVAILABLE,

    /**
     * Too many perk effects are already live — the server-side concurrent
     * activation limit (`MAX_CONCURRENT_ACTIVE_PERKS` in functions
     * `crownHunt/perks-core.ts`, enforced in the deploy transaction) was hit. The
     * one deploy rejection that carries a structured `details.reason`, so it gets
     * its own message.
     */
    ACTIVATION_LIMIT,

    /**
     * A trap was placed within 300 m of an active/imminent event — the
     * server-side anti-griefing rule (`crownHunt/deployPerk.ts`,
     * `details.reason == "event_too_close"`). Its own message so the user is
     * told to move away from the meet.
     */
    EVENT_TOO_CLOSE,

    /** Anything else (network, unexpected server error). */
    UNKNOWN,
}

/** UI-facing status of the deploy flow. Carries the perkId so a per-row spinner/message targets one perk. */
sealed interface PerkDeployStatus {
    data object Idle : PerkDeployStatus

    /** A deploy for [perkId] is in flight — the row's button shows a spinner and is disabled. */
    data class Deploying(val perkId: String) : PerkDeployStatus

    /**
     * Deployed [perkId]. [expiresAtMillis] is the effect's expiry (trap/shield/
     * boost), [inventoryCount] the remaining owned count, [alreadyDeployed] true
     * on an idempotent replay.
     */
    data class Deployed(
        val perkId: String,
        val kind: PerkKind,
        val expiresAtMillis: Long,
        val inventoryCount: Long,
        val alreadyDeployed: Boolean,
    ) : PerkDeployStatus

    data class Failed(val perkId: String, val reason: PerkDeployFailureReason) : PerkDeployStatus
}

/**
 * Orchestrates a single perk DEPLOY. Pure Kotlin (Firebase-free) so it is
 * unit-testable with a fake repository + a fake location source, mirroring
 * [PerkShopCoordinator].
 *
 * Two guards against a double-deploy:
 *  1. A SYNCHRONOUS in-flight guard — a second [deploy] while one is
 *     [PerkDeployStatus.Deploying] is dropped before any suspension point, so a
 *     double-tap cannot start two calls.
 *  2. A fresh idempotency key per logical deploy ([keyFactory]) — a retried
 *     call consumes inventory once server-side.
 *
 * TRAP handling: a trap is dropped at the caller's CURRENT GPS, resolved lazily
 * via [locationSource] only when a trap is actually deployed (so the menu never
 * requests a fix just by opening). A null fix short-circuits to
 * [PerkDeployFailureReason.NO_LOCATION] without a round-trip. SHIELD/BOOST need
 * no location.
 *
 * The coordinator remembers the last SHIELD/BOOST expiry it deployed
 * ([shieldActiveUntilMillis]/[boostActiveUntilMillis]) so the menu can show a
 * live countdown and disable a re-raise instantly — before the server record's
 * listener echoes the deploy back. This session window is a per-process cache
 * only: it is LOST on a cold start, so the authoritative post-restart source is
 * the owner-readable server record (`perkShieldPublic.shieldedUntil` /
 * `perkBoost.expiresAt`), which the caller merges with these via `laterOf`.
 */
class PerkDeployCoordinator(
    private val repository: PerkShopRepository,
    private val locationSource: suspend () -> LatLng?,
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val state = MutableStateFlow<PerkDeployStatus>(PerkDeployStatus.Idle)
    val status: StateFlow<PerkDeployStatus> = state.asStateFlow()

    // Last-known active windows for the effects deployed THIS session — a
    // per-process cache for instant countdown/disable before the server record's
    // listener echoes the deploy back. Both effects also have an owner-readable
    // server record (`perkShieldPublic` / `perkBoost`), and the menu takes
    // whichever of session vs server is later — so a boost survives a restart via
    // the server record even though this cache is gone.
    private val shieldUntil = MutableStateFlow<Long?>(null)
    private val boostUntil = MutableStateFlow<Long?>(null)

    /** Session-local shield expiry from the last deploy (epoch-ms), or null. */
    val shieldActiveUntilMillis: StateFlow<Long?> = shieldUntil.asStateFlow()

    /** Session-local boost expiry from the last deploy (epoch-ms), or null. */
    val boostActiveUntilMillis: StateFlow<Long?> = boostUntil.asStateFlow()

    // Race-free single-deploy guard (an atomic compareAndSet, unlike a
    // check-then-set on state.value), matching PerkShopCoordinator.
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Deploys one unit of [perkId] (of [kind]). For a TRAP, resolves the current
     * GPS and fails locally with [PerkDeployFailureReason.NO_LOCATION] if none is
     * available; SHIELD/BOOST ignore location.
     */
    suspend fun deploy(perkId: String, kind: PerkKind) {
        // Atomic in-flight guard: only one deploy can claim `inFlight`; a
        // concurrent or double tap (any perk) returns immediately. `finally`
        // releases it on every exit (success, failure, or cancellation).
        if (!inFlight.compareAndSet(false, true)) return
        try {
            val location: LatLng? =
                if (kind == PerkKind.TRAP) {
                    val fix = resolveTrapLocation()
                    if (fix == null) {
                        state.value =
                            PerkDeployStatus.Failed(perkId, PerkDeployFailureReason.NO_LOCATION)
                        return
                    }
                    fix
                } else {
                    null
                }

            state.value = PerkDeployStatus.Deploying(perkId)
            try {
                val result =
                    repository.deployPerk(
                        perkId = perkId,
                        latitude = location?.latitude,
                        longitude = location?.longitude,
                        idempotencyKey = keyFactory(),
                    )
                rememberActiveWindow(result)
                state.value =
                    PerkDeployStatus.Deployed(
                        perkId = result.perkId,
                        kind = result.kind,
                        expiresAtMillis = result.expiresAtMillis,
                        inventoryCount = result.inventoryCount,
                        alreadyDeployed = result.alreadyDeployed,
                    )
            } catch (cancellation: CancellationException) {
                state.value = PerkDeployStatus.Idle
                throw cancellation
            } catch (activationLimit: PerkDeployActivationLimitException) {
                state.value =
                    PerkDeployStatus.Failed(perkId, PerkDeployFailureReason.ACTIVATION_LIMIT)
            } catch (eventTooClose: PerkDeployEventTooCloseException) {
                state.value =
                    PerkDeployStatus.Failed(perkId, PerkDeployFailureReason.EVENT_TOO_CLOSE)
            } catch (unavailable: PerkDeployUnavailableException) {
                state.value = PerkDeployStatus.Failed(perkId, PerkDeployFailureReason.UNAVAILABLE)
            } catch (missingLocation: PerkDeployMissingLocationException) {
                // The server also rejects a trap with no/invalid coordinate; map
                // it to the same NO_LOCATION message the client pre-check uses.
                state.value = PerkDeployStatus.Failed(perkId, PerkDeployFailureReason.NO_LOCATION)
            } catch (failure: Exception) {
                state.value = PerkDeployStatus.Failed(perkId, PerkDeployFailureReason.UNKNOWN)
            }
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun resolveTrapLocation(): LatLng? {
        val fix = locationSource() ?: return null
        // Cheap client-side WGS-84 pre-check (the server re-validates); a bad fix
        // is treated as "no location" so the user is told to try again, not shown
        // a generic error.
        return if (isValidWgs84Coordinate(fix)) fix else null
    }

    private fun rememberActiveWindow(result: PerkDeployResult) {
        when (result.kind) {
            PerkKind.SHIELD -> shieldUntil.value = result.expiresAtMillis
            PerkKind.BOOST -> boostUntil.value = result.expiresAtMillis
            PerkKind.TRAP -> Unit
        }
    }

    /** Clears a terminal status (Deployed/Failed) so the menu is fresh again. */
    fun reset() {
        if (state.value !is PerkDeployStatus.Deploying) {
            state.value = PerkDeployStatus.Idle
        }
    }
}
