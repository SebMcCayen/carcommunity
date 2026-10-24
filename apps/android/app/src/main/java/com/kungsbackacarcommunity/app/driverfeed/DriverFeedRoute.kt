package com.kungsbackacarcommunity.app.driverfeed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.design.KccSpacing
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onEach

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
    val coordinator = remember(repository, uid) { DriverFeedCoordinator(repository, uid, scope) }
    val state by coordinator.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(coordinator) { onDispose { coordinator.stop() } }
    LaunchedEffect(coordinator, state.selectedId, isActive, lifecycle) {
        if (!isActive) { coordinator.pause(); return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                if (repository == null || uid == null) coordinator.refresh()
                else repository.invalidations(uid, state.selectedId).onEach { coordinator.invalidate() }
                    .debounce(150).collect { coordinator.refresh() }
            } finally { coordinator.pause() }
        }
    }
    BackHandler(state.selectedId != null) { coordinator.back() }
    DriverFeedContent(state, strings, modifier, coordinator::back, { coordinator.refresh() },
        coordinator::more, coordinator::open)
}

@Composable
private fun defaultDriverFeedRepository(): DriverFeedRepository? {
    val context = LocalContext.current.applicationContext
    return remember(context) { FirebaseDriverFeedRepository.createIfAvailable(context) }
}

@Composable
fun DriverFeedContent(
    state: DriverFeedState, strings: DriverFeedStrings, modifier: Modifier = Modifier,
    onBack: () -> Unit, onRefresh: () -> Unit, onMore: () -> Unit, onOpen: (String) -> Unit,
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
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                state.failure?.let { failure ->
                    item {
                        Column(Modifier.padding(KccSpacing.s4)) {
                            Text(strings[when (failure) {
                                DriverFeedFailure.ERROR -> "error"
                                DriverFeedFailure.UNAVAILABLE -> "unavailable"
                                DriverFeedFailure.ONBOARDING -> "onboarding"
                                DriverFeedFailure.CANONICAL_ONBOARDING -> "canonicalOnboarding"
                                DriverFeedFailure.RESTRICTED -> "restricted"
                            }])
                            TextButton(onClick = onRefresh) { Text(strings["retry"]) }
                        }
                    }
                }
                if (state.selectedId == null) {
                    items(state.posts, key = { it.id }) { post ->
                        Card(onClick = { onOpen(post.id) }, modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = KccSpacing.s3)) {
                            DriverPostBody(post, strings)
                        }
                    }
                    if (!state.loading && state.failure == null && state.posts.isEmpty()) item {
                        Text(strings["empty"], Modifier.padding(KccSpacing.s4))
                    }
                } else state.post?.let { post ->
                    item { DriverPostBody(post, strings) }
                    item { Text(strings["comments"], Modifier.padding(KccSpacing.s4).semantics { heading() },
                        style = MaterialTheme.typography.titleMedium) }
                    items(state.comments, key = { it.id }) { comment ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = KccSpacing.s4),
                            verticalArrangement = Arrangement.spacedBy(KccSpacing.s1)) {
                            Text(comment.author, style = MaterialTheme.typography.titleSmall)
                            comment.createdAtMillis?.let { Text(driverDate(it), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text(comment.text, style = MaterialTheme.typography.bodyLarge)
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
private fun DriverPostBody(post: DriverPost, strings: DriverFeedStrings) {
    Column(Modifier.fillMaxWidth().padding(KccSpacing.s4), verticalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
        Text(post.author, style = MaterialTheme.typography.titleMedium)
        Text(listOfNotNull(post.createdAtMillis?.let(::driverDate), strings["source"]).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(post.text, style = MaterialTheme.typography.bodyLarge)
        post.mediaUrls.forEach { url ->
            AsyncImage(model = url, contentDescription = strings["image"], contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = KccSpacing.s12 * 8))
        }
        Text("${strings["likes"]}: ${post.likes} · ${strings["comments"]}: ${post.replies}",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun driverDate(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    .format(Date(millis))
