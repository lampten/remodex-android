package dev.remodex.android.feature.threads

import dev.remodex.android.model.ImageAttachment

internal const val relayHistoryImagePlaceholderUri = "remodex://history-image-elided"

internal enum class AttachmentRenderPreference {
    ComposerPreview,
    TimelineHistory,
}

internal fun isRelayHistoryImagePlaceholderUri(uri: String?): Boolean {
    val normalized = uri?.trim().orEmpty()
    return normalized.equals(relayHistoryImagePlaceholderUri, ignoreCase = true)
}

internal fun resolveRenderableAttachmentSource(
    attachment: ImageAttachment,
    preference: AttachmentRenderPreference = AttachmentRenderPreference.TimelineHistory,
): String? {
    val localUri = normalizedRenderableAttachmentSource(attachment.uri)
    val encodedSource = sequenceOf(
        attachment.payloadDataUrl,
        attachment.thumbnailUri,
    ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .mapNotNull(::normalizedRenderableAttachmentSource)
        .firstOrNull()

    return when (preference) {
        AttachmentRenderPreference.ComposerPreview -> localUri ?: encodedSource
        AttachmentRenderPreference.TimelineHistory -> encodedSource ?: localUri
    }
}

private fun normalizedRenderableAttachmentSource(source: String?): String? {
    return source
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.takeUnless(::isRelayHistoryImagePlaceholderUri)
        ?.takeIf { normalizedSource ->
            isDirectlyRenderableAttachmentSource(normalizedSource)
        }
}

internal fun isDirectlyRenderableAttachmentSource(source: String?): Boolean {
    val normalized = source?.trim().orEmpty()
    if (normalized.isEmpty()) {
        return false
    }
    if (isRelayHistoryImagePlaceholderUri(normalized)) {
        return false
    }
    return normalized.startsWith("content://", ignoreCase = true)
        || normalized.startsWith("file://", ignoreCase = true)
        || normalized.startsWith("http://", ignoreCase = true)
        || normalized.startsWith("https://", ignoreCase = true)
        || normalized.startsWith("android.resource://", ignoreCase = true)
        || normalized.startsWith("data:image", ignoreCase = true)
}

internal fun hasRenderableAttachment(attachment: ImageAttachment): Boolean {
    return resolveRenderableAttachmentSource(attachment) != null
}

internal fun hasRenderableAttachments(attachments: List<ImageAttachment>): Boolean {
    return attachments.any(::hasRenderableAttachment)
}

internal fun hasOnlyPlaceholderAttachments(attachments: List<ImageAttachment>): Boolean {
    return attachments.isNotEmpty() && attachments.all { attachment ->
        !hasRenderableAttachment(attachment) && isRelayHistoryImagePlaceholderUri(attachment.uri)
    }
}
