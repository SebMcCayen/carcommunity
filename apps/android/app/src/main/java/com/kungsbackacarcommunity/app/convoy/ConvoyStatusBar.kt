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
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.kungsbackacarcommunity.app.map.ConvoyFocusMode

/** Test tag on the whole convoy status bar. */
const val CONVOY_BAR_TEST_TAG = "convoy_bar"

/** Test tag on the bar's map-focus toggle (me vs the whole convoy). */
const val CONVOY_BAR_FOCUS_TAG = "convoy_bar_focus"

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
 * @param focusMode what the map camera is currently framing. Defaults to
 *   [ConvoyFocusMode.Me] — the behaviour that existed before this control — so a
 *   host that does not wire the toggle is unchanged.
 * @param onFocusModeChange invoked with the mode the user just picked.
 */
@Composable
fun ConvoyStatusBar(
    state: ConvoyBarState,
    onEndConvoy: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    focusMode: ConvoyFocusMode = ConvoyFocusMode.Me,
    onFocusModeChange: (ConvoyFocusMode) -> Unit = {},
) {
    var confirmEnd by remember { mutableStateOf(false) }

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
                val inviteWired = state.inviteAvailability == ConvoyBarActionAvailability.Wired
                IconButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.testTag(CONVOY_BAR_INVITE_TAG),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PersonAdd,
                        contentDescription =
                            stringResource(
                                if (inviteWired) {
                                    R.string.convoy_barInvite
                                } else {
                                    R.string.convoy_barInviteUnavailable
                                },
                            ),
                    )
                }

                // Leave (member) / End (owner). Only the owner's variant is wired,
                // and it confirms first because it ends the drive for everyone.
                val leaveWired = state.leaveAvailability == ConvoyBarActionAvailability.Wired
                IconButton(
                    onClick = { confirmEnd = true },
                    enabled = leaveWired && !state.busy,
                    modifier = Modifier.testTag(CONVOY_BAR_LEAVE_TAG),
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
                                    leaveWired -> R.string.convoy_barLeave
                                    else -> R.string.convoy_barLeaveUnavailable
                                },
                            ),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // The honest one-liner for whatever is disabled above. Dropped in the
            // navigation variant, where the top of the screen belongs to the
            // maneuver instructions.
            val noticeRes =
                when (state.notice) {
                    ConvoyBarNotice.None -> null
                    ConvoyBarNotice.InviteMissing -> R.string.convoy_barNoticeInvite
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
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(stringResource(R.string.convoy_barEndConfirmTitle)) },
            text = { Text(stringResource(R.string.convoy_barEndConfirmBody)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmEnd = false
                        onEndConvoy(state.convoyId)
                    },
                ) {
                    Text(stringResource(R.string.convoy_barEndConfirmAction))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnd = false }) {
                    Text(stringResource(R.string.convoy_barConfirmCancel))
                }
            },
        )
    }
}
