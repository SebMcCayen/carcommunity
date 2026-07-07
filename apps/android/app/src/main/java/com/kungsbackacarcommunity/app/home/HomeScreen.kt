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
    // Notification preferences: opens the settings screen; null hides the entry.
    onOpenNotificationSettings: (() -> Unit)? = null,
    // Phase 12 slice 13: opens the garage (member); null hides the entry.
    onOpenGarage: (() -> Unit)? = null,
    // Phase 12 slice 14: opens badges; null hides the entry.
    onOpenBadges: (() -> Unit)? = null,
    // Phase 12 slice 8: opens the blocked-users management screen; null hides it.
    onOpenBlocked: (() -> Unit)? = null,
    // Phase 12 slice 12: opens saved drives; null hides the entry.
    onOpenSavedDrives: (() -> Unit)? = null,
    // Phase 12 slice 15: opens the points wallet; null hides the entry.
    onOpenPoints: (() -> Unit)? = null,
    // Phase 12 slice 24: opens the subscription/membership purchase; null hides.
    onOpenSubscription: (() -> Unit)? = null,
    // Phase 12 slice 18: opens the partner application; null hides the entry.
    onOpenPartnerApplication: (() -> Unit)? = null,
    // Phase 12 slice 20: opens digital billboards; null hides the entry.
    onOpenBillboards: (() -> Unit)? = null,
    // Phase 12 slice 25: opens account deletion; null hides the entry.
    onOpenAccountDeletion: (() -> Unit)? = null,
    // Phase 12 slice 19: opens partner-stats opt-in; null hides the entry.
    onOpenPartnerStats: (() -> Unit)? = null,
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
            if (onOpenNotificationSettings != null) {
                Button(onClick = onOpenNotificationSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.notifications_settingsTitle))
                }
            }
            if (onOpenGarage != null) {
                Button(onClick = onOpenGarage, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.garage_screenTitle))
                }
            }
            if (onOpenBadges != null) {
                Button(onClick = onOpenBadges, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.badges_screenTitle))
                }
            }
            if (onOpenBlocked != null) {
                OutlinedButton(onClick = onOpenBlocked, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.blocking_blockedUsersTitle))
                }
            }
            if (onOpenSavedDrives != null) {
                Button(onClick = onOpenSavedDrives, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.savedDrives_screenTitle))
                }
            }
            if (onOpenPoints != null) {
                Button(onClick = onOpenPoints, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.points_screenTitle))
                }
            }
            if (onOpenSubscription != null) {
                Button(onClick = onOpenSubscription, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.subscription_screenTitle))
                }
            }
            if (onOpenPartnerApplication != null) {
                OutlinedButton(onClick = onOpenPartnerApplication, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.partners_applicationTitle))
                }
            }
            if (onOpenBillboards != null) {
                OutlinedButton(onClick = onOpenBillboards, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.billboard_advertisingFrom))
                }
            }
            if (onOpenPartnerStats != null) {
                OutlinedButton(onClick = onOpenPartnerStats, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.privacySettings_title))
                }
            }
            if (onOpenAccountDeletion != null) {
                OutlinedButton(onClick = onOpenAccountDeletion, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.settings_deleteAccount))
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
