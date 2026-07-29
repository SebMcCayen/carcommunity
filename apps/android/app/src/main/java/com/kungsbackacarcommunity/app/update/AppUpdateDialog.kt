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
 * The update prompt: a newer build is on Play, go get it — or not now.
 *
 * Two shapes, from one decision:
 *  - [AppUpdateDecision.OPTIONAL] — what Seb asked for. Dismissible by the
 *    "Inte nu" button, by Back and by an outside tap; every one of those
 *    paths runs [onDismiss], which is what records the suppression window.
 *  - [AppUpdateDecision.REQUIRED] — the separate, default-inert
 *    unsupported-version block. No dismiss button, and Back/outside taps do
 *    nothing, because there is nothing useful the user can do in an
 *    unsupported build.
 *
 * Follows the shell's translucent Material3 [AlertDialog] convention so the
 * map home stays visible behind it.
 */
@Composable
fun AppUpdateDialog(
    decision: AppUpdateDecision,
    latestVersionName: String?,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (decision == AppUpdateDecision.NONE) return
    val required = decision == AppUpdateDecision.REQUIRED

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
                when {
                    required -> stringResource(R.string.appUpdate_requiredMessage)
                    // The version name is display text only — it is never what
                    // the decision was made on. Omitted when the server did not
                    // supply one, rather than showing a raw versionCode.
                    latestVersionName != null ->
                        stringResource(R.string.appUpdate_messageWithVersion, latestVersionName)
                    else -> stringResource(R.string.appUpdate_message)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onUpdate) {
                Text(stringResource(R.string.appUpdate_update))
            }
        },
        dismissButton = {
            // Nothing at all in the required case: an unsupported build has no
            // "later" to offer, so there is no button to press.
            if (!required) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.appUpdate_dismiss))
                }
            }
        },
    )
}
