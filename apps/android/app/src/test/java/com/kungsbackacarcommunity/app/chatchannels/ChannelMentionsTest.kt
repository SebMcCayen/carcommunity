package com.kungsbackacarcommunity.app.chatchannels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The @mention client model. The failure this suite exists to prevent is a
 * recorded uid drifting onto text that belongs to somebody else — a mention
 * pushes a stranger's conversation into a personal inbox, so a mention pointing
 * at the wrong member is worse than no mention at all. Every ambiguous case below
 * therefore asserts a DROP, not a best guess.
 */
class ChannelMentionsTest {

    private val alice = MentionCandidate("uid-alice", "Alice")
    private val bob = MentionCandidate("uid-bob", "Bob")
    // The whole reason mentions are uid-based: two DIFFERENT members, one name.
    private val otherBob = MentionCandidate("uid-bob-2", "Bob")

    private fun draftOf(vararg picks: MentionCandidate): MentionDraft {
        var draft = MentionDraft("", emptyList())
        for (candidate in picks) {
            val query = DraftMentions.activeQuery(draft.text + "@", draft.text.length + 1)!!
            val inserted =
                DraftMentions.insert(draft.copy(text = draft.text + "@"), query, candidate)
            draft = (inserted as MentionInsertResult.Inserted).draft
        }
        return draft
    }

    // ---------------------------------------------------------------- queries

    @Test
    fun `an at-sign at the caret opens a query`() {
        val query = DraftMentions.activeQuery("hey @al", cursor = 7)
        assertEquals(MentionQuery(start = 4, end = 7, term = "al"), query)
    }

    @Test
    fun `a bare at-sign opens a query listing everyone`() {
        assertEquals(MentionQuery(0, 1, ""), DraftMentions.activeQuery("@", cursor = 1))
    }

    @Test
    fun `an email's at-sign does not open a query`() {
        // "seb@example" — the '@' follows an alphanumeric, so it starts no token.
        assertNull(DraftMentions.activeQuery("seb@exa", cursor = 7))
    }

    @Test
    fun `a query tolerates one space so a two-word name can be typed`() {
        assertEquals(MentionQuery(0, 7, "Seb Mc"), DraftMentions.activeQuery("@Seb Mc", cursor = 7))
        // ...but a sentence after a stray '@' closes the picker instead of
        // hanging it over the whole message.
        assertNull(DraftMentions.activeQuery("@Seb went to", cursor = 12))
    }

    @Test
    fun `a newline ends a query`() {
        assertNull(DraftMentions.activeQuery("@Alice\nhello", cursor = 12))
    }

    @Test
    fun `a selection is not a query`() {
        assertNull(DraftMentions.activeQuery("@al", cursor = 1, selectionEnd = 3))
    }

    @Test
    fun `the caret inside a resolved mention opens no query`() {
        val draft = draftOf(alice)
        // "@Alice " — caret inside the label must not re-suggest over it.
        assertNull(DraftMentions.activeQuery(draft.text, cursor = 3, mentions = draft.mentions))
    }

    // -------------------------------------------------------------- insertion

    @Test
    fun `insert records the uid behind the inserted label`() {
        val result = DraftMentions.insert(MentionDraft("hi @al"), MentionQuery(3, 6, "al"), alice)
        val inserted = result as MentionInsertResult.Inserted
        assertEquals("hi @Alice ", inserted.draft.text)
        assertEquals(listOf(MentionSpan("uid-alice", "@Alice", 3)), inserted.draft.mentions)
        assertEquals(inserted.draft.text.length, inserted.cursor)
        assertEquals(listOf("uid-alice"), inserted.draft.sendableUids)
    }

    @Test
    fun `insert shifts a later mention and leaves an earlier one alone`() {
        // "@Alice @Bob " then insert into the middle-ish start.
        val base = draftOf(alice, bob)
        val text = "x" + base.text
        val shifted = base.copy(text = text, mentions = base.mentions.map { it.copy(start = it.start + 1) })
        val result =
            DraftMentions.insert(shifted, MentionQuery(text.length, text.length, ""), otherBob)
        val draft = (result as MentionInsertResult.Inserted).draft
        assertEquals("x@Alice @Bob @Bob ", draft.text)
        assertEquals(listOf("uid-alice", "uid-bob", "uid-bob-2"), draft.sendableUids)
        // Every span still reads back as its own label — no drift.
        assertEquals(draft, DraftMentions.verified(draft))
    }

    // -------------------------------------------------------------- the cap

