package com.kungsbackacarcommunity.app.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing

/**
 * Pure, JVM-testable helpers behind the inline reply-to-message UI (WhatsApp-style
 * quoted reply — a flat quote, NOT a thread). Shared by the two chat surfaces this
 * feature covers (the community/convoy channels and DMs); event chat is a separate
 * system and out of scope.
 *
 * The DENORMALIZED reply snapshot itself ({ messageId, senderUid,
 * senderDisplayName, textPreview }) is built SERVER-SIDE and mirrored onto each
 * domain's own message model (chatchannels ChannelReplyTo / dm DmReplyTo). These
 * helpers are the small pieces of logic the UI runs client-side: bounding the
 * preview text a fresh optimistic quote carries, and locating a quoted parent in
 * the loaded window so the quote header can scroll to it.
 */
object ChatReply {
    /**
     * Upper bound on the quote preview the client snapshots onto an OPTIMISTIC
     * reply bubble, mirroring the backend's CHAT_MESSAGE_PREVIEW_LENGTH /
     * DM_MESSAGE_PREVIEW_LENGTH (both 120) so the instantly-shown quote matches
     * the one the server will store and deliver. The delivered document then
     * carries the server's authoritative snapshot and supersedes this.
     */
    const val QUOTE_PREVIEW_MAX_LENGTH = 120

    /** The bounded, trimmed quote preview for a parent message's text. */
    fun quotePreview(text: String): String = text.trim().take(QUOTE_PREVIEW_MAX_LENGTH)

    /**
     * The index of the message with [targetId] within [items], or null when it is
     * not in the loaded window (it expired, or paged out). Drives tap-to-scroll:
     * a null result means "the original is gone" and the tap does nothing
     * gracefully rather than scrolling to the wrong row. Pure so the lookup is
     * unit-testable off-device.
     */
    fun <T> indexOfMessage(items: List<T>, targetId: String, id: (T) -> String): Int? =
        items.indexOfFirst { id(it) == targetId }.takeIf { it >= 0 }
}

/**
 * The quote HEADER attached above a sent bubble that is an inline reply: the
 * quoted author's name and a one-line preview of what was replied to, indented
 * behind a leading accent bar so it reads as visually distinct from the reply's
 * own text. Shared by the channel and DM bubbles so the quote renders identically
 * across surfaces.
 *
 * Tapping it invokes [onClick] — the caller scrolls to and briefly highlights the
 * original message when it is still loaded, or does nothing when it has expired.
 * When [onClick] is null (e.g. the parent is known to be gone) the header is inert
 * and not announced as a button.
 *
 * Colours are derived from [isOwn] so the header sits legibly inside either bubble
 * (the caller's primary bubble vs. another member's surface-variant bubble),
 * keeping it theme-aware in light and dark.
 */
@Composable
fun ChatQuoteHeader(
    authorName: String,
    preview: String,
    isOwn: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val accent = if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val authorColor =
        if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val previewColor =
        if (isOwn) {
            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val label = stringResource(R.string.chat_replyQuoteHeaderAction, authorName, preview)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(KccRadius.sm))
                .then(
                    if (onClick != null) {
                        Modifier.clickable(role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                // One merged label so a screen reader announces the quote as a
                // single "reply to X: <preview>" element rather than two stray
                // texts. mergeDescendants (not clearAndSet) keeps the clickable's
                // Button role + tap action in the a11y tree so it stays operable.
                .semantics(mergeDescendants = true) { contentDescription = label }
                .padding(vertical = KccSpacing.s1),
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
    ) {
        // Leading accent bar — the conventional "this is a quote" marker.
        Box(
            modifier =
                Modifier
                    .width(3.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(KccRadius.sm))
                    .background(accent),
        )
        Column(modifier = Modifier.padding(vertical = KccSpacing.s1)) {
            Text(
                text = authorName,
                style = MaterialTheme.typography.labelMedium,
                color = authorColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = previewColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The quote CHIP shown directly above the composer once the member has chosen to
 * reply to a message, before they send: the quoted author's name, a one-line
 * preview of the message being replied to, and an ✕ to cancel the reply. Typing
 * and sending while it is showing attaches the reply; [onCancel] clears it.
 *
 * Shared by the channel and DM composers so the pre-send affordance is identical
 * across surfaces.
 */
@Composable
fun ReplyComposerChip(
    authorName: String,
    preview: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bannerLabel = stringResource(R.string.chat_replyComposerBanner, authorName)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(KccRadius.md),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = KccSpacing.s3, end = KccSpacing.s1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            Box(
                modifier =
                    Modifier
                        .width(3.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(KccRadius.sm))
                        .background(MaterialTheme.colorScheme.primary),
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(vertical = KccSpacing.s2)
                        // Announced as one element; the ✕ button carries its own label.
                        .clearAndSetSemantics { contentDescription = bannerLabel },
            ) {
                Text(
                    text = stringResource(R.string.chat_replyComposerReplyingTo, authorName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.chat_replyCancel),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
