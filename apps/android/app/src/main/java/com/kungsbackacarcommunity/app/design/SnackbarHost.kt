package com.kungsbackacarcommunity.app.design

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.compositionLocalOf

/**
 * Shell-provided [SnackbarHostState] for transient, non-blocking failure
 * feedback. A screen deep in the tree can surface a one-off message via
 * `LocalSnackbarHostState.current` without owning a Scaffold of its own.
 *
 * Defaults to `null` (no host attached). Only the
 * [AuthenticatedApp][com.kungsbackacarcommunity.app.AuthenticatedApp] shell,
 * which renders a real [androidx.compose.material3.SnackbarHost], provides a
 * non-null instance. Callers must null-guard: when the local is `null`
 * (previews, isolated UI tests, or any screen rendered outside the shell) they
 * skip showing, so messages truly go nowhere.
 *
 * A non-null default is deliberately avoided: calling `showSnackbar()` on a
 * [SnackbarHostState] that is not attached to a rendered `SnackbarHost`
 * suspends until dismissed — with no host to dismiss it, the coroutine would
 * hang indefinitely rather than no-op.
 */
val LocalSnackbarHostState = compositionLocalOf<SnackbarHostState?> { null }
