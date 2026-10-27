package com.kungsbackacarcommunity.app.convoy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Keeps success accounting before ConvoyCoordinator publishes Created (which
 * immediately dismisses the route and cancels its scope). No accounting is done
 * from the selected set or an incomplete convoy-list/history snapshot.
 * All non-create operations delegate unchanged to the existing repository.
 */
class RankedConvoyCreateRepository(
    private val delegate: ConvoyRepository,
    private val usage: ConvoyInviteUsageStore,
    private val ownerUid: String?,
    private val currentUid: () -> String?,
) : ConvoyRepository by delegate {
    override suspend fun create(inviteeUids: List<String>, title: String?, vehicleId: String?): CreateConvoyResult {
        val result = delegate.create(inviteeUids, title, vehicleId)
        currentCoroutineContext().ensureActive()
        if (result is CreateConvoyResult.Created && !ownerUid.isNullOrBlank() && currentUid() == ownerUid) {
            // Use only server-confirmed, requested invitees, once per submit.
            val successful = result.invited.distinct().filter { it in inviteeUids && it != ownerUid }
            try {
                withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    if (successful.isNotEmpty() && currentUid() == ownerUid) usage.record(ownerUid, successful)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A cosmetic local hint must never turn a successful create into a failure.
            }
        }
        return result
    }
}
