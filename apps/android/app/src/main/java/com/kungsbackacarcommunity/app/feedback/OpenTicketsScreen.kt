package com.kungsbackacarcommunity.app.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.AeroPage

/**
 * "Open tickets" browser: the member-facing list of OPEN GitHub issues mirrored
 * into `openTickets`. Per ticket the user can open the issue in the browser,
 * +1 ("me too"), and add ONE comment. The +1/comment controls disable once done
 * (optimistic, session-local — see [OpenTicketsCoordinator]).
 *
 * Gated upstream by the `reportTicketsBrowser` flag: the route is only reachable
 * while the flag is on, so this screen never renders with the feature off.
 */
@Composable
fun OpenTicketsScreen(
    listState: OpenTicketsListState,
    interactions: Map<Int, TicketInteractionState>,
    onPlusOne: (Int) -> Unit,
    onComment: (Int, String) -> Unit,
    onCommentEdited: (Int) -> Unit,
    onOpenInGitHub: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AeroPage(
        title = stringResource(R.string.openTickets_title),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentWindowInsets = WindowInsets.ime.union(WindowInsets.navigationBars),
    ) {
        Text(
            text = stringResource(R.string.openTickets_intro),
            style = MaterialTheme.typography.bodyMedium,
        )

        when (listState) {
            is OpenTicketsListState.Loading ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }

            is OpenTicketsListState.Error ->
                Text(
                    text = stringResource(R.string.openTickets_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

            is OpenTicketsListState.Loaded ->
                if (listState.tickets.isEmpty()) {
                    Text(
                        text = stringResource(R.string.openTickets_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    listState.tickets.forEach { ticket ->
                        TicketCard(
                            ticket = ticket,
                            state = interactions[ticket.number] ?: TicketInteractionState(),
                            onPlusOne = { onPlusOne(ticket.number) },
                            onComment = { text -> onComment(ticket.number, text) },
                            onCommentEdited = { onCommentEdited(ticket.number) },
                            onOpenInGitHub = { onOpenInGitHub(ticket.htmlUrl) },
                        )
                    }
                }
        }
    }
}

@Composable
private fun TicketCard(
    ticket: OpenTicket,
    state: TicketInteractionState,
    onPlusOne: () -> Unit,
    onComment: (String) -> Unit,
    onCommentEdited: () -> Unit,
    onOpenInGitHub: () -> Unit,
) {
    var comment by rememberSaveable(ticket.number) { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = ticket.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (ticket.summary.isNotBlank() && ticket.summary != ticket.title) {
                Text(
                    text = ticket.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text =
                    stringResource(R.string.openTickets_plusOneCount, ticket.plusOneCount) +
                        "  •  " +
                        stringResource(R.string.openTickets_commentCount, ticket.commentCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // +1 ("me too") and the browser link.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val plusOneDescription =
                    stringResource(R.string.openTickets_plusOneDescription)
                OutlinedButton(
                    onClick = onPlusOne,
                    enabled = state.canPlusOne,
                    modifier = Modifier.semantics { contentDescription = plusOneDescription },
                ) {
                    if (state.isPlusOneSubmitting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            text =
                                stringResource(
                                    if (state.plusOneDone) {
                                        R.string.openTickets_plusOneDone
                                    } else {
                                        R.string.openTickets_plusOne
                                    },
                                ),
                        )
                    }
                }
                val openDescription = stringResource(R.string.openTickets_openInGitHubDescription)
                TextButton(
                    onClick = onOpenInGitHub,
                    modifier = Modifier.semantics { contentDescription = openDescription },
                ) {
                    Text(text = stringResource(R.string.openTickets_openInGitHub))
                }
            }

            // One comment per issue.
            if (state.commentDone) {
                Text(
                    text = stringResource(R.string.openTickets_commentDone),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                OutlinedTextField(
                    value = comment,
                    onValueChange = {
                        comment = it
                        onCommentEdited()
                    },
                    label = { Text(text = stringResource(R.string.openTickets_commentLabel)) },
                    singleLine = false,
                    minLines = 2,
                    enabled = state.submitting == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        onComment(comment)
                        comment = ""
                    },
                    enabled = state.canComment && TicketComments.isValid(comment),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isCommentSubmitting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(text = stringResource(R.string.openTickets_commentSubmit))
                    }
                }
            }

            state.error?.let { error ->
                Text(
                    text =
                        stringResource(
                            when (error) {
                                TicketInteractionError.ALREADY_DONE -> R.string.openTickets_alreadyDone
                                TicketInteractionError.RATE_LIMITED -> R.string.openTickets_rateLimited
                                TicketInteractionError.EMPTY_COMMENT -> R.string.openTickets_emptyComment
                                TicketInteractionError.UNKNOWN -> R.string.openTickets_genericError
                            },
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (error == TicketInteractionError.ALREADY_DONE) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
            }
        }
    }
}
