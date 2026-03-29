package dev.remodex.android.feature.threads

import android.content.Context
import dev.remodex.android.model.ImageAttachment
import dev.remodex.android.model.ThreadDetail
import dev.remodex.android.model.ThreadStatus
import dev.remodex.android.model.TimelineEntry
import dev.remodex.android.model.TimelineEntryKind
import org.json.JSONArray
import org.json.JSONObject

data class CachedAttachmentMessage(
    val body: String,
    val attachments: List<ImageAttachment>,
)

data class ImageAttachmentCacheState(
    val messagesByThreadId: Map<String, List<CachedAttachmentMessage>> = emptyMap(),
)

interface ImageAttachmentCachePersistence {
    fun read(): ImageAttachmentCacheState
    fun write(state: ImageAttachmentCacheState)
    fun clear()
}

class SharedPrefsImageAttachmentCacheStore(
    context: Context,
) : ImageAttachmentCachePersistence {
    private val preferences = context.getSharedPreferences(
        "dev.remodex.android.image_attachment_cache",
        Context.MODE_PRIVATE,
    )

    override fun read(): ImageAttachmentCacheState {
        val raw = preferences.getString(KEY_STATE, null)?.trim().orEmpty()
        if (raw.isEmpty()) {
            return ImageAttachmentCacheState()
        }

        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return ImageAttachmentCacheState()
        val messagesByThreadId = buildMap {
            val threads = json.optJSONObject("messagesByThreadId") ?: JSONObject()
            val threadKeys = threads.keys()
            while (threadKeys.hasNext()) {
                val threadId = threadKeys.next().trim()
                if (threadId.isEmpty()) {
                    continue
                }
                val cachedMessages = decodeCachedMessages(threads.optJSONArray(threadId))
                if (cachedMessages.isNotEmpty()) {
                    put(threadId, cachedMessages)
                }
            }
        }
        return ImageAttachmentCacheState(messagesByThreadId = messagesByThreadId)
    }

    override fun write(state: ImageAttachmentCacheState) {
        val serialized = JSONObject()
            .put(
                "messagesByThreadId",
                JSONObject().apply {
                    state.messagesByThreadId.toSortedMap().forEach { (threadId, messages) ->
                        if (messages.isNotEmpty()) {
                            put(threadId, encodeCachedMessages(messages))
                        }
                    }
                },
            )
            .toString()
        preferences.edit().putString(KEY_STATE, serialized).apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY_STATE).apply()
    }

    private fun decodeCachedMessages(rawMessages: JSONArray?): List<CachedAttachmentMessage> {
        return buildList {
            for (index in 0 until (rawMessages?.length() ?: 0)) {
                val messageObject = rawMessages?.optJSONObject(index) ?: continue
                val body = messageObject.optString("body").trim()
                val attachments = decodeAttachments(messageObject.optJSONArray("attachments"))
                if (attachments.any(::hasRenderableAttachment)) {
                    add(CachedAttachmentMessage(body = body, attachments = attachments))
                }
            }
        }
    }

    private fun decodeAttachments(rawAttachments: JSONArray?): List<ImageAttachment> {
        return buildList {
            for (index in 0 until (rawAttachments?.length() ?: 0)) {
                val attachmentObject = rawAttachments?.optJSONObject(index) ?: continue
                add(
                    ImageAttachment(
                        id = attachmentObject.optString("id").ifBlank { "cached-attachment-$index" },
                        uri = attachmentObject.optString("uri").trim(),
                        thumbnailUri = attachmentObject.optString("thumbnailUri").trim().takeIf(String::isNotEmpty),
                        payloadDataUrl = attachmentObject.optString("payloadDataUrl").trim().takeIf(String::isNotEmpty),
                    ),
                )
            }
        }
    }

    private fun encodeCachedMessages(messages: List<CachedAttachmentMessage>): JSONArray {
        return JSONArray().apply {
            messages.forEach { message ->
                put(
                    JSONObject()
                        .put("body", message.body)
                        .put(
                            "attachments",
                            JSONArray().apply {
                                message.attachments.forEach { attachment ->
                                    put(
                                        JSONObject()
                                            .put("id", attachment.id)
                                            .put("uri", attachment.uri)
                                            .put("thumbnailUri", attachment.thumbnailUri ?: JSONObject.NULL)
                                            .put("payloadDataUrl", attachment.payloadDataUrl ?: JSONObject.NULL),
                                    )
                                }
                            },
                        ),
                )
            }
        }
    }

    private companion object {
        private const val KEY_STATE = "image_attachment_cache_state"
    }
}

internal fun extractRenderableAttachmentMessages(detail: ThreadDetail): List<CachedAttachmentMessage> {
    return detail.entries.mapNotNull { entry ->
        if (entry.kind != TimelineEntryKind.Chat || !entry.speaker.equals("You", ignoreCase = true)) {
            return@mapNotNull null
        }
        val renderableAttachments = entry.attachments.filter(::hasRenderableAttachment)
        if (renderableAttachments.isEmpty()) {
            return@mapNotNull null
        }
        CachedAttachmentMessage(
            body = entry.body.trim(),
            attachments = renderableAttachments,
        )
    }
}

internal fun mergeCachedAttachmentMessages(
    existing: List<CachedAttachmentMessage>,
    incoming: List<CachedAttachmentMessage>,
    maxEntriesPerThread: Int = 40,
): List<CachedAttachmentMessage> {
    if (incoming.isEmpty()) {
        return existing
    }

    val merged = existing.toMutableList()
    for (message in incoming) {
        val normalizedBody = message.body.trim()
        val incomingSignature = attachmentSignature(message.attachments)
        val matchIndex = merged.indexOfFirst { candidate ->
            candidate.body.trim() == normalizedBody
                && attachmentSignature(candidate.attachments) == incomingSignature
        }
        if (matchIndex >= 0) {
            merged[matchIndex] = message
        } else {
            merged += message
        }
    }
    return merged.takeLast(maxEntriesPerThread)
}

internal fun buildAttachmentPreservationDetail(
    threadId: String,
    existingDetail: ThreadDetail?,
    cachedMessages: List<CachedAttachmentMessage>,
): ThreadDetail? {
    if (existingDetail == null && cachedMessages.isEmpty()) {
        return null
    }

    val cachedEntries = cachedMessages.mapIndexed { index, message ->
        TimelineEntry(
            id = "cached-$threadId-$index",
            speaker = "You",
            timestampLabel = "Earlier",
            body = message.body,
            kind = TimelineEntryKind.Chat,
            attachments = message.attachments,
        )
    }

    return if (existingDetail == null) {
        ThreadDetail(
            threadId = threadId,
            subtitle = "Cached attachments",
            stateLabel = "Cached",
            entries = cachedEntries,
            status = ThreadStatus.Waiting,
        )
    } else {
        existingDetail.copy(entries = existingDetail.entries + cachedEntries)
    }
}

private fun attachmentSignature(attachments: List<ImageAttachment>): String {
    return attachments.joinToString(separator = "|") { attachment ->
        attachment.payloadDataUrl
            ?: attachment.thumbnailUri
            ?: attachment.uri
    }
}
