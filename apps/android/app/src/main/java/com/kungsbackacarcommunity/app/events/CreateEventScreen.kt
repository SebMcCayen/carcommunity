package com.kungsbackacarcommunity.app.events

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
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

    // System/gesture Back while the picker is open closes it rather than leaving
    // the create flow (the route's own Back handles the form-level exit).
    BackHandler(enabled = showLocationPicker) { showLocationPicker = false }

    // The location picker takes over the whole screen while open; confirming
    // captures the pin's coordinate, cancelling leaves the coordinate unchanged.
    if (showLocationPicker) {
        EventLocationPickerScreen(
            initialLatitude = latitude,
            initialLongitude = longitude,
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

    AeroPage(title = stringResource(R.string.events_createTitle), modifier = modifier) {
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
