package com.kungsbackacarcommunity.app.events

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.shell.AeroPage
import java.text.DateFormat
import java.util.Date

/**
 * Event detail (Phase 12 slice 9). Stateless: shows the teaser fields to any
 * authenticated user, the member-gated [detail] (exact location/description)
 * or a membership gate, and — for members on a published event — an RSVP row
 * whose current selection reflects [myRsvp], plus the "who's going" section.
 */
@Composable
fun EventDetailScreen(
    event: EventSummary?,
    detail: EventDetail?,
    myRsvp: RsvpStatus?,
    passesMemberGate: Boolean,
    rsvpStatus: RsvpStatusUi,
    onRsvp: (RsvpStatus) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // True until the first Firestore snapshot arrives, so a null event reads
    // as "loading" rather than "error" on the very first composition.
    isLoading: Boolean = false,
    // Re-invokes the detail load from the error state; null hides the retry.
    onRetry: (() -> Unit)? = null,
    onOpenChat: (() -> Unit)? = null,
    onOpenGroupDrive: (() -> Unit)? = null,
    // Who answered. The COUNT always comes from the public rsvpCounts tally on
    // the event doc; the NAMES (grouped by RSVP answer) render when the
    // events-listAttendees roster read succeeds — see EventAttendees.
    attendees: EventAttendeesState = EventAttendeesState.Unavailable,
    // Opens a member's read-only profile. Null (config-less build / no member
    // profile repository) leaves the rows inert rather than dead-ending a tap.
    onOpenMember: ((String) -> Unit)? = null,
    // Re-runs the roster read from the transient-error state; null hides retry.
    onRetryAttendees: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    AeroPage(title = event?.title ?: stringResource(R.string.events_title), modifier = modifier) {
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
                if (!isLoading && onRetry != null) {
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(R.string.events_retry))
                    }
                }
                return@AeroPage
            }

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
            // actually serve it: passes the member gate AND published. A
            // caller who fails the gate sees the membership upsell INSTEAD of
            // the detail (that copy is the block, not a hint beside it, so it
            // disappears while gating is disabled); someone who passes but is
            // on a non-published event sees
            // neither (the cancelled notice above already explains the state).
            if (Events.canSeeDetails(passesMemberGate, event.status)) {
                DetailCard(locationName = event.locationName, detail = detail)
            } else if (!passesMemberGate) {
                InfoCard(
                    title = stringResource(R.string.events_memberRequiredTitle),
                    body = stringResource(R.string.events_memberRequiredBody),
                )
            }

            // RSVP row — gate-passers only, published events only.
            if (Events.canRsvp(passesMemberGate, event.status)) {
                Text(
                    text = stringResource(R.string.events_rsvpCountsLabel),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // A light confirm haptic accompanies the RSVP write; the
                    // failure path is surfaced as a shell snackbar by the route.
                    val onRsvpHaptic: (RsvpStatus) -> Unit = { answer ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onRsvp(answer)
                    }
                    RsvpButton(R.string.events_rsvpGoing, RsvpStatus.GOING, myRsvp, rsvpStatus, onRsvpHaptic)
                    RsvpButton(R.string.events_rsvpMaybe, RsvpStatus.MAYBE, myRsvp, rsvpStatus, onRsvpHaptic)
                    RsvpButton(R.string.events_rsvpNotGoing, RsvpStatus.NOT_GOING, myRsvp, rsvpStatus, onRsvpHaptic)
                }

                // Who's going — same member+published gate as the details, so
                // the roster is never teased to a non-member.
                AttendeesSection(
                    state = attendees,
                    goingCount = event.counts.going,
                    onOpenMember = onOpenMember,
                    onRetry = onRetryAttendees,
                )
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
    }
}

/**
 * "Who's going": the count (always available — it is the server-maintained
 * public rsvpCounts tally) plus, when the roster read succeeded, the members
 * themselves. [EventAttendeesState.Unavailable] states plainly that names
 * aren't shown rather than pretending the event has no attendees — the count
 * next to it would contradict that lie anyway.
 */
