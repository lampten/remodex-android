package dev.remodex.android.model

import androidx.compose.runtime.Immutable

enum class ConnectionPhase {
    NotPaired,
    TrustedMac,
    PairingReady,
    Connecting,
    Handshaking,
    Syncing,
    Ready,
}

enum class ThreadStatus {
    Running,
    Waiting,
    Completed,
    Failed,
}

enum class ThreadSyncState {
    Live,
    ArchivedLocal,
}

enum class CollaborationModeKind(val wireValue: String) {
    Plan("plan"),
}

enum class TimelineEntryKind {
    Chat,
    Thinking,
    ToolActivity,
    FileChange,
    CommandExecution,
    Plan,
    SubagentAction,
    System,
}

@Immutable
data class TimelinePlanStep(
    val text: String,
    val status: String? = null,
)

@Immutable
data class PairingStep(
    val index: Int,
    val title: String,
    val detail: String,
)

@Immutable
data class BridgeSnapshot(
    val deviceName: String,
    val relayLabel: String,
    val phase: ConnectionPhase,
    val trustStored: Boolean,
    val lastSeenLabel: String,
)

@Immutable
data class ThreadSummary(
    val id: String,
    val title: String,
    val projectPath: String,
    val status: ThreadStatus,
    val preview: String,
    val lastUpdatedLabel: String,
    val model: String? = null,
    val modelProvider: String? = null,
    val parentThreadId: String? = null,
    val syncState: ThreadSyncState = ThreadSyncState.Live,
)

@Immutable
data class TimelineEntry(
    val id: String,
    val speaker: String,
    val timestampLabel: String,
    val body: String,
    val kind: TimelineEntryKind = TimelineEntryKind.Chat,
    val title: String? = null,
    val detail: String? = null,
    val planSteps: List<TimelinePlanStep> = emptyList(),
    val isStreaming: Boolean = false,
    val attachments: List<ImageAttachment> = emptyList(),
)

@Immutable
data class ThreadDetail(
    val threadId: String,
    val subtitle: String,
    val stateLabel: String,
    val entries: List<TimelineEntry>,
    val status: ThreadStatus = ThreadStatus.Waiting,
    val activeTurnId: String? = null,
    val latestTurnId: String? = null,
    val hasInterruptibleTurnWithoutId: Boolean = false,
) {
    val isRunning: Boolean
        get() = status == ThreadStatus.Running || activeTurnId != null || hasInterruptibleTurnWithoutId

    val canInterrupt: Boolean
        get() = activeTurnId != null || hasInterruptibleTurnWithoutId || status == ThreadStatus.Running

    val canContinue: Boolean
        get() = !isRunning
}

@Immutable
data class ModelOption(
    val id: String,
    val model: String,
    val displayName: String,
    val isDefault: Boolean = false,
    val supportedReasoningEfforts: List<ReasoningDisplayOption> = emptyList(),
    val defaultReasoningEffort: String? = null,
)

@Immutable
data class ReasoningDisplayOption(
    val effort: String,
    val title: String,
) {
    val rank: Int
        get() = when (title) {
            "Low" -> 0
            "Medium" -> 1
            "High" -> 2
            "Extra High" -> 3
            else -> 4
        }
}

enum class ServiceTier(val displayName: String) {
    Fast("Fast");

    val wireValue: String
        get() = when (this) {
            Fast -> "fast"
        }
}

enum class AccessMode(val displayName: String, val menuTitle: String) {
    OnRequest("Ask", "On-Request"),
    FullAccess("Full", "Full Access");

    val approvalPolicyCandidates: List<String>
        get() = when (this) {
            OnRequest -> listOf("on-request", "onRequest")
            FullAccess -> listOf("never")
        }

    val sandboxLegacyValue: String
        get() = when (this) {
            OnRequest -> "workspace-write"
            FullAccess -> "danger-full-access"
        }
}

@Immutable
data class ContextWindowUsage(
    val usedTokens: Long,
    val maxTokens: Long,
) {
    val usagePercent: Float
        get() = if (maxTokens > 0) (usedTokens.toFloat() / maxTokens).coerceIn(0f, 1f) else 0f
    val usageLabel: String
        get() = "${(usagePercent * 100).toInt()}% left"
    val formattedUsed: String
        get() = formatTokenCount(usedTokens)
    val formattedMax: String
        get() = formatTokenCount(maxTokens)
}

private fun formatTokenCount(tokens: Long): String {
    return when {
        tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
        tokens >= 1_000 -> "%.1fK".format(tokens / 1_000.0)
        else -> "$tokens"
    }
}

@Immutable
data class ImageAttachment(
    val id: String,
    val uri: String,
    val thumbnailUri: String? = null,
    val payloadDataUrl: String? = null,
)

// Maps raw reasoning effort strings to display titles (matching iOS TurnComposerMetaMapper)
fun reasoningEffortTitle(effort: String): String {
    return when (effort.trim().lowercase()) {
        "minimal", "low" -> "Low"
        "medium" -> "Medium"
        "high" -> "High"
        "xhigh", "extra_high", "extra-high", "very_high", "very-high" -> "Extra High"
        else -> effort.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }
}

