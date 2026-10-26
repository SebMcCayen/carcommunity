package com.kungsbackacarcommunity.app.partners

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import com.kungsbackacarcommunity.app.design.KccButton as Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.shell.AeroPage

/**
 * Partner-application form (Phase 12 slice 18). Owns its field state; validates before submitting
 * via the callable and shows a success confirmation on Done.
 */
@Composable
fun PartnerApplicationScreen(
    status: PartnerApplicationStatus,
    onSubmit: (PartnerApplicationInput) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var companyName by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<PartnerCategory?>(null) }
    var contactName by rememberSaveable { mutableStateOf("") }
    var contactEmail by rememberSaveable { mutableStateOf("") }
    var contactPhone by rememberSaveable { mutableStateOf("") }
    var website by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var showError by rememberSaveable { mutableStateOf(false) }

    val form =
        PartnerApplicationForm(
            companyName,
            category,
            contactName,
            contactEmail,
            contactPhone,
            website,
            message,
        )

    AeroPage(
        ownerShortcutEnabled = false,
        title = stringResource(R.string.partners_applicationTitle),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        // Consume the IME (and nav-bar) inset so the lower fields and the submit
        // button aren't hidden behind the keyboard or the phone's navigation bar.
        // The app is edge-to-edge (MainActivity calls enableEdgeToEdge()), so each
        // screen must apply the keyboard inset itself; without this the form's
        // viewport ran under the IME and the focused field / Submit button vanished
        // behind the keyboard and nav buttons (issue #818). This mirrors the fix
        // already applied to the "Report a problem" form (FeedbackReportScreen).
        // The union takes the taller of IME / nav bar rather than double-counting;
        // AeroPage stays scrollable (default) so Compose's bring-into-view keeps the
        // focused field visible within the now-shrunk viewport.
        contentWindowInsets = WindowInsets.ime.union(WindowInsets.navigationBars),
    ) {
        if (status == PartnerApplicationStatus.Done) {
            Text(
                text = stringResource(R.string.partners_submitSuccess),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.partners_close))
            }
            return@AeroPage
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.partners_privacyNotice),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(companyName, R.string.partners_companyNameLabel) { companyName = it }
                Text(
                    text = stringResource(R.string.partners_categoryLabel),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.selectableGroup()) {
                    PartnerCategory.values().forEach { option ->
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .selectable(
                                        selected = option == category,
                                        role = Role.RadioButton,
                                        onClick = { category = option },
                                    ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == category, onClick = null)
                            Text(
                                text = stringResource(option.labelRes()),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(contactName, R.string.partners_contactNameLabel) { contactName = it }
                Field(contactEmail, R.string.partners_contactEmailLabel, KeyboardType.Email) {
                    contactEmail = it
                }
                Field(contactPhone, R.string.partners_contactPhoneLabel, KeyboardType.Phone) {
                    contactPhone = it
                }
                Field(website, R.string.partners_websiteLabel, keyboardType = KeyboardType.Uri) {
                    website = it
                }
                // Free-form optional message — allow multiple lines.
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text(text = stringResource(R.string.partners_messageLabel)) },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (showError && PartnerApplications.validate(form) != null) {
            Text(
                text = stringResource(R.string.partners_fieldRequired),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (status is PartnerApplicationStatus.Failed) {
            val messageRes =
                when (status.reason) {
                    PartnerApplicationFailureReason.INVALID_ARGUMENT ->
                        R.string.partners_submitErrorInvalid
                    PartnerApplicationFailureReason.ALREADY_EXISTS ->
                        R.string.partners_submitErrorDuplicate
                    PartnerApplicationFailureReason.UNKNOWN -> R.string.partners_submitError
                }
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = {
                val input = PartnerApplications.toInput(form)
                if (input == null) showError = true else onSubmit(input)
            },
            enabled = status != PartnerApplicationStatus.Submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.partners_submitButton))
        }
    }
}

@Composable
private fun Field(
    value: String,
    labelRes: Int,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(text = stringResource(labelRes)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}
