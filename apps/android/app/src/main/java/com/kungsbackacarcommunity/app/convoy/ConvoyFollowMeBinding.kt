package com.kungsbackacarcommunity.app.convoy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.kungsbackacarcommunity.app.map.ConvoyMemberPosition
import com.kungsbackacarcommunity.app.shell.MapPoint
import com.kungsbackacarcommunity.app.shell.MapSurface
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Production bridge from one convoy's shared document to the button, publisher
 * and map. A changed convoy/user gets a fresh observation and publisher; pending
 * observation must never borrow the previous convoy's leader or route.
 */
@Composable
fun rememberConvoyFollowMeBinding(
    repository: ConvoyFollowMeRepository?,
    convoyId: String?,
    selfUid: String,
    memberUids: Set<String>,
    ownPosition: ConvoyMemberPosition?,
    memberPositions: List<ConvoyMemberPosition>,
    mapSurface: MapSurface,
    now: () -> Long = { System.currentTimeMillis() },
): Boolean {
    val state = key(repository, convoyId, selfUid) {
        val flow = remember(repository, convoyId) {
            if (repository != null && convoyId != null) repository.observeFollowMe(convoyId)
            else flowOf(null)
        }
        flow.collectAsState(initial = null).value
    }
    val selfLeading = selfUid in memberUids && FollowMeTrail.isSelfLeading(state?.leaderUid, selfUid)
    val decoded = remember(state?.polyline) { FollowMeTrail.decode(state?.polyline) }
    val latestPosition by rememberUpdatedState(ownPosition)
    val latestNow by rememberUpdatedState(now)
    var staleTick by remember(repository, convoyId, selfUid) { mutableIntStateOf(0) }
    LaunchedEffect(repository, convoyId, state?.leaderUid) {
        if (state?.leaderUid != null) {
            while (true) {
                delay(FollowMeTrail.STALE_RECHECK_MS)
                staleTick++
            }
        }
    }
    DisposableEffect(mapSurface, repository, convoyId, selfUid) {
        onDispose { mapSurface.setFollowMeTrail(null) }
    }
    LaunchedEffect(mapSurface, state, decoded, memberUids, ownPosition, memberPositions, staleTick) {
        val leaderUid = state?.leaderUid
        val leader = if (leaderUid == selfUid) ownPosition else memberPositions.firstOrNull { it.uid == leaderUid }
        val freshMs = listOfNotNull(leader?.updatedAtMillis, state?.updatedAtMillis).maxOrNull()
        val draw = selfUid in memberUids && FollowMeTrail.shouldDraw(
            leaderUid = leaderUid,
            selfUid = selfUid,
            leaderIsMember = leaderUid != null && leaderUid in memberUids,
            lastFreshMs = freshMs,
            nowMs = now(),
        )
        mapSurface.setFollowMeTrail(if (draw) decoded else null)
    }
    LaunchedEffect(repository, convoyId, selfUid, selfLeading, state?.activationId) {
        if (selfLeading && repository != null && convoyId != null) {
            val activationId = state?.activationId
            val publisher = FollowMeTrailPublisher()
            coroutineScope {
                val snapshots = Channel<String>(Channel.CONFLATED)
                // Only this writer waits on IO. Movement keeps feeding the
                // buffer, so replacing a queued full snapshot drops an old
                // payload without dropping intermediate corners from the trail.
                launch {
                    for (encoded in snapshots) repository.writeTrail(convoyId, encoded, activationId)
                }
                try {
                    snapshotFlow { latestPosition }.collect { position ->
                        if (position != null) {
                            val encoded = publisher.onFix(MapPoint(position.longitude, position.latitude), latestNow())
                            if (encoded != null) snapshots.trySend(encoded)
                        }
                    }
                } finally {
                    // An activation change cancels the producer and writer;
                    // the captured id guards any old Firestore task that survives.
                    snapshots.close()
                }
            }
        }
    }
    return selfLeading
}
