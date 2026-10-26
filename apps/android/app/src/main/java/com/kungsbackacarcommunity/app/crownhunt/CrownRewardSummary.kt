package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedCard
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R

/** Called only after AWARDED, with the server's actual (possibly clipped) reward. */
@Composable
internal fun CrownRewardSummary(pointsAwarded: Int, newBalance: Int? = null) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(crownPointGlyphRes()), contentDescription = null,
            modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            text = stringResource(R.string.crownHunt_spawnResultAwardedPoints, pointsAwarded),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.CheckCircleOutline, null, tint = MaterialTheme.colorScheme.tertiary)
                    Text(stringResource(R.string.crownHunt_serverConfirmed), style = MaterialTheme.typography.titleMedium)
                }
                newBalance?.let {
                    Text(stringResource(R.string.crownHunt_shopBalanceLabel), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.crownHunt_kpValue, it), style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
