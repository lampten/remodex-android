package dev.remodex.android.feature.threads

import dev.remodex.android.model.TimelineEntry
import dev.remodex.android.model.TimelineEntryKind

internal fun normalizeThinkingEntryBody(value: String): String {
    val trimmed = value.trim()
    if (trimmed.equals("thinking", ignoreCase = true) || trimmed.equals("thinking...", ignoreCase = true)) {
        return ""
    }
    return if (trimmed.lowercase().startsWith("thinking...")) {
        trimmed.drop("thinking...".length).trim()
    } else {
        trimmed
    }
}

internal fun collapseThinkingEntries(entries: List<TimelineEntry>): List<TimelineEntry> {
    val result = mutableListOf<TimelineEntry>()
    for (entry in entries) {
        if (entry.kind != TimelineEntryKind.Thinking) {
            result += entry
            continue
        }

        val normalizedEntry = entry.copy(body = normalizeThinkingEntryBody(entry.body))
        val previousIndex = latestReusableThinkingIndex(result)
        if (previousIndex == null) {
            if (!shouldHideThinkingEntry(normalizedEntry)) {
                result += normalizedEntry
            }
            continue
        }

        val previous = result[previousIndex]
        val merged = previous.copy(
            id = normalizedEntry.id,
            timestampLabel = normalizedEntry.timestampLabel,
            body = mergeThinkingBodies(previous.body, normalizedEntry.body),
            title = normalizedEntry.title ?: previous.title,
            detail = normalizedEntry.detail ?: previous.detail,
            isStreaming = normalizedEntry.isStreaming,
        )
        if (shouldHideThinkingEntry(merged)) {
            result.removeAt(previousIndex)
        } else {
            result[previousIndex] = merged
        }
    }
    return result.filterNot(::shouldHideThinkingEntry)
}

private fun latestReusableThinkingIndex(entries: List<TimelineEntry>): Int? {
    for (index in entries.indices.reversed()) {
        val candidate = entries[index]
        if (candidate.kind == TimelineEntryKind.Chat) {
            break
        }
        if (candidate.kind == TimelineEntryKind.Thinking) {
            return index
        }
    }
    return null
}

private fun shouldHideThinkingEntry(entry: TimelineEntry): Boolean {
    return entry.kind == TimelineEntryKind.Thinking &&
        !entry.isStreaming &&
        normalizeThinkingEntryBody(entry.body).isEmpty()
}

private fun mergeThinkingBodies(
    existing: String,
    incoming: String,
): String {
    val existingTrimmed = normalizeThinkingEntryBody(existing)
    val incomingTrimmed = normalizeThinkingEntryBody(incoming)
    if (incomingTrimmed.isEmpty()) {
        return existingTrimmed
    }
    if (existingTrimmed.isEmpty()) {
        return incomingTrimmed
    }
    if (incomingTrimmed.equals(existingTrimmed, ignoreCase = true)) {
        return incomingTrimmed
    }
    if (incomingTrimmed.contains(existingTrimmed)) {
        return incomingTrimmed
    }
    if (existingTrimmed.contains(incomingTrimmed)) {
        return existingTrimmed
    }
    return "$existingTrimmed\n$incomingTrimmed"
}
