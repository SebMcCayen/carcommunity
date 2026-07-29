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
 * ## The three affordances are all deployed
 * The convoy surface now includes `convoy-invite` and `convoy-leave` alongside
 * `convoy-create` / `convoy-respond` / `convoy-start` / `convoy-end` /
 * `convoy-list`, so every control the bar renders is [Wired]:
 *
 *  - **Owner's End** is `convoy-end` — owner-only, ends the convoy *for everyone*
 *    and stores the shared summary. It is emphatically NOT "leave"; a member must
 *    never reach it.
 *  - **Member's Leave** is `convoy-leave` — removes only the CALLER (clears their
 *    `members.{uid}` entry so they drop out of `memberUids` / `livePositionUids`
 *    and convoy chat). The owner is refused server-side and told to use End.
 *  - **Invite** is `convoy-invite` — any accepted member may add their friends to
 *    the EXISTING convoy (`{ convoyId, inviteeUids[] }` → `{ convoy, invited,
 *    skipped }`), which is why the bar's invite button opens the friend picker
 *    and grows *this* convoy rather than pointing at create (which would spawn a
 *    second one).
 *
 * The controls still gate on TWO halves — the availability flag AND the presence
 * of a handler — so a flag alone can never produce a live button that does
 * nothing, and the leave-vs-end split routes on [ConvoyBarState.viewerIsOwner],
 * not on the availability flag, so a member's Leave tap is closed off from the
 * group-wide End structurally (see [ConvoyStatusBar]).
 */

/** Whether a convoy-bar action can actually reach a backend. */
enum class ConvoyBarActionAvailability {
    /** A callable exists and is wired — the control runs for real. */
    Wired,

    /**
     * No callable exists for this action. Kept for the general shape (a control
     * that renders but is disabled with an honest label), though every convoy-bar
     * action is [Wired] today. A control that lands here is still RENDERED, but
     * disabled and paired with a short "…unavailable" description rather than
     * silently absent or wired to a callable that does something else.
     */
    BackendMissing,
}

/** The one-line honest explanation the bar shows under its actions, if any. */
enum class ConvoyBarNotice {
    /** Every rendered action is wired — no explanation needed (the case today). */
    None,

    /** Only inviting is missing. */
    InviteMissing,

    /** Only leaving is missing. */
    LeaveMissing,

    /** Both inviting and leaving are missing. */
    InviteAndLeaveMissing,
}

/**
 * One accepted member as the bar's member-list popup needs them: a display name
 * (nullable — resolved server-side, absent until a profile lands) and the raw
 * avatar storage path (nullable). Deliberately a slimmed-down projection of
 * [ConvoyMember] so the bar's popup depends on just what it shows, not the whole
 * membership record.
 */
data class ConvoyBarMember(
    val uid: String,
    val displayName: String?,
    val avatarPath: String?,
)

/**
 * Everything the convoy status bar renders. `null` (rather than an empty
 * instance) is how "not in a convoy" is expressed, so the bar cannot accidentally
 * render as a blank strip or a placeholder.
 *
 * @param members the ACCEPTED members, in roster order — the people actually in
 *   the convoy. Invited-but-unanswered and declined people are not in it. Drives
 *   both the member count shown on the bar ([memberCount], derived from this) and
 *   the tap-to-open member-list popup, so the count and the list are ONE source of
 *   truth and cannot disagree.
 * @param viewerIsOwner drives the leave-vs-end split: the owner has no "leave",
 *   only "end this convoy for everyone".
 * @param busy true while a mutation for this convoy is in flight, so the actions
 *   disable rather than fire twice.
 */
data class ConvoyBarState(
    val convoyId: String,
    val members: List<ConvoyBarMember>,
    val viewerIsOwner: Boolean,
    val busy: Boolean,
    val inviteAvailability: ConvoyBarActionAvailability,
    val leaveAvailability: ConvoyBarActionAvailability,
    /**
     * The SHARED-destination row's state (none / set by me / set by someone
     * else). Its availability is gated separately in [ConvoyDestinations],
     * because it waits on a different pair of callables — see the
     * [ConvoyDestination] file KDoc for that contract.
     */
    val destinationState: ConvoyDestinationState = ConvoyDestinationState.None,
    /** Whether the viewer may clear the current destination (setter or owner). */
    val canClearDestination: Boolean = false,
    /**
     * Unread messages in THIS convoy's chat for the viewer, badging the bar's chat
     * icon. Defaults to 0 (nothing to show), which is also what a config-less
     * build and a host that hasn't wired the count supply.
     *
     * SATURATING: the source counts within a bounded newest-message window, so a
     * very busy chat reports that window's size rather than the true backlog —
     * which is why the badge caps its display below it ([ConvoyBar.unreadBadgeLabel]).
     */
    val unreadChatCount: Int = 0,
) {
    /**
     * ACCEPTED member count, DERIVED from [members] so the number on the bar and
     * the list behind it can never drift out of sync (a stored count could be set
     * to 2 with an empty list, leaving the popup blank under a "2").
     */
    val memberCount: Int
        get() = members.size

    /**
     * The explanation line, derived from BOTH availabilities independently. Both
     * `convoy-invite` and `convoy-leave` are deployed, so this is [ConvoyBarNotice.None]
     * in practice; the per-availability derivation is kept so that if either flag
     * were ever set back to [ConvoyBarActionAvailability.BackendMissing] the notice
     * still names exactly what is missing rather than overclaiming from one flag.
     */
    val notice: ConvoyBarNotice
        get() {
            val inviteMissing = inviteAvailability == ConvoyBarActionAvailability.BackendMissing
            val leaveMissing = leaveAvailability == ConvoyBarActionAvailability.BackendMissing
            return when {
                inviteMissing && leaveMissing -> ConvoyBarNotice.InviteAndLeaveMissing
                inviteMissing -> ConvoyBarNotice.InviteMissing
                leaveMissing -> ConvoyBarNotice.LeaveMissing
                else -> ConvoyBarNotice.None
            }
        }
}

