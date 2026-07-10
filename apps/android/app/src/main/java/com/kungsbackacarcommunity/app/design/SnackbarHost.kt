package com.kungsbackacarcommunity.app.design

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.compositionLocalOf

/**
 * Shell-provided [SnackbarHostState] for transient, non-blocking failure
 * feedback. A screen deep in the tree can surface a one-off message via
 * `LocalSnackbarHostState.current` without owning a Scaffold of its own.
 *
 * Defaults to a detached state (no host attached) so previews and isolated
 * UI tests that render a single screen never crash — messages simply go
 * nowhere until the [AuthenticatedApp][com.kungsbackacarcommunity.app.AuthenticatedApp]
 * shell provides a host-backed instance.
 */
val LocalSnackbarHostState = compositionLocalOf { SnackbarHostState() }
