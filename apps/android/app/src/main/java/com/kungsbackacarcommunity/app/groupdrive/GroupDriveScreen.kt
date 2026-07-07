package com.kungsbackacarcommunity.app.groupdrive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R

/**
 * Group driving roster + participation controls (Phase 12 slice 11).
 * Stateless. Joining is gated on [canJoin] (member + published + going/maybe
 * RSVP); live-location sharing is a SEPARATE action and never implied here.
 */
@Composable
fun GroupDriveScreen(
    participants: List<GroupDriveParticipant>,
    myStatus: GroupDriveStatus?,
    canJoin: Boolean,
    actionStatus: GroupDriveActionStatus,
    onJoin: () -> Unit,
    onSetStatus: (GroupDriveStatus) -> Unit,
    onLeave: () -> Unit,
    onShowOnMap: (() -> Unit)? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = actionStatus == GroupDriveActionStatus.Working
    val participating = GroupDrive.isParticipating(myStatus)
    val activeCount = GroupDrive.activeParticipants(participants).size

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.groupDrive_screenTitle),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            WarningCard(stringResource(R.string.groupDrive_safeDrivingWarning))
            Text(
                text = stringResource(R.string.groupDrive_liveLocationSeparate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!canJoin && !participating) {
                InfoCard(
                    title = stringResource(R.string.groupDrive_memberRequired),
                    body = stringResource(R.string.groupDrive_rsvpRequired),
                )
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.profile_back))
                }
                return@Column
            }

            Text(
                text = "${stringResource(R.string.groupDrive_participantCount)}: $activeCount",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            // "Show on map" opens the shared map for the active roster. Only
            // wired when live markers are available (onShowOnMap != null) and
            // there is at least one active participant to show. Each member only
            // renders if they are actually sharing (their own opt-in) — joining
            // a drive never implies live-location sharing.
            if (onShowOnMap != null && activeCount > 0) {
                OutlinedButton(onClick = onShowOnMap, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.groupDrive_showOnMap))
                }
            }

            if (participating) {
                Text(
                    text = statusLabel(myStatus),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.groupDrive_setStatus),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusButton(R.string.groupDrive_statusJoined, GroupDriveStatus.JOINED, myStatus, busy, onSetStatus)
                StatusButton(R.string.groupDrive_statusOnTheWay, GroupDriveStatus.ON_THE_WAY, myStatus, busy, onSetStatus)
                StatusButton(R.string.groupDrive_statusArrived, GroupDriveStatus.ARRIVED, myStatus, busy, onSetStatus)
                OutlinedButton(onClick = onLeave, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.groupDrive_leaveButton))
                }
            } else {
                Button(onClick = onJoin, enabled = !busy && canJoin, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.groupDrive_joinButton))
                }
            }

            if (actionStatus == GroupDriveActionStatus.Failed) {
                Text(
                    text = stringResource(R.string.groupDrive_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.profile_back))
            }
        }
    }
}

@Composable
private fun statusLabel(status: GroupDriveStatus?): String =
    stringResource(
        when (status) {
            GroupDriveStatus.ON_THE_WAY -> R.string.groupDrive_statusOnTheWay
            GroupDriveStatus.ARRIVED -> R.string.groupDrive_statusArrived
            else -> R.string.groupDrive_statusJoined
        },
    )

@Composable
private fun StatusButton(
    labelRes: Int,
    status: GroupDriveStatus,
    myStatus: GroupDriveStatus?,
    busy: Boolean,
    onSetStatus: (GroupDriveStatus) -> Unit,
) {
    if (status == myStatus) {
        Button(onClick = { onSetStatus(status) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(labelRes))
        }
    } else {
        OutlinedButton(onClick = { onSetStatus(status) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(labelRes))
        }
    }
}

@Composable
private fun WarningCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
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
