package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
const val SAVE_LOCATION_SHARE_TEST_TAG = "save_location_share"

/**
 * The naming popup raised by the map's "Save this location": it asks the member
 * what to NAME the picked point, or to SHARE it with a friend instead.
 *
 * The name is optional. Leaving it blank is not an error — the caller derives the
 * name from the GPS coordinate (`LocationShare.resolveName`), so a place is never
 * nameless. The [coordinateHint] is shown as the field's supporting placeholder so
 * the member can see what an empty name will become before committing.
 *
 * @param initialName a pre-resolved POI name when the gesture tapped a basemap
 *   place, else blank for a bare long-press.
 * @param coordinateHint the readable "lat, lng" the name falls back to when blank.
 * @param onSave persist the place under the entered (possibly blank) name.
 * @param onShare open the friend picker to share this location under the entered
 *   (possibly blank) name, instead of saving.
 */
@Composable
fun SaveLocationDialog(
    initialName: String,
    coordinateHint: String,
    onSave: (String) -> Unit,
    onShare: (String) -> Unit,
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
            Row(horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                TextButton(
                    onClick = { onShare(name) },
                    modifier = Modifier.testTag(SAVE_LOCATION_SHARE_TEST_TAG),
                ) {
                    Text(stringResource(R.string.shell_saveLocationShare))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.shell_saveLocationCancel))
                }
            }
        },
    )
}
