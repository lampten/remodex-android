package dev.remodex.android.feature.threads

import android.content.Context
import dev.remodex.android.model.ThreadDetail
import dev.remodex.android.model.ThreadStatus
import dev.remodex.android.model.ThreadSummary
import org.json.JSONObject

data class LiveThreadSessionState(
    val selectedThreadId: String? = null,
    val renamedTitlesByThreadId: Map<String, String> = emptyMap(),
    val archivedThreadIds: Set<String> = emptySet(),
    val deletedThreadIds: Set<String> = emptySet(),
)

interface LiveThreadSessionPersistence {
    fun read(): LiveThreadSessionState
    fun write(state: LiveThreadSessionState)
    fun clear()
}

class SharedPrefsLiveThreadSessionStore(
    context: Context,
) : LiveThreadSessionPersistence {
    private val preferences = context.getSharedPreferences(
        "dev.remodex.android.live_threads",
        Context.MODE_PRIVATE,
    )

    override fun read(): LiveThreadSessionState {
        val raw = preferences.getString(KEY_STATE, null)?.trim().orEmpty()
        if (raw.isEmpty()) {
            return LiveThreadSessionState()
        }

        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return LiveThreadSessionState()
        val selectedThreadId = json.optString("selectedThreadId").trim().takeIf { it.isNotEmpty() }
        val renamedTitlesByThreadId = buildMap {
            val renamed = json.optJSONObject("renamedTitlesByThreadId") ?: JSONObject()
            val keys = renamed.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = renamed.optString(key).trim()
                if (key.isNotBlank() && value.isNotBlank()) {
                    put(key, value)
                }
            }
        }
        val archivedThreadIds = buildSet {
            val archived = json.optJSONArray("archivedThreadIds")
            for (index in 0 until (archived?.length() ?: 0)) {
                archived?.optString(index)?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
        val deletedThreadIds = buildSet {
            val deleted = json.optJSONArray("deletedThreadIds")
            for (index in 0 until (deleted?.length() ?: 0)) {
                deleted?.optString(index)?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
        return LiveThreadSessionState(
            selectedThreadId = selectedThreadId,
            renamedTitlesByThreadId = renamedTitlesByThreadId,
            archivedThreadIds = archivedThreadIds,
            deletedThreadIds = deletedThreadIds,
        )
    }

    override fun write(state: LiveThreadSessionState) {
        val serialized = JSONObject()
            .put("selectedThreadId", state.selectedThreadId ?: JSONObject.NULL)
            .put(
                "renamedTitlesByThreadId",
                JSONObject().apply {
                    state.renamedTitlesByThreadId.forEach { (threadId, title) ->
                        put(threadId, title)
                    }
                },
            )
            .put(
                "archivedThreadIds",
                org.json.JSONArray().apply {
                    state.archivedThreadIds.sorted().forEach(::put)
                },
            )
            .put(
                "deletedThreadIds",
                org.json.JSONArray().apply {
                    state.deletedThreadIds.sorted().forEach(::put)
                },
            )
            .toString()
        preferences.edit().putString(KEY_STATE, serialized).apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY_STATE).apply()
    }

    private companion object {
        private const val KEY_STATE = "live_thread_session_state"
    }
}

internal fun applyLocalThreadSessionState(
    threads: List<ThreadSummary>,
    sessionState: LiveThreadSessionState,
): List<ThreadSummary> {
    val hiddenThreadIds = sessionState.archivedThreadIds + sessionState.deletedThreadIds
    return threads
        .filterNot { it.id in hiddenThreadIds }
        .map { summary ->
            val renamedTitle = sessionState.renamedTitlesByThreadId[summary.id]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (renamedTitle == null || renamedTitle == summary.title) {
                summary
            } else {
                summary.copy(title = renamedTitle)
            }
        }
}

internal fun mergeArchivedThreadsIntoSessionState(
    sessionState: LiveThreadSessionState,
    archivedThreads: List<ThreadSummary>,
): LiveThreadSessionState {
    val archivedThreadIds = archivedThreads.mapTo(linkedSetOf()) { it.id.trim() }
        .filter(String::isNotEmpty)
        .toSet()
    if (archivedThreadIds.isEmpty()) {
        return sessionState
    }
    return sessionState.copy(
        archivedThreadIds = sessionState.archivedThreadIds + archivedThreadIds,
    )
}

internal fun collectDescendantThreadIds(
    threads: List<ThreadSummary>,
    parentThreadId: String,
): Set<String> {
    val normalizedParentId = parentThreadId.trim()
    if (normalizedParentId.isEmpty()) {
        return emptySet()
    }

    val childrenByParentId = threads
        .mapNotNull { thread ->
            val normalizedThreadId = thread.id.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val normalizedImmediateParentId = thread.parentThreadId?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            normalizedImmediateParentId to normalizedThreadId
        }
        .groupBy(
            keySelector = { it.first },
            valueTransform = { it.second },
        )

    val pendingParentIds = ArrayDeque<String>()
    pendingParentIds.addLast(normalizedParentId)
    val visitedThreadIds = linkedSetOf<String>()

    while (pendingParentIds.isNotEmpty()) {
        val currentParentId = pendingParentIds.removeFirst()
        val childIds = childrenByParentId[currentParentId].orEmpty()
        for (childId in childIds) {
            if (visitedThreadIds.add(childId)) {
                pendingParentIds.addLast(childId)
            }
        }
    }

    return visitedThreadIds
}

internal fun collectSubtreeThreadIds(
    threads: List<ThreadSummary>,
    rootThreadId: String,
): Set<String> {
    val normalizedRootId = rootThreadId.trim()
    if (normalizedRootId.isEmpty()) {
        return emptySet()
    }
    return linkedSetOf(normalizedRootId).apply {
        addAll(collectDescendantThreadIds(threads, normalizedRootId))
    }
}

internal fun resolveRecoveredSelectedThreadId(
    threads: List<ThreadSummary>,
    currentSelectedThreadId: String?,
    persistedSelectedThreadId: String?,
): String? {
    if (threads.isEmpty()) {
        return null
    }

    val candidateIds = listOf(currentSelectedThreadId, persistedSelectedThreadId)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()

    for (candidateId in candidateIds) {
        if (threads.any { it.id == candidateId }) {
            return candidateId
        }
    }

    return threads.firstOrNull { it.status == ThreadStatus.Running }?.id ?: threads.first().id
}

internal fun syncThreadSummaryWithDetail(
    summary: ThreadSummary,
    detail: ThreadDetail,
): ThreadSummary {
    if (summary.id != detail.threadId) {
        return summary
    }

    return summary.copy(status = detail.status)
}

internal fun overlayThreadSummaryStatuses(
    threads: List<ThreadSummary>,
    details: Map<String, ThreadDetail>,
    statusOverrides: Map<String, ThreadStatus>,
): List<ThreadSummary> {
    return threads.map { summary ->
        val overrideStatus = statusOverrides[summary.id]
            ?: details[summary.id]?.status
        if (overrideStatus == null || overrideStatus == summary.status) {
            summary
        } else {
            summary.copy(status = overrideStatus)
        }
    }
}
