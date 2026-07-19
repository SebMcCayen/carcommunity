package com.kungsbackacarcommunity.app.convoy

/**
 * The compact convoy status bar shown at the TOP of the map home and of
 * turn-by-turn navigation while — and only while — the caller is actually IN a
 * convoy.
 *
 * Everything that decides *whether* the bar renders, *which* convoy it describes
 * and *which* of its actions can do anything lives here as pure Kotlin, so the
 * visibility and owner/member rules are JVM-unit-testable instead of buried in a
 * Composable.
 *
 * ## What "in a convoy" means
 * Being INVITED is not being in a convoy: a pending invite belongs to the
 * invites list, and showing a driving-mode bar for a convoy the user has not
 * answered would claim a membership they don't have. So the bar requires the
 * caller's own [ConvoyViewer.inviteStatus] to be [ConvoyInviteStatus.Accepted]
 * (the owner is always accepted) and the convoy not to have ended. A convoy that
 * is still `Forming` DOES count — the roster exists, the members are gathering,
 * and that is exactly when someone wants to see who is in and invite more.
 *
 * ## BACKEND GAP — two of the three affordances have no callable
 * The deployed convoy surface is exactly five callables (`convoy-create`,
 * `convoy-respond`, `convoy-start`, `convoy-end`, `convoy-list`). Neither
 * inviting someone into an EXISTING convoy nor a member LEAVING one is among
 * them:
 *
 *  - **`convoy-respond` cannot leave.** It hard-requires
 *    `entry.inviteStatus === 'invited'` and throws `failed-precondition`
 *    otherwise, so it only ever answers a still-pending invite. Once you have
 *    accepted, respond is closed to you.
 *  - **`convoy-end` is owner-only** (a non-owner gets `not-found` so a convoy
 *    can't be probed) and it ends the convoy *for everyone*, storing the shared
 *    summary. It is emphatically NOT "leave" — wiring a member's Leave button to
 *    it would silently end the drive for the whole group.
 *  - **`convoy-create` takes invitees only at creation.** There is no
 *    "add member" path, so pointing the bar's invite button at the existing
 *    create/invite picker would create a SECOND convoy rather than grow this one.
 *
 * Hence [ConvoyBarActionAvailability]: the owner's End action is [Wired] and
 * confirmed; the member's Leave action and BOTH roles' Invite action are
 * [BackendMissing] — rendered, disabled, and explained in one honest line
 * ([ConvoyBarNotice]) rather than silently absent or, worse, wired to a callable
 * that does something else.
 *
 * ### Callables needed to finish this
 *  - `convoy.leave` (grouped export `convoy-leave`, europe-west1, auth +
 *    App Check), payload `{ convoyId: string }` → `{ convoy: ConvoySummary }`.
 *    Removes the CALLER from a convoy they have ACCEPTED: clear their
 *    `members.{uid}` entry (or mark it `left`) so they drop out of `memberUids`
 *    and `livePositionUids` and stop receiving convoy chat. The OWNER must be
 *    refused (`failed-precondition`) and told to use `convoy.end` — an owner
 *    leaving would orphan the convoy. A non-member / unknown convoy gets
 *    `not-found` (never `permission-denied`), matching respond/start/end so a
 *    convoy cannot be probed. Already-ended → `failed-precondition`. Idempotent:
 *    leaving twice is not an error worth surfacing.
 *  - `convoy.invite` (grouped export `convoy-invite`), payload
 *    `{ convoyId: string, inviteeUids: string[] }` →
 *    `{ convoy, invited: string[], skipped: SkippedInvitee[] }`. Same friend-only
 *    gate and same neutral skip reasons as `convoy.create`, restricted to a
 *    convoy that has not ended. Whether non-owner members may invite is a product
 *    decision; the client is happy either way (owner-only would simply gate the
 *    button on [ConvoyBarState.viewerIsOwner]).
 *
 * When either lands, the only client change is flipping the corresponding
 * [ConvoyBarActionAvailability] here and handing the bar a lambda.
 */

/** Whether a convoy-bar action can actually reach a backend today. */
enum class ConvoyBarActionAvailability {
    /** A callable exists and is wired — the control runs for real. */
    Wired,

    /**
     * No callable exists for this action yet. The control is still RENDERED, but
     * disabled and paired with a short honest explanation: unlike a report (which
     * must never *look* filed), "leave" and "invite" are affordances the user is
     * actively looking for while sitting in a convoy, and an absent button reads
     * as "this app can't do that at all" rather than "not yet".
     */
    BackendMissing,
}

