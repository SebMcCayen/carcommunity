package com.kungsbackacarcommunity.app.design

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * Shared keyboard configuration for every chat message composer — community and
 * convoy channels, event chat, and direct messages. One source of truth so every
 * chat surface behaves identically; change it here and all composers follow.
 *
 * Mirrors the defaults phone messaging apps use:
 *  - [KeyboardCapitalization.Sentences] auto-capitalises the first letter of the
 *    message and the first letter after sentence-ending punctuation — the main
 *    behaviour users expect from a messaging app.
 *  - [KeyboardType.Text] keeps the standard text keyboard.
 *  - autoCorrect stays on — sensible for free-form chat prose.
 *
 * imeAction is deliberately left at its default: every chat composer is multi-line
 * (`singleLine = false`) and sends via an explicit Send button, so the Enter key
 * must insert a newline rather than submit the message.
 */
val ChatComposerKeyboardOptions: KeyboardOptions =
    KeyboardOptions(
        capitalization = KeyboardCapitalization.Sentences,
        keyboardType = KeyboardType.Text,
        autoCorrectEnabled = true,
    )
