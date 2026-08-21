package com.kungsbackacarcommunity.app.crownhunt

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why a buy failed — selects the (localized) message resource the shop shows. */
enum class PerkBuyFailureReason {
    /** The member cannot afford the perk (client pre-check or server overdraft). */
    INSUFFICIENT_FUNDS,

    /** The member already holds the max of this perk (per-perk or total value). */
    HOLD_CAP,

    /** The shop is off, the account cannot spend, or the perk is unknown. */
    UNAVAILABLE,

    /** Anything else (network, unexpected server error). */
    UNKNOWN,
}

/** UI-facing status of the buy flow. Carries the perkId so a per-card spinner/message can target one row. */
sealed interface PerkBuyStatus {
    data object Idle : PerkBuyStatus

    /** A buy for [perkId] is in flight — the row's button shows a spinner and is disabled. */
    data class Buying(val perkId: String) : PerkBuyStatus

    /** Bought [perkId]; [newBalance]/[inventoryCount] are the post-purchase totals. */
    data class Bought(
        val perkId: String,
        val newBalance: Long,
        val inventoryCount: Long,
        val alreadyPurchased: Boolean,
    ) : PerkBuyStatus

    data class Failed(val perkId: String, val reason: PerkBuyFailureReason) : PerkBuyStatus
}

/**
 * Orchestrates a single perk purchase. Pure Kotlin (Firebase-free) so it is
 * unit-testable with a fake repository, mirroring [com.kungsbackacarcommunity.app.feedback.FeedbackCoordinator].
 *
 * Two guards against a double-buy:
 *  1. A SYNCHRONOUS in-flight guard — a second [buy] while one is [PerkBuyStatus.Buying]
 *     is dropped before any suspension point, so a double-tap cannot start two calls.
 *  2. A fresh idempotency key per logical buy ([keyFactory]) — a retried/replayed
 *     call debits once server-side. A DELIBERATE second purchase gets a new key,
 *     so it is a genuine second buy, not a deduped no-op.
 *
 * An `affordable == false` buy is rejected LOCALLY (no round-trip) as
 * [PerkBuyFailureReason.INSUFFICIENT_FUNDS]; the server remains the authority and
 * its own overdraft rejection maps to the same reason.
 */
class PerkShopCoordinator(
    private val repository: PerkShopRepository,
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val state = MutableStateFlow<PerkBuyStatus>(PerkBuyStatus.Idle)
    val status: StateFlow<PerkBuyStatus> = state.asStateFlow()

    // Race-free single-buy guard, separate from the display `state`: claiming it is
    // an atomic compareAndSet, unlike a check-then-set on state.value where two
    // callers could both read non-Buying before either writes Buying.
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Buys one unit of [perkId]. [affordable] is the row's display hint: when
     * false, the buy short-circuits to [PerkBuyFailureReason.INSUFFICIENT_FUNDS]
     * without calling the backend.
     */
    suspend fun buy(perkId: String, affordable: Boolean) {
        // Atomic in-flight guard: only one buy can claim `inFlight`; a concurrent or
        // double tap (any perk) returns immediately. `finally` releases it on every
        // exit (success, failure, short-circuit, or cancellation).
        if (!inFlight.compareAndSet(false, true)) return
        try {
            if (!affordable) {
                state.value = PerkBuyStatus.Failed(perkId, PerkBuyFailureReason.INSUFFICIENT_FUNDS)
                return
            }

            state.value = PerkBuyStatus.Buying(perkId)
            try {
                val result = repository.buyPerk(perkId, keyFactory())
                state.value =
                    PerkBuyStatus.Bought(
                        perkId = result.perkId,
                        newBalance = result.newBalance,
                        inventoryCount = result.inventoryCount,
                        alreadyPurchased = result.alreadyPurchased,
                    )
            } catch (cancellation: CancellationException) {
                state.value = PerkBuyStatus.Idle
                throw cancellation
            } catch (insufficient: PerkPurchaseInsufficientFundsException) {
                state.value = PerkBuyStatus.Failed(perkId, PerkBuyFailureReason.INSUFFICIENT_FUNDS)
            } catch (holdCap: PerkPurchaseHoldCapException) {
                state.value = PerkBuyStatus.Failed(perkId, PerkBuyFailureReason.HOLD_CAP)
            } catch (unavailable: PerkPurchaseUnavailableException) {
                state.value = PerkBuyStatus.Failed(perkId, PerkBuyFailureReason.UNAVAILABLE)
            } catch (failure: Exception) {
                state.value = PerkBuyStatus.Failed(perkId, PerkBuyFailureReason.UNKNOWN)
            }
        } finally {
            inFlight.set(false)
        }
    }

    /** Clears a terminal status (Bought/Failed) so the shop is fresh again. */
    fun reset() {
        if (state.value !is PerkBuyStatus.Buying) {
            state.value = PerkBuyStatus.Idle
        }
    }
}
