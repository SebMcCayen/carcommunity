package com.kungsbackacarcommunity.app.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.map.ConvoyFocusMode
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.navigation.LatLng

/** Test tag on the whole convoy status bar. */
const val CONVOY_BAR_TEST_TAG = "convoy_bar"

/** Test tag on the tappable member-count control (people icon + number). */
const val CONVOY_BAR_MEMBERS_TAG = "convoy_bar_members"

/** Test tag on the member-list popup opened by tapping the member count. */
const val CONVOY_BAR_MEMBER_LIST_TAG = "convoy_bar_member_list"

/** Test tag on the member-actions sheet raised by tapping a joined member row. */
const val CONVOY_BAR_MEMBER_ACTIONS_TAG = "convoy_bar_member_actions"

/** Test tag on the member-actions "Profile" action. */
const val CONVOY_BAR_MEMBER_PROFILE_TAG = "convoy_bar_member_profile"

/** Test tag on the member-actions "Go to location" action. */
const val CONVOY_BAR_MEMBER_LOCATION_TAG = "convoy_bar_member_location"

/** Test tag on the bar's map-focus toggle (me vs the whole convoy). */
const val CONVOY_BAR_FOCUS_TAG = "convoy_bar_focus"

/** Test tag on the bar's convoy-chat control (speech bubble + unread badge). */
const val CONVOY_BAR_CHAT_TAG = "convoy_bar_chat"

/** Test tag on the bar's invite ("person +") control. */
const val CONVOY_BAR_INVITE_TAG = "convoy_bar_invite"

/** Test tag on the bar's exit control (leave / end / the leader's chooser). */
const val CONVOY_BAR_LEAVE_TAG = "convoy_bar_leave"

/** Test tag on the "Leave the convoy" option inside the leader's exit chooser. */
const val CONVOY_BAR_EXIT_CHOICE_LEAVE_TAG = "convoy_bar_exit_choice_leave"

/** Test tag on the "End for everyone" option inside the leader's exit chooser. */
const val CONVOY_BAR_EXIT_CHOICE_END_TAG = "convoy_bar_exit_choice_end"

/** Test tag on the bar's set/change shared-destination control. */
const val CONVOY_BAR_DESTINATION_SET_TAG = "convoy_bar_destination_set"

/** Test tag on the bar's clear shared-destination control. */
const val CONVOY_BAR_DESTINATION_CLEAR_TAG = "convoy_bar_destination_clear"

/** Test tag on the bar's "start navigation to the shared destination" control. */
const val CONVOY_BAR_DESTINATION_NAVIGATE_TAG = "convoy_bar_destination_navigate"

