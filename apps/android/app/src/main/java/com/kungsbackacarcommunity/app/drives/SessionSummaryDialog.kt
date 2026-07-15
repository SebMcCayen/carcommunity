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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing

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
    when (state) {
        is RecordingState.PromptSave ->
            SummaryPrompt(
                elapsedMillis = state.elapsedMillis,
                pointsProvider = pointsProvider,
                showError = false,
                onSave = onSave,
                onDiscard = onDiscard,
            )

        is RecordingState.Failed ->
            SummaryPrompt(
                elapsedMillis = state.elapsedMillis,
                pointsProvider = pointsProvider,
                showError = true,
                onSave = onSave,
                onDiscard = onDiscard,
            )

        RecordingState.Saving -> SavingDialog()

        RecordingState.Idle,
        is RecordingState.Recording,
        RecordingState.Saved,
        RecordingState.Discarded,
        -> Unit
    }
}

@Composable
private fun SummaryPrompt(
    elapsedMillis: Long,
    pointsProvider: () -> List<RecordedPoint>,
    showError: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    // Points are frozen once recording stops, so compute the estimate once.
    val preview = remember(elapsedMillis) { DriveSummary.preview(pointsProvider(), elapsedMillis) }
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
                SummaryRow(
                    label = stringResource(R.string.savedDrives_distance),
                    value = DriveFormatters.formatDistance(preview.distanceMeters),
                )
                SummaryRow(
                    label = stringResource(R.string.savedDrives_averageSpeed),
                    value =
                        DriveFormatters.formatSpeed(
                            DriveFormatters.effectiveAverageSpeed(
                                averageSpeedMetersPerSecond = preview.averageSpeedMetersPerSecond,
                                distanceMeters = preview.distanceMeters,
                                durationSeconds = preview.durationSeconds,
                            ),
                        ),
                )
                SummaryRow(
                    label = stringResource(R.string.savedDrives_duration),
                    value = DriveFormatters.formatDuration(preview.durationSeconds),
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
