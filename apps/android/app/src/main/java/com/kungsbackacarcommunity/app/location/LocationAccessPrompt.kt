package com.kungsbackacarcommunity.app.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing

/**
 * Explains that the map needs location, and offers the ONE action that fixes
 * the specific problem — see [LocationAccess] for why the two causes are not
 * merged into a single "check your settings" message.
 *
 * Non-blocking by design: it is a card over the map, not a dialog. The map is
 * still usable for browsing events and incidents without a position, so
 * trapping the user behind a modal would take away more than it gives.
 *
 * Stateless — the caller owns visibility and dismissal, so this renders the same
 * way under test as in the app.
 *
 * @param settingsUnavailable set once an attempt to open Settings found no
 *   activity to handle it. The action is then replaced with an honest line
 *   rather than left as a button that does nothing.
 */
@Composable
fun LocationAccessPrompt(
    access: LocationAccess,
    remedy: LocationPermissionRemedy,
    settingsUnavailable: Boolean,
    onFix: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!access.isBlocked) return

    val body = when (access) {
        LocationAccess.SERVICES_OFF -> R.string.map_locationServicesOffBody
        else -> R.string.map_locationPermissionBody
    }
    // Permission still promptable → the button raises the system dialog, which
    // is a far shorter road than a trip through Settings. Everything else
    // (permanent denial, master switch off) can only be fixed in Settings.
    val actionLabel = when {
        access == LocationAccess.PERMISSION_DENIED &&
            remedy == LocationPermissionRemedy.REQUEST_AGAIN -> R.string.map_locationAllow
        else -> R.string.map_locationOpenSettings
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            Text(
                text = stringResource(R.string.map_locationNeededTitle),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = stringResource(body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (settingsUnavailable) {
                // Per DESTINATION, not one generic line: the two cases fail on
                // different settings screens and want opposite manual advice.
                // Telling a services-off user to "grant location access to the
                // app" would send them after a permission they already have —
                // the same wrong-remedy trap this whole prompt exists to avoid.
                val fallback = when (access) {
                    LocationAccess.SERVICES_OFF ->
                        R.string.map_locationServicesSettingsUnavailable
                    else -> R.string.map_locationSettingsUnavailable
                }
                Text(
                    text = stringResource(fallback),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.padding(top = KccSpacing.s1),
                horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
            ) {
                if (!settingsUnavailable) {
                    Button(onClick = onFix) { Text(stringResource(actionLabel)) }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.map_locationDismiss))
                }
            }
        }
    }
}
