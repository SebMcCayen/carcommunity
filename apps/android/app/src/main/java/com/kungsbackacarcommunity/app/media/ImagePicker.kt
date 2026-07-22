package com.kungsbackacarcommunity.app.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import java.io.InputStream
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

/**
 * The three distinct outcomes of a photo pick, so a caller can tell a user who
 * BACKED OUT (say nothing) apart from a pick that FAILED TO READ (surface an
 * error). Collapsing both into a single `null` — as the picker did before — made
 * a real pick that could not be read (a cloud-only Google Photos item that never
 * finished downloading, an unreadable/oversized file, a content-resolver error)
 * indistinguishable from a cancel, so the caller stayed silent and the user saw
 * "nothing happen" after choosing a photo.
 */
internal sealed interface PickOutcome {
    /** The user dismissed the picker without choosing anything. */
    data object Cancelled : PickOutcome

    /** A photo WAS chosen but its bytes could not be read (see above). */
    data object Failed : PickOutcome

    /** A photo was chosen and read successfully. */
    data class Picked(val image: PickedImage) : PickOutcome
}

/**
 * Pure mapping from a raw pick [source] (the picker's nullable result) to a
 * [PickOutcome], extracted from the Compose launcher so the cancel-vs-failure
 * decision is unit-testable without an Android [Uri]/[Context]. A null [source]
 * is a cancel; otherwise [read] runs and a null read is a genuine failure.
 */
internal suspend fun <T : Any> resolvePickOutcome(
    source: T?,
    read: suspend (T) -> PickedImage?,
): PickOutcome =
    if (source == null) {
        PickOutcome.Cancelled
    } else {
        read(source)?.let { PickOutcome.Picked(it) } ?: PickOutcome.Failed
    }

/**
 * @param maxBytes hard byte cap for the read. A pick whose size is known to
 *   exceed the cap is rejected before any bytes are read; an unknown-size
 *   stream is read with a bound of `maxBytes` and rejected if it overflows, so
 *   an oversized pick can never materialize an unbounded byte array (OOM).
 *   Defaults to the largest media cap ([MediaUpload.VEHICLE_IMAGE_MAX_BYTES]).
 *   The per-flow cap is still enforced exactly by the upload precheck.
 * @param onPickFailed invoked when a photo WAS chosen but could not be read, so
 *   the caller can surface a failure instead of silently doing nothing. When
 *   null (the default), a failed read falls back to `onPicked(null)` — matching
 *   the historical behaviour — so existing callers are unaffected. A genuine
 *   user cancel is ALWAYS routed to `onPicked(null)`, never to this, so backing
 *   out of the picker never shows an error.
 */
@Composable
fun rememberImagePickLauncher(
    maxBytes: Long = MediaUpload.VEHICLE_IMAGE_MAX_BYTES,
    onPickFailed: (() -> Unit)? = null,
    onPicked: suspend (PickedImage?) -> Unit,
): ImagePickLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            scope.launch {
                when (val outcome = resolvePickOutcome(uri) { readPickedImage(context, it, maxBytes) }) {
                    PickOutcome.Cancelled -> onPicked(null)
                    PickOutcome.Failed -> if (onPickFailed != null) onPickFailed() else onPicked(null)
                    is PickOutcome.Picked -> onPicked(outcome.image)
                }
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

/**
 * Reads bytes + content type for a content [uri]; null if it cannot be read OR
 * the pick exceeds [maxBytes]. The cap is enforced BEFORE materializing an
 * unbounded array: a resolver-reported size over the cap is rejected without
 * reading, and streams of unknown size are read with a bound so an oversized
 * pick fails instead of OOM-ing.
 */
suspend fun readPickedImage(
    context: Context,
    uri: Uri,
    maxBytes: Long = MediaUpload.VEHICLE_IMAGE_MAX_BYTES,
): PickedImage? =
    withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val contentType = resolver.getType(uri)
            // Cheap up-front rejection when the provider reports a size.
            val declaredSize = queryDeclaredSize(context, uri)
            if (declaredSize != null && declaredSize > maxBytes) return@runCatching null
            val bytes =
                resolver.openInputStream(uri)?.use { readBounded(it, maxBytes) }
                    ?: return@runCatching null
            PickedImage(bytes = bytes, contentType = contentType)
        }.getOrNull()
    }

/**
 * Queries the content provider for the pick's declared size in bytes, or null
 * when it is unknown (a null column / no OpenableColumns.SIZE support).
 */
private fun queryDeclaredSize(context: Context, uri: Uri): Long? =
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
        }

/**
 * Reads at most [maxBytes] from [input], returning the bytes; null if the
 * stream holds more than [maxBytes] (so a huge, unknown-size pick can never
 * materialize an unbounded array). Reads one extra byte to detect overflow.
 */
internal fun readBounded(input: InputStream, maxBytes: Long): ByteArray? {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val out = java.io.ByteArrayOutputStream()
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        total += read
        // Over the cap: bail without materializing the whole (oversized) pick.
        if (total > maxBytes) return null
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
