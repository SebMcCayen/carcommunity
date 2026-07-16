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
import com.kungsbackacarcommunity.app.media.ImageCompressor
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
 * and wires the coordinator into the stateless screens. [garageState] is the
 * single garage stream hoisted to the shell (shared with the garage hub's
 * main-car header avatar) so the whole garage section holds exactly one
 * vehicles snapshot listener; [onRetry] asks that owner to re-subscribe after
 * a listener error. [repository] remains for the photo-path update callable.
 */
@Composable
fun GarageRoute(
    repository: GarageRepository,
    coordinator: GarageCoordinator?,
    uid: String,
    garageState: GarageState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    mediaUploader: MediaUploader? = null,
    currentYear: Int = Year.now().value,
) {
    val scope = rememberCoroutineScope()
    var showForm by rememberSaveable { mutableStateOf(false) }
    var editingVehicleId by rememberSaveable { mutableStateOf<String?>(null) }

    val saveStatus by
        (coordinator?.saveStatus ?: flowOf(VehicleSaveStatus.Idle))
            .collectAsState(initial = VehicleSaveStatus.Idle)

    // System/gesture Back leaves the add/edit form back to the list (mirrors the
    // form's Cancel); at the list root it is disabled so the shell's BackHandler
    // returns to Home. Only the save coordinator is reset here; the form's photo
    // coordinator is form-scoped (remembered inside the form branch) and is simply
    // discarded when the form leaves composition — it has no self-reset on dispose.
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
                    modifications = it.modifications ?: "",
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
                // Read above the upload cap so the raw pick reaches the compressor
                // (which downscales + re-encodes it below the cap). Still bounded;
                // the upload precheck on the sanitised result enforces
                // VEHICLE_IMAGE_MAX_BYTES.
                maxBytes = MediaUpload.VEHICLE_IMAGE_READ_MAX_BYTES,
            ) { picked ->
                if (picked != null && photoCoordinator != null && editingId != null) {
                    // Strip GPS + identifying metadata BEFORE upload: car profiles
                    // are PUBLICLY visible to other members, so a photo taken at the
                    // owner's home must never leak their coordinates or device
                    // fingerprint. compressForPublicUpload GUARANTEES the returned
                    // bytes are free of every STRIP_TAG (all GPS + identifying EXIF):
                    // the happy path re-encodes to JPEG (dropping all metadata), and
                    // if a pick can't be re-encoded it physically strips those tags
                    // or returns the original only when proven free of them — else it
                    // returns null and we fail closed / skip the upload rather than
                    // risk leaking source metadata. Vehicle photos keep a larger
                    // longest-side cap than avatars so detail shots stay crisp.
                    val sanitized =
                        ImageCompressor.compressForPublicUpload(
                            picked,
                            maxDimension = ImageCompressor.VEHICLE_MAX_DIMENSION,
                        )
                    if (sanitized != null) {
                        val imageId = MediaUpload.newImageId(sanitized.contentType)
                        val path = MediaUpload.vehicleImagePath(uid, editingId, imageId)
                        photoCoordinator.upload(sanitized, path) { storedPath ->
                            repository.updateVehicleImagePath(editingId, storedPath)
                        }
                    } else {
                        // Sanitisation failed (decode/re-encode returned null), so
                        // nothing was uploaded. Surface the failure instead of a
                        // silent no-op so the user knows to retry.
                        photoCoordinator.markFailed()
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
            onRetry = onRetry,
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
            onSetMain = { id, isMain ->
                coordinator?.let { c ->
                    scope.launch {
                        try {
                            c.setMain(id, isMain)
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
