package com.kungsbackacarcommunity.app.convoy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.map.ConvoyFocusMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the convoy status bar's destructive control: WHICH convoy
 * a confirmed end actually ends, and whether the control's destructive colour
 * respects its own disabled state.
 */
@RunWith(AndroidJUnit4::class)
class ConvoyStatusBarTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * Strings come from the resources rather than being retyped in English: the
     * app's DEFAULT resources are Swedish (`values/`), with English in
     * `values-en/`, so a hard-coded English literal would only match on an
     * English-locale device.
     */
    private fun string(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private val twoMembers =
        listOf(
            ConvoyBarMember(uid = "u1", displayName = "Alice", avatarPath = null),
            ConvoyBarMember(uid = "u2", displayName = "Bob", avatarPath = null),
        )

    /**
     * Three accepted members, so that ONE of them leaving still leaves two behind
     * — which is what makes a plain leave (and, for a leader, a real choice
     * between leaving and ending) reachable at all. With [twoMembers] every exit
     * ends the convoy, which is a different branch on purpose.
     */
    private val threeMembers =
        twoMembers + ConvoyBarMember(uid = "u3", displayName = "Cara", avatarPath = null)

    private fun ownerState(convoyId: String) =
        ConvoyBarState(
            convoyId = convoyId,
            members = twoMembers,
            viewerIsOwner = true,
            busy = false,
            inviteAvailability = ConvoyBar.inviteAvailability,
            leaveAvailability = ConvoyBar.leaveAvailability,
        )

    private fun memberState(convoyId: String) =
        ConvoyBarState(
            convoyId = convoyId,
            members = twoMembers,
            viewerIsOwner = false,
            busy = false,
            inviteAvailability = ConvoyBar.inviteAvailability,
            leaveAvailability = ConvoyBar.leaveAvailability,
        )

    /** A non-leader whose exit leaves two others behind → a PLAIN leave. */
    private fun memberOfThreeState(convoyId: String) =
        memberState(convoyId).copy(members = threeMembers)

    /** A leader with two others behind them → the leave-or-end CHOOSER. */
    private fun leaderOfThreeState(convoyId: String) =
        ownerState(convoyId).copy(members = threeMembers)

    /**
     * The one that matters: the bar's [ConvoyBarState] is hoisted and refreshes
     * underneath the composable, so the convoy it describes can change WHILE the
     * end-convoy confirmation is open. Confirming must end the convoy the dialog
     * was opened for, never whichever convoy the bar happens to be showing by the
     * time the user's finger lands.
     *
     * The assertion is on the OBSERVABLE — the convoy id `onEndConvoy` actually
     * received — not on the dialog closing, which would pass for any
     * implementation. Against a bar that reads `state.convoyId` inside the
     * confirm handler this fails with "expected:<c1> but was:<c2>": it really
     * ends the wrong convoy.
     */
    @Test
    fun confirmingAfterTheBarSwitchesConvoy_endsTheConvoyTheDialogWasOpenedFor() {
        var ended: String? = null
        var current by mutableStateOf(ownerState("c1"))
        composeTestRule.setContent {
            KccTheme { ConvoyStatusBar(state = current, onEndConvoy = { ended = it }) }
        }

        // Open the confirmation while the bar is describing convoy "c1".
        composeTestRule.onNodeWithTag(CONVOY_BAR_LEAVE_TAG).performClick()
        composeTestRule.waitForIdle()
        assertNull("nothing may be ended before the user confirms", ended)

        // A refresh swaps the bar over to a different convoy under the open
        // dialog — a newly started convoy outranking the forming one, say.
        current = ownerState("c2")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(string(R.string.convoy_barEndConfirmAction)).performClick()
        composeTestRule.waitForIdle()

        assertEquals("must end the convoy the dialog named, not the bar's new one", "c1", ended)
    }

    /**
     * The convoy the dialog is about is fixed at open time, so a refresh must not
     * silently cancel a considered destructive decision (which is what keying the
     * dialog flag to `state.convoyId` would do). The dialog stays up.
     */
    @Test
    fun switchingConvoyUnderTheOpenDialog_doesNotSilentlyDismissIt() {
        var current by mutableStateOf(ownerState("c1"))
        composeTestRule.setContent {
            KccTheme { ConvoyStatusBar(state = current, onEndConvoy = {}) }
        }

        composeTestRule.onNodeWithTag(CONVOY_BAR_LEAVE_TAG).performClick()
        composeTestRule.waitForIdle()

        current = ownerState("c2")
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithText(string(R.string.convoy_barEndConfirmBody))
            .assertIsDisplayed()
    }

    /**
     * The one failure in this component that would actually hurt people: a member
     * tapping "leave" and silently ending everyone's drive. The trailing control
     * routes on the EXIT CHOICE, not on `leaveAvailability`, so a non-leader's tap
     * opens the LEAVE confirmation (never END's, and never the leader's chooser)
     * and, once confirmed, reaches the leave handler alone — the case a click path
     * keyed on availability would get wrong now that `convoy-leave` is wired and
     * the member flag is Wired.
     */
    @Test
    fun aMembersLeaveNeverReachesTheEndConvoyPath() {
        var ended: String? = null
        var left: String? = null
        // Non-leader with two others behind them → a plain leave, wired handler.
        val state = memberOfThreeState("c1")
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = state,
                    onEndConvoy = { ended = it },
                    onLeaveConvoy = { left = it },
                )
            }
        }

        composeTestRule.onNodeWithTag(CONVOY_BAR_LEAVE_TAG).performClick()
        composeTestRule.waitForIdle()

        // The confirmation shown is LEAVE's, never END's (which ends the convoy
        // for everyone) and never the leader's chooser; nothing has left yet until
        // the user confirms.
        composeTestRule
            .onNodeWithText(string(R.string.convoy_barEndConfirmBody))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithTag(CONVOY_BAR_EXIT_CHOICE_END_TAG)
            .assertDoesNotExist()
        assertNull("leaving must wait for the confirmation", left)

        composeTestRule
            .onNodeWithText(string(R.string.convoy_barLeaveConfirmAction))
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals("a member's leave must reach the leave handler", "c1", left)
        assertNull("a member's leave must never end the convoy for everyone", ended)
    }

    // --- the two exits ----------------------------------------------------

    /**
     * The feature: a LEADER with people behind them gets a real CHOICE, and the
     * two options do genuinely different things.
     *
     * Asserted through the observables — which handler each option reaches — so it
     * fails against a chooser that wires both branches to the same action, which
     * is exactly the "one button with a hidden modifier" this replaces.
     */
    @Test
    fun theLeaderIsOfferedBothExits_andEachReachesItsOwnAction() {
        var ended: String? = null
        var left: String? = null
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = leaderOfThreeState("c1"),
                    onEndConvoy = { ended = it },
                    onLeaveConvoy = { left = it },
                )
            }
        }

        // The exit opens the CHOOSER, not either confirmation directly.
        composeTestRule.onNodeWithTag(CONVOY_BAR_LEAVE_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CONVOY_BAR_EXIT_CHOICE_LEAVE_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CONVOY_BAR_EXIT_CHOICE_END_TAG).assertIsDisplayed()
        assertNull("the chooser itself must commit nothing", ended)
        assertNull("the chooser itself must commit nothing", left)

        // Leave → the leave confirmation → the LEAVE handler, and nothing ends.
        composeTestRule.onNodeWithTag(CONVOY_BAR_EXIT_CHOICE_LEAVE_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(string(R.string.convoy_barLeaveConfirmAction))
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals("the leader's Leave must reach the leave handler", "c1", left)
        assertNull("the leader's Leave must not end the convoy for everyone", ended)
    }

    /**
     * The other half of the same choice: "End for everyone" really does reach the
     * group-wide action — and only after its own confirmation, so the destructive
     * option is never one stray tap away.
     */
    @Test
    fun theLeadersEndForEveryoneReachesTheGroupWideAction_afterConfirming() {
        var ended: String? = null
        var left: String? = null
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = leaderOfThreeState("c1"),
                    onEndConvoy = { ended = it },
                    onLeaveConvoy = { left = it },
                )
            }
        }

        composeTestRule.onNodeWithTag(CONVOY_BAR_LEAVE_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CONVOY_BAR_EXIT_CHOICE_END_TAG).performClick()
        composeTestRule.waitForIdle()

        // Picking the destructive option opens ITS confirmation; it has not fired.
        composeTestRule
            .onNodeWithText(string(R.string.convoy_barEndConfirmBody))
            .assertIsDisplayed()
        assertNull("ending must wait for its own confirmation", ended)

        composeTestRule.onNodeWithText(string(R.string.convoy_barEndConfirmAction)).performClick()
        composeTestRule.waitForIdle()
        assertEquals("End for everyone must reach the end handler", "c1", ended)
        assertNull("ending is not also a leave", left)
    }

    /**
     * A NON-leader must never be able to reach "end for everyone" — not through
     * the chooser (which is the leader's alone) and not through any count. This is
     * the abuse case: one annoyed member closing everyone else's convoy mid-drive.
     */
    @Test
    fun aNonLeaderNeverSeesTheEndForEveryoneOption() {
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = memberOfThreeState("c1"),
                    onEndConvoy = {},
                    onLeaveConvoy = {},
                )
            }
        }
        composeTestRule.onNodeWithTag(CONVOY_BAR_LEAVE_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CONVOY_BAR_EXIT_CHOICE_END_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(CONVOY_BAR_EXIT_CHOICE_LEAVE_TAG).assertDoesNotExist()
        composeTestRule
            .onNodeWithText(string(R.string.convoy_barEndConfirmBody))
            .assertDoesNotExist()
    }

    /**
     * The exactly-one-would-remain case for a NON-leader. Leave is still OFFERED —
     * hiding it would trap them, because ending is leader-only and they would then
     * have no exit at all — but the confirmation says out loud that the convoy
     * ends, and the tap still reaches the LEAVE handler rather than the group-wide
     * end (which the server would refuse them anyway).
     */
    @Test
    fun aNonLeaderWhoseExitEndsTheConvoyIsToldSo_andStillOnlyLeaves() {
        var ended: String? = null
        var left: String? = null
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    // Two accepted members: one leaving would leave a single
                    // person alone in a live convoy, so the server ends it.
                    state = memberState("c1"),
                    onEndConvoy = { ended = it },
                    onLeaveConvoy = { left = it },
                )
            }
        }

        composeTestRule.onNodeWithTag(CONVOY_BAR_LEAVE_TAG).performClick()
        composeTestRule.waitForIdle()

        // The wording is the ends-the-convoy one, not "the others keep driving
        // without you" — which would be simply false here.
        composeTestRule
            .onNodeWithText(string(R.string.convoy_barLeaveEndsConfirmBody))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.convoy_barLeaveConfirmBody))
            .assertDoesNotExist()

        composeTestRule
            .onNodeWithText(string(R.string.convoy_barLeaveEndsConfirmAction))
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals("it is still a LEAVE, whatever it does to the convoy", "c1", left)
        assertNull("a non-leader must never reach convoy-end", ended)
    }

    /**
     * A leader whose own exit would end the convoy anyway is not offered a choice
     * between two identical outcomes — they get End, directly.
     */
    @Test
    fun aLeaderWhoseExitEndsTheConvoyGetsEndDirectly_noChooser() {
        var ended: String? = null
        composeTestRule.setContent {
            // ownerState carries two accepted members, so one leaving leaves one.
            KccTheme { ConvoyStatusBar(state = ownerState("c1"), onEndConvoy = { ended = it }) }
        }
        composeTestRule.onNodeWithTag(CONVOY_BAR_LEAVE_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(CONVOY_BAR_EXIT_CHOICE_LEAVE_TAG).assertDoesNotExist()
        composeTestRule
            .onNodeWithText(string(R.string.convoy_barEndConfirmBody))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.convoy_barEndConfirmAction)).performClick()
        composeTestRule.waitForIdle()
        assertEquals("c1", ended)
    }

    /**
     * The invite control's enablement is DERIVED from both halves, not hard-coded:
     * the `Wired` availability flag AND a supplied handler. Neither alone makes the
     * button act. Asserted through the observable — whether a tap reaches
     * `onInvite` — rather than by reading the `enabled` argument back.
     */
    @Test
    fun inviteControl_goesLiveOnlyWithBothAWiredFlagAndAHandler() {
        var invited: String? = null
        val wired = ConvoyBarActionAvailability.Wired
        val missing = ConvoyBarActionAvailability.BackendMissing
        // Wired flag (the default now), but no handler → inert.
        var current by mutableStateOf(ownerState("c1").copy(inviteAvailability = wired))
        var handler: ((String) -> Unit)? by mutableStateOf(null)
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(state = current, onEndConvoy = {}, onInvite = handler)
            }
        }

        // Wired flag, but no handler → still inert.
        composeTestRule.onNodeWithTag(CONVOY_BAR_INVITE_TAG).performClick()
        composeTestRule.waitForIdle()
        assertNull("a wired flag alone must not make the control act", invited)

        // Handler, but the flag forced back to BackendMissing → still inert.
        handler = { invited = it }
        current = ownerState("c1").copy(inviteAvailability = missing)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CONVOY_BAR_INVITE_TAG).performClick()
        composeTestRule.waitForIdle()
        assertNull("a handler alone must not make the control act", invited)

        // Both → live, and it invites THIS convoy.
        current = ownerState("c1").copy(inviteAvailability = wired)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CONVOY_BAR_INVITE_TAG).performClick()
        composeTestRule.waitForIdle()
        assertEquals("c1", invited)
    }

    /**
     * A flag flipped ahead of its handler leaves the controls disabled — so the
     * accessibility labels must keep saying the actions are unavailable, because
     * they still are. A disabled button that announces itself as "Invite more" /
     * "Leave convoy" tells a screen-reader user, who has no visual disabled cue to
     * fall back on, that something is available when it does nothing.
     *
     * The unavailability is carried ONLY by the controls' "…unavailable"
     * contentDescriptions: there is no visible "not available yet" body-text
     * notice line (it was removed as map clutter), so this also asserts the notice
     * string is NOT rendered.
     */
    @Test
    fun aWiredFlagWithoutItsHandler_stillAnnouncesAsUnavailableWithNoVisibleNotice() {
        val wired = ConvoyBarActionAvailability.Wired
        // Both flags flipped, neither handler supplied — the mid-refactor state.
        val state =
            memberState("c1").copy(inviteAvailability = wired, leaveAvailability = wired)
        composeTestRule.setContent {
            KccTheme { ConvoyStatusBar(state = state, onEndConvoy = {}) }
        }

        composeTestRule
            .onNodeWithContentDescription(string(R.string.convoy_barInviteUnavailable))
            .assertExists()
        composeTestRule
            .onNodeWithContentDescription(string(R.string.convoy_barLeaveUnavailable))
            .assertExists()
        // The visible explanation line is gone — accessibility rides on the
        // contentDescriptions above, not a separate paragraph.
        composeTestRule
            .onNodeWithText(string(R.string.convoy_barNoticeInviteAndLeave))
            .assertDoesNotExist()
    }

    // NOTE: disabledLeaveIcon_isNotPaintedFullStrengthDestructiveRed is a pixel-
    // capture test (captureToImage/toPixelMap). It stays on the emulator in
    // ConvoyStatusBarDestructiveColorTest (src/androidTest) because Robolectric's
    // captureToImage path hangs in forceRedraw (no real window draw callback) —
    // the same GPU/rendering reason ConvoyMapAwarenessOverlayTest stays on-device.

    // --- all controls inline (no expand, no popup) --------------------------

    /**
     * The relocation's whole point: every convoy control is now inline in the one
     * always-visible bar — no expand step, no popup. The map-focus toggle, the
     * invite control and the leave/End control are all in the tree at once and
     * reachable directly. (The individual actions' behaviour is asserted in the
     * focused tests above; this one pins that they are all inline together.)
     */
    @Test
    fun allControlsRenderInlineWithoutAnExpandStep() {
        var picked: ConvoyFocusMode? = null
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = memberState("c1"),
                    onEndConvoy = {},
                    onInvite = {},
                    onLeaveConvoy = {},
                    showDestination = false,
                    focusMode = ConvoyFocusMode.Me,
                    onFocusModeChange = { picked = it },
                )
            }
        }

        // All three controls are present inline, no expand required.
        composeTestRule.onNodeWithTag(CONVOY_BAR_FOCUS_TAG).assertExists()
        composeTestRule.onNodeWithTag(CONVOY_BAR_INVITE_TAG).assertExists()
        composeTestRule.onNodeWithTag(CONVOY_BAR_LEAVE_TAG).assertExists()

        // And the focus toggle is genuinely operable inline (the "I can only press
        // the location button" complaint) — its callback fires on a tap.
        composeTestRule.onNodeWithTag(CONVOY_BAR_FOCUS_TAG).performClick()
        composeTestRule.waitForIdle()
        assertEquals(ConvoyFocusMode.Convoy, picked)
    }

    /**
     * The member count starts as a tappable control that opens a member-list
     * popup (previously nothing happened on tap). Tapping it must reveal the
     * popup listing every accepted member by name — here both `twoMembers`.
     */
    @Test
    fun tappingMemberCount_opensMemberListWithEveryMember() {
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = memberState("c1"),
                    onEndConvoy = {},
                    onInvite = {},
                    onLeaveConvoy = {},
                    showDestination = false,
                )
            }
        }

        // Closed to start: no member list on screen.
        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBER_LIST_TAG).assertDoesNotExist()

        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBERS_TAG).performClick()
        composeTestRule.waitForIdle()

        // The popup is up and lists both members by display name.
        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBER_LIST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bob").assertIsDisplayed()
    }

    /**
     * Seb's ask: the "In this convoy" list also shows people who were INVITED but
     * have not joined yet, each as "<name> Waiting to join…". Here Dana is a pending
     * invitee carried alongside the two accepted members, and the popup shows her
     * name plus the waiting status while the count still reads only the two joined.
     */
    @Test
    fun tappingMemberCount_showsPendingInviteesAsWaitingToJoin() {
        val state =
            memberState("c1").copy(
                pendingInvitees =
                    listOf(ConvoyBarMember(uid = "u9", displayName = "Dana", avatarPath = null)),
            )
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = state,
                    onEndConvoy = {},
                    onInvite = {},
                    onLeaveConvoy = {},
                    showDestination = false,
                )
            }
        }

        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBERS_TAG).performClick()
        composeTestRule.waitForIdle()

        // Joined members and the waiting invitee are all listed...
        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bob").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dana").assertIsDisplayed()
        // ...the pending invitee carrying the localized "waiting to join" status.
        composeTestRule
            .onNodeWithText(string(R.string.convoy_barMemberWaiting))
            .assertIsDisplayed()
    }

    /**
     * Requirement 2: tapping a JOINED member opens the actions sheet with Profile
     * and Go to location. Alice IS sharing a position ([memberLocations]), so Go to
     * location is enabled and, tapped, reaches the host with her uid.
     */
    @Test
    fun tappingJoinedMember_opensActionsSheet_andWiresProfileAndLocation() {
        var profileUid: String? = null
        var locationUid: String? = null
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = memberState("c1"),
                    onEndConvoy = {},
                    onInvite = {},
                    onLeaveConvoy = {},
                    showDestination = false,
                    onOpenMemberProfile = { profileUid = it },
                    onGoToMemberLocation = { locationUid = it },
                    memberLocations = setOf("u1"),
                )
            }
        }

        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBERS_TAG).performClick()
        composeTestRule.waitForIdle()
        // Tap Alice's row → her actions sheet.
        composeTestRule.onNodeWithText("Alice").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBER_ACTIONS_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBER_PROFILE_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBER_LOCATION_TAG).performClick()
        composeTestRule.waitForIdle()

        assertEquals("Go to location reaches the host with the member's uid", "u1", locationUid)
        assertNull("only the location action was tapped", profileUid)
    }

    /**
     * Go to location is present but DISABLED (not silently missing) for a joined
     * member who is not sharing a position right now — Bob is not in
     * [memberLocations], so tapping it does nothing.
     */
    @Test
    fun goToLocation_isDisabled_forAMemberWithNoSharedPosition() {
        var locationUid: String? = null
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = memberState("c1"),
                    onEndConvoy = {},
                    onLeaveConvoy = {},
                    showDestination = false,
                    onOpenMemberProfile = {},
                    onGoToMemberLocation = { locationUid = it },
                    // Nobody sharing a position.
                    memberLocations = emptySet(),
                )
            }
        }

        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBERS_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Bob").performClick()
        composeTestRule.waitForIdle()

        // The action is shown (discoverable) with its unavailable note...
        composeTestRule
            .onNodeWithText(string(R.string.convoy_barMemberLocationUnavailable))
            .assertIsDisplayed()
        // ...but disabled: a click does not reach the host.
        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBER_LOCATION_TAG).performClick()
        composeTestRule.waitForIdle()
        assertNull("a disabled Go to location must not fire", locationUid)
    }

    /**
     * A PENDING invitee ("Waiting to join…") has no actions — their row is not
     * tappable, so no sheet appears. (Only joined members carry a location/profile
     * action in the convoy.)
     */
    @Test
    fun tappingPendingInvitee_doesNotOpenActionsSheet() {
        val state =
            memberState("c1").copy(
                pendingInvitees =
                    listOf(ConvoyBarMember(uid = "u9", displayName = "Dana", avatarPath = null)),
            )
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = state,
                    onEndConvoy = {},
                    onLeaveConvoy = {},
                    showDestination = false,
                    onOpenMemberProfile = {},
                    onGoToMemberLocation = {},
                    memberLocations = setOf("u9"),
                )
            }
        }

        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBERS_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Dana").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBER_ACTIONS_TAG).assertDoesNotExist()
    }

    /**
     * Requirement 3 (avatar): a member WITHOUT a set picture falls back to the
     * neutral placeholder — the row still renders by name and opening its actions
     * sheet works, i.e. the placeholder path does not break the list. (The real
     * image path reuses the app's established [rememberStorageImageUrl] + AsyncImage
     * loader, exercised by the member-detail avatar tests.)
     */
    @Test
    fun memberWithoutPicture_rendersPlaceholderRow_thatStillOpensActions() {
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state =
                        memberState("c1").copy(
                            members =
                                listOf(
                                    ConvoyBarMember(uid = "u1", displayName = "Alice", avatarPath = null),
                                ),
                        ),
                    onEndConvoy = {},
                    onLeaveConvoy = {},
                    showDestination = false,
                    onOpenMemberProfile = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBERS_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alice").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBER_ACTIONS_TAG).assertIsDisplayed()
    }

    /**
     * The open actions sheet renders from the LIVE roster (by uid), not a captured
     * snapshot: a display name that resolves while the sheet is open updates it.
     */
    @Test
    fun openActionsSheet_reflectsLiveNameChange() {
        var current by mutableStateOf(memberState("c1"))
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = current,
                    onEndConvoy = {},
                    onLeaveConvoy = {},
                    showDestination = false,
                    onOpenMemberProfile = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBERS_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Alice").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBER_ACTIONS_TAG).assertIsDisplayed()

        // Alice's profile resolves to a fuller name while the sheet is open.
        current =
            current.copy(
                members =
                    listOf(
                        ConvoyBarMember(uid = "u1", displayName = "Alicia Svensson", avatarPath = null),
                        ConvoyBarMember(uid = "u2", displayName = "Bob", avatarPath = null),
                    ),
            )
        composeTestRule.waitForIdle()

        // The open sheet shows the updated name, not the captured "Alice".
        composeTestRule.onNodeWithText("Alicia Svensson").assertIsDisplayed()
    }

    /**
     * The member-list popup is a passive info surface, so — unlike the destructive
     * end/leave dialogs — it must NOT linger across a convoy switch showing the new
     * convoy's roster under the count the user tapped. Its visibility flag is keyed
     * to the convoy id, so a background refresh that swaps the bar to a different
     * convoy closes the popup.
     */
    @Test
    fun switchingConvoyUnderTheOpenMemberList_closesIt() {
        var current by mutableStateOf(memberState("c1"))
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = current,
                    onEndConvoy = {},
                    onInvite = {},
                    onLeaveConvoy = {},
                    showDestination = false,
                )
            }
        }

        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBERS_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBER_LIST_TAG).assertIsDisplayed()

        // A refresh swaps the bar to a different convoy under the open popup.
        current = memberState("c2")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(CONVOY_BAR_MEMBER_LIST_TAG).assertDoesNotExist()
    }

    /**
     * The tappable count keeps the full "%d in convoy" phrase as its
     * contentDescription (accessibility) even though only the bare number is
     * shown, so TalkBack still announces the whole thing.
     */
    @Test
    fun memberCount_keepsFullPhraseAsContentDescription() {
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = memberState("c1"),
                    onEndConvoy = {},
                    onInvite = {},
                    onLeaveConvoy = {},
                    showDestination = false,
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription(string(R.string.convoy_barMembers, twoMembers.size))
            .assertIsDisplayed()
    }

    // --- convoy chat control + unread badge ---------------------------------

    /**
     * Tapping the chat icon must open THIS convoy's chat — the id the bar is
     * describing, not a bare "open the chat hub" — because the hub lands on the
     * convoy the handler names.
     */
    @Test
    fun tappingTheChatIcon_opensThisConvoysChat() {
        var opened: String? = null
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = memberState("c1"),
                    onEndConvoy = {},
                    onOpenChat = { opened = it },
                    showDestination = false,
                )
            }
        }

        composeTestRule.onNodeWithTag(CONVOY_BAR_CHAT_TAG).performClick()
        composeTestRule.waitForIdle()

        assertEquals("c1", opened)
    }

    /**
     * The chat control is OMITTED, not disabled, without a handler — a chat icon
     * has no honest disabled meaning, and a greyed-out one carrying an unread
     * badge would announce messages it refuses to open.
     */
    @Test
    fun theChatIcon_isAbsentEntirelyWithoutAHandler() {
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = memberState("c1").copy(unreadChatCount = 3),
                    onEndConvoy = {},
                    showDestination = false,
                )
            }
        }

        composeTestRule.onNodeWithTag(CONVOY_BAR_CHAT_TAG).assertDoesNotExist()
    }

    /**
     * The one that matters visually: a caught-up member must see NO badge, not a
     * "0". Asserted through the RENDERED text, so a badge that draws "0" fails —
     * which reading the count back off the state would not catch. The label has
     * to lose the number too, or TalkBack announces "0 unread messages" on a chat
     * with nothing new in it.
     */
    @Test
    fun theUnreadBadge_isAbsentAtZeroAndNeverPrintsAZero() {
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = memberState("c1").copy(unreadChatCount = 0),
                    onEndConvoy = {},
                    onOpenChat = {},
                    showDestination = false,
                )
            }
        }

        // The control is there...
        composeTestRule.onNodeWithTag(CONVOY_BAR_CHAT_TAG).assertExists()
        // ...but nothing is badged on it.
        composeTestRule.onNodeWithText("0").assertDoesNotExist()
        composeTestRule
            .onNodeWithContentDescription(string(R.string.convoy_barChat))
            .assertExists()
    }

    /** A real count is drawn as-is, and announced with the number in it. */
    @Test
    fun theUnreadBadge_showsTheCountAndAnnouncesIt() {
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = memberState("c1").copy(unreadChatCount = 3),
                    onEndConvoy = {},
                    onOpenChat = {},
                    showDestination = false,
                )
            }
        }

        composeTestRule.onNodeWithText("3").assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(string(R.string.convoy_barChatUnread, 3))
            .assertExists()
    }

    /**
     * Past the cap the badge saturates instead of growing — the bar is one
     * compact row shared with four other controls, and a widening badge would
     * push them off it. The announcement saturates with it ("more than 9"), so a
     * screen-reader user is never told an exact number the badge is not claiming.
     */
    @Test
    fun theUnreadBadge_capsItsWidthAndItsAnnouncementOnABusyConvoy() {
        composeTestRule.setContent {
            KccTheme {
                ConvoyStatusBar(
                    state = memberState("c1").copy(unreadChatCount = 250),
                    onEndConvoy = {},
                    onOpenChat = {},
                    showDestination = false,
                )
            }
        }

        composeTestRule.onNodeWithText("250").assertDoesNotExist()
        composeTestRule.onNodeWithText("${ConvoyBar.UNREAD_DISPLAY_MAX}+").assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(
                string(R.string.convoy_barChatUnreadMany, ConvoyBar.UNREAD_DISPLAY_MAX),
            )
            .assertExists()
    }
}
