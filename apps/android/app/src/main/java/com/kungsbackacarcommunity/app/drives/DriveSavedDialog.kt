package com.kungsbackacarcommunity.app.drives

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** Test tag on the "drive saved" confirmation dialog. */
const val DRIVE_SAVED_DIALOG_TAG = "drive_saved_dialog"

/** Test tag on the dialog's OK (dismiss) action. */
const val DRIVE_SAVED_DIALOG_OK_TAG = "drive_saved_dialog_ok"

/** Test tag on the dialog's History (open Drives/History) action. */
const val DRIVE_SAVED_DIALOG_HISTORY_TAG = "drive_saved_dialog_history"

/**
 * Confirmation dialog shown when a SINGLE live session stops and its drive is
 * auto-kept (#853/#856): the drive is saved to History in the background, so this
 * is purely informational — NOT a Keep/Delete decision. The owner asked for a
 * window (rather than the earlier snackbar) so the confirmation can't be missed
 * while it briefly floats past.
 *
 * Two actions:
 * - [onDismiss] (OK): acknowledge and close.
 * - [onHistory] (History): jump to the Drives/History route, then close.
 *
 * The host raises it OPTIMISTICALLY the instant the drive is committed to keep —
 * while the background `drives-save` may still be settling — so ending a session
 * feels instant rather than waiting on the save's network round-trip (see
 * [DriveSaveConfirmation] and the reconcile effect in AuthenticatedApp). The
 * background save then reconciles the optimistic UI: a definitive SUCCESS keeps
 * this window up (and fills in the ride id its History action deep-links to),
 * while a definitive FAILURE RETRACTS it so the failure safety-net
 * ([SessionSummaryDialog]) is the only surface — the app never leaves a "saved"
 * window standing over a drive that was not persisted. Removing an unwanted drive
 * stays available from History.
 */
@Composable
fun DriveSavedDialog(
    message: String,
    confirmLabel: String,
    historyLabel: String,
    onDismiss: () -> Unit,
    onHistory: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(DRIVE_SAVED_DIALOG_TAG),
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(DRIVE_SAVED_DIALOG_OK_TAG),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(
                // Self-contained: navigate, then always close — so the dialog can
                // never be left open, and no caller has to remember to dismiss it.
                onClick = {
                    onHistory()
                    onDismiss()
                },
                modifier = Modifier.testTag(DRIVE_SAVED_DIALOG_HISTORY_TAG),
            ) {
                Text(historyLabel)
            }
        },
    )
}
