package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing

/** A receipt of the actual award. Never substitute the crown's base reward. */
@Composable
internal fun CrownRewardSummary(pointsAwarded: Int?, newBalance: Int? = null) {
    if (pointsAwarded == null && newBalance == null) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
    ) {
        Icon(painterResource(crownPointGlyphRes()), contentDescription = null,
            modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
        pointsAwarded?.let {
            Text(
                text = stringResource(R.string.crownHunt_spawnResultAwardedPoints, it),
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        newBalance?.let {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.semantics(mergeDescendants = true) {},
            ) {
                Text(stringResource(R.string.crownHunt_shopBalanceLabel), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.crownHunt_kpValue, it), style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
