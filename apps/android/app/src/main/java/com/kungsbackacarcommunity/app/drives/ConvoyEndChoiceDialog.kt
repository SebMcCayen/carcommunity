package com.kungsbackacarcommunity.app.drives

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccButton

@Composable
fun ConvoyEndChoiceDialog(onChoice: (ConvoyEndChoice) -> Unit) {
    AlertDialog(
        onDismissRequest = { onChoice(ConvoyEndChoice.EndSession) },
        title = { Text(stringResource(R.string.convoy_endedContinueTitle)) },
        text = { Text(stringResource(R.string.convoy_endedContinueBody)) },
        confirmButton = {
            KccButton(onClick = { onChoice(ConvoyEndChoice.ContinueAsSingle) }) {
                Text(stringResource(R.string.convoy_endedContinueAsSingle))
            }
        },
        dismissButton = {
            TextButton(onClick = { onChoice(ConvoyEndChoice.EndSession) }) {
                Text(stringResource(R.string.convoy_endedContinueEnd))
            }
        },
    )
}
