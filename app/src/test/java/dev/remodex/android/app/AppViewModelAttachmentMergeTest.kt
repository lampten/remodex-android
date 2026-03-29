package dev.remodex.android.app

import dev.remodex.android.feature.threads.buildAttachmentPreservationDetail
import dev.remodex.android.feature.threads.extractRenderableAttachmentMessages
import dev.remodex.android.feature.threads.mergeCachedAttachmentMessages
import dev.remodex.android.model.ImageAttachment
import dev.remodex.android.model.ThreadDetail
import dev.remodex.android.model.ThreadStatus
import dev.remodex.android.model.TimelineEntry
import dev.remodex.android.model.TimelineEntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppViewModelAttachmentMergeTest {

    @Test
    fun preservesLocalImageForImageOnlyUserMessageWhenHistoryUsesPlaceholder() {
        val localAttachment = ImageAttachment(
            id = "local-image",
            uri = "content://images/1",
            payloadDataUrl = "data:image/jpeg;base64,AAAA",
        )
        val existingDetail = threadDetail(
            entries = listOf(
                userEntry(
                    id = "local-user",
                    body = "",
                    attachments = listOf(localAttachment),
                ),
            ),
        )
        val incomingDetail = threadDetail(
            entries = listOf(
                userEntry(
                    id = "server-user",
                    body = "",
                    attachments = listOf(
                        ImageAttachment(
                            id = "server-image",
                            uri = "remodex://history-image-elided",
                        ),
                    ),
                ),
            ),
        )

        val merged = preserveLocalRenderableAttachments(existingDetail, incomingDetail)

        assertEquals(1, merged.entries.first().attachments.size)
        assertEquals("content://images/1", merged.entries.first().attachments.first().uri)
        assertEquals("data:image/jpeg;base64,AAAA", merged.entries.first().attachments.first().payloadDataUrl)
    }

    @Test
    fun preservesLocalImageWhenBodyMatchesAndIncomingHistoryHasNoRenderableAttachment() {
        val localAttachment = ImageAttachment(
            id = "local-image",
            uri = "content://images/2",
            payloadDataUrl = "data:image/jpeg;base64,BBBB",
        )
        val existingDetail = threadDetail(
            entries = listOf(
                userEntry(
                    id = "local-user",
                    body = "image test",
                    attachments = listOf(localAttachment),
                ),
            ),
        )
        val incomingDetail = threadDetail(
            entries = listOf(
                userEntry(
                    id = "server-user",
                    body = "image test",
                    attachments = emptyList(),
                ),
            ),
        )

        val merged = preserveLocalRenderableAttachments(existingDetail, incomingDetail)

        assertEquals("data:image/jpeg;base64,BBBB", merged.entries.first().attachments.first().payloadDataUrl)
    }

    @Test
    fun doesNotOverwriteRenderableIncomingHistoryAttachment() {
        val existingDetail = threadDetail(
            entries = listOf(
                userEntry(
                    id = "local-user",
                    body = "image test",
                    attachments = listOf(
                        ImageAttachment(
                            id = "local-image",
                            uri = "content://images/3",
                            payloadDataUrl = "data:image/jpeg;base64,CCCC",
                        ),
                    ),
                ),
            ),
        )
        val incomingDetail = threadDetail(
            entries = listOf(
                userEntry(
                    id = "server-user",
                    body = "image test",
                    attachments = listOf(
                        ImageAttachment(
                            id = "remote-image",
                            uri = "https://example.com/image.jpg",
                        ),
                    ),
                ),
            ),
        )

        val merged = preserveLocalRenderableAttachments(existingDetail, incomingDetail)

        assertEquals("https://example.com/image.jpg", merged.entries.first().attachments.first().uri)
        assertTrue(merged.entries.first().attachments.first().payloadDataUrl.isNullOrBlank())
    }

    @Test
    fun restoresCachedImageWhenOldThreadReloadStartsWithoutLiveLocalDetail() {
        val cachedAttachment = ImageAttachment(
            id = "cached-image",
            uri = "content://images/4",
            thumbnailUri = "data:image/jpeg;base64,THUMB",
            payloadDataUrl = "data:image/jpeg;base64,DDDD",
        )
        val cachedMessages = extractRenderableAttachmentMessages(
            threadDetail(
                entries = listOf(
                    userEntry(
                        id = "cached-user",
                        body = "photo test",
                        attachments = listOf(cachedAttachment),
                    ),
                ),
            ),
        )
        val incomingDetail = threadDetail(
            entries = listOf(
                userEntry(
                    id = "server-user",
                    body = "photo test",
                    attachments = listOf(
                        ImageAttachment(
                            id = "server-image",
                            uri = "remodex://history-image-elided",
                        ),
                    ),
                ),
            ),
        )

        val merged = preserveLocalRenderableAttachments(
            existingDetail = buildAttachmentPreservationDetail(
                threadId = "thread-1",
                existingDetail = null,
                cachedMessages = cachedMessages,
            ),
            incomingDetail = incomingDetail,
        )

        assertEquals("data:image/jpeg;base64,DDDD", merged.entries.first().attachments.first().payloadDataUrl)
        assertEquals("data:image/jpeg;base64,THUMB", merged.entries.first().attachments.first().thumbnailUri)
    }

    @Test
    fun deduplicatesCachedMessagesByBodyAndAttachmentSignature() {
        val existing = listOf(
            cachedMessage(
                body = "same body",
                attachment = ImageAttachment(
                    id = "old",
                    uri = "content://images/old",
                    payloadDataUrl = "data:image/jpeg;base64,EEEE",
                ),
            ),
        )
        val incoming = listOf(
            cachedMessage(
                body = "same body",
                attachment = ImageAttachment(
                    id = "new",
                    uri = "content://images/new",
                    payloadDataUrl = "data:image/jpeg;base64,EEEE",
                ),
            ),
        )

        val merged = mergeCachedAttachmentMessages(existing = existing, incoming = incoming)

        assertEquals(1, merged.size)
        assertEquals("new", merged.first().attachments.first().id)
    }

    @Test
    fun detectsThreadWarmupErrorsWithoutTreatingThemAsGenericFailures() {
        assertTrue(isThreadNotMaterializedMessage("no rollout found for thread id abc"))
        assertTrue(isThreadNotMaterializedMessage("thread is not materialized yet"))
        assertTrue(isThreadNotMaterializedMessage("includeTurns is unavailable before first user message"))
        assertFalse(isThreadNotMaterializedMessage("The secure session is not active yet."))
    }

    private fun threadDetail(entries: List<TimelineEntry>): ThreadDetail {
        return ThreadDetail(
            threadId = "thread-1",
            subtitle = "Live",
            stateLabel = "Ready",
            entries = entries,
            status = ThreadStatus.Waiting,
        )
    }

    private fun userEntry(
        id: String,
        body: String,
        attachments: List<ImageAttachment>,
    ): TimelineEntry {
        return TimelineEntry(
            id = id,
            speaker = "You",
            timestampLabel = "Now",
            body = body,
            kind = TimelineEntryKind.Chat,
            attachments = attachments,
        )
    }

    private fun cachedMessage(
        body: String,
        attachment: ImageAttachment,
    ) = dev.remodex.android.feature.threads.CachedAttachmentMessage(
        body = body,
        attachments = listOf(attachment),
    )
}
