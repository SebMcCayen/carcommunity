package com.kungsbackacarcommunity.app.crownhunt

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
/** Callback delivery and clock expiry are independent: a stalled GPS cannot freeze readiness. */
@Composable
fun rememberCrownReadiness(
    context: Context,
    uid: String?,
    active: Boolean,
    crown: CrownSpawn?,
    nearby: List<CrownSpawn>,
    answered: Boolean,
    ambient: CrownFix? = null,
): Pair<CrownReadinessSession, CrownReadinessSnapshot> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val session = remember(uid) { CrownReadinessSession() }
    var snapshot by remember(session) { mutableStateOf(CrownReadinessSnapshot()) }
    val currentCrown by rememberUpdatedState(crown)
    val currentNearby by rememberUpdatedState(nearby)
    val shouldPrewarm = nearby.isNotEmpty()
    LaunchedEffect(session, active, ambient) {
        val now = System.currentTimeMillis()
        if (active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ambient != null &&
            now - ambient.recordedAtMillis in 0..CrownSpawnLimits.MAX_POSITION_AGE_SECONDS * 1000) {
            session.record(ambient)
            snapshot = session.snapshot(now, currentCrown)
        }
    }
    LaunchedEffect(session, lifecycle, active, crown?.id, shouldPrewarm, answered) {
        if (!active) {
            session.clear()
            snapshot = CrownReadinessSnapshot()
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                // Subscribe to lifecycle cancellation itself: a stopped recomposer
                // must not be responsible for releasing a GPS callback.
                snapshot = session.snapshot(System.currentTimeMillis(), currentCrown)
                if (answered || (currentCrown == null && currentNearby.isEmpty())) return@repeatOnLifecycle
                runCrownReadiness(
                    session, open = currentCrown != null,
                    crown = { currentCrown }, nearby = { currentNearby },
                    nowMillis = System::currentTimeMillis,
                    updates = { highAccuracy -> CrownLocation.updates(context, highAccuracy) },
                    publish = { snapshot = it },
                )
            } finally {
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    session.clear()
                    snapshot = CrownReadinessSnapshot()
                }
            }
        }
    }
    return session to snapshot
}
