package com.kungsbackacarcommunity.app.garage

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.media.ImageCompressor
import com.kungsbackacarcommunity.app.media.ImageEditFrameShape
import com.kungsbackacarcommunity.app.media.ImageEditScreen
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
 *
 * [onFormOpenChange] reports whether the add/edit form is currently open, so the
 * host panel can DISARM its nested-scroll pull-dismiss while a form is up (issue
 * #796) — a fast scroll-to-top must not over-scroll into a panel dismiss.
 * [dismissRequestTick] is bumped by the host each time a panel-owned dismiss
 * (drag-handle, outside-tap, accessibility) fires WHILE the form is open, so that
 * gesture routes through the SAME confirm-if-dirty + cleanup path as Back instead
 * of tearing the form down and stranding the nav state.
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
    onFormOpenChange: (Boolean) -> Unit = {},
    dismissRequestTick: Int = 0,
) {
    val scope = rememberCoroutineScope()
    var showForm by rememberSaveable { mutableStateOf(false) }
    var editingVehicleId by rememberSaveable { mutableStateOf<String?>(null) }
    // The car whose full detail page is open (tapped from the list), or null on
    // the list itself. Survives config changes; the form takes precedence over
    // it (editing a car from its detail page opens the form on top, and closing
    // the form lands back on the detail because this id is untouched).
    var detailVehicleId by rememberSaveable { mutableStateOf<String?>(null) }

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

    // ── New-car draft + accidental-dismiss guard (issue #796) ───────────────────
    //
    // The add form's text fields and catalogue selections live inside
    // VehicleFormScreen; they are mirrored up here (via onFormChange) so this
    // route — which owns showForm and the coordinator — can tell whether the form
    // is DIRTY, persist a draft, and run one cleanup for every dismissal.
    val draftStore = remember(photoContext) { VehicleFormDraftStore(photoContext) }

    // The seed the add form opens with. Empty for a normal add; the restored
    // draft after the user accepts the "continue your unsaved car?" prompt. The
    // key below is bumped alongside it so VehicleFormScreen re-seeds its fields
    // (rememberSaveable only reads `initial` on first composition for a given key).
    var addFormInitial by remember { mutableStateOf(VehicleForm()) }
    var addFormSeedKey by remember { mutableIntStateOf(0) }

    // The add form's LIVE values, pushed up from VehicleFormScreen on every edit.
    // Drives the dirty test (discard confirm + whether a draft is worth writing)
    // and IS what gets serialised to the draft. Not rememberSaveable: the durable
    // copy is the on-disk draft, which survives process death where this would be
    // dropped for the exact TransactionTooLarge reason pendingPhoto is.
    var addFormCurrent by remember { mutableStateOf(VehicleForm()) }

    // Overlay prompts. discard = "throw away the new car you're dismissing?";
    // restore = "continue the unsaved car from last time?" with the draft to seed.
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var pendingDraft by remember { mutableStateOf<VehicleForm?>(null) }

    // Whether a dismissal of the open form should confirm first. Only a dirty NEW
    // car: edit has a saved vehicle to fall back to, an untouched add has nothing
    // to lose.
    val addFormDirty =
        VehicleFormDraftStore.shouldConfirmDismiss(
            isAddMode = editingVehicleId == null,
            form = addFormCurrent,
        )

    // THE single cleanup, run by every clean exit from the form (save, in-form
    // Cancel, Back, and a confirmed discard). Resets exactly the nav/coordinator
    // state the Back path always reset, PLUS the draft — so a clean exit never
    // leaves showForm stranded (the stuck-menu bug) and never leaves a draft to
    // be offered again. A draft therefore only survives the UNCLEAN exits this is
    // NOT called on: a tab switch or process death.
    val closeAddForm = {
        showForm = false
        editingVehicleId = null
        coordinator?.reset()
        resetPhoto()
        draftStore.clear()
        addFormCurrent = VehicleForm()
    }

    // THE one dismiss handler for the open form. A dirty new car asks first
    // ("Discard new car?"); anything else (clean, or an edit) closes straight
    // away. Gesture (drag-handle / outside-tap / a11y, via dismissRequestTick),
    // outside-tap and Back all funnel through here, so they can never diverge.
    val requestFormDismiss = {
        if (addFormDirty) showDiscardConfirm = true else closeAddForm()
    }

    // Report the form-open state up so the host panel can disarm its
    // nested-scroll pull-dismiss while a form is open (issue #796, fix 1).
    LaunchedEffect(showForm) { onFormOpenChange(showForm) }

    // Keep the on-disk draft in step with the open add form. Writing on change is
    // cheap (async apply) and being on disk it outlives a process death — which is
    // the whole point: the in-memory addFormCurrent would not. Emptying every
    // field by hand CLEARS the draft, so a later unclean exit (tab switch /
    // process death) can never resurrect content the user actually deleted. The
    // restore prompt is a hands-off window: the empty form rendered underneath it
    // must not wipe the very draft being offered.
    LaunchedEffect(addFormCurrent, showForm, editingVehicleId, pendingDraft) {
        if (showForm && editingVehicleId == null) {
            when {
                pendingDraft != null -> Unit
                VehicleFormDraftStore.hasUserContent(addFormCurrent) ->
                    draftStore.write(addFormCurrent, System.currentTimeMillis())
                else -> draftStore.clear()
            }
        }
    }

    // A panel-owned dismiss (drag-handle, outside-tap, accessibility) arrived
    // while the form is open: route it through the same confirm/cleanup as Back.
    // Seeded equal so the initial composition is not treated as a dismiss; only a
    // genuine host bump (tick change) fires it.
    var handledDismissTick by remember { mutableIntStateOf(dismissRequestTick) }
    LaunchedEffect(dismissRequestTick) {
        if (dismissRequestTick != handledDismissTick) {
            handledDismissTick = dismissRequestTick
            if (showForm) requestFormDismiss()
        }
    }

    // Detail-page "add more photos" pipeline — independent of the form's, so the
    // two never contend on one coordinator. The vehicle already exists on the
    // detail page, so an added photo uploads immediately (no pending-photo dance
    // like the add form): a single coordinator is reused across sequential adds,
    // reset before each pick. Same crop -> compress -> EXIF/GPS-strip route.
    val detailPhotoCoordinator =
        remember(mediaUploader) {
            mediaUploader?.let { ImageUploadCoordinator(it, MediaUpload.VEHICLE_IMAGE_MAX_BYTES) }
        }
    val detailPhotoStatus by
        (detailPhotoCoordinator?.status ?: flowOf(ImageUploadStatus.Idle))
            .collectAsState(initial = ImageUploadStatus.Idle)
    // The pick being cropped for the detail gallery, and its display-only decode
    // (same inert/raw handling as the form's cropCandidate/cropPreview).
    var detailCropCandidate by remember { mutableStateOf<PickedImage?>(null) }
    var detailCropPreview by remember { mutableStateOf<Bitmap?>(null) }
    val cancelDetailCrop = {
        detailCropCandidate = null
        detailCropPreview = null
    }
    // Drop any in-progress detail crop + upload status when the open car changes
    // (or the detail closes), so a pick abandoned on one car can never surface a
    // stale (recycled) bitmap on the next, and the "Uploading…" state resets.
    LaunchedEffect(detailVehicleId) {
        cancelDetailCrop()
        detailPhotoCoordinator?.reset()
    }

    val saveStatus by
        (coordinator?.saveStatus ?: flowOf(VehicleSaveStatus.Idle))
            .collectAsState(initial = VehicleSaveStatus.Idle)

    // The car whose detail page is open, resolved from the live list. Null when
    // no detail is open OR when the id points at a car that no longer exists
    // (e.g. just deleted); the effect below then clears the stale id so Back and
    // the render branch agree.
    val loaded = garageState as? GarageState.Loaded
    val detailVehicle =
        detailVehicleId?.let { id -> loaded?.vehicles?.firstOrNull { it.id == id } }
    LaunchedEffect(loaded, detailVehicleId) {
        if (detailVehicleId != null && loaded != null && detailVehicle == null) {
            detailVehicleId = null
        }
    }

    // System/gesture Back unwinds one level at a time: crop → form, form → the
    // place it was opened from (list or detail), detail → list. At the list root
    // it is disabled so the shell's BackHandler returns to Home. The photo
    // coordinator now outlives the form branch (an add-mode upload starts as the
    // form closes), so backing out must reset it explicitly rather than relying
    // on it being discarded from composition. While cropping, Back cancels the
    // CROP and returns to the form — losing a half-filled vehicle form because
    // the user changed their mind about a photo would be a nasty surprise.
    BackHandler(enabled = showForm || detailVehicleId != null) {
        when {
            showForm && cropPreview != null -> cancelCrop()
            // Same unified handler as the panel gesture/outside-tap: a dirty new
            // car asks before it is thrown away, everything else cleans up and
            // closes (issue #796).
            showForm -> requestFormDismiss()
            // Cropping a detail-page "add more photos" pick: Back cancels the
            // crop and returns to the detail page, not all the way to the list.
            detailCropPreview != null -> cancelDetailCrop()
            else -> detailVehicleId = null
        }
    }

    // "Discard new car?" — shown when a dismissal is attempted on a dirty add
    // form. Confirming runs the same cleanup as every other exit (clearing the
    // draft); dismissing the dialog keeps the user in the form with their input.
    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(text = stringResource(R.string.garage_discardNewTitle)) },
            text = { Text(text = stringResource(R.string.garage_discardNewMessage)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    closeAddForm()
                }) {
                    Text(text = stringResource(R.string.garage_discardNewConfirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(text = stringResource(R.string.garage_discardNewKeepEditing))
                }
            },
        )
    }

    // "Continue your unsaved car?" — offered when the add form is reopened within
    // the draft TTL. Continue re-seeds the form from the draft; Start over throws
    // the draft away and keeps the fresh, empty form already on screen.
    val draftToRestore = pendingDraft
    if (draftToRestore != null) {
        AlertDialog(
            onDismissRequest = {
                // Treat a scrim tap as "start over": leaving the draft in place
                // would re-prompt on the next open with no way to have decided.
                draftStore.clear()
                pendingDraft = null
            },
            title = { Text(text = stringResource(R.string.garage_draftRestoreTitle)) },
            text = { Text(text = stringResource(R.string.garage_draftRestoreMessage)) },
            confirmButton = {
                TextButton(onClick = {
                    addFormInitial = draftToRestore
                    addFormCurrent = draftToRestore
                    addFormSeedKey++
                    pendingDraft = null
                }) {
                    Text(text = stringResource(R.string.garage_draftRestoreContinue))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    draftStore.clear()
                    pendingDraft = null
                }) {
                    Text(text = stringResource(R.string.garage_draftRestoreStartOver))
                }
            },
        )
    }

    if (showForm) {
        val editingId = editingVehicleId
        val vehicle =
            editingId?.let { id -> (garageState as? GarageState.Loaded)?.vehicles?.firstOrNull { it.id == id } }
        val initial =
            vehicle?.let {
                VehicleForm(
                    // Only real catalogue ids pre-select anything. A vehicle from
                    // before the catalogue opens with EMPTY selectors and its saved
                    // text carried alongside (legacyMake/legacyModel) — we refuse
                    // to guess which catalogue entry the old free text meant, and
                    // the owner sees exactly what is being replaced.
                    makeId = it.makeId,
                    modelId = it.modelId,
                    legacyMake = it.make,
                    legacyModel = it.model,
                    modelYear = it.modelYear,
                    powertrain = it.powertrain,
                    engineDescription = it.engineDescription ?: "",
                    modifications = it.modifications ?: "",
                    registrationPlate = it.registrationPlate ?: "",
                )
                // Add mode seeds from addFormInitial (empty, or a restored draft);
                // a mid-edit vehicle that vanished falls back to an empty form.
            } ?: if (editingId == null) addFormInitial else VehicleForm()

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
        val sanitizeAndUpload: suspend (PickedImage, Float, NormalizedCropRect) -> Unit =
            { picked, rotationDegrees, crop ->
                if (photoCoordinator != null) {
                    val sanitized =
                        ImageCompressor.compressForPublicUpload(
                            picked,
                            maxDimension = ImageCompressor.VEHICLE_MAX_DIMENSION,
                            crop = crop,
                            rotationDegrees = rotationDegrees,
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
            // (they are rememberSaveable, and the `key(editingId, addFormSeedKey)`
            // below is unchanged), so returning from the crop lands the user back
            // on the vehicle they were filling in.
            ImageEditScreen(
                bitmap = cropping,
                // Vehicle/other photos get the free-form frame (draggable edges),
                // started at the source's own aspect.
                frameShape = ImageEditFrameShape.FREEFORM,
                initialAspect = cropping.width.toFloat() / cropping.height.toFloat(),
                onConfirm = { rotationDegrees, crop ->
                    cancelCrop()
                    // Route scope, not the crop screen's: the screen leaves
                    // composition on the line above, and a screen-scoped
                    // coroutine would be cancelled mid-sanitise.
                    scope.launch { sanitizeAndUpload(candidate, rotationDegrees, crop) }
                },
                onCancel = cancelCrop,
            )
        } else {
        // addFormSeedKey re-seeds the add form's fields when a draft is restored
        // (rememberSaveable only reads `initial` on the first composition per key).
        key(editingId, addFormSeedKey) {
            VehicleFormScreen(
                initial = initial,
                isEdit = editingId != null,
                saveStatus = saveStatus,
                currentYear = currentYear,
                // Mirror the add form's live values up so this route can compute
                // dirtiness and persist the draft. Edit fires this too; it is
                // ignored because the draft/dirty logic is gated on add mode.
                onFormChange = { addFormCurrent = it },
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
                // The in-form Cancel button AND the post-save auto-close both land
                // here; closeAddForm runs the shared cleanup and clears the draft
                // (a successful save and an explicit Cancel are both "done with
                // this draft"). The accidental gesture/Back paths do NOT reach
                // here — they go through requestFormDismiss so a dirty new car is
                // confirmed first.
                onCancel = closeAddForm,
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
    } else if (detailVehicle != null) {
        // THE one route from a detail-page "add more photos" pick to Storage:
        // pick -> crop -> compressForPublicUpload (crop + downscale + EXIF/GPS
        // strip, from #407/#479) -> putBytes -> garage-addVehiclePhoto records
        // the path. Never a raw upload — same guarantee as the single-photo edit.
        val detailSanitizeAndUpload: suspend (PickedImage, Float, NormalizedCropRect) -> Unit =
            { picked, rotationDegrees, crop ->
                if (detailPhotoCoordinator != null) {
                    val sanitized =
                        ImageCompressor.compressForPublicUpload(
                            picked,
                            maxDimension = ImageCompressor.VEHICLE_MAX_DIMENSION,
                            crop = crop,
                            rotationDegrees = rotationDegrees,
                        )
                    if (sanitized != null) {
                        val imageId = MediaUpload.newImageId(sanitized.contentType)
                        val path = MediaUpload.vehicleImagePath(uid, detailVehicle.id, imageId)
                        detailPhotoCoordinator.upload(sanitized, path) { storedPath ->
                            repository.addVehiclePhoto(detailVehicle.id, storedPath)
                        }
                    } else {
                        // Sanitisation failed → nothing uploaded; surface it.
                        detailPhotoCoordinator.markFailed()
                    }
                }
            }

        val detailPhotoPicker =
            rememberImagePickLauncher(
                maxBytes = MediaUpload.VEHICLE_IMAGE_READ_MAX_BYTES,
            ) { picked ->
                if (picked != null && detailPhotoCoordinator != null) {
                    val preview =
                        ImageCompressor.decodeForCrop(
                            picked,
                            maxDimension = ImageCompressor.VEHICLE_MAX_DIMENSION,
                        )
                    if (preview != null) {
                        detailCropCandidate = picked
                        detailCropPreview = preview
                    } else {
                        detailPhotoCoordinator.markFailed()
                    }
                }
            }

        val detailCropping = detailCropPreview
        val detailCandidate = detailCropCandidate
        if (detailCropping != null && detailCandidate != null) {
            // Release the preview's pixels once it can no longer be drawn (same
            // onDispose rationale as the form crop).
            DisposableEffect(detailCropping) {
                onDispose { detailCropping.recycle() }
            }
            // The crop step REPLACES the detail page while open; Back cancels it
            // (handled in the BackHandler) and returns here.
            ImageEditScreen(
                bitmap = detailCropping,
                frameShape = ImageEditFrameShape.FREEFORM,
                initialAspect = detailCropping.width.toFloat() / detailCropping.height.toFloat(),
                onConfirm = { rotationDegrees, crop ->
                    cancelDetailCrop()
                    scope.launch {
                        detailSanitizeAndUpload(detailCandidate, rotationDegrees, crop)
                    }
                },
                onCancel = cancelDetailCrop,
            )
        } else {
            VehicleDetailScreen(
                vehicle = detailVehicle,
                onEdit = {
                    // Opens the SAME add/edit form on top of the detail page; because
                    // detailVehicleId is left set, closing the form (save or cancel)
                    // lands the user back on this car's detail page.
                    editingVehicleId = detailVehicle.id
                    coordinator?.reset()
                    pendingPhoto = null
                    photoSession++
                    showForm = true
                },
                onDelete = {
                    // Return to the list immediately; the delete runs in the
                    // background and the list observer reflects the result. Leaving
                    // the detail open would show a car that is being removed.
                    val id = detailVehicle.id
                    detailVehicleId = null
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
                onSetMain = { isMain ->
                    val id = detailVehicle.id
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
                // Add more photos through the crop/compress/EXIF-strip pipeline
                // above. Null (button hidden) when there is no uploader.
                onAddPhoto =
                    if (detailPhotoCoordinator != null) {
                        {
                            detailPhotoCoordinator.reset()
                            detailPhotoPicker.pickImage()
                        }
                    } else {
                        null
                    },
                onSetCover = { path ->
                    // Reorder the current gallery so the chosen photo is first
                    // (cover). moveToCover yields a permutation, which the
                    // reorderVehiclePhotos callable requires.
                    val id = detailVehicle.id
                    val ordered =
                        VehicleGallery.moveToCover(VehicleGallery.photoPaths(detailVehicle), path)
                    coordinator?.let { c ->
                        scope.launch {
                            try {
                                c.reorderPhotos(id, ordered)
                            } catch (cancellation: CancellationException) {
                                throw cancellation // never swallow coroutine cancellation
                            } catch (failure: Exception) {
                                // Fire-and-forget: the list observer reflects the result.
                            }
                        }
                    }
                },
                onRemovePhoto = { path ->
                    val id = detailVehicle.id
                    coordinator?.let { c ->
                        scope.launch {
                            try {
                                c.removePhoto(id, path)
                            } catch (cancellation: CancellationException) {
                                throw cancellation // never swallow coroutine cancellation
                            } catch (failure: Exception) {
                                // Fire-and-forget: the list observer reflects the result.
                            }
                        }
                    }
                },
                isUploadingPhoto = detailPhotoStatus == ImageUploadStatus.Uploading,
            )
        }
    } else {
        GarageScreen(
            state = garageState,
            onRetry = onRetry,
            onOpen = { vehicle -> detailVehicleId = vehicle.id },
            onAdd = {
                editingVehicleId = null
                coordinator?.reset()
                // Fresh photo session: never inherit a previous car's pending
                // pick or upload status (see photoSession).
                pendingPhoto = null
                photoSession++
                // Open on a clean, empty form; re-seed via addFormSeedKey.
                addFormInitial = VehicleForm()
                addFormCurrent = VehicleForm()
                addFormSeedKey++
                showForm = true
                // Offer to continue an unsaved car left within the TTL (a tab
                // switch or process death, since a clean exit clears the draft).
                // readFresh also sweeps away a stale one.
                val fresh = draftStore.readFresh(System.currentTimeMillis())
                if (fresh != null) pendingDraft = fresh.form
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
