package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.design.ChatComposerKeyboardOptions
import com.kungsbackacarcommunity.app.design.ChatQuickEmojiRow
import com.kungsbackacarcommunity.app.design.KccButton
import com.kungsbackacarcommunity.app.design.KccSpacing

/** Community's composer presentation. The route owns limits, drafts and send acknowledgement. */
@Composable
internal fun ColumnScope.ChannelChatComposer(
    draft: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onQuickEmoji: (String) -> Unit,
    canSend: Boolean,
    placeholder: String,
    sendLabel: String,
    enabled: Boolean = true,
    inputModifier: Modifier = Modifier,
    quickEmojiEnabled: Boolean = enabled,
) {
    ChatQuickEmojiRow(onEmojiSelected = onQuickEmoji, enabled = quickEmojiEnabled)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft, onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            modifier = Modifier.weight(1f).then(inputModifier),
            enabled = enabled,
            keyboardOptions = ChatComposerKeyboardOptions,
            singleLine = false, maxLines = 5,
            shape = RoundedCornerShape(24.dp),
        )
        KccButton(
            onClick = onSend, enabled = enabled && canSend,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) { Text(sendLabel) }
    }
}
