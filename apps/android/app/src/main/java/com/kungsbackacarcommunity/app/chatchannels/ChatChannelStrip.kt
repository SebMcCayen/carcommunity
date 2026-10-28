package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing

const val CHAT_CHANNEL_STRIP_TEST_TAG = "chat_channel_strip"
internal fun chatChannelTabTag(tab: ChatTab) = "chat_channel_${tab.name}"

/** Compact full labels at normal widths; narrow or enlarged-text layouts can grow vertically. */
@Composable
internal fun ChatChannelStrip(
    selected: ChatTab,
    unread: List<Boolean>,
    onSelect: (ChatTab) -> Unit,
    onBack: (() -> Unit)?,
) {
    val labels = listOf(R.string.chatHub_tabCommunity, R.string.chatHub_tabConvoys,
        R.string.chatHub_tabFriends, R.string.chatHub_tabNotifications, R.string.clubs_chatTab)
    val labelTexts = labels.map { stringResource(it) }
    val labelStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val naturalWidths = labelTexts.map { label ->
        val textWidth = with(density) {
            measurer.measure(label, labelStyle, softWrap = false).size.width.toDp()
        }
        textWidth.coerceAtLeast(48.dp)
    }
    val unreadLabel = stringResource(R.string.chatHub_tabUnread)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = maxWidth - (if (onBack != null) 48.dp else 0.dp) - KccSpacing.s1 * (labels.size - 1)
        val naturalTotal = naturalWidths.fold(0.dp) { total, width -> total + width }
        val tabWidths = if (availableWidth >= naturalTotal) {
            // Keep short labels compact and give each full label the width it
            // actually needs before distributing the remaining space evenly.
            naturalWidths.map { it + (availableWidth - naturalTotal) / labels.size }
        } else {
            // All targets retain 48dp first. Share the remaining width by each
            // label's extra space requirement; wrapping is the accessibility
            // fallback, never font shrinking, truncation or horizontal scrolling.
            val extraWidths = naturalWidths.map { (it - 48.dp).value }
            val extraTotal = extraWidths.sum()
            val remaining = (availableWidth - 48.dp * labels.size).coerceAtLeast(0.dp)
            extraWidths.map { extra ->
                48.dp + remaining * (if (extraTotal > 0f) extra / extraTotal else 1f / labels.size)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.chatHub_back))
                }
            }
            Row(
                modifier = Modifier.weight(1f).height(IntrinsicSize.Min)
                    .testTag(CHAT_CHANNEL_STRIP_TEST_TAG).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(KccSpacing.s1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChatTab.entries.forEach { tab ->
                    val active = selected == tab
                    val shape = RoundedCornerShape(KccRadius.xl)
                    Surface(
                        modifier = Modifier.weight(tabWidths[tab.ordinal].value).fillMaxHeight()
                            .heightIn(min = 48.dp).clip(shape)
                            .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) })
                            .testTag(chatChannelTabTag(tab)),
                        shape = shape,
                        color = MaterialTheme.colorScheme.background,
                        contentColor = if (active) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Column(
                                modifier = Modifier,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                // Reserve the same compact badge lane for every tab,
                                // so unread changes never move or overlap its label.
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(KccSpacing.s2),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (unread.getOrElse(tab.ordinal) { false }) {
                                        Badge(
                                            modifier = Modifier.semantics { contentDescription = unreadLabel },
                                            containerColor = MaterialTheme.colorScheme.tertiary,
                                            contentColor = MaterialTheme.colorScheme.onTertiary,
                                        )
                                    }
                                }
                                Text(
                                    labelTexts[tab.ordinal],
                                    modifier = Modifier.fillMaxWidth(),
                                    style = labelStyle,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
