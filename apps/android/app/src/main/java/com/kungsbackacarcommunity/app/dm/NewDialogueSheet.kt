package com.kungsbackacarcommunity.app.dm

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
import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendsRepository
import kotlinx.coroutines.launch

/** Test tags so UI tests can address the new-dialogue friend picker. */
const val NEW_DIALOGUE_FAB_TEST_TAG = "new_dialogue_fab"
const val NEW_DIALOGUE_SHEET_TEST_TAG = "new_dialogue_sheet"
const val NEW_DIALOGUE_EMPTY_TEST_TAG = "new_dialogue_empty"
const val NEW_DIALOGUE_ERROR_TEST_TAG = "new_dialogue_error"
const val NEW_DIALOGUE_RETRY_TEST_TAG = "new_dialogue_retry"

/** Test-tag prefix for one friend row (suffixed with the friend uid). */
fun newDialogueFriendRowTestTag(uid: String): String = "new_dialogue_friend_$uid"

/**
 * The friend picker raised by the DM inbox's "start a new dialogue" button. The
 * member picks a friend, and [onPick] opens (or re-opens) the DM thread with
 * them — pure navigation, so there is no send here and no new backend.
 *
 * Mirrors the shell's [com.kungsbackacarcommunity.app.shell.ShareLocationSheet]:
 * the empty-friends case is a first-class state (not an error) — the member has
 * simply not added anyone yet, so the sheet explains how rather than showing a
 * spinner or a failure.
 *
 * @param onPick invoked with the chosen friend; the host resolves the open target
 *   ([NewDialogue.openTargetFor]) and opens the thread. The sheet dismisses after.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewDialogueSheet(
    friendsRepository: FriendsRepository,
    onPick: (FriendSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val coordinator =
        remember(friendsRepository) { NewDialogueCoordinator.fromFriendsRepository(friendsRepository) }
    val state by coordinator.state.collectAsState()

    LaunchedEffect(coordinator) { coordinator.load() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(NEW_DIALOGUE_SHEET_TEST_TAG),
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
                text = stringResource(R.string.dm_newDialogue_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = KccSpacing.s2),
            )
            Text(
                text = stringResource(R.string.dm_newDialogue_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = KccSpacing.s2),
            )

            when (val current = state) {
                NewDialogueState.Loading ->
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = KccSpacing.s4),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                NewDialogueState.Error ->
                    Column(
                        modifier = Modifier.padding(horizontal = KccSpacing.s2, vertical = KccSpacing.s3),
                        verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
                    ) {
                        Text(
                            text = stringResource(R.string.dm_newDialogue_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(NEW_DIALOGUE_ERROR_TEST_TAG),
                        )
                        TextButton(
                            onClick = { scope.launch { coordinator.load() } },
                            modifier = Modifier.testTag(NEW_DIALOGUE_RETRY_TEST_TAG),
                        ) {
                            Text(stringResource(R.string.dm_newDialogue_retry))
                        }
                    }

                is NewDialogueState.Ready ->
                    if (current.friends.isEmpty()) {
                        Text(
                            text = stringResource(R.string.dm_newDialogue_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier
                                    .padding(horizontal = KccSpacing.s2, vertical = KccSpacing.s3)
                                    .testTag(NEW_DIALOGUE_EMPTY_TEST_TAG),
                        )
                    } else {
                        current.friends.forEach { friend ->
                            FriendRow(friend = friend, onClick = { onPick(friend) })
                        }
                    }
            }
        }
    }
}

@Composable
private fun FriendRow(
    friend: FriendSummary,
    onClick: () -> Unit,
) {
    val name =
        friend.displayName?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.dm_newDialogue_unnamedFriend)
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag(newDialogueFriendRowTestTag(friend.uid)),
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
        }
    }
}
