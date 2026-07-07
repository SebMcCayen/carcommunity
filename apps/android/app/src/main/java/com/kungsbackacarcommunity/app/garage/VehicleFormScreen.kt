package com.kungsbackacarcommunity.app.garage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.media.ImageUploadStatus

/**
 * Vehicle add/edit form (Phase 12 slice 13). Owns its field state; validates
 * against the backend bounds ([VehicleValidation]) before reporting a payload,
 * and closes on a successful save.
 */
@Composable
fun VehicleFormScreen(
    initial: VehicleForm,
    isEdit: Boolean,
    saveStatus: VehicleSaveStatus,
    currentYear: Int,
    onSave: (VehicleInput) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    // Photo (edit only — a new vehicle has no id yet to key the storage path):
    // the resolved current-photo URL, the in-flight upload status, and the
    // add/change action. onChangePhoto null hides the button (config-less build
    // or add mode).
    photoUrl: String? = null,
    photoUploadStatus: ImageUploadStatus = ImageUploadStatus.Idle,
    onChangePhoto: (() -> Unit)? = null,
) {
    var make by rememberSaveable { mutableStateOf(initial.make) }
    var model by rememberSaveable { mutableStateOf(initial.model) }
    var year by rememberSaveable { mutableStateOf(initial.modelYear) }
    var engine by rememberSaveable { mutableStateOf(initial.engineDescription) }
    var powertrain by rememberSaveable { mutableStateOf(initial.powertrain) }
    var showError by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(saveStatus) {
        if (saveStatus == VehicleSaveStatus.Saved) onCancel()
    }

    val form = VehicleForm(make, model, year, powertrain, engine)
    val error = VehicleValidation.validate(form, currentYear)

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
                text =
                    stringResource(
                        if (isEdit) R.string.garage_formTitleEdit else R.string.garage_formTitleCreate,
                    ),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )

            if (onChangePhoto != null) {
                VehiclePhotoSection(
                    photoUrl = photoUrl,
                    uploadStatus = photoUploadStatus,
                    onChangePhoto = onChangePhoto,
                )
            }

            OutlinedTextField(
                value = make,
                onValueChange = { make = it },
                label = { Text(text = stringResource(R.string.garage_make)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text(text = stringResource(R.string.garage_model)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = year,
                onValueChange = { year = it.filter { ch -> ch.isDigit() } },
                label = { Text(text = stringResource(R.string.garage_modelYear)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(R.string.garage_powertrain),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VehiclePowertrain.values().forEach { option ->
                if (option == powertrain) {
                    Button(onClick = { powertrain = option }, modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(option.labelRes()))
                    }
                } else {
                    OutlinedButton(onClick = { powertrain = option }, modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(option.labelRes()))
                    }
                }
            }

            OutlinedTextField(
                value = engine,
                onValueChange = { engine = it },
                label = { Text(text = stringResource(R.string.garage_engineDescription)) },
                modifier = Modifier.fillMaxWidth(),
            )

            if (showError && error != null) {
                Text(
                    text = stringResource(error.messageRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (saveStatus == VehicleSaveStatus.Failed) {
                Text(
                    text = stringResource(R.string.garage_saveError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    val input = VehicleValidation.toInput(form, currentYear)
                    if (input == null) {
                        showError = true
                    } else {
                        onSave(input)
                    }
                },
                enabled = saveStatus != VehicleSaveStatus.Saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.garage_saveVehicle))
            }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.garage_cancelButton))
            }
        }
    }
}

@Composable
private fun VehiclePhotoSection(
    photoUrl: String?,
    uploadStatus: ImageUploadStatus,
    onChangePhoto: () -> Unit,
) {
    val uploading = uploadStatus == ImageUploadStatus.Uploading
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (photoUrl != null) {
            // Coil renders nothing (keeps the placeholder) when no URL resolves.
            AsyncImage(
                model = photoUrl,
                contentDescription = stringResource(R.string.garage_photoAlt),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
        }
        if (uploading) {
            CircularProgressIndicator()
        }
    }
    OutlinedButton(onClick = onChangePhoto, enabled = !uploading, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                when {
                    uploading -> R.string.garage_photoUploading
                    photoUrl != null -> R.string.garage_photoChange
                    else -> R.string.garage_photoAdd
                },
            ),
        )
    }
    when (uploadStatus) {
        ImageUploadStatus.TooLarge ->
            Text(
                text = stringResource(R.string.garage_photoTooLarge),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        ImageUploadStatus.Failed ->
            Text(
                text = stringResource(R.string.garage_photoUploadError),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        else -> Unit
    }
}
