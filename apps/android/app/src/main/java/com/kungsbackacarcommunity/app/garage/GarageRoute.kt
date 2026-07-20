package com.kungsbackacarcommunity.app.garage

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.kungsbackacarcommunity.app.media.NormalizedCropRect
import com.kungsbackacarcommunity.app.media.PickedImage
import com.kungsbackacarcommunity.app.media.rememberImagePickLauncher
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import java.time.Year
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Garage integration route (Phase 12 slice 13): owns the list ↔ form selection
 * and wires the coordinator into the stateless screens. Rendered directly as the
 * Garage TAB — the vehicle list and its "Add vehicle" affordance are the first
 * thing the tab shows, with no intermediate hub to tap through.
 *
 * [garageState] is the single garage stream hoisted to the shell so the garage
 * section holds exactly one vehicles snapshot listener; [onRetry] asks that
 * owner to re-subscribe after a listener error. [repository] remains for the
 * photo-path update callable.
 */
@Composable
fun GarageRoute(
    repository: GarageRepository,
    coordinator: GarageCoordinator?,
    uid: String,
    garageState: GarageState,
    onRetry: () -> Unit,
    mediaUploader: MediaUploader? = null,
    currentYear: Int = Year.now().value,
) {
    val scope = rememberCoroutineScope()
    var showForm by rememberSaveable { mutableStateOf(false) }
    var editingVehicleId by rememberSaveable { mutableStateOf<String?>(null) }

    // Photo state, hoisted above the form branch so an add-mode upload survives
    // the form closing on save (see pendingPhoto).
    val photoContext = LocalContext.current

    // Bumped every time the form OPENS, giving each add/edit session its own
    // upload coordinator. Sessions must NOT share one: an add-mode upload keeps
    // running after its form closes, and ImageUploadCoordinator's re-entrancy
    // Mutex would then make the NEXT session's upload a silent no-op — adding
    // two cars with photos in quick succession would drop the second photo with
    // no error. A fresh coordinator per session removes that contention; the
    // in-flight upload holds its own reference and completes regardless.
    var photoSession by rememberSaveable { mutableStateOf(0) }
    val photoCoordinator =
        remember(mediaUploader, photoSession) {
            mediaUploader?.let { ImageUploadCoordinator(it, MediaUpload.VEHICLE_IMAGE_MAX_BYTES) }
        }
    val photoStatus by
        (photoCoordinator?.status ?: flowOf(ImageUploadStatus.Idle))
            .collectAsState(initial = ImageUploadStatus.Idle)

    // A photo picked while ADDING a vehicle, already sanitised (EXIF/GPS
    // stripped) but not yet uploadable: vehicleImages/{uid}/{vehicleId}/ cannot
    // be keyed until garage-addVehicle mints the id. Held here and uploaded the
    // moment the add resolves. Plain `remember`, not `rememberSaveable`: the
    // bytes are far too large for the saved-instance Bundle (TransactionTooLarge),
    // so a process death drops the pending pick and the user re-picks — the
    // vehicle itself is unaffected because it is not created until Save.
    var pendingPhoto by remember { mutableStateOf<PickedImage?>(null) }

    // The pick currently being CROPPED, and its display-only preview decode.
    // Both are raw, unsanitised and deliberately inert: `cropCandidate` is the
    // ORIGINAL pick (it only ever reaches Storage by way of
    // compressForPublicUpload below) and `cropPreview` is a Bitmap, which has no
    // encoded form to upload at all. Plain `remember` for the same reason as
    // pendingPhoto — far too large for the saved-instance Bundle.
    var cropCandidate by remember { mutableStateOf<PickedImage?>(null) }
    var cropPreview by remember { mutableStateOf<Bitmap?>(null) }

    // Abandons an in-progress crop. Safe by construction at any point before
    // Use photo: nothing has been uploaded and no vehicle document has been
    // touched, so there is no half-uploaded image and no vehicle left pointing
    // at a path that does not exist — the pick is simply dropped.
    val cancelCrop = {
        cropCandidate = null
        cropPreview = null
    }

    // Clears the form's transient photo state. Called on every path that leaves
    // the form WITHOUT saving, so a pick abandoned in the add form can never
    // attach itself to the next vehicle the user starts adding.
    val resetPhoto = {
        pendingPhoto = null
        cancelCrop()
        photoCoordinator?.reset()
    }

    val saveStatus by
        (coordinator?.saveStatus ?: flowOf(VehicleSaveStatus.Idle))
            .collectAsState(initial = VehicleSaveStatus.Idle)

    // System/gesture Back leaves the add/edit form back to the list (mirrors the
    // form's Cancel); at the list root it is disabled so the shell's BackHandler
    // returns to Home. The photo coordinator now outlives the form branch (an
    // add-mode upload starts as the form closes), so backing out must reset it
    // explicitly rather than relying on it being discarded from composition.
    // While cropping, Back cancels the CROP and returns to the form — losing a
    // half-filled vehicle form because the user changed their mind about a photo
    // would be a nasty surprise.
    BackHandler(enabled = showForm) {
        if (cropPreview != null) {
            cancelCrop()
        } else {
            showForm = false
            editingVehicleId = null
            coordinator?.reset()
            resetPhoto()
        }
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

        // Photo upload is available whether ADDING or editing. The 10 MB cap
        // mirrors the storage rules. Wired only when the uploader is present
        // (config-less builds hide the button).
        val photoUrl = rememberStorageImageUrl(photoContext, vehicle?.imagePath)

        // THE one route from a picked image to Storage. Called after the user
        // confirms their crop; [crop] is a window, not pixels, so the ONLY thing
        // that ever turns a pick into uploadable bytes is
        // compressForPublicUpload below.
        //
        // Strip GPS + identifying metadata BEFORE upload: car profiles are
        // PUBLICLY visible to other members, so a photo taken at the owner's home
        // must never leak their coordinates or device fingerprint.
        // compressForPublicUpload GUARANTEES the returned bytes are free of every
        // STRIP_TAG (all GPS + identifying EXIF): the happy path re-encodes to
        // JPEG (dropping all metadata) — cutting the crop out of that same decode
        // — and if a pick can't be re-encoded it physically strips those tags or
        // returns the original only when proven free of them AND no crop was
        // asked for; else it returns null and we fail closed / skip the upload
        // rather than risk leaking source metadata. Vehicle photos keep a larger
        // longest-side cap than avatars so detail shots stay crisp.
        val sanitizeAndUpload: suspend (PickedImage, NormalizedCropRect) -> Unit =
            { picked, crop ->
                if (photoCoordinator != null) {
                    val sanitized =
                        ImageCompressor.compressForPublicUpload(
                            picked,
                            maxDimension = ImageCompressor.VEHICLE_MAX_DIMENSION,
                            crop = crop,
                        )
                    if (sanitized != null) {
                        if (editingId != null) {
                            // Edit: the vehicle exists, so its storage prefix is
                            // known — upload straight away.
                            val imageId = MediaUpload.newImageId(sanitized.contentType)
                            val path = MediaUpload.vehicleImagePath(uid, editingId, imageId)
                            photoCoordinator.upload(sanitized, path) { storedPath ->
                                repository.updateVehicleImagePath(editingId, storedPath)
                            }
                        } else {
                            // Add: no vehicle id exists yet to key
                            // vehicleImages/{uid}/{vehicleId}/, so hold the ALREADY
                            // SANITISED, ALREADY CROPPED bytes and upload them once
                            // Save mints the id. Sanitising here (not at upload
                            // time) means the raw pick never outlives this call.
                            pendingPhoto = sanitized
                        }
                    } else {
                        // Sanitisation failed (decode/re-encode returned null), so
                        // nothing was uploaded. Surface the failure instead of a
                        // silent no-op so the user knows to retry.
                        photoCoordinator.markFailed()
                    }
                }
            }

        val photoPicker =
            rememberImagePickLauncher(
                // Read above the upload cap so the raw pick reaches the compressor
                // (which downscales + re-encodes it below the cap). Still bounded;
                // the upload precheck on the sanitised result enforces
                // VEHICLE_IMAGE_MAX_BYTES.
                maxBytes = MediaUpload.VEHICLE_IMAGE_READ_MAX_BYTES,
            ) { picked ->
                if (picked != null && photoCoordinator != null) {
                    // Straight to the crop step — NOTHING is uploaded on picking
                    // any more. decodeForCrop hands back a display-only Bitmap;
                    // the pick's bytes are held untouched until the user confirms
                    // a crop, and then only sanitizeAndUpload consumes them.
                    val preview =
                        ImageCompressor.decodeForCrop(
                            picked,
                            maxDimension = ImageCompressor.VEHICLE_MAX_DIMENSION,
                        )
                    if (preview != null) {
                        cropCandidate = picked
                        cropPreview = preview
                    } else {
                        // Undecodable pick: it could not be shown, and it could
                        // not have been sanitised either. Fail visibly.
                        photoCoordinator.markFailed()
                    }
                }
            }

        val cropping = cropPreview
        val candidate = cropCandidate
        if (cropping != null && candidate != null) {
            // Release the preview's pixels as soon as it can no longer be drawn.
            // A VEHICLE_MAX_DIMENSION decode is several megabytes, and picking
            // photo after photo would otherwise leave a string of them alive
            // until the collector got round to them.
            //
            // onDispose, NOT the cancel/confirm handlers: recycle() on a bitmap
            // Compose is still drawing throws "Canvas: trying to use a recycled
            // bitmap". Clearing the state only SCHEDULES the screen's removal,
            // so recycling there races the outgoing frame. onDispose runs after
            // the subtree is gone, which is the first moment no draw can
            // reference it. Keyed on the bitmap so swapping previews releases
            // the outgoing one too.
            DisposableEffect(cropping) {
                onDispose { cropping.recycle() }
            }
            // The crop step REPLACES the form for as long as it is open rather
            // than stacking on top of it: the form's own fields keep their state
            // (they are rememberSaveable, and the `key(editingId)` below is
            // unchanged), so returning from the crop lands the user back on the
            // vehicle they were filling in.
            VehiclePhotoCropScreen(
                bitmap = cropping,
                onConfirm = { crop ->
                    cancelCrop()
                    // Route scope, not the crop screen's: the screen leaves
                    // composition on the line above, and a screen-scoped
                    // coroutine would be cancelled mid-sanitise.
                    scope.launch { sanitizeAndUpload(candidate, crop) }
                },
                onCancel = cancelCrop,
            )
        } else {
        key(editingId) {
            VehicleFormScreen(
                initial = initial,
                isEdit = editingId != null,
                saveStatus = saveStatus,
                currentYear = currentYear,
                onSave = { input ->
                    coordinator?.let { c ->
                        // Launched in the ROUTE's scope, not the form's: the form
                        // closes as soon as saveStatus flips to Saved, so a
                        // form-scoped coroutine would be cancelled mid-upload and
                        // the new vehicle would silently lose its photo.
                        scope.launch {
                            val photo = pendingPhoto
                            val vehicleId = c.save(input, editingId)
                            // Add-mode photo: the id exists only now. Nothing to do
                            // when editing (that photo uploaded when its crop was
                            // confirmed) or
                            // when the save failed (vehicleId null) — an upload
                            // under a nonexistent vehicle's prefix would be
                            // rejected by the callable's ownership check anyway.
                            if (vehicleId != null && editingId == null && photo != null && photoCoordinator != null) {
                                pendingPhoto = null
                                val imageId = MediaUpload.newImageId(photo.contentType)
                                val path = MediaUpload.vehicleImagePath(uid, vehicleId, imageId)
                                photoCoordinator.upload(photo, path) { storedPath ->
                                    repository.updateVehicleImagePath(vehicleId, storedPath)
                                }
                            }
                        }
                    }
                },
                onCancel = {
                    showForm = false
                    editingVehicleId = null
                    coordinator?.reset()
                    resetPhoto()
                },
                photoUrl = photoUrl,
                // Local preview of a not-yet-uploaded add-mode pick. Takes
                // precedence over photoUrl (which is null while adding anyway).
                photoPreview = pendingPhoto?.bytes,
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
        }
    } else {
        GarageScreen(
            state = garageState,
            onRetry = onRetry,
            onAdd = {
                editingVehicleId = null
                coordinator?.reset()
                // Fresh photo session: never inherit a previous car's pending
                // pick or upload status (see photoSession).
                pendingPhoto = null
                photoSession++
                showForm = true
            },
            onEdit = { vehicle ->
                editingVehicleId = vehicle.id
                coordinator?.reset()
                pendingPhoto = null
                photoSession++
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
        )
    }
}
