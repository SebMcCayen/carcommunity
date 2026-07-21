package com.kungsbackacarcommunity.app.convoy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.map.ConvoyFocusMode
import com.kungsbackacarcommunity.app.navigation.LatLng

/** Test tag on the whole convoy status bar. */
const val CONVOY_BAR_TEST_TAG = "convoy_bar"

/** Test tag on the bar's map-focus toggle (me vs the whole convoy). */
const val CONVOY_BAR_FOCUS_TAG = "convoy_bar_focus"

/** Test tag on the bar's invite ("person +") control. */
const val CONVOY_BAR_INVITE_TAG = "convoy_bar_invite"

/** Test tag on the bar's leave / end-convoy control. */
const val CONVOY_BAR_LEAVE_TAG = "convoy_bar_leave"

/** Test tag on the bar's set/change shared-destination control. */
const val CONVOY_BAR_DESTINATION_SET_TAG = "convoy_bar_destination_set"

/** Test tag on the bar's clear shared-destination control. */
const val CONVOY_BAR_DESTINATION_CLEAR_TAG = "convoy_bar_destination_clear"

/** Test tag on the bar's "start navigation to the shared destination" control. */
const val CONVOY_BAR_DESTINATION_NAVIGATE_TAG = "convoy_bar_destination_navigate"

/** Test tag on the compact in-row convoy pill (member count + quick controls). */
const val CONVOY_BAR_INLINE_TAG = "convoy_bar_inline"

/** Test tag on the compact pill's map-focus toggle (me vs the whole convoy). */
const val CONVOY_BAR_INLINE_FOCUS_TAG = "convoy_bar_inline_focus"

/** Test tag on the compact pill's expand/collapse control. */
const val CONVOY_BAR_EXPAND_TAG = "convoy_bar_expand"

/** Test tag on the popup that the compact pill's expand control opens. */
const val CONVOY_BAR_EXPAND_POPUP_TAG = "convoy_bar_expand_popup"

/**
 * The COMPACT convoy pill shown inline in the map's top row, wedged between the
 * search control and the profile avatar (see `SearchBarRow`). That slot is narrow,
 * so this shows ICONS AND A NUMBER ONLY — no text labels:
 *
 *  - a group glyph + the bare accepted-member count (the count carries the full
 *    "N in the convoy" announcement for TalkBack, so the raw number is not read
 *    out on its own);
 *  - the map-focus toggle — the one always-operable control (follow me vs. keep
 *    the whole convoy framed), and the icon a member sees respond when every other
 *    action is still backend-gated;
 *  - an expand control that opens the FULL [ConvoyStatusBar] in a popup for
 *    everything that does not fit inline (leave/end, invite, and the shared
 *    destination row).
 *
 * ## Why the actions live behind the expand, not inline
 * The full bar's leave/invite/destination controls are mostly DISABLED today
 * because their callables are not deployed (see [ConvoyBar]); only the owner's End
 * and the focus toggle actually do anything. Cramming disabled buttons into the
 * pill would put dead controls in a permanently-visible strip — exactly the "I
 * can't press any of these" complaint. Instead the pill surfaces the one live
 * quick control (focus) and defers the rest to the popup, where each disabled
 * control keeps its "…unavailable" contentDescription for accessibility (there is
 * no visible "not available yet" body text — see [ConvoyStatusBar]). Nothing is
 * lost: the full bar — with all its confirmations, routing and accessibility
 * labels — is rendered verbatim in the popup via [expandedContent], so there is
 * still ONE set of convoy-bar rules.
 *
 * @param memberCount accepted members only — the number shown next to the glyph.
 * @param focusMode what the map camera is currently framing; drives the toggle.
 * @param onFocusModeChange invoked with the mode the user just picked.
 * @param expandedContent the full [ConvoyStatusBar] for this convoy, rendered in
 *   the expand popup. Passed as a slot so the pill does not re-thread every convoy
 *   callback and there is no second copy of the bar's action logic.
 */