// Maps raw model IDs to normalized display titles (matching iOS TurnComposerMetaMapper)
fun modelDisplayTitle(model: ModelOption): String {
    return when (model.model.lowercase()) {
        "gpt-5.3-codex" -> "GPT-5.3-Codex"
        "gpt-5.3-codex-spark" -> "GPT-5.3-Codex-Spark"
        "gpt-5.2-codex" -> "GPT-5.2-Codex"
        "gpt-5.1-codex-max" -> "GPT-5.1-Codex-Max"
        "gpt-5.4" -> "GPT-5.4"
        "gpt-5.4-mini" -> "GPT-5.4-Mini"
        "gpt-5.2" -> "GPT-5.2"
        "gpt-5.1-codex-mini" -> "GPT-5.1-Codex-Mini"
        "codex-mini" -> "Codex Mini"
        else -> model.displayName
    }
}

object SampleShellData {
    val placeholderBridge = BridgeSnapshot(
        deviceName = "Josh's MacBook Pro",
        relayLabel = "Local relay over bridge QR",
        phase = ConnectionPhase.NotPaired,
        trustStored = false,
        lastSeenLabel = "No trusted session yet",
    )

    val pairingSteps = listOf(
        PairingStep(
            index = 1,
            title = "Start the bridge on your Mac",
            detail = "Run remodex up and keep the QR visible in the terminal.",
        ),
        PairingStep(
            index = 2,
            title = "Paste the bridge payload",
            detail = "Copy the full JSON payload from the QR output and validate it here.",
        ),
        PairingStep(
            index = 3,
            title = "Reconnect without a new QR",
            detail = "Once trust is saved, this phone can reconnect to the same Mac without pairing again.",
        ),
    )

    val threads = listOf(
        ThreadSummary(
            id = "thread-bridge-check",
            title = "Bridge handshake notes",
            projectPath = "~/Downloads/remodex-Android",
            status = ThreadStatus.Running,
            preview = "Map secure pairing fields before wiring the Android transport.",
            lastUpdatedLabel = "2m ago",
        ),
        ThreadSummary(
            id = "thread-reconnect",
            title = "Trusted reconnect rules",
            projectPath = "~/Downloads/remodex-Android",
            status = ThreadStatus.Waiting,
            preview = "Keep one reconnect model shared by pairing recovery and cold app start.",
            lastUpdatedLabel = "18m ago",
        ),
        ThreadSummary(
            id = "thread-ui-shell",
            title = "Android shell scaffold",
            projectPath = "~/Downloads/remodex-Android",
            status = ThreadStatus.Completed,
            preview = "Create a runnable shell for pairing, threads, detail, and settings.",
            lastUpdatedLabel = "44m ago",
        ),
        ThreadSummary(
            id = "thread-regression",
            title = "Late event merge risk",
            projectPath = "~/work/remodex-reference",
            status = ThreadStatus.Failed,
            preview = "Timeline ordering can look right until the bridge sends delayed completion events.",
            lastUpdatedLabel = "Yesterday",
        ),
    )

    val details = mapOf(
        "thread-bridge-check" to ThreadDetail(
            threadId = "thread-bridge-check",
            subtitle = "Scaffold timeline surface",
            stateLabel = "Running on the Mac bridge",
            entries = listOf(
                TimelineEntry(
                    id = "1",
                    speaker = "You",
                    timestampLabel = "09:12",
                    body = "Read the Android milestone and set up the first real app shell.",
                ),
                TimelineEntry(
                    id = "2",
                    speaker = "Codex",
                    timestampLabel = "09:13",
                    body = "I am creating a minimal Android project so pairing and reconnect can land in a real build, not in a doc-only branch.",
                ),
                TimelineEntry(
                    id = "3",
                    speaker = "Codex",
                    timestampLabel = "09:16",
                    body = "Next checkpoint is a runnable shell with tabs for pairing, threads, and settings, plus a thread detail pane for timeline work.",
                ),
            ),
        ),
        "thread-reconnect" to ThreadDetail(
            threadId = "thread-reconnect",
            subtitle = "Connection state notes",
            stateLabel = "Waiting on transport implementation",
            entries = listOf(
                TimelineEntry(
                    id = "4",
                    speaker = "You",
                    timestampLabel = "08:41",
                    body = "What should the reconnect checkpoint cover before we touch push or voice?",
                ),
                TimelineEntry(
                    id = "5",
                    speaker = "Codex",
                    timestampLabel = "08:43",
                    body = "Cold launch recovery, temporary disconnect retry, and the ability to forget an invalid pair are the minimum pieces worth shipping.",
                ),
            ),
        ),
        "thread-ui-shell" to ThreadDetail(
            threadId = "thread-ui-shell",
            subtitle = "Root scaffold checkpoint",
            stateLabel = "Completed locally",
            entries = listOf(
                TimelineEntry(
                    id = "6",
                    speaker = "Codex",
                    timestampLabel = "07:51",
                    body = "The shell uses Compose and keeps a single app module until pairing and timeline logic are real enough to justify module splits.",
                ),
                TimelineEntry(
                    id = "7",
                    speaker = "Codex",
                    timestampLabel = "07:56",
                    body = "The placeholder flow makes room for pairing, thread list, thread detail, and settings without pretending the transport already works.",
                ),
            ),
        ),
        "thread-regression" to ThreadDetail(
            threadId = "thread-regression",
            subtitle = "Failure reminder",
            stateLabel = "Needs a follow-up task",
            entries = listOf(
                TimelineEntry(
                    id = "8",
                    speaker = "Codex",
                    timestampLabel = "Yesterday",
                    body = "Do not treat a sorted thread list as proof that the timeline merger is correct. Delayed events can still break the active run state.",
                ),
            ),
        ),
    )
}
