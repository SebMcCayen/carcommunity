package com.kungsbackacarcommunity.app.chatchannels

/**
 * @MENTIONS for the community channel — the pure (Firebase-free, Compose-free)
 * half: candidate sourcing, the @-query the composer autocompletes on, the
 * uid-tracking model that survives free-text editing, and the uid → span mapping
 * the renderer highlights with.
 *
 * THE LOAD-BEARING RULE (from the backend, functions/src/chatchannels): a
 * mention is a uid, never a name. displayName is NOT unique in this app, so the
 * server refuses to parse "@Seb" out of the text and instead takes an explicit
 * `mentionedUids` array that the picker resolved from a real profile. Every
 * function here exists to keep a recorded uid welded to the exact text the picker
 * inserted for it: a uid that drifts onto someone else's text pushes a stranger's
 * conversation into a stranger's inbox, which is the one failure that actually
 * hurts. When in doubt this code DROPS a mention (it degrades to plain text and
 * the user can re-pick) rather than guess.
 */

/**
 * Backend MAX_MESSAGE_MENTIONS. The server hard-REJECTS a post above this with
 * `invalid-argument` (unlike every other mention rule, which silently drops),
 * precisely because the picker is expected to enforce the same cap — so we do,
 * and the user gets a sentence instead of a failed send.
 */
const val MAX_MESSAGE_MENTIONS = 10

/** How many suggestions the picker shows at once (the list is scrollable). */
const val MENTION_PICKER_MAX_RESULTS = 8

/**
 * Longest @-query we keep the picker open for. Display names may contain a
 * space, so the query cannot simply end at whitespace; instead it tolerates at
 * most [MENTION_QUERY_MAX_SPACES] spaces and this many characters, so typing an
 * email address or a sentence after a stray "@" closes the picker instead of
 * leaving it hanging over the whole message.
 */
const val MENTION_QUERY_MAX_LENGTH = 32

/** Spaces tolerated inside an @-query (enough for "First Last"). */
const val MENTION_QUERY_MAX_SPACES = 1

/** A member the picker can insert: a uid with a non-blank, typable display name. */
data class MentionCandidate(
    val uid: String,
    val displayName: String,
    val avatarPath: String? = null,
)

/**
 * One recorded mention in a draft: the [uid] the picker resolved, the exact
 * [label] it inserted (INCLUDING the leading "@"), and the [start] offset the
 * label currently sits at. The label is stored rather than re-derived because it
 * is the invariant every remap is checked against — a span whose text no longer
 * reads back as its label is not a mention any more, it's a coincidence.
 */
data class MentionSpan(val uid: String, val label: String, val start: Int) {
    val end: Int get() = start + label.length
}

/**
 * A composer draft: the raw [text] plus the mentions currently welded to it.
 * Invariant (held by every function in [DraftMentions]): for each span,
 * `text.startsWith(span.label, span.start)`.
 */
data class MentionDraft(val text: String = "", val mentions: List<MentionSpan> = emptyList()) {
    /**
     * The uids to send, deduplicated in first-appearance order and hard-capped at
     * [MAX_MESSAGE_MENTIONS]. The cap is already enforced at insert time; taking
     * it again here means no draft state can ever reach the server's
     * `invalid-argument`.
     */
    val sendableUids: List<String>
        get() = mentions.map { it.uid }.distinct().take(MAX_MESSAGE_MENTIONS)

    /** Distinct mentioned members — what the cap counts (a repeated @Seb is one member). */
    val distinctUidCount: Int get() = mentions.distinctBy { it.uid }.size
}

/** The in-progress "@…" the caret currently sits in: its text range and search term. */
data class MentionQuery(val start: Int, val end: Int, val term: String)

/** Outcome of inserting a picked candidate into a draft. */
sealed interface MentionInsertResult {
    /** [draft] with the mention inserted; [cursor] is where the caret should land. */
    data class Inserted(val draft: MentionDraft, val cursor: Int) : MentionInsertResult

    /** Refused: the draft already names [MAX_MESSAGE_MENTIONS] distinct members. */
    data object AtCap : MentionInsertResult
}

/** The uid-tracking model: query detection, insertion, and edit survival. */
object DraftMentions {

    /**
     * The @-query the caret is inside, or null when the composer should not be
     * autocompleting. Requires a collapsed caret, an "@" that starts a token
     * (text start, or preceded by a non-alphanumeric so an email's "@" never
     * opens the picker), and a bounded, single-line term. Returns null when the
     * caret sits inside an existing mention — that text is already resolved and
     * re-suggesting over it would let one span swallow another.
     */
    fun activeQuery(
        text: String,
        cursor: Int,
        selectionEnd: Int = cursor,
        mentions: List<MentionSpan> = emptyList(),
    ): MentionQuery? {
        if (cursor != selectionEnd) return null
        if (cursor < 0 || cursor > text.length) return null
        if (mentions.any { cursor > it.start && cursor < it.end }) return null

        var at = -1
        var index = cursor - 1
        while (index >= 0) {
            val char = text[index]
            if (char == '@') {
                at = index
                break
            }
            // A newline always ends a query; nothing before it can be the anchor.
            if (char == '\n') return null
            index--
        }
        if (at < 0) return null
        if (at > 0 && text[at - 1].isLetterOrDigit()) return null
        // The anchor must not be inside an already-resolved mention.
        if (mentions.any { at >= it.start && at < it.end }) return null

        val term = text.substring(at + 1, cursor)
        if (term.length > MENTION_QUERY_MAX_LENGTH) return null
        if (term.count { it == ' ' } > MENTION_QUERY_MAX_SPACES) return null
        if (term.startsWith(' ')) return null
        return MentionQuery(start = at, end = cursor, term = term)
    }

