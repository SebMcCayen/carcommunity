package com.kungsbackacarcommunity.app.home

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme

/**
 * Authenticated home shell (Phase 12, slice 1).
 *
 * The landing surface for a signed-in user: a greeting, placeholder
 * community/event status cards (their real content arrives with later
 * feature slices), and a sign-out action. All copy comes from generated
 * string resources (contracts/localization); wrap in [KccTheme] at the
 * call site.
 *
 * @param displayName the Firebase display name, or null when absent.
 * @param onSignOut invoked when the user taps sign out; null hides the
 *   action (e.g. an Unavailable build with no Firebase session).
 */
@Composable
fun HomeScreen(
    displayName: String?,
    onSignOut: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onOpenProfile: (() -> Unit)? = null,
    // Phase 12 slice 3 gates: the live-location teaser is shown when the
    // liveLocation feature flag is on; the member-value card when the user
    // holds an active member entitlement (see FeatureGate).
    showLiveLocationTeaser: Boolean = false,
    showMemberValue: Boolean = false,
    // Phase 12 slice 5: opens the live-location screen; null hides the entry
    // point (flag off or no Firebase session).
    onOpenLiveLocation: (() -> Unit)? = null,
    // Phase 12 slice 9: opens the events list; null hides the entry point.
    onOpenEvents: (() -> Unit)? = null,
    // Phase 12 slice 16: opens Kronjakt; null hides the entry point.
    onOpenCrownHunt: (() -> Unit)? = null,
    // Phase 12 slice 17: opens partners; null hides the entry point.
    onOpenPartners: (() -> Unit)? = null,
    // Phase 12 slice 21: opens the notification inbox; null hides the entry.
    onOpenNotifications: (() -> Unit)? = null,
    // Phase 12 slice 13: opens the garage (member); null hides the entry.
    onOpenGarage: (() -> Unit)? = null,
) {
    val greetingName = HomeContent.greetingName(displayName)
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.auth_loggedInAs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (greetingName != null) {
                Text(
                    text = greetingName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            StatusCard(
                title = stringResource(R.string.home_communityStatusTitle),
                body = stringResource(R.string.home_communityStatusBody),
            )
            StatusCard(
                title = stringResource(R.string.home_nextEventTitle),
                body = stringResource(R.string.home_nextEventBody),
            )
            if (onOpenEvents != null) {
                Button(onClick = onOpenEvents, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.events_title))
                }
            }
            if (onOpenCrownHunt != null) {
                Button(onClick = onOpenCrownHunt, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.crownHunt_screenTitle))
                }
            }
            if (onOpenPartners != null) {
                Button(onClick = onOpenPartners, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.partners_screenTitle))
                }
            }
            if (onOpenNotifications != null) {
                Button(onClick = onOpenNotifications, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.notifications_title))
                }
            }
            if (onOpenGarage != null) {
                Button(onClick = onOpenGarage, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.garage_screenTitle))
                }
            }

            if (showLiveLocationTeaser) {
                StatusCard(
                    title = stringResource(R.string.home_liveLocationSectionTitle),
                    body = stringResource(R.string.home_liveLocationDisclaimer),
                )
                if (onOpenLiveLocation != null) {
                    Button(onClick = onOpenLiveLocation, modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(R.string.home_liveLocationButton))
                    }
                }
            }
            if (showMemberValue) {
                StatusCard(
                    title = stringResource(R.string.home_memberValueTitle),
                    body = stringResource(R.string.home_memberValueBody),
                )
            }

            if (onOpenProfile != null) {
                Button(onClick = onOpenProfile, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.navigation_profile))
                }
            }

            if (onSignOut != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.auth_signOut))
                }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(name = "Home – signed in", showBackground = true)
@Composable
private fun HomeScreenPreview() {
    KccTheme {
        HomeScreen(displayName = "Sebbe", onSignOut = {})
    }
}

@Preview(name = "Home – dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeScreenPreviewDark() {
    KccTheme {
        HomeScreen(displayName = null, onSignOut = {})
    }
}
