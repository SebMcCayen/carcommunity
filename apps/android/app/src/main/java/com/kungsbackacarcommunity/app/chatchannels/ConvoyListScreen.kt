package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing

/**
 * The Convoys tab list: one row per chat-eligible convoy (accepted member),
 * newest-first. Tapping a row opens its channel. Loads once via the suspend
 * `convoy-list` callable (convoys change rarely), with a retry on failure and an
 * empty state when the caller is in no convoys.
 */
@Composable
fun ConvoyListRoute(
    repository: ConvoyChatRepository,
    onOpenConvoy: (ChatConvoy) -> Unit,
    modifier: Modifier = Modifier,
) {
    var reloadKey by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<ConvoyListState>(ConvoyListState.Loading) }
    LaunchedEffect(reloadKey) {
        state = ConvoyListState.Loading
        state = repository.listConvoys()
    }

    ConvoyListScreen(
        state = state,
        onOpenConvoy = onOpenConvoy,
        onRetry = { reloadKey++ },
        modifier = modifier,
    )
}

@Composable
fun ConvoyListScreen(
    state: ConvoyListState,
    onOpenConvoy: (ChatConvoy) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(KccSpacing.s4)) {
        when (state) {
            ConvoyListState.Loading ->
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

            ConvoyListState.Error ->
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
                ) {
                    Text(
                        text = stringResource(R.string.chatHub_convoysError),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.chatHub_convoysRetry))
                    }
                }

            is ConvoyListState.Loaded ->
                if (state.convoys.isEmpty()) {
                    Text(
                        text = stringResource(R.string.chatHub_convoysEmpty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
                    ) {
                        items(state.convoys, key = { it.convoyId }) { convoy ->
                            ConvoyRow(convoy = convoy, onClick = { onOpenConvoy(convoy) })
                        }
                    }
                }
        }
    }
}

@Composable
private fun ConvoyRow(convoy: ChatConvoy, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
        ) {
            Text(
                text = convoy.title ?: stringResource(R.string.chatHub_convoyUntitled),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.chatHub_convoyMembers, convoy.memberCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