    /**
     * Replaces [query]'s "@…" with [candidate]'s label plus a trailing space and
     * records the uid behind it. Refuses with [MentionInsertResult.AtCap] when
     * this would name an 11th distinct member (re-mentioning someone already in
     * the draft is always allowed — it costs no extra notification).
     */
    fun insert(
        draft: MentionDraft,
        query: MentionQuery,
        candidate: MentionCandidate,
    ): MentionInsertResult {
        val isNewMember = draft.mentions.none { it.uid == candidate.uid }
        if (isNewMember && draft.distinctUidCount >= MAX_MESSAGE_MENTIONS) {
            return MentionInsertResult.AtCap
        }
        val label = "@${candidate.displayName}"
        val inserted = "$label "
        val text = draft.text.replaceRange(query.start, query.end, inserted)
        val delta = inserted.length - (query.end - query.start)
        val kept =
            draft.mentions.mapNotNull { span ->
                when {
                    // Can't happen (activeQuery refuses a query overlapping a
                    // mention) but a span the replacement would have cut through
                    // drops rather than survive half-eaten.
                    span.start < query.end && span.end > query.start -> null
                    span.end <= query.start -> span
                    else -> span.copy(start = span.start + delta)
                }
            }
        val updated =
            MentionDraft(
                text = text,
                mentions = kept + MentionSpan(candidate.uid, label, query.start),
            )
        return MentionInsertResult.Inserted(
            draft = verified(updated),
            cursor = query.start + inserted.length,
        )
    }

    /**
     * Re-anchors a draft's mentions after the user edited the text freely, which
     * is the whole problem: an offset that drifts silently re-points a uid at
     * somebody else's name.
     *
     * The edit is recovered by diffing [MentionDraft.text] against [newText], but
     * the maximal common prefix/suffix is NOT on its own a bound on where the edit
     * happened when the text repeats: "@Bob @Bob" → "@Bob" matches a 4-char prefix
     * even though the user may equally have deleted the FIRST token. So [lo]/[hi]
     * widen the touched region to cover EVERY edit consistent with the two
     * strings, and any mention the region might have touched is dropped. An
     * ambiguous edit therefore costs a mention (re-pick it) instead of silently
     * handing one member's mention to another with the same display name — which
     * is exactly the collision this whole feature is designed around.
     *
     * Spans that survive are shifted and then re-verified against their label, so
     * nothing escapes on arithmetic alone.
     */
    fun onTextChanged(draft: MentionDraft, newText: String): MentionDraft {
        val old = draft.text
        if (old == newText) return verified(draft)
        if (draft.mentions.isEmpty()) return MentionDraft(newText, emptyList())

        val bound = minOf(old.length, newText.length)
        var prefix = 0
        while (prefix < bound && old[prefix] == newText[prefix]) prefix++
        var suffix = 0
        while (
            suffix < bound &&
            old[old.length - 1 - suffix] == newText[newText.length - 1 - suffix]
        ) {
            suffix++
        }
        val delta = newText.length - old.length

        // The union, in OLD coordinates, of every region the edit could have hit.
        // Derived from which edit positions q actually explain old -> newText:
        //  - deletion of d: q <= prefix and q >= old.length - d - suffix;
        //  - insertion:     q <= prefix and q >= old.length - suffix;
        //  - replacement:   the minimal interpretation, q == prefix.
        val lo: Int
        val hi: Int
        when {
            delta < 0 -> {
                val deleted = -delta
                lo = maxOf(0, old.length - deleted - suffix)
                hi = prefix + deleted
            }
            delta > 0 -> {
                lo = maxOf(0, old.length - suffix)
                hi = prefix
            }
            else -> {
                lo = prefix
                hi = old.length - suffix
            }
        }
        val low = minOf(lo, hi)
        val high = maxOf(lo, hi)

        val remapped =
            draft.mentions.mapNotNull { span ->
                when {
                    // Touched (or possibly touched) by the edit — drop.
                    span.start < high && span.end > low -> null
                    // Entirely before it — untouched.
                    span.end <= low -> span
                    // Entirely after it — slide by the length change.
                    else -> span.copy(start = span.start + delta)
                }
            }
        return verified(MentionDraft(newText, remapped))
    }

