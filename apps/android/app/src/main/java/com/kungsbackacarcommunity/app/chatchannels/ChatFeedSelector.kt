package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing

@Composable
fun ChatFeedHeader(driverSelected: Boolean, onSelect: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Text(stringResource(R.string.shell_tabChat), style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(KccSpacing.s4))
        ChatFeedSelector(driverSelected, onSelect,
            Modifier.padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s2))
    }
}

/** Catalogue S29/S37: two distinct content sources, with native selection semantics. */
@Composable
fun ChatFeedSelector(driverSelected: Boolean, onSelect: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Surface(modifier.fillMaxWidth(), shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.selectableGroup().padding(KccSpacing.s1)) {
            listOf(false to R.string.chatHub_tabChats, true to R.string.chatHub_tabDriver).forEach { (driver, label) ->
                val selected = driver == driverSelected
                Surface(Modifier.weight(1f), shape = shape,
                    color = if (selected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surface,
                    contentColor = if (selected) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurfaceVariant) {
                    Text(stringResource(label), style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.selectable(selected, role = Role.Tab, onClick = { onSelect(driver) })
                            .heightIn(min = 48.dp).padding(horizontal = KccSpacing.s2, vertical = KccSpacing.s3))
                }
            }
        }
    }
}
