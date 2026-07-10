package com.kungsbackacarcommunity.app.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R

/** A single entry in a hub screen; [onClick] null hides the row (unavailable). */
data class HubEntry(
    val label: String,
    val icon: ImageVector,
    val onClick: (() -> Unit)?,
)

/**
 * A simple scrollable hub: a title and a vertical list of navigable entries.
 * Used for the Create (+), Social, Garage, and "More" landings so every
 * previously-reachable destination stays reachable in the redesigned shell.
 * Unavailable entries (null [HubEntry.onClick]) are omitted.
 */
@Composable
fun HubScreen(
    title: String,
    entries: List<HubEntry>,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    if (onBack != null) {
        BackHandler(onBack = onBack)
    }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onBack != null) {
                TextButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
                    Text(text = stringResource(R.string.profile_back))
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            entries.forEach { entry ->
                val onClick = entry.onClick
                if (onClick != null) {
                    HubRow(entry.label, entry.icon, onClick)
                }
            }
        }
    }
}

@Composable
private fun HubRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
