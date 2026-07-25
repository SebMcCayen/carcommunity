package com.kungsbackacarcommunity.app.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccAlpha
import com.kungsbackacarcommunity.app.design.KccSpacing

/**
 * The once-per-version "what's new" popup shown after an app UPDATE (never on
 * first install — see the wiring in AuthenticatedApp): the newest unseen
 * release's highlights as a short bulleted list, an "…and more" hint when
 * several versions were skipped, and a button to the full "Vad är nytt" page.
 *
 * Follows the shell's [LiveSharePromptDialog] pattern: a translucent-surfaced
 * Material3 [AlertDialog], dismissable via outside tap / Back / the close
 * button — every dismissal path is the caller's [onDismiss], which records the
 * current version so the popup shows at most once per version.
 */
@Composable
fun WhatsNewDialog(
    announcement: UpdateAnnouncement,
    onShowAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // Translucent surface so the map home stays visible behind the popup,
        // matching the shell's other transparent prompts (shared Aero token).
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = KccAlpha.aeroSurface),
        title = {
            Text(
                stringResource(
                    R.string.whatsNew_dialogTitle,
                    announcement.entry.versionName,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                announcement.entry.highlights
                    .take(Changelog.POPUP_HIGHLIGHT_LIMIT)
                    .forEach { highlight -> BulletLine(highlight) }
                if (announcement.includesEarlierVersions) {
                    Text(
                        text = stringResource(R.string.whatsNew_dialogMoreVersions),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onShowAll) {
                Text(stringResource(R.string.whatsNew_dialogShowAll))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.whatsNew_dialogClose))
            }
        },
    )
}

/** One "• text" bullet line; the bullet hangs so wrapped lines stay indented. */
@Composable
internal fun BulletLine(text: String) {
    Row {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = text,
            modifier = Modifier.padding(start = KccSpacing.s2),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