    @Test
    fun `the tenth distinct member is accepted and the eleventh is refused`() {
        val ten = (1..MAX_MESSAGE_MENTIONS).map { MentionCandidate("uid-$it", "Member$it") }
        val draft = draftOf(*ten.toTypedArray())
        assertEquals(MAX_MESSAGE_MENTIONS, draft.distinctUidCount)
        assertEquals(MAX_MESSAGE_MENTIONS, draft.sendableUids.size)

        val query = DraftMentions.activeQuery(draft.text + "@", draft.text.length + 1)!!
        val refused = DraftMentions.insert(draft.copy(text = draft.text + "@"), query, alice)
        // The server's ONE hard reject (invalid-argument above the cap) — the
        // picker refuses first so the user gets a sentence, not a failed send.
        assertEquals(MentionInsertResult.AtCap, refused)
    }

    @Test
    fun `re-mentioning someone already named is allowed at the cap`() {
        val ten = (1..MAX_MESSAGE_MENTIONS).map { MentionCandidate("uid-$it", "Member$it") }
        val draft = draftOf(*ten.toTypedArray())
        val query = DraftMentions.activeQuery(draft.text + "@", draft.text.length + 1)!!
        val again = DraftMentions.insert(draft.copy(text = draft.text + "@"), query, ten.first())
        assertTrue(again is MentionInsertResult.Inserted)
        // Two spans, still one member: the cap counts distinct members, and
        // sendableUids stays within it.
        val updated = (again as MentionInsertResult.Inserted).draft
        assertEquals(MAX_MESSAGE_MENTIONS, updated.distinctUidCount)
        assertEquals(MAX_MESSAGE_MENTIONS, updated.sendableUids.size)
    }

    @Test
    fun `sendableUids can never exceed the cap even from hand-built state`() {
        // Each label genuinely stands where its span claims, so all 15 are real
        // mentions and only the cap may cut them down.
        val labels = (1..15).map { "@M$it" }
        val text = labels.joinToString(" ")
        var offset = 0
        val spans =
            labels.mapIndexed { index, label ->
                val span = MentionSpan("uid-${index + 1}", label, offset)
                offset += label.length + 1
                span
            }
        assertEquals(MAX_MESSAGE_MENTIONS, MentionDraft(text, spans).sendableUids.size)
    }

    @Test
    fun `sendableUids drops a uid whose label no longer stands at its offset`() {
        // A desynced draft — spans restored beside text they no longer match (the
        // rememberSaveable restore path reconstructs both without re-checking).
        // Sending "uid-bob" here would notify a member the text never names: a
        // wrong-RECIPIENT bug, not a wrong highlight. Nothing may escape.
        val draft = MentionDraft("@Alice hello", listOf(MentionSpan("uid-bob", "@Bob", 0)))
        assertEquals(emptyList<String>(), draft.sendableUids)
    }

    @Test
    fun `sendableUids keeps the intact mentions of a partially desynced draft`() {
        // Only the span that no longer reads back is dropped; a still-anchored
        // mention in the same draft is not collateral.
        val draft =
            MentionDraft(
                "@Alice hi",
                listOf(
                    MentionSpan("uid-alice", "@Alice", 0),
                    MentionSpan("uid-bob", "@Bob", 6),
                ),
            )
        assertEquals(listOf("uid-alice"), draft.sendableUids)
    }

    // -------------------------------------- uid tracking across free-text edits

    @Test
    fun `typing after a mention keeps it anchored`() {
        val draft = draftOf(alice)
        val edited = DraftMentions.onTextChanged(draft, draft.text + "how are you?")
        assertEquals(listOf(MentionSpan("uid-alice", "@Alice", 0)), edited.mentions)
    }

    @Test
    fun `typing before a mention slides it`() {
        val draft = draftOf(alice)
        val edited = DraftMentions.onTextChanged(draft, "hey " + draft.text)
        assertEquals(listOf(MentionSpan("uid-alice", "@Alice", 4)), edited.mentions)
        assertTrue(edited.text.startsWith("@Alice", 4))
    }

    @Test
    fun `backspacing into a mention's label drops it rather than re-point it`() {
        val draft = draftOf(alice)
        // "@Alice " -> "@Alic " : the label no longer stands, so neither does the
        // mention. It degrades to plain text; the user can re-pick.
        val edited = DraftMentions.onTextChanged(draft, "@Alic ")
        assertEquals(emptyList<MentionSpan>(), edited.mentions)
        assertEquals(emptyList<String>(), edited.sendableUids)
    }

    @Test
    fun `retyping a dropped mention's exact text does not resurrect the uid`() {
        val draft = draftOf(alice)
        val broken = DraftMentions.onTextChanged(draft, "@Alic ")
        val retyped = DraftMentions.onTextChanged(broken, "@Alice ")
        // The text reads "@Alice" again, but nothing resolved it — the server
        // would have to guess which Alice, which is exactly what it refuses to do.
        assertEquals(emptyList<MentionSpan>(), retyped.mentions)
    }

