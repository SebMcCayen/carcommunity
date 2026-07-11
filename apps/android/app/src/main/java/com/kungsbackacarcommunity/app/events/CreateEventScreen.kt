package com.kungsbackacarcommunity.app.events

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
 * BACKEND GAP (flagged, not fixable in the Android lane): the deployed
 * `events-create` callable requires an active admin (requireAdminActor) and the
 * Firestore rules forbid client writes to `events/{id}`. So for a normal member
 * this submit returns permission-denied and surfaces the generic error below.
 * A member-callable createEvent + rules change is needed to actually let users
 * create events — that lives in functions/ + *.rules, out of this lane.
 */
@Composable
fun CreateEventScreen(
    status: CreateEventStatusUi,
    onSubmit: (CreateEventInput) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var area by rememberSaveable { mutableStateOf("") }
    var summary by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var locationName by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var startsAtMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    // Date chosen in the date step, held until the time step completes it.
    var pendingDateMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var showValidation by rememberSaveable { mutableStateOf(false) }

    val saving = status == CreateEventStatusUi.Saving
    val startsAt = startsAtMillis

    AeroPage(title = stringResource(R.string.events_createTitle), modifier = modifier) {
        Text(
            text = stringResource(R.string.events_createSubtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Honest, non-blocking notice about the current backend restriction.
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.events_createRestrictedNotice),
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
        OutlinedTextField(
            value = area,
            onValueChange = { area = it },
            label = { Text(text = stringResource(R.string.events_createFieldArea)) },
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
            value = summary,
            onValueChange = { summary = it },
            label = { Text(text = stringResource(R.string.events_createFieldSummary)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text(text = stringResource(R.string.events_createFieldDescription)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = locationName,
            onValueChange = { locationName = it },
            label = { Text(text = stringResource(R.string.events_createFieldLocationName)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text(text = stringResource(R.string.events_createFieldAddress)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (showValidation) {
            Text(
                text = stringResource(R.string.events_createValidation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (status == CreateEventStatusUi.Failed) {
            Text(
                text = stringResource(R.string.events_createError),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = {
                val input =
                    startsAt?.let {
                        CreateEventInput(
                            title = title,
                            approximateArea = area,
                            startsAtMillis = it,
                            summary = summary.ifBlank { null },
                            description = description.ifBlank { null },
                            locationName = locationName.ifBlank { null },
                            address = address.ifBlank { null },
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
