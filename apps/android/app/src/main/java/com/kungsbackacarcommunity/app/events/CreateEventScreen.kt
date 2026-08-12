package com.kungsbackacarcommunity.app.events

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.navigation.CurrentLocation
import com.kungsbackacarcommunity.app.shell.AeroPage
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date

/**
 * User-facing "create event" form (Aero themed). Owns its field state and
 * reports a validated [CreateEventInput] on submit; the route wires it to
 * [CreateEventCoordinator].
 *
 * An active member may create an event: `events-create` publishes it straight
 * away and admins moderate afterwards, so the form promises immediate
 * publication and no approval wait. Two deliberate omissions:
 *  - No `isOfficial` toggle. The callable forces the flag false for
 *    member-created events, so a control here would silently do nothing.
 *  - No "pending approval" language. The event is live the moment it is
 *    created; the only after-the-fact action is an admin takedown.
 * The 3-per-rolling-24h cap gets its own message
 * ([CreateEventFailure.RATE_LIMITED]) rather than a generic failure.
 */
@Composable
fun CreateEventScreen(
    status: CreateEventStatusUi,
    onSubmit: (CreateEventInput) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    // Creator opt-in to the public homepage + public event page. OFF by
    // default — an event never reaches the open web unless its creator asks.
    var publicSiteEnabled by rememberSaveable { mutableStateOf(false) }
    var startsAtMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    // Map-pin coordinates captured by the location picker (both set or both null).
    var latitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var longitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var showLocationPicker by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    // Date chosen in the date step, held until the time step completes it.
    var pendingDateMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var showValidation by rememberSaveable { mutableStateOf(false) }

    val saving = status == CreateEventStatusUi.Saving
    val startsAt = startsAtMillis
    val hasMapToken = stringResource(R.string.mapbox_access_token).isNotBlank()

    // The user's OWN location, used to open the map picker centred on where they
    // are rather than a fixed town. It is only a camera HINT — never submitted
    // unless the user confirms a pin — so a null result (permission denied /
    // location unavailable / CI) simply leaves the picker on its Kungsbacka
    // fallback. Fetched the FIRST time the picker opens (not on form entry), so a
    // member who never picks a map point is never located; then cached for
    // re-opens. lastKnown is instant when a cached fix exists and degrades to
    // null (never throws) without the location permission, reusing the app's
    // existing one-shot source; it adds no new permission flow. If the fix lands
    // after the picker is already showing, the picker recentres to it.
    val context = LocalContext.current
    // All three persist together (rememberSaveable) so the "already fetched" flag
    // and the coords it produced stay CONSISTENT across a config change / process
    // recreation: after a rotation the flag restores as true AND the coords
    // restore alongside it, so the picker still opens on the user's location. If
    // the flag were saveable but the coords were not, a recreation would restore
    // the flag true with null coords and the one-shot fetch — gated on the flag —
    // would never run again, stranding the picker on the Kungsbacka fallback.
    var userLatitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var userLongitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var userLocationRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showLocationPicker) {
        if (showLocationPicker && !userLocationRequested) {
            userLocationRequested = true
            CurrentLocation.lastKnown(context)?.let { fix ->
                userLatitude = fix.latitude
                userLongitude = fix.longitude
            }
        }
    }

    // System/gesture Back while the picker is open closes it rather than leaving
    // the create flow (the route's own Back handles the form-level exit).
    BackHandler(enabled = showLocationPicker) { showLocationPicker = false }

    // The location picker takes over the whole screen while open; confirming
    // captures the pin's coordinate, cancelling leaves the coordinate unchanged.
    if (showLocationPicker) {
        EventLocationPickerScreen(
            initialLatitude = latitude,
            initialLongitude = longitude,
            userLatitude = userLatitude,
            userLongitude = userLongitude,
            hasToken = hasMapToken,
            onConfirm = { lat, lng ->
                latitude = lat
                longitude = lng
                showLocationPicker = false
            },
            onCancel = { showLocationPicker = false },
        )
        return
    }

    AeroPage(
        title = stringResource(R.string.events_createTitle),
        modifier = modifier,
        // Consume the IME (and nav-bar) inset so the create button and lower fields
        // aren't hidden behind the keyboard/navigation bar on edge-to-edge devices
        // (matches FeedbackReportScreen / issue #818).
        contentWindowInsets = WindowInsets.ime.union(WindowInsets.navigationBars),
    ) {
        Text(
            text = stringResource(R.string.events_createSubtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Sets the expectation the backend actually honours: published on
        // submit, moderated afterwards.
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.events_createLiveNotice),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(text = stringResource(R.string.events_createFieldTitle)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Start date/time.
        Text(
            text =
                startsAt?.let {
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
                } ?: stringResource(R.string.events_createNoDate),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.events_createPickStart))
        }

        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text(text = stringResource(R.string.events_createFieldDescription)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text(text = stringResource(R.string.events_createFieldAddress)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Map location picker: opens the full-screen map with a centre pin. The
        // status line reflects whether a pin has been placed, and a placed pin can
        // be cleared. The event's pin is PUBLIC (shown on everyone's map).
        val lat = latitude
        val lng = longitude
        Text(
            text =
                if (lat != null && lng != null) {
                    val coords = String.format(java.util.Locale.US, "%.5f, %.5f", lat, lng)
                    stringResource(R.string.events_createLocationSet, coords)
                } else {
                    stringResource(R.string.events_createLocationNone)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { showLocationPicker = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text =
                    stringResource(
                        if (latitude != null && longitude != null) {
                            R.string.events_createLocationEdit
                        } else {
                            R.string.events_createLocationPick
                        },
                    ),
            )
        }
        if (latitude != null && longitude != null) {
            TextButton(
                onClick = {
                    latitude = null
                    longitude = null
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.events_createLocationClear))
            }
        }

        // Public homepage opt-in. The whole row toggles (checkbox + label are
        // one touch target via toggleable), and the helper text spells out
        // exactly what becomes public — the creator is putting their event on
        // the open web, so the decision must be informed.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = publicSiteEnabled,
                        role = Role.Checkbox,
                        enabled = !saving,
                        onValueChange = { publicSiteEnabled = it },
                    ),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = publicSiteEnabled,
                // Handled by the row's toggleable so the label is tappable too.
                onCheckedChange = null,
            )
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    text = stringResource(R.string.events_createPublicSiteLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.events_createPublicSiteHelp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (showValidation) {
            Text(
                text = stringResource(R.string.events_createValidation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (status is CreateEventStatusUi.Failed) {
            // A rate limit is not a fault the member can retry away, so it gets
            // its own message naming the real cap instead of "please try again".
            Text(
                text =
                    when (status.reason) {
                        CreateEventFailure.RATE_LIMITED ->
                            stringResource(
                                R.string.events_createRateLimited,
                                Events.MEMBER_EVENT_RATE_LIMIT_PER_DAY,
                            )
                        CreateEventFailure.UNKNOWN -> stringResource(R.string.events_createError)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = {
                val input =
                    startsAt?.let {
                        // "Approximate area", "Short summary" and "Location name"
                        // inputs were removed from this form (2026-08); they are
                        // optional server-side, so we omit them entirely.
                        CreateEventInput(
                            title = title,
                            startsAtMillis = it,
                            description = description.ifBlank { null },
                            address = address.ifBlank { null },
                            latitude = latitude,
                            longitude = longitude,
                            publicSiteEnabled = publicSiteEnabled,
                        )
                    }
                if (input == null || !Events.isValidForCreate(input)) {
                    showValidation = true
                } else {
                    showValidation = false
                    onSubmit(input)
                }
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text =
                    stringResource(
                        if (saving) R.string.events_createSaving else R.string.events_createSubmit,
                    ),
            )
        }
        TextButton(onClick = onCancel, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.events_createCancel))
        }
    }

    if (showDatePicker) {
        StartDatePicker(
            onDismiss = { showDatePicker = false },
            onDatePicked = { dateMillis ->
                pendingDateMillis = dateMillis
                showDatePicker = false
                showTimePicker = true
            },
        )
    }

    if (showTimePicker) {
        StartTimePicker(
            onDismiss = { showTimePicker = false },
            onTimePicked = { hour, minute ->
                val date = pendingDateMillis
                if (date != null) {
                    val localDate = Instant.ofEpochMilli(date).atZone(ZoneOffset.UTC).toLocalDate()
                    startsAtMillis =
                        localDate
                            .atTime(hour, minute)
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli()
                }
                showTimePicker = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartDatePicker(
    onDismiss: () -> Unit,
    onDatePicked: (Long) -> Unit,
) {
    val state = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let(onDatePicked) },
                enabled = state.selectedDateMillis != null,
            ) {
                Text(text = stringResource(R.string.events_createDialogNext))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.events_createCancel))
            }
        },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartTimePicker(
    onDismiss: () -> Unit,
    onTimePicked: (Int, Int) -> Unit,
) {
    val state = rememberTimePickerState(is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onTimePicked(state.hour, state.minute) }) {
                Text(text = stringResource(R.string.events_createDialogConfirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.events_createCancel))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.events_createPickTime))
                Row { TimePicker(state = state) }
            }
        },
    )
}

@Preview(name = "Events – create", showBackground = true)
@Composable
private fun CreateEventPreview() {
    KccTheme {
        CreateEventScreen(
            status = CreateEventStatusUi.Idle,
            onSubmit = {},
            onCancel = {},
        )
    }
}
