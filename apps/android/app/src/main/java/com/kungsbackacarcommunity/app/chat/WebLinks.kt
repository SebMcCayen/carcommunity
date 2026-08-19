package com.kungsbackacarcommunity.app.chat

/**
 * The single source of truth for **auto-linkifying web URLs a user pastes into a
 * chat message** — the plain `http://…` / `https://…` substrings the chat renderers
 * detect and turn into tappable links that open the phone's default browser.
 *
 * Modelled deliberately on [com.kungsbackacarcommunity.app.location.GeoLinks] and
 * [com.kungsbackacarcommunity.app.events.EventShareLinks]: one place holds the
 * detection rules (the regex + the trailing-punctuation trim), so every chat surface
 * (community + convoy channels, event chat, direct messages) linkifies identically,
 * and the whole thing is a plain JVM object with NO Compose/Android dependency, so it
 * is unit-testable off-device.
 *
 * ## Scheme allowlist — HTTP/HTTPS ONLY
 * Only `http://` and `https://` URLs are ever detected. Dangerous or app-hijacking
 * schemes — `tel:`, `mailto:`, `intent:`, `javascript:`, `file:`, `content:`, `sms:`,
 * `data:`, custom app schemes, bare `www.…` with no scheme — are **not** matched, so
 * they render as plain text and can never be turned into a tap target. This is the
 * security boundary: a message can only ever produce a link to a web address, never
 * to something that could dial a number, run script, or read a local file. The tap
 * handler that consumes a match still opens it via a plain `ACTION_VIEW`, so the OS
 * resolves the user's default browser (or a chooser) — nothing is ever auto-opened.
 *
 * ## What counts as a URL
 * A `https?://` scheme (case-insensitive) followed by at least one non-space,
 * non-`<>` character, not immediately preceded by a letter/digit (so `xhttp://…` and
 * an already-embedded scheme are not mistaken for a fresh link). Trailing sentence
 * punctuation (`. , ; : ! ? ) ] } > " '`) is trimmed off the end of the match so
 * "see https://example.com." links `https://example.com` and leaves the full stop as
 * plain text; a closing `)` is kept only when a `(` opens inside the URL (so a
 * Wikipedia-style `…_(disambiguation)` URL survives intact).
 */
data class WebLink(
    val url: String,
)

/** A [WebLink] found in a longer string, welded to the exact [range] it occupies. */
data class WebLinkMatch(
    val range: IntRange,
    val link: WebLink,
)

object WebLinks {
    /**
     * Matches an `http://` or `https://` URL. Case-insensitive on the scheme only.
     * The body is a greedy run of non-whitespace, non-angle-bracket characters; the
     * trailing-punctuation trim in [findAll] then pares back any sentence punctuation
     * the greedy run swallowed. A preceding letter/digit is excluded (the same guard
     * GeoLinks/EventShareLinks use) so a scheme embedded in a larger token is not a
     * match.
     */
    private val TOKEN = Regex("""(?<![A-Za-z0-9])(?i:https?)://[^\s<>]+""")

    /** Trailing characters pared off the end of a greedy URL match (sentence punctuation). */
    private const val TRAILING_TRIM = ".,;:!?)]}>\"'"

    /**
     * Every http/https URL in [text], in order, each with the exact character range it
     * occupies (with trailing sentence punctuation trimmed off, so a renderer links
     * only the address itself). Returns an empty list when there is nothing to find —
     * the common case, kept cheap via a fast substring pre-check. A match whose body
     * is empty after the scheme (e.g. a bare `http://` with nothing after it) is
     * dropped, so only real addresses are linkified.
     */
    fun findAll(text: String): List<WebLinkMatch> {
        // Cheap fast-path: no `://` means no possible http(s) URL.
        if (!text.contains("://")) return emptyList()
        return TOKEN.findAll(text)
            .mapNotNull { match ->
                val trimmed = trimTrailing(match.value)
                // Nothing left after `http(s)://` → not an address, skip it.
                if (!hasHost(trimmed)) return@mapNotNull null
                val range = IntRange(match.range.first, match.range.first + trimmed.length - 1)
                WebLinkMatch(range = range, link = WebLink(trimmed))
            }
            .toList()
    }

    /**
     * Pares sentence punctuation off the end of [raw] so "https://x.com." links
     * `https://x.com`. A closing `)` is kept when a `(` opens inside the URL so a
     * balanced-parens URL (Wikipedia disambiguation pages) survives; an unbalanced
     * trailing `)` is trimmed like the other punctuation.
     */
    private fun trimTrailing(raw: String): String {
        var end = raw.length
        while (end > 0) {
            val c = raw[end - 1]
            if (c == ')') {
                val opens = raw.count { it == '(' }
                val closes = raw.take(end).count { it == ')' }
                if (closes <= opens) break // balanced — keep this ')'
            } else if (TRAILING_TRIM.indexOf(c) < 0) {
                break
            }
            end--
        }
        return raw.substring(0, end)
    }

    /** True when [url] carries at least one character after its `http(s)://` scheme. */
    private fun hasHost(url: String): Boolean {
        val slashes = url.indexOf("://")
        if (slashes < 0) return false
        return url.length > slashes + 3
    }
}
