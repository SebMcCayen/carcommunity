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
            BillboardSponsorLabel()
            BillboardBody(headline = billboard.headline, message = billboard.message)
        }
    }
}

/**
 * The "Sponsrad placering" mark.
 *
 * Its own composable, and always rendered ABOVE the copy it labels, because one
 * of the six confirmations an admin must tick to activate a billboard is that
 * the content is clearly marked as advertising. A label placed underneath the
 * sales copy — or forgotten at one of the two call sites — would be the admin
 * confirming something the UI then quietly walks back.
 */
@Composable
internal fun BillboardSponsorLabel(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.billboard_sponsoredLabel),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * A billboard's headline and message, in the one typography both places use.
 *
 * Shared by this screen's cards and by [BillboardMapPopup] — the popup a tap on
 * a map marker opens, which is now the ONLY route a member has to a billboard.
 * Extracted rather than duplicated so an advert cannot end up looking like two
 * different things depending on how it was reached, and so a change to how
 * sponsored copy is presented is one edit rather than two that can drift.
 */
@Composable
internal fun BillboardBody(headline: String, message: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        message?.takeIf { it.isNotBlank() }?.let { body ->
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
