package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.navigation.SavedPlaces

/** Test tags for the map's save-location naming popup. */
const val SAVE_LOCATION_DIALOG_TEST_TAG = "save_location_dialog"
const val SAVE_LOCATION_NAME_TEST_TAG = "save_location_name"
const val SAVE_LOCATION_SAVE_TEST_TAG = "save_location_save"

/**
 * The naming popup raised by the map's "Save this location": it asks the member
 * what to NAME the picked point before saving.
 *
 * The name is optional. Leaving it blank is not an error — the caller derives the
 * name from the GPS coordinate (`LocationShare.resolveName`), so a place is never
 * nameless. The [coordinateHint] is shown as the field's supporting placeholder so
 * the member can see what an empty name will become before committing.
 *
 * Getting a location out of the app lives elsewhere, so this popup offers only
 * Save/Cancel: the place-actions menu that opens first has "Copy position" (the
 * coordinates to the clipboard), and the Saved-places long-press has a
 * Share-to-a-friend action.
 *
 * @param initialName a pre-resolved POI name when the gesture tapped a basemap
 *   place, else blank for a bare long-press.
 * @param coordinateHint the readable "lat, lng" the name falls back to when blank.
 * @param onSave persist the place under the entered (possibly blank) name.
 */
@Composable
fun SaveLocationDialog(
    initialName: String,
    coordinateHint: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(SAVE_LOCATION_DIALOG_TEST_TAG),
        title = { Text(stringResource(R.string.shell_saveLocationTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                Text(
                    text = coordinateHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(SavedPlaces.MAX_LABEL) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.shell_saveLocationNameLabel)) },
                    placeholder = { Text(coordinateHint) },
                    modifier = Modifier.fillMaxWidth().testTag(SAVE_LOCATION_NAME_TEST_TAG),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name) },
                modifier = Modifier.testTag(SAVE_LOCATION_SAVE_TEST_TAG),
            ) {
                Text(stringResource(R.string.shell_saveLocationSave))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.shell_saveLocationCancel))
            }
        },
    )
}