@Composable
fun ConvoyStatusBarInline(
    memberCount: Int,
    modifier: Modifier = Modifier,
    focusMode: ConvoyFocusMode = ConvoyFocusMode.Me,
    onFocusModeChange: (ConvoyFocusMode) -> Unit = {},
    expandedContent: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val convoyFocused = focusMode == ConvoyFocusMode.Convoy
    val density = LocalDensity.current
    // Drop the expand popup just BELOW the pill vertically (anchored to the pill's
    // bottom edge), but HORIZONTALLY CENTRED IN THE WINDOW — not aligned to the
    // pill's x. The popup is a near-full-width card (see the widthIn cap below), so
    // pinning its left edge under a pill that sits near the right of the top row
    // would push it off-screen; centring keeps it on screen at any pill position.
    // Both axes are clamped so the card can never leave the screen — the same
    // proven pattern the profile menu uses, so it can't spill off a narrow phone.
    val positionProvider =
        remember(density) {
            val gapPx = with(density) { KccSpacing.s1.roundToPx() }
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val x =
                        ((windowSize.width - popupContentSize.width) / 2)
                            .coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
                    val y =
                        (anchorBounds.bottom + gapPx)
                            .coerceIn(0, maxOf(0, windowSize.height - popupContentSize.height))
                    return IntOffset(x, y)
                }
            }
        }

    Box {
        Surface(
            modifier = modifier.testTag(CONVOY_BAR_INLINE_TAG),
            shape = RoundedCornerShape(KccRadius.full),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 3.dp,
        ) {
            Row(
                // The IconButtons carry the 48dp touch target, so the pill lands at
                // the same height as the search button and avatar it sits between.
                modifier = Modifier.padding(start = KccSpacing.s3, end = KccSpacing.s1),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KccSpacing.s1),
            ) {
                Icon(
                    // Decorative: the count's own node carries the full "N in the
                    // convoy" announcement (below), so the glyph is not read too.
                    imageVector = Icons.Filled.Groups,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                val membersDescription =
                    stringResource(R.string.convoy_barMembers, memberCount)
                Text(
                    // Shows the bare number, but announces "N in the convoy" — a
                    // contentDescription overrides the visible "3" for TalkBack while
                    // the digit stays visible (and findable by text).
                    text = memberCount.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { contentDescription = membersDescription },
                )
                IconButton(
                    onClick = {
                        onFocusModeChange(
                            if (convoyFocused) ConvoyFocusMode.Me else ConvoyFocusMode.Convoy,
                        )
                    },
                    modifier = Modifier.testTag(CONVOY_BAR_INLINE_FOCUS_TAG),
                ) {
                    Icon(
                        imageVector =
                            if (convoyFocused) Icons.Filled.Groups else Icons.Filled.CenterFocusStrong,
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
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.testTag(CONVOY_BAR_EXPAND_TAG),
                ) {
                    Icon(
                        imageVector =
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription =
                            stringResource(
                                if (expanded) R.string.convoy_barCollapse else R.string.convoy_barExpand,
                            ),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        if (expanded) {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                // No frosted card of its own: the full bar is already a rounded,
                // tonally-elevated Surface, so wrapping it in a second Surface would
                // double the chrome. Fill the window width (capped) so the bar keeps
                // its normal full-width layout.
                Box(
                    modifier =
                        Modifier
                            .padding(KccSpacing.s2)
                            .fillMaxWidth()
                            .widthIn(max = 400.dp)
                            .testTag(CONVOY_BAR_EXPAND_POPUP_TAG),
                ) {
                    expandedContent()
                }
            }
        }
    }
}

/**
 * A compact, full-width bar describing the convoy the user is currently in:
 * how many people are in it, an invite affordance, and a leave (member) or end
 * (owner) affordance.
 *
 * Rendered by the map home and by turn-by-turn navigation through a slot, so
 * there is ONE convoy bar with one set of rules rather than a map version and a
 * navigation version that can drift apart. [state] being null is the caller's
 * signal not to compose it at all — this function is only ever called with a real
 * state, and the host omits the slot entirely when [ConvoyBar.stateFor] returns
 * null, so "not in a convoy" renders nothing whatsoever (no empty bar, no
 * placeholder).
 *
 * ## Owner vs member
 * The trailing control is deliberately NOT one action with two labels. The owner
 * gets "End convoy" — which really does end the drive for the whole group (that
 * is what `convoy-end` does) — and it always confirms first. A member gets
 * "Leave convoy", which has no callable yet and is therefore disabled; it must
 * never fall through to `convoy-end`, since a member tapping "leave" and silently
 * ending everyone's convoy would be a genuinely harmful bug.
 *
 * Actions with no backend stay VISIBLE but disabled. There is NO visible
 * "not available yet" notice line: each disabled control carries its own
 * "…unavailable" contentDescription instead, so the unavailability is announced to
 * screen-reader users without a body-text paragraph cluttering the bar over the
 * map. (See this file's sibling [ConvoyBar] KDoc for the exact callable contracts
 * still needed.)
 *
 * Styled from the same frosted, rounded, tonally-elevated `surface` language as
 * the map's search bar and floating controls, so it reads as one more piece of
 * the map chrome rather than a foreign banner.
 *
 * @param compact tightens the padding and hides the map-focus toggle — used in
 *   turn-by-turn navigation, where vertical space next to safety-critical
 *   maneuver instructions is at a premium and the navigation SDK owns the camera.
 *   The disabled controls keep their "…unavailable" content descriptions in both
 *   variants, so accessibility is unchanged by [compact].
 * @param onEndConvoy invoked (after the user confirms) when the OWNER ends the
 *   convoy. Never invoked for a member.
 * @param onInvite invites people into THIS convoy (by id). Null today, because
 *   the `convoy.invite` callable does not exist — see [ConvoyBar]. The invite
 *   control needs both this handler AND
 *   [ConvoyBarState.inviteAvailability] `== Wired` before it enables, so neither
 *   half can be added on its own and quietly produce a button that looks live and
 *   does nothing, or a flag that claims a capability the UI never exposes.
 * @param onLeaveConvoy removes the CALLER from this convoy — a member's action,
 *   never an owner's. Null today, because the `convoy.leave` callable does not
 *   exist either. A member's tap is routed here on `viewerIsOwner`, so it can
 *   never reach the end-convoy confirmation no matter what
 *   [ConvoyBarState.leaveAvailability] says: "leave" ending everyone's drive is
 *   the one failure in this component that would actually hurt people, so it is
 *   prevented structurally rather than by the availability flag being correct.
 * @param focusMode what the map camera is currently framing. Defaults to
 *   [ConvoyFocusMode.Me] — the behaviour that existed before this control — so a
 *   host that does not wire the toggle is unchanged.
 * @param onFocusModeChange invoked with the mode the user just picked.
 * @param onSetDestination open the place picker to set (or replace) the convoy's
 *   SHARED destination. Invoked only after the overwrite confirmation, when the
 *   current destination was set by somebody else. No-op today: the control is
 *   disabled while [ConvoyDestinations.availability] is
 *   [ConvoyDestinationAvailability.BackendMissing].
 * @param onClearDestination clear the shared destination. Offered only to the
 *   member who set it or to the convoy owner ([ConvoyBarState.canClearDestination]).
 * @param onNavigateToDestination start turn-by-turn to the shared destination.
 *   Carries the coordinate + a display label and hands off to the SAME navigation
 *   entry point the map's search flow uses — there is no second navigation path.
 * @param navigationEvent what just happened to the destination the viewer is
 *   already navigating to. Drives the dismissible banner; see
 *   [ConvoyDestinationNavigationEvent] for why a cleared destination never
 *   cancels a running navigation.
 * @param onDismissNavigationEvent acknowledge that banner.
 */
@Composable
fun ConvoyStatusBar(
    state: ConvoyBarState,
    onEndConvoy: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onInvite: ((String) -> Unit)? = null,
    onLeaveConvoy: ((String) -> Unit)? = null,
    focusMode: ConvoyFocusMode = ConvoyFocusMode.Me,
    onFocusModeChange: (ConvoyFocusMode) -> Unit = {},
    onSetDestination: (() -> Unit)? = null,
    onClearDestination: ((String) -> Unit)? = null,
    onNavigateToDestination: ((LatLng, String) -> Unit)? = null,
    navigationEvent: ConvoyDestinationNavigationEvent =
        ConvoyDestinationNavigationEvent.Unchanged,
    onDismissNavigationEvent: () -> Unit = {},
) {
    // The convoy the open confirm dialog is ABOUT, captured when the user opened
    // it — not a bare boolean. [state] is hoisted and refreshes underneath this
    // composable (the coordinator re-fetches the convoy list after every
    // mutation, and `activeConvoy` re-picks which convoy the bar describes), so a
    // boolean flag plus a `state.convoyId` read at confirm time would end
    // WHICHEVER convoy the bar happened to be showing at the moment of the tap —
    // not the one named in the dialog the user was answering.
    //
    // Deliberately NOT `remember(state.convoyId)`: keying the flag would make the
    // dialog silently disappear under a background refresh, cancelling a
    // considered destructive decision without a word and leaving the user to
    // wonder whether it went through. Capturing the id instead keeps the dialog
    // up and keeps its meaning fixed — it still ends exactly the convoy it named.
    var pendingEndConvoyId by remember { mutableStateOf<String?>(null) }

    // Whether the "set destination" tap is currently waiting on the overwrite
    // confirmation (only raised when the current destination was set by someone
    // else — see ConvoyDestinations.requiresOverwriteConfirmation).
    var confirmOverwrite by remember { mutableStateOf(false) }

    // Whether each action is actually USABLE, which needs both halves: the state
    // flag says a callable exists, the handler says this host has wired it up.
    // Both downstream decisions — enablement and the accessibility label — read
    // these two booleans and nothing else, so the control cannot end up disabled
    // while announcing itself as available, or enabled while its "…unavailable"
    // label still says the feature is missing.
    //
    // `state.busy` is deliberately NOT part of them: it is a transient in-flight
    // state, so it must disable the buttons without rewriting their labels into
    // "not available yet", which would tell a screen-reader user the feature is
    // gone when it is merely mid-request.
    val inviteUsable =
        state.inviteAvailability == ConvoyBarActionAvailability.Wired && onInvite != null
    val leaveUsable =
        state.leaveAvailability == ConvoyBarActionAvailability.Wired &&
            // The owner's control is `convoy-end`, whose handler is non-null by
            // signature; only a member's leave depends on the optional handler.
            (state.viewerIsOwner || onLeaveConvoy != null)

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
                    top = KccSpacing.s1,
                    bottom = if (compact) KccSpacing.s1 else KccSpacing.s2,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
            ) {
                Icon(
                    imageVector = Icons.Filled.Group,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.convoy_barMembers, state.memberCount),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // Map focus: follow ME (the default, and exactly the camera
                // behaviour that existed before convoys) or keep the WHOLE
                // convoy framed. One button rather than a segmented control —
                // it is a two-state choice in a bar that has to survive being
                // squeezed into the navigation chrome. The icon shows the mode
                // currently in effect, and it is tinted when convoy framing is
                // on so an unusual camera state is never silent.
                //
                // Hidden in the [compact] (turn-by-turn) variant on purpose: the
                // navigation map's camera is owned by the Navigation SDK, so the
                // choice would have nothing to act on there. A control that
                // silently does nothing is worse than one that isn't offered.
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

                // Invite ("person +"). No `convoy.invite` callable exists, so this
                // is disabled rather than pointed at the create-convoy picker,
                // which would spawn a SECOND convoy instead of growing this one.
                //
                // Enablement is DERIVED from the state and the presence of a
                // handler, never hard-coded: `enabled = false` with an empty
                // `onClick` would let `inviteAvailability` be flipped to `Wired`
                // — the one-line change this is all waiting on — while the button
                // stayed silently dead, and the "…unavailable" contentDescription
                // stopped saying why. Requiring [onInvite] as well means flipping
                // the flag alone cannot produce an ENABLED button that does
                // nothing either; both halves have to be done deliberately.
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

                // Leave (member) / End (owner). Two genuinely different actions
                // sharing a slot, so ownership — not availability — decides which
                // one a tap runs. Routing on `viewerIsOwner` explicitly, rather
                // than letting a Wired `leaveAvailability` imply "open the end
                // dialog", is what keeps the footgun in this file's KDoc closed:
                // the day `convoy.leave` lands and someone flips
                // [ConvoyBar.leaveAvailability] to Wired for members, a member's
                // tap CANNOT fall through into "end the convoy for everyone". It
                // reaches [onLeaveConvoy] or, while that is still null, nothing at
                // all — the control simply stays disabled until the handler is
                // supplied, exactly like the invite control above.
                val leaveEnabled = leaveUsable && !state.busy
                IconButton(
                    onClick = {
                        if (state.viewerIsOwner) {
                            pendingEndConvoyId = state.convoyId
                        } else {
                            onLeaveConvoy?.invoke(state.convoyId)
                        }
                    },
                    enabled = leaveEnabled,
                    modifier = Modifier.testTag(CONVOY_BAR_LEAVE_TAG),
                    // Destructive red, but only while the control can actually do
                    // something. Handing the colour to `iconButtonColors` rather
                    // than hard-tinting the Icon is what lets Material apply its
                    // own disabled treatment (the derived low-opacity
                    // `disabledContentColor`, published through LocalContentColor)
                    // — a hard `tint = error` bypasses that and paints a DISABLED
                    // member-leave icon in full strength destructive red, which
                    // reads as tappable and is especially noisy over a moving map.
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Icon(
                        imageVector =
                            if (state.viewerIsOwner) {
                                Icons.Filled.StopCircle
                            } else {
                                Icons.AutoMirrored.Filled.Logout
                            },
                        contentDescription =
                            stringResource(
                                when {
                                    state.viewerIsOwner -> R.string.convoy_barEnd
                                    leaveUsable -> R.string.convoy_barLeave
                                    else -> R.string.convoy_barLeaveUnavailable
                                },
                            ),
                        // No explicit tint: LocalContentColor carries whichever of
                        // the IconButton's enabled/disabled content colours applies.
                    )
                }
            }

            // The SHARED destination: where the whole convoy is heading. One
            // member picks it, everyone else gets a one-tap "start navigation".
            // No callable exists for setting or clearing it yet, so both controls
            // render disabled — see the ConvoyDestination file KDoc.
            ConvoyDestinationRow(
                state = state,
                // Wrapped, but only when the host actually supplied a handler:
                // `?.let` keeps null NULL, so the row's usability gate still sees
                // "no handler" rather than a wrapper that would swallow the tap.
                onSetDestination =
                    onSetDestination?.let { set ->
                        {
                            // Replacing somebody ELSE's destination changes where
                            // the whole group is heading, so it confirms first.
                            // Replacing your own does not: a confirmation on a
                            // correction you are making to your own pick only
                            // trains people to dismiss the dialog that matters.
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
            // to. Deliberately a message, never an interruption: a destination
            // that is cleared or replaced mid-drive leaves the running
            // turn-by-turn alone.
            ConvoyDestinationNavigationBanner(
                event = navigationEvent,
                onDismiss = onDismissNavigationEvent,
                onNavigateToNew = onNavigateToDestination,
            )

            // No visible "not available yet" notice line. The invite/leave and
            // shared-destination controls that have no callable yet stay VISIBLE
            // but disabled, and each carries its own "…unavailable"
            // contentDescription (see the IconButtons above and in
            // [ConvoyDestinationRow]) so a screen-reader user is still told the
            // action is unavailable. The body-text explanations were removed:
            // they read as clutter over the map, and the leave/invite/destination
            // callables are actively being wired, so a standing "isn't available
            // yet" line is becoming false. Accessibility is carried by the control
            // labels, not a separate paragraph.
        }
    }

    // Destructive and group-wide: ending is never one tap. The body says out loud
    // that this is not "leave", so an owner looking for a way out for THEMSELVES
    // finds out before they end everyone's drive.
    //
    // The id is read out of the pending state, NOT out of [state], so the convoy
    // the user is answering about cannot change between opening this dialog and
    // confirming it.
    val confirmingConvoyId = pendingEndConvoyId
    if (confirmingConvoyId != null) {
        AlertDialog(
            onDismissRequest = { pendingEndConvoyId = null },
            title = { Text(stringResource(R.string.convoy_barEndConfirmTitle)) },
            text = { Text(stringResource(R.string.convoy_barEndConfirmBody)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingEndConvoyId = null
                        onEndConvoy(confirmingConvoyId)
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

    // Replacing a destination SOMEONE ELSE chose redirects the whole group, and
    // the person who chose it is not the one tapping. Name them when we can, so
    // the decision is made against a person rather than against an abstraction.
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
                        // above. That wrapper is an argument expression and binds
                        // nothing in this scope, so confirming here goes straight
                        // to the host's picker and cannot re-open this dialog.
                        // The ordering below is therefore not load-bearing either.
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
 * Always rendered, including in the compact navigation variant — a driver
 * following a convoy is precisely the person who needs "where are we going" and
 * "take me there" within reach. The disabled controls carry their "…unavailable"
 * content descriptions in every variant, so the unavailability survives for
 * screen-reader users without a visible explanation line.
 */
@Composable
private fun ConvoyDestinationRow(
    state: ConvoyBarState,
    onSetDestination: (() -> Unit)?,
    onClearDestination: (() -> Unit)?,
    onNavigateToDestination: ((LatLng, String) -> Unit)?,
) {
    // Usable needs BOTH halves, exactly as invite/leave do above: the
    // availability flag says the callable exists, the handler says this host
    // actually wired it up. Gating on the flag alone would mean that the day
    // ConvoyDestinations.availability flips to Wired, a host that had not yet
    // passed the callbacks would render enabled buttons that silently do
    // nothing — the precise failure the invite/leave gating exists to prevent,
    // and there is no reason for this row to learn it the hard way separately.
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

        // Set / change. Any accepted member may set one (see the callable
        // contract); replacing someone else's confirms first, upstream of here.
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
                // Destructive red, but only while the control can actually do
                // something. Handing the colour to `iconButtonColors` rather than
                // hard-tinting the Icon is what lets Material apply its own
                // disabled treatment (the derived low-opacity
                // `disabledContentColor`, published through LocalContentColor) — a
                // hard `tint = error` bypasses that and paints a DISABLED clear
                // icon in full strength destructive red. That is not hypothetical
                // here: [ConvoyDestinations.availability] is BackendMissing, so
                // `clearUsable` is false on every build today and this button ships
                // permanently disabled. It must not look tappable.
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
                    // No explicit tint: LocalContentColor carries whichever of the
                    // IconButton's enabled/disabled content colours applies.
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
