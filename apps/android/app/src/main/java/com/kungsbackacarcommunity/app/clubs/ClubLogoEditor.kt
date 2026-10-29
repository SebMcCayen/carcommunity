package com.kungsbackacarcommunity.app.clubs

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.media.ImageCompressor
import com.kungsbackacarcommunity.app.media.ImageEditFrameShape
import com.kungsbackacarcommunity.app.media.ImageEditScreen
import com.kungsbackacarcommunity.app.media.NormalizedCropRect
import com.kungsbackacarcommunity.app.media.PickedImage
import com.kungsbackacarcommunity.app.media.rememberImagePickLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

internal const val CLUB_LOGO_READ_BYTES = 25L * 1024 * 1024
internal const val CLUB_LOGO_UPLOAD_BYTES = 256 * 1024

/** Always re-derive the upload from the original and the confirmed crop; never upload a preview. */
internal suspend fun prepareClubLogo(pick: PickedImage, crop: NormalizedCropRect, rotation: Float): PickedImage {
    var image = ImageCompressor.compressForPublicUpload(pick, 512, 80, crop, rotation)
    if (image != null && image.sizeBytes > CLUB_LOGO_UPLOAD_BYTES) {
        image = ImageCompressor.compressForPublicUpload(pick, 384, 65, crop, rotation)
    }
    if (image == null || image.contentType != "image/jpeg" || image.sizeBytes !in 1..CLUB_LOGO_UPLOAD_BYTES.toLong()) {
        throw ClubException("invalid-logo")
    }
    return image
}

/** Lives inside the membership-keyed composition: revocation cancels preparation/upload and clears the pick. */
@Composable
internal fun ClubLogoControl(
    clubId: String,
    repository: ClubRepository,
    enabled: Boolean,
    onBusy: (Boolean) -> Unit,
    onError: (Throwable?) -> Unit,
    onUpdated: () -> Unit,
) {
    var candidate by remember { mutableStateOf<PickedImage?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentEnabled by rememberUpdatedState(enabled)
    val picker = rememberImagePickLauncher(CLUB_LOGO_READ_BYTES,
        onPickFailed = { onError(ClubException("invalid-logo")) }) { picked ->
        if (picked != null && currentEnabled && !preparing) {
            preparing = true
            onBusy(true)
            onError(null)
            try {
                val decoded = ImageCompressor.decodeForCrop(picked, 1024)
                if (decoded == null) onError(ClubException("invalid-logo"))
                else { candidate = picked; preview = decoded }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { onError(ClubException("invalid-logo")) }
            finally { preparing = false; onBusy(false) }
        }
    }
    OutlinedButton(onClick = { onError(null); picker.pickImage() }, enabled = enabled && !preparing && preview == null) {
        Text(stringResource(R.string.clubs_logo))
    }
    Text(stringResource(R.string.clubLogo_requirements), style = MaterialTheme.typography.bodySmall)
    val shown = preview
    val picked = candidate
    if (shown != null && picked != null) {
        // Do not recycle in confirm/cancel: an outgoing Compose frame can still draw this bitmap.
        DisposableEffect(shown) { onDispose { shown.recycle() } }
        fun dismiss() { preview = null; candidate = null }
        Dialog(onDismissRequest = { dismiss() }, properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        )) {
            Surface(Modifier.fillMaxSize()) {
                ImageEditScreen(shown, ImageEditFrameShape.CIRCLE, 1f,
                    onConfirm = confirm@{ rotation, crop ->
                        if (preview !== shown) return@confirm
                        dismiss()
                        onBusy(true)
                        onError(null)
                        scope.launch {
                            try {
                                val safe = prepareClubLogo(picked, crop, rotation)
                                val path = repository.uploadLogo(clubId, safe.bytes)
                                repository.manage("update", mapOf("clubId" to clubId, "logoPath" to path), UUID.randomUUID().toString())
                                onUpdated()
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { onError(e) }
                            finally { onBusy(false) }
                        }
                    }, onCancel = { dismiss() }, modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                )
            }
        }
    }
}