    @Test
    fun `editing one mention leaves an unrelated one intact`() {
        val draft = draftOf(alice, bob) // "@Alice @Bob "
        val edited = DraftMentions.onTextChanged(draft, "@Alic @Bob ")
        assertEquals(listOf(MentionSpan("uid-bob", "@Bob", 6)), edited.mentions)
        assertTrue(edited.text.startsWith("@Bob", 6))
    }

    @Test
    fun `char-by-char deletion of the first of two same-named mentions is unambiguous`() {
        // "@Bob @Bob " naming TWO DIFFERENT members. One backspace inside the
        // first label is explained by exactly one edit position, so the second
        // mention must survive AND still point at its own member.
        val draft = draftOf(bob, otherBob)
        assertEquals("@Bob @Bob ", draft.text)
        val edited = DraftMentions.onTextChanged(draft, "@Bo @Bob ")
        assertEquals(listOf(MentionSpan("uid-bob-2", "@Bob", 4)), edited.mentions)
        assertTrue(edited.text.startsWith("@Bob", 4))
        assertEquals(listOf("uid-bob-2"), edited.sendableUids)
    }

    @Test
    fun `deleting one of two identically-named mentions wholesale drops BOTH`() {
        // THE test. "@Bob @Bob " naming two different members; the user selects
        // one "@Bob" and deletes it. Prefix/suffix matching cannot tell WHICH one
        // went — every edit position explains the result equally — so keeping
        // either would be a coin flip that can silently notify the wrong Bob.
        // Both drop; the surviving "@Bob" is plain text until re-picked.
        val draft = draftOf(bob, otherBob)
        val edited = DraftMentions.onTextChanged(draft, "@Bob ")
        assertEquals(emptyList<MentionSpan>(), edited.mentions)
    }

    @Test
    fun `deleting a distinctly-named mention keeps the other one anchored`() {
        // "@Alice @Bob " -> " @Bob ": Alice's label is gone and only one edit
        // explains that, so Bob survives — correctly re-anchored to his own text
        // rather than dropped out of paranoia.
        val draft = draftOf(alice, bob)
        val edited = DraftMentions.onTextChanged(draft, " @Bob ")
        assertEquals(listOf(MentionSpan("uid-bob", "@Bob", 1)), edited.mentions)
        assertTrue(edited.text.startsWith("@Bob", 1))
    }

    @Test
    fun `an ambiguous deletion drops a mention it cannot prove it missed`() {
        // "@Alice @Bob " -> "@Bob ", i.e. the user selected "@Alice " (WITH the
        // trailing space) and deleted it. Two edits explain that equally: drop
        // "@Alice " and keep Bob's token, or drop "Alice @" and leave Alice's '@'
        // welded to Bob's "Bob " — a token neither member owns. Since one reading
        // leaves Bob's mention pointing at spliced text, the mention drops and
        // "@Bob" becomes plain text until re-picked. Conservative by design: the
        // cost is one re-pick, the alternative is notifying the wrong member.
        val draft = draftOf(alice, bob)
        assertEquals(emptyList<MentionSpan>(), DraftMentions.onTextChanged(draft, "@Bob ").mentions)
    }

    @Test
    fun `clearing the draft clears every mention`() {
        assertEquals(emptyList<MentionSpan>(), DraftMentions.onTextChanged(draftOf(alice, bob), "").mentions)
    }

    @Test
    fun `an ambiguous repeated-character insertion drops the mentions it may have hit`() {
        val draft = MentionDraft("@aaa ", listOf(MentionSpan("uid-a", "@aaa", 0)))
        // "@aaa " -> "@aaaa ": the extra 'a' could have landed anywhere in the
        // label. Conservative: drop.
        assertEquals(emptyList<MentionSpan>(), DraftMentions.onTextChanged(draft, "@aaaa ").mentions)
    }

    @Test
    fun `verified drops a span whose text no longer reads as its label`() {
        // The last line of defence: hand-built drift (right offset, wrong text).
        val drifted = MentionDraft("hello there", listOf(MentionSpan("uid-alice", "@Alice", 0)))
        assertEquals(emptyList<MentionSpan>(), DraftMentions.verified(drifted).mentions)
    }

    @Test
    fun `verified drops an out-of-bounds span`() {
        val past = MentionDraft("hi", listOf(MentionSpan("uid-alice", "@Alice", 1)))
        assertEquals(emptyList<MentionSpan>(), DraftMentions.verified(past).mentions)
    }

    // ------------------------------------------------------------- candidates

