package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing

const val CHAT_SOURCE_SELECTOR_TEST_TAG = "chat_source_selector"

@Composable
fun ChatFeedHeader(driverSelected: Boolean, onSelect: (Boolean) -> Unit) {
    Box(Modifier.fillMaxWidth().statusBarsPadding()) {
        ChatFeedSelector(
            driverSelected, onSelect,
            Modifier.padding(horizontal = KccSpacing.s4),
        )
    }
}

/** S29/S37: transparent text tabs; underline and native semantics both identify selection. */
@Composable
fun ChatFeedSelector(driverSelected: Boolean, onSelect: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().testTag(CHAT_SOURCE_SELECTOR_TEST_TAG)
            .padding(vertical = KccSpacing.s1).height(IntrinsicSize.Min).selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(false to R.string.chatHub_tabChats, true to R.string.chatHub_tabDriver).forEach { (driver, label) ->
            val selected = driver == driverSelected
            Box(
                Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)
                    .selectable(selected, role = Role.Tab, onClick = { onSelect(driver) })
                    .padding(horizontal = KccSpacing.s2, vertical = KccSpacing.s1),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(label),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    textDecoration = if (selected) TextDecoration.Underline else TextDecoration.None,
                )
            }
        }
    }
}
