package com.kungsbackacarcommunity.app.shell

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
import com.kungsbackacarcommunity.app.location.ShareLocationCoordinator
import com.kungsbackacarcommunity.app.location.ShareLocationState
import com.kungsbackacarcommunity.app.location.ShareableLocation
import kotlinx.coroutines.launch

/** Test tags so UI tests can address the share-location friend picker. */
const val SHARE_LOCATION_SHEET_TEST_TAG = "share_location_sheet"
const val SHARE_LOCATION_EMPTY_TEST_TAG = "share_location_empty"
const val SHARE_LOCATION_ERROR_TEST_TAG = "share_location_error"

/** Test-tag prefix for one friend row (suffixed with the friend uid). */
fun shareLocationFriendRowTestTag(uid: String): String = "share_location_friend_$uid"

/**
 * The friend picker raised by every "share a location with a friend" entry point
 * — the map's save-location naming popup and the Saved-places long-press "Share".
 * The member picks a friend, and the [location] is delivered to them as a direct
 * message that renders as a tappable "show on map" chip on the friend's side.
 *
 * Delivery reuses the existing DM send path ([ShareLocationCoordinator]); there is
 * no new backend. The empty-friends case is a first-class state (not an error):
 * the member simply has not added anyone yet, so the sheet explains how rather
 * than showing a spinner or a failure.
 *
 * @param location the place to share (name + coordinate). A blank name is filled
 *   from the coordinate before sending (see `LocationShare.messageText`).
 * @param onShared invoked with the friend's display name once a send is confirmed,
 *   so the host can show a confirmation; the sheet dismisses itself after.
 * @param onSendFailed invoked when the send could not be delivered, so the host
 *   can surface a retryable error; the sheet stays open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareLocationSheet(
    location: ShareableLocation,
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
            ShareLocationCoordinator.fromFriendsRepository(friendsRepository, dmRepository)
        }
    val state by coordinator.state.collectAsState()
    val sendingUid by coordinator.sending.collectAsState()

    LaunchedEffect(coordinator) { coordinator.load() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(SHARE_LOCATION_SHEET_TEST_TAG),
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
                text = stringResource(R.string.shareLocation_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = KccSpacing.s2),
            )
            Text(
                text = stringResource(R.string.shareLocation_subtitle, location.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = KccSpacing.s2),
            )

            when (val current = state) {
                ShareLocationState.Loading ->
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = KccSpacing.s4),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                ShareLocationState.Error ->
                    Text(
                        text = stringResource(R.string.shareLocation_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            Modifier
                                .padding(horizontal = KccSpacing.s2, vertical = KccSpacing.s3)
                                .testTag(SHARE_LOCATION_ERROR_TEST_TAG),
                    )

                is ShareLocationState.Ready ->
                    if (current.friends.isEmpty()) {
                        Text(
                            text = stringResource(R.string.shareLocation_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier
                                    .padding(horizontal = KccSpacing.s2, vertical = KccSpacing.s3)
                                    .testTag(SHARE_LOCATION_EMPTY_TEST_TAG),
                        )
                    } else {
                        current.friends.forEach { friend ->
                            FriendRow(
                                friend = friend,
                                sending = sendingUid == friend.uid,
                                onClick = {
                                    scope.launch {
                                        val ok = coordinator.share(friend, location)
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
    onClick: () -> Unit,
) {
    val name =
        friend.displayName?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.shareLocation_unnamedFriend)
    TextButton(
        onClick = onClick,
        enabled = !sending,
        modifier = Modifier.fillMaxWidth().testTag(shareLocationFriendRowTestTag(friend.uid)),
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
