package com.kungsbackacarcommunity.app.auth

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme

/**
 * Minimal sign-in scaffold (migration plan Phase 7, PR 7c).
 *
 * Google Sign-In per docs/auth-mobile-requirements.md. All copy comes from
 * generated string resources (contracts/localization). Wrap in [KccTheme]
 * at the call site.
 */
@Composable
fun SignInScreen(
    status: SignInStatus,
    onSignInClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.auth_loginTitle),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.auth_loginSubtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            when (status) {
                SignInStatus.InProgress -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.auth_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is SignInStatus.Failed -> {
                    Text(
                        text =
                            stringResource(
                                when (status.reason) {
                                    SignInFailure.UNAVAILABLE -> R.string.auth_platformUnsupported
                                    SignInFailure.GENERIC -> R.string.auth_errorGeneric
                                },
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    GoogleSignInButton(onSignInClick)
                }

                SignInStatus.Idle -> GoogleSignInButton(onSignInClick)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.auth_privacyNote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun GoogleSignInButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.auth_googleLoginButton))
    }
}

@Preview(name = "Idle Light", showBackground = true)
@Composable
private fun SignInScreenPreviewIdle() {
    KccTheme { SignInScreen(status = SignInStatus.Idle, onSignInClick = {}) }
}

@Preview(name = "Failed Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SignInScreenPreviewFailed() {
    KccTheme {
        SignInScreen(
            status = SignInStatus.Failed(SignInFailure.GENERIC),
            onSignInClick = {},
        )
    }
}
