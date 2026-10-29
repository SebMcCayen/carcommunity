package com.kungsbackacarcommunity.app.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R

const val CHAT_COLLAPSED_BODY_LINES = 8

/** Layout-only collapse: original text, graphemes, annotations and link handlers are never sliced. */
@Composable
fun ChatMessageBody(
    messageId: String,
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    textAlign: TextAlign = TextAlign.Start,
) {
    var expanded by rememberSaveable(messageId, text.text) { mutableStateOf(false) }
    var overflowing by remember(messageId, text.text) { mutableStateOf(false) }
    val description = stringResource(if (expanded) R.string.chat_flood_expanded else R.string.chat_flood_collapsed)
    Column {
        Text(
            text = text, style = style, color = color, textAlign = textAlign,
            maxLines = if (expanded) Int.MAX_VALUE else CHAT_COLLAPSED_BODY_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
            modifier = Modifier.testTag("chat-body-$messageId").then(
                if (expanded || overflowing) Modifier.semantics { stateDescription = description } else Modifier),
        )
        if (expanded || overflowing) TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.testTag("chat-body-toggle-$messageId"),
            colors = ButtonDefaults.textButtonColors(contentColor = color),
            contentPadding = PaddingValues(horizontal = 0.dp),
        ) { Text(stringResource(if (expanded) R.string.chat_flood_show_less else R.string.chat_flood_show_more)) }
    }
}
