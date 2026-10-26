package com.kungsbackacarcommunity.app.events

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import com.kungsbackacarcommunity.app.design.KccButton as Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.kungsbackacarcommunity.app.design.KccOutlinedButton as OutlinedButton
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
import androidx.compose.ui.platform.testTag
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
import java.util.Date

const val EVENT_EDITOR_TITLE_TAG = "event_editor_title"
const val EVENT_EDITOR_DESCRIPTION_TAG = "event_editor_description"
const val EVENT_EDITOR_ADDRESS_TAG = "event_editor_address"
const val EVENT_EDITOR_START_TAG = "event_editor_start"
const val EVENT_EDITOR_SUBMIT_TAG = "event_editor_submit"

/**
 * User-facing event form (Aero themed) — shared by CREATE and creator EDIT. Owns
 * its field state and reports a validated [CreateEventInput] on submit; the route
 * wires it to [CreateEventCoordinator] (create) or [EditEventCoordinator] (edit).
 *
 * An active member may create an event: `events-create` publishes it straight
 * away and admins moderate afterwards, so the form promises immediate
 * publication and no approval wait. Two deliberate omissions:
 *  - No `isOfficial` toggle. The callable forces the flag false for
 *    member-created events, so a control here would silently do nothing.
 *  - No "pending approval" language. The event is live the moment it is
 *    created; the only after-the-fact action is an admin takedown.
 *
 * EDIT MODE ([isEdit] = true, fields pre-filled from [initialInput]) reuses the
 * exact same inputs and validation, with three differences: the title/subtitle
 * and submit label say "edit"/"save", the "goes live on create" notice is hidden
 * (the event is already live), and the public-homepage checkbox is hidden — the
 * `events-update` payload deliberately never carries `publicSiteEnabled` (that
 * flag is toggled only via events.setPublicSite), so offering it here would be a
 * control the edit path silently ignores.
 *
 * Transient status is passed in decomposed form so the screen is agnostic to
 * which coordinator drives it: [saving] disables the controls and swaps the
 * button label, and [errorMessage] (already resolved by the route — the create
 * rate-limit message, or an edit permission/immutable message) is shown inline.
 */