    /**
     * Drops every span that does not read back as its own label at its own
     * offset. This is the model's last line of defence: a mention only survives
     * while the exact text the picker inserted is still standing where it was
     * inserted, so no arithmetic slip can leave a uid pointing at unrelated text.
     */
    fun verified(draft: MentionDraft): MentionDraft {
        val text = draft.text
        val kept =
            draft.mentions.filter { span ->
                span.start >= 0 &&
                    span.label.isNotEmpty() &&
                    span.end <= text.length &&
                    text.startsWith(span.label, span.start)
            }
        return if (kept.size == draft.mentions.size) draft else draft.copy(mentions = kept)
    }
}

/** Sourcing + filtering of the members the picker can offer. */
object MentionCandidates {

    /**
     * The pickable roster. There is deliberately NO community-wide member listing
     * to query (no repository exposes one, and the Android lane doesn't add
     * backend queries), so the picker is scoped to the two rosters already in
     * hand: the caller's [friends] (from `friend-list`) and the [messageSenders]
     * denormalized onto the loaded channel messages — i.e. your friends plus
     * whoever is actually talking in the conversation you're replying to, which
     * covers the realistic reason to @ someone here.
     *
     * [selfUid] is excluded: the server drops self-mentions anyway, and offering
     * one would make a legitimate drop look like a delivery failure.
     */
    fun from(
        friends: List<MentionCandidate>,
        messageSenders: List<MentionCandidate>,
        selfUid: String,
    ): List<MentionCandidate> =
        (friends + messageSenders)
            .filter { it.uid.isNotBlank() && it.uid != selfUid && it.displayName.isNotBlank() }
            .distinctBy { it.uid }
            .sortedWith(compareBy({ it.displayName.lowercase() }, { it.uid }))

    /** The distinct authors of [messages] as candidates (skips nameless senders). */
    fun sendersOf(messages: List<ChannelMessage>): List<MentionCandidate> =
        messages.mapNotNull { message ->
            val name = message.senderDisplayName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MentionCandidate(message.senderUid, name, message.senderAvatarPath)
        }

    /**
     * uid → display name for RENDERING (a superset of the picker: it keeps
     * [selfUid]'s own name, since being mentioned yourself must highlight too).
     */
    fun displayNames(sources: List<MentionCandidate>): Map<String, String> =
        sources
            .filter { it.uid.isNotBlank() && it.displayName.isNotBlank() }
            .associate { it.uid to it.displayName }

    /**
     * The suggestions for [term]: prefix matches first (what a typist expects),
     * then anywhere-matches, each alphabetical. A blank term lists everyone —
     * bare "@" is a legitimate "who's here?".
     */
    fun matching(
        all: List<MentionCandidate>,
        term: String,
        limit: Int = MENTION_PICKER_MAX_RESULTS,
    ): List<MentionCandidate> {
        val needle = term.trim()
        val matches =
            if (needle.isEmpty()) {
                all
            } else {
                all.filter { it.displayName.contains(needle, ignoreCase = true) }
            }
        return matches
            .sortedWith(
                compareBy(
                    { if (it.displayName.startsWith(needle, ignoreCase = true)) 0 else 1 },
                    { it.displayName.lowercase() },
                    { it.uid },
                ),
            )
            .take(limit)
    }
}

/**
 * uid → span mapping for RENDERING a stored message.
 *
 * A stored message carries `mentionedUids` but NOT offsets (the server never
 * parsed the text, so it has none to store), so highlighting has to map uids back
 * onto the text here. It does so by resolving each accepted uid to a display name
 * and matching "@name" at token boundaries.
 *
 * The consequences, all deliberate:
 *  - a uid we cannot name (no profile in reach) simply isn't highlighted — the
 *    text still renders verbatim, and the member was still notified;
 *  - two mentioned members sharing a display name highlight each other's
 *    occurrences. That is cosmetic only: highlighting is derived from the
 *    ACCEPTED uid set, so nothing about who was actually notified is affected;
 *  - a uid that the server DROPPED is not in `mentionedUids`, so its text is
 *    never highlighted — a dropped mention renders as plain text, which is
 *    exactly what it now is.
 */
object MentionRendering {

    /**
     * The ranges of [text] to highlight for [mentionedUids] (the ACCEPTED set),
     * given whatever uid → display name resolution is available. Longest labels
     * win, and a match must sit on token boundaries so "@Seb" never lights up the
     * front of "@Sebastian".
     */
    fun highlightRanges(
        text: String,
        mentionedUids: List<String>,
        displayNames: Map<String, String>,
    ): List<IntRange> {
        if (text.isEmpty() || mentionedUids.isEmpty()) return emptyList()
        val labels =
            mentionedUids
                .mapNotNull { uid -> displayNames[uid]?.takeIf { it.isNotBlank() } }
                .map { "@$it" }
                .distinct()
                .sortedByDescending { it.length }
        if (labels.isEmpty()) return emptyList()

        val ranges = mutableListOf<IntRange>()
        var index = 0
        while (index < text.length) {
            val hit =
                labels.firstOrNull { label ->
                    text.startsWith(label, index) &&
                        (index == 0 || !text[index - 1].isLetterOrDigit()) &&
                        (
                            index + label.length == text.length ||
                                !text[index + label.length].isLetterOrDigit()
                            )
                }
            if (hit != null) {
                ranges += index until (index + hit.length)
                index += hit.length
            } else {
                index++
            }
        }
        return ranges
    }
}
