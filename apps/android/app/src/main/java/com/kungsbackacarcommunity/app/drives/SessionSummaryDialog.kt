package com.kungsbackacarcommunity.app.drives

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.map.DriveRouteFullscreenDialog
import com.kungsbackacarcommunity.app.map.DriveRouteMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Test tag on the end-of-session Keep/Delete summary dialog. */
const val SESSION_SUMMARY_DIALOG_TAG = "session_summary_dialog"

/** Test tag on the are-you-sure confirmation guarding the delete branch. */
const val DELETE_CONFIRM_DIALOG_TAG = "delete_confirm_dialog"

/** Test tag on the show/hide control for the just-driven route map. */
const val SESSION_ROUTE_TOGGLE_TAG = "session_summary_route_toggle"

/** Test tag on the expanded route area (the map, or the note standing in for it). */
const val SESSION_ROUTE_AREA_TAG = "session_summary_route_area"

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
 * transient save failure must not let the drive slip away. The route map added
 * below the stats does not change that: expanding it is an in-place toggle, and
 * its full-screen popup is a separate window whose dismissal only closes itself.
 *
 * @param pointsProvider supplies the recorded fixes for the client-side
 *   [DriveSummary] preview AND the just-driven route map; read once per prompt
 *   (points are frozen after stop and held until the choice resolves).
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
    // The route map starts COLLAPSED — see [SessionRouteSection] for why — and,
    // like the confirmation, survives a recreation so an expanded map is not
    // silently folded away under the user.
    var routeExpanded by rememberSaveable { mutableStateOf(false) }
    var routeFullscreen by rememberSaveable { mutableStateOf(false) }
    val content = rememberSummaryContent(elapsedMillis, pointsProvider, withRoute = true)
    AlertDialog(
        modifier = Modifier.testTag(SESSION_SUMMARY_DIALOG_TAG),
        // Force an explicit choice: back / outside-tap does not dismiss.
        onDismissRequest = {},
        title = { Text(stringResource(R.string.savedDrives_autoSavedTitle)) },
        text = {
            Column(
                // The expanded map makes this the tallest content the dialog can
                // hold. Material3 gives the text slot `weight(1f, fill = false)`,
                // so the Keep/Delete row can never be pushed off-screen — but
                // without this the overflow would be CLIPPED on a short screen.
                // Scrolling keeps every row reachable instead.
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
            ) {
                Text(
                    text = stringResource(R.string.savedDrives_autoSavedBody),
                    style = MaterialTheme.typography.bodyMedium,
                )
                DriveSummaryRows(elapsedMillis = elapsedMillis, preview = content?.preview)
                SessionRouteSection(
                    route = content?.route,
                    expanded = routeExpanded,
                    onToggle = { routeExpanded = !routeExpanded },
                    onOpenFullscreen = { routeFullscreen = true },
                )
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

    // The zoomable full-screen route map, reusing History's popup so a route
    // looks and behaves identically wherever it is shown. Its own window, so
    // dismissing it returns to the (still non-dismissible) summary. Re-checks
    // that the route is drawable: the flag is only settable from the drawable
    // branch, and re-checking keeps an empty popup impossible.
    val route = content?.route
    if (routeFullscreen && route != null && route.size >= SessionRoutePreview.MIN_DRAWABLE_POINTS) {
        DriveRouteFullscreenDialog(points = route, onDismiss = { routeFullscreen = false })
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
                // No route map on the failure path: nothing was stored, so the
                // question is "save it or not", not "look at where you drove" —
                // hence withRoute = false, which skips the conversion entirely.
                DriveSummaryRows(
                    elapsedMillis = elapsedMillis,
                    preview =
                        rememberSummaryContent(
                            elapsedMillis = elapsedMillis,
                            pointsProvider = pointsProvider,
                            withRoute = false,
                        )?.preview,
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
 * Everything the prompts derive from the recorded fixes, resolved together off
 * ONE snapshot of them.
 *
 * @param preview the client-side distance / average-speed estimate.
 * @param route the just-driven route for the map. EMPTY both when the drive is
 *   too short to draw (see [SessionRoutePreview]) and when the caller asked for
 *   no route at all — the prompts that pass `withRoute = false` never read it.
 */
private data class SummaryContent(
    val preview: DriveSummaryPreview,
    val route: List<RoutePoint>,
)

/**
 * Resolves the [SummaryContent] for a stopped recording. A recording can hold up
 * to DriveRecorder.MAX_ROUTE_POINTS (20k) fixes, the distance scan is O(n)
 * Haversine/trig and the route conversion is O(n) allocation, so BOTH run on a
 * background dispatcher and the caller renders placeholders until they land.
 * Points are frozen once recording stops, so keying on [elapsedMillis] (stable
 * for a given stop) computes this exactly once per prompt — and taking a single
 * [pointsProvider] snapshot for both means the up-to-20k-point copy happens once,
 * not once per consumer.
 *
 * @param withRoute false for a prompt that shows no map, which skips the O(n)
 *   route conversion outright rather than allocating a list nothing will draw.
 */
@Composable
private fun rememberSummaryContent(
    elapsedMillis: Long,
    pointsProvider: () -> List<RecordedPoint>,
    withRoute: Boolean,
): SummaryContent? {
    val content by
        produceState<SummaryContent?>(initialValue = null, elapsedMillis, withRoute) {
            value = withContext(Dispatchers.Default) {
                val points = pointsProvider()
                SummaryContent(
                    preview = DriveSummary.preview(points, elapsedMillis),
                    route =
                        if (withRoute) SessionRoutePreview.routePoints(points) else emptyList(),
                )
            }
        }
    return content
}

/**
 * The just-driven route, shown under the stats of the "Drive saved" summary with
 * a control to expand it into view and minimize it again.
 *
 * ## Collapsed by DEFAULT
 * The dialog's job at this moment is the Keep/Delete choice, and it already
 * carries a title, a body line and three stat rows. On a short phone an
 * always-open map would leave the choice needing a scroll to reach; keeping it
 * shut means the dialog is never taller than it is today, and the map is one
 * clearly labelled tap away. It also keeps the GL surface uninflated unless it
 * is actually wanted, so the dialog still appears instantly at the moment the
 * user stops — the point where any extra work is most noticeable.
 *
 * ## Two levels of "bigger"
 * Expanded shows History's static thumbnail ([DriveRouteMap], gestures off);
 * tapping THAT opens History's zoomable full-screen popup with the per-km
 * markers ([DriveRouteFullscreenDialog]). Both are reused as-is, so the route the
 * user sees here is drawn by exactly the same code that will draw it in History.
 *
 * @param route the just-driven route: null while the conversion is still running,
 *   EMPTY when the drive has too few fixes to draw a line, otherwise the drawable
 *   route. Each of the three renders a different thing (see the `when` below).
 */
@Composable
private fun SessionRouteSection(
    route: List<RoutePoint>?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenFullscreen: () -> Unit,
) {
    // A real Mapbox token is required to render the GL map; the config-less / CI
    // build has none and falls back to History's placeholder line.
    val hasMapboxToken = stringResource(R.string.mapbox_access_token).isNotBlank()
    val toggleLabel =
        stringResource(
            if (expanded) {
                R.string.savedDrives_sessionRouteHide
            } else {
                R.string.savedDrives_sessionRouteShow
            },
        )
    TextButton(
        onClick = onToggle,
        modifier =
            Modifier
                .testTag(SESSION_ROUTE_TOGGLE_TAG)
                // The chevron carries no description of its own (it would read out
                // twice), so name the whole control explicitly rather than leaning
                // on the button text alone.
                .semantics(mergeDescendants = true) { contentDescription = toggleLabel },
    ) {
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(KccSpacing.s5),
        )
        Spacer(modifier = Modifier.width(KccSpacing.s1))
        Text(text = toggleLabel)
    }

    if (!expanded) return

    val expandLabel = stringResource(R.string.savedDrives_routeExpand)
    // TalkBack label for the whole tap target: the child is an AndroidView-hosted
    // MapView (an a11y black box), so the clickable node needs its OWN
    // contentDescription — the onClickLabel only names the action.
    val thumbnailLabel = stringResource(R.string.savedDrives_routeMapThumbnailLabel)
    when {
        // No token (config-less or CI build): the GL map cannot render, so
        // explain rather than show an empty rectangle.
        !hasMapboxToken ->
            RouteNote(
                text = stringResource(R.string.savedDrives_routeOverviewPlaceholder),
                modifier = Modifier.testTag(SESSION_ROUTE_AREA_TAG),
            )

        // The conversion has not landed yet (a long route, first frame).
        route == null ->
            RouteNote(
                text = stringResource(R.string.savedDrives_routeLoading),
                modifier = Modifier.testTag(SESSION_ROUTE_AREA_TAG),
            )

        // Too few fixes to draw a line — a stationary or permission-less session.
        route.size < SessionRoutePreview.MIN_DRAWABLE_POINTS ->
            RouteNote(
                text = stringResource(R.string.savedDrives_routeEmpty),
                modifier = Modifier.testTag(SESSION_ROUTE_AREA_TAG),
            )

        else ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(SESSION_ROUTE_MAP_HEIGHT)
                        .testTag(SESSION_ROUTE_AREA_TAG)
                        .clickable(onClickLabel = expandLabel) { onOpenFullscreen() }
                        .semantics(mergeDescendants = true) {
                            contentDescription = thumbnailLabel
                        },
            ) {
                DriveRouteMap(points = route, modifier = Modifier.fillMaxSize())
                // Non-interactive affordance (the whole thumbnail is the tap
                // target); translucent so it reads over any basemap tile.
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.TopEnd).padding(KccSpacing.s2),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Fullscreen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(KccSpacing.s1).size(24.dp),
                    )
                }
            }
    }
}

/**
 * Height of the expanded inline route map. Shorter than History's 240dp
 * thumbnail: this one sits inside a dialog that also has to fit the stats and
 * the Keep/Delete row.
 */
private val SESSION_ROUTE_MAP_HEIGHT = 180.dp

/**
 * The distance / average speed / duration rows shared by the Keep/Delete and
 * save-failed prompts. [preview] is null until the background scan lands, and the
 * rows render the em-dash placeholder in the meantime.
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
