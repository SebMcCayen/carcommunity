package com.kungsbackacarcommunity.app.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.shell.AeroPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The "Vad är nytt" (changelog) page reached from Settings: the
 * [Changelog.PAGE_ENTRY_LIMIT] most recent releases, newest first — each as a
 * frosted Aero card with the version + release date and a bulleted list of that
 * update's changes. Data comes from the bundled `res/raw/changelog.json`.
 */
@Composable
fun WhatsNewRoute(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // null while the raw-resource read + JSON parse are still running; loading
    // them off the main thread keeps that blocking IO out of composition.
    var entries by remember { mutableStateOf<List<ChangelogEntry>?>(null) }
    LaunchedEffect(context) {
        entries = withContext(Dispatchers.IO) {
            Changelog.latestEntries(ChangelogLoader.load(context))
        }
    }
    when (val loaded = entries) {
        // Still loading: keep the page chrome but show a spinner — NOT the
        // empty-state copy, which would wrongly claim there are no release notes.
        null ->
            AeroPage(title = stringResource(R.string.whatsNew_title), modifier = modifier) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(KccSpacing.s6),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        // Loaded: WhatsNewScreen renders the cards, or its own empty-state copy
        // only when the changelog genuinely has no entries.
        else -> WhatsNewScreen(entries = loaded, modifier = modifier)
    }
}

@Composable
fun WhatsNewScreen(
    entries: List<ChangelogEntry>,
    modifier: Modifier = Modifier,
) {
    AeroPage(title = stringResource(R.string.whatsNew_title), modifier = modifier) {
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.whatsNew_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        entries.forEach { entry -> ReleaseCard(entry) }
    }
}

/** One release: "Version X · date" heading + that update's bulleted changes. */
@Composable
private fun ReleaseCard(entry: ChangelogEntry) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(KccRadius.lg),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            Text(
                text =
                    stringResource(
                        R.string.whatsNew_entryTitle,
                        entry.versionName,
                        entry.releaseDate,
                    ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            entry.changes.forEach { change -> BulletLine(change) }
        }
    }
}
