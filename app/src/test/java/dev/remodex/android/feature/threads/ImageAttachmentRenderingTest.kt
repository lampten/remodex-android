package dev.remodex.android.feature.threads

import dev.remodex.android.model.ImageAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageAttachmentRenderingTest {

    @Test
    fun prefersLocalUriForComposerPreviewWhenAvailable() {
        val attachment = ImageAttachment(
            id = "attachment-1",
            uri = "content://images/preview",
            payloadDataUrl = "data:image/jpeg;base64,AAAA",
        )

        assertEquals(
            "content://images/preview",
            resolveRenderableAttachmentSource(
                attachment = attachment,
                preference = AttachmentRenderPreference.ComposerPreview,
            ),
        )
    }

    @Test
    fun prefersEncodedPayloadForTimelineHistoryWhenAvailable() {
        val attachment = ImageAttachment(
            id = "attachment-1b",
            uri = "content://images/history",
            payloadDataUrl = "data:image/jpeg;base64,AAAA",
        )

        assertEquals(
            "data:image/jpeg;base64,AAAA",
            resolveRenderableAttachmentSource(
                attachment = attachment,
                preference = AttachmentRenderPreference.TimelineHistory,
            ),
        )
    }

    @Test
    fun fallsBackToPayloadWhenHistoryUriIsOnlyPlaceholder() {
        val attachment = ImageAttachment(
            id = "attachment-2",
            uri = relayHistoryImagePlaceholderUri,
            payloadDataUrl = "data:image/jpeg;base64,BBBB",
        )

        assertEquals(
            "data:image/jpeg;base64,BBBB",
            resolveRenderableAttachmentSource(
                attachment = attachment,
                preference = AttachmentRenderPreference.TimelineHistory,
            ),
        )
    }

    @Test
    fun returnsNullWhenOnlyPlaceholderReferenceExists() {
        val attachment = ImageAttachment(
            id = "attachment-3",
            uri = relayHistoryImagePlaceholderUri,
        )

        assertNull(
            resolveRenderableAttachmentSource(
                attachment = attachment,
                preference = AttachmentRenderPreference.TimelineHistory,
            ),
        )
    }

    @Test
    fun doesNotTreatMacAbsolutePathAsPhoneRenderableImage() {
        val attachment = ImageAttachment(
            id = "attachment-4",
            uri = "/tmp/remodex/session/image.png",
        )

        assertNull(
            resolveRenderableAttachmentSource(
                attachment = attachment,
                preference = AttachmentRenderPreference.TimelineHistory,
            ),
        )
    }

    @Test
    fun fallsBackToThumbnailWhenPrimaryUriIsMacOnlyPath() {
        val attachment = ImageAttachment(
            id = "attachment-5",
            uri = "/tmp/remodex/session/image.png",
            thumbnailUri = "data:image/jpeg;base64,CCCC",
        )

        assertEquals(
            "data:image/jpeg;base64,CCCC",
            resolveRenderableAttachmentSource(
                attachment = attachment,
                preference = AttachmentRenderPreference.TimelineHistory,
            ),
        )
    }

    // --- decodeDataUriToBytes tests ---
    // Note: decodeDataUriToBytes and generateThumbnailFromDataUri use android.util.Base64
    // and android.graphics.BitmapFactory which are not available in plain JUnit tests.
    // These functions are tested through integration / on-device tests.
    // The unit tests below validate the pure-logic paths that don't need Android APIs.

    @Test
    fun decodeDataUriToBytesReturnsNullForNonDataUri() {
        assertNull(decodeDataUriToBytes("https://example.com/image.png"))
    }

    @Test
    fun decodeDataUriToBytesReturnsNullForNonImageMimeType() {
        assertNull(decodeDataUriToBytes("data:text/plain;base64,SGVsbG8="))
    }

    @Test
    fun decodeDataUriToBytesReturnsNullForMissingComma() {
        assertNull(decodeDataUriToBytes("data:image/jpeg;base64"))
    }

    @Test
    fun generateThumbnailFromDataUriReturnsNullForNonImageUri() {
        assertNull(generateThumbnailFromDataUri("https://example.com/image.png"))
    }

    @Test
    fun generateThumbnailFromDataUriReturnsNullForEmptyString() {
        assertNull(generateThumbnailFromDataUri(""))
    }

    @Test
    fun generateThumbnailFromDataUriReturnsNullForPlaceholderUri() {
        assertNull(generateThumbnailFromDataUri(relayHistoryImagePlaceholderUri))
    }
}
