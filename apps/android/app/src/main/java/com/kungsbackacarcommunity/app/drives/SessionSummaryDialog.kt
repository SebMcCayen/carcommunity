package com.kungsbackacarcommunity.app.drives

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
import androidx.compose.runtime.produceState
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
 * Driven entirely by the [DriveRecordingCoordinator] state:
 * - [RecordingState.PromptSave] / [RecordingState.Failed] → the summary + actions
 *   (Failed additionally shows a retry error line).
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
            is RecordingState.PromptSave -> SummaryPromptArgs(state.elapsedMillis, showError = false)
            is RecordingState.Failed -> SummaryPromptArgs(state.elapsedMillis, showError = true)
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
            showError = prompt.showError,
            onSave = onSave,
            onDiscard = onDiscard,
        )
    } else if (state == RecordingState.Saving) {
        SavingDialog()
    }
}

/** The [SummaryPrompt] inputs derived from a prompt-bearing [RecordingState]. */
private data class SummaryPromptArgs(val elapsedMillis: Long, val showError: Boolean)

@Composable
private fun SummaryPrompt(
    elapsedMillis: Long,
    pointsProvider: () -> List<RecordedPoint>,
    showError: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
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
                if (showError) {
                    Text(
                        text = stringResource(R.string.savedDrives_saveError),
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
            TextButton(onClick = onDiscard) {
                Text(text = stringResource(R.string.savedDrives_discardAction))
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
