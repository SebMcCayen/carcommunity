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

    fun open(id: String) { refresh(id) }
    fun back() { refresh(null) }
    fun invalidate() {
        job?.cancel()
        generation++
        mutable.value = DriverFeedState(selectedId = mutable.value.selectedId, loading = true)
    }
    fun refresh(selected: String? = mutable.value.selectedId) {
        job?.cancel()
        generation++
        mutable.value = DriverFeedState(selectedId = selected)
        load(false)
    }

    fun more() {
        if (!mutable.value.loading && mutable.value.next != null) load(true)
    }

    fun stop() {
        pause()
        mutable.value = DriverFeedState()
    }

    fun pause() {
        generation++
        job?.cancel()
        mutable.value = DriverFeedState(selectedId = mutable.value.selectedId)
    }

    private fun load(more: Boolean) {
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
                val nextState = if (previous.selectedId == null) {
                    val page = repository.feed(uid, if (more) previous.next else null)
                    previous.copy(posts = ((if (more) previous.posts else emptyList()) + page.items).distinctBy { it.id },
                        next = page.next, loading = false)
                } else {
                    val detail = repository.detail(uid, previous.selectedId, if (more) previous.next else null)
                    val page = detail.comments
                    previous.copy(post = detail.post,
                        comments = ((if (more) previous.comments else emptyList()) + page.items).distinctBy { it.id },
                        next = page.next, loading = false)
                }
                if (generation == ticket) mutable.value = nextState
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
