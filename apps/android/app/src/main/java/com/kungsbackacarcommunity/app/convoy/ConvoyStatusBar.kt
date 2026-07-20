package com.kungsbackacarcommunity.app.convoy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing

/**
 * A usable/not-usable boolean as the availability it corresponds to, so the
 * bar's explanation line can be derived from the same booleans that decide
 * enablement and the accessibility labels instead of a parallel rule.
 */
private fun availability(usable: Boolean): ConvoyBarActionAvailability =
    if (usable) {
        ConvoyBarActionAvailability.Wired
    } else {
        ConvoyBarActionAvailability.BackendMissing
    }

/** Test tag on the whole convoy status bar. */
const val CONVOY_BAR_TEST_TAG = "convoy_bar"

/** Test tag on the bar's invite ("person +") control. */
const val CONVOY_BAR_INVITE_TAG = "convoy_bar_invite"

/** Test tag on the bar's leave / end-convoy control. */
const val CONVOY_BAR_LEAVE_TAG = "convoy_bar_leave"

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
 * Actions with no backend stay VISIBLE but disabled and are explained in one
 * short line under the row (see [ConvoyBarNotice] and this file's sibling
 * [ConvoyBar] KDoc, which carries the exact callable contracts still needed).
 *
 * Styled from the same frosted, rounded, tonally-elevated `surface` language as
 * the map's search bar and floating controls, so it reads as one more piece of
 * the map chrome rather than a foreign banner.
 *
 * @param compact drops the explanation line and tightens the padding — used in
 *   turn-by-turn navigation, where vertical space next to safety-critical
 *   maneuver instructions is at a premium. The explanation is not lost, only
 *   deferred: it is still shown on the map home, and the disabled controls keep
 *   their explanatory content descriptions here for accessibility.
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
 */
@Composable
fun ConvoyStatusBar(
    state: ConvoyBarState,
    onEndConvoy: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onInvite: ((String) -> Unit)? = null,
    onLeaveConvoy: ((String) -> Unit)? = null,
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

    // Whether each action is actually USABLE, which needs both halves: the state
    // flag says a callable exists, the handler says this host has wired it up.
    // Every downstream decision — enablement, the accessibility label, and the
    // explanation line — reads these two booleans and nothing else, so the
    // control cannot end up disabled while announcing itself as available, or
    // enabled while the line underneath still says the feature is missing.
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

    // The explanation line is recomputed from usability rather than read off
    // `state.notice`, for the same reason: a flag flipped ahead of its handler
    // must keep saying the action is unavailable, because it still is.
    val effectiveNotice =
        state.copy(
            inviteAvailability = availability(inviteUsable),
            leaveAvailability = availability(leaveUsable),
        ).notice

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

                // Invite ("person +"). No `convoy.invite` callable exists, so this
                // is disabled rather than pointed at the create-convoy picker,
                // which would spawn a SECOND convoy instead of growing this one.
                //
                // Enablement is DERIVED from the state and the presence of a
                // handler, never hard-coded: `enabled = false` with an empty
                // `onClick` would let `inviteAvailability` be flipped to `Wired`
                // — the one-line change this is all waiting on — while the button
                // stayed silently dead, and the explanation line underneath
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

            // The honest one-liner for whatever is disabled above. Dropped in the
            // navigation variant, where the top of the screen belongs to the
            // maneuver instructions.
            val noticeRes =
                when (effectiveNotice) {
                    ConvoyBarNotice.None -> null
                    ConvoyBarNotice.InviteMissing -> R.string.convoy_barNoticeInvite
                    ConvoyBarNotice.LeaveMissing -> R.string.convoy_barNoticeLeave
                    ConvoyBarNotice.InviteAndLeaveMissing ->
                        R.string.convoy_barNoticeInviteAndLeave
                }
            if (!compact && noticeRes != null) {
                Text(
                    text = stringResource(noticeRes),
                    modifier = Modifier.padding(bottom = KccSpacing.s1),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
}
