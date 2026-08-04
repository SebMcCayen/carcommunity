package com.kungsbackacarcommunity.app.chat

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.first

/**
 * The one keyboard/scroll interaction shared by every chat surface — the group
 * channels ([com.kungsbackacarcommunity.app.chatchannels.ChannelChatContent]),
 * DM threads ([com.kungsbackacarcommunity.app.dm.ChatScreen]) and event chat
 * ([EventChatScreen]).
 *
 * All three pad their composer by `WindowInsets.ime.union(navigationBars)`, which
 * shrinks the message list's viewport from the bottom when the keyboard rises. A
 * `LazyColumn` holds its scroll OFFSET, not its bottom edge, so without a re-pin
 * the newest message slides out under the keyboard the moment the user taps the
 * input. Extracted here so the three surfaces cannot drift into three subtly
 * different scroll behaviours.
 */
object ChatListPinning {
    /**
     * Should the list jump back to the newest item now that the viewport shrank?
     *
     * Only for a reader who was already parked at the bottom: someone who has
     * scrolled up through history and then taps the composer must not be yanked
     * back down. "At the bottom" is the last item or the one before it, matching
     * the near-bottom tolerance the new-message auto-scroll already uses.
     *
     * A list that has not been laid out yet reports lastVisibleIndex -1, which
     * clears the threshold only for a SINGLE item (-1 >= 1 - 2). That is
     * deliberate: with one item the pin is a no-op, and beyond that nothing is
     * known about where the reader is, so the effect defers to the new-message
     * auto-scroll rather than guessing.
     *
     * @param lastVisibleIndex index of the last visible item, or -1 when the list
     *   has not been laid out yet.
     * @param totalItemsCount the LazyColumn's item count (messages plus any
     *   prepended "load older" header).
     */
    fun shouldRepinToNewest(lastVisibleIndex: Int, totalItemsCount: Int): Boolean {
        // Nothing to scroll to.
        if (totalItemsCount <= 0) return false
        return lastVisibleIndex >= totalItemsCount - 2
    }

    /**
     * Should the list follow a newly-arrived message down to the newest item?
     *
     * Yes when it is the reader's OWN send (always follow your own message down),
     * or when they are already sitting at/near the bottom — the last row or the one
     * before it, the same near-bottom tolerance as [shouldRepinToNewest]. A reader
     * who has scrolled UP into history is left exactly where they are, so an
     * incoming message never yanks them away from what they were reading.
     *
     * This is the FOLLOW decision only; the one-time jump-to-bottom on OPEN is owned
     * by [ScrollToNewestOnOpen], which is why this can assume the list is already
     * laid out and does not special-case a not-yet-measured (totalItemsCount 0)
     * list into "scroll".
     *
     * @param lastVisibleIndex index of the last visible item, or -1 when the list
     *   has not been laid out yet.
     * @param totalItemsCount the LazyColumn's item count.
     * @param isOwnMessage whether the newly-arrived newest message is the caller's
     *   own send.
     */
    fun shouldFollowNewest(
        lastVisibleIndex: Int,
        totalItemsCount: Int,
        isOwnMessage: Boolean,
    ): Boolean {
        if (totalItemsCount <= 0) return false
        if (isOwnMessage) return true
        return shouldRepinToNewest(lastVisibleIndex, totalItemsCount)
    }
}

/**
 * Positions [listState] at the newest message and keeps it there, the "open lands
 * at the bottom" behaviour shared by every chat surface (group channels, DM
 * threads, event chat) so the three cannot drift into subtly different scroll
 * behaviours.
 *
 * Two distinct jobs, deliberately split so the initial open cannot fight the
 * new-message follow:
 *
 *  1. ON OPEN — a ONE-TIME, NON-ANIMATED jump to the last row, run only once the
 *     `LazyColumn` has actually measured at least one item. Folding this into the
 *     new-message effect (as all three surfaces used to) meant it fired while the
 *     list was still un-laid-out (`totalItemsCount == 0`) and animated a long
 *     scroll that settled SHORT of the bottom as rows finished measuring — the
 *     "chat opens in the middle" complaint. Waiting for the first real layout and
 *     jumping (no animation) lands straight at the newest message every time.
 *
 *  2. ON A NEW MESSAGE — an animated follow to the bottom, but only when it won't
 *     fight the reader ([ChatListPinning.shouldFollowNewest]). Deferred until the
 *     open jump has happened so the two never race on the first frame.
 *
 * @param newestMessageId id of the newest message, or null when the list is empty.
 *   Keys the follow effect, so it re-runs exactly once per newly-arrived message.
 * @param isOwnNewestMessage whether that newest message is the caller's own send.
 */
@Composable
fun KeepPinnedToNewest(
    listState: LazyListState,
    newestMessageId: String?,
    isOwnNewestMessage: Boolean,
) {
    // Reset with the list itself (a fresh thread gets a fresh state), so re-opening
    // a chat jumps to the bottom again rather than trusting a stale flag.
    var didInitialJump by remember(listState) { mutableStateOf(false) }

    // ON OPEN: wait for the first real layout, then JUMP (no animation) to the last
    // row. snapshotFlow.first { it > 0 } is the fix for the async-load race — the
    // effect suspends until the LazyColumn has measured something, instead of firing
    // a no-op scroll against an empty layout and never correcting it.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        didInitialJump = true
    }

    // ON A NEW MESSAGE: follow to the bottom, but only after the open jump owns the
    // first positioning, and only when the reader is at/near the bottom or it's
    // their own send. Scrolled up reading history, they are left put.
    LaunchedEffect(newestMessageId) {
        if (newestMessageId == null || !didInitialJump) return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val totalItems = layoutInfo.totalItemsCount
        if (ChatListPinning.shouldFollowNewest(lastVisibleIndex, totalItems, isOwnNewestMessage)) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }
}

/**
 * Is the soft keyboard up? Read as a plain inset rather than the experimental
 * `WindowInsets.isImeVisible`.
 *
 * `derivedStateOf` so the per-frame inset animation doesn't recompose the caller
 * 60 times a second — only the two transitions (up / down) propagate, which is
 * what makes [RepinToNewestOnImeRise] a rising-EDGE trigger rather than something
 * that fires continuously while the keyboard is open.
 */
@Composable
fun rememberImeVisible(): Boolean {
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val visible by remember(imeInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    return visible
}

/**
 * Re-pins [listState] to its newest item on the keyboard's RISING edge, subject
 * to [ChatListPinning.shouldRepinToNewest].
 *
 * Keyed on the boolean, so closing the keyboard — which grows the viewport back
 * on its own — moves nothing.
 */
@Composable
fun RepinToNewestOnImeRise(listState: LazyListState) {
    val imeVisible = rememberImeVisible()
    LaunchedEffect(imeVisible) {
        if (!imeVisible) return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val totalItems = layoutInfo.totalItemsCount
        if (ChatListPinning.shouldRepinToNewest(lastVisibleIndex, totalItems)) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }
}
