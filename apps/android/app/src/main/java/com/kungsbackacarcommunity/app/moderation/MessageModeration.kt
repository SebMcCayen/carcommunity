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
 *  - **Report** is per-surface: only EVENT chat has a report callable today
 *    (`events-reportChatMessage`). The community channel, convoy channels and
 *    DMs have no report backend at all, so the action renders DISABLED with an
 *    explanation rather than silently pretending to file a report.
 *
 * [reportAvailability] is the single source of truth for that split, so when the
 * missing backends land the only client change is this map (plus handing the
 * route a submit lambda).
 *
 * BACKEND GAP — what `ReportAvailability.BackendMissing` is waiting on:
 *
 *  - `chatchannels.reportMessage` (grouped export `chatchannels-reportMessage`,
 *    europe-west1, auth + App Check), payload:
 *    `{ channel: 'community' | 'convoy', convoyId?: string, messageId: string,
 *       reason: <CHAT_MESSAGE_REPORT_REASONS>, details?: string (<=500) }`
 *    → `{ reported: true }`. `convoyId` required iff `channel === 'convoy'`.
 *  - `dm.reportMessage` (grouped export `dm-reportMessage`, same guards),
 *    payload: `{ conversationId: string, messageId: string, reason, details? }`
 *    → `{ reported: true }`.
 *  - `moderation.reportUser` (for the member-profile "Report user" action),
 *    payload: `{ reportedUserId: string, reason, details? }` → `{ reported: true }`.
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
     * No report backend exists for this surface yet. The action is rendered
     * disabled with an explanatory note: a report the client cannot file must
     * never look like one it filed.
     */
    BackendMissing,
}

object MessageModeration {
    /**
     * Whether reporting is wired for [surface]. Only event chat has a report
     * callable today (`events-reportChatMessage`); see the file KDoc for the
     * exact callables the other surfaces are waiting on.
     */
    fun reportAvailability(surface: ChatSurface): ReportAvailability =
        when (surface) {
            ChatSurface.EventChat -> ReportAvailability.Wired
            ChatSurface.CommunityChannel,
            ChatSurface.ConvoyChannel,
            ChatSurface.DirectMessage,
            -> ReportAvailability.BackendMissing
        }

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
