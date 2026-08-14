package com.kungsbackacarcommunity.app.drives

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Test tag on the end-of-session dialog (now only shown on a save failure). */
const val SESSION_SUMMARY_DIALOG_TAG = "session_summary_dialog"

/**
 * The end-of-session dialog shown when a Single / Convoy live session ends.
 *
 * A finished live session's drive is recorded alongside it and AUTO-SAVED via the
 * `drives-save` callable (which recomputes the authoritative stats server-side),
 * and — since #853 — it is KEPT automatically: stopping a session no longer opens
 * a Keep/Delete prompt over the already-saved drive. Every drive lands in History
 * by default, and the user removes an unwanted one from the History list instead
 * ([DrivesScreen]'s per-row / detail delete).
 *
 * So this dialog is now purely the never-lose-a-drive SAFETY NET: it renders only
 * on [RecordingState.Failed], when the background save has exhausted its retries
 * (or hit a permanent refusal). Every other state renders nothing — the auto-save
 * + auto-keep flow resolves in the background and the host dismisses on the
 * terminal Kept / Deleted / Discarded.
 *
 * - [RecordingState.Failed] → the summary + an error line; a retryable fault
 *   offers Retry, a permanent member-gate refusal (which nothing was saved for)
 *   offers Close, which discards.
 *
 * The prompt is intentionally NOT dismissible by back / outside tap: a transient
 * save failure must not let the drive slip away silently.
 *
 * @param pointsProvider supplies the recorded fixes for the client-side
 *   [DriveSummary] preview shown on the failure prompt; read once per prompt
 *   (points are frozen after stop and held until the choice resolves).
 */
@Composable
fun SessionSummaryDialog(
    state: RecordingState,
    pointsProvider: () -> List<RecordedPoint>,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    when (state) {
        is RecordingState.Failed ->
            SaveFailedPrompt(
                elapsedMillis = state.elapsedMillis,
                pointsProvider = pointsProvider,
                isPermanentRefusal = state.isPermanentRefusal,
                onRetry = onRetry,
                onDiscard = onDiscard,
            )

        // Every non-failure state renders nothing: the drive auto-saves and is
        // auto-kept in the background (#853), so there is no user-facing prompt to
        // show for the normal stop path. The host dismisses on Kept / Deleted /
        // Discarded.
        RecordingState.Saving,
        is RecordingState.PromptSave,
        is RecordingState.SavedPendingChoice,
        is RecordingState.KeptPendingSave,
        RecordingState.Deleting,
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
                DriveSummaryRows(
                    elapsedMillis = elapsedMillis,
                    preview = rememberSummaryPreview(elapsedMillis, pointsProvider),
                )
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
 * The client-side distance / average-speed / top-speed estimate for the failure
 * prompt, computed off the recorded fixes on a background dispatcher. A recording
 * can hold up to [DriveRecorder.MAX_ROUTE_POINTS] (20k) fixes and the distance
 * scan is O(n) Haversine/trig, so it runs off the main thread and the caller
 * renders the em-dash placeholders until it lands. Points are frozen once
 * recording stops, so keying on [elapsedMillis] computes this once per prompt.
 */
@Composable
private fun rememberSummaryPreview(
    elapsedMillis: Long,
    pointsProvider: () -> List<RecordedPoint>,
): DriveSummaryPreview? {
    val preview by
        produceState<DriveSummaryPreview?>(initialValue = null, elapsedMillis) {
            value = withContext(Dispatchers.Default) {
                DriveSummary.preview(pointsProvider(), elapsedMillis)
            }
        }
    return preview
}

/**
 * The distance / average speed / top speed / duration rows on the save-failed
 * prompt. [preview] is null until the background scan lands, and the rows render
 * the em-dash placeholder in the meantime.
 */
@Composable
private fun DriveSummaryRows(elapsedMillis: Long, preview: DriveSummaryPreview?) {
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
        label = stringResource(R.string.savedDrives_topSpeed),
        value = DriveFormatters.formatSpeed(preview?.topSpeedMetersPerSecond),
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
        verticalAlignment = Alignment.CenterVertically,
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
