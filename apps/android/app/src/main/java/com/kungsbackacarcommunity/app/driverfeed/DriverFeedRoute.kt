package com.kungsbackacarcommunity.app.driverfeed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import com.kungsbackacarcommunity.app.design.KccOutlinedButton as OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.design.KccSpacing
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onEach

/** The chat host retains navigation only until it unmounts or its UID changes. */
val LocalDriverFeedSessionOwner = staticCompositionLocalOf<DriverFeedSessionOwner?> { null }

/** Opaque native reading surface. The host owns the Chattar/Driver switch and localized keys. */
@Composable
fun DriverFeedRoute(
    uid: String?,
    strings: DriverFeedStrings,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    repository: DriverFeedRepository? = defaultDriverFeedRepository(),
) {
    key(uid, repository) { DriverFeedSession(uid, strings, modifier, isActive, repository) }
}

@Composable
@OptIn(FlowPreview::class)
private fun DriverFeedSession(uid: String?, strings: DriverFeedStrings, modifier: Modifier, isActive: Boolean,
    repository: DriverFeedRepository?) {
    val scope = rememberCoroutineScope()
    val owner = LocalDriverFeedSessionOwner.current
    val coordinator = remember(repository, uid, owner) {
        owner?.coordinator(uid, repository) ?: DriverFeedCoordinator(repository, uid, scope)
    }
    val state by coordinator.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(coordinator, owner) {
        onDispose { if (owner == null) coordinator.stop() else coordinator.pause() }
    }
    LaunchedEffect(coordinator, state.selectedId, isActive, lifecycle) {
        if (!isActive) { coordinator.pause(); return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                if (repository == null || uid == null) {
                    coordinator.refresh()
                    // Keep the recovery state visible until lifecycle/host cancellation.
                    awaitCancellation()
                }
                else repository.invalidations(uid, state.selectedId).onEach { coordinator.invalidate() }
                    .debounce(150).collect { coordinator.resume() }
            } finally { coordinator.pause() }
        }
    }
    BackHandler(state.selectedId != null) { coordinator.back() }
    val selected = state.selectedId
    val viewport = coordinator.viewport(selected)
    val listState = remember(coordinator, selected) { LazyListState(viewport.index, viewport.offset) }
    val readable = !state.loading && state.failure == null &&
        (state.posts.isNotEmpty() || state.post != null)
    LaunchedEffect(coordinator, selected, readable) {
        if (readable) {
            // Restore only after reauthorization has rebuilt the old paging window;
            // a temporary loading row must not clamp the retained viewport to zero.
            val target = clampedDriverViewport(state, coordinator.viewport(selected))
            // A deeper history viewport clamps to the last restored item. Resume
            // never replays arbitrary history against the shared hourly read quota.
            listState.scrollToItem(target.index, target.offset)
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (index, offset) -> coordinator.recordViewport(selected, index, offset) }
        }
    }
    DriverFeedContent(state, strings, modifier, coordinator::back, { coordinator.refresh() },
        coordinator::more, coordinator::open, listState)
}

@Composable
private fun defaultDriverFeedRepository(): DriverFeedRepository? {
    val context = LocalContext.current.applicationContext
    val owner = LocalDriverFeedSessionOwner.current
    return remember(context, owner) {
        if (owner == null) FirebaseDriverFeedRepository.createIfAvailable(context)
        else owner.defaultRepository { FirebaseDriverFeedRepository.createIfAvailable(context) }
    }
}

