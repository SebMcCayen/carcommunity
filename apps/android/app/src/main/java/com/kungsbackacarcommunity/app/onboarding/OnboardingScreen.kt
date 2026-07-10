package com.kungsbackacarcommunity.app.onboarding

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.design.KccTheme

/**
 * Onboarding consent gate (Phase 12 slice 2).
 *
 * Collects the three mandatory consents (age 18+, terms, privacy policy) and a
 * REQUIRED public display name, then calls auth.completeOnboarding via
 * [onSubmit]. The display name is what other members see; the user's Google
 * account name is never prefilled or shown. Continue is enabled only when all
 * consents are checked and a valid display name is entered
 * ([OnboardingForm.canSubmit]) and no submission is in flight.
 *
 * The content scrolls and consumes safe-drawing insets (status/navigation bars
 * and the IME) so the submit button stays reachable under edge-to-edge and with
 * the keyboard open. All copy is generated string resources
 * (contracts/localization). Wrap in [KccTheme].
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
    // Starts empty on purpose — never prefilled with the Google account name.
    var displayName by remember { mutableStateOf("") }

    val submitting = status == OnboardingStatus.Submitting
    val canSubmit = OnboardingForm.canSubmit(age, terms, privacy, displayName) && !submitting

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(KccSpacing.s6),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
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
            ConsentLink(text = stringResource(R.string.onboarding_termsLink), url = stringResource(R.string.url_terms))

            ConsentRow(checked = privacy, onCheckedChange = { privacy = it }, label = stringResource(R.string.onboarding_privacyAccept))
            ConsentLink(text = stringResource(R.string.onboarding_privacyLink), url = stringResource(R.string.url_privacy))

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(stringResource(R.string.onboarding_displayNameLabel)) },
                placeholder = { Text(stringResource(R.string.onboarding_displayNamePlaceholder)) },
                supportingText = { Text(stringResource(R.string.onboarding_displayNameDescription)) },
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

/**
 * A small "read the terms/privacy policy" link. Kept out of the toggleable
 * consent [Row] so the tap opens the document instead of toggling the checkbox.
 * Aligned under the label (checkbox width + row padding).
 */
@Composable
private fun ConsentLink(text: String, url: String) {
    val context = LocalContext.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier =
            Modifier
                // Checkbox width (s12) + the label's own start padding (s2) so the
                // link lines up under the consent label text, not the checkbox.
                .padding(start = KccSpacing.s12 + KccSpacing.s2)
                // Screen readers otherwise announce this as plain text: mark it
                // as a button and label the action with the link text itself
                // ("Read the terms" / "Läs villkoren") so it reads as tappable.
                .clickable(
                    role = Role.Button,
                    onClickLabel = text,
                ) { openExternalUrl(context, url) },
    )
}

/**
 * Opens an external web document via ACTION_VIEW. Restricted to http(s) so a
 * misconfigured/empty URL resource can never launch a non-web intent
 * (file:/intent:/javascript: …). Silently no-ops if there is no browser.
 */
private fun openExternalUrl(context: Context, url: String) {
    // Trim first: leading/trailing whitespace makes Uri.parse yield a null scheme.
    val uri = Uri.parse(url.trim())
    // Scheme comparison is case-insensitive: a pasted "HTTPS://…" must still open.
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        // No browser available — nothing to open.
    }
}

@Preview(name = "Onboarding", showBackground = true)
@Composable
private fun OnboardingScreenPreview() {
    KccTheme { OnboardingScreen(status = OnboardingStatus.Idle, onSubmit = {}) }
}