object ConvoyBar {
    /**
     * The largest unread count the chat badge prints as a NUMBER; anything more
     * renders as "9+".
     *
     * A cap is not cosmetic here. The bar is a single compact line that already
     * shares the map shell's search row with the member count, focus, invite and
     * leave/end controls, so a badge that grew to three or four digits would push
     * those controls out — and on a long-running convoy an uncapped number is
     * exactly what it would do. One digit is also all the information the badge
     * owes: past a handful, "some" and "a lot" lead to the same action (open the
     * chat).
     *
     * The count's own window is sized one ABOVE this (see
     * FirebaseConvoyChatRepository), so the badge never has to distinguish a
     * saturated count from a real one.
     */
    const val UNREAD_DISPLAY_MAX = 9

    /**
     * What the chat badge prints for [count], or null when there is NOTHING to
     * badge — the zero case is a null, not a "0", so the caller omits the badge
     * entirely rather than drawing an empty one.
     */
    fun unreadBadgeLabel(count: Int): String? = when {
        // Defensive on negatives as well as zero: a badge is an invitation to
        // open the chat, and there is nothing to open for either.
        count <= 0 -> null
        count > UNREAD_DISPLAY_MAX -> "$UNREAD_DISPLAY_MAX+"
        else -> count.toString()
    }

    /**
     * Inviting into an existing convoy is now backed by the deployed
     * `convoy-invite` callable (any accepted member may invite their friends), so
     * this is [Wired] for owner and member alike — a constant rather than a
     * per-role decision. The control still only enables once a host also supplies
     * an `onInvite` handler (see [ConvoyStatusBar]), so the flag alone can't
     * produce a live button that does nothing.
     */
    val inviteAvailability: ConvoyBarActionAvailability = ConvoyBarActionAvailability.Wired

    /**
     * Whether the caller's leave/end control can run.
     *
     * Both are now [Wired]: the OWNER's control is `convoy-end` (ends the convoy
     * for everyone — labelled "End convoy" and confirmed first), and a MEMBER's
     * "leave" is the deployed `convoy-leave` callable (removes only the caller).
     * The bar still routes the trailing control on `viewerIsOwner`, so a member's
     * tap reaches `onLeaveConvoy` and can never fall through to the owner-only,
     * group-wide `convoy-end`. Because both roles are `Wired`, this is a constant
     * (like [inviteAvailability]) — it takes no role argument.
     */
    val leaveAvailability: ConvoyBarActionAvailability = ConvoyBarActionAvailability.Wired

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

    /** Accepted members only — the people actually in [convoy], in roster order. */
    fun acceptedMembers(convoy: ConvoySummary): List<ConvoyBarMember> =
        convoy.members
            .filter { it.inviteStatus == ConvoyInviteStatus.Accepted }
            .map { ConvoyBarMember(uid = it.uid, displayName = it.displayName, avatarPath = it.avatarPath) }

    /**
     * The full bar state, or null when the bar must not render at all.
     *
     * [busyConvoys] is the coordinator's in-flight set, so the End action greys
     * out while its callable runs instead of being tappable twice.
     *
     * [unreadChatCount] is threaded in rather than derived, because it comes from
     * a live listener the HOST owns: the host is what knows whether the bar can
     * be seen at all, and the count must not be subscribed when it can't.
     */
    fun stateFor(
        status: ConvoyListStatus,
        busyConvoys: Set<String> = emptySet(),
        viewerUid: String? = null,
        unreadChatCount: Int = 0,
    ): ConvoyBarState? {
        val convoy = activeConvoy(status) ?: return null
        val accepted = acceptedMembers(convoy)
        return ConvoyBarState(
            convoyId = convoy.convoyId,
            members = accepted,
            viewerIsOwner = convoy.viewerIsOwner,
            busy = convoy.convoyId in busyConvoys,
            inviteAvailability = inviteAvailability,
            leaveAvailability = leaveAvailability,
            destinationState = ConvoyDestinations.stateFor(convoy.destination, viewerUid),
            canClearDestination =
                ConvoyDestinations.canClear(
                    destination = convoy.destination,
                    viewerUid = viewerUid,
                    viewerIsOwner = convoy.viewerIsOwner,
                ),
            unreadChatCount = unreadChatCount,
        )
    }
}
