package com.kungsbackacarcommunity.app.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import kotlin.random.Random

/**
 * Car quotes shown under the sign-in form — one is picked at random per
 * screen display. The quotes are attributed and localized through the
 * localization contract (Swedish is the default locale, English lives in
 * values-en). Kept internal so tests can assert on the list.
 */
internal val signInCarQuoteResIds = listOf(
    R.string.auth_carQuote01,
    R.string.auth_carQuote02,
    R.string.auth_carQuote03,
    R.string.auth_carQuote04,
    R.string.auth_carQuote05,
    R.string.auth_carQuote06,
    R.string.auth_carQuote07,
    R.string.auth_carQuote08,
    R.string.auth_carQuote09,
    R.string.auth_carQuote10,
    R.string.auth_carQuote11,
    R.string.auth_carQuote12,
    R.string.auth_carQuote13,
    R.string.auth_carQuote14,
    R.string.auth_carQuote15,
    R.string.auth_carQuote16,
    R.string.auth_carQuote17,
    R.string.auth_carQuote18,
    R.string.auth_carQuote19,
    R.string.auth_carQuote20,
)

/**
 * Minimal sign-in scaffold (migration plan Phase 7, PR 7c).
 *
 * Google Sign-In per docs/auth-mobile-requirements.md. All copy comes from
 * generated string resources (contracts/localization). The screen forces its
 * own [KccTheme] (dark — a brand moment over Ink Black) internally, overriding
 * any ambient/app-level theme, so it looks the same regardless of the caller's
 * theme; no dedicated [KccTheme] wrapper is needed for it.
 */
@Composable
fun SignInScreen(
    status: SignInStatus,
    onSignInClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Pins the car quote shown under the form (index into
    // [signInCarQuoteResIds]) so previews/screenshot tests are deterministic.
    // null (production) = random pick per screen display, stable across
    // recomposition and rotation via rememberSaveable.
    quoteIndex: Int? = null,
    // Debug-only dev sign-in. Non-null ONLY in a debug build wired to the local
    // Firebase emulator (MainActivity passes it under
    // BuildConfig.DEBUG && BuildConfig.USE_FIREBASE_EMULATOR); null everywhere
    // else, including all release builds, so the affordance never renders in
    // production and Google Sign-In stays the sole production path.
    onDevSignInClick: (() -> Unit)? = null,
) {
    // The sign-in screen is a brand moment shown over Ink Black art, so it
    // always renders light-on-dark (light content over the dark background)
    // regardless of the system theme. Forcing KccTheme's dark scheme keeps
    // every text/button color on a contract token (light ivory text, gold
    // button) instead of hardcoding hex.
    KccTheme(darkTheme = true) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Subtle full-screen brand background. The art is bottom-weighted
                // (top ~55% is clean), so the centered form stays legible over it.
                Image(
                    painter = painterResource(R.drawable.login_bg),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.kcc_logo_dark_bg),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.fillMaxWidth(0.7f),
                    )
                    Spacer(Modifier.height(32.dp))
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

                    // Debug-only dev sign-in (local emulator builds only). Never
                    // shown in production: onDevSignInClick is null there.
                    if (onDevSignInClick != null) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onDevSignInClick,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = "Dev sign-in (Sven — emulator)")
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    // Random car quote in place of the old privacy note.
                    // In production (quoteIndex == null) a random index is
                    // drawn and remembered so the pick stays stable across
                    // recomposition and rotation; a new one is drawn each time
                    // the screen enters composition. When an explicit
                    // quoteIndex is pinned (previews/tests) no random state is
                    // created. The final index is wrapped into bounds at the
                    // point of use regardless of source (pinned OR a random
                    // value restored by rememberSaveable), so neither a
                    // caller-supplied out-of-range value nor a stale saved
                    // index from an older quote-list size can crash this public
                    // composable. No maxLines: the longest quote wraps to 2-3
                    // centered lines.
                    val rawQuoteIndex =
                        if (quoteIndex != null) {
                            quoteIndex
                        } else {
                            rememberSaveable { Random.nextInt(signInCarQuoteResIds.size) }
                        }
                    val resolvedQuoteIndex = rawQuoteIndex.mod(signInCarQuoteResIds.size)
                    Text(
                        text = stringResource(signInCarQuoteResIds[resolvedQuoteIndex]),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun GoogleSignInButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.auth_googleLoginButton))
    }
}

// SignInScreen forces its own dark theme, so these previews always render
// dark regardless of ambient/system theme — the names reflect that.
@Preview(name = "Idle (forced dark)", showBackground = true)
@Composable
private fun SignInScreenPreviewIdle() {
    // SignInScreen applies its own KccTheme — do not wrap it again.
    // quoteIndex is pinned so the preview is deterministic.
    SignInScreen(status = SignInStatus.Idle, onSignInClick = {}, quoteIndex = 0)
}

@Preview(name = "Failed (forced dark)", showBackground = true)
@Composable
private fun SignInScreenPreviewFailed() {
    // SignInScreen applies its own KccTheme — do not wrap it again.
    // quoteIndex 12 pins the longest quote (Bobby Unser) to preview how
    // multi-line quotes wrap.
    SignInScreen(
        status = SignInStatus.Failed(SignInFailure.GENERIC),
        onSignInClick = {},
        quoteIndex = 12,
    )
}
