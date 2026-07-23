package com.kungsbackacarcommunity.app.drives

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Test tag on the end-of-session Keep/Delete summary dialog. */
const val SESSION_SUMMARY_DIALOG_TAG = "session_summary_dialog"

/** Test tag on the are-you-sure confirmation guarding the delete branch. */
const val DELETE_CONFIRM_DIALOG_TAG = "delete_confirm_dialog"

/**
 * The end-of-session summary shown when a Single / Convoy live session ends. The
 * drive was recorded alongside the live session and is AUTO-SAVED the instant the
 * session ends (via the `drives-save` callable, which recomputes the
 * authoritative stats server-side), so it can never be lost by the user missing a
 * Save. This dialog then asks whether to KEEP the just-saved drive (it stays in
 * History) or DELETE it again (removed via the `drives-delete` callable).
 *
 * DELETE is destructive, so it is guarded by a second are-you-sure confirmation
 * ([DELETE_CONFIRM_DIALOG_TAG]); KEEP is not, since it only dismisses.
 *
 * Driven entirely by the [DriveRecordingCoordinator] state:
 * - [RecordingState.PromptSave] (transient — the UI auto-saves from it) and
 *   [RecordingState.Saving] → a non-dismissible "saving" progress dialog.
 * - [RecordingState.SavedPendingChoice] → the summary + Keep/Delete actions
 *   (with a delete-failed error line after a failed delete).
 * - [RecordingState.Deleting] → a non-dismissible "deleting" progress dialog.
 * - [RecordingState.Failed] → the summary + an error line; a retryable fault
 *   offers Retry, a permanent member-gate refusal (which nothing was saved for)
 *   offers Close, which discards.
 * - any other state → nothing (the host dismisses on Kept / Deleted / Discarded).
 *
 * The summary and progress dialogs are intentionally NOT dismissible by
 * back/outside tap: ending a session forces an explicit Keep/Delete choice, and a
 * transient save failure must not let the drive slip away.
 *
 * @param pointsProvider supplies the recorded fixes for the client-side
 *   [DriveSummary] preview; read once per prompt (points are frozen after stop
 *   and held until the choice resolves).
 */
@Composable
fun SessionSummaryDialog(
    state: RecordingState,
    pointsProvider: () -> List<RecordedPoint>,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    when (state) {
        is RecordingState.SavedPendingChoice ->
            KeepOrDeletePrompt(
                elapsedMillis = state.elapsedMillis,
                pointsProvider = pointsProvider,
                deleteFailed = state.deleteFailed,
                onKeep = onKeep,
                onDelete = onDelete,
            )

        is RecordingState.Failed ->
            SaveFailedPrompt(
                elapsedMillis = state.elapsedMillis,
                pointsProvider = pointsProvider,
                isPermanentRefusal = state.isPermanentRefusal,
                onRetry = onRetry,
                onDiscard = onDiscard,
            )

        // The drive is being written. PromptSave is transient for the live flow
        // (the UI auto-saves from it immediately), so render the same saving
        // indicator rather than flashing Save/Discard buttons for a frame.
        RecordingState.Saving,
        is RecordingState.PromptSave,
        -> ProgressDialog(R.string.savedDrives_savingProgress)

        RecordingState.Deleting -> ProgressDialog(R.string.savedDrives_deletingProgress)

        RecordingState.Idle,
        is RecordingState.Recording,
        RecordingState.Saved,
        RecordingState.Kept,
        RecordingState.Deleted,
        RecordingState.Discarded,
        -> Unit
    }
}

