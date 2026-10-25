package com.kungsbackacarcommunity.app.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date

/** A date tile, not a substitute event photo. The complete date remains beside it. */
@Composable
internal fun EventDateBadge(startsAtMillis: Long?) {
    val locale = LocalLocale.current.platformLocale
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clearAndSetSemantics { },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (startsAtMillis == null) {
                Icon(Icons.Filled.Event, contentDescription = null, modifier = Modifier.size(32.dp))
            } else {
                val date = Date(startsAtMillis)
                Text(SimpleDateFormat("d", locale).format(date), style = MaterialTheme.typography.headlineSmall)
                Text(SimpleDateFormat("MMM", locale).format(date), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
internal fun EventInfoLine(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
