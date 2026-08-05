package com.kungsbackacarcommunity.app.chat

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.collect
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
     * by [KeepPinnedToNewest]'s open effect, which is why this can assume the list is
     * already laid out and does not special-case a not-yet-measured (totalItemsCount
     * 0) list into "scroll".
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

    /**
     * Should the list stay pinned to its newest item on THIS frame of the IME
     * open animation?
     *
     * The keyboard does not appear instantly: `WindowInsets.ime` animates from 0
     * up to the full keyboard height over ~250ms, and the composer's ime padding
     * shrinks the message list's viewport from the bottom in lockstep. A
     * `LazyColumn` holds its scroll OFFSET, not its bottom edge, so across that
     * animation the newest message slides down under the composer unless it is
     * re-pinned on every frame the inset grows — a SINGLE re-pin on the rising edge
     * (when the inset is still ~0 and nothing has shrunk yet) settles against the
     * full-height layout and is undone the moment the inset actually grows.
     *
     * The follow is committed ONCE, on the first growing frame, from where the
     * reader was parked ([atBottom]); thereafter [alreadyFollowing] holds it for the
     * rest of the rise, so a mid-animation frame where the newest row has already
     * slipped a little under the composer — and so momentarily reads as "not at the
     * bottom" — does not abandon the follow half-way. A reader scrolled up into
     * history never starts a follow, so tapping the input while reading old messages
     * still leaves them put.
     *
     * @param previousBottomPx the IME bottom inset (px) on the previous frame.
     * @param currentBottomPx the IME bottom inset (px) on this frame.
     * @param alreadyFollowing whether a follow was already committed earlier in this
     *   same rise (this function's own previous-frame result).
     * @param atBottom whether the reader is parked at/near the newest message
     *   ([shouldRepinToNewest]); consulted only to START a follow.
     * @return whether to keep the list pinned to the newest item this frame; also
     *   the next frame's [alreadyFollowing].
     */
    fun shouldKeepPinnedDuringImeRise(
        previousBottomPx: Int,
        currentBottomPx: Int,
        alreadyFollowing: Boolean,
        atBottom: Boolean,
    ): Boolean {
        // Only while the keyboard is GROWING. A settled (fully-open) or shrinking
        // (closing) inset ends the follow: the growing-back viewport re-reveals the
        // bottom on its own, so a closing keyboard needs no scroll and the reader
        // gets scrolling back.
        val rising = currentBottomPx > previousBottomPx
        if (!rising) return false
        return alreadyFollowing || atBottom
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
    // Saveable, sharing the SAME lifetime as rememberLazyListState's own saved
    // scroll position: both are restored together across a configuration change
    // (rotation) or process death, and both start fresh on a genuinely new open.
    // A plain remember here would reset to false on rotation while the list's saved
    // scroll offset came back intact, re-firing the open jump and yanking a reader
    // who had scrolled up into history back down to the bottom.
    var didInitialJump by rememberSaveable { mutableStateOf(false) }

    // ON OPEN: wait for the first real layout, then JUMP (no animation) to the last
    // row — once. snapshotFlow.first { it > 0 } is the fix for the async-load race:
    // the effect suspends until the LazyColumn has measured something, instead of
    // firing a no-op scroll against an empty layout and never correcting it. Gated
    // on !didInitialJump so a rotation (which hands us a fresh LazyListState instance
    // but a RESTORED scroll position) doesn't re-jump a scrolled-up reader.
    LaunchedEffect(listState) {
        if (didInitialJump) return@LaunchedEffect
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
 * Keeps [listState] pinned to its newest item for the WHOLE time the soft keyboard
 * is animating open, subject to [ChatListPinning.shouldKeepPinnedDuringImeRise].
 *
 * `WindowInsets.ime` animates from 0 to the full keyboard height over ~250ms, and
 * the composer's ime padding shrinks this list's viewport from the bottom in
 * lockstep. Because a `LazyColumn` holds its scroll OFFSET rather than its bottom
 * edge, the newest message slides down under the composer across that animation
 * unless it is re-pinned on every frame the inset grows — a single re-pin on the
 * rising edge (inset still ~0, nothing shrunk yet) settles against the full-height
 * layout and is undone the instant the inset actually grows.
 *
 * So this observes the animated inset directly via [snapshotFlow] and re-snaps the
 * list to the last item on each growing frame — an instant, non-animated
 * `scrollToItem`, which in lockstep with the per-frame padding growth reads as the
 * list gliding up with the keyboard. The follow is committed once, from where the
 * reader was parked, and released when the inset settles (fully open) or shrinks
 * (closing), where the growing-back viewport re-reveals the bottom on its own.
 */
@Composable
fun RepinToNewestOnImeRise(listState: LazyListState) {
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    LaunchedEffect(listState, imeInsets, density) {
        // Seeded from the current inset so a list composed with the keyboard
        // ALREADY up (e.g. after a rotation) sees no rise and does not scroll —
        // the restore is KeepPinnedToNewest's job, not this effect's.
        var previousBottom = imeInsets.getBottom(density)
        var following = false
        // snapshotFlow's first emission is the current value (== previousBottom),
        // so the first frame is never a rise and never scrolls.
        snapshotFlow { imeInsets.getBottom(density) }.collect { currentBottom ->
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val atBottom =
                ChatListPinning.shouldRepinToNewest(lastVisibleIndex, layoutInfo.totalItemsCount)
            following =
                ChatListPinning.shouldKeepPinnedDuringImeRise(
                    previousBottomPx = previousBottom,
                    currentBottomPx = currentBottom,
                    alreadyFollowing = following,
                    atBottom = atBottom,
                )
            previousBottom = currentBottom
            if (following) {
                val totalItems = layoutInfo.totalItemsCount
                if (totalItems > 0) listState.scrollToItem(totalItems - 1)
            }
        }
    }
}
