package com.kungsbackacarcommunity.app.events

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import com.kungsbackacarcommunity.app.design.KccButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.kungsbackacarcommunity.app.design.KccOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import java.time.ZoneId
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.shell.AeroPage
import kotlinx.coroutines.delay

const val EVENT_RSVP_SELECTOR_TAG = "event_rsvp_selector"
const val EVENT_DETAIL_CHAT_TAG = "event_detail_chat"
const val EVENT_DETAIL_GROUP_DRIVE_TAG = "event_detail_group_drive"

/**
 * Event identity, RSVP and contextual actions precede the longer description.
 * Full details and RSVP stay free for eligible accounts. Attendee identities and
 * check-in retain their independent paid/server gates from the route.
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
    // Paid identity access is independent of the free full-detail card.
    canViewAttendees: Boolean = false,
    // Opens the subscription screen from the upgrade prompt. Null renders the
    // prompt as an informational card with no action (config-less build).
    onUpgrade: (() -> Unit)? = null,
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
    // True when a geofenced check-in can be attempted right now: member +
    // published + positioned + inside the event's time window (EventCheckIn).
    checkInAvailable: Boolean = false,
    // The in-flight check-in flow state (spinner / success / error message).
    checkInState: CheckInUiState = CheckInUiState.Idle,
    // The caller's own attendance record, so a confirmed/pending state survives a
    // restart rather than only reflecting this session's tap.
    attendance: EventAttendanceStatus? = null,
    // When the member's first sample was recorded (epoch millis) — the anchor of
    // the dwell countdown. The caller resolves it from the persisted record's
    // createdAt (survives app death) falling back to this session's first fix, so
    // a temporary exit or leaving the screen never restarts the countdown. Null
    // when no sample has landed yet.
    firstSampleAtMillis: Long? = null,
    // Runs a check-in. Null (config-less build / no location source) hides the
    // action rather than offering one that cannot work.
    onCheckIn: (() -> Unit)? = null,
    // Triggers the app's navigate-to-the-event action. In the real app this is the
    // OWN in-app navigate-to-point handoff (the same "Navigate here" preview a
    // tapped map place or a chat geo-link raises); only a config-less build that
    // wires no in-app handoff falls back to the device's external maps app. Offered
    // only when the event has a valid pin; null leaves the button off. Independent
    // of [hasMapToken] — the button gates on the pin, not on this screen's embedded
    // map token.
    onNavigate: (() -> Unit)? = null,
    // Shares this event with a friend in-app (friend picker → DM with a tappable
    // "Open event" chip). Null (no friends/DM repository) hides the Share button.
    onShareEvent: (() -> Unit)? = null,
    // Adds the event to the phone's calendar with a one-hour reminder. Null (no
    // readable start time) hides the button.
    onAddToCalendar: (() -> Unit)? = null,
    // The event organiser's current display name (resolved from the creator uid
    // via live users/{uid} in the route). Null hides the "Organizer: …" line — an
    // event with no creator uid, an unresolved name, or a config-less build.
    organizerName: String? = null,
    // Triggers the DEFERRED attendee-roster load when the viewer taps "Check who
    // answered": the roster is collapsed behind that button so the page stays
    // short, and the events-listAttendees read is not run until it is asked for.
    // Null in isolated screen tests, where the [attendees] state is supplied
    // directly — the button still expands the section locally.
    onRevealAttendees: (() -> Unit)? = null,
    // Whether a Mapbox token is configured, so the embedded map can render. When
    // false the map area is hidden (Navigate still shows if the event has a pin).
    hasMapToken: Boolean = false,
    // True when the signed-in user is this event's CREATOR and the event is still
    // in an editable state (draft/published) — the route computes it via
    // Events.canManageOwnEvent. Gates the Edit/Remove creator actions; false for a
    // non-creator, an admin (who moderates through admin-web), or an event that is
    // already cancelled/completed (immutable).
    canManage: Boolean = false,
    // Opens the pre-filled edit form (the shared CreateEventScreen in edit mode).
    // Shown only when [canManage]; null hides it (e.g. config-less build).
    onEdit: (() -> Unit)? = null,
    // Confirmed removal — invoked after the user accepts the confirmation dialog;
    // the route calls events.cancel. Shown only when [canManage]; null hides it.
    onRemove: (() -> Unit)? = null,
    clubAttendance: (@Composable () -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    // The event's single marker point, or null when it carries no valid pin — the
    // one gate for the whole location section (embedded map + Navigate button), so
    // an event with no coordinates hides both gracefully.
    val markerPoint = remember(event) { event?.let { EventMapPresentation.markerPoint(it) } }
    // Whether the full-screen (maximized) map is open. Saveable so a rotation while
    // inspecting the map does not collapse it back to the thumbnail. KEYED on the
    // event id so it resets when the selected event changes in place (e.g. a shared
    // "Open event" chip switches events while this screen stays composed) rather
    // than leaving a stale full-screen map open for the new event.
    var mapMaximized by rememberSaveable(event?.id) { mutableStateOf(false) }
    // Whether the "who answered" roster DIALOG is open. Closed by default (the
    // viewer taps "Check who answered" to open it), which keeps the page short
    // and defers the roster read until asked. Keyed on the event id so switching
    // events in place closes it rather than showing the previous event's roster.
    var showAttendeesDialog by rememberSaveable(event?.id) { mutableStateOf(false) }
    // Whether the "remove event?" confirmation dialog is open. Keyed on the event
    // id so switching events in place dismisses it rather than leaving a stale
    // confirmation targeting the previous event.
    var showRemoveDialog by rememberSaveable(event?.id) { mutableStateOf(false) }
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
                    color = if (com.kungsbackacarcommunity.app.design.LocalKccDarkTheme.current) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            if (event.status == EventStatus.CANCELLED) {
                Text(
                    text = stringResource(R.string.events_cancelledNotice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // The model has no event-cover field. Use its actual date/place as
            // the native header rather than inventing a persisted or sample photo.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                EventDateBadge(event.startsAtMillis)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    event.startsAtMillis?.let { millis ->
                        EventInfoLine(
                            icon = Icons.Filled.CalendarMonth,
                            text = EventSchedule.format(millis, event.endsAtMillis, LocalLocale.current.platformLocale, ZoneId.systemDefault()),
                        )
                    }
                    (event.locationName?.takeIf { it.isNotBlank() }
                        ?: event.approximateArea?.takeIf { it.isNotBlank() })?.let { place ->
                        EventInfoLine(
                            icon = Icons.Filled.LocationOn,
                            text = place,
                            modifier = Modifier.testTag(EVENT_DETAIL_LOCATION_NAME_TAG),
                        )
                    }
                    if (Events.canSeeDetails(passesMemberGate, event.status)) {
                        detail?.address?.takeIf { it.isNotBlank() }?.let { address ->
                            Text(address, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    organizerName?.takeIf { it.isNotBlank() }?.let { name ->
                        EventInfoLine(
                            icon = Icons.Filled.Groups,
                            text = stringResource(R.string.events_organizerLabel, name),
                            modifier = Modifier.testTag(EVENT_DETAIL_ORGANIZER_TAG),
                        )
                    }
                }
            }
            event.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            clubAttendance?.invoke()

            // Creator actions — Edit + Remove, shown ONLY when the signed-in user
            // created this event and it is still editable (canManage). Placed with
            // the event's identity, above the member-gated detail. A non-creator,
            // an admin, or a cancelled/completed event never sees them.
            if (canManage && (onEdit != null || onRemove != null)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onEdit != null) {
                        OutlinedButton(
                            onClick = onEdit,
                            modifier = Modifier.weight(1f).testTag(EVENT_DETAIL_EDIT_TAG),
                        ) {
                            Text(text = stringResource(R.string.events_editButton))
                        }
                    }
                    if (onRemove != null) {
                        OutlinedButton(
                            onClick = { showRemoveDialog = true },
                            modifier = Modifier.weight(1f).testTag(EVENT_DETAIL_REMOVE_TAG),
                            colors =
                                ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                        ) {
                            Text(text = stringResource(R.string.events_removeButton))
                        }
                    }
                }
            }

            if (!passesMemberGate) {
                InfoCard(
                    title = stringResource(R.string.events_memberRequiredTitle),
                    body = stringResource(R.string.events_memberRequiredBody),
                )
            }

            // 3. RSVP row — gate-passers only, published events only — then 4. the
            // "Check who answered" button, which reveals the roster on tap.
            if (Events.canRsvp(passesMemberGate, event.status)) {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        RsvpCountsBreakdown(event.counts)
                    }
                }
                key(event.id) {
                    RsvpSelector(myRsvp, rsvpStatus) { answer ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onRsvp(answer)
                    }
                }

                // Who answered — behind a button so the roster never makes the page
                // long, and its events-listAttendees read is deferred until the
                // viewer asks (onRevealAttendees). Tapping opens a dialog listing the
                // people who answered, grouped by their answer. The attendee LIST is
                // a paid benefit (Slice D) — server-enforced by events-listAttendees
                // (requiresPaid for a free caller) and mirrored here: a free viewer
                // never sees the reveal button (the upgrade card below states the
                // roster is paid). The public rsvpCounts tally still shows for
                // everyone via RsvpCountsBreakdown, so a free viewer sees HOW MANY
                // answered, just not WHO.
                if (canViewAttendees) {
                    OutlinedButton(
                        onClick = {
                            showAttendeesDialog = true
                            onRevealAttendees?.invoke()
                        },
                        modifier = Modifier.fillMaxWidth().testTag(EVENT_DETAIL_REVEAL_ATTENDEES_TAG),
                    ) {
                        Text(text = stringResource(R.string.events_attendeesReveal))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onOpenChat != null) {
                    EventContextAction(stringResource(R.string.chat_eventChatTitle), Icons.AutoMirrored.Filled.Chat,
                        onOpenChat, Modifier.weight(1f).testTag(EVENT_DETAIL_CHAT_TAG))
                }
                if (onOpenGroupDrive != null) {
                    EventContextAction(stringResource(R.string.groupDrive_screenTitle), Icons.Filled.DirectionsCar,
                        onOpenGroupDrive, Modifier.weight(1f).testTag(EVENT_DETAIL_GROUP_DRIVE_TAG))
                }
                if (onShareEvent != null) {
                    EventContextAction(stringResource(R.string.events_shareEvent), Icons.Filled.Share,
                        onShareEvent, Modifier.weight(1f).testTag(EVENT_DETAIL_SHARE_TAG))
                }
            }

            if (Events.canSeeDetails(passesMemberGate, event.status)) DetailCard(detail)
            if (Events.canRsvp(passesMemberGate, event.status) && !canViewAttendees) UpgradeCard(onUpgrade)

            // 5. Location — the embedded map (tap to maximize) + the Navigate
            // button. Shown only when the event has a valid pin ([markerPoint]); the
            // embedded map itself needs a Mapbox token, but the Navigate button
            // gates on the pin (not the token) and shows even without one — it
            // routes through the app's IN-APP navigate-to-point handoff (the same
            // "Navigate here" preview a tapped map place raises), never the device's
            // maps app in the real app. An event with no coordinates gets neither.
            if (markerPoint != null) {
                if (hasMapToken) {
                    // Keyed on the point so the underlying MapView is disposed and
                    // recreated (onRelease → onDestroy, then a fresh factory at the
                    // new coordinate) when the selected event changes in place — the
                    // static factory has no update path, so without this the map
                    // would keep showing the previous event's location.
                    key(markerPoint) {
                        EventLocationMap(
                            point = markerPoint,
                            onMaximize = { mapMaximized = true },
                        )
                    }
                }
                if (onNavigate != null) {
                    Button(
                        onClick = onNavigate,
                        modifier = Modifier.fillMaxWidth().testTag(EVENT_DETAIL_NAVIGATE_TAG),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Navigation,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.events_navigate),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }

            if (onAddToCalendar != null) {
                OutlinedButton(
                    onClick = onAddToCalendar,
                    modifier = Modifier.fillMaxWidth().testTag(EVENT_DETAIL_CALENDAR_TAG),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.events_addToCalendar),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            // Geofenced check-in — the PROOF of attendance (an RSVP is only
            // intent). Kept below the primary spine but still OUTSIDE the
            // published-only RSVP block on purpose: it must still show for a
            // COMPLETED event inside its check-in window (the server accepts
            // those), and the confirmed state must remain visible after the event
            // ends. Renders nothing on its own when there is no check-in to offer
            // and none has happened.
            CheckInSection(
                available = checkInAvailable,
                state = checkInState,
                attendance = attendance,
                firstSampleAtMillis = firstSampleAtMillis,
                onCheckIn = onCheckIn,
            )

            // "Check who answered" dialog — the roster, grouped by answer. Opened
            // from the button above; the deferred roster read has already been
            // triggered (onRevealAttendees) when it opened.
            if (showAttendeesDialog && canViewAttendees) {
                AttendeesDialog(
                    state = attendees,
                    goingCount = event.counts.going,
                    onOpenMember = onOpenMember,
                    onRetry = onRetryAttendees,
                    onDismiss = { showAttendeesDialog = false },
                )
            }

            // Remove confirmation — a creator removing their own event is a
            // notify-everyone action (attendees marked "coming" are messaged), so
            // it is gated behind an explicit confirm. Confirming invokes the
            // route's events.cancel; the dialog never removes anything itself.
            if (showRemoveDialog && onRemove != null) {
                AlertDialog(
                    onDismissRequest = { showRemoveDialog = false },
                    title = { Text(text = stringResource(R.string.events_removeTitle)) },
                    text = { Text(text = stringResource(R.string.events_removeConfirmMessage)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showRemoveDialog = false
                                onRemove()
                            },
                            modifier = Modifier.testTag(EVENT_DETAIL_REMOVE_CONFIRM_TAG),
                        ) {
                            Text(
                                text = stringResource(R.string.events_removeConfirmConfirm),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRemoveDialog = false }) {
                            Text(text = stringResource(R.string.events_removeConfirmCancel))
                        }
                    },
                )
            }

            // Maximized (full-screen, zoomable) map, raised by tapping the embedded
            // thumbnail. Composed here so it overlays the whole detail; dismissing
            // returns to the thumbnail.
            if (mapMaximized && markerPoint != null && hasMapToken) {
                key(markerPoint) {
                    EventLocationFullscreenDialog(
                        point = markerPoint,
                        onDismiss = { mapMaximized = false },
                    )
                }
            }
    }
}

/** Three equal-width actions keep the detail spine compact without shrinking text. */
@Composable
private fun EventContextAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    OutlinedButton(onClick = onClick, modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Geofenced check-in — the attendance PROOF. Three faces, chosen by what has
 * happened so far:
 *  - VERIFIED: a confirmed row with a check icon; the button is gone (there is
 *    nothing left to do, and it stays confirmed even after the window closes).
 *  - PENDING: the first sample landed but the geofence+dwell evidence is not yet
 *    complete. A DWELL COUNTDOWN (progress bar + "m:ss") runs down the ten
 *    minutes since that first sample; while it runs the member is told to stay a
 *    little longer, and once it completes the button becomes a "Confirm
 *    attendance" call-to-action. The countdown is anchored to THIS session's
 *    first-fix capture time (a stable device-clock instant, the same basis the
 *    backend measures dwell from), falling back to the persisted record's
 *    createdAt so it survives leaving the screen or the app; a temporary walk out
 *    of the fence NEVER restarts it — only the final fix has to be back inside
 *    (see CheckInDwell / functions checkIn.ts).
 *  - AVAILABLE: inside the window, not yet checked in — just the button.
 * Renders nothing when a check-in cannot be attempted and none has happened, so
 * an event outside its window shows no dead control.
 */
@Composable
private fun CheckInSection(
    available: Boolean,
    state: CheckInUiState,
    attendance: EventAttendanceStatus?,
    firstSampleAtMillis: Long?,
    onCheckIn: (() -> Unit)?,
) {
    // Verified from EITHER the persistent record OR this session's success (the
    // observed record can lag the callable's reply by a snapshot).
    val verified = attendance?.verified == true || (state as? CheckInUiState.Success)?.verified == true
    val pending = !verified && (attendance?.checkedIn == true || state is CheckInUiState.Success)
    val working = state == CheckInUiState.Working
    val offerButton = available && onCheckIn != null
    if (!verified && !pending && !offerButton) return

    // The dwell countdown ticks once a second while a sample is pending and the
    // ten minutes have not yet elapsed. `now` seeds from the clock and is the
    // only thing that recomposes; the ANCHOR ([firstSampleAtMillis]) is external
    // and persisted, so backgrounding, navigating away, or a temporary exit from
    // the fence leaves it untouched.
    val anchor = firstSampleAtMillis.takeIf { pending }
    var now by remember(anchor) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(anchor) {
        val start = anchor ?: return@LaunchedEffect
        while (!CheckInDwell.isDwellElapsed(start, System.currentTimeMillis())) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
        now = System.currentTimeMillis()
    }
    val dwellElapsed = anchor != null && CheckInDwell.isDwellElapsed(anchor, now)

    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
            .padding(16.dp).testTag(CHECK_IN_SECTION_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.events_checkInTitle),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (verified) {
            Row(
                modifier = Modifier.fillMaxWidth().testTag(CHECK_IN_CONFIRMED_TAG),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.events_checkInConfirmed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            return@Column
        }

        if (offerButton) {
            // Once the dwell is done the button IS the confirm step, so it says so;
            // before then it is the initial "Check in".
            val buttonLabel =
                if (pending && dwellElapsed) {
                    R.string.events_checkInConfirmButton
                } else {
                    R.string.events_checkInButton
                }
            Button(
                onClick = onCheckIn!!,
                enabled = !working,
                modifier = Modifier.fillMaxWidth().testTag(CHECK_IN_BUTTON_TAG),
            ) {
                if (working) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(text = stringResource(buttonLabel))
                }
            }
            // The geofence requirement, stated up front only before the first
            // sample — once a countdown is running the pending copy below carries
            // the "stay in the area" message, so this would be redundant.
            if (!pending) {
                Text(
                    text = stringResource(R.string.events_checkInWithinArea),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(CHECK_IN_WITHIN_AREA_TAG),
                )
            }
        }
        if (pending) {
            // Guidance for the pending state, kept coherent with the CTA. The
            // countdown and "ready to confirm" copy are IMPERATIVE — they tell the
            // member to check in again — so they only show when the confirm button
            // is actually on screen (offerButton). The full matrix, with the button
            // this branch pairs with:
            //   offerButton & anchor & !elapsed  -> progress bar + "confirm in m:ss"  (button: Check in)
            //   offerButton & anchor &  elapsed  -> "ready, check in again now"        (button: Confirm attendance)
            //   offerButton & no anchor          -> generic "stay a bit, check in ~10 min" (button: Check in)
            //   !offerButton (any anchor/elapsed) -> generic guidance, NO imperative — e.g. the
            //                                        window closed while pending, so there is no CTA
            //                                        to point at (the bug this gate fixes).
            when {
                offerButton && anchor != null && !dwellElapsed -> {
                    val remaining = CheckInDwell.remainingMillis(anchor, now)
                    val (mins, secs) = CheckInDwell.remainingMinutesSeconds(remaining)
                    val progressLabel = stringResource(R.string.events_checkInCountdownLabel)
                    LinearProgressIndicator(
                        progress = { CheckInDwell.progressFraction(anchor, now) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(CHECK_IN_PROGRESS_TAG)
                                .semantics { contentDescription = progressLabel },
                    )
                    Text(
                        text =
                            stringResource(
                                R.string.events_checkInCountdown,
                                String.format("%d:%02d", mins, secs),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(CHECK_IN_COUNTDOWN_TAG),
                    )
                }
                offerButton && anchor != null && dwellElapsed ->
                    Text(
                        text = stringResource(R.string.events_checkInReadyToConfirm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag(CHECK_IN_COUNTDOWN_TAG),
                    )
                else ->
                    Text(
                        text = stringResource(R.string.events_checkInPending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
        (state as? CheckInUiState.Failed)?.let { failed ->
            // A geofence miss on the FINAL (confirming) fix is a different problem
            // from one on the first — the member has already dwelt the full ten
            // minutes, they just stepped out. Tell them to come back to FINISH, but
            // only in the confirm phase AND when the confirm button is on screen
            // (offerButton): before the dwell elapses "finish checking in" would be
            // a lie (they still have to wait), and with no CTA there is nothing to
            // finish. Otherwise fall back to the generic geofence explanation.
            val label =
                if (failed.error == CheckInError.OUTSIDE_GEOFENCE &&
                    offerButton && pending && dwellElapsed
                ) {
                    R.string.events_checkInMoveBackToFinish
                } else {
                    checkInErrorLabel(failed.error)
                }
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(CHECK_IN_ERROR_TAG),
            )
        }
    }
}

/** String resource for a check-in failure, in the member's own terms. */
private fun checkInErrorLabel(error: CheckInError): Int =
    when (error) {
        CheckInError.OUTSIDE_GEOFENCE -> R.string.events_checkInErrorGeofence
        CheckInError.WINDOW_CLOSED, CheckInError.NOT_CHECKINABLE ->
            R.string.events_checkInErrorWindow
        CheckInError.POSITION_UNAVAILABLE -> R.string.events_checkInErrorLocation
        CheckInError.MOCK_LOCATION -> R.string.events_checkInErrorMock
        CheckInError.GENERIC -> R.string.events_checkInErrorGeneric
    }

/**
 * The three-way RSVP tally (Going / Maybe / Can't go) from the event's public
 * `rsvpCounts`. Always available — it needs no roster read — so it renders for
 * every gate-passer beneath the RSVP buttons.
 *
 * Presentation is deliberately NEUTRAL: three equal-weight counts using the same
 * three labels the RSVP buttons carry, no emphasis, no "winning" answer. It is a
 * factual summary of who answered what, nothing more.
 */
@Composable
private fun RsvpCountsBreakdown(counts: RsvpCounts) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(RSVP_COUNTS_BREAKDOWN_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RsvpCountItem(R.string.events_rsvpGoing, counts.going, RsvpStatus.GOING, Modifier.weight(1f))
        RsvpCountItem(R.string.events_rsvpMaybe, counts.maybe, RsvpStatus.MAYBE, Modifier.weight(1f))
        RsvpCountItem(R.string.events_rsvpNotGoing, counts.notGoing, RsvpStatus.NOT_GOING, Modifier.weight(1f))
    }
}

/** One count in the RSVP breakdown: the tally over its answer label. Carries a
 * per-answer tag so a test targets the count distinctly from the like-labelled
 * RSVP action button. */
@Composable
private fun RsvpCountItem(labelRes: Int, count: Int, answer: RsvpStatus, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.testTag(rsvpCountTag(answer)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = when (answer) {
                RsvpStatus.GOING -> Icons.Filled.Groups
                RsvpStatus.MAYBE -> Icons.Filled.HelpOutline
                RsvpStatus.NOT_GOING -> Icons.Filled.Cancel
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The "Check who answered" dialog: the roster grouped by answer (Going / Maybe /
 * Can't go), each member a tappable row into their profile. The body reuses the
 * same [AttendeesSection] the page used to expand inline, wrapped in a scroll
 * container so a long roster scrolls inside the dialog. Its own header (the
 * "Who answered" title + the going tally) doubles as the dialog heading, so no
 * separate AlertDialog title is set.
 */
@Composable
private fun AttendeesDialog(
    state: EventAttendeesState,
    goingCount: Int,
    onOpenMember: ((String) -> Unit)?,
    onRetry: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.events_attendeesClose))
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                AttendeesSection(
                    state = state,
                    goingCount = goingCount,
                    onOpenMember = onOpenMember,
                    onRetry = onRetry,
                )
            }
        },
    )
}

/**
 * "Who answered": the count (always available — it is the server-maintained
 * public rsvpCounts tally) plus, when the roster read succeeded, the members
 * themselves, grouped by answer. [EventAttendeesState.Unavailable] states plainly
 * that names aren't shown rather than pretending the event has no attendees — the
 * count next to it would contradict that lie anyway. Rendered as the body of
 * [AttendeesDialog].
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

/** Test tags for the check-in section, its button, confirmed row and error line. */
internal const val CHECK_IN_SECTION_TAG = "events_checkin_section"
internal const val CHECK_IN_BUTTON_TAG = "events_checkin_button"
internal const val CHECK_IN_CONFIRMED_TAG = "events_checkin_confirmed"
internal const val CHECK_IN_ERROR_TAG = "events_checkin_error"

/** Test tag on the "must be within the event area" check-in helper line. */
internal const val CHECK_IN_WITHIN_AREA_TAG = "events_checkin_within_area"

/** Test tag on the dwell-countdown progress bar. */
internal const val CHECK_IN_PROGRESS_TAG = "events_checkin_progress"

/** Test tag on the dwell-countdown / ready-to-confirm line. */
internal const val CHECK_IN_COUNTDOWN_TAG = "events_checkin_countdown"

/** Test tags for the Navigate, Share and Add-to-calendar actions. */
const val EVENT_DETAIL_NAVIGATE_TAG = "events_detail_navigate"
const val EVENT_DETAIL_SHARE_TAG = "events_detail_share"
const val EVENT_DETAIL_CALENDAR_TAG = "events_detail_calendar"

/** Test tag on the "Organizer: <name>" line. */
const val EVENT_DETAIL_ORGANIZER_TAG = "events_detail_organizer"

/** Test tags for the creator Edit / Remove actions and the remove-confirm button. */
const val EVENT_DETAIL_EDIT_TAG = "events_detail_edit"
const val EVENT_DETAIL_REMOVE_TAG = "events_detail_remove"
const val EVENT_DETAIL_REMOVE_CONFIRM_TAG = "events_detail_remove_confirm"

/** Test tag on the "Check who answered" button that opens the attendee roster dialog. */
const val EVENT_DETAIL_REVEAL_ATTENDEES_TAG = "events_detail_reveal_attendees"

/** Test tags on the free-viewer paid-upgrade prompt card and its action button. */
const val EVENT_DETAIL_UPGRADE_TAG = "events_detail_upgrade"
const val EVENT_DETAIL_UPGRADE_ACTION_TAG = "events_detail_upgrade_action"

/** Test tag on the three-way RSVP count breakdown (Going / Maybe / Can't go). */
const val RSVP_COUNTS_BREAKDOWN_TAG = "events_detail_rsvp_counts_breakdown"

/**
 * Per-answer test tag on an RSVP ACTION button. Distinct from [rsvpCountTag] so a
 * test can tap the button without colliding with the like-labelled count in the
 * breakdown (both render "Going" / "Maybe" / "Can't go").
 */
fun rsvpButtonTag(answer: RsvpStatus): String = "events_rsvp_button_${answer.wire}"

/** Per-answer test tag on a count in the RSVP breakdown (see [rsvpButtonTag]). */
fun rsvpCountTag(answer: RsvpStatus): String = "events_rsvp_count_${answer.wire}"

/**
 * Test tag on the PUBLIC place-name line, so a UI test can assert it is rendered
 * outside the member gate (a non-member must still see where the event is).
 */
internal const val EVENT_DETAIL_LOCATION_NAME_TAG = "events_detail_location_name"

/** Stable per-attendee test tag so a UI test can tap a specific member. */
internal fun attendeeRowTag(uid: String): String = "events_attendee_$uid"

/** Stable per-status-group test tag (going / maybe / not_going headers). */
internal fun attendeeGroupTag(status: RsvpStatus): String = "events_attendee_group_${status.wire}"

@Composable
private fun DetailCard(detail: EventDetail?) {
    val description = detail?.description?.takeIf { it.isNotBlank() } ?: return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.events_memberDetailPlaceholder),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(description, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Explains the paid attendee-name and check-in benefits; full details stay free. */
@Composable
private fun UpgradeCard(onUpgrade: (() -> Unit)?) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(EVENT_DETAIL_UPGRADE_TAG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.events_upgradeDetailsTitle),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.events_upgradeDetailsBody),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onUpgrade != null) {
                Button(
                    onClick = onUpgrade,
                    modifier = Modifier.fillMaxWidth().testTag(EVENT_DETAIL_UPGRADE_ACTION_TAG),
                ) {
                    Text(text = stringResource(R.string.events_upgradeDetailsAction))
                }
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
private fun RsvpSelector(
    myRsvp: RsvpStatus?,
    rsvpStatus: RsvpStatusUi,
    onRsvp: (RsvpStatus) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val enabled = rsvpStatus != RsvpStatusUi.Saving
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box(Modifier.fillMaxWidth()) {
        Button(
            onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(EVENT_RSVP_SELECTOR_TAG)
                .semantics { selected = myRsvp != null },
        ) {
            if (myRsvp != null) Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(myRsvp?.let(::rsvpLabel) ?: R.string.events_rsvpCountsLabel),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                textAlign = TextAlign.Center,
            )
            Icon(Icons.Filled.ExpandMore, contentDescription = null)
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            RsvpStatus.entries.forEach { answer ->
                DropdownMenuItem(
                    text = { Text(stringResource(rsvpLabel(answer))) },
                    trailingIcon = {
                        if (answer == myRsvp) Icon(Icons.Filled.CheckCircle, contentDescription = null)
                    },
                    modifier = Modifier.testTag(rsvpButtonTag(answer)).semantics { selected = answer == myRsvp },
                    onClick = {
                        expanded = false
                        onRsvp(answer)
                    },
                    enabled = enabled,
                )
            }
        }
    }
}

private fun rsvpLabel(answer: RsvpStatus): Int = when (answer) {
    RsvpStatus.GOING -> R.string.events_rsvpGoing
    RsvpStatus.MAYBE -> R.string.events_rsvpMaybe
    RsvpStatus.NOT_GOING -> R.string.events_rsvpNotGoing
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
            canViewAttendees = true,
            rsvpStatus = RsvpStatusUi.Idle,
            onRsvp = {},
            onBack = {},
        )
    }
}
