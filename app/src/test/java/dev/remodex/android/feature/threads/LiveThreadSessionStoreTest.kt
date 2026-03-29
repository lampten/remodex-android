package dev.remodex.android.feature.threads

import dev.remodex.android.model.ThreadDetail
import dev.remodex.android.model.ThreadStatus
import dev.remodex.android.model.ThreadSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveThreadSessionStoreTest {
    @Test
    fun prefersCurrentSelectionThenPersistedSelectionWhenThreadStillExists() {
        val threads = listOf(
            threadSummary(id = "thread-1", status = ThreadStatus.Completed),
            threadSummary(id = "thread-2", status = ThreadStatus.Running),
        )

        assertEquals(
            "thread-1",
            resolveRecoveredSelectedThreadId(
                threads = threads,
                currentSelectedThreadId = "thread-1",
                persistedSelectedThreadId = "thread-2",
            ),
        )
        assertEquals(
            "thread-2",
            resolveRecoveredSelectedThreadId(
                threads = threads,
                currentSelectedThreadId = "missing-thread",
                persistedSelectedThreadId = "thread-2",
            ),
        )
    }

    @Test
    fun fallsBackToRunningThreadThenFirstThread() {
        val runningThreads = listOf(
            threadSummary(id = "thread-1", status = ThreadStatus.Completed),
            threadSummary(id = "thread-2", status = ThreadStatus.Running),
        )
        val idleThreads = listOf(
            threadSummary(id = "thread-3", status = ThreadStatus.Waiting),
            threadSummary(id = "thread-4", status = ThreadStatus.Completed),
        )

        assertEquals(
            "thread-2",
            resolveRecoveredSelectedThreadId(
                threads = runningThreads,
                currentSelectedThreadId = null,
                persistedSelectedThreadId = null,
            ),
        )
        assertEquals(
            "thread-3",
            resolveRecoveredSelectedThreadId(
                threads = idleThreads,
                currentSelectedThreadId = null,
                persistedSelectedThreadId = null,
            ),
        )
    }

    @Test
    fun syncsSummaryStatusFromFreshThreadDetail() {
        val summary = threadSummary(id = "thread-1", status = ThreadStatus.Running)
        val detail = ThreadDetail(
            threadId = "thread-1",
            subtitle = "detail",
            stateLabel = "Completed",
            entries = emptyList(),
            status = ThreadStatus.Completed,
        )

        val synced = syncThreadSummaryWithDetail(summary, detail)

        assertEquals(ThreadStatus.Completed, synced.status)
    }

    @Test
    fun overlaysSummaryStatusesFromOverridesBeforeStaleListData() {
        val threads = listOf(
            threadSummary(id = "thread-1", status = ThreadStatus.Completed),
            threadSummary(id = "thread-2", status = ThreadStatus.Waiting),
        )
        val details = mapOf(
            "thread-2" to ThreadDetail(
                threadId = "thread-2",
                subtitle = "detail",
                stateLabel = "Completed",
                entries = emptyList(),
                status = ThreadStatus.Completed,
            ),
        )
        val overrides = mapOf(
            "thread-1" to ThreadStatus.Running,
        )

        val merged = overlayThreadSummaryStatuses(
            threads = threads,
            details = details,
            statusOverrides = overrides,
        )

        assertEquals(ThreadStatus.Running, merged[0].status)
        assertEquals(ThreadStatus.Completed, merged[1].status)
    }

    @Test
    fun appliesLocalRenameAndHidesArchivedOrDeletedThreads() {
        val threads = listOf(
            threadSummary(id = "thread-1", status = ThreadStatus.Completed),
            threadSummary(id = "thread-2", status = ThreadStatus.Waiting),
            threadSummary(id = "thread-3", status = ThreadStatus.Running),
        )

        val applied = applyLocalThreadSessionState(
            threads = threads,
            sessionState = LiveThreadSessionState(
                renamedTitlesByThreadId = mapOf("thread-1" to "Renamed title"),
                archivedThreadIds = setOf("thread-2"),
                deletedThreadIds = setOf("thread-3"),
            ),
        )

        assertEquals(1, applied.size)
        assertEquals("thread-1", applied.first().id)
        assertEquals("Renamed title", applied.first().title)
    }

    @Test
    fun mergesServerArchivedThreadsIntoPersistedArchivedSet() {
        val merged = mergeArchivedThreadsIntoSessionState(
            sessionState = LiveThreadSessionState(
                archivedThreadIds = setOf("thread-1"),
            ),
            archivedThreads = listOf(
                threadSummary(id = "thread-2", status = ThreadStatus.Completed),
                threadSummary(id = "thread-3", status = ThreadStatus.Waiting),
            ),
        )

        assertEquals(setOf("thread-1", "thread-2", "thread-3"), merged.archivedThreadIds)
    }

    @Test
    fun collectsAllDescendantsAcrossNestedThreadTree() {
        val threads = listOf(
            threadSummary(id = "root", status = ThreadStatus.Completed),
            threadSummary(id = "child-a", status = ThreadStatus.Completed, parentThreadId = "root"),
            threadSummary(id = "child-b", status = ThreadStatus.Completed, parentThreadId = "root"),
            threadSummary(id = "grandchild", status = ThreadStatus.Completed, parentThreadId = "child-a"),
            threadSummary(id = "unrelated", status = ThreadStatus.Completed, parentThreadId = "other-root"),
        )

        val descendants = collectDescendantThreadIds(threads, "root")
        val subtree = collectSubtreeThreadIds(threads, "root")

        assertEquals(setOf("child-a", "child-b", "grandchild"), descendants)
        assertEquals(setOf("root", "child-a", "child-b", "grandchild"), subtree)
        assertTrue("unrelated" !in descendants)
    }

    private fun threadSummary(
        id: String,
        status: ThreadStatus,
        parentThreadId: String? = null,
    ): ThreadSummary {
        return ThreadSummary(
            id = id,
            title = id,
            projectPath = "~/work/project",
            status = status,
            preview = "preview",
            lastUpdatedLabel = "Now",
            parentThreadId = parentThreadId,
        )
    }
}
