package com.kungsbackacarcommunity.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import com.kungsbackacarcommunity.app.auth.AuthState
import com.kungsbackacarcommunity.app.auth.FirebaseAuthRepository
import com.kungsbackacarcommunity.app.auth.GoogleCredentialTokenProvider
import com.kungsbackacarcommunity.app.auth.SignInCoordinator
import com.kungsbackacarcommunity.app.auth.SignInStatus
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Guarded Firebase wiring: null when google-services.json is absent
        // (CI/local validation builds) — the app renders without auth.
        val authRepository = FirebaseAuthRepository.createIfAvailable(applicationContext)
        val signInCoordinator =
            authRepository?.let {
                SignInCoordinator(
                    tokenProvider = GoogleCredentialTokenProvider(this),
                    repository = it,
                )
            }

        setContent {
            val authState =
                authRepository?.authState?.collectAsState()?.value ?: AuthState.Unavailable
            val signInStatus =
                signInCoordinator?.status?.collectAsState()?.value ?: SignInStatus.Idle

            AppRoot(
                authState = authState,
                signInStatus = signInStatus,
                onSignInClick = {
                    signInCoordinator?.let { coordinator ->
                        lifecycleScope.launch { coordinator.signIn() }
                    }
                },
                // signOut flips Firebase auth state; the authState listener
                // re-renders AppRoot back to the sign-in screen reactively.
                onSignOutClick = { authRepository?.signOut() },
            )
        }
    }
}
