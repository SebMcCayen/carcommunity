package com.kungsbackacarcommunity.app

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.kungsbackacarcommunity.app.auth.AuthState
import com.kungsbackacarcommunity.app.auth.SignInScreen
import com.kungsbackacarcommunity.app.auth.SignInStatus
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.home.HomeScreen

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
) {
    KccTheme {
        when (authState) {
            AuthState.SignedOut ->
                SignInScreen(status = signInStatus, onSignInClick = onSignInClick)

            is AuthState.SignedIn ->
                HomeScreen(displayName = authState.displayName, onSignOut = onSignOutClick)

            AuthState.Unavailable ->
                HomeScreen(displayName = null, onSignOut = null)
        }
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
