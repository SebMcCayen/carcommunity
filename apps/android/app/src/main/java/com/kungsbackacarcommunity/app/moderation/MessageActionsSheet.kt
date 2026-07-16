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
const val MESSAGE_ACTIONS_BLOCK_TEST_TAG = "message_actions_block"
const val MESSAGE_ACTIONS_REPORT_TEST_TAG = "message_actions_report"

/**
 * The long-press action sheet for another member's chat message — the single
 * shared moderation menu behind every chat surface (community + convoy channels,
 * DMs, event chat).
 *
 * The caller only composes this for a message it has already cleared with
 * [MessageModeration.canActOn], so the sheet never appears on your own message
 * (nor on one with no resolvable author).
 *
 * @param memberName the author's display name, or null when unknown — the block
 *   row then falls back to the generic "Block user" label rather than rendering
 *   "Block null".
 * @param canBlock false in a config-less build with no blocking repository; the
 *   block row is then omitted entirely (an action that cannot run is not an
 *   action).
 * @param reportAvailability [ReportAvailability.Wired] enables the report row;
 *   [ReportAvailability.BackendMissing] renders it DISABLED with a note naming
 *   the limitation, so the user is never misled into believing a report was
 *   filed on a surface that has no report backend yet.
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

            SheetAction(
                text = stringResource(R.string.moderation_reportMessage),
                onClick = onReport,
                enabled = reportWired,
                testTag = MESSAGE_ACTIONS_REPORT_TEST_TAG,
            )
            if (!reportWired) {
                Text(
                    text = stringResource(R.string.moderation_reportMessageUnavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = KccSpacing.s4),
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
