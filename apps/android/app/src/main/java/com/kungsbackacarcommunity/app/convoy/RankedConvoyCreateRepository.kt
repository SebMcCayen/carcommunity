package com.kungsbackacarcommunity.app.convoy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Accounts successful create AND invite-more calls before ConvoyCoordinator
 * publishes Created/Done (which can dismiss the picker and cancel its scope).
 * No accounting comes from selected sets or convoy-list/history snapshots.
 * Other operations delegate unchanged. Wrap the raw repository once per
 * coordinator, rather than stacking decorators or recording again on Done.
 * Backend invite retries with invited=[] are no-ops; do not infer success from
 * the returned convoy roster, where already-invited members still appear.
 */
class RankedConvoyCreateRepository(
    private val delegate: ConvoyRepository,
    private val usage: ConvoyInviteUsageStore,
    private val ownerUid: String?,
    private val currentUid: () -> String?,
) : ConvoyRepository by delegate {
    override suspend fun create(inviteeUids: List<String>, title: String?, vehicleId: String?): CreateConvoyResult {
        val result = delegate.create(inviteeUids, title, vehicleId)
        accountSuccessfulInvites(inviteeUids, result)
        return result
    }

    override suspend fun invite(convoyId: String, inviteeUids: List<String>): CreateConvoyResult {
        val result = delegate.invite(convoyId, inviteeUids)
        accountSuccessfulInvites(inviteeUids, result)
        return result
    }

    private suspend fun accountSuccessfulInvites(requested: List<String>, result: CreateConvoyResult) {
        currentCoroutineContext().ensureActive()
        if (result is CreateConvoyResult.Created && !ownerUid.isNullOrBlank() && currentUid() == ownerUid) {
            // Use only server-confirmed, requested invitees, once per submit.
            val requestedUids = requested.toSet()
            val successful = result.invited.distinct().filter {
                it.isNotBlank() && it in requestedUids && it != ownerUid
            }
            try {
                withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    if (successful.isNotEmpty() && currentUid() == ownerUid) usage.record(ownerUid, successful)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A cosmetic local hint must never turn backend success into a failure.
            }
        }
    }
}
