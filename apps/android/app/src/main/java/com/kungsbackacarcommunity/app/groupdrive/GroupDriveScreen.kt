package com.kungsbackacarcommunity.app.groupdrive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.kungsbackacarcommunity.app.design.KccButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import com.kungsbackacarcommunity.app.design.KccOutlinedButton as OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import java.time.ZoneId
import com.kungsbackacarcommunity.app.shell.AeroPage
import com.kungsbackacarcommunity.app.events.EventSummary
import com.kungsbackacarcommunity.app.events.EventSchedule
import com.kungsbackacarcommunity.app.events.EventDateBadge
import com.kungsbackacarcommunity.app.events.EventInfoLine
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.LocationOn

/**
 * Group driving roster + participation controls (Phase 12 slice 11).
 * Stateless. Joining is gated on [canJoin] (active account + published + going/maybe
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
    // Identity from the existing parent event, without a second event subscription.
    event: EventSummary? = null,
) {
    val busy = actionStatus == GroupDriveActionStatus.Working
    val participating = GroupDrive.isParticipating(myStatus)
    val activeCount = GroupDrive.activeParticipants(participants).size

    AeroPage(title = event?.title ?: stringResource(R.string.groupDrive_screenTitle), modifier = modifier) {
        if (event != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                EventDateBadge(event.startsAtMillis)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.groupDrive_screenTitle), style = MaterialTheme.typography.titleMedium)
                    event.startsAtMillis?.let { start ->
                        EventInfoLine(Icons.Filled.CalendarMonth,
                            EventSchedule.format(start, event.endsAtMillis, LocalLocale.current.platformLocale, ZoneId.systemDefault()))
                    }
                    (event.locationName?.takeIf { it.isNotBlank() } ?: event.approximateArea?.takeIf { it.isNotBlank() })?.let { place ->
                        EventInfoLine(Icons.Filled.LocationOn, place)
                    }
                }
            }
        }
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
            return@AeroPage
        }

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
                text = stringResource(R.string.groupDrive_setStatus),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusButton(R.string.groupDrive_statusJoined, GroupDriveStatus.JOINED, myStatus, busy, onSetStatus)
                StatusButton(R.string.groupDrive_statusOnTheWay, GroupDriveStatus.ON_THE_WAY, myStatus, busy, onSetStatus)
                StatusButton(R.string.groupDrive_statusArrived, GroupDriveStatus.ARRIVED, myStatus, busy, onSetStatus)
            }
            OutlinedButton(onClick = onLeave, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.groupDrive_leaveButton))
            }
        } else {
            Button(
                onClick = onJoin,
                enabled = !busy && canJoin,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary),
                modifier = Modifier.fillMaxWidth(),
            ) {
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


        Text(
            text = "${stringResource(R.string.groupDrive_participantCount)}: $activeCount",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        // Names and participation status are the complete roster model. No
        // inferred vehicle, avatar, position or live-sharing state.
        val active = GroupDrive.activeParticipants(participants)
        if (active.isNotEmpty()) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                active.forEachIndexed { index, participant ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                            .testTag("group_drive_participant_${participant.uid}"),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(32.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = participant.displayName?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.events_attendeesUnknownMember),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = statusLabel(participant.status),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (index < active.lastIndex) HorizontalDivider()
                }
            }
        }


        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.profile_back))
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
    FilterChip(
        selected = status == myStatus,
        enabled = !busy,
        onClick = { onSetStatus(status) },
        label = { Text(text = stringResource(labelRes)) },
    )
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
