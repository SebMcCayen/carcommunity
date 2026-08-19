package com.kungsbackacarcommunity.app.chat

/**
 * Reconciles the SEPARATE link matchers a chat renderer runs over one message — web
 * URLs ([WebLinks]), shared locations (`geo:`), shared events (`kccevent:`), plus (in
 * the group channels) @mention spans — into a single, strictly non-overlapping,
 * in-order list.
 *
 * ## Why this is needed
 * The matchers are independent and their ranges are NOT guaranteed disjoint: a pasted
 * web URL can legitimately contain a `geo:`- or `kccevent:`-looking substring in its
 * path (`https://example.com/geo:59.1,18.2`), and each matcher only requires a
 * non-alphanumeric boundary before its scheme — so `GeoLinks`/`EventShareLinks` will
 * ALSO match the substring nested inside the URL. A naive render loop that assumes
 * disjoint matches can then move its output cursor BACKWARDS onto the nested match and
 * duplicate/misorder the text. [nonOverlapping] removes that hazard once, up front.
 */
object ChatLinkSpans {
    /**
     * [matches] sorted by start position with any match that overlaps an
     * already-kept earlier match dropped — so the survivors are strictly
     * non-overlapping and ascending. The **earliest-starting** match wins, so an
     * outer web URL keeps its whole span and a false `geo:`/`kccevent:` match nested
     * inside it is discarded (the URL starts first). A renderer walking the result
     * therefore only ever advances its cursor.
     */
    fun <T> nonOverlapping(matches: List<T>, range: (T) -> IntRange): List<T> {
        if (matches.size <= 1) return matches
        val sorted = matches.sortedBy { range(it).first }
        val kept = ArrayList<T>(sorted.size)
        var cursor = -1 // last index already consumed by a kept span
        for (match in sorted) {
            val r = range(match)
            if (r.first <= cursor) continue // overlaps the previous (outer) span → drop
            kept.add(match)
            cursor = r.last
        }
        return kept
    }
}
