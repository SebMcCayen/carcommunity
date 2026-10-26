package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing

const val CHAT_CHANNEL_STRIP_TEST_TAG = "chat_channel_strip"
internal fun chatChannelTabTag(tab: ChatTab) = "chat_channel_${tab.name}"

/** S29-S33: one reading-sized row. Overflow scrolls instead of shrinking accessible text. */
@Composable
internal fun ChatChannelStrip(
    selected: ChatTab,
    unread: List<Boolean>,
    onSelect: (ChatTab) -> Unit,
    onBack: (() -> Unit)?,
) {
    val labels = listOf(R.string.chatHub_tabCommunity, R.string.chatHub_tabConvoys,
        R.string.chatHub_tabFriends, R.string.chatHub_tabNotifications)
    val unreadLabel = stringResource(R.string.chatHub_tabUnread)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.chatHub_back))
            }
        }
        // Measure the whole pill (text + unread badge + padding) at its natural
        // width. The legacy tab-row sizing could constrain the text before
        // allocating space to the badge, even on the default text scale.
        Row(
            modifier = Modifier.weight(1f).testTag(CHAT_CHANNEL_STRIP_TEST_TAG)
                .horizontalScroll(rememberScrollState()).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatTab.entries.forEach { tab ->
                val active = selected == tab
                val bringIntoView = remember { BringIntoViewRequester() }
                var placedSize by remember { mutableStateOf(IntSize.Zero) }
                LaunchedEffect(active, placedSize) {
                    if (active && placedSize != IntSize.Zero) bringIntoView.bringIntoView()
                }
                val shape = RoundedCornerShape(KccRadius.xl)
                Surface(
                    modifier = Modifier.padding(horizontal = KccSpacing.s1)
                        .bringIntoViewRequester(bringIntoView)
                        .onGloballyPositioned { placedSize = it.size }
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
                    Row(
                        modifier = Modifier.padding(horizontal = KccSpacing.s3, vertical = KccSpacing.s2),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
                    ) {
                        Text(stringResource(labels[tab.ordinal]), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, softWrap = false)
                        if (unread[tab.ordinal]) {
                            Badge(
                                modifier = Modifier.semantics { contentDescription = unreadLabel },
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}
