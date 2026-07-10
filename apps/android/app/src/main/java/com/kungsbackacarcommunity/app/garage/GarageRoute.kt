package com.kungsbackacarcommunity.app.garage

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kungsbackacarcommunity.app.media.ImageUploadCoordinator
import com.kungsbackacarcommunity.app.media.ImageUploadStatus
import com.kungsbackacarcommunity.app.media.MediaUpload
import com.kungsbackacarcommunity.app.media.MediaUploader
import com.kungsbackacarcommunity.app.media.rememberImagePickLauncher
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import java.time.Year
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Garage integration route (Phase 12 slice 13): owns the list ↔ form selection
 * and wires the repository flow + coordinator into the stateless screens.
 */
@Composable
fun GarageRoute(
    repository: GarageRepository,
    coordinator: GarageCoordinator?,
    uid: String,
    isActiveMember: Boolean,
    onBack: () -> Unit,
    mediaUploader: MediaUploader? = null,
    currentYear: Int = Year.now().value,
) {
    val scope = rememberCoroutineScope()
    var showForm by rememberSaveable { mutableStateOf(false) }
    var editingVehicleId by rememberSaveable { mutableStateOf<String?>(null) }

    val garageState by
        remember(repository, uid) { repository.observeGarage(uid) }
            .collectAsState(initial = GarageState.Loading)
    val saveStatus by
        (coordinator?.saveStatus ?: flowOf(VehicleSaveStatus.Idle))
            .collectAsState(initial = VehicleSaveStatus.Idle)

    // System/gesture Back leaves the add/edit form back to the list (mirrors the
    // form's Cancel); at the list root it is disabled so the shell's BackHandler
    // returns to Home. The form's photo coordinator resets itself on dispose.
    BackHandler(enabled = showForm) {
        showForm = false
        editingVehicleId = null
        coordinator?.reset()
    }

    if (showForm) {
        val editingId = editingVehicleId
        val vehicle =
            editingId?.let { id -> (garageState as? GarageState.Loaded)?.vehicles?.firstOrNull { it.id == id } }
        val initial =
            vehicle?.let {
                VehicleForm(
                    make = it.make,
                    model = it.model,
                    modelYear = it.modelYear.toString(),
                    powertrain = it.powertrain,
                    engineDescription = it.engineDescription ?: "",
                )
            } ?: VehicleForm()

        // Photo upload is available only when editing an existing vehicle: a
        // brand-new vehicle has no id yet to key vehicleImages/{uid}/{id}/. The
        // 10 MB cap + member gate mirror the storage rules (garage is member-
        // gated). Wired only when the uploader is present (config-less builds
        // hide the button).
        val photoContext = LocalContext.current
        val photoCoordinator =
            remember(mediaUploader, editingId) {
                if (mediaUploader != null && editingId != null) {
                    ImageUploadCoordinator(mediaUploader, MediaUpload.VEHICLE_IMAGE_MAX_BYTES)
                } else {
                    null
                }
            }
        val photoStatus by
            (photoCoordinator?.status ?: flowOf(ImageUploadStatus.Idle))
                .collectAsState(initial = ImageUploadStatus.Idle)
        val photoUrl = rememberStorageImageUrl(photoContext, vehicle?.imagePath)
        val photoPicker =
            rememberImagePickLauncher(
                maxBytes = MediaUpload.VEHICLE_IMAGE_MAX_BYTES,
            ) { picked ->
                if (picked != null && photoCoordinator != null && editingId != null) {
                    val imageId = MediaUpload.newImageId(picked.contentType)
                    val path = MediaUpload.vehicleImagePath(uid, editingId, imageId)
                    photoCoordinator.upload(picked, path) { storedPath ->
                        repository.updateVehicleImagePath(editingId, storedPath)
                    }
                }
            }

        key(editingId) {
            VehicleFormScreen(
                initial = initial,
                isEdit = editingId != null,
                saveStatus = saveStatus,
                currentYear = currentYear,
                onSave = { input ->
                    coordinator?.let { c -> scope.launch { c.save(input, editingId) } }
                },
                onCancel = {
                    showForm = false
                    editingVehicleId = null
                    coordinator?.reset()
                    photoCoordinator?.reset()
                },
                photoUrl = photoUrl,
                photoUploadStatus = photoStatus,
                onChangePhoto =
                    if (photoCoordinator != null) {
                        {
                            photoCoordinator.reset()
                            photoPicker.pickImage()
                        }
                    } else {
                        null
                    },
            )
        }
    } else {
        GarageScreen(
            state = garageState,
            isActiveMember = isActiveMember,
            onAdd = {
                editingVehicleId = null
                coordinator?.reset()
                showForm = true
            },
            onEdit = { vehicle ->
                editingVehicleId = vehicle.id
                coordinator?.reset()
                showForm = true
            },
            onDelete = { id ->
                coordinator?.let { c ->
                    scope.launch {
                        try {
                            c.delete(id)
                        } catch (cancellation: CancellationException) {
                            throw cancellation // never swallow coroutine cancellation
                        } catch (failure: Exception) {
                            // Fire-and-forget: the list observer reflects the result.
                        }
                    }
                }
            },
            onBack = onBack,
        )
    }
}
