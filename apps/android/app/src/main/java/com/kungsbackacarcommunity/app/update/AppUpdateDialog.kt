package com.kungsbackacarcommunity.app.update

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccAlpha

/**
 * The update prompt: Google Play has a newer build, go get it — or not now.
 *
 * Two shapes, from one decision:
 *  - [AppUpdateDecision.FLEXIBLE] — the normal case. Dismissible by the
 *    "Inte nu" button, by Back and by an outside tap; every one of those
 *    paths runs [onDismiss], which is what records the suppression window.
 *    Accepting hands off to Play's BACKGROUND download, so the app stays
 *    usable — nothing is interrupted, including a drive in progress.
 *  - [AppUpdateDecision.IMMEDIATE] — the default-inert blocking path, reached
 *    only for a release published at Play's top `inAppUpdatePriority` or to
 *    resume a blocking flow already begun. No dismiss button, and
 *    Back/outside taps do nothing.
 *
 * [AppUpdateDecision.AWAITING_RESTART] is deliberately NOT a dialog — a
 * finished download is good news, not an interruption, so the shell offers the
 * restart in a snackbar instead.
 *
 * There is no version number in the copy: Play's In-App Updates API reports a
 * `versionCode`, not the `versionName` a person would recognise, and showing a
 * raw build integer to a member is worse than showing nothing.
 *
 * Follows the shell's translucent Material3 [AlertDialog] convention so the
 * map home stays visible behind it.
 */
@Composable
fun AppUpdateDialog(
    decision: AppUpdateDecision,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (decision != AppUpdateDecision.FLEXIBLE && decision != AppUpdateDecision.IMMEDIATE) return
    val required = decision == AppUpdateDecision.IMMEDIATE

    AlertDialog(
        onDismissRequest = { if (!required) onDismiss() },
        properties =
            DialogProperties(
                dismissOnBackPress = !required,
                dismissOnClickOutside = !required,
            ),
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = KccAlpha.aeroSurface),
        title = {
            Text(
                stringResource(
                    if (required) R.string.appUpdate_requiredTitle else R.string.appUpdate_title,
                ),
            )
        },
        text = {
            Text(
                stringResource(
                    if (required) {
                        R.string.appUpdate_requiredMessage
                    } else {
                        R.string.appUpdate_message
                    },
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onUpdate) {
                Text(stringResource(R.string.appUpdate_update))
            }
        },
        dismissButton = {
            // Nothing at all in the blocking case: there is no "later" to
            // offer, so there is no button to press.
            if (!required) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.appUpdate_dismiss))
                }
            }
        },
    )
}
