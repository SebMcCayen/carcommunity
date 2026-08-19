package com.kungsbackacarcommunity.app.design

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R

/** Test tag for the whole quick-emoji row (Compose tests locate the row by this). */
const val CHAT_QUICK_EMOJI_ROW_TEST_TAG = "chat-quick-emoji-row"

/** Test tag for one emoji chip, keyed by its glyph (each glyph is unique). */
fun chatQuickEmojiTestTag(glyph: String): String = "chat-quick-emoji-$glyph"

/**
 * One pre-chosen quick-reaction emoji: the [glyph] that is SENT verbatim as a whole
 * chat message on tap, plus [contentDescriptionRes] naming it for screen readers
 * (a bare emoji glyph otherwise announces as an unlabeled button).
 */
data class QuickEmoji(val glyph: String, @StringRes val contentDescriptionRes: Int)

/**
 * The fixed, ordered quick-emoji set every chat composer shows in a single row
 * above its input — a small set of one-tap reactions, not a picker. Kept as one
 * shared constant so the community + convoy channels, event chat, and direct
 * messages all show the same emojis in the same order; change it here and every
 * chat surface follows.
 *
 * The three the owner asked for are load-bearing and pinned by
 * [ChatQuickEmojiRowTest]: 😊 happy, 😢 sad, 👑 crown (the app's motif). The rest
 * are the everyday car-community reactions — a laugh, a thumbs-up, a heart, and a
 * car.
 */
val ChatQuickEmojis: List<QuickEmoji> =
    listOf(
        QuickEmoji("😊", R.string.chat_quickEmoji_happy),
        QuickEmoji("😂", R.string.chat_quickEmoji_laughing),
        QuickEmoji("😢", R.string.chat_quickEmoji_sad),
        QuickEmoji("👍", R.string.chat_quickEmoji_thumbsUp),
        QuickEmoji("❤️", R.string.chat_quickEmoji_heart),
        QuickEmoji("🚗", R.string.chat_quickEmoji_car),
        QuickEmoji("👑", R.string.chat_quickEmoji_crown),
    )

/**
 * A horizontal row of pre-chosen emojis pinned directly ABOVE a chat composer's
 * text input. Tapping one calls [onEmojiSelected] with that emoji's glyph — the
 * caller sends it immediately down the SAME optimistic path its Send button uses,
 * so it lands as a normal one-character chat message (a quick reaction, never text
 * inserted into the draft field).
 *
 * Shared by every chat surface (community + convoy channels, event chat, direct
 * messages) so the set and behaviour cannot drift between them.
 *
 * [enabled] mirrors whatever gate the surface's Send button carries (e.g. event
 * chat disables both while a send is already in flight); when false the chips dim
 * and stop responding, exactly like the disabled Send button beside them.
 *
 * A [LazyRow] rather than a plain Row so the fixed set never overflows the screen
 * edge — it simply scrolls horizontally if a future set (or a very narrow device)
 * would run past the composer's width.
 */
@Composable
fun ChatQuickEmojiRow(
    onEmojiSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val rowLabel = stringResource(R.string.chat_quickEmojiRow)
    LazyRow(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(CHAT_QUICK_EMOJI_ROW_TEST_TAG)
                .semantics { contentDescription = rowLabel },
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
    ) {
        items(ChatQuickEmojis, key = { it.glyph }) { emoji ->
            val label = stringResource(emoji.contentDescriptionRes)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier =
                    Modifier
                        .size(40.dp)
                        // Material's disabled-content alpha, so a gated row reads as
                        // greyed-out in step with the Send button next to it.
                        .alpha(if (enabled) 1f else 0.38f)
                        // clip BEFORE clickable so the tap ripple is bounded to the
                        // circular chip rather than painting a square behind it.
                        .clip(CircleShape)
                        .clickable(
                            enabled = enabled,
                            role = Role.Button,
                            onClick = { onEmojiSelected(emoji.glyph) },
                        )
                        .semantics { contentDescription = label }
                        .testTag(chatQuickEmojiTestTag(emoji.glyph)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = emoji.glyph,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