/**
 * A compact, full-width bar describing the convoy the user is currently in: how
 * many people are in it, a map-focus toggle, an invite affordance, and the EXIT
 * (leave, or — for the leader — leave-or-end) — ALL visible inline, no expand
 * step.
 *
 * Rendered by the map home (wedged full-width into the search row between the
 * search control and the profile avatar) and by turn-by-turn navigation through a
 * slot, so there is ONE convoy bar with one set of rules rather than a map version
 * and a navigation version that can drift apart. [state] being null is the
 * caller's signal not to compose it at all — the host omits the slot entirely when
 * [ConvoyBar.stateFor] returns null, so "not in a convoy" renders nothing
 * whatsoever (no empty bar, no placeholder).
 *
 * ## Every control is inline and live
 * There is no expand/collapse and no popup: the member count, focus toggle, chat,
 * invite and the exit all sit in the one always-visible row. All of the actions
 * are backed by deployed callables (see [ConvoyBar]); each still gates on BOTH an
 * availability flag AND the presence of a handler, so a control never looks live
 * while doing nothing. Space is kept by using icon buttons (no text labels) for
 * the controls, with the member count carrying the only text — and the chat
 * icon's unread badge is capped ("9+") for the same reason, so no amount of
 * chatter can widen the row.
 *
 * ## The two exits
 * The trailing control is deliberately NOT one action with two labels. What it
 * offers is [ConvoyBarState.exitChoice], and there are three shapes:
 *
 *  - **A leader with people to leave behind** taps into a CHOOSER offering two
 *    separate actions: "Leave the convoy" (the others carry on; leadership
 *    transfers) and "End for everyone" (the whole drive stops). The chooser has no
 *    affirmative default button, and the destructive option is a text action in
 *    the error colour next to the leave action's filled button, so ending cannot
 *    be the thing a stray tap lands on.
 *  - **A leader whose exit would end it anyway** gets End alone — offering both
 *    would be two buttons for one outcome.
 *  - **A non-leader** gets Leave alone, because `convoy-end` is leader-only. When
 *    their exit would take the convoy below the survival threshold the Leave
 *    confirmation says the convoy will end; it is never HIDDEN, because a member
 *    who can neither leave nor end would be trapped in the convoy.
 *
 * Each path confirms before it fires, and a non-leader's tap is routed on the
 * exit choice, so it reaches [onLeaveConvoy] and can never fall through to the
 * group-wide end-convoy confirmation: "leave" ending everyone's drive is the one
 * failure in this component that would truly hurt people, so it is prevented
 * structurally, not by the availability flag.
 *
 * Styled from the same frosted, rounded, tonally-elevated `surface` language as
 * the map's search bar and floating controls, so it reads as one more piece of
 * the map chrome rather than a foreign banner.
 *
 * @param compact tightens the padding and hides the map-focus toggle — used in
 *   turn-by-turn navigation, where vertical space next to safety-critical
 *   maneuver instructions is at a premium and the navigation SDK owns the camera.
 * @param showDestination whether to render the shared-destination row (and the
 *   "destination changed while navigating" banner) below the control row. The map
 *   home's inline placement sits in a single-line band between the search control
 *   and the avatar, so it passes `false` — a second text row would not fit that
 *   band. Turn-by-turn navigation, which has the vertical room, keeps it `true`.
 * @param onEndConvoy invoked (after the user confirms) when the LEADER ends the
 *   convoy for everyone. Never invoked for a member who is not the leader.
 * @param onInvite invites people into THIS convoy (by id) — the host opens the
 *   friend picker and calls `convoy-invite`. The invite control needs both this
 *   handler AND [ConvoyBarState.inviteAvailability] `== Wired` before it enables.
 * @param onOpenChat opens THIS convoy's chat channel (by id). Null omits the chat
 *   control entirely rather than rendering a dead one — the same "a control never
 *   looks live while doing nothing" rule the other actions follow, expressed as
 *   absence because a chat icon has no honest disabled meaning. The unread badge
 *   on it comes from [ConvoyBarState.unreadChatCount] and is hidden at zero.
 * @param onLeaveConvoy removes the CALLER from this convoy. Any accepted member's
 *   action, the LEADER included (leadership transfers server-side). Invoked only
 *   after the leave confirmation. A non-leader's tap is routed here by
 *   [ConvoyBarState.exitChoice], so it can never reach the end-convoy
 *   confirmation no matter what [ConvoyBarState.leaveAvailability] says.
 * @param focusMode what the map camera is currently framing. Defaults to
 *   [ConvoyFocusMode.Me]. @param onFocusModeChange invoked with the picked mode.
 * @param onSetDestination open the place picker to set (or replace) the convoy's
 *   SHARED destination. @param onClearDestination clear it (setter or owner only).
 * @param onNavigateToDestination start turn-by-turn to the shared destination.
 * @param navigationEvent what just happened to the destination the viewer is
 *   already navigating to; drives the dismissible banner.
 * @param onDismissNavigationEvent acknowledge that banner.
 * @param onOpenMemberProfile open a JOINED member's profile (their uid) from the
 *   member-list's per-member actions — the same read-only member profile the
 *   friends/chat rows open. Null omits the Profile action.
 * @param onGoToMemberLocation centre the convoy map on a JOINED member's current
 *   shared position (their uid). Null omits the action (e.g. the turn-by-turn
 *   variant, whose camera the Navigation SDK owns).
 * @param memberLocations the uids that currently HAVE a shared position, so "Go to
 *   location" is offered live and disabled for a member not sharing one right now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvoyStatusBar(
    state: ConvoyBarState,
    onEndConvoy: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showDestination: Boolean = true,
    onInvite: ((String) -> Unit)? = null,
    onOpenChat: ((String) -> Unit)? = null,
    onLeaveConvoy: ((String) -> Unit)? = null,
    focusMode: ConvoyFocusMode = ConvoyFocusMode.Me,
    onFocusModeChange: (ConvoyFocusMode) -> Unit = {},
    onSetDestination: (() -> Unit)? = null,
    onClearDestination: ((String) -> Unit)? = null,
    onNavigateToDestination: ((LatLng, String) -> Unit)? = null,
    navigationEvent: ConvoyDestinationNavigationEvent =
        ConvoyDestinationNavigationEvent.Unchanged,
    onDismissNavigationEvent: () -> Unit = {},
    onOpenMemberProfile: ((String) -> Unit)? = null,
    onGoToMemberLocation: ((String) -> Unit)? = null,
    memberLocations: Set<String> = emptySet(),
) {
    // The convoy each open confirm dialog is ABOUT, captured when the user opened
    // it — not a bare boolean. [state] is hoisted and refreshes underneath this
    // composable (the coordinator re-fetches the convoy list after every mutation,
    // and `activeConvoy` re-picks which convoy the bar describes), so a boolean
    // flag plus a `state.convoyId` read at confirm time would act on WHICHEVER
    // convoy the bar happened to be showing at the moment of the tap — not the one
    // named in the dialog the user was answering.
    //
    // Deliberately NOT `remember(state.convoyId)`: keying the flag would make the
    // dialog silently disappear under a background refresh, cancelling a
    // considered destructive decision without a word.
    var pendingEndConvoyId by remember { mutableStateOf<String?>(null) }
    var pendingLeaveConvoyId by remember { mutableStateOf<String?>(null) }

    // Whether the LEADER's two-option chooser is up. A bare boolean rather than a
    // captured id, unlike the two confirmations above, because it does not itself
    // commit anything — picking an option opens the matching confirmation, which
    // captures the id at that point.
    var showExitChoice by remember { mutableStateOf(false) }

    // Whether the member-list popup (opened by tapping the member count) is up.
    //
    // Keyed to the convoy id — UNLIKE the destructive end/leave dialogs above,
    // which deliberately capture their id and stay open under a refresh so a
    // considered decision is never silently cancelled. This is a passive info
    // popup with no decision to protect: if the bar switches to a DIFFERENT convoy
    // while it is open (the coordinator re-fetches and `activeConvoy` re-picks),
    // leaving it open would silently swap it to the new convoy's roster under the
    // count the user tapped. Keying it resets the flag to false on that switch, so
    // the popup closes rather than misrepresenting which convoy it is listing.
    var showMembers by remember(state.convoyId) { mutableStateOf(false) }

    // The JOINED member whose actions sheet ("Profile" / "Go to location") is open,
    // or null. Captured when a member row is tapped, and — like the member-list
    // popup — keyed to the convoy so a background refresh that swaps the bar to a
    // different convoy dismisses it rather than acting on a member of the old one.
    var memberActionsFor by remember(state.convoyId) { mutableStateOf<ConvoyBarMember?>(null) }

    // Whether the "set destination" tap is currently waiting on the overwrite
    // confirmation (only raised when the current destination was set by someone
    // else — see ConvoyDestinations.requiresOverwriteConfirmation).
    var confirmOverwrite by remember { mutableStateOf(false) }

    // Whether each action is actually USABLE, which needs both halves: the state
    // flag says a callable exists, the handler says this host has wired it up.
    // Both downstream decisions — enablement and the accessibility label — read
    // these two booleans and nothing else, so the control cannot end up disabled
    // while announcing itself as available, or enabled while its "…unavailable"
    // label still says the feature is missing. `state.busy` is deliberately NOT
    // part of them: it is a transient in-flight state, so it must disable the
    // buttons without rewriting their labels.
    val inviteUsable =
        state.inviteAvailability == ConvoyBarActionAvailability.Wired && onInvite != null
    val leaveUsable =
        state.leaveAvailability == ConvoyBarActionAvailability.Wired &&
            // `convoy-end`'s handler is non-null by signature, so the ONE case
            // that can still be unusable is a leave with no handler wired. That
            // now includes the LEADER — they can leave too — except when their
            // only offer IS the end (EndOnly), which needs no leave handler.
            (state.exitChoice == ConvoyExitChoice.EndOnly || onLeaveConvoy != null)

    Surface(
        modifier = modifier.fillMaxWidth().testTag(CONVOY_BAR_TEST_TAG),
        shape = RoundedCornerShape(KccRadius.full),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier =
                Modifier.padding(
                    start = KccSpacing.s4,
                    end = KccSpacing.s2,
                    top = if (compact) KccSpacing.s1 else KccSpacing.s0,
                    bottom = if (compact) KccSpacing.s1 else KccSpacing.s0,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KccSpacing.s1),
            ) {
                // Member count: people glyph + the bare NUMBER (no trailing
                // "in convoy" text — that would only ever truncate to "2 i…" in
                // the width this bar gets). The full "%d in convoy" phrase is kept
                // as the control's contentDescription so TalkBack still announces
                // it in full. Tapping opens the member-list popup below.
                //
                // The whole control merges to ONE node reading that description +
                // a Button role + the open action: the glyph is decorative and the
                // number's own semantics are cleared, so nothing double-announces.
                val membersLabel = stringResource(R.string.convoy_barMembers, state.memberCount)
                Box(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s1),
                        modifier =
                            Modifier
                                .testTag(CONVOY_BAR_MEMBERS_TAG)
                                .clickable(role = Role.Button) { showMembers = true }
                                .semantics(mergeDescendants = true) {
                                    contentDescription = membersLabel
                                    role = Role.Button
                                },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Group,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = state.memberCount.toString(),
                            modifier = Modifier.clearAndSetSemantics {},
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (showMembers) {
                        // Joined members get a per-member actions sheet when the
                        // host wired at least one action; pending invitees never do
                        // (they have no live location, and no in-convoy identity to
                        // act on yet). Passing null disables the row tap entirely.
                        val onMemberSelected: ((ConvoyBarMember) -> Unit)? =
                            if (onOpenMemberProfile != null || onGoToMemberLocation != null) {
                                { member ->
                                    showMembers = false
                                    memberActionsFor = member
                                }
                            } else {
                                null
                            }
                        ConvoyMemberListPopup(
                            entries = state.memberListEntries,
                            onDismiss = { showMembers = false },
                            onMemberSelected = onMemberSelected,
                        )
                    }
                }

                // Map focus: follow ME (the default) or keep the WHOLE convoy
                // framed. One button rather than a segmented control — a two-state
                // choice in a bar that has to survive being squeezed into the
                // navigation chrome. The icon shows the mode in effect and is
                // tinted when convoy framing is on, so an unusual camera state is
                // never silent. Hidden in the [compact] (turn-by-turn) variant on
                // purpose: the navigation map's camera is owned by the Navigation
                // SDK, so the choice would have nothing to act on there.
                val convoyFocused = focusMode == ConvoyFocusMode.Convoy
                if (!compact) {
                    IconButton(
                        onClick = {
                            onFocusModeChange(
                                if (convoyFocused) ConvoyFocusMode.Me else ConvoyFocusMode.Convoy,
                            )
                        },
                        modifier = Modifier.testTag(CONVOY_BAR_FOCUS_TAG),
                    ) {
                        Icon(
                            imageVector =
                                if (convoyFocused) {
                                    Icons.Filled.Groups
                                } else {
                                    Icons.Filled.CenterFocusStrong
                                },
                            contentDescription =
                                stringResource(
                                    if (convoyFocused) {
                                        R.string.convoy_barFocusConvoy
                                    } else {
                                        R.string.convoy_barFocusMe
                                    },
                                ),
                            tint =
                                if (convoyFocused) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }

                // Convoy CHAT: opens THIS convoy's channel, badged with how many
                // messages the viewer has not read in it.
                //
                // Omitted rather than disabled when the host wires no handler —
                // the other controls have an honest disabled story ("…unavailable"
                // for a missing callable), a chat icon does not: it would either
                // lie about there being a chat or sit greyed out next to a badge
                // announcing unread messages it refuses to show.
                //
                // The badge is a NULL-or-label, never a "0": `unreadBadgeLabel`
                // returns null at zero so nothing is drawn at all, and caps the
                // printed number ("9+") so a long-running convoy cannot widen this
                // control and squeeze the rest of the row off the bar. The count in
                // the accessibility label is capped the same way, so what TalkBack
                // announces and what is drawn can't disagree.
                //
                // Deliberately a Box with the badge aligned INSIDE the button's
                // footprint, not the [BadgedBox] the map home's floating chat
                // bubble uses. BadgedBox measures itself as its anchor and then
                // places the badge at a NEGATIVE offset — outside its own bounds,
                // above and past the anchor's end. That is fine for a control
                // floating over the map, but this bar is a `Surface` clipped to a
                // rounded shape whose non-compact vertical padding is `s0`, so the
                // bar is exactly one icon-button tall: an overflowing badge would
                // be shaved off at the top and would lean into the invite button
                // beside it. The 48dp button has 12dp of slack around its 24dp
                // glyph, which is room enough to sit the badge in the corner and
                // stay inside the bar.
                if (onOpenChat != null) {
                    val unreadLabel = ConvoyBar.unreadBadgeLabel(state.unreadChatCount)
                    val chatDescription =
                        when {
                            unreadLabel == null -> stringResource(R.string.convoy_barChat)
                            state.unreadChatCount > ConvoyBar.UNREAD_DISPLAY_MAX ->
                                stringResource(
                                    R.string.convoy_barChatUnreadMany,
                                    ConvoyBar.UNREAD_DISPLAY_MAX,
                                )
                            else ->
                                stringResource(
                                    R.string.convoy_barChatUnread,
                                    state.unreadChatCount,
                                )
                        }
                    Box(
                        // Merged into ONE node so the control reads as a single
                        // thing (button, label, action) instead of a button sitting
                        // beside a loose number — the same treatment the member
                        // count and the map home's chat bubble get.
                        modifier =
                            Modifier
                                .testTag(CONVOY_BAR_CHAT_TAG)
                                .semantics(mergeDescendants = true) {},
                    ) {
                        IconButton(onClick = { onOpenChat(state.convoyId) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Chat,
                                contentDescription = chatDescription,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (unreadLabel != null) {
                            Badge(
                                modifier =
                                    Modifier.align(Alignment.TopEnd).padding(KccSpacing.s1),
                            ) {
                                Text(unreadLabel)
                            }
                        }
                    }
                }

                // Invite ("person +"). Opens the friend picker (see the host) and
                // grows THIS convoy via `convoy-invite`. Enablement is DERIVED from
                // the state and the presence of a handler, never hard-coded, so a
                // flag flip alone cannot produce an enabled button that does nothing.
                IconButton(
                    onClick = { onInvite?.invoke(state.convoyId) },
                    enabled = inviteUsable && !state.busy,
                    modifier = Modifier.testTag(CONVOY_BAR_INVITE_TAG),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PersonAdd,
                        contentDescription =
                            stringResource(
                                if (inviteUsable) {
                                    R.string.convoy_barInvite
                                } else {
                                    R.string.convoy_barInviteUnavailable
                                },
                            ),
                    )
                }

                // THE EXIT. What this ONE control offers is [ConvoyExitChoice]'s
                // decision, taken from ownership AND how many members would be left
                // behind — never from the availability flag. Routing on the choice
                // is what keeps a non-leader's tap from ever falling through into
                // "end the convoy for everyone": their branches only ever open the
                // LEAVE confirmation, whose confirm calls [onLeaveConvoy].
                //
                // A LEADER with enough people behind them gets a real CHOICE (the
                // chooser below): two separate, separately-labelled actions rather
                // than one button with a hidden modifier. Everyone else gets the
                // single action they are actually allowed to take, worded to match
                // what it will really do.
                val leaveEnabled = leaveUsable && !state.busy
                val exitChoice = state.exitChoice
                IconButton(
                    onClick = {
                        when (exitChoice) {
                            ConvoyExitChoice.LeaveOrEnd -> showExitChoice = true
                            ConvoyExitChoice.EndOnly -> pendingEndConvoyId = state.convoyId
                            ConvoyExitChoice.LeaveOnly,
                            ConvoyExitChoice.LeaveEndsConvoy,
                            -> pendingLeaveConvoyId = state.convoyId
                        }
                    },
                    enabled = leaveEnabled,
                    modifier = Modifier.testTag(CONVOY_BAR_LEAVE_TAG),
                    // Destructive red, but only while the control can actually do
                    // something. Handing the colour to `iconButtonColors` rather
                    // than hard-tinting the Icon lets Material apply its own
                    // disabled treatment (the derived low-opacity
                    // `disabledContentColor`) — a hard `tint = error` bypasses that
                    // and paints a DISABLED icon in full-strength destructive red,
                    // which reads as tappable.
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Icon(
                        imageVector =
                            // The stop glyph means "this can end the convoy", so it
                            // is shown exactly when the tap can do that — including
                            // the chooser, where ending is one of the two offers.
                            // Plain "leave" is the only case that gets the door.
                            if (exitChoice == ConvoyExitChoice.LeaveOnly) {
                                Icons.AutoMirrored.Filled.Logout
                            } else {
                                Icons.Filled.StopCircle
                            },
                        contentDescription =
                            stringResource(
                                when {
                                    !leaveUsable -> R.string.convoy_barLeaveUnavailable
                                    exitChoice == ConvoyExitChoice.LeaveOrEnd ->
                                        R.string.convoy_barExit
                                    exitChoice == ConvoyExitChoice.EndOnly ->
                                        R.string.convoy_barEnd
                                    exitChoice == ConvoyExitChoice.LeaveEndsConvoy ->
                                        R.string.convoy_barLeaveEnds
                                    else -> R.string.convoy_barLeave
                                },
                            ),
                        // No explicit tint: LocalContentColor carries whichever of
                        // the IconButton's enabled/disabled content colours applies.
                    )
                }
            }

            // The SHARED destination row — where the whole convoy is heading — plus
            // the "changed while navigating" banner. Rendered only where there is
            // room for a second text row (turn-by-turn nav), never in the map home's
            // single-line search-row band (showDestination = false there).
            if (showDestination) {
                ConvoyDestinationRow(
                    state = state,
                    // Wrapped only when the host actually supplied a handler: `?.let`
                    // keeps null NULL, so the row's usability gate still sees "no
                    // handler" rather than a wrapper that would swallow the tap.
                    onSetDestination =
                        onSetDestination?.let { set ->
                            {
                                // Replacing somebody ELSE's destination changes where
                                // the whole group is heading, so it confirms first.
                                // Replacing your own does not.
                                if (state.destinationState is ConvoyDestinationState.SetByOther) {
                                    confirmOverwrite = true
                                } else {
                                    set()
                                }
                            }
                        },
                    onClearDestination =
                        onClearDestination?.let { clear -> { clear(state.convoyId) } },
                    onNavigateToDestination = onNavigateToDestination,
                )

                // What happened to the destination the viewer is CURRENTLY driving
                // to. Deliberately a message, never an interruption.
                ConvoyDestinationNavigationBanner(
                    event = navigationEvent,
                    onDismiss = onDismissNavigationEvent,
                    onNavigateToNew = onNavigateToDestination,
                )
            }
        }
    }

    // The per-member actions sheet ("Profile" / "Go to location"), raised by
    // tapping a JOINED member row in the list popup. Hosted here at the bar's top
    // level — not inside the list Popup — so it is the app's ordinary bottom sheet
    // (matching the place/message action sheets) rather than a fragile popup nested
    // in a focusable popup.
    val memberActions = memberActionsFor
    if (memberActions != null) {
        ConvoyMemberActionsSheet(
            member = memberActions,
            hasLocation = memberActions.uid in memberLocations,
            onOpenProfile =
                onOpenMemberProfile?.let { open ->
                    {
                        memberActionsFor = null
                        open(memberActions.uid)
                    }
                },
            onGoToLocation =
                onGoToMemberLocation?.let { go ->
                    {
                        memberActionsFor = null
                        go(memberActions.uid)
                    }
                },
            onDismiss = { memberActionsFor = null },
        )
    }

    // Destructive and group-wide: ending is never one tap. The body says out loud
    // that this is not "leave", so an owner looking for a way out for THEMSELVES
    // finds out before they end everyone's drive. The id is read out of the pending
    // state, NOT out of [state], so the convoy the user is answering about cannot
    // change between opening this dialog and confirming it.
    val confirmingEndConvoyId = pendingEndConvoyId
    if (confirmingEndConvoyId != null) {
        AlertDialog(
            onDismissRequest = { pendingEndConvoyId = null },
            title = { Text(stringResource(R.string.convoy_barEndConfirmTitle)) },
            text = { Text(stringResource(R.string.convoy_barEndConfirmBody)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingEndConvoyId = null
                        onEndConvoy(confirmingEndConvoyId)
                    },
                ) {
                    Text(stringResource(R.string.convoy_barEndConfirmAction))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingEndConvoyId = null }) {
                    Text(stringResource(R.string.convoy_barConfirmCancel))
                }
            },
        )
    }

    // Leaving removes only the caller — but it is still a deliberate exit, so it
    // confirms too. Same captured-id discipline as the end dialog above, so a
    // background refresh cannot re-point which convoy is left.
    //
    // The BODY is the [ConvoyExitChoice]'s, not a constant: for a member whose
    // exit takes the convoy below the survival threshold, "the others keep driving
    // without you" is simply false — the convoy ends. Saying so is what stops a
    // member being surprised, and it is why "Leave" is still offered here at all
    // rather than hidden: hiding it would leave a non-leader with no exit.
    val confirmingLeaveConvoyId = pendingLeaveConvoyId
    if (confirmingLeaveConvoyId != null) {
        val leaveEnds = state.exitChoice == ConvoyExitChoice.LeaveEndsConvoy
        AlertDialog(
            onDismissRequest = { pendingLeaveConvoyId = null },
            title = {
                Text(
                    stringResource(
                        if (leaveEnds) {
                            R.string.convoy_barLeaveEndsConfirmTitle
                        } else {
                            R.string.convoy_barLeaveConfirmTitle
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (leaveEnds) {
                            R.string.convoy_barLeaveEndsConfirmBody
                        } else {
                            R.string.convoy_barLeaveConfirmBody
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingLeaveConvoyId = null
                        onLeaveConvoy?.invoke(confirmingLeaveConvoyId)
                    },
                ) {
                    Text(
                        stringResource(
                            if (leaveEnds) {
                                R.string.convoy_barLeaveEndsConfirmAction
                            } else {
                                R.string.convoy_barLeaveConfirmAction
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLeaveConvoyId = null }) {
                    Text(stringResource(R.string.convoy_barConfirmCancel))
                }
            },
        )
    }

    // THE LEADER'S CHOICE. Two genuinely different actions, presented as two
    // separate controls with their own explanation of what each does — never one
    // button with a modifier, and never a plain yes/no where "yes" could mean
    // either.
    //
    // Deliberately NO affirmative `confirmButton`: both options live in the body
    // and the only dialog-level action is Cancel. That is what keeps the
    // destructive option from becoming the accidental default — there is no
    // default, no button the Enter key or a stray tap lands on, and the two are
    // visually distinct (Leave is a normal filled action, End for everyone is a
    // text action in the error colour). Ending is one deliberate tap inside a
    // dialog that already spells out how many people it stops, which is the same
    // two-step commitment the old single End button had.
    if (showExitChoice) {
        AlertDialog(
            onDismissRequest = { showExitChoice = false },
            title = { Text(stringResource(R.string.convoy_barExitChoiceTitle)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                    Text(
                        text = stringResource(R.string.convoy_barExitChoiceBody),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            showExitChoice = false
                            pendingLeaveConvoyId = state.convoyId
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(CONVOY_BAR_EXIT_CHOICE_LEAVE_TAG),
                    ) {
                        Text(stringResource(R.string.convoy_barExitChoiceLeave))
                    }
                    Text(
                        text = stringResource(R.string.convoy_barExitChoiceLeaveDetail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            showExitChoice = false
                            pendingEndConvoyId = state.convoyId
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(CONVOY_BAR_EXIT_CHOICE_END_TAG),
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                    ) {
                        Text(stringResource(R.string.convoy_barExitChoiceEnd))
                    }
                    Text(
                        text =
                            stringResource(
                                R.string.convoy_barExitChoiceEndDetail,
                                state.memberCount,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExitChoice = false }) {
                    Text(stringResource(R.string.convoy_barConfirmCancel))
                }
            },
        )
    }

    // Replacing a destination SOMEONE ELSE chose redirects the whole group, and
    // the person who chose it is not the one tapping. Name them when we can.
    if (confirmOverwrite) {
        val setter =
            (state.destinationState as? ConvoyDestinationState.SetByOther)
                ?.destination
                ?.setByDisplayName
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text(stringResource(R.string.convoy_barDestinationOverwriteTitle)) },
            text = {
                Text(
                    if (setter != null) {
                        stringResource(R.string.convoy_barDestinationOverwriteBody, setter)
                    } else {
                        stringResource(R.string.convoy_barDestinationOverwriteBodyUnknown)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // NOTE: this is the RAW `onSetDestination` parameter, not
                        // the confirm-first wrapper handed to ConvoyDestinationRow
                        // above, so confirming here goes straight to the host's
                        // picker and cannot re-open this dialog.
                        confirmOverwrite = false
                        onSetDestination?.invoke()
                    },
                ) {
                    Text(stringResource(R.string.convoy_barDestinationOverwriteAction))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmOverwrite = false }) {
                    Text(stringResource(R.string.convoy_barConfirmCancel))
                }
            },
        )
    }
}

/**
 * The shared-destination row: what the convoy is heading for, who chose it, and
 * the three affordances around it (start navigation / set-or-change / clear).
 *
 * Rendered in the turn-by-turn navigation variant of the bar, where there is room
 * for a second text row — the map home's single-line band omits it (see
 * [ConvoyStatusBar]'s `showDestination`).
 */
