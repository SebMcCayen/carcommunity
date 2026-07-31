package com.kungsbackacarcommunity.app.billboards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.AeroPage

/**
 * Digital billboards list (Phase 12 slice 20). Stateless: renders active
 * billboards; tapping one records an `open` interaction.
 *
 * **Intentionally not reachable by members right now (Seb, 2026-07-31).** The
 * Social menu's "Billboards" row — the only door into this screen — was removed
 * because billboards are meant to be MAP PINS: "something that should be within
 * the map, if it isn't activated it shouldn't be shown for our users". No
 * billboard rendering exists in the map yet, so the row advertised a feature
 * that does not work as intended. Making billboards invisible to members until
 * the map work lands is the accepted, intended outcome — NOT an oversight.
 *
 * This screen, [BillboardsRoute] and
 * [com.kungsbackacarcommunity.app.shell.ShellRoute.Billboards] are all kept
 * wired and working (and still covered by `BillboardsScreenTest`) precisely so
 * that pointing the map at billboards later is a small job rather than a
 * rebuild. **Do not delete any of them as dead code**, and do not re-add a menu
 * entry — the next step here is map rendering.
 */
@Composable
fun BillboardsScreen(
    state: BillboardsState,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AeroPage(title = stringResource(R.string.billboard_advertisingFrom), modifier = modifier) {
            when (state) {
                BillboardsState.Loading ->
                    Text(
                        text = stringResource(R.string.billboard_sponsoredLabel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                BillboardsState.Error ->
                    Text(
                        text = stringResource(R.string.billboard_loadError),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                is BillboardsState.Loaded ->
                    state.billboards.forEach { billboard ->
                        BillboardCard(billboard = billboard, onClick = { onOpen(billboard.id) })
                    }
            }
    }
}

@Composable
private fun BillboardCard(billboard: Billboard, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.billboard_sponsoredLabel),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = billboard.headline,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            billboard.message?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