@Composable
private fun KeepOrDeletePrompt(
    elapsedMillis: Long,
    pointsProvider: () -> List<RecordedPoint>,
    deleteFailed: Boolean,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
) {
    // Deleting removes the just-saved drive, so it takes a second, explicit
    // confirmation. Held in rememberSaveable: the summary itself survives an
    // Activity recreation (SingleSessionRecording is process-scoped), so the
    // confirmation stacked on top of it must survive one too.
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        modifier = Modifier.testTag(SESSION_SUMMARY_DIALOG_TAG),
        // Force an explicit choice: back / outside-tap does not dismiss.
        onDismissRequest = {},
        title = { Text(stringResource(R.string.savedDrives_autoSavedTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                Text(
                    text = stringResource(R.string.savedDrives_autoSavedBody),
                    style = MaterialTheme.typography.bodyMedium,
                )
                DriveSummaryRows(elapsedMillis = elapsedMillis, pointsProvider = pointsProvider)
                if (deleteFailed) {
                    Text(
                        text = stringResource(R.string.savedDrives_deleteError),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onKeep) {
                Text(text = stringResource(R.string.savedDrives_keepAction))
            }
        },
        dismissButton = {
            // Routed through the are-you-sure confirmation, never straight to
            // onDelete: this removes an already-saved drive.
            TextButton(onClick = { confirmingDelete = true }) {
                Text(text = stringResource(R.string.savedDrives_deleteSessionAction))
            }
        },
    )

    // Composed AFTER the summary, deliberately: each AlertDialog owns a separate
    // window and the LAST one composed is the one on top.
    if (confirmingDelete) {
        DeleteConfirmDialog(
            onConfirm = {
                confirmingDelete = false
                onDelete()
            },
            onCancel = { confirmingDelete = false },
        )
    }
}

@Composable
private fun SaveFailedPrompt(
    elapsedMillis: Long,
    pointsProvider: () -> List<RecordedPoint>,
    isPermanentRefusal: Boolean,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(SESSION_SUMMARY_DIALOG_TAG),
        // Non-dismissible: a transient failure must not let the drive vanish.
        onDismissRequest = {},
        title = { Text(stringResource(R.string.savedDrives_saveFailedTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                DriveSummaryRows(elapsedMillis = elapsedMillis, pointsProvider = pointsProvider)
                Text(
                    text =
                        stringResource(
                            // A member-gate refusal can never succeed on a retry,
                            // so name what is actually wrong instead of "try again".
                            if (isPermanentRefusal) {
                                R.string.savedDrives_memberRequired
                            } else {
                                R.string.savedDrives_saveError
                            },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            if (isPermanentRefusal) {
                // Retrying is futile (the gate refuses every attempt) and nothing
                // was persisted, so allow closing — which discards the drive.
                Button(onClick = onDiscard) {
                    Text(text = stringResource(R.string.savedDrives_closeButton))
                }
            } else {
                Button(onClick = onRetry) {
                    Text(text = stringResource(R.string.savedDrives_retryAction))
                }
            }
        },
    )
}

/**
 * Second confirmation before the just-saved drive is deleted again. Deleting
 * removes it from History permanently, and it sits next to Keep in the summary
 * where a mis-tap would bin the whole drive.
 *
 * Unlike the summary it guards, this one IS dismissible by back/outside tap:
 * dismissing simply returns to the summary, where the Keep/Delete choice still
 * stands and the drive is still safely saved.
 */
@Composable
private fun DeleteConfirmDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag(DELETE_CONFIRM_DIALOG_TAG),
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.savedDrives_deleteSessionConfirmTitle)) },
        text = { Text(stringResource(R.string.savedDrives_deleteSessionConfirmBody)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.savedDrives_deleteSessionConfirmAction),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = stringResource(R.string.savedDrives_deleteSessionConfirmCancel))
            }
        },
    )
}

/**
 * The distance / average speed / duration rows shared by the Keep/Delete and
 * save-failed prompts. A recording can hold up to DriveRecorder.MAX_ROUTE_POINTS
 * (20k) fixes and the distance scan is O(n) Haversine/trig, so it is resolved on
 * a background dispatcher and renders the em-dash placeholder until it lands.
 * Points are frozen once recording stops, so keying on [elapsedMillis] (stable
 * for a given stop) computes this exactly once per prompt.
 */
@Composable
private fun DriveSummaryRows(elapsedMillis: Long, pointsProvider: () -> List<RecordedPoint>) {
    val preview by
        produceState<DriveSummaryPreview?>(initialValue = null, elapsedMillis) {
            value = withContext(Dispatchers.Default) {
                DriveSummary.preview(pointsProvider(), elapsedMillis)
            }
        }
    // O(1), so the duration renders immediately rather than waiting on the scan.
    val durationSeconds = DriveSummary.durationSeconds(elapsedMillis)
    SummaryRow(
        label = stringResource(R.string.savedDrives_distance),
        value = DriveFormatters.formatDistance(preview?.distanceMeters),
    )
    SummaryRow(
        label = stringResource(R.string.savedDrives_averageSpeed),
        value =
            DriveFormatters.formatSpeed(
                DriveFormatters.effectiveAverageSpeed(
                    averageSpeedMetersPerSecond = preview?.averageSpeedMetersPerSecond,
                    distanceMeters = preview?.distanceMeters,
                    durationSeconds = preview?.durationSeconds ?: 0L,
                ),
            ),
    )
    SummaryRow(
        label = stringResource(R.string.savedDrives_duration),
        value = DriveFormatters.formatDuration(durationSeconds),
    )
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun ProgressDialog(@StringRes messageRes: Int) {
    AlertDialog(
        onDismissRequest = {},
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(KccSpacing.s5), strokeWidth = 2.dp)
                Text(text = stringResource(messageRes))
            }
        },
        confirmButton = {},
    )
}
