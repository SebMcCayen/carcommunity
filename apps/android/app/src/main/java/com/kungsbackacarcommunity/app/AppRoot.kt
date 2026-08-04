package com.kungsbackacarcommunity.app

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import com.kungsbackacarcommunity.app.auth.AuthState
import com.kungsbackacarcommunity.app.auth.SignInScreen
import com.kungsbackacarcommunity.app.auth.SignInStatus
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.design.LocalThemeController
import com.kungsbackacarcommunity.app.design.ThemeController
import com.kungsbackacarcommunity.app.home.HomeScreen
import com.kungsbackacarcommunity.app.map.LocalMapZoomController
import com.kungsbackacarcommunity.app.map.MapZoomController

/**
 * App shell (migration plan Phases 5–7, extended in Phase 12 slice 1).
 *
 * Themed with KCC Crown UI tokens (contracts/design-tokens/tokens.json) via
 * [KccTheme] — light, dark, and system-adaptive.
 *
 * Auth-state-driven top-level navigation: a signed-out user sees
 * [SignInScreen]; a signed-in user sees [HomeScreen] with a sign-out
 * action; a build without Firebase configured ([AuthState.Unavailable],
 * i.e. CI/validation builds) renders the home shell without sign-out so it
 * never crashes. In-app navigation between feature destinations arrives
 * with later slices, once there is more than one authenticated screen.
 */
@Composable
fun AppRoot(
    authState: AuthState = AuthState.Unavailable,
    signInStatus: SignInStatus = SignInStatus.Idle,
    onSignInClick: () -> Unit = {},
    onSignOutClick: () -> Unit = {},
    // The signed-in experience is injected by MainActivity (onboarding gate +
    // Home/Profile). The default renders the home shell directly so previews
    // and pure UI tests need no repositories.
    signedInContent: @Composable (uid: String, displayName: String?) -> Unit =
        { _, displayName -> HomeScreen(displayName = displayName, onSignOut = onSignOutClick) },
    // The resolved app-wide darkness: the user's ThemePreference applied to the
    // current system setting (see MainActivity). Passed in rather than computed
    // here so there is exactly ONE place the app decides light vs. dark.
    // Defaults to the system value so previews and UI tests that don't wire a
    // preference behave as before.
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Read/write access to the theme preference for the Settings screen, which
    // sits several levels down inside the shell's route host. Provided here
    // because this composable is already the app's theme boundary; null (the
    // default, used by previews and UI tests) leaves the no-op controller from
    // LocalThemeController in place.
    themeController: ThemeController? = null,
    // Read/write access to the resting map-zoom preference for the map-layers
    // popup, which — like Settings for the theme — sits several levels down inside
    // the shell. Provided here alongside the theme controller because this is
    // already the app's ambient-preference boundary; null (the default, previews
    // and UI tests) leaves the no-op controller from LocalMapZoomController in place.
    mapZoomController: MapZoomController? = null,
) {
    val content: @Composable () -> Unit = {
        KccTheme(darkTheme = darkTheme) {
            when (authState) {
                AuthState.SignedOut ->
                    SignInScreen(status = signInStatus, onSignInClick = onSignInClick)

                is AuthState.SignedIn ->
                    signedInContent(authState.uid, authState.displayName)

                AuthState.Unavailable ->
                    HomeScreen(displayName = null, onSignOut = null)
            }
        }
    }
    // Provide whichever ambient preference controllers MainActivity wired; each is
    // independent, so previews that pass neither still render with the no-op
    // defaults. Applied outermost-first (order is irrelevant — different keys).
    val themed: @Composable () -> Unit =
        if (themeController == null) {
            content
        } else {
            { CompositionLocalProvider(LocalThemeController provides themeController, content = content) }
        }
    if (mapZoomController == null) {
        themed()
    } else {
        CompositionLocalProvider(LocalMapZoomController provides mapZoomController, content = themed)
    }
}

@Preview(name = "Signed in", showBackground = true)
@Composable
private fun AppRootPreviewSignedIn() {
    AppRoot(authState = AuthState.SignedIn(uid = "preview", displayName = "Sebbe"))
}

@Preview(name = "Signed in – dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppRootPreviewSignedInDark() {
    AppRoot(authState = AuthState.SignedIn(uid = "preview", displayName = null))
}

@Preview(name = "Signed out", showBackground = true)
@Composable
private fun AppRootPreviewSignedOut() {
    AppRoot(authState = AuthState.SignedOut)
}
