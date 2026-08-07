package com.kungsbackacarcommunity.app.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.dm.DmRepository
import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendsRepository
import kotlinx.coroutines.launch

/** Test tags so UI tests can address the share-event friend picker. */
const val SHARE_EVENT_SHEET_TEST_TAG = "share_event_sheet"
const val SHARE_EVENT_EMPTY_TEST_TAG = "share_event_empty"
const val SHARE_EVENT_ERROR_TEST_TAG = "share_event_error"
const val SHARE_EVENT_RETRY_TEST_TAG = "share_event_retry"

/** Test-tag prefix for one friend row (suffixed with the friend uid). */
fun shareEventFriendRowTestTag(uid: String): String = "share_event_friend_$uid"

/**
 * The friend picker raised by the event detail's "Share" button. The member picks a
 * friend and the event ([eventId] + [title]) is delivered to them as a direct
 * message that renders as a tappable "Open event" chip on the friend's side —
 * tapping it opens THAT event's detail page.
 *
 * A direct parallel of
 * [com.kungsbackacarcommunity.app.shell.ShareLocationSheet]: delivery reuses the
 * existing DM send path ([EventShareCoordinator]); there is no new backend. The
 * empty-friends case is a first-class state (not an error): the member simply has
 * not added anyone yet, so the sheet explains how rather than showing a spinner or
 * a failure.
 *
 * @param onShared invoked with the friend's display name once a send is confirmed,
 *   so the host can show a confirmation; the sheet dismisses itself after.
 * @param onSendFailed invoked when the send could not be delivered, so the host can
 *   surface a retryable error; the sheet stays open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventShareSheet(
    eventId: String,
    eventTitle: String?,
    friendsRepository: FriendsRepository,
    dmRepository: DmRepository,
    onShared: (friendName: String?) -> Unit,
    onSendFailed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val coordinator =
        remember(friendsRepository, dmRepository) {
            EventShareCoordinator.fromFriendsRepository(friendsRepository, dmRepository)
        }
    val state by coordinator.state.collectAsState()
    val sendingUid by coordinator.sending.collectAsState()

    LaunchedEffect(coordinator) { coordinator.load() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(SHARE_EVENT_SHEET_TEST_TAG),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s2),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            Text(
                text = stringResource(R.string.events_shareEventTitle),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = KccSpacing.s2),
            )
            Text(
                text =
                    stringResource(
                        R.string.events_shareEventSubtitle,
                        eventTitle?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.events_shareEventUnnamed),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = KccSpacing.s2),
            )

            when (val current = state) {
                EventShareState.Loading ->
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = KccSpacing.s4),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                EventShareState.Error ->
                    Column(
                        modifier = Modifier.padding(horizontal = KccSpacing.s2, vertical = KccSpacing.s3),
                        verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
                    ) {
                        Text(
                            text = stringResource(R.string.events_shareEventError),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(SHARE_EVENT_ERROR_TEST_TAG),
                        )
                        TextButton(
                            onClick = { scope.launch { coordinator.load() } },
                            modifier = Modifier.testTag(SHARE_EVENT_RETRY_TEST_TAG),
                        ) {
                            Text(stringResource(R.string.events_shareEventRetry))
                        }
                    }

                is EventShareState.Ready ->
                    if (current.friends.isEmpty()) {
                        Text(
                            text = stringResource(R.string.events_shareEventEmpty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier
                                    .padding(horizontal = KccSpacing.s2, vertical = KccSpacing.s3)
                                    .testTag(SHARE_EVENT_EMPTY_TEST_TAG),
                        )
                    } else {
                        current.friends.forEach { friend ->
                            FriendRow(
                                friend = friend,
                                sending = sendingUid == friend.uid,
                                // Disable EVERY row while any send is in flight, so a
                                // second tap can't reach the coordinator's busy guard
                                // and be misread as a send failure. Only the sending
                                // row shows the spinner.
                                enabled = sendingUid == null,
                                onClick = {
                                    scope.launch {
                                        val ok = coordinator.share(friend, eventId, eventTitle)
                                        if (ok) {
                                            onShared(friend.displayName)
                                            onDismiss()
                                        } else {
                                            onSendFailed()
                                        }
                                    }
                                },
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun FriendRow(
    friend: FriendSummary,
    sending: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val name =
        friend.displayName?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.events_shareEventUnnamedFriend)
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(shareEventFriendRowTestTag(friend.uid)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
            if (sending) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
            }
        }
    }
}
