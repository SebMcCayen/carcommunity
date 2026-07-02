package com.kungsbackacarcommunity.app

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.kungsbackacarcommunity.app.auth.AuthState
import com.kungsbackacarcommunity.app.auth.SignInScreen
import com.kungsbackacarcommunity.app.auth.SignInStatus
import com.kungsbackacarcommunity.app.design.KccTheme

/**
 * App shell (migration plan Phases 5–7).
 *
 * Themed with KCC Crown UI tokens (contracts/design-tokens/tokens.json) via
 * [KccTheme] — light, dark, and system-adaptive.
 *
 * Phase 7 adds the sign-in gate: a signed-out user sees [SignInScreen];
 * a signed-in user (or a build without Firebase configured, i.e.
 * [AuthState.Unavailable]) sees the placeholder home shell. Real navigation
 * arrives with the first feature slices.
 */
@Composable
fun AppRoot(
    authState: AuthState = AuthState.Unavailable,
    signInStatus: SignInStatus = SignInStatus.Idle,
    onSignInClick: () -> Unit = {},
) {
    KccTheme {
        when (authState) {
            AuthState.SignedOut ->
                SignInScreen(status = signInStatus, onSignInClick = onSignInClick)

            AuthState.Unavailable,
            is AuthState.SignedIn,
            -> PlaceholderHome()
        }
    }
}

@Composable
private fun PlaceholderHome() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun AppRootPreviewLight() {
    AppRoot()
}

@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppRootPreviewDark() {
    AppRoot()
}

@Preview(name = "Signed out", showBackground = true)
@Composable
private fun AppRootPreviewSignedOut() {
    AppRoot(authState = AuthState.SignedOut)
}
