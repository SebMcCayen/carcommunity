package com.kungsbackacarcommunity.app.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.AeroPage

/**
 * "Report a problem" form. Owns its field state; a prominent notice warns that
 * the report is filed to a PUBLIC issue tracker before any typing. Validates
 * (description required) then submits via the callable; shows a success
 * confirmation (with an optional "View issue" link) or a friendly error.
 */
@Composable
fun FeedbackReportScreen(
    status: FeedbackStatus,
    clientContext: FeedbackClientContext,
    onSubmit: (FeedbackReportInput) -> Unit,
    onViewIssue: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var summary by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var showError by rememberSaveable { mutableStateOf(false) }

    val form = FeedbackReportForm(summary = summary, description = description)

    AeroPage(
        title = stringResource(R.string.feedback_title),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
            if (status is FeedbackStatus.Done) {
                Text(
                    text =
                        stringResource(
                            if (status.issueUrl != null) {
                                R.string.feedback_successWithIssue
                            } else {
                                R.string.feedback_success
                            },
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (status.issueUrl != null) {
                    OutlinedButton(
                        onClick = { onViewIssue(status.issueUrl) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.feedback_viewIssue))
                    }
                }
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.feedback_close))
                }
                return@AeroPage
            }

            // Prominent public-tracker warning — the report is world-readable.
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.feedback_publicNoticeTitle),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.feedback_publicNotice),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            OutlinedTextField(
                value = summary,
                onValueChange = { summary = it },
                label = { Text(text = stringResource(R.string.feedback_summaryLabel)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(text = stringResource(R.string.feedback_descriptionLabel)) },
                singleLine = false,
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            if (showError && FeedbackReports.validate(form) != null) {
                Text(
                    text = stringResource(R.string.feedback_descriptionRequired),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (status is FeedbackStatus.Failed) {
                Text(
                    text =
                        stringResource(
                            if (status.rateLimited) {
                                R.string.feedback_rateLimited
                            } else {
                                R.string.feedback_error
                            },
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    val input = FeedbackReports.toInput(form, clientContext)
                    if (input == null) showError = true else onSubmit(input)
                },
                enabled = status != FeedbackStatus.Submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.feedback_submit))
            }
    }
}