@Composable
fun DriverFeedContent(
    state: DriverFeedState, strings: DriverFeedStrings, modifier: Modifier = Modifier,
    onBack: () -> Unit, onRefresh: () -> Unit, onMore: () -> Unit, onOpen: (String) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    onOpenDriver: (String) -> Boolean = driverPostLauncher(),
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = KccSpacing.s2),
                horizontalArrangement = Arrangement.SpaceBetween) {
                if (state.selectedId != null) TextButton(onClick = onBack) { Text(strings["back"]) }
                else Text(strings["title"], Modifier.padding(KccSpacing.s3).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onRefresh, enabled = !state.loading) { Text(strings["refresh"]) }
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState,
                contentPadding = PaddingValues(horizontal = KccSpacing.s4, vertical = KccSpacing.s3),
                verticalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                state.failure?.let { failure ->
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.padding(KccSpacing.s4), verticalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                                Text(strings[when (failure) {
                                    DriverFeedFailure.ERROR -> "error"
                                    DriverFeedFailure.UNAVAILABLE -> "unavailable"
                                    DriverFeedFailure.ONBOARDING -> "onboarding"
                                    DriverFeedFailure.CANONICAL_ONBOARDING -> "canonicalOnboarding"
                                    DriverFeedFailure.RESTRICTED -> "restricted"
                                }])
                                OutlinedButton(onClick = onRefresh) { Text(strings["retry"]) }
                            }
                        }
                    }
                }
                if (state.selectedId == null) {
                    items(state.posts, key = { it.id }) { post ->
                        Card(onClick = { onOpen(post.id) }, modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            DriverPostBody(post, strings, onOpenDriver)
                        }
                    }
                    if (!state.loading && state.failure == null && state.posts.isEmpty()) item {
                        Text(strings["empty"], Modifier.padding(KccSpacing.s4))
                    }
                } else state.post?.let { post ->
                    item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        DriverPostBody(post, strings, onOpenDriver)
                    } }
                    item { Text(strings["comments"], Modifier.padding(KccSpacing.s4).semantics { heading() },
                        style = MaterialTheme.typography.titleMedium) }
                    items(state.comments, key = { it.id }) { comment ->
                        Row(Modifier.fillMaxWidth().padding(vertical = KccSpacing.s2),
                            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                            DriverAuthorIcon()
                            Column(Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(KccSpacing.s1)) {
                                Text(comment.author, style = MaterialTheme.typography.titleSmall)
                                comment.createdAtMillis?.let { Text(driverDate(it), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text(comment.text, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    if (!state.loading && state.comments.isEmpty()) item {
                        Text(strings["noComments"], Modifier.padding(KccSpacing.s4))
                    }
                }
                if (state.loading) item {
                    Row(Modifier.padding(KccSpacing.s4), horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                        CircularProgressIndicator()
                        Text(strings["loading"])
                    }
                }
                if (!state.loading && state.next != null) item {
                    TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text(strings["more"]) }
                }
            }
        }
    }
}

@Composable
private fun DriverPostBody(post: DriverPost, strings: DriverFeedStrings, onOpenDriver: (String) -> Boolean) {
    var appOpenFailed by remember(post.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(KccSpacing.s4), verticalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3), verticalAlignment = Alignment.CenterVertically) {
            DriverAuthorIcon()
            Column(Modifier.weight(1f)) {
                Text(post.author, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(post.createdAtMillis?.let(::driverDate), strings["source"]).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(post.text, style = MaterialTheme.typography.bodyLarge)
        post.mediaUrls.forEach { url ->
            AsyncImage(model = url, contentDescription = strings["image"], contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = KccSpacing.s12 * 8)
                    .clip(MaterialTheme.shapes.medium))
        }
        Text("${strings["likes"]}: ${post.likes} · ${strings["comments"]}: ${post.replies}",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { appOpenFailed = !onOpenDriver(post.id) }) { Text(strings["openPost"]) }
        Text(strings["postLinkHint"], style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (appOpenFailed) Text(strings["postOpenError"],
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
}

/** The read contract has no avatar URL; show a native fallback instead of a sample portrait. */
@Composable
private fun DriverAuthorIcon() {
    Box(Modifier.size(48.dp).border(1.dp, MaterialTheme.colorScheme.tertiary, CircleShape)
        .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun driverDate(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    .format(Date(millis))
