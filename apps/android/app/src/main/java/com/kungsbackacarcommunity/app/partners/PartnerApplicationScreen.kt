package com.kungsbackacarcommunity.app.partners

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R

/**
 * Partner-application form (Phase 12 slice 18). Owns its field state; validates
 * before submitting via the callable and shows a success confirmation on Done.
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
        PartnerApplicationForm(companyName, category, contactName, contactEmail, contactPhone, website, message)

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.partners_applicationTitle),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )

            if (status == PartnerApplicationStatus.Done) {
                Text(
                    text = stringResource(R.string.partners_submitSuccess),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.partners_close))
                }
                return@Column
            }

            Text(
                text = stringResource(R.string.partners_privacyNotice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Field(companyName, R.string.partners_companyNameLabel) { companyName = it }
            Text(
                text = stringResource(R.string.partners_categoryLabel),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PartnerCategory.values().forEach { option ->
                if (option == category) {
                    Button(onClick = { category = option }, modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(option.labelRes()))
                    }
                } else {
                    OutlinedButton(onClick = { category = option }, modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(option.labelRes()))
                    }
                }
            }
            Field(contactName, R.string.partners_contactNameLabel) { contactName = it }
            Field(contactEmail, R.string.partners_contactEmailLabel) { contactEmail = it }
            Field(contactPhone, R.string.partners_contactPhoneLabel) { contactPhone = it }
            Field(website, R.string.partners_websiteLabel) { website = it }
            Field(message, R.string.partners_messageLabel) { message = it }

            if (showError && PartnerApplications.validate(form) != null) {
                Text(
                    text = stringResource(R.string.partners_fieldRequired),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (status == PartnerApplicationStatus.Failed) {
                Text(
                    text = stringResource(R.string.partners_error),
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
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.partners_close))
            }
        }
    }
}

@Composable
private fun Field(value: String, labelRes: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(text = stringResource(labelRes)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
