package com.kungsbackacarcommunity.app.crownhunt

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Structured demand: cancellation tears down both the provider and the independent clock. */
suspend fun runCrownReadiness(
    session: CrownReadinessSession,
    open: Boolean,
    crown: () -> CrownSpawn?,
    nearby: () -> List<CrownSpawn>,
    nowMillis: () -> Long,
    updates: (Boolean) -> Flow<CrownFix>,
    publish: (CrownReadinessSnapshot) -> Unit,
) = coroutineScope {
    fun emit() { publish(session.snapshot(nowMillis(), crown())) }
    emit()
    val clock = if (open) launch {
        while (isActive) { emit(); delay(CrownReadinessSession.READINESS_TICK_MS) }
    } else null
    try {
        if (open) {
            while (isActive) {
                updates(true).collect { fix -> session.record(fix); emit() }
                delay(CrownReadinessSession.PREWARM_RETRY_MS)
            }
        } else {
            while (isActive) {
                val pause = session.prewarmPauseMillis(nowMillis(), nearby())
                if (pause > 0) { delay(pause); continue }
                withTimeoutOrNull(CrownReadinessSession.PREWARM_BURST_MS) {
                    updates(false).firstOrNull { fix ->
                        session.record(fix)
                        emit()
                        CrownCollectGate.isMoving(fix.speedMetersPerSecond) ||
                            session.prewarmPauseMillis(nowMillis(), nearby()) > 0
                    }
                }
                if (session.prewarmPauseMillis(nowMillis(), nearby()) == 0L) {
                    delay(CrownReadinessSession.PREWARM_RETRY_MS)
                }
            }
        }
    } finally { clock?.cancel() }
}
