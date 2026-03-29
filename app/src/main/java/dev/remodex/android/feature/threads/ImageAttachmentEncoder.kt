package dev.remodex.android.feature.threads

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import dev.remodex.android.model.ImageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

private const val maxAttachmentDimensionPx = 2048
private const val attachmentJpegQuality = 82
private const val attachmentThumbnailDimensionPx = 320
private const val attachmentThumbnailJpegQuality = 70

suspend fun encodeImageAttachment(
    contentResolver: ContentResolver,
    uri: Uri,
): ImageAttachment? = withContext(Dispatchers.IO) {
    val uriString = uri.toString()
    val encodedImage = contentResolver.buildEncodedImageAttachment(uri) ?: return@withContext null
    ImageAttachment(
        id = UUID.randomUUID().toString(),
        uri = uriString,
        thumbnailUri = encodedImage.thumbnailDataUrl,
        payloadDataUrl = encodedImage.payloadDataUrl,
    )
}

private data class EncodedImageAttachment(
    val payloadDataUrl: String,
    val thumbnailDataUrl: String?,
)

private fun ContentResolver.buildEncodedImageAttachment(uri: Uri): EncodedImageAttachment? {
    val originalBytes = openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (originalBytes.isEmpty()) {
        return null
    }

    val detectedMimeType = getType(uri)?.trim()?.takeIf { it.startsWith("image/") }
    val normalizedJpeg = normalizeImageToJpeg(originalBytes, maxAttachmentDimensionPx, attachmentJpegQuality)
    return if (normalizedJpeg != null) {
        val thumbnailDataUrl = normalizeImageToJpeg(
            normalizedJpeg,
            attachmentThumbnailDimensionPx,
            attachmentThumbnailJpegQuality,
        )?.let { thumbnailBytes ->
            "data:image/jpeg;base64,${Base64.encodeToString(thumbnailBytes, Base64.NO_WRAP)}"
        }
        EncodedImageAttachment(
            payloadDataUrl = "data:image/jpeg;base64,${Base64.encodeToString(normalizedJpeg, Base64.NO_WRAP)}",
            thumbnailDataUrl = thumbnailDataUrl,
        )
    } else {
        val fallbackMimeType = detectedMimeType ?: "image/jpeg"
        EncodedImageAttachment(
            payloadDataUrl = "data:$fallbackMimeType;base64,${Base64.encodeToString(originalBytes, Base64.NO_WRAP)}",
            thumbnailDataUrl = null,
        )
    }
}

private fun normalizeImageToJpeg(
    sourceBytes: ByteArray,
    maxDimensionPx: Int,
    jpegQuality: Int,
): ByteArray? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimensionPx)
    }
    val decodedBitmap = BitmapFactory.decodeByteArray(
        sourceBytes,
        0,
        sourceBytes.size,
        decodeOptions,
    ) ?: return null

    val scaledBitmap = decodedBitmap.scaleDownIfNeeded(maxDimensionPx)
    val output = ByteArrayOutputStream()
    val didCompress = scaledBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)

    if (scaledBitmap !== decodedBitmap) {
        scaledBitmap.recycle()
    }
    decodedBitmap.recycle()

    if (!didCompress) {
        return null
    }
    return output.toByteArray()
}

private fun Bitmap.scaleDownIfNeeded(maxDimension: Int): Bitmap {
    val longestSide = maxOf(width, height)
    if (longestSide <= maxDimension) {
        return this
    }

    val scale = maxDimension.toFloat() / longestSide.toFloat()
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
}

private fun calculateInSampleSize(width: Int, height: Int, maxDimensionPx: Int): Int {
    var inSampleSize = 1
    var halfWidth = width / 2
    var halfHeight = height / 2

    while ((halfWidth / inSampleSize) >= maxDimensionPx
        || (halfHeight / inSampleSize) >= maxDimensionPx
    ) {
        inSampleSize *= 2
    }

    return inSampleSize.coerceAtLeast(1)
}

// --- History thumbnail generation ---

private const val historyThumbnailDimensionPx = 160
private const val historyThumbnailJpegQuality = 75

/**
 * Decodes a `data:image/...;base64,...` URI and generates a small JPEG thumbnail,
 * returned as a `data:image/jpeg;base64,...` string. This mirrors iOS's
 * `makeThumbnailBase64JPEG(from:)` which always generates a persistent thumbnail
 * during history decode so old-conversation images are always renderable.
 */
internal fun generateThumbnailFromDataUri(dataUri: String): String? {
    val imageBytes = decodeDataUriToBytes(dataUri) ?: return null
    val thumbnailBytes = normalizeImageToJpeg(
        imageBytes,
        historyThumbnailDimensionPx,
        historyThumbnailJpegQuality,
    ) ?: return null
    return "data:image/jpeg;base64,${Base64.encodeToString(thumbnailBytes, Base64.NO_WRAP)}"
}

/**
 * Parses a `data:image/...;base64,...` URI into raw image bytes.
 * Mirrors iOS `decodeDataURIImageData()`.
 */
internal fun decodeDataUriToBytes(dataUri: String): ByteArray? {
    val commaIndex = dataUri.indexOf(',')
    if (commaIndex < 0) return null

    val metadata = dataUri.substring(0, commaIndex).lowercase()
    if (!metadata.startsWith("data:image") || !metadata.contains(";base64")) {
        return null
    }

    val base64Part = dataUri.substring(commaIndex + 1)
    return try {
        Base64.decode(base64Part, Base64.DEFAULT)
    } catch (_: IllegalArgumentException) {
        null
    }
}
