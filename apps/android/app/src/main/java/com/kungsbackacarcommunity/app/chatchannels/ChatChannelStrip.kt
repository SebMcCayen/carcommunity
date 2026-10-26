package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
        ScrollableTabRow(
            selectedTabIndex = selected.ordinal,
            modifier = Modifier.weight(1f).testTag(CHAT_CHANNEL_STRIP_TEST_TAG),
            edgePadding = 0.dp,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.tertiary,
            indicator = {}, divider = {},
        ) {
            ChatTab.entries.forEach { tab ->
                val active = selected == tab
                Tab(
                    selected = active, onClick = { onSelect(tab) },
                    modifier = Modifier.testTag(chatChannelTabTag(tab)).heightIn(min = 48.dp),
                    selectedContentColor = MaterialTheme.colorScheme.tertiary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Surface(
                        modifier = Modifier.padding(horizontal = KccSpacing.s1),
                        shape = RoundedCornerShape(KccRadius.xl),
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
}
