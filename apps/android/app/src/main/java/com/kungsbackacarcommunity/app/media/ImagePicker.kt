package com.kungsbackacarcommunity.app.media

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A remembered image-pick launcher backed by the Photo Picker
 * ([ActivityResultContracts.PickVisualMedia]) — no runtime permission needed on
 * any supported API level. Reads the picked image's bytes + content type off the
 * main thread and hands a [PickedImage] (or null when the user cancelled / the
 * read failed) to [onPicked].
 */
class ImagePickLauncher internal constructor(
    private val launch: () -> Unit,
) {
    fun pickImage() = launch()
}

@Composable
fun rememberImagePickLauncher(
    onPicked: suspend (PickedImage?) -> Unit,
): ImagePickLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            scope.launch {
                val picked = uri?.let { readPickedImage(context, it) }
                onPicked(picked)
            }
        }
    return remember(launcher) {
        ImagePickLauncher {
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
}

/** Reads bytes + content type for a content [uri]; null if it cannot be read. */
suspend fun readPickedImage(context: Context, uri: Uri): PickedImage? =
    withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val contentType = resolver.getType(uri)
            val bytes =
                resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
            PickedImage(bytes = bytes, contentType = contentType)
        }.getOrNull()
    }
