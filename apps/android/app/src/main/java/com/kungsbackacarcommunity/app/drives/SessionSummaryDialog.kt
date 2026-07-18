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

/** Test tag on the end-of-session save/discard summary dialog. */
const val SESSION_SUMMARY_DIALOG_TAG = "session_summary_dialog"

/**
 * The save-or-discard summary shown when a Single (solo live-sharing) session
 * ends. The drive was recorded alongside the live session; this dialog presents
 * the estimated distance / average speed / duration and lets the user SAVE it to
 * History (persisting a [SavedDrive] via the `drives-save` callable, which
 * recomputes the authoritative stats server-side) or DISCARD it (nothing stored,
 * the product's explicit-save rule).
 *
 * DISCARD is irreversible, so it is guarded by a second are-you-sure
 * confirmation ([DISCARD_CONFIRM_DIALOG_TAG]); SAVE is not, since it only adds.
 *
 * Driven entirely by the [DriveRecordingCoordinator] state:
 * - [RecordingState.PromptSave] / [RecordingState.Failed] → the summary + actions
 *   (Failed additionally shows an error line — the member-gate refusal names the
 *   missing membership, anything else offers a retry).
 * - [RecordingState.Saving] → a non-dismissible progress dialog.
 * - any other state → nothing (the host dismisses on Saved / Discarded).
 *
 * The dialog is intentionally NOT dismissible by back/outside tap: ending a
 * session forces an explicit Save/Discard choice so a recorded drive is never
 * silently dropped or silently kept.
 *
 * @param pointsProvider supplies the recorded fixes for the client-side
 *   [DriveSummary] preview; read once per prompt (points are frozen after stop).
 */
@Composable
fun SessionSummaryDialog(
    state: RecordingState,
    pointsProvider: () -> List<RecordedPoint>,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    // PromptSave and Failed render the SAME prompt (Failed only adds an error
    // line), so they resolve to one SummaryPrompt call site rather than two
    // `when` branches. That keeps the composable's identity — and therefore its
    // in-flight preview computation — alive across a failed save + retry instead
    // of restarting the point scan on every transition.
    val prompt: SummaryPromptArgs? =
        when (state) {
            is RecordingState.PromptSave ->
                SummaryPromptArgs(state.elapsedMillis, error = null)
            is RecordingState.Failed ->
                SummaryPromptArgs(
                    state.elapsedMillis,
                    // A member-gate refusal can never succeed on a retry, so say
                    // what is actually wrong instead of "please try again".
                    error =
                        if (state.isPermanentRefusal) {
                            R.string.savedDrives_memberRequired
                        } else {
                            R.string.savedDrives_saveError
                        },
                )
            RecordingState.Saving,
            RecordingState.Idle,
            is RecordingState.Recording,
            RecordingState.Saved,
            RecordingState.Discarded,
            -> null
        }

    if (prompt != null) {
        SummaryPrompt(
            elapsedMillis = prompt.elapsedMillis,
            pointsProvider = pointsProvider,
            error = prompt.error,
            onSave = onSave,
            onDiscard = onDiscard,
        )
    } else if (state == RecordingState.Saving) {
        SavingDialog()
    }
}

/**
 * The [SummaryPrompt] inputs derived from a prompt-bearing [RecordingState].
 *
 * @property error the error string to show under the summary, or null when the
 *   prompt is not in a failed state.
 */
private data class SummaryPromptArgs(val elapsedMillis: Long, @StringRes val error: Int?)

@Composable
private fun SummaryPrompt(
    elapsedMillis: Long,
    pointsProvider: () -> List<RecordedPoint>,
    @StringRes error: Int?,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    // Discarding destroys the recorded drive irrecoverably, so it takes a second,
    // explicit confirmation. Held in rememberSaveable: the prompt itself survives
    // an Activity recreation (SingleSessionRecording is process-scoped), so the
    // confirmation stacked on top of it must survive one too rather than
    // silently reverting to the summary.
    var confirmingDiscard by rememberSaveable { mutableStateOf(false) }
    // A recording can hold up to DriveRecorder.MAX_ROUTE_POINTS (20k) fixes and
    // the distance scan is O(n) Haversine/trig, so computing it during
    // composition would jank the frame the dialog opens on. Resolve it on a
    // background dispatcher instead and render the established em-dash
    // placeholder until it lands. Points are frozen once recording stops, so
    // keying on elapsedMillis (stable for a given stop, including across a
    // failed save + retry) computes this exactly once per prompt.
    val preview by
        produceState<DriveSummaryPreview?>(initialValue = null, elapsedMillis) {
            value = withContext(Dispatchers.Default) {
                DriveSummary.preview(pointsProvider(), elapsedMillis)
            }
        }
    // O(1), so the duration renders immediately rather than waiting on the scan.
    val durationSeconds = DriveSummary.durationSeconds(elapsedMillis)
    AlertDialog(
        modifier = Modifier.testTag(SESSION_SUMMARY_DIALOG_TAG),
        // Force an explicit choice: a session's drive is never silently dropped
        // or silently kept, so back / outside-tap does not dismiss.
        onDismissRequest = {},
        title = { Text(stringResource(R.string.savedDrives_promptTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                Text(
                    text = stringResource(R.string.savedDrives_promptBody),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // While [preview] is still resolving these render "—", the same
                // placeholder the formatters already use for an unknown value.
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
                Text(
                    text = stringResource(R.string.savedDrives_promptPrivacyNote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    Text(
                        text = stringResource(error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text(text = stringResource(R.string.savedDrives_saveAction))
            }
        },
        dismissButton = {
            // Routed through the are-you-sure confirmation, never straight to
            // onDiscard: this is the irreversible branch.
            TextButton(onClick = { confirmingDiscard = true }) {
                Text(text = stringResource(R.string.savedDrives_discardAction))
            }
        },
    )

    // Composed AFTER the summary, deliberately: each AlertDialog owns a separate
    // window and the LAST one composed is the one on top. Declared before the
    // summary it would open behind it, leaving the user staring at an
    // unresponsive-looking summary.
    if (confirmingDiscard) {
        DiscardConfirmDialog(
            onConfirm = {
                confirmingDiscard = false
                onDiscard()
            },
            onCancel = { confirmingDiscard = false },
        )
    }
}

/** Test tag on the are-you-sure confirmation guarding the discard branch. */
const val DISCARD_CONFIRM_DIALOG_TAG = "discard_confirm_dialog"

/**
 * Second confirmation before the recorded drive is destroyed. Discard is
 * irreversible — the points live only in memory, so there is nothing to undo
 * from — and it sits next to Save in the summary, where a mis-tap would silently
 * bin the whole drive.
 *
 * Unlike the summary it guards, this one IS dismissible by back/outside tap:
 * dismissing simply returns to the summary, where the forced Save/Discard choice
 * still stands, so no drive can slip through unresolved.
 */
@Composable
private fun DiscardConfirmDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag(DISCARD_CONFIRM_DIALOG_TAG),
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.savedDrives_discardConfirmTitle)) },
        text = { Text(stringResource(R.string.savedDrives_discardConfirmBody)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.savedDrives_discardConfirmAction),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = stringResource(R.string.savedDrives_discardConfirmCancel))
            }
        },
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
private fun SavingDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.savedDrives_saveAction)) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(KccSpacing.s5), strokeWidth = 2.dp)
                Text(text = stringResource(R.string.savedDrives_saveAction))
            }
        },
        confirmButton = {},
    )
}
