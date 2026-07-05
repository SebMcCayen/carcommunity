package com.kungsbackacarcommunity.app.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme

/**
 * Profile view/edit screen (Phase 12 slice 2).
 *
 * View mode shows the display name and bio; edit mode edits the two
 * whitelisted owner-writable fields (Phase 9a) with inline validation
 * ([ProfileValidation]) and saves via [onSave] (a direct users/{uid}
 * write). Avatar, contact details, and privacy toggles are deferred to a
 * later settings slice. Wrap in [KccTheme].
 */
@Composable
fun ProfileScreen(
    profile: UserProfile?,
    saveStatus: ProfileEditStatus,
    onSave: (displayName: String, bio: String) -> Unit,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    var nameField by remember { mutableStateOf("") }
    var bioField by remember { mutableStateOf("") }

    val saving = saveStatus == ProfileEditStatus.Saving
    // Leave edit mode only on a successful save; a failure keeps the drafts.
    LaunchedEffect(saveStatus) {
        if (saveStatus == ProfileEditStatus.Saved) editing = false
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.profile_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            if (editing) {
                val validation = ProfileValidation.validate(nameField, bioField)
                OutlinedTextField(
                    value = nameField,
                    onValueChange = { nameField = it },
                    label = { Text(stringResource(R.string.profile_displayNameLabel)) },
                    isError = validation.displayNameError != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldError(validation.displayNameError)
                OutlinedTextField(
                    value = bioField,
                    onValueChange = { bioField = it },
                    label = { Text(stringResource(R.string.profile_bioLabel)) },
                    isError = validation.bioError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldError(validation.bioError)

                if (saveStatus == ProfileEditStatus.Failed) {
                    Text(
                        text = stringResource(R.string.profile_saveError),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = { onSave(nameField.trim(), bioField.trim()) },
                    enabled = validation.isValid && !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    } else {
                        Text(stringResource(R.string.profile_saveButton))
                    }
                }
                TextButton(
                    onClick = { editing = false },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.profile_cancelButton))
                }
            } else {
                Text(
                    text = profile?.displayName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.common_placeholder),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = profile?.bio?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.common_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = {
                        nameField = profile?.displayName.orEmpty()
                        bioField = profile?.bio.orEmpty()
                        editing = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.profile_editButton))
                }
                OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.auth_signOut))
                }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.profile_back))
                }
            }
        }
    }
}

@Composable
private fun FieldError(error: ProfileValidation.FieldError?) {
    if (error == null) return
    val message =
        when (error) {
            ProfileValidation.FieldError.REQUIRED -> stringResource(R.string.profile_errorNameRequired)
            ProfileValidation.FieldError.TOO_LONG -> stringResource(R.string.profile_errorTooLong)
        }
    Text(
        text = message,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Preview(name = "Profile", showBackground = true)
@Composable
private fun ProfileScreenPreview() {
    KccTheme {
        ProfileScreen(
            profile = UserProfile(displayName = "Sebbe", bio = "Volvo-entusiast", onboardingComplete = true),
            saveStatus = ProfileEditStatus.Idle,
            onSave = { _, _ -> },
            onBack = {},
            onSignOut = {},
        )
    }
}
