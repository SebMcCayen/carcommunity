package com.kungsbackacarcommunity.app.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shared quick-emoji set that every chat composer (community + convoy
 * channels, event chat, direct messages) shows above its input via
 * [ChatQuickEmojis].
 *
 * The load-bearing promise is the owner's three requested reactions — 😊 happy,
 * 😢 sad, and 👑 crown (the app's motif) — which this test keeps present so the set
 * cannot silently drop one. It also guards the invariants the row relies on: the
 * set stays a small single row, every glyph is unique (glyphs are the LazyRow key),
 * and every entry carries a content description for accessibility.
 *
 * Pure JVM: [QuickEmoji] is a plain data holder, so no device or Robolectric.
 */
class ChatQuickEmojiRowTest {

    private val glyphs = ChatQuickEmojis.map { it.glyph }

    @Test
    fun `includes the three owner-requested reactions`() {
        assertTrue("missing happy 😊", glyphs.contains("😊"))
        assertTrue("missing sad 😢", glyphs.contains("😢"))
        assertTrue("missing crown 👑", glyphs.contains("👑"))
    }

    @Test
    fun `stays a small single row`() {
        assertTrue(
            "quick-emoji set should stay small (was ${ChatQuickEmojis.size})",
            ChatQuickEmojis.size in 3..8,
        )
    }

    @Test
    fun `every glyph is unique`() {
        assertEquals(glyphs.size, glyphs.toSet().size)
    }

    @Test
    fun `every emoji carries a content description`() {
        assertTrue(
            "every quick emoji needs a non-zero content-description resource",
            ChatQuickEmojis.all { it.contentDescriptionRes != 0 },
        )
    }
}
