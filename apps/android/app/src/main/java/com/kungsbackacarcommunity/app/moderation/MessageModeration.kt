package com.kungsbackacarcommunity.app.moderation

/**
 * Cross-surface message moderation (block + report) — pure Kotlin, JVM-testable.
 *
 * Every chat surface in the app long-presses a message bubble to the same action
 * sheet ([MessageActionsSheet]), but the two actions have very different backend
 * coverage:
 *
 *  - **Block** is surface-independent: it targets a USER, and the
 *    `blocking-block` / `blocking-unblock` callables exist and are wired
 *    everywhere via [com.kungsbackacarcommunity.app.blocking.BlockingRepository].
 *  - **Report** is per-surface: EVENT chat (`events-reportChatMessage`) and the
 *    global community channel (`chatchannels-reportMessage`, channel: 'community')
 *    are wired. Convoy channels (the same `chatchannels-reportMessage` callable,
 *    channel: 'convoy') and DMs (`dm.reportMessage`) have a backend but no Android
 *    wiring yet, so the action is NOT RENDERED there — an action that cannot run is
 *    not an action. A permanently dead button invites "report is broken" bug
 *    reports and teaches users the feature is unreliable; an absent one simply
 *    appears the day its route passes a submit lambda.
 *
 * [reportAvailability] (and [reportUserAvailability] for the profile action) is
 * the single source of truth for that split, so bringing an unwired surface
 * online is just flipping it to [ReportAvailability.Wired] here plus handing the
 * route a submit lambda (and, for a surface whose backend does not exist yet,
 * building that callable first). Call sites must never hardcode a surface check
 * of their own.
 *
 * What each `ReportAvailability.BackendMissing` surface is waiting on. NOTE the
 * name is historical: it means "report not wired on this surface", which today
 * is TWO cases — a backend that already EXISTS but has no Android client wiring
 * yet (convoy, DMs), and a backend not yet BUILT (the profile "report user"):
 *
 *  - `chatchannels.reportMessage` (grouped export `chatchannels-reportMessage`,
 *    europe-west1, auth + App Check), payload:
 *    `{ channel: 'community' | 'convoy', convoyId?: string, messageId: string,
 *       reason: <CHAT_MESSAGE_REPORT_REASONS>, details?: string (<=500) }`
 *    → `{ reported: true }`. `convoyId` required iff `channel === 'convoy'`. EXISTS
 *    (community is wired); convoy only needs the Android route wired.
 *  - `dm.reportMessage` (grouped export `dm-reportMessage`, same guards),
 *    payload: `{ conversationId: string, messageId: string, reason, details? }`
 *    → `{ reported: true }`. EXISTS — only needs the Android route wired.
 *  - `moderation.reportUser` (for the member-profile "Report user" action),
 *    payload: `{ reportedUserId: string, reason, details? }` → `{ reported: true }`.
 *    NOT BUILT yet — a genuine backend gap.
 *
 * All three should mirror `functions/src/events/reportChatMessage.ts`: reporter
 * eligibility = read eligibility for that surface, own content is unreportable,
 * dedupe per (target, reporter, reason) via a deterministic doc id, reports are
 * never client-readable, and the response never reveals whether a prior report
 * existed. The reason enum is already shared —
 * [com.kungsbackacarcommunity.app.chat.ChatReportReason] mirrors the backend's
 * `CHAT_MESSAGE_REPORT_REASONS`.
 */

/** The chat surface a long-pressed message lives on. */
enum class ChatSurface {
    EventChat,
    CommunityChannel,
    ConvoyChannel,
    DirectMessage,
}

/** Whether "Report message" can actually reach a backend on a given surface. */
enum class ReportAvailability {
    /** A report callable exists and is wired — the reason picker submits for real. */
    Wired,

    /**
     * No report backend exists for this surface yet, so the action is not
     * rendered at all: a report the client cannot file must never look like one
     * it filed, and a permanently disabled row is just a slower way of saying
     * the same thing to a user who only wanted the feature to work.
     */
    BackendMissing,
}

object MessageModeration {
    /**
     * Whether reporting a MESSAGE is wired for [surface]. Event chat
     * (`events-reportChatMessage`) and the community channel
     * (`chatchannels-reportMessage`, channel: 'community') are wired today; the
     * convoy channel and DMs are not yet — see the comment on the `when` branches
     * below for the callables they are waiting on.
     */
    fun reportAvailability(surface: ChatSurface): ReportAvailability =
        when (surface) {
            // Event chat (`events-reportChatMessage`) and the community channel
            // (`chatchannels-reportMessage`, channel: 'community') both have a wired
            // report callable. Convoy shares the same callable but its Android entry
            // point isn't wired yet, and DMs (`dm.reportMessage`) have no client
            // wiring either — both stay hidden until their route passes a submit lambda.
            ChatSurface.EventChat,
            ChatSurface.CommunityChannel,
            -> ReportAvailability.Wired
            ChatSurface.ConvoyChannel,
            ChatSurface.DirectMessage,
            -> ReportAvailability.BackendMissing
        }

    /**
     * Whether reporting a USER from their member profile is wired. Surface-less
     * (a user is reported as a person, not as a message), hence its own switch
     * rather than a [ChatSurface] entry. No `moderation.reportUser` callable
     * exists yet, so the profile's report row is not rendered; flipping this to
     * [ReportAvailability.Wired] is all it takes to bring it back.
     */
    val reportUserAvailability: ReportAvailability = ReportAvailability.BackendMissing

    /**
     * Whether the long-press sheet has ANY action to offer, and is therefore
     * worth opening at all.
     *
     * With reporting hidden rather than disabled, a surface whose report has no
     * backend AND whose block is unwired ([canBlock] false, i.e. a config-less
     * build) would otherwise open a sheet containing nothing but its own Close
     * button. Long-press then simply does nothing, which is the honest outcome.
     */
    fun hasActions(canBlock: Boolean, reportAvailability: ReportAvailability): Boolean =
        canBlock || reportAvailability == ReportAvailability.Wired

    /**
     * Whether the caller may open moderation actions on a message by [authorUid].
     *
     * False for the caller's OWN messages — you can neither block nor report
     * yourself, and the backend rejects both — and false for a blank author uid,
     * which a malformed message can carry and which would target nobody.
     */
    fun canActOn(authorUid: String, currentUid: String): Boolean =
        authorUid.isNotBlank() && authorUid != currentUid
}
