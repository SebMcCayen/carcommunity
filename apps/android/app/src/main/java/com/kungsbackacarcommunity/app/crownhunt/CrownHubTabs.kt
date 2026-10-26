package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.design.KccSpacing

internal data class CrownHubTab(val id: Int, val label: String)

/** Fit full labels first; use horizontal overflow only when accessible text needs it. */
@Composable
internal fun CrownHubTabs(
    tabs: List<CrownHubTab>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    tagPrefix: String = "crownHubTab",
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelLarge
    val textWidths = tabs.map { tab ->
        with(density) {
            measurer.measure(tab.label, style = style, maxLines = 1, softWrap = false).size.width.toDp()
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gaps = KccSpacing.s1 * (tabs.size - 1).coerceAtLeast(0)
        val naturalTotal = textWidths.fold(0.dp) { sum, width -> sum + width }
        val padding = if (naturalTotal + KccSpacing.s3 * (2 * tabs.size) + gaps <= maxWidth) {
            KccSpacing.s3
        } else KccSpacing.s2
        val widths = textWidths.map { (it + padding * 2).coerceAtLeast(48.dp) }
        val extra = ((maxWidth - gaps - widths.fold(0.dp) { sum, width -> sum + width }) / tabs.size)
            .coerceAtLeast(0.dp)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val active = selectedId == tab.id
                val bringIntoView = remember { BringIntoViewRequester() }
                var placedSize by remember { mutableStateOf(IntSize.Zero) }
                LaunchedEffect(active, placedSize) {
                    if (active && placedSize != IntSize.Zero) bringIntoView.bringIntoView()
                }
                val shape = MaterialTheme.shapes.medium
                Surface(
                    modifier = Modifier.width(widths[index] + extra).heightIn(min = 48.dp)
                        .bringIntoViewRequester(bringIntoView)
                        .onGloballyPositioned { placedSize = it.size }
                        .clip(shape)
                        .selectable(active, role = Role.Tab, onClick = { onSelect(tab.id) })
                        .testTag("${tagPrefix}_${tab.id}"),
                    shape = shape,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.padding(horizontal = padding, vertical = KccSpacing.s2),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(tab.label, modifier = Modifier.testTag("${tagPrefix}_label_${tab.id}"),
                            style = style, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