@Composable
fun CreateEventScreen(
    saving: Boolean,
    errorMessage: String?,
    onSubmit: (CreateEventInput) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    // Pre-fills every field for EDIT mode; null on create (empty form).
    initialInput: CreateEventInput? = null,
    // Switches the form's copy (title/subtitle/submit label) to "edit" and hides
    // the create-only affordances (live notice + public-site opt-in).
    isEdit: Boolean = false,
) {
    var title by rememberSaveable { mutableStateOf(initialInput?.title ?: "") }
    var description by rememberSaveable { mutableStateOf(initialInput?.description ?: "") }
    var address by rememberSaveable { mutableStateOf(initialInput?.address ?: "") }
    // Creator opt-in to the public homepage + public event page. OFF by
    // default — an event never reaches the open web unless its creator asks.
    // Create-only: hidden and never submitted in edit mode.
    var publicSiteEnabled by rememberSaveable { mutableStateOf(initialInput?.publicSiteEnabled ?: false) }
    var startsAtMillis by rememberSaveable { mutableStateOf(initialInput?.startsAtMillis) }
    // Map-pin coordinates captured by the location picker (both set or both null).
    var latitude by rememberSaveable { mutableStateOf(initialInput?.latitude) }
    var longitude by rememberSaveable { mutableStateOf(initialInput?.longitude) }
    var showLocationPicker by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    // Date chosen in the date step, held until the time step completes it.
    var pendingDateMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var showValidation by rememberSaveable { mutableStateOf(false) }

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
        ownerShortcutEnabled = false,
        title = stringResource(if (isEdit) R.string.events_editTitle else R.string.events_createTitle),
        modifier = modifier,
        // Consume the IME (and nav-bar) inset so the create button and lower fields
        // aren't hidden behind the keyboard/navigation bar on edge-to-edge devices
        // (matches FeedbackReportScreen / issue #818).
        contentWindowInsets = WindowInsets.ime.union(WindowInsets.navigationBars),
    ) {
        Text(
            text = stringResource(if (isEdit) R.string.events_editSubtitle else R.string.events_createSubtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(text = stringResource(R.string.events_createFieldTitle)) },
                supportingText = { Text("${title.length}/${Events.TITLE_MAX}") },
                isError = title.length > Events.TITLE_MAX || (showValidation && title.isBlank()),
                enabled = !saving,
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag(EVENT_EDITOR_TITLE_TAG),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(text = stringResource(R.string.events_createFieldDescription)) },
                supportingText = { Text("${description.length}/${Events.DESCRIPTION_MAX}") },
                isError = description.length > Events.DESCRIPTION_MAX,
                enabled = !saving,
                minLines = 4,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag(EVENT_EDITOR_DESCRIPTION_TAG),
            )
        }
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text(text = stringResource(R.string.events_createFieldAddress)) },
            leadingIcon = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
            supportingText = { Text("${address.length}/${Events.ADDRESS_MAX}") },
            isError = address.length > Events.ADDRESS_MAX,
            enabled = !saving,
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().testTag(EVENT_EDITOR_ADDRESS_TAG),
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
            enabled = !saving,
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
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.events_createLocationClear))
            }
        }

        // The same two-step date/time picker, grouped into one readable row.
        OutlinedButton(
            onClick = { showDatePicker = true },
            enabled = !saving,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().testTag(EVENT_EDITOR_START_TAG),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = stringResource(R.string.events_createPickStart), color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = startsAt?.let {
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
                        } ?: stringResource(R.string.events_createNoDate),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // Sets the expectation the backend actually honours: published on
        // submit, moderated afterwards. Create-only — an edit is to an event that
        // is already live, so the notice would be misleading.
        if (!isEdit) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.events_createLiveNotice),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Public homepage opt-in. The whole row toggles (checkbox + label are
        // one touch target via toggleable), and the helper text spells out
        // exactly what becomes public — the creator is putting their event on
        // the open web, so the decision must be informed. Create-only: the
        // events-update payload never carries publicSiteEnabled (that flag is
        // toggled through events.setPublicSite), so it is hidden in edit mode
        // rather than offered as a control the edit path would ignore.
        if (!isEdit) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                        .toggleable(
                            value = publicSiteEnabled,
                            role = Role.Checkbox,
                            enabled = !saving,
                            onValueChange = { publicSiteEnabled = it },
                        ).padding(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = publicSiteEnabled,
                    // Handled by the row's toggleable so the label is tappable too.
                    onCheckedChange = null,
                    // Mirror the row's disabled state so the checkbox looks (and
                    // reads to accessibility services) as disabled while saving.
                    enabled = !saving,
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
        }

        if (showValidation) {
            Text(
                text = stringResource(R.string.events_createValidation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // Already resolved by the route so the screen stays agnostic to which
        // coordinator drives it (create rate-limit / generic, or edit
        // permission-denied / immutable / generic).
        errorMessage?.let { message ->
            Text(
                text = message,
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
            modifier = Modifier.fillMaxWidth().testTag(EVENT_EDITOR_SUBMIT_TAG),
        ) {
            Text(
                text =
                    stringResource(
                        when {
                            saving && isEdit -> R.string.events_editSaving
                            saving -> R.string.events_createSaving
                            isEdit -> R.string.events_editSubmit
                            else -> R.string.events_createSubmit
                        },
                    ),
            )
        }
        TextButton(onClick = onCancel, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.events_createCancel))
        }
    }

    if (showDatePicker) {
        StartDatePicker(
            startsAtMillis = startsAtMillis,
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
            startsAtMillis = startsAtMillis,
            onDismiss = { showTimePicker = false },
            onTimePicked = { hour, minute ->
                val date = pendingDateMillis
                if (date != null) {
                    startsAtMillis = EventEditorTime.combine(date, hour, minute, ZoneId.systemDefault())
                }
                showTimePicker = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartDatePicker(
    startsAtMillis: Long?,
    onDismiss: () -> Unit,
    onDatePicked: (Long) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = startsAtMillis?.let { EventEditorTime.pickerDate(it, ZoneId.systemDefault()) },
    )
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
    startsAtMillis: Long?,
    onDismiss: () -> Unit,
    onTimePicked: (Int, Int) -> Unit,
) {
    val start = startsAtMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
    val state = rememberTimePickerState(initialHour = start?.hour ?: 0, initialMinute = start?.minute ?: 0, is24Hour = true)
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
            saving = false,
            errorMessage = null,
            onSubmit = {},
            onCancel = {},
        )
    }
}
