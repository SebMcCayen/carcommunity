package com.kungsbackacarcommunity.app.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision half of the keyboard re-pin shared by all three chat surfaces
 * (group channels, DM threads, event chat).
 *
 * The trigger half — "the IME just rose" — is not covered here: no instrumentation
 * environment available in this repo reliably raises a soft keyboard
 * (`ChatHubInsetsTest` documents the emulator refusing to show one at all, with
 * `mInputShown=false` even for a focused EditText), so there is no honest seam for
 * it. What IS pinned is the part that decides whether the user gets yanked, which
 * is the behaviour that can regress silently.
 */
class ChatListPinningTest {
    @Test
    fun repinsWhenTheReaderIsSittingOnTheNewestMessage() {
        assertTrue(ChatListPinning.shouldRepinToNewest(lastVisibleIndex = 19, totalItemsCount = 20))
    }

    @Test
    fun repinsWhenTheReaderIsOneItemOffTheBottom() {
        // The same near-bottom tolerance the new-message auto-scroll uses: a
        // partially-visible last row still counts as "at the bottom".
        assertTrue(ChatListPinning.shouldRepinToNewest(lastVisibleIndex = 18, totalItemsCount = 20))
    }

    @Test
    fun doesNotYankAReaderScrolledUpIntoHistory() {
        // THE guard. Tapping the composer while reading older messages must not
        // teleport the reader to the bottom.
        assertFalse(ChatListPinning.shouldRepinToNewest(lastVisibleIndex = 17, totalItemsCount = 20))
        assertFalse(ChatListPinning.shouldRepinToNewest(lastVisibleIndex = 3, totalItemsCount = 20))
        assertFalse(ChatListPinning.shouldRepinToNewest(lastVisibleIndex = 0, totalItemsCount = 20))
    }

    @Test
    fun anEmptyListHasNothingToPinTo() {
        // animateScrollToItem(totalItems - 1) would be index -1.
        assertFalse(ChatListPinning.shouldRepinToNewest(lastVisibleIndex = -1, totalItemsCount = 0))
    }

    @Test
    fun aSingleItemNotYetLaidOutListPins() {
        // Nothing has been measured (lastVisibleIndex -1), but with one item the
        // pin is a no-op rather than a yank.
        assertTrue(ChatListPinning.shouldRepinToNewest(lastVisibleIndex = -1, totalItemsCount = 1))
    }

    @Test
    fun aNotYetLaidOutListOfTwoOrMoreDoesNotPin() {
        // -1 >= 2 - 2 is false. Deliberate, and pinned here because the boundary
        // is easy to "fix" into a yank: with nothing measured, the effect knows
        // nothing about where the reader is and defers to the new-message
        // auto-scroll instead of guessing.
        assertFalse(ChatListPinning.shouldRepinToNewest(lastVisibleIndex = -1, totalItemsCount = 2))
        assertFalse(ChatListPinning.shouldRepinToNewest(lastVisibleIndex = -1, totalItemsCount = 20))
    }

    // --- shouldFollowNewest: the new-message follow decision. The open-on-newest
    // JUMP is owned by KeepPinnedToNewest's layout-gated effect and is not part of
    // this pure decision, so every case below assumes an already-laid-out list. ---

    @Test
    fun followsOwnSendEvenFromDeepInHistory() {
        // Always follow your OWN message down, wherever you were reading.
        assertTrue(
            ChatListPinning.shouldFollowNewest(
                lastVisibleIndex = 0,
                totalItemsCount = 20,
                isOwnMessage = true,
            ),
        )
    }

    @Test
    fun followsAnIncomingMessageWhenTheReaderIsAtTheBottom() {
        assertTrue(
            ChatListPinning.shouldFollowNewest(
                lastVisibleIndex = 19,
                totalItemsCount = 20,
                isOwnMessage = false,
            ),
        )
        // One row off the bottom still counts, matching the repin tolerance.
        assertTrue(
            ChatListPinning.shouldFollowNewest(
                lastVisibleIndex = 18,
                totalItemsCount = 20,
                isOwnMessage = false,
            ),
        )
    }

    @Test
    fun doesNotFollowAnIncomingMessageWhenScrolledUpReadingHistory() {
        // THE guard for the reported bug's sibling: an incoming message must not
        // yank a reader who has scrolled up. (Their own send still would — covered
        // above — but someone else's must not.)
        assertFalse(
            ChatListPinning.shouldFollowNewest(
                lastVisibleIndex = 17,
                totalItemsCount = 20,
                isOwnMessage = false,
            ),
        )
        assertFalse(
            ChatListPinning.shouldFollowNewest(
                lastVisibleIndex = 3,
                totalItemsCount = 20,
                isOwnMessage = false,
            ),
        )
    }

    @Test
    fun neverFollowsAnEmptyList() {
        // animateScrollToItem(totalItems - 1) would be index -1 — nothing to do,
        // even for an "own" send that hasn't been laid out yet.
        assertFalse(
            ChatListPinning.shouldFollowNewest(
                lastVisibleIndex = -1,
                totalItemsCount = 0,
                isOwnMessage = false,
            ),
        )
        assertFalse(
            ChatListPinning.shouldFollowNewest(
                lastVisibleIndex = -1,
                totalItemsCount = 0,
                isOwnMessage = true,
            ),
        )
    }
}
