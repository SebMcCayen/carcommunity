package com.kungsbackacarcommunity.app.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme

/**
 * Onboarding consent gate (Phase 12 slice 2).
 *
 * Collects the three mandatory consents (age 18+, terms, privacy policy)
 * and an optional display name, then calls auth.completeOnboarding via
 * [onSubmit]. Continue is enabled only when all consents are checked
 * ([OnboardingForm.canSubmit]) and no submission is in flight. All copy is
 * generated string resources (contracts/localization). Wrap in [KccTheme].
 */
@Composable
fun OnboardingScreen(
    status: OnboardingStatus,
    onSubmit: (displayName: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var age by remember { mutableStateOf(false) }
    var terms by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf("") }

    val submitting = status == OnboardingStatus.Submitting
    val canSubmit = OnboardingForm.canSubmit(age, terms, privacy) && !submitting

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ConsentRow(checked = age, onCheckedChange = { age = it }, label = stringResource(R.string.onboarding_ageConfirm))
            ConsentRow(checked = terms, onCheckedChange = { terms = it }, label = stringResource(R.string.onboarding_termsAccept))
            ConsentRow(checked = privacy, onCheckedChange = { privacy = it }, label = stringResource(R.string.onboarding_privacyAccept))

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(stringResource(R.string.onboarding_displayNameLabel)) },
                placeholder = { Text(stringResource(R.string.onboarding_displayNamePlaceholder)) },
                isError = OnboardingForm.isDisplayNameTooLong(displayName),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (status == OnboardingStatus.Failed) {
                Text(
                    text = stringResource(R.string.onboarding_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = { onSubmit(OnboardingForm.normalizedDisplayName(displayName)) },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (submitting) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                } else {
                    Text(stringResource(R.string.onboarding_continueButton))
                }
            }
        }
    }
}

@Composable
private fun ConsentRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    // Whole row is one toggleable target (accessibility + testability):
    // tapping the label toggles the checkbox, which is null-controlled.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Checkbox),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Preview(name = "Onboarding", showBackground = true)
@Composable
private fun OnboardingScreenPreview() {
    KccTheme { OnboardingScreen(status = OnboardingStatus.Idle, onSubmit = {}) }
}