@Composable
private fun AttendeesSection(
    state: EventAttendeesState,
    goingCount: Int,
    onOpenMember: ((String) -> Unit)?,
    onRetry: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(ATTENDEES_SECTION_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.events_attendeesTitle),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.events_attendeesCount, goingCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        when (state) {
            is EventAttendeesState.Loading ->
                AttendeesNote(stringResource(R.string.events_attendeesLoading))

            is EventAttendeesState.Empty ->
                AttendeesNote(stringResource(R.string.events_attendeesEmpty))

            is EventAttendeesState.Unavailable ->
                AttendeesNote(stringResource(R.string.events_attendeesUnavailable))

            is EventAttendeesState.Error -> {
                AttendeesNote(stringResource(R.string.events_attendeesError))
                if (onRetry != null) {
                    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(R.string.events_retry))
                    }
                }
            }

            is EventAttendeesState.Loaded ->
                EventAttendees.groupedByStatus(state.attendees).forEach { group ->
                    Text(
                        text = stringResource(attendeeGroupLabel(group.status)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(attendeeGroupTag(group.status)),
                    )
                    group.members.forEach { attendee ->
                        AttendeeRow(attendee = attendee, onOpenMember = onOpenMember)
                    }
                }
        }
    }
}

/** String resource for a status group header (going / maybe / not_going). */
private fun attendeeGroupLabel(status: RsvpStatus): Int =
    when (status) {
        RsvpStatus.GOING -> R.string.events_attendeesGroupGoing
        RsvpStatus.MAYBE -> R.string.events_attendeesGroupMaybe
        RsvpStatus.NOT_GOING -> R.string.events_attendeesGroupNotGoing
    }

@Composable
private fun AttendeesNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One attendee. Tapping opens their read-only member profile; when
 * [onOpenMember] is null the row renders identically but is not clickable, so
 * it never advertises a destination that isn't wired up.
 */
@Composable
private fun AttendeeRow(
    attendee: EventAttendee,
    onOpenMember: ((String) -> Unit)?,
) {
    val name =
        attendee.displayName?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.events_attendeesUnknownMember)
    val base =
        Modifier
            .fillMaxWidth()
            .testTag(attendeeRowTag(attendee.uid))
    Row(
        modifier =
            if (onOpenMember != null) {
                // role = Role.Button matches every other profile-open affordance
                // (friends list, chat authors, convoy roster) so TalkBack
                // announces this row as a button rather than plain text. The
                // padding stays INSIDE the clickable — .clickable().padding()
                // puts the tap target around the padded row, not within it.
                base
                    .clickable(role = Role.Button) { onOpenMember(attendee.uid) }
                    .padding(vertical = 8.dp)
            } else {
                base.padding(vertical = 8.dp)
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AttendeeAvatar(avatarPath = attendee.avatarPath)
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun AttendeeAvatar(avatarPath: String?) {
    val context = LocalContext.current
    val url = rememberStorageImageUrl(context, avatarPath)
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Test tag for the attendees section container. */
internal const val ATTENDEES_SECTION_TAG = "events_attendees_section"

/** Stable per-attendee test tag so a UI test can tap a specific member. */
internal fun attendeeRowTag(uid: String): String = "events_attendee_$uid"

/** Stable per-status-group test tag (going / maybe / not_going headers). */
internal fun attendeeGroupTag(status: RsvpStatus): String = "events_attendee_group_${status.wire}"

@Composable
private fun DetailCard(locationName: String?, detail: EventDetail?) {
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
            // The public place name (teaser) leads; the precise street address
            // (member-only) is the fallback when no place name was set.
            val location = locationName ?: detail?.address
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
                    locationName = "Torg",
                    latitude = 57.4874,
                    longitude = 12.0757,
                    isOfficial = true,
                    status = EventStatus.PUBLISHED,
                    counts = RsvpCounts(12, 3, 1),
                ),
            detail = EventDetail("Bring your car.", "Storgatan 1"),
            myRsvp = RsvpStatus.GOING,
            passesMemberGate = true,
            rsvpStatus = RsvpStatusUi.Idle,
            onRsvp = {},
            onBack = {},
        )
    }
}
