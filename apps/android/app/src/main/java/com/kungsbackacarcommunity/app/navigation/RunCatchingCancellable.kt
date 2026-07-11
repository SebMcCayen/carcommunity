package com.kungsbackacarcommunity.app.navigation

import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching] but never swallows coroutine cancellation.
 *
 * Plain `runCatching { … }` in a suspend/coroutine context also catches
 * [CancellationException], which breaks structured concurrency: the coroutine's
 * cancellation is turned into a `Result.failure` (usually observed downstream as
 * a `null`/default), so a cancelled coroutine appears to "finish" with no result
 * instead of unwinding — and any child work it launched keeps running.
 *
 * Use this at every call site whose block can suspend (a suspend call,
 * `suspendCancellableCoroutine`, `withContext`, …) so a *real* failure still
 * yields `Result.failure` (and the same `null`/default as before) while
 * cancellation propagates untouched. It is `inline`, so suspend calls inside the
 * block are legal exactly as they are inside stdlib `runCatching`.
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
