package com.kungsbackacarcommunity.app.firebase

import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Minimal `Task` -> suspend bridges (no kotlinx-coroutines-play-services dep).
 *
 * The app deliberately does not depend on `kotlinx-coroutines-play-services`
 * (which would supply `Task.await()`): the two bridges below are all the app
 * needs, and both are short enough that the dependency would buy nothing but
 * another Play-services artifact in the graph.
 *
 * Both guard on [kotlinx.coroutines.CancellableContinuation.isActive] before
 * resuming: a Task listener can fire after the awaiting coroutine was already
 * cancelled, and resuming twice (or resuming a cancelled continuation) throws.
 *
 * There are two of them because the two Task callback styles do NOT agree on
 * what a *cancelled* Task means, and that difference is load-bearing:
 *
 * - [await] uses success/failure listeners. A cancelled Task fires **neither**,
 *   so the caller stays suspended until its own coroutine is cancelled.
 * - [awaitOrThrow] uses a completion listener, which *does* fire for a cancelled
 *   Task — with `isSuccessful == false` and a **null** `exception`. That is the
 *   case [fallbackMessage] exists for.
 *
 * Pick the one matching the semantics the call site wants; they are not
 * interchangeable.
 */

/**
 * Awaits this Task via success/failure listeners, returning its result or
 * throwing the Task's failure.
 *
 * A cancelled Task never resumes the caller (see the file KDoc). Prefer
 * [awaitOrThrow] when the call site must observe cancellation as a failure.
 */
internal suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) continuation.resume(result)
        }.addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

/**
 * Awaits this Task via a completion listener, returning its result or throwing.
 *
 * On failure the Task's own exception is thrown; a Task that completed
 * unsuccessfully with no exception (i.e. it was cancelled) throws an
 * [IllegalStateException] carrying [fallbackMessage], so the call site never
 * hangs and never reports a causeless failure.
 */
internal suspend fun <T> Task<T>.awaitOrThrow(fallbackMessage: () -> String): T =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            if (task.isSuccessful) {
                continuation.resume(task.result)
            } else {
                continuation.resumeWithException(
                    task.exception ?: IllegalStateException(fallbackMessage()),
                )
            }
        }
    }
