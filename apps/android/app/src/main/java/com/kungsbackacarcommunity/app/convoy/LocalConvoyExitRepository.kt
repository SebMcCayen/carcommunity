package com.kungsbackacarcommunity.app.convoy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LocalConvoyExits(
    val pending: Set<String> = emptySet(),
    val completed: Set<String> = emptySet(),
)

/** Shared by the map and detail coordinators, scoped to the signed-in user. */
class LocalConvoyExitRepository(
    private val delegate: ConvoyRepository,
) : ConvoyRepository by delegate {
    private val exits = MutableStateFlow(LocalConvoyExits())
    val localExits = exits.asStateFlow()

    override suspend fun end(convoyId: String): ConvoyMutationResult = exit(convoyId) {
        val result = delegate.end(convoyId)
        result to (result is ConvoyMutationResult.Updated)
    }

    override suspend fun leave(convoyId: String): LeaveConvoyResult = exit(convoyId) {
        val result = delegate.leave(convoyId)
        result to (result is LeaveConvoyResult.Left)
    }

    override suspend fun respond(convoyId: String, accept: Boolean): ConvoyMutationResult {
        // A new auto-session may arrive before a rejoin callable returns.
        if (accept) exits.update { it.copy(completed = it.completed - convoyId) }
        return delegate.respond(convoyId, accept)
    }

    private suspend fun <T> exit(convoyId: String, command: suspend () -> Pair<T, Boolean>): T {
        exits.update { it.copy(pending = it.pending + convoyId) }
        try {
            val (result, succeeded) = command()
            // Publish success before releasing the pending fence: listener echoes
            // must never get a frame in which this looks like somebody else's end.
            if (succeeded) exits.update { it.copy(completed = it.completed + convoyId) }
            return result
        } finally {
            exits.update { it.copy(pending = it.pending - convoyId) }
        }
    }
}
