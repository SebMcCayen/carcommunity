package com.kungsbackacarcommunity.app.design

import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the shared chat-composer keyboard defaults that every chat surface
 * (community + convoy channels, event chat, direct messages) inherits from
 * [ChatComposerKeyboardOptions].
 *
 * The load-bearing promise is sentence capitalisation: like every phone messaging
 * app, the first letter of a message — and the first letter after sentence-ending
 * punctuation — must auto-capitalise. This test keeps that honest so the setting
 * cannot silently drift back to the platform default (None).
 *
 * Pure JVM: [KeyboardOptions] is a plain data holder, so no device or Robolectric
 * is needed.
 */
class ChatComposerKeyboardTest {

    @Test
    fun `chat composer capitalises sentences`() {
        assertEquals(
            KeyboardCapitalization.Sentences,
            ChatComposerKeyboardOptions.capitalization,
        )
    }

    @Test
    fun `chat composer uses the plain text keyboard`() {
        assertEquals(
            KeyboardType.Text,
            ChatComposerKeyboardOptions.keyboardType,
        )
    }

    @Test
    fun `chat composer keeps autocorrect on`() {
        assertEquals(
            true,
            ChatComposerKeyboardOptions.autoCorrectEnabled,
        )
    }
}
