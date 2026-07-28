package com.kungsbackacarcommunity.app.notifications

/**
 * What a convoy-referencing inbox row means RIGHT NOW, and where tapping it
 * should land.
 *
 * WHY THIS EXISTS
 * ---------------
 * A notification is an immutable historical record. "You were invited to a
 * convoy" stays in the inbox forever, and is never rewritten when the convoy
 * ends or the invite is answered — exactly the same shape as the friend-request
 * staleness problem [Notifications.pendingFriendRequestId] already solves. So
 * the row's CURRENT meaning cannot be read off the notification; it has to be
 * re-derived against live convoy state every time it is rendered.
 *
 * Deliberately expressed in this package's own vocabulary ([ConvoyFacts]) rather
 * than in the convoy domain's `ConvoySummary`. The inbox does not need a convoy
 * — it needs two booleans per convoy id — and taking the whole type would drag
 * the notifications package into the convoy package's model for no gain. The
 * shell does the one-line projection at the wiring point.
 *
 * Pure and total: no I/O, no exceptions, no Compose. The stale/fresh/ended
 * distinction is therefore unit-testable without Firestore or a device, which is
 * the point — it is the part that was wrong on the device.
 */

/**
 * The live facts about ONE convoy, as the convoy list snapshot already knows
 * them. Both fields come from the same `convoy-list` response the shell's convoy
 * bar is already holding, so resolving a row costs no read of its own.
 */
data class ConvoyFacts(
    /** The convoy has been ended by its owner (status `ended`). */
    val ended: Boolean,
    /**
     * The VIEWER's own invite to this convoy is still unanswered. False once
     * they have accepted or declined, and false for a convoy they own.
     */
    val inviteOpen: Boolean,
)

/**
 * How one inbox row stands against live convoy state.
 *
 * [UNRESOLVED] is a first-class outcome rather than an error: the convoy list is
 * bounded (the newest 200 the member belongs to), and it may not have loaded
 * yet, so "I have no facts about this convoy" is normal. It must NOT be confused
 * with "ended" — claiming a convoy is over because we happen not to be holding
 * it would put a strikethrough on a live invite, which is a worse failure than
 * the one being fixed. Unresolved rows therefore keep behaving exactly as an
 * active row does, and the destination re-checks on arrival.
 */
enum class ConvoyRowState {
    /** Not a convoy row at all (or no convoy id on it). */
    NOT_CONVOY,

    /** A convoy row whose convoy we hold no facts about. Treated as live. */
    UNRESOLVED,

    /** Live convoy, invite still unanswered — the actionable case. */
    INVITE_OPEN,

    /** Live convoy, but this invite has already been accepted or declined. */
    INVITE_ANSWERED,

    /** The convoy has ended. The row is dead. */
    ENDED,
}

/** True when the row must be presented as dead (struck through + labelled). */
val ConvoyRowState.isDead: Boolean
    get() = this == ConvoyRowState.ENDED

/** What tapping a row should do, beyond the mark-read it already does. */
sealed interface NotificationTapAction {
    /** Nothing to open. The tap still marks the row read. */
    data object None : NotificationTapAction

    /**
     * Open the convoy list, where the pending invite is accepted or declined.
     * [convoyId] is null when the notification carries no usable id — the list
     * is then still the right landing (the invite is on it), which is why this
     * degrades rather than doing nothing.
     */
    data class OpenConvoyInvite(val convoyId: String?) : NotificationTapAction

    /**
     * The convoy is over. Navigating anywhere would land on a screen with
     * nothing on it, so the row explains itself in place instead — see
     * [ConvoyRowState.ENDED], which puts the reason on the row permanently.
     */
    data object ConvoyEnded : NotificationTapAction
}

object ConvoyNotifications {
    /** Categories whose `relatedEntityId` is a convoy id. */
    private val CONVOY_CATEGORIES =
        setOf(NotificationCategory.CONVOY_INVITE, NotificationCategory.CONVOY_CHAT)

    /** The convoy this row is about, or null when it is not about one. */
    fun convoyId(item: AppNotification): String? =
        if (item.category in CONVOY_CATEGORIES) {
            item.relatedEntityId?.takeIf { it.isNotBlank() }
        } else {
            null
        }

    /**
     * The row's current state, derived from live convoy facts.
     *
     * A convoy CHAT row can only be live or ended — there is no invite on it —
     * so it never reports [ConvoyRowState.INVITE_OPEN]/[ConvoyRowState.INVITE_ANSWERED].
     */
    fun rowState(item: AppNotification, facts: Map<String, ConvoyFacts>): ConvoyRowState {
        val id = convoyId(item) ?: return ConvoyRowState.NOT_CONVOY
        val fact = facts[id] ?: return ConvoyRowState.UNRESOLVED
        if (fact.ended) return ConvoyRowState.ENDED
        if (item.category != NotificationCategory.CONVOY_INVITE) return ConvoyRowState.UNRESOLVED
        return if (fact.inviteOpen) ConvoyRowState.INVITE_OPEN else ConvoyRowState.INVITE_ANSWERED
    }

    /**
     * Where tapping this row goes.
     *
     * An ANSWERED invite still navigates: the convoy is live and the member is
     * (or chose not to be) part of it, so the list is a truthful place to land —
     * it is only the "there is something waiting for you" implication that is
     * withdrawn, and the row's label does that. An ENDED one navigates nowhere.
     *
     * A convoy CHAT row is resolved (so an ended convoy's chat row is struck
     * through like any other) but does not navigate. Its destination is a tab
     * INSIDE the chat hub, whose selected tab is seeded once on entry — so
     * opening it from a row that is itself already inside the hub would change
     * nothing on screen, which is the very failure this whole change exists to
     * remove. Wiring it properly means reworking the hub's tab state, which is
     * not this fix.
     */
    fun tapAction(item: AppNotification, state: ConvoyRowState): NotificationTapAction =
        when {
            state == ConvoyRowState.ENDED -> NotificationTapAction.ConvoyEnded
            item.category == NotificationCategory.CONVOY_INVITE ->
                NotificationTapAction.OpenConvoyInvite(convoyId(item))
            else -> NotificationTapAction.None
        }

    /** Convenience: state + action in one pass, for a row being composed. */
    fun tapAction(item: AppNotification, facts: Map<String, ConvoyFacts>): NotificationTapAction =
        tapAction(item, rowState(item, facts))

    /** True when a tap on this row would navigate somewhere. */
    fun navigates(action: NotificationTapAction): Boolean =
        action !is NotificationTapAction.None && action !is NotificationTapAction.ConvoyEnded
}

/**
 * The inbox's convoy wiring, bundled so every surface that hosts the inbox (the
 * Notifications route, the chat hub route, the chat hub popup) threads ONE
 * nullable parameter instead of two that could drift apart.
 *
 * Null in a config-less build — the inbox then renders exactly as it did before,
 * with no convoy resolution and no navigation, and costs nothing.
 */
data class ConvoyNotificationLink(
    /** convoyId -> live facts, projected from the convoy list the shell holds. */
    val facts: Map<String, ConvoyFacts>,
    /** Performs a [NotificationTapAction]. Never called for None/ConvoyEnded. */
    val onOpen: (NotificationTapAction) -> Unit,
)
