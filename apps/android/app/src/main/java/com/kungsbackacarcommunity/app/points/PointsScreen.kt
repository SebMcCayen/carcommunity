package com.kungsbackacarcommunity.app.points

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.AeroPage
import com.kungsbackacarcommunity.app.crownhunt.crownPointGlyphRes
import java.text.DateFormat
import java.text.SimpleDateFormat
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
    var filter by rememberSaveable { mutableStateOf(PointsFilter.ALL) }
    AeroPage(title = stringResource(R.string.points_screenTitle), modifier = modifier) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(crownPointGlyphRes()), contentDescription = null,
                    modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.points_balanceLabel),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = balance?.let { stringResource(R.string.crownHunt_kpValue, it) } ?: stringResource(R.string.points_loading),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.points_noTransfer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TabRow(selectedTabIndex = filter.ordinal, indicator = {}, divider = {},
            containerColor = MaterialTheme.colorScheme.surfaceVariant) {
            PointsFilter.entries.forEach { option ->
                Tab(selected = filter == option, onClick = { filter = option },
                    modifier = Modifier.background(if (filter == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.shapes.medium),
                    selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(stringResource(when (option) {
                        PointsFilter.ALL -> R.string.crownHunt_ledgerAll
                        PointsFilter.CREDITS -> R.string.crownHunt_ledgerCredits
                        PointsFilter.DEBITS -> R.string.crownHunt_ledgerDebits
                    })) })
            }
        }

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

            is PointsEntriesState.Loaded -> {
                val entries = Points.filteredEntries(entriesState.entries, filter)
                if (entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.points_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val monthFormat = SimpleDateFormat("LLLL yyyy", LocalLocale.current.platformLocale)
                    entries.groupBy { entry -> entry.createdAtMillis?.let { monthFormat.format(Date(it)) } }.forEach { (month, rows) ->
                        month?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) }
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            rows.forEachIndexed { index, entry ->
                                PointsEntryRow(entry)
                                if (index < rows.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PointsEntryRow(entry: PointsEntry) {
        val locale = LocalLocale.current.platformLocale
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (entry.amount >= 0) Icon(
                painterResource(crownPointGlyphRes()), contentDescription = null,
                modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary,
            ) else Icon(Icons.Filled.ReceiptLong, contentDescription = null,
                modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.tertiary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                entry.createdAtMillis?.let { millis ->
                    Text(
                        text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(millis)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(
                text = stringResource(R.string.crownHunt_ledgerAmount, if (entry.amount >= 0) "+${entry.amount}" else entry.amount.toString()),
                style = MaterialTheme.typography.titleMedium,
                color = if (entry.amount >= 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
              )
              entry.balanceAfter?.let {
                  Text(stringResource(R.string.crownHunt_ledgerBalanceAfter, it), style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
        }
}
