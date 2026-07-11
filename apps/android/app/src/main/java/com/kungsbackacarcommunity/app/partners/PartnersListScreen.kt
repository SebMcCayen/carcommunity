package com.kungsbackacarcommunity.app.partners

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
 * Partner companies list (Phase 12 slice 17). Stateless: renders [state] and
 * reports taps. Any authenticated user sees active companies.
 */
@Composable
fun PartnersListScreen(
    state: CompaniesState,
    onOpenCompany: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // Re-invokes the companies load; when null the error state shows no retry.
    onRetry: (() -> Unit)? = null,
) {
    AeroPage(title = stringResource(R.string.partners_screenTitle), modifier = modifier) {
            when (state) {
                CompaniesState.Loading ->
                    Text(
                        text = stringResource(R.string.partners_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                CompaniesState.Error -> {
                    Text(
                        text = stringResource(R.string.partners_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (onRetry != null) {
                        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                            Text(text = stringResource(R.string.partners_retry))
                        }
                    }
                }

                is CompaniesState.Loaded ->
                    if (state.companies.isEmpty()) {
                        Text(
                            text = stringResource(R.string.partners_noPartnersNearby),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.companies.forEach { company ->
                            CompanyCard(company = company, onClick = { onOpenCompany(company.id) })
                        }
                    }
            }
    }
}

@Composable
private fun CompanyCard(company: PartnerCompany, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = company.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(company.category.labelRes()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