    @Test
    fun `candidates merge friends with channel senders and exclude the caller`() {
        val messages =
            listOf(
                message("m1", "uid-bob", "Bob", "hi"),
                message("m2", "uid-self", "Me", "hey"),
                message("m3", "uid-nameless", null, "..."),
            )
        val candidates =
            MentionCandidates.from(
                friends = listOf(alice),
                messageSenders = MentionCandidates.sendersOf(messages),
                selfUid = "uid-self",
            )
        // Alice (friend) + Bob (talking here); never the caller, never a sender
        // with no name to insert.
        assertEquals(listOf("uid-alice", "uid-bob"), candidates.map { it.uid })
    }

    @Test
    fun `candidates dedupe a friend who is also talking in the channel`() {
        val senders = MentionCandidates.sendersOf(listOf(message("m1", "uid-alice", "Alice", "hi")))
        val candidates =
            MentionCandidates.from(listOf(alice), senders, selfUid = "uid-self")
        assertEquals(1, candidates.size)
    }

    @Test
    fun `matching prefers prefix matches and is case-insensitive`() {
        val all =
            listOf(
                MentionCandidate("uid-1", "Barbara"),
                MentionCandidate("uid-2", "Bob"),
                MentionCandidate("uid-3", "Bobby"),
                MentionCandidate("uid-4", "Littlebob"),
            )
        // "bob" prefix-matches Bob/Bobby and mid-matches Littlebob — prefixes first.
        assertEquals(
            listOf("uid-2", "uid-3", "uid-4"),
            MentionCandidates.matching(all, "bob").map { it.uid },
        )
        // A blank term lists everyone: bare "@" is a legitimate "who's here?".
        assertEquals(4, MentionCandidates.matching(all, "").size)
    }

    @Test
    fun `matching is capped at the picker's page size`() {
        val many = (1..30).map { MentionCandidate("uid-$it", "Member$it") }
        assertEquals(MENTION_PICKER_MAX_RESULTS, MentionCandidates.matching(many, "Member").size)
    }

    @Test
    fun `displayNames keeps the caller so being mentioned yourself highlights`() {
        val senders = MentionCandidates.sendersOf(listOf(message("m1", "uid-self", "Me", "hey")))
        assertEquals(mapOf("uid-self" to "Me"), MentionCandidates.displayNames(senders))
    }

    // -------------------------------------------------------------- rendering

    @Test
    fun `highlighting maps accepted uids back onto their spans`() {
        val ranges =
            MentionRendering.highlightRanges(
                text = "@Alice and @Bob, look",
                mentionedUids = listOf("uid-alice", "uid-bob"),
                displayNames = mapOf("uid-alice" to "Alice", "uid-bob" to "Bob"),
            )
        assertEquals(listOf(0 until 6, 11 until 15), ranges)
    }

    @Test
    fun `a uid the server DROPPED renders as plain text`() {
        // Reconciliation, end to end: the composer optimistically named both, the
        // server accepted only Alice, so the stored/echoed set holds only Alice —
        // and "@Bob" gets no highlight, because it is no longer a mention.
        val ranges =
            MentionRendering.highlightRanges(
                text = "@Alice and @Bob, look",
                mentionedUids = listOf("uid-alice"),
                displayNames = mapOf("uid-alice" to "Alice", "uid-bob" to "Bob"),
            )
        assertEquals(listOf(0 until 6), ranges)
    }

    @Test
    fun `an unresolvable uid highlights nothing and breaks nothing`() {
        val ranges =
            MentionRendering.highlightRanges("@Ghost hi", listOf("uid-ghost"), emptyMap())
        assertEquals(emptyList<IntRange>(), ranges)
    }

    @Test
    fun `a longer name wins over a shorter one that prefixes it`() {
        val ranges =
            MentionRendering.highlightRanges(
                text = "@Seb McCayen hi",
                mentionedUids = listOf("uid-seb", "uid-sebm"),
                displayNames = mapOf("uid-seb" to "Seb", "uid-sebm" to "Seb McCayen"),
            )
        assertEquals(listOf(0 until 12), ranges)
    }

    @Test
    fun `a name is not highlighted inside a longer word`() {
        // "@Sebastian" is not "@Seb" — no partial-token highlight.
        val ranges =
            MentionRendering.highlightRanges(
                "@Sebastian hi",
                listOf("uid-seb"),
                mapOf("uid-seb" to "Seb"),
            )
        assertEquals(emptyList<IntRange>(), ranges)
    }

    @Test
    fun `an empty mention set highlights nothing (pre-mentions history and convoy)`() {
        assertEquals(
            emptyList<IntRange>(),
            MentionRendering.highlightRanges("@Alice hi", emptyList(), mapOf("uid-alice" to "Alice")),
        )
    }

    private fun message(id: String, uid: String, name: String?, text: String) =
        ChannelMessage(
            id = id,
            senderUid = uid,
            text = text,
            senderDisplayName = name,
            senderAvatarPath = null,
            createdAtMillis = 1L,
            createdAtIso = "2026-07-16T10:00:00Z",
        )
}
