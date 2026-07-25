package com.kungsbackacarcommunity.app.badges

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.design.KccSpacing

/**
 * The two pieces every badge wall is built from — shared by the member's OWN
 * wall ([com.kungsbackacarcommunity.app.profile.ProfileBadgesSection]) and by
 * another member's public wall on the read-only member-profile screen.
 *
 * Shared on purpose: the medallion, its label and the grid geometry are the
 * badge system's visual identity, and two copies would drift the moment one
 * screen changed a size. What is NOT shared is everything about progress —
 * bars, counters and next-rung goals exist only on the own-profile wall, which
 * composes these tiles into extra bands of its own.
 *
 * Presentational only.
 */

/**
 * One badge in a wall: medallion, name, and an optional second caption line
 * (the tier, or "no tier yet").
 *
 * @param earned false renders the locked/greyed treatment.
 * @param onClick null makes the tile inert — a public wall that has no detail
 *   sheet to open renders exactly the same tiles without a phantom ripple.
 */
@Composable
fun BadgeMedallionTile(
    glyph: BadgeGlyph,
    tier: BadgeTier?,
    earned: Boolean,
    label: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    medallionSize: Dp = 40.dp,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(vertical = KccSpacing.s1),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
    ) {
        BadgeMedallion(
            glyph = glyph,
            tier = tier,
            earned = earned,
            contentDescription = contentDescription,
            size = medallionSize,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color =
                if (earned) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            textAlign = TextAlign.Center,
        )
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * A fixed-column grid built from Rows.
 *
 * Deliberately not a LazyVerticalGrid: badge walls render inside a vertically
 * scrolling column, where a nested lazy grid has unbounded height. The item
 * counts are small and fixed (3–6 per grid), so plain Rows are both correct and
 * cheaper. The trailing row is padded with empty weights so items stay
 * column-aligned instead of centring.
 */
@Composable
fun <T> BadgeGridRows(
    items: List<T>,
    perRow: Int,
    item: @Composable (T) -> Unit,
) {
    items.chunked(perRow).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            row.forEach { value ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    item(value)
                }
            }
            repeat(perRow - row.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }
}