/** The one-line honest explanation the bar shows under its actions, if any. */
enum class ConvoyBarNotice {
    /** Every rendered action is wired — no explanation needed. */
    None,

    /** Only inviting is missing (the owner, whose End action IS wired). */
    InviteMissing,

    /** Both inviting and leaving are missing (a non-owner member). */
    InviteAndLeaveMissing,
}

/**
 * Everything the convoy status bar renders. `null` (rather than an empty
 * instance) is how "not in a convoy" is expressed, so the bar cannot accidentally
 * render as a blank strip or a placeholder.
 *
 * @param memberCount ACCEPTED members only — the people actually in the convoy.
 *   Invited-but-unanswered and declined people are not in it, and counting them
 *   would overstate the group to a driver glancing at the bar.
 * @param viewerIsOwner drives the leave-vs-end split: the owner has no "leave",
 *   only "end this convoy for everyone".
 * @param busy true while a mutation for this convoy is in flight, so the actions
 *   disable rather than fire twice.
 */
data class ConvoyBarState(
    val convoyId: String,
    val memberCount: Int,
    val viewerIsOwner: Boolean,
    val busy: Boolean,
    val inviteAvailability: ConvoyBarActionAvailability,
    val leaveAvailability: ConvoyBarActionAvailability,
) {
    /** The explanation line, derived from the two availabilities. */
    val notice: ConvoyBarNotice
        get() =
            when {
                inviteAvailability == ConvoyBarActionAvailability.Wired &&
                    leaveAvailability == ConvoyBarActionAvailability.Wired -> ConvoyBarNotice.None
                leaveAvailability == ConvoyBarActionAvailability.BackendMissing ->
                    ConvoyBarNotice.InviteAndLeaveMissing
                else -> ConvoyBarNotice.InviteMissing
            }
}

object ConvoyBar {
    /**
     * Inviting into an existing convoy has no callable — see the file KDoc for the
     * `convoy.invite` contract it is waiting on. Applies to owner and member
     * alike, so it is a constant rather than a per-role decision.
     */
    val inviteAvailability: ConvoyBarActionAvailability = ConvoyBarActionAvailability.BackendMissing

    /**
     * Whether the caller's leave/end control can run.
     *
     * The OWNER's control is `convoy-end`, which exists — it ends the convoy for
     * everyone, which is why the UI labels it "End convoy" and confirms first.
     * A MEMBER's "leave" has no callable at all, and must NOT fall back to
     * `convoy-end` (owner-only, and group-wide) or to `convoy-respond` (which
     * only answers a still-pending invite).
     */
    fun leaveAvailability(viewerIsOwner: Boolean): ConvoyBarActionAvailability =
        if (viewerIsOwner) {
            ConvoyBarActionAvailability.Wired
        } else {
            ConvoyBarActionAvailability.BackendMissing
        }

    /**
     * The convoy the bar should describe, or null when the caller is not in one
     * (which is how the bar is hidden entirely).
     *
     * Picks the single most relevant convoy when the caller is in more than one:
     * an `Active` convoy — one actually being driven — outranks a `Forming` one
     * that is still gathering. Within a status the backend's `createdAt desc`
     * ordering decides, so the newest wins.
     */
    fun activeConvoy(status: ConvoyListStatus): ConvoySummary? {
        val loaded = status as? ConvoyListStatus.Loaded ?: return null
        val joined =
            loaded.convoys.filter { convoy ->
                convoy.status != ConvoyStatus.Ended &&
                    convoy.viewer?.inviteStatus == ConvoyInviteStatus.Accepted
            }
        return joined.firstOrNull { it.status == ConvoyStatus.Active } ?: joined.firstOrNull()
    }

    /** Accepted members only — the people actually in [convoy]. */
    fun memberCount(convoy: ConvoySummary): Int =
        convoy.members.count { it.inviteStatus == ConvoyInviteStatus.Accepted }

    /**
     * The full bar state, or null when the bar must not render at all.
     *
     * [busyConvoys] is the coordinator's in-flight set, so the End action greys
     * out while its callable runs instead of being tappable twice.
     */
    fun stateFor(status: ConvoyListStatus, busyConvoys: Set<String> = emptySet()): ConvoyBarState? {
        val convoy = activeConvoy(status) ?: return null
        return ConvoyBarState(
            convoyId = convoy.convoyId,
            memberCount = memberCount(convoy),
            viewerIsOwner = convoy.viewerIsOwner,
            busy = convoy.convoyId in busyConvoys,
            inviteAvailability = inviteAvailability,
            leaveAvailability = leaveAvailability(convoy.viewerIsOwner),
        )
    }
}
