package com.kungsbackacarcommunity.app.drives

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.kungsbackacarcommunity.app.design.KccSpacing

/** Test tag on the "drive saved" confirmation snackbar. */
const val DRIVE_SAVED_SNACKBAR_TAG = "drive_saved_snackbar"

/** Test tag on the snackbar's Undo (delete the just-saved drive) action. */
const val DRIVE_SAVED_SNACKBAR_UNDO_TAG = "drive_saved_snackbar_undo"

/** Test tag on the snackbar's View (open the drive in History) action. */
const val DRIVE_SAVED_SNACKBAR_VIEW_TAG = "drive_saved_snackbar_view"

/**
 * Snackbar shown when a SINGLE live session stops and its drive is auto-kept
 * (#853/#856): the drive lands in History by default, and this non-blocking
 * confirmation gives two ways to act on it before it auto-dismisses —
 * - Undo: delete the just-saved ride (the delete-from-History mechanism), the
 *   replacement for the old "Delete" choice;
 * - View: open that ride's detail in History.
 *
 * A two-action snackbar is not expressible with the default `actionLabel` slot, so
 * this carries the labels + callbacks as custom [SnackbarVisuals] and is rendered
 * by [DriveSavedSnackbar] from the shell's `SnackbarHost` content. It is only used
 * on the KEPT path — a save FAILURE is still handled by [SessionSummaryDialog].
 */
class DriveSavedSnackbarVisuals(
    override val message: String,
    val undoLabel: String,
    val viewLabel: String,
    val onUndo: () -> Unit,
    val onView: () -> Unit,
) : SnackbarVisuals {
    // Two custom actions are rendered by DriveSavedSnackbar, not the default slot.
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = false
    // Actionable but non-blocking: long enough to reach, then auto-dismisses.
    override val duration: SnackbarDuration = SnackbarDuration.Long
}

/**
 * Renders a [DriveSavedSnackbarVisuals] with its two actions. Tapping either runs
 * the action and dismisses the snackbar; it also auto-dismisses on its own
 * duration if untouched. Drop this into a `SnackbarHost` content lambda, falling
 * back to the default `Snackbar(data)` for every other snackbar.
 */
@Composable
fun DriveSavedSnackbar(data: SnackbarData, visuals: DriveSavedSnackbarVisuals) {
    Snackbar(
        modifier = Modifier.testTag(DRIVE_SAVED_SNACKBAR_TAG),
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(KccSpacing.s1)) {
                TextButton(
                    onClick = {
                        visuals.onView()
                        data.dismiss()
                    },
                    modifier = Modifier.testTag(DRIVE_SAVED_SNACKBAR_VIEW_TAG),
                ) {
                    Text(
                        text = visuals.viewLabel,
                        color = MaterialTheme.colorScheme.inversePrimary,
                    )
                }
                TextButton(
                    onClick = {
                        visuals.onUndo()
                        data.dismiss()
                    },
                    modifier = Modifier.testTag(DRIVE_SAVED_SNACKBAR_UNDO_TAG),
                ) {
                    Text(
                        text = visuals.undoLabel,
                        color = MaterialTheme.colorScheme.inversePrimary,
                    )
                }
            }
        },
    ) {
        Text(visuals.message)
    }
}
