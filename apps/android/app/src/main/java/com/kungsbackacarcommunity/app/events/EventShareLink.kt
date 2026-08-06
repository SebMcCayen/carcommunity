package com.kungsbackacarcommunity.app.events

/**
 * The single source of truth for the app's **in-app event share link** — a
 * `kccevent:<eventId>` token that the friend-picker share ([EventShareCoordinator])
 * writes into a direct message and the recipient's chat renderer detects and turns
 * into a tappable "Open event" chip. Tapping it drives the SAME
 * `pendingEventDeepLinkId → EventsRoute.initialEventId` navigation an
 * event-reminder push tap takes (see `openEventFromNotification` in
 * AuthenticatedApp), so the recipient lands on THAT event's detail page.
 *
 * Modelled deliberately on [com.kungsbackacarcommunity.app.location.GeoLinks]: one
 * place holds the token format (the regex + the build/parse functions), so the
 * message builder and the chat detector can never disagree about what a valid
 * token looks like, and the whole thing is JVM-unit-testable off-Compose. There is
 * NO new backend, callable, or message type — an event travels as an ordinary DM
 * whose body carries this token, exactly as a shared location travels as a `geo:`
 * token.
 *
 * ## The format
 * `kccevent:<eventId>` — the event's Firestore document id. A human 🎟️ prefix may
 * sit OUTSIDE the token for readability in a client that does not linkify it;
 * detection is unaffected. A leading letter/digit is excluded so `akccevent:x` is
 * not mistaken for a link.
 *
 * ## Validation
 * [parse] accepts only a plausible Firestore document id — the `[A-Za-z0-9_-]`
 * charset, 1..[MAX_ID_LENGTH] characters. Anything else is simply not a match, so
 * chat renders it as plain text and no bogus event is ever opened.
 */
data class EventShareLink(
    val eventId: String,
)

/** An [EventShareLink] found in a longer string, welded to the [range] it occupies. */
data class EventShareLinkMatch(
    val range: IntRange,
    val link: EventShareLink,
)

object EventShareLinks {
    /** The URI-style scheme that prefixes every event share token. */
    const val SCHEME = "kccevent:"

    /**
     * The most characters an event id may carry and still be treated as a link.
     * Firestore auto-ids are 20 chars; a generous cap catches custom ids while
     * still rejecting a runaway token as garbage.
     */
    const val MAX_ID_LENGTH = 128

    /**
     * Matches a `kccevent:<id>` token. The id charset is the Firestore
     * document-id-safe `[A-Za-z0-9_-]` (which also stops the match from swallowing
     * following whitespace/punctuation). A preceding letter/digit is excluded so an
     * id embedded in a larger word is not mistaken for a link.
     */
    private val TOKEN =
        Regex("""(?<![A-Za-z0-9])kccevent:([A-Za-z0-9_-]{1,$MAX_ID_LENGTH})""")

    /** The bare `kccevent:<eventId>` token for [eventId]. */
    fun format(eventId: String): String = "$SCHEME$eventId"

    /**
     * Parses a single `kccevent:<id>` [token] into a validated [EventShareLink], or
     * null when it is not a well-formed, sensibly-bounded event id.
     */
    fun parse(token: String): EventShareLink? {
        val match = TOKEN.matchEntire(token.trim()) ?: return null
        return EventShareLink(match.groupValues[1])
    }

    /**
     * Every valid `kccevent:` link in [text], in order, each with the exact
     * character range it occupies (the whole `kccevent:…` token, so a renderer
     * replaces all of it). Invalid tokens are skipped, so they survive as plain
     * text. Returns an empty list when there is nothing to find — the common case,
     * kept cheap.
     */
    fun findAll(text: String): List<EventShareLinkMatch> {
        if (!text.contains(SCHEME)) return emptyList()
        return TOKEN.findAll(text)
            .map { match ->
                EventShareLinkMatch(range = match.range, link = EventShareLink(match.groupValues[1]))
            }
            .toList()
    }
}

/**
 * Pure message-building rules for sharing an event with a friend, kept Android-free
 * so the share flow (friend picker → DM send) is JVM-unit-testable off-Compose.
 *
 * A shared event is delivered as an ordinary direct message whose body carries an
 * [EventShareLinks] `kccevent:` token — the recipient's chat detects it and renders
 * a tappable "Open event" chip. So "share an event with a friend" reuses the
 * existing `dm-sendMessage` send path verbatim; this object only decides the TEXT.
 */
object EventShare {
    /**
     * The direct-message body for sharing an event: the event [title] on its own
     * line (trimmed; a blank title is dropped so the message is never a lone empty
     * line), then the `kccevent:` token with a leading 🎟️ so it reads as an event
     * even before it is linkified. The recipient's chat detects the token and turns
     * it into a tappable chip that opens the event's detail page.
     */
    fun messageText(title: String?, eventId: String): String {
        val token = "🎟️ ${EventShareLinks.format(eventId)}"
        val trimmedTitle = title?.trim().orEmpty()
        return if (trimmedTitle.isEmpty()) token else "$trimmedTitle\n$token"
    }
}
