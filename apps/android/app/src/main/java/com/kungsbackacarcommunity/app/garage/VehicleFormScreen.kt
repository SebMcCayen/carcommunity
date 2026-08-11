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
import androidx.compose.ui.text.input.KeyboardCapitalization
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.media.ImageUploadStatus
import com.kungsbackacarcommunity.app.shell.AeroPage
import java.util.Locale

/**
 * Which selector sheet is open. Only one at a time, so a single state value
 * beats three booleans that could disagree.
 */
enum class VehiclePicker { None, Make, Model, Year }

/**
 * Vehicle add/edit form (Phase 12 slice 13). Owns its field state; validates
 * against the backend bounds ([VehicleValidation]) before reporting a payload,
 * and closes on a successful save.
 *
 * Make, model and year are SELECTED from [VehicleCatalogue] through three
 * dependent selectors (choosing a manufacturer filters the models); there is no
 * free-text field for any of them, because per-manufacturer counts only work if
 * everyone's Volvo stores the same id. Editing a vehicle created BEFORE the
 * catalogue shows its saved free text under the empty selectors and asks the
 * owner to pick — nothing is guessed on their behalf, and nothing is lost if
 * they pick "Other / not listed".
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
 * @param onFormChange reports the form's current field values on every edit, so a
 *   host can observe dirtiness and persist a draft (see GarageRoute's new-car
 *   draft, issue #796). Default no-op — nothing else needs it, and the photo is
 *   deliberately NOT part of this (its bytes never belong in a draft).
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
    onFormChange: (VehicleForm) -> Unit = {},
) {
    var makeId by rememberSaveable { mutableStateOf(initial.makeId) }
    var modelId by rememberSaveable { mutableStateOf(initial.modelId) }
    var year by rememberSaveable { mutableStateOf(initial.modelYear) }
    var engine by rememberSaveable { mutableStateOf(initial.engineDescription) }
    var powertrain by rememberSaveable { mutableStateOf(initial.powertrain) }
    var modifications by rememberSaveable { mutableStateOf(initial.modifications) }
    var registrationPlate by rememberSaveable { mutableStateOf(initial.registrationPlate) }
    var showError by rememberSaveable { mutableStateOf(false) }
    var openPicker by rememberSaveable { mutableStateOf(VehiclePicker.None) }

    LaunchedEffect(saveStatus) {
        if (saveStatus == VehicleSaveStatus.Saved) onCancel()
    }

    val form =
        VehicleForm(
            makeId = makeId,
            modelId = modelId,
            modelYear = year,
            powertrain = powertrain,
            engineDescription = engine,
            modifications = modifications,
            registrationPlate = registrationPlate,
            legacyMake = initial.legacyMake,
            legacyModel = initial.legacyModel,
        )
    // Report the live form up on every real change (data-class equality as the
    // key, so it fires on content changes, not every recomposition). Fires once
    // on first composition too, seeding the host with the initial values.
    LaunchedEffect(form) { onFormChange(form) }
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

            // Make / model / year are SELECTED, never typed (2026-07): the
            // community can only count cars per manufacturer if every Volvo
            // stores the same `volvo` id. See VehicleCataloguePicker.
            val otherLabel = stringResource(R.string.garage_catalogueOther)
            // A vehicle created before the catalogue has no ids. Show the text its
            // owner originally typed underneath the empty selector so they can see
            // what they are replacing — we deliberately do NOT guess which
            // catalogue entry "Wolwo 245" meant, because a wrong guess would
            // silently corrupt the counts and mislabel their car.
            val legacyMakeHint =
                initial.legacyMake
                    .takeIf { it.isNotBlank() && makeId == null }
                    ?.let { stringResource(R.string.garage_legacySavedValue, it) }
            val legacyModelHint =
                initial.legacyModel
                    .takeIf { it.isNotBlank() && modelId == null }
                    ?.let { stringResource(R.string.garage_legacySavedValue, it) }

            CatalogueSelectorField(
                label = stringResource(R.string.garage_make),
                value = makeId?.let { id ->
                    if (id == VehicleCatalogue.OTHER_ID) otherLabel else VehicleCatalogue.makeName(id)
                },
                placeholder = stringResource(R.string.garage_selectMake),
                enabled = true,
                supportingText = legacyMakeHint,
                onClick = { openPicker = VehiclePicker.Make },
            )
            CatalogueSelectorField(
                label = stringResource(R.string.garage_model),
                value = modelId?.let { id ->
                    if (id == VehicleCatalogue.OTHER_ID) {
                        otherLabel
                    } else {
                        VehicleCatalogue.modelName(makeId, id)
                    }
                },
                // The cascade made visible: until a manufacturer is chosen there
                // is no model list to open, and the field says so rather than
                // opening an empty sheet.
                placeholder =
                    stringResource(
                        if (makeId == null) R.string.garage_selectMakeFirst else R.string.garage_selectModel,
                    ),
                enabled = makeId != null,
                supportingText = legacyModelHint,
                onClick = { openPicker = VehiclePicker.Model },
            )
            CatalogueSelectorField(
                label = stringResource(R.string.garage_modelYear),
                value = year?.toString(),
                placeholder = stringResource(R.string.garage_selectModelYear),
                enabled = true,
                onClick = { openPicker = VehiclePicker.Year },
            )
            if (legacyMakeHint != null) {
                // Why this vehicle suddenly asks for a selection, and the promise
                // that the saved text is not thrown away in the meantime.
                Text(
                    text = stringResource(R.string.garage_legacyReselectHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (makeId == VehicleCatalogue.OTHER_ID || modelId == VehicleCatalogue.OTHER_ID) {
                // Tell the owner their choice is recorded as a request, not a
                // dead end — an "other" selection is exactly how the catalogue
                // learns what it is missing.
                Text(
                    text = stringResource(R.string.garage_catalogueOtherHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (openPicker) {
                VehiclePicker.Make ->
                    MakePickerSheet(
                        onPick = { picked ->
                            // Switching manufacturer invalidates the model: model
                            // ids repeat across brands, so keeping the old one
                            // would silently produce a "Mazda MGB".
                            if (picked.id != makeId) modelId = null
                            makeId = picked.id
                            openPicker = VehiclePicker.None
                        },
                        onDismiss = { openPicker = VehiclePicker.None },
                    )
                VehiclePicker.Model ->
                    ModelPickerSheet(
                        makeId = makeId,
                        onPick = { picked ->
                            modelId = picked.id
                            openPicker = VehiclePicker.None
                        },
                        onDismiss = { openPicker = VehiclePicker.None },
                    )
                VehiclePicker.Year ->
                    ModelYearPickerSheet(
                        currentYear = currentYear,
                        onPick = { picked ->
                            year = picked
                            openPicker = VehiclePicker.None
                        },
                        onDismiss = { openPicker = VehiclePicker.None },
                    )
                VehiclePicker.None -> Unit
            }

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

            // Registration plate: optional and DELIBERATELY PUBLIC — it lands on
            // vehicles/{id}, whose read rule is `allow read: if isAuthenticated()`,
            // so ANY signed-in user can read it (not member-gated, not withdrawn
            // from suspended accounts). The supporting text below must keep saying
            // that plainly. Uppercased as the user types, matching the
            // backend normalisation; final trim/collapse happens in
            // VehicleValidation.normaliseRegistrationPlate. Pin Locale.ROOT so the
            // casing is locale-independent (Turkish 'i' -> 'I', never 'İ').
            OutlinedTextField(
                value = registrationPlate,
                onValueChange = { registrationPlate = it.uppercase(Locale.ROOT) },
                label = { Text(text = stringResource(R.string.garage_registrationPlate)) },
                supportingText = { Text(text = stringResource(R.string.garage_registrationPlateHint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
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
