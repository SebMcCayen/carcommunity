package com.kungsbackacarcommunity.app.points

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.AeroPage
import java.text.DateFormat
import java.util.Date

/**
 * Kronpoäng wallet (Phase 12 slice 15). Read-only: the balance and the recent
 * append-only transactions. Points are non-transferable (disclaimer shown).
 */
@Composable
fun PointsScreen(
    balance: Long?,
    entriesState: PointsEntriesState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AeroPage(title = stringResource(R.string.points_screenTitle), modifier = modifier) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.points_balanceLabel),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = (balance ?: 0L).toString(),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Text(
                text = stringResource(R.string.points_noTransfer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.points_recentTransactions),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            when (entriesState) {
                PointsEntriesState.Loading ->
                    Text(
                        text = stringResource(R.string.points_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                PointsEntriesState.Error ->
                    Text(
                        text = stringResource(R.string.points_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                is PointsEntriesState.Loaded ->
                    if (entriesState.entries.isEmpty()) {
                        Text(
                            text = stringResource(R.string.points_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        entriesState.entries.forEach { entry -> EntryCard(entry) }
                    }
            }
    }
}

@Composable
private fun EntryCard(entry: PointsEntry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = if (entry.amount >= 0) "+${entry.amount}" else entry.amount.toString(),
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (entry.amount >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.createdAtMillis?.let { millis ->
                Text(
                    text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
