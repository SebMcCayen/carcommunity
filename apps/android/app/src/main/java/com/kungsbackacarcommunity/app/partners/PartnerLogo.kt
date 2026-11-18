package com.kungsbackacarcommunity.app.partners

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.kungsbackacarcommunity.app.media.PickedImage
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only server-approved Storage paths are placed in the public company model. */
@Composable
fun PartnerLogo(path: String?, modifier: Modifier = Modifier.size(64.dp)) {
    val url = rememberStorageImageUrl(LocalContext.current, path)
    if (url == null) {
        Icon(Icons.Filled.Storefront, contentDescription = null, modifier = modifier)
    } else {
        SubcomposeAsyncImage(model = url, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit,
            error = { Icon(Icons.Filled.Storefront, contentDescription = null) },
            loading = { Icon(Icons.Filled.Storefront, contentDescription = null) })
    }
}

/** PNG-only re-encode: preserves alpha and never forwards source EXIF/metadata. */
internal object PartnerLogoEncoding {
    const val MAX_BYTES = 5L * 1024 * 1024
    suspend fun encode(image: PickedImage): String = withContext(Dispatchers.Default) {
        require(image.bytes.size <= MAX_BYTES && image.contentType == "image/png")
        require(image.bytes.take(8).toByteArray().contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        require(bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000L)
        val decodeOptions = BitmapFactory.Options().apply {
            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
            inSampleSize = sample
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, decodeOptions))
        try {
            val scale = minOf(1f, 1024f / maxOf(bitmap.width, bitmap.height))
            val normalized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
            try {
                val bytes = ByteArrayOutputStream().use { out ->
                    check(normalized.compress(Bitmap.CompressFormat.PNG, 100, out))
                    out.toByteArray()
                }
                require(bytes.size <= MAX_BYTES)
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } finally { if (normalized !== bitmap) normalized.recycle() }
        } finally { bitmap.recycle() }
    }
}

/** Small local preview; original bytes never enter saved state or Storage URLs. */
@Composable
internal fun PartnerLogoPreview(base64: String) {
    val bitmap = androidx.compose.runtime.remember(base64) {
        runCatching {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull()
    }
    bitmap?.let {
        androidx.compose.foundation.Image(it.asImageBitmap(), contentDescription = null,
            modifier = Modifier.size(96.dp), contentScale = ContentScale.Fit)
    }
}
