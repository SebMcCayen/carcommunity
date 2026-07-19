package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.design.KccSpacing
import java.text.Collator
import java.util.Locale

/** A single entry in a hub screen; [onClick] null hides the row (unavailable). */
data class HubEntry(
    val label: String,
    val icon: ImageVector,
    val onClick: (() -> Unit)?,
)

/**
 * Orders hub entries alphabetically by their DISPLAYED, localized
 * [HubEntry.label] under [locale]'s own collation rules — never by enum name,
 * resource key or declaration order, so the list reads alphabetically in
 * whatever language the user is actually seeing.
 *
 * The two locales therefore order DIFFERENTLY, which is correct: e.g. Social's
 * entries sort Billboards → Crown Hunt → Events → Friends → Notifications →
 * Partners in English, but Anslagstavlor → Aviseringar → Event → Kronjakt →
 * Partners → Vänner in Swedish.
 *
 * Uses [Collator] rather than a plain string sort because Kotlin's natural
 * ordering is by UTF-16 code unit, which mis-sorts Swedish: å/ä/ö sort AFTER z
 * in Swedish, but their code points would place them right after z only by luck
 * and, more importantly, "Ä" (U+00C4) would sort after every unaccented capital
 * rather than by Swedish rules. [Collator.SECONDARY] makes the sort ignore case
 * (so a label's capitalization cannot jump it up the list) while still keeping
 * accented letters distinct from their base letters.
 *
 * Stable: [sortedWith] preserves the declared order of entries whose labels
 * collate equally.
 */
fun sortedHubEntriesByLabel(entries: List<HubEntry>, locale: Locale): List<HubEntry> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    return entries.sortedWith { a, b -> collator.compare(a.label, b.label) }
}

/**
 * A simple scrollable hub: a title and a vertical list of navigable entries.
 * Used for the Create (+), Social, Garage, and "More" landings so every
 * previously-reachable destination stays reachable in the redesigned shell.
 * Unavailable entries (null [HubEntry.onClick]) are omitted.
 *
 * Shares the [AeroPage] chrome with every other sub-route; Back is handled by
 * the shell's system-Back handler, so the hub renders no Back affordance.
 */
@Composable
fun HubScreen(
    title: String,
    entries: List<HubEntry>,
    modifier: Modifier = Modifier,
) {
    AeroPage(title = title, modifier = modifier) {
        entries.forEach { entry ->
            val onClick = entry.onClick
            if (onClick != null) {
                HubRow(entry.label, entry.icon, onClick)
            }
        }
    }
}

/**
 * A single navigable row shared by the hub landings and the Settings screen: a
 * tonally-elevated surface with a leading icon and a label.
 */
@Composable
internal fun HubRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(KccSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s4),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(KccSpacing.s6),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
