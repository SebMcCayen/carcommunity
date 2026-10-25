package com.kungsbackacarcommunity.app.driverfeed

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DriverFeedState(
    val posts: List<DriverPost> = emptyList(),
    val selectedId: String? = null,
    val post: DriverPost? = null,
    val comments: List<DriverComment> = emptyList(),
    val next: DriverCursor? = null,
    val loading: Boolean = false,
    val failure: DriverFeedFailure? = null,
)

data class DriverFeedViewport(val index: Int = 0, val offset: Int = 0)

/** A tab/foreground return spends at most two authorized page reads (40 candidates). */
const val DRIVER_RESTORE_MAX_PAGES = 2

/** Clamp to readable rows, excluding transient loading and the More control. */
fun clampedDriverViewport(state: DriverFeedState, viewport: DriverFeedViewport): DriverFeedViewport {
    val lastIndex = if (state.selectedId == null) (state.posts.size - 1).coerceAtLeast(0)
        else 1 + state.comments.size.coerceAtLeast(1) // Post, comments heading, comments/empty notice.
    val index = viewport.index.coerceIn(0, lastIndex)
    return DriverFeedViewport(index, if (index == viewport.index) viewport.offset.coerceAtLeast(0) else 0)
}

/** Every navigation/access reset fences even repositories that complete after cancellation. */
class DriverFeedCoordinator(
    private val repository: DriverFeedRepository?,
    private val uid: String?,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(DriverFeedState())
    val state: StateFlow<DriverFeedState> = mutable
    private var job: Job? = null
    private var generation = 0L
    private var feedPages = 1
    private var detailPages = 1
    private val viewports = mutableMapOf<String?, DriverFeedViewport>()

    fun viewport(selected: String? = mutable.value.selectedId): DriverFeedViewport =
        viewports[selected] ?: DriverFeedViewport()

    fun recordViewport(selected: String?, index: Int, offset: Int) {
        viewports[selected] = DriverFeedViewport(index.coerceAtLeast(0), offset.coerceAtLeast(0))
    }

    fun open(id: String) {
        detailPages = 1
        viewports.remove(id)
        refresh(id)
    }
    fun back() {
        pause()
        mutable.value = DriverFeedState()
        resume()
    }
    fun invalidate() {
        job?.cancel()
        generation++
        mutable.value = DriverFeedState(selectedId = mutable.value.selectedId, loading = true)
    }
    fun refresh(selected: String? = mutable.value.selectedId) {
        if (selected == null) feedPages = 1 else detailPages = 1
        viewports.remove(selected)
        reload(selected, 1)
    }

    /** Reauthorize a small window; deeper history remains available through explicit More. */
    fun resume() {
        val selected = mutable.value.selectedId
        reload(selected, (if (selected == null) feedPages else detailPages).coerceAtMost(DRIVER_RESTORE_MAX_PAGES))
    }

    private fun reload(selected: String?, pages: Int) {
        job?.cancel()
        generation++
        mutable.value = DriverFeedState(selectedId = selected)
        load(false, pages)
    }

    fun more() {
        if (!mutable.value.loading && mutable.value.next != null) load(true)
    }

    fun stop() {
        pause()
        feedPages = 1
        detailPages = 1
        viewports.clear()
        mutable.value = DriverFeedState()
    }

    fun pause() {
        generation++
        job?.cancel()
        mutable.value = DriverFeedState(selectedId = mutable.value.selectedId)
    }

    private fun load(more: Boolean, pages: Int = 1) {
        val repository = repository
        val uid = uid
        if (repository == null || uid == null) {
            mutable.value = DriverFeedState(failure = DriverFeedFailure.RESTRICTED)
            return
        }
        val previous = mutable.value
        val ticket = generation
        mutable.value = previous.copy(loading = true, failure = null)
        job = scope.launch {
            try {
                var loadedPages = 0
                val nextState = if (previous.selectedId == null) {
                    val posts = (if (more) previous.posts else emptyList()).toMutableList()
                    var cursor = if (more) previous.next else null
                    do {
                        val page = repository.feed(uid, cursor)
                        if (generation != ticket) return@launch
                        posts += page.items
                        cursor = page.next
                        loadedPages++
                    } while (loadedPages < pages && cursor != null)
                    previous.copy(posts = posts.distinctBy { it.id }, next = cursor, loading = false)
                } else {
                    val comments = (if (more) previous.comments else emptyList()).toMutableList()
                    var cursor = if (more) previous.next else null
                    var post: DriverPost
                    do {
                        val detail = repository.detail(uid, previous.selectedId, cursor)
                        if (generation != ticket) return@launch
                        post = detail.post
                        comments += detail.comments.items
                        cursor = detail.comments.next
                        loadedPages++
                    } while (loadedPages < pages && cursor != null)
                    previous.copy(post = post, comments = comments.distinctBy { it.id },
                        next = cursor, loading = false)
                }
                if (generation == ticket) {
                    if (previous.selectedId == null) feedPages = if (more) feedPages + loadedPages else loadedPages
                    else detailPages = if (more) detailPages + loadedPages else loadedPages
                    mutable.value = nextState
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == ticket) mutable.value = DriverFeedState(
                    selectedId = previous.selectedId,
                    failure = (error as? DriverFeedException)?.failure ?: DriverFeedFailure.ERROR,
                )
            }
        }
    }
}