@Composable
private fun ConvoyDestinationRow(
    state: ConvoyBarState,
    onSetDestination: (() -> Unit)?,
    onClearDestination: (() -> Unit)?,
    onNavigateToDestination: ((LatLng, String) -> Unit)?,
) {
    // Usable needs BOTH halves, exactly as invite/leave do above: the availability
    // flag says the callable exists, the handler says this host actually wired it up.
    val setUsable = ConvoyDestinations.isWired && onSetDestination != null
    val clearUsable = ConvoyDestinations.isWired && onClearDestination != null
    val navigateUsable = ConvoyDestinations.isWired && onNavigateToDestination != null
    val destination =
        when (val d = state.destinationState) {
            is ConvoyDestinationState.SetByMe -> d.destination
            is ConvoyDestinationState.SetByOther -> d.destination
            ConvoyDestinationState.None -> null
        }
    val unnamed = stringResource(R.string.convoy_barDestinationUnnamed)
    // A long-press on open map has no name to carry, so a nameless destination
    // gets a generic label rather than raw coordinates read out at a driver.
    val placeLabel = destination?.label?.takeIf { it.isNotBlank() } ?: unnamed

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
    ) {
        Icon(
            imageVector = Icons.Filled.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text =
                when (state.destinationState) {
                    ConvoyDestinationState.None ->
                        stringResource(R.string.convoy_barDestinationNone)
                    is ConvoyDestinationState.SetByMe ->
                        stringResource(R.string.convoy_barDestinationSetByMe, placeLabel)
                    is ConvoyDestinationState.SetByOther ->
                        destination?.setByDisplayName?.let { name ->
                            stringResource(R.string.convoy_barDestinationSetByOther, placeLabel, name)
                        } ?: stringResource(
                            R.string.convoy_barDestinationSetByOtherUnknown,
                            placeLabel,
                        )
                },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // "Take me there" — the whole point of the feature for everyone who did
        // not set it. It hands the coordinate to the SAME navigation entry point
        // the map search flow uses; there is no convoy-specific navigation path.
        if (destination != null) {
            IconButton(
                onClick = { onNavigateToDestination?.invoke(destination.point, placeLabel) },
                enabled = navigateUsable,
                modifier = Modifier.testTag(CONVOY_BAR_DESTINATION_NAVIGATE_TAG),
            ) {
                Icon(
                    imageVector = Icons.Filled.Navigation,
                    contentDescription =
                        stringResource(
                            if (navigateUsable) {
                                R.string.convoy_barDestinationNavigate
                            } else {
                                R.string.convoy_barDestinationNavigateUnavailable
                            },
                        ),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Set / change. Any accepted member may set one; replacing someone else's
        // confirms first, upstream of here.
        IconButton(
            onClick = { onSetDestination?.invoke() },
            enabled = setUsable && !state.busy,
            modifier = Modifier.testTag(CONVOY_BAR_DESTINATION_SET_TAG),
        ) {
            Icon(
                imageVector = Icons.Filled.AddLocationAlt,
                contentDescription =
                    stringResource(
                        when {
                            !setUsable -> R.string.convoy_barDestinationSetUnavailable
                            destination != null -> R.string.convoy_barDestinationChange
                            else -> R.string.convoy_barDestinationSet
                        },
                    ),
            )
        }

        // Clear — offered only where the server would allow it (setter or owner),
        // so the control is not a button that exists to be refused.
        if (destination != null && state.canClearDestination) {
            IconButton(
                onClick = { onClearDestination?.invoke() },
                enabled = clearUsable && !state.busy,
                modifier = Modifier.testTag(CONVOY_BAR_DESTINATION_CLEAR_TAG),
                colors =
                    IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Clear,
                    contentDescription =
                        stringResource(
                            if (clearUsable) {
                                R.string.convoy_barDestinationClear
                            } else {
                                R.string.convoy_barDestinationClearUnavailable
                            },
                        ),
                )
            }
        }
    }
}

/**
 * The dismissible line shown when the destination the viewer is ALREADY
 * navigating to is cleared or replaced underneath them.
 *
 * This is a message, never an interruption. Their turn-by-turn keeps running in
 * both cases — see [ConvoyDestinationNavigationEvent] for why cancelling
 * somebody's route mid-road is the worst option available. When the destination
 * was replaced rather than removed, switching is offered as one explicit tap.
 */
@Composable
private fun ConvoyDestinationNavigationBanner(
    event: ConvoyDestinationNavigationEvent,
    onDismiss: () -> Unit,
    onNavigateToNew: ((LatLng, String) -> Unit)?,
) {
    if (event is ConvoyDestinationNavigationEvent.Unchanged) return
    val unnamed = stringResource(R.string.convoy_barDestinationUnnamed)
    Column(modifier = Modifier.fillMaxWidth().padding(top = KccSpacing.s1)) {
        Text(
            text =
                when (event) {
                    is ConvoyDestinationNavigationEvent.Cleared ->
                        stringResource(R.string.convoy_barDestinationClearedWhileNavigating)
                    is ConvoyDestinationNavigationEvent.Replaced ->
                        stringResource(R.string.convoy_barDestinationChangedWhileNavigating)
                    ConvoyDestinationNavigationEvent.Unchanged -> ""
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(KccSpacing.s1)) {
            if (event is ConvoyDestinationNavigationEvent.Replaced) {
                TextButton(
                    onClick = {
                        onDismiss()
                        onNavigateToNew?.invoke(
                            event.destination.point,
                            event.destination.label?.takeIf { it.isNotBlank() } ?: unnamed,
                        )
                    },
                    // Same both-halves gate as the row above.
                    enabled = ConvoyDestinations.isWired && onNavigateToNew != null,
                ) {
                    Text(stringResource(R.string.convoy_barDestinationNavigateNew))
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.convoy_barDestinationDismissNotice))
            }
        }
    }
}

/**
 * The list of people in the convoy, shown when the member count is tapped.
 *
 * A [Popup] (not a Dialog) so there is no dimming scrim — the map stays visible,
 * matching the profile menu's idiom on the map home. It anchors just below the
 * member-count control, start-aligned, and dismisses on an outside tap or Back
 * (`focusable = true`). Rows come off [ConvoyBarState.memberListEntries] — the
 * accepted roster the bar already carries, followed by the invited-but-unanswered
 * people shown muted with a "Waiting to join…" status. A person whose display name
 * has not resolved yet falls back to a generic label rather than showing a raw uid.
 * Each member shows their real profile picture where one is set, falling back to a
 * neutral avatar placeholder.
 *
 * [onMemberSelected] — when non-null — makes a JOINED row tappable to raise that
 * member's actions sheet; pending-invitee rows are never tappable (no live
 * location, no in-convoy identity to act on yet).
 */
@Composable
private fun ConvoyMemberListPopup(
    entries: List<ConvoyMemberListEntry>,
    onDismiss: () -> Unit,
    onMemberSelected: ((ConvoyBarMember) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val positionProvider =
        remember(density) {
            val gapPx = with(density) { KccSpacing.s2.roundToPx() }
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    // Start-align to the count and drop just below it. anchorBounds
                    // is in physical window coordinates; leading edge is the
                    // physical left in LTR, the physical right in RTL. Clamped so
                    // the card never leaves the window on a narrow screen.
                    val x =
                        when (layoutDirection) {
                            LayoutDirection.Ltr -> anchorBounds.left
                            LayoutDirection.Rtl -> anchorBounds.right - popupContentSize.width
                        }.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
                    val y =
                        (anchorBounds.bottom + gapPx)
                            .coerceIn(0, maxOf(0, windowSize.height - popupContentSize.height))
                    return IntOffset(x, y)
                }
            }
        }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier =
                Modifier
                    .widthIn(min = 180.dp, max = 280.dp)
                    .testTag(CONVOY_BAR_MEMBER_LIST_TAG),
            shape = RoundedCornerShape(KccRadius.lg),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(KccSpacing.s3)
                        // Long rosters scroll rather than run off the screen.
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.convoy_barMemberListTitle),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(KccSpacing.s2))
                val unnamed = stringResource(R.string.convoy_barMemberUnnamed)
                val waitingLabel = stringResource(R.string.convoy_barMemberWaiting)
                entries.forEach { entry ->
                    val waiting = entry.presence == ConvoyMemberPresence.WaitingToJoin
                    // A pending invitee reads as muted — the avatar and name step
                    // down to the secondary tone joined members never use — so "who
                    // is actually here" and "who is on the way" are legible apart at
                    // a glance without a second control.
                    val nameTint =
                        if (waiting) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    // Only joined members open the actions sheet — see the KDoc.
                    val tappable = !waiting && onMemberSelected != null
                    val rowModifier =
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (tappable) {
                                    Modifier.clickable(role = Role.Button) {
                                        onMemberSelected?.invoke(entry.member)
                                    }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(vertical = KccSpacing.s1)
                    Row(
                        modifier = rowModifier,
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
                    ) {
                        ConvoyMemberAvatar(avatarPath = entry.member.avatarPath, muted = waiting)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.member.displayName?.takeIf { it.isNotBlank() } ?: unnamed,
                                style = MaterialTheme.typography.bodyMedium,
                                color = nameTint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (waiting) {
                                Text(
                                    text = waitingLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A convoy member's avatar in the list popup: their real profile picture when one
 * is set, resolved through the same [rememberStorageImageUrl] path the rest of the
 * app loads member avatars with.
 *
 * The neutral placeholder (grey circle + person glyph) is drawn UNDERNEATH the
 * image and always present, so it is what shows in every non-loaded case — no
 * picture set, the image still loading, or the load failing — and the real image
 * simply covers it once it decodes. That means a member with a set picture shows
 * the placeholder while it loads and then the photo, and falls back to the same
 * placeholder (never a blank circle) on error. [muted] dims the placeholder glyph
 * for a pending invitee, matching the muted name/status of a "waiting to join" row.
 */
@Composable
private fun ConvoyMemberAvatar(avatarPath: String?, muted: Boolean) {
    val context = LocalContext.current
    val url = rememberStorageImageUrl(context, avatarPath)
    Box(
        modifier =
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        // Always-present placeholder beneath the image: shows for no-url, and stays
        // visible while the image loads or if it fails (AsyncImage is transparent
        // until it has a decoded bitmap, so this shows through).
        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = null,
            tint =
                if (muted) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.size(18.dp),
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/**
 * The per-member actions raised by tapping a JOINED member in the list: open their
 * profile, or centre the convoy map on their live location.
 *
 * A [ModalBottomSheet] to match the app's other row-action menus (place / message
 * actions) and to avoid nesting a menu inside the member-list's focusable popup.
 *
 * "Go to location" is present whenever the host wired [onGoToLocation], but ENABLED
 * only while [hasLocation] — a member not sharing a position right now cannot be
 * centred on, so the action is shown disabled with a short "location unavailable"
 * note rather than silently missing (its presence still tells you it is a thing you
 * can normally do). "Profile" appears whenever [onOpenProfile] is wired.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConvoyMemberActionsSheet(
    member: ConvoyBarMember,
    hasLocation: Boolean,
    onOpenProfile: (() -> Unit)?,
    onGoToLocation: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val unnamed = stringResource(R.string.convoy_barMemberUnnamed)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(CONVOY_BAR_MEMBER_ACTIONS_TAG),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s2),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = KccSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
            ) {
                ConvoyMemberAvatar(avatarPath = member.avatarPath, muted = false)
                Text(
                    text = member.displayName?.takeIf { it.isNotBlank() } ?: unnamed,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (onOpenProfile != null) {
                ConvoyMemberAction(
                    icon = Icons.Filled.Person,
                    text = stringResource(R.string.convoy_barMemberMenuProfile),
                    onClick = onOpenProfile,
                    testTag = CONVOY_BAR_MEMBER_PROFILE_TAG,
                )
            }
            if (onGoToLocation != null) {
                ConvoyMemberAction(
                    icon = Icons.Filled.Place,
                    text = stringResource(R.string.convoy_barMemberMenuGoToLocation),
                    // Disabled — not hidden — when the member is not sharing a
                    // position right now, so the affordance stays discoverable.
                    subtext =
                        if (!hasLocation) {
                            stringResource(R.string.convoy_barMemberLocationUnavailable)
                        } else {
                            null
                        },
                    enabled = hasLocation,
                    onClick = onGoToLocation,
                    testTag = CONVOY_BAR_MEMBER_LOCATION_TAG,
                )
            }
        }
    }
}

@Composable
private fun ConvoyMemberAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    testTag: String,
    enabled: Boolean = true,
    subtext: String? = null,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(testTag),
    ) {
        Icon(imageVector = icon, contentDescription = null)
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = KccSpacing.s3),
        ) {
            Text(text = text, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
            if (subtext != null) {
                Text(
                    text = subtext,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
