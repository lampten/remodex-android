package dev.remodex.android.feature.threads

import dev.remodex.android.model.TimelineEntry
import dev.remodex.android.model.TimelineEntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingEntriesTest {

    @Test
    fun removesCompletedPlaceholderThinkingRows() {
        val collapsed = collapseThinkingEntries(
            listOf(
                thinkingEntry(
                    id = "thinking-1",
                    body = "Thinking...",
                    isStreaming = false,
                ),
            ),
        )

        assertTrue(collapsed.isEmpty())
    }

    @Test
    fun mergesRepeatedThinkingSnapshotsIntoSingleRow() {
        val collapsed = collapseThinkingEntries(
            listOf(
                thinkingEntry(
                    id = "thinking-1",
                    body = "Thinking...",
                    isStreaming = true,
                ),
                thinkingEntry(
                    id = "thinking-2",
                    body = "Checking the current diff",
                    isStreaming = true,
                ),
                thinkingEntry(
                    id = "thinking-3",
                    body = "Checking the current diff\nComparing the Android flow to iOS",
                    isStreaming = false,
                ),
            ),
        )

        assertEquals(1, collapsed.size)
        assertEquals("thinking-3", collapsed.first().id)
        assertEquals(
            "Checking the current diff\nComparing the Android flow to iOS",
            collapsed.first().body,
        )
        assertFalse(collapsed.first().isStreaming)
    }

    @Test
    fun keepsThinkingRowsSeparatedByChatMessages() {
        val collapsed = collapseThinkingEntries(
            listOf(
                thinkingEntry(
                    id = "thinking-1",
                    body = "Planning the reply",
                    isStreaming = false,
                ),
                TimelineEntry(
                    id = "assistant-1",
                    speaker = "Codex",
                    timestampLabel = "Now",
                    body = "Reply",
                    kind = TimelineEntryKind.Chat,
                ),
                thinkingEntry(
                    id = "thinking-2",
                    body = "Planning the next step",
                    isStreaming = false,
                ),
            ),
        )

        assertEquals(3, collapsed.size)
        assertEquals("thinking-1", collapsed.first().id)
        assertEquals("thinking-2", collapsed.last().id)
    }

    @Test
    fun stripsInlineThinkingLabelFromVisibleBody() {
        assertEquals("", normalizeThinkingEntryBody("Thinking..."))
        assertEquals("Reviewing the thread state", normalizeThinkingEntryBody("Thinking...\nReviewing the thread state"))
    }

    private fun thinkingEntry(
        id: String,
        body: String,
        isStreaming: Boolean,
    ): TimelineEntry {
        return TimelineEntry(
            id = id,
            speaker = "Thinking",
            timestampLabel = "Now",
            body = body,
            kind = TimelineEntryKind.Thinking,
            isStreaming = isStreaming,
        )
    }
}
