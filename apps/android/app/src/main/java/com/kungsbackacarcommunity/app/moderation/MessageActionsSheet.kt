package com.kungsbackacarcommunity.app.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.chat.ChatReportReason
import com.kungsbackacarcommunity.app.design.KccSpacing

/** Test tags so UI tests can address the sheet's rows without matching copy. */
const val MESSAGE_ACTIONS_SHEET_TEST_TAG = "message_actions_sheet"
const val MESSAGE_ACTIONS_REPLY_TEST_TAG = "message_actions_reply"
const val MESSAGE_ACTIONS_BLOCK_TEST_TAG = "message_actions_block"
const val MESSAGE_ACTIONS_REPORT_TEST_TAG = "message_actions_report"

/**
 * The long-press action sheet behind every chat surface (community + convoy
 * channels, DMs, event chat) — the single shared, EXTENSIBLE context menu a
 * message bubble long-presses to. It ships Reply + the moderation actions (block,
 * report) today and is the reserved anchor for the message actions that
 * fast-follow: a future React / Copy row slots in here beside these, keyed off the
 * same long-pressed message, with no new menu.
 *
 * The caller composes this whenever the sheet has ANY action to offer: Reply is
 * available on any message (including the caller's OWN — you can quote yourself),
 * while block/report only apply to ANOTHER member's message (the caller passes
 * [canBlock]/[reportAvailability] already narrowed by [MessageModeration.canActOn]),
 * so an own-message long-press opens a sheet with just Reply + Close.
 *
 * @param memberName the author's display name, or null when unknown — the block
 *   row then falls back to the generic "Block user" label rather than rendering
 *   "Block null".
 * @param canReply whether the Reply row is shown (the chatReplies flag, threaded
 *   down from the route). Off leaves the menu at its prior moderation-only shape.
 * @param onReply chosen "Svara" (Reply) — the caller opens the composer quote chip
 *   for the long-pressed message.
 * @param canBlock false in a config-less build with no blocking repository, or on
 *   the caller's own message; the block row is then omitted entirely (an action
 *   that cannot run is not an action).
 * @param reportAvailability [ReportAvailability.Wired] renders the report row;
 *   [ReportAvailability.BackendMissing] omits it entirely, on the same principle
 *   as [canBlock] — the user is never shown a report they cannot file, whether
 *   as a lie (a button that quietly does nothing) or as a permanently dead
 *   control. It reappears when its callable lands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    memberName: String?,
    canBlock: Boolean,
    reportAvailability: ReportAvailability,
    onBlock: () -> Unit,
    onReport: () -> Unit,
    onDismiss: () -> Unit,
    canReply: Boolean = false,
    onReply: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState()
    val name = memberName?.takeIf { it.isNotBlank() }
    val reportWired = reportAvailability == ReportAvailability.Wired

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(MESSAGE_ACTIONS_SHEET_TEST_TAG),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // The sheet draws to the bottom edge, so its own content must
                    // clear the navigation bar / gesture pill.
                    .navigationBarsPadding()
                    .padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s2),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            Text(
                text =
                    stringResource(
                        R.string.moderation_messageActionsTitle,
                        name ?: stringResource(R.string.chat_unknownAuthor),
                    ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = KccSpacing.s2),
            )

            // Reply is the primary action, so it leads. A future React / Copy row
            // would sit here beside it — this ordering is the reserved anchor.
            if (canReply) {
                SheetAction(
                    text = stringResource(R.string.moderation_reply),
                    onClick = onReply,
                    testTag = MESSAGE_ACTIONS_REPLY_TEST_TAG,
                )
            }

            if (canBlock) {
                SheetAction(
                    text =
                        if (name != null) {
                            stringResource(R.string.moderation_blockMember, name)
                        } else {
                            stringResource(R.string.blocking_blockUser)
                        },
                    onClick = onBlock,
                    testTag = MESSAGE_ACTIONS_BLOCK_TEST_TAG,
                )
            }

            if (reportWired) {
                SheetAction(
                    text = stringResource(R.string.moderation_reportMessage),
                    onClick = onReport,
                    testTag = MESSAGE_ACTIONS_REPORT_TEST_TAG,
                )
            }

            SheetAction(text = stringResource(R.string.moderation_close), onClick = onDismiss)
        }
    }
}

@Composable
private fun SheetAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        Text(text = text, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Confirm dialog for blocking [memberName] (or a generic "user" when unknown).
 * Shared by every surface that can initiate a block, so the consequence copy the
 * user sees is identical from chat, from the map and from a profile.
 */
@Composable
fun BlockConfirmDialog(memberName: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val name = memberName?.takeIf { it.isNotBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text =
                    if (name != null) {
                        stringResource(R.string.moderation_blockMember, name)
                    } else {
                        stringResource(R.string.blocking_blockConfirmTitle)
                    },
            )
        },
        text = { Text(text = stringResource(R.string.blocking_blockConfirmBody)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.blocking_blockConfirmAction))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.blocking_blockCancelAction))
            }
        },
    )
}

/** Confirm dialog for unblocking a member. Mirrors [BlockConfirmDialog]. */
@Composable
fun UnblockConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.blocking_unblockConfirmTitle)) },
        text = { Text(text = stringResource(R.string.blocking_unblockConfirmBody)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.blocking_unblockConfirmAction))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.blocking_unblockCancelAction))
            }
        },
    )
}

/**
 * The shared report-reason picker. The reasons mirror the backend's
 * `CHAT_MESSAGE_REPORT_REASONS` via [ChatReportReason], so a surface that gains
 * a report callable can submit the selection unchanged.
 *
 * Only ever composed for a [ReportAvailability.Wired] surface — a picker on a
 * surface that cannot submit would be the exact "pretends to send" behaviour the
 * disabled report row exists to avoid.
 */
@Composable
fun ReportReasonDialog(onSelect: (ChatReportReason) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.chat_reportReasonPrompt)) },
        text = {
            Column(horizontalAlignment = Alignment.Start) {
                ChatReportReason.entries.forEach { reason ->
                    TextButton(
                        onClick = { onSelect(reason) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(reason.labelRes()), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.profile_cancelButton))
            }
        },
    )
}

/** The `chat.reportReason*` label for a [ChatReportReason]. */
private fun ChatReportReason.labelRes(): Int =
    when (this) {
        ChatReportReason.HARASSMENT -> R.string.chat_reportReasonHarassment
        ChatReportReason.HATE_OR_ABUSE -> R.string.chat_reportReasonHateOrAbuse
        ChatReportReason.SPAM -> R.string.chat_reportReasonSpam
        ChatReportReason.UNSAFE_DRIVING -> R.string.chat_reportReasonUnsafeDriving
        ChatReportReason.PRIVACY -> R.string.chat_reportReasonPrivacy
        ChatReportReason.OTHER -> R.string.chat_reportReasonOther
    }
