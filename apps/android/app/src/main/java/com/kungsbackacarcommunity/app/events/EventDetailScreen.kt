package com.kungsbackacarcommunity.app.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import java.text.DateFormat
import java.util.Date

/**
 * Event detail (Phase 12 slice 9). Stateless: shows the teaser fields to any
 * authenticated user, the member-gated [detail] (exact location/description)
 * or a membership gate, and — for members on a published event — an RSVP row
 * whose current selection reflects [myRsvp].
 */
@Composable
fun EventDetailScreen(
    event: EventSummary?,
    detail: EventDetail?,
    myRsvp: RsvpStatus?,
    isActiveMember: Boolean,
    rsvpStatus: RsvpStatusUi,
    onRsvp: (RsvpStatus) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // True until the first Firestore snapshot arrives, so a null event reads
    // as "loading" rather than "error" on the very first composition.
    isLoading: Boolean = false,
    onOpenChat: (() -> Unit)? = null,
    onOpenGroupDrive: (() -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (event == null) {
                Text(
                    text =
                        stringResource(
                            if (isLoading) R.string.events_loadingDetail else R.string.events_errorDetail,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (isLoading) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.profile_back))
                }
                return@Column
            }

            Text(
                text = event.title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (event.isOfficial) {
                Text(
                    text = stringResource(R.string.events_officialBadge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (event.status == EventStatus.CANCELLED) {
                Text(
                    text = stringResource(R.string.events_cancelledNotice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            event.startsAtMillis?.let { millis ->
                Text(
                    text = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date(millis)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = event.approximateArea,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            event.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Member-gated detail: exact location + full description, or a gate.
            // Detail (exact location/description) only when the rules would
            // actually serve it: active member AND published. Non-members see
            // the membership gate; a member on a non-published event sees
            // neither (the cancelled notice above already explains the state).
            if (Events.canSeeDetails(isActiveMember, event.status)) {
                DetailCard(detail)
            } else if (!isActiveMember) {
                InfoCard(
                    title = stringResource(R.string.events_memberRequiredTitle),
                    body = stringResource(R.string.events_memberRequiredBody),
                )
            }

            // RSVP row — members only, published events only.
            if (Events.canRsvp(isActiveMember, event.status)) {
                Text(
                    text = stringResource(R.string.events_rsvpCountsLabel),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RsvpButton(R.string.events_rsvpGoing, RsvpStatus.GOING, myRsvp, rsvpStatus, onRsvp)
                    RsvpButton(R.string.events_rsvpMaybe, RsvpStatus.MAYBE, myRsvp, rsvpStatus, onRsvp)
                    RsvpButton(R.string.events_rsvpNotGoing, RsvpStatus.NOT_GOING, myRsvp, rsvpStatus, onRsvp)
                }
                if (rsvpStatus == RsvpStatusUi.Failed) {
                    Text(
                        text = stringResource(R.string.events_rsvpSubmitError),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Event chat — offered only when eligible (decided by the caller:
            // chat flag + member + published + going/maybe RSVP).
            if (onOpenChat != null) {
                OutlinedButton(onClick = onOpenChat, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.chat_eventChatTitle))
                }
            }
            // Group drive — offered when eligible (member + published +
            // going/maybe RSVP).
            if (onOpenGroupDrive != null) {
                OutlinedButton(onClick = onOpenGroupDrive, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.groupDrive_screenTitle))
                }
            }

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.profile_back))
            }
        }
    }
}

@Composable
private fun DetailCard(detail: EventDetail?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.events_memberDetailPlaceholder),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val location = detail?.locationName ?: detail?.address
            if (location != null) {
                Text(
                    text = location,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            detail?.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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

@Composable
private fun RowScope.RsvpButton(
    labelRes: Int,
    answer: RsvpStatus,
    myRsvp: RsvpStatus?,
    rsvpStatus: RsvpStatusUi,
    onRsvp: (RsvpStatus) -> Unit,
) {
    val label = stringResource(labelRes)
    val enabled = rsvpStatus != RsvpStatusUi.Saving
    if (answer == myRsvp) {
        Button(onClick = { onRsvp(answer) }, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text(text = label, textAlign = TextAlign.Center)
        }
    } else {
        OutlinedButton(
            onClick = { onRsvp(answer) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(),
        ) {
            Text(text = label, textAlign = TextAlign.Center)
        }
    }
}

@Preview(name = "Event detail – member", showBackground = true)
@Composable
private fun EventDetailPreview() {
    KccTheme {
        EventDetailScreen(
            event =
                EventSummary(
                    id = "e1",
                    title = "Cars & Coffee",
                    summary = "Monthly meet",
                    startsAtMillis = 0L,
                    endsAtMillis = null,
                    approximateArea = "Kungsbacka",
                    isOfficial = true,
                    status = EventStatus.PUBLISHED,
                    counts = RsvpCounts(12, 3, 1),
                ),
            detail = EventDetail("Bring your car.", "Torg", "Storgatan 1", 57.0, 12.0),
            myRsvp = RsvpStatus.GOING,
            isActiveMember = true,
            rsvpStatus = RsvpStatusUi.Idle,
            onRsvp = {},
            onBack = {},
        )
    }
}
