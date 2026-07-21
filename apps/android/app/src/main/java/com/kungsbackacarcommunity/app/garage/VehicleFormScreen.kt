package com.kungsbackacarcommunity.app.garage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.media.ImageUploadStatus
import com.kungsbackacarcommunity.app.shell.AeroPage

/**
 * Vehicle add/edit form (Phase 12 slice 13). Owns its field state; validates
 * against the backend bounds ([VehicleValidation]) before reporting a payload,
 * and closes on a successful save.
 *
 * Rendered inside [AeroPage] like every other page in the app, which supplies
 * the status-bar inset — the title must never sit under the system clock — plus
 * the shared gutters and title treatment. Do not hand-roll a `Surface` +
 * `statusBarsPadding()` here (see AeroPage's KDoc).
 *
 * @param photoUrl the resolved URL of the vehicle's ALREADY-UPLOADED photo
 *   (edit mode). Null while adding — nothing is uploaded until the vehicle
 *   exists.
 * @param photoPreview JPEG/PNG bytes of a photo picked but NOT yet uploaded
 *   (add mode). Already sanitised by the caller. Takes precedence over
 *   [photoUrl] so the user sees their pick immediately.
 * @param onChangePhoto opens the picker; null hides the whole photo section
 *   (config-less build with no uploader wired).
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
    photoUrl: String? = null,
    photoPreview: ByteArray? = null,
    photoUploadStatus: ImageUploadStatus = ImageUploadStatus.Idle,
    onChangePhoto: (() -> Unit)? = null,
) {
    var make by rememberSaveable { mutableStateOf(initial.make) }
    var model by rememberSaveable { mutableStateOf(initial.model) }
    var year by rememberSaveable { mutableStateOf(initial.modelYear) }
    var engine by rememberSaveable { mutableStateOf(initial.engineDescription) }
    var powertrain by rememberSaveable { mutableStateOf(initial.powertrain) }
    var modifications by rememberSaveable { mutableStateOf(initial.modifications) }
    var showError by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(saveStatus) {
        if (saveStatus == VehicleSaveStatus.Saved) onCancel()
    }

    val form = VehicleForm(make, model, year, powertrain, engine, modifications)
    val error = VehicleValidation.validate(form, currentYear)

    AeroPage(
        title =
            stringResource(
                if (isEdit) R.string.garage_formTitleEdit else R.string.garage_formTitleCreate,
            ),
        modifier = modifier,
        // Tighter than the Aero default: a form of stacked fields reads better
        // at the 12dp rhythm it has always used.
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
    ) {
            if (onChangePhoto != null) {
                VehiclePhotoSection(
                    photoUrl = photoUrl,
                    photoPreview = photoPreview,
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
            // Exactly the four offered powertrains (Petrol / Diesel / Hybrid /
            // Electric) — plus, ONLY when the car being edited still holds a
            // retired value, that value as a final row. Without it the user
            // would see their plug-in hybrid with nothing selected and no way to
            // tell what it is. Choosing any of the four migrates the car forward;
            // the retired row then disappears for good.
            val powertrainOptions =
                remember(powertrain) {
                    VehiclePowertrain.selectable() +
                        listOfNotNull(powertrain?.takeIf { !it.isSelectable })
                }
            powertrainOptions.forEach { option ->
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

            OutlinedTextField(
                value = modifications,
                onValueChange = { modifications = it },
                label = { Text(text = stringResource(R.string.garage_modifications)) },
                minLines = 3,
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

/**
 * The photo tile + add/change button.
 *
 * [photoPreview] (a pick not yet uploaded, add mode) wins over [photoUrl] (an
 * uploaded photo, edit mode): the user's own pick is the freshest truth, and in
 * add mode there is no URL to compete with anyway. Coil renders `ByteArray`
 * models natively, so the pick needs no temp file or upload to be previewed.
 */
@Composable
private fun VehiclePhotoSection(
    photoUrl: String?,
    photoPreview: ByteArray?,
    uploadStatus: ImageUploadStatus,
    onChangePhoto: () -> Unit,
) {
    val uploading = uploadStatus == ImageUploadStatus.Uploading
    val model: Any? = photoPreview ?: photoUrl
    Box(
        // Round (circle-clipped) preview, matching how My Garage renders vehicle
        // photos. A square (1:1) box centre-crops the pick into the circle, so the
        // tile shown here is the round tile the garage will show.
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            // Coil renders nothing (keeps the placeholder) when the model does
            // not resolve.
            AsyncImage(
                model = model,
                contentDescription = stringResource(R.string.garage_photoAlt),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
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
                    model != null -> R.string.garage_photoChange
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
