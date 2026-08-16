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
    fun doesNotFollowAnIncomingMessageWhileTheUserIsFlingingUpNearTheBottom() {
        // #870: a fast upward flick lifts the finger while still within the
        // near-bottom tolerance, then a fling carries the reader up. An incoming
        // message arriving mid-fling must NOT fire the follow — animateScrollToItem
        // would cancel the fling and drag the reader back to the bottom ("scroll up
        // fast and the chat window scrolls away"). While the USER is scrolling the
        // incoming message defers, even though the reader still reads as at/near the
        // bottom.
        assertFalse(
            ChatListPinning.shouldFollowNewest(
                lastVisibleIndex = 19,
                totalItemsCount = 20,
                isOwnMessage = false,
                isUserScrolling = true,
            ),
        )
        assertFalse(
            ChatListPinning.shouldFollowNewest(
                lastVisibleIndex = 18,
                totalItemsCount = 20,
                isOwnMessage = false,
                isUserScrolling = true,
            ),
        )
    }

    @Test
    fun stillFollowsYourOwnSendEvenWhileScrolling() {
        // The user-scrolling guard is only for OTHER people's messages. Your own send
        // always follows down, so hitting send mid-fling still lands you on it.
        assertTrue(
            ChatListPinning.shouldFollowNewest(
                lastVisibleIndex = 2,
                totalItemsCount = 20,
                isOwnMessage = true,
                isUserScrolling = true,
            ),
        )
    }

    @Test
    fun catchesUpABurstThatLandsDuringItsOwnFollowAnimation() {
        // The review's catch on the first #870 fix: gating on
        // LazyListState.isScrollInProgress would have suppressed the follow for the
        // 2nd, 3rd, … message of a burst, because THIS effect's own follow animation
        // keeps that flag true — leaving a pinned reader stuck a row above the newest.
        // The programmatic follow is NOT a user scroll (isUserScrolling = false, since
        // no drag gesture set it), so a burst message landing mid-animation still
        // follows and the reader ends on the newest, not above it. A reader one row
        // off the bottom (the animation has not fully landed yet) still counts.
        assertTrue(
            ChatListPinning.shouldFollowNewest(
                lastVisibleIndex = 19,
                totalItemsCount = 20,
                isOwnMessage = false,
                isUserScrolling = false,
            ),
        )
        assertTrue(
            ChatListPinning.shouldFollowNewest(
                lastVisibleIndex = 18,
                totalItemsCount = 20,
                isOwnMessage = false,
                isUserScrolling = false,
            ),
        )
    }

    @Test
    fun followsAnIncomingMessageAtTheBottomOnceTheScrollHasSettled() {
        // Same near-bottom reader, no user scroll in progress: the follow resumes, so
        // a settled reader parked at the bottom still tracks new messages down.
        assertTrue(
            ChatListPinning.shouldFollowNewest(
                lastVisibleIndex = 19,
                totalItemsCount = 20,
                isOwnMessage = false,
                isUserScrolling = false,
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

    // --- shouldKeepPinnedDuringImeRise: the per-frame follow decision across the
    // keyboard's open animation. THIS is the fix for "the newest message is behind
    // the keyboard": the newest row must stay pinned to the bottom on every frame
    // the IME inset grows, not just once on the rising edge. ---

    @Test
    fun startsFollowingOnTheFirstGrowingFrameWhenAtTheBottom() {
        // Inset grew (keyboard opening) and the reader was parked at the newest
        // message: commit to the follow.
        assertTrue(
            ChatListPinning.shouldKeepPinnedDuringImeRise(
                previousBottomPx = 0,
                currentBottomPx = 40,
                alreadyFollowing = false,
                atBottom = true,
            ),
        )
    }

    @Test
    fun doesNotStartFollowingAReaderScrolledUpIntoHistory() {
        // THE guard: tapping the composer while reading old messages must not yank
        // the reader down, even as the keyboard rises.
        assertFalse(
            ChatListPinning.shouldKeepPinnedDuringImeRise(
                previousBottomPx = 0,
                currentBottomPx = 40,
                alreadyFollowing = false,
                atBottom = false,
            ),
        )
    }

    @Test
    fun keepsFollowingMidRiseEvenIfTheNewestRowMomentarilyReadsOffTheBottom() {
        // The core of the fix. Once committed, a mid-animation frame where the
        // newest row has slipped a little under the composer — so atBottom now reads
        // false — must NOT abandon the follow, or the message settles half-hidden.
        assertTrue(
            ChatListPinning.shouldKeepPinnedDuringImeRise(
                previousBottomPx = 120,
                currentBottomPx = 200,
                alreadyFollowing = true,
                atBottom = false,
            ),
        )
    }

    @Test
    fun stopsFollowingOnceTheInsetSettlesFullyOpen() {
        // Inset unchanged (keyboard fully open): the follow is released so the
        // reader can scroll freely, even though it was following a frame ago.
        assertFalse(
            ChatListPinning.shouldKeepPinnedDuringImeRise(
                previousBottomPx = 300,
                currentBottomPx = 300,
                alreadyFollowing = true,
                atBottom = true,
            ),
        )
    }

    @Test
    fun stopsFollowingWhileTheKeyboardIsClosing() {
        // Inset shrinking (keyboard closing): no scroll — the growing-back viewport
        // re-reveals the bottom on its own, and the reader gets scrolling back. This
        // is what returns the newest message to the bottom of the full window when
        // the keyboard is dismissed.
        assertFalse(
            ChatListPinning.shouldKeepPinnedDuringImeRise(
                previousBottomPx = 300,
                currentBottomPx = 180,
                alreadyFollowing = true,
                atBottom = true,
            ),
        )
    }
}
