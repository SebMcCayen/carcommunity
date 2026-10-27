package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing

const val CHAT_CHANNEL_STRIP_TEST_TAG = "chat_channel_strip"
internal fun chatChannelTabTag(tab: ChatTab) = "chat_channel_${tab.name}"

/** All four destinations stay visible. Labels wrap at the user's text size; targets grow vertically. */
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
                    modifier = Modifier.weight(1f).fillMaxHeight()
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
                        Text(
                            stringResource(labels[tab.ordinal]),
                            modifier = Modifier.fillMaxWidth().padding(
                                start = KccSpacing.s1, end = KccSpacing.s1,
                                top = KccSpacing.s3, bottom = KccSpacing.s2,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        if (unread[tab.ordinal]) {
                            Badge(
                                modifier = Modifier.align(Alignment.TopEnd).padding(KccSpacing.s1)
                                    .semantics { contentDescription = unreadLabel },
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
