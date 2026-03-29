package dev.remodex.android.app

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.remodex.android.feature.pairing.PairingQrPayload
import dev.remodex.android.feature.pairing.PairingQrValidationResult
import dev.remodex.android.feature.pairing.PairingStatusMessage
import dev.remodex.android.feature.pairing.PairingStatusTone
import dev.remodex.android.feature.pairing.RelayBootstrapException
import dev.remodex.android.feature.pairing.RelayBootstrapPhase
import dev.remodex.android.feature.pairing.RelayBootstrapService
import dev.remodex.android.feature.pairing.RelayBootstrapVerification
import dev.remodex.android.feature.pairing.LiveThreadRuntimeEvent
import dev.remodex.android.feature.pairing.DeviceHistoryEntry
import dev.remodex.android.feature.pairing.displayLabel
import dev.remodex.android.feature.pairing.SharedPrefsDeviceHistoryStore
import dev.remodex.android.feature.pairing.SharedPrefsTrustedReconnectStore
import dev.remodex.android.feature.pairing.TrustedReconnectPath
import dev.remodex.android.feature.pairing.TrustedReconnectRecord
import dev.remodex.android.feature.pairing.expiryLabel
import dev.remodex.android.feature.pairing.toDeviceHistoryEntry
import dev.remodex.android.feature.pairing.toTrustedReconnectRecord
import dev.remodex.android.feature.pairing.maskedMacDeviceLabel
import dev.remodex.android.feature.pairing.relayDisplayLabel
import dev.remodex.android.feature.pairing.relayGuidanceMessage
import dev.remodex.android.feature.pairing.validatePairingQrCode
import dev.remodex.android.feature.runtime.RuntimeConfigState
import dev.remodex.android.feature.runtime.SharedPrefsRuntimeConfigStore
import dev.remodex.android.feature.runtime.ThreadRuntimeOverride
import dev.remodex.android.feature.runtime.normalizeRuntimeConfigState
import dev.remodex.android.feature.runtime.resolveEffectiveReasoningEffort
import dev.remodex.android.feature.runtime.resolveEffectiveServiceTier
import dev.remodex.android.feature.runtime.resolveSelectedModelOption
import dev.remodex.android.feature.threads.CachedAttachmentMessage
import dev.remodex.android.feature.threads.ImageAttachmentCacheState
import dev.remodex.android.feature.threads.LiveThreadSessionState
import dev.remodex.android.feature.threads.SharedPrefsImageAttachmentCacheStore
import dev.remodex.android.feature.threads.SharedPrefsLiveThreadSessionStore
import dev.remodex.android.feature.threads.applyLocalThreadSessionState
import dev.remodex.android.feature.threads.buildAttachmentPreservationDetail
import dev.remodex.android.feature.threads.collectDescendantThreadIds
import dev.remodex.android.feature.threads.collectSubtreeThreadIds
import dev.remodex.android.feature.threads.encodeImageAttachment
import dev.remodex.android.feature.threads.extractRenderableAttachmentMessages
import dev.remodex.android.feature.threads.hasRenderableAttachment
import dev.remodex.android.feature.threads.hasRenderableAttachments
import dev.remodex.android.feature.threads.mergeCachedAttachmentMessages
import dev.remodex.android.feature.threads.mergeArchivedThreadsIntoSessionState
import dev.remodex.android.feature.threads.normalizeThinkingEntryBody
import dev.remodex.android.feature.threads.overlayThreadSummaryStatuses
import dev.remodex.android.feature.threads.resolveRecoveredSelectedThreadId
import dev.remodex.android.feature.threads.syncThreadSummaryWithDetail
import dev.remodex.android.model.AccessMode
import dev.remodex.android.model.BridgeSnapshot
import dev.remodex.android.model.CollaborationModeKind
import dev.remodex.android.model.ConnectionPhase
import dev.remodex.android.model.ContextWindowUsage
import dev.remodex.android.model.ImageAttachment
import dev.remodex.android.model.ModelOption
import dev.remodex.android.model.ReasoningDisplayOption
import dev.remodex.android.model.SampleShellData
import dev.remodex.android.model.ServiceTier
import dev.remodex.android.model.ThreadDetail
import dev.remodex.android.model.ThreadStatus
import dev.remodex.android.model.ThreadSummary
import dev.remodex.android.model.TimelineEntry
import dev.remodex.android.model.TimelineEntryKind
import dev.remodex.android.model.modelDisplayTitle
import dev.remodex.android.model.reasoningEffortTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "RemodexApp"

private val defaultReasoningOptions = listOf(
    ReasoningDisplayOption("low", "Low"),
    ReasoningDisplayOption("medium", "Medium"),
    ReasoningDisplayOption("high", "High"),
    ReasoningDisplayOption("xhigh", "Extra High"),
)

enum class AppScreen {
    Home,
    Pairing,
    Settings,
}

enum class LiveThreadAction {
    Sending,
    Stopping,
    Continuing,
}

internal const val trustedAutoReconnectCooldownMs = 5_000L
internal const val liveThreadRunningSyncIntervalMs = 1_000L
internal const val liveThreadIdleSyncIntervalMs = 3_000L
internal const val liveThreadListSyncIntervalMs = 10_000L
private val trustedReconnectMutex = Mutex()

internal fun shouldAttemptTrustedAutoReconnect(
    hasSavedTrustedMac: Boolean,
    hasActiveSession: Boolean,
    isRecoveringTrustedLiveSession: Boolean,
    currentScreen: AppScreen,
    stagedPairingPayload: PairingQrPayload?,
    pairingCodeInput: String,
    bootstrapPhase: RelayBootstrapPhase,
): Boolean {
    if (!hasSavedTrustedMac || hasActiveSession || isRecoveringTrustedLiveSession) {
        return false
    }
    if (currentScreen == AppScreen.Pairing) {
        return false
    }
    if (stagedPairingPayload != null || pairingCodeInput.isNotBlank()) {
        return false
    }
    if (bootstrapPhase == RelayBootstrapPhase.Connecting || bootstrapPhase == RelayBootstrapPhase.Handshaking) {
        return false
    }
    return true
}

internal fun shouldDisconnectActiveSessionWhenClearingPairing(
    isShowingLiveThreads: Boolean,
    bootstrapPhase: RelayBootstrapPhase,
): Boolean = !isShowingLiveThreads && bootstrapPhase != RelayBootstrapPhase.Idle

internal fun resolveBridgeConnectionPhase(
    payload: PairingQrPayload?,
    trustedReconnectRecord: TrustedReconnectRecord?,
    bootstrapPhase: RelayBootstrapPhase,
    bootstrapVerification: RelayBootstrapVerification?,
): ConnectionPhase {
    return when {
        bootstrapVerification != null -> ConnectionPhase.Ready
        bootstrapPhase == RelayBootstrapPhase.Connecting -> ConnectionPhase.Connecting
        bootstrapPhase == RelayBootstrapPhase.Handshaking -> ConnectionPhase.Handshaking
        payload != null -> ConnectionPhase.PairingReady
        trustedReconnectRecord != null -> ConnectionPhase.TrustedMac
        else -> ConnectionPhase.NotPaired
    }
}

internal fun isTrustedAutoReconnectCoolingDown(
    lastAttemptElapsedRealtimeMs: Long?,
    nowElapsedRealtimeMs: Long,
    cooldownMs: Long = trustedAutoReconnectCooldownMs,
): Boolean {
    val lastAttempt = lastAttemptElapsedRealtimeMs ?: return false
    return nowElapsedRealtimeMs - lastAttempt in 0 until cooldownMs
}

internal fun shouldRunLiveThreadSyncLoop(
    isAppInForeground: Boolean,
    isShowingLiveThreads: Boolean,
    hasActiveSession: Boolean,
    isRecoveringTrustedLiveSession: Boolean,
): Boolean {
    return isAppInForeground && isShowingLiveThreads && hasActiveSession && !isRecoveringTrustedLiveSession
}

internal fun shouldShowPairingTransitionOverlay(
    bootstrapPhase: RelayBootstrapPhase,
    isShowingLiveThreads: Boolean,
): Boolean {
    if (isShowingLiveThreads) {
        return false
    }
    return bootstrapPhase == RelayBootstrapPhase.Connecting || bootstrapPhase == RelayBootstrapPhase.Handshaking
}

internal fun liveThreadSyncDelayMs(
    selectedThreadId: String?,
    selectedDetail: ThreadDetail?,
    liveThreadActionState: LiveThreadActionState?,
    runningIntervalMs: Long = liveThreadRunningSyncIntervalMs,
    idleIntervalMs: Long = liveThreadIdleSyncIntervalMs,
    fallbackIntervalMs: Long = liveThreadListSyncIntervalMs,
): Long {
    if (selectedThreadId.isNullOrBlank()) {
        return fallbackIntervalMs
    }
    val selectedThreadAction = liveThreadActionState?.threadId == selectedThreadId
    return if (selectedThreadAction || selectedDetail?.isRunning == true) {
        runningIntervalMs
    } else {
        idleIntervalMs
    }
}

internal fun isThreadNotMaterializedMessage(message: String?): Boolean {
    val normalized = message?.trim()?.lowercase().orEmpty()
    if (normalized.isEmpty()) {
        return false
    }
    return normalized.contains("not materialized")
        || normalized.contains("not yet materialized")
        || normalized.contains("no rollout found")
        || normalized.contains("includeturns is unavailable before first user message")
        || normalized.contains("include turns is unavailable before first user message")
}

internal fun preserveLocalRenderableAttachments(
    existingDetail: ThreadDetail?,
    incomingDetail: ThreadDetail,
): ThreadDetail {
    if (existingDetail == null || existingDetail.threadId != incomingDetail.threadId) {
        return incomingDetail
    }

    val claimedLocalIndexes = mutableSetOf<Int>()
    val mergedEntries = incomingDetail.entries.mapIndexed { incomingIndex, incomingEntry ->
        if (!timelineEntryNeedsLocalRenderableAttachments(incomingEntry)) {
            return@mapIndexed incomingEntry
        }

        val localMatchIndex = findLocalAttachmentMatchIndex(
            existingEntries = existingDetail.entries,
            incomingEntry = incomingEntry,
            incomingIndex = incomingIndex,
            claimedLocalIndexes = claimedLocalIndexes,
        ) ?: return@mapIndexed incomingEntry

        claimedLocalIndexes += localMatchIndex
        incomingEntry.copy(attachments = existingDetail.entries[localMatchIndex].attachments)
    }

    return incomingDetail.copy(entries = mergedEntries)
}

private fun findLocalAttachmentMatchIndex(
    existingEntries: List<TimelineEntry>,
    incomingEntry: TimelineEntry,
    incomingIndex: Int,
    claimedLocalIndexes: Set<Int>,
): Int? {
    val donorEntries = existingEntries.withIndex().filter { indexedEntry ->
        indexedEntry.index !in claimedLocalIndexes && timelineEntryCanDonateRenderableAttachments(indexedEntry.value)
    }
    if (donorEntries.isEmpty()) {
        return null
    }

    donorEntries.firstOrNull { it.value.id == incomingEntry.id }?.let { return it.index }
    donorEntries.firstOrNull { it.index == incomingIndex && timelineEntriesLikelyMatch(it.value, incomingEntry) }
        ?.let { return it.index }
    donorEntries.firstOrNull {
        timelineEntriesLikelyMatch(it.value, incomingEntry) && attachmentCountsLookCompatible(it.value, incomingEntry)
    }?.let { return it.index }
    donorEntries.firstOrNull { timelineEntriesLikelyMatch(it.value, incomingEntry) }
        ?.let { return it.index }

    if (normalizeTimelineEntryBody(incomingEntry.body).isEmpty()) {
        donorEntries.firstOrNull {
            normalizeTimelineEntryBody(it.value.body).isEmpty() && attachmentCountsLookCompatible(it.value, incomingEntry)
        }?.let { return it.index }
    }

    return null
}

private fun timelineEntryNeedsLocalRenderableAttachments(entry: TimelineEntry): Boolean {
    return entry.kind == TimelineEntryKind.Chat &&
        entry.speaker.equals("You", ignoreCase = true) &&
        !hasRenderableAttachments(entry.attachments)
}

private fun timelineEntryCanDonateRenderableAttachments(entry: TimelineEntry): Boolean {
    return entry.kind == TimelineEntryKind.Chat &&
        entry.speaker.equals("You", ignoreCase = true) &&
        entry.attachments.any(::hasRenderableAttachment)
}

private fun timelineEntriesLikelyMatch(
    localEntry: TimelineEntry,
    incomingEntry: TimelineEntry,
): Boolean {
    return localEntry.kind == incomingEntry.kind &&
        localEntry.speaker.equals(incomingEntry.speaker, ignoreCase = true) &&
        normalizeTimelineEntryBody(localEntry.body) == normalizeTimelineEntryBody(incomingEntry.body)
}

private fun attachmentCountsLookCompatible(
    localEntry: TimelineEntry,
    incomingEntry: TimelineEntry,
): Boolean {
    return incomingEntry.attachments.isEmpty() || localEntry.attachments.size == incomingEntry.attachments.size
}

private fun normalizeTimelineEntryBody(value: String): String {
    return value.trim().replace("\r\n", "\n")
}

data class LiveThreadActionState(
    val threadId: String,
    val action: LiveThreadAction,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val trustedReconnectStore = SharedPrefsTrustedReconnectStore(application)
    private val deviceHistoryStore = SharedPrefsDeviceHistoryStore(application)
    private val liveThreadSessionStore = SharedPrefsLiveThreadSessionStore(application)
    private val runtimeConfigStore = SharedPrefsRuntimeConfigStore(application)
    private val imageAttachmentCacheStore = SharedPrefsImageAttachmentCacheStore(application)
    val relayBootstrapService = RelayBootstrapService(trustedReconnectPersistence = trustedReconnectStore)
    private val relayRequestMutex = Mutex()

    // --- Navigation state ---
    var currentScreen by mutableStateOf(AppScreen.Home)
    var selectedThreadId by mutableStateOf<String?>(null)
        private set
    var isDrawerOpen by mutableStateOf(false)
        private set

    // --- Device history state ---
    var deviceHistory by mutableStateOf<List<DeviceHistoryEntry>>(emptyList())
        private set
    var stagedReconnectDevice by mutableStateOf<DeviceHistoryEntry?>(null)
        private set
    var pendingDeviceRenameId by mutableStateOf<String?>(null)
        private set
    var pendingDeviceRenameSuggestedName by mutableStateOf<String?>(null)
        private set

    // --- Pairing state ---
    var pairingCodeInput by mutableStateOf("")
    var stagedPairingPayload by mutableStateOf<PairingQrPayload?>(null)
        private set
    var acceptedPairingPayload by mutableStateOf<PairingQrPayload?>(null)
        private set
    var pairingStatusMessage by mutableStateOf<PairingStatusMessage?>(null)
        private set
    var bootstrapPhase by mutableStateOf(RelayBootstrapPhase.Idle)
        private set
    var bootstrapVerification by mutableStateOf<RelayBootstrapVerification?>(null)
        private set
    var trustedReconnectRecord by mutableStateOf<TrustedReconnectRecord?>(null)
        private set
    var isRecoveringTrustedLiveSession by mutableStateOf(false)
        private set

    // --- Thread state ---
    var threadSummaries by mutableStateOf(SampleShellData.threads)
        private set
    var threadDetails by mutableStateOf(SampleShellData.details)
        private set
    var isShowingLiveThreads by mutableStateOf(false)
        private set
    var liveThreadActionState by mutableStateOf<LiveThreadActionState?>(null)
        private set
    private var liveThreadStatusOverrides by mutableStateOf<Map<String, ThreadStatus>>(emptyMap())
    private var runtimeAssistantEntriesByThreadId by mutableStateOf<Map<String, Map<String, TimelineEntry>>>(emptyMap())
    private var cachedAttachmentMessagesByThreadId: Map<String, List<CachedAttachmentMessage>> =
        imageAttachmentCacheStore.read().messagesByThreadId
    var draftsByThreadId by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var isCreatingThread by mutableStateOf(false)
        private set
    private var pendingCreatedThreadSummary by mutableStateOf<ThreadSummary?>(null)
    private var pendingMaterializationThreadIds by mutableStateOf<Set<String>>(emptySet())
    private var selectionLockThreadId: String? = null
    private var lastTrustedAutoReconnectAttemptElapsedRealtimeMs: Long? = null
    private var lastLiveThreadListRefreshElapsedRealtimeMs: Long? = null
    private var isAppInForeground: Boolean = false
    private var liveThreadSyncJob: Job? = null
    private var hasPendingRuntimeThreadListRefresh: Boolean = false

    // --- Snackbar ---
    var snackbarMessage by mutableStateOf<String?>(null)

    // --- Model / reasoning / attachment state ---
    var availableModels by mutableStateOf<List<ModelOption>>(emptyList())
        private set
    var isLoadingModels by mutableStateOf(false)
        private set
    var selectedModelId by mutableStateOf<String?>(null)
    private var globalSelectedReasoningEffort by mutableStateOf<String?>(null)
    private var globalSelectedServiceTier by mutableStateOf<ServiceTier?>(null)
    private var threadRuntimeOverridesByThreadId by mutableStateOf<Map<String, ThreadRuntimeOverride>>(emptyMap())
    var selectedAccessMode by mutableStateOf(AccessMode.OnRequest)
    var contextWindowUsage by mutableStateOf<ContextWindowUsage?>(null)
        private set
    var currentGitBranch by mutableStateOf("main")
        private set
    var pendingAttachments by mutableStateOf<List<ImageAttachment>>(emptyList())
        private set
    var isPlanModeArmed by mutableStateOf(false)
        private set

    val selectedModelTitle: String
        get() {
            val model = selectedModelOption
            return model?.let { modelDisplayTitle(it) } ?: "Auto"
        }

    val globalSelectedReasoningTitle: String
        get() = globalSelectedReasoningEffort?.let(::reasoningEffortTitle) ?: "Auto"

    val defaultReasoningEffortSelection: String?
        get() = globalSelectedReasoningEffort

    val defaultServiceTierSelection: ServiceTier?
        get() = globalSelectedServiceTier

    private val selectedModelOption: ModelOption?
        get() = resolveSelectedModelOption(
            availableModels = availableModels,
            selectedModelId = selectedModelId,
        )

    private val effectiveModelIdentifier: String?
        get() = selectedModelOption?.model
            ?: availableModels.firstOrNull { it.isDefault }?.model
            ?: availableModels.firstOrNull()?.model

    private val currentThreadRuntimeOverride: ThreadRuntimeOverride?
        get() = selectedThreadId
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { threadRuntimeOverridesByThreadId[it] }

    val isCurrentThreadReasoningOverridden: Boolean
        get() {
            val overrideEffort = currentThreadRuntimeOverride
                ?.takeIf { it.overridesReasoning }
                ?.reasoningEffort
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: return false
            val supportedEfforts = currentReasoningOptions.map { it.effort }.toSet()
            return supportedEfforts.isEmpty() || overrideEffort in supportedEfforts
        }

    val isCurrentThreadServiceTierOverridden: Boolean
        get() = currentThreadRuntimeOverride?.overridesServiceTier == true

    val selectedReasoningEffort: String?
        get() {
            val supportedEfforts = currentReasoningOptions.map { it.effort }.toSet()
            val overrideEffort = currentThreadRuntimeOverride
                ?.takeIf { it.overridesReasoning }
                ?.reasoningEffort
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            if (overrideEffort != null && (supportedEfforts.isEmpty() || overrideEffort in supportedEfforts)) {
                return overrideEffort
            }

            val globalEffort = globalSelectedReasoningEffort?.trim()?.takeIf(String::isNotEmpty)
            if (globalEffort != null && (supportedEfforts.isEmpty() || globalEffort in supportedEfforts)) {
                return globalEffort
            }

            return null
        }

    val selectedServiceTier: ServiceTier?
        get() = resolveEffectiveServiceTier(
            globalServiceTier = globalSelectedServiceTier,
            threadRuntimeOverride = currentThreadRuntimeOverride,
        )

    val selectedReasoningTitle: String
        get() = reasoningEffortTitle(effectiveReasoningEffort)

    val currentReasoningOptions: List<ReasoningDisplayOption>
        get() {
            val model = selectedModelOption
            return model?.supportedReasoningEfforts?.sortedBy { it.rank }
                ?: defaultReasoningOptions
        }

    val effectiveReasoningEffort: String
        get() = resolveEffectiveReasoningEffort(
            availableModels = availableModels,
            selectedModelId = selectedModelId,
            globalReasoningEffort = globalSelectedReasoningEffort,
            threadRuntimeOverride = currentThreadRuntimeOverride,
        )

    val effectiveGlobalReasoningEffort: String
        get() = resolveEffectiveReasoningEffort(
            availableModels = availableModels,
            selectedModelId = selectedModelId,
            globalReasoningEffort = globalSelectedReasoningEffort,
            threadRuntimeOverride = null,
        )

    fun selectModel(id: String?) {
        selectedModelId = id?.trim()?.takeIf(String::isNotEmpty)
        globalSelectedReasoningEffort = null
        normalizeAndPersistRuntimeConfig()
    }

    fun selectReasoningEffort(effort: String?) {
        val normalizedEffort = effort?.trim()?.takeIf(String::isNotEmpty)
        val threadId = selectedThreadId?.trim()?.takeIf(String::isNotEmpty)
        if (threadId != null && isShowingLiveThreads) {
            if (normalizedEffort == null || normalizedEffort == globalSelectedReasoningEffort) {
                clearThreadReasoningEffortOverride(threadId)
            } else {
                updateThreadRuntimeOverride(threadId) { current ->
                    current.copy(
                        reasoningEffort = normalizedEffort,
                        overridesReasoning = true,
                    )
                }
            }
            return
        }
        globalSelectedReasoningEffort = normalizedEffort
        normalizeAndPersistRuntimeConfig()
    }

    fun selectServiceTier(tier: ServiceTier?) {
        val threadId = selectedThreadId?.trim()?.takeIf(String::isNotEmpty)
        if (threadId != null && isShowingLiveThreads) {
            if (tier == globalSelectedServiceTier) {
                clearThreadServiceTierOverride(threadId)
            } else {
                updateThreadRuntimeOverride(threadId) { current ->
                    current.copy(
                        serviceTier = tier,
                        overridesServiceTier = true,
                    )
                }
            }
            return
        }
        globalSelectedServiceTier = tier
        persistRuntimeConfig()
    }

    fun useDefaultReasoningForCurrentThread() {
        clearThreadReasoningEffortOverride(selectedThreadId)
    }

    fun useDefaultServiceTierForCurrentThread() {
        clearThreadServiceTierOverride(selectedThreadId)
    }

    fun selectGlobalReasoningEffort(effort: String?) {
        globalSelectedReasoningEffort = effort?.trim()?.takeIf(String::isNotEmpty)
        normalizeAndPersistRuntimeConfig()
    }

    fun selectGlobalServiceTier(tier: ServiceTier?) {
        globalSelectedServiceTier = tier
        persistRuntimeConfig()
    }

    fun selectAccessMode(mode: AccessMode) {
        selectedAccessMode = mode
        persistRuntimeConfig()
    }

    fun togglePlanMode() {
        isPlanModeArmed = !isPlanModeArmed
    }

    fun clearPlanMode() {
        isPlanModeArmed = false
    }

    fun addAttachment(attachment: ImageAttachment) {
        if (pendingAttachments.size >= 5) {
            snackbarMessage = "Maximum 5 images per message."
            return
        }
        pendingAttachments = pendingAttachments + attachment
    }

    fun addAttachmentFromUri(uri: Uri) {
        if (pendingAttachments.size >= 5) {
            snackbarMessage = "Maximum 5 images per message."
            return
        }

        val contentResolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            val attachment = runCatching {
                encodeImageAttachment(contentResolver = contentResolver, uri = uri)
            }.getOrElse { error ->
                Log.w(TAG, "attachment encode failed: ${error.message}", error)
                null
            }

            if (attachment == null) {
                snackbarMessage = "Could not prepare that image for sending."
                return@launch
            }
            addAttachment(attachment)
        }
    }

    fun removeAttachment(id: String) {
        pendingAttachments = pendingAttachments.filter { it.id != id }
    }

    fun clearAttachments() {
        pendingAttachments = emptyList()
    }

    private fun persistRuntimeConfig() {
        runtimeConfigStore.write(
            RuntimeConfigState(
                selectedModelId = selectedModelId,
                selectedReasoningEffort = globalSelectedReasoningEffort,
                selectedServiceTier = globalSelectedServiceTier,
                selectedAccessMode = selectedAccessMode,
                threadOverridesByThreadId = threadRuntimeOverridesByThreadId,
            ),
        )
    }

    private fun normalizeAndPersistRuntimeConfig() {
        val normalized = normalizeRuntimeConfigState(
            state = RuntimeConfigState(
                selectedModelId = selectedModelId,
                selectedReasoningEffort = globalSelectedReasoningEffort,
                selectedServiceTier = globalSelectedServiceTier,
                selectedAccessMode = selectedAccessMode,
                threadOverridesByThreadId = threadRuntimeOverridesByThreadId,
            ),
            availableModels = availableModels,
        )
        selectedModelId = normalized.selectedModelId
        globalSelectedReasoningEffort = normalized.selectedReasoningEffort
        globalSelectedServiceTier = normalized.selectedServiceTier
        selectedAccessMode = normalized.selectedAccessMode
        threadRuntimeOverridesByThreadId = normalized.threadOverridesByThreadId
        persistRuntimeConfig()
    }

    private fun updateThreadRuntimeOverride(
        threadId: String?,
        mutate: (ThreadRuntimeOverride) -> ThreadRuntimeOverride,
    ) {
        val normalizedThreadId = threadId?.trim()?.takeIf(String::isNotEmpty) ?: return
        val current = threadRuntimeOverridesByThreadId[normalizedThreadId] ?: ThreadRuntimeOverride()
        val next = mutate(current)
        threadRuntimeOverridesByThreadId = if (next.isEmpty) {
            threadRuntimeOverridesByThreadId - normalizedThreadId
        } else {
            threadRuntimeOverridesByThreadId + (normalizedThreadId to next)
        }
        persistRuntimeConfig()
    }

    private fun clearThreadReasoningEffortOverride(threadId: String?) {
        updateThreadRuntimeOverride(threadId) { current ->
            current.copy(
                reasoningEffort = null,
                overridesReasoning = false,
            )
        }
    }

    private fun clearThreadServiceTierOverride(threadId: String?) {
        updateThreadRuntimeOverride(threadId) { current ->
            current.copy(
                serviceTier = null,
                overridesServiceTier = false,
            )
        }
    }

    private fun clearRuntimeOverridesForDeletedThread(threadId: String?) {
        val normalizedThreadId = threadId?.trim()?.takeIf(String::isNotEmpty) ?: return
        if (normalizedThreadId !in threadRuntimeOverridesByThreadId) {
            return
        }
        threadRuntimeOverridesByThreadId = threadRuntimeOverridesByThreadId - normalizedThreadId
        persistRuntimeConfig()
    }

    fun loadModelsFromRelay() {
        if (isLoadingModels) return
        isLoadingModels = true
        viewModelScope.launch {
            try {
                val relayModels = withRelayRequestLock {
                    relayBootstrapService.fetchModelList()
                }
                availableModels = relayModels.map { rm ->
                    ModelOption(
                        id = rm.id,
                        model = rm.model,
                        displayName = rm.displayName,
                        isDefault = rm.isDefault,
                        supportedReasoningEfforts = rm.supportedReasoningEfforts.map { e ->
                            ReasoningDisplayOption(
                                effort = e.reasoningEffort,
                                title = reasoningEffortTitle(e.reasoningEffort),
                            )
                        },
                        defaultReasoningEffort = rm.defaultReasoningEffort,
                    )
                }
                normalizeAndPersistRuntimeConfig()
            } catch (error: Exception) {
                Log.w(TAG, "model/list failed: ${error.message}", error)
            } finally {
                isLoadingModels = false
            }
        }
    }

    fun refreshContextWindowUsage() {
        val threadId = selectedThreadId ?: return
        if (!isShowingLiveThreads) return
        if (isPendingMaterializationThread(threadId)) return
        viewModelScope.launch {
            try {
                val result = withRelayRequestLock {
                    relayBootstrapService.fetchContextWindowUsage(threadId)
                }
                if (result != null) {
                    contextWindowUsage = ContextWindowUsage(
                        usedTokens = result.first,
                        maxTokens = result.second,
                    )
                }
            } catch (error: Exception) {
                Log.w(TAG, "context window fetch failed: ${error.message}")
            }
        }
    }

    // --- Thread management actions ---
    fun renameThread(threadId: String, newTitle: String) {
        val trimmedTitle = newTitle.trim()
        if (trimmedTitle.isEmpty()) {
            snackbarMessage = "Conversation title cannot be empty."
            return
        }

        threadSummaries = threadSummaries.map {
            if (it.id == threadId) it.copy(title = trimmedTitle) else it
        }
        updateLiveThreadSessionState { state ->
            state.copy(
                renamedTitlesByThreadId = state.renamedTitlesByThreadId + (threadId to trimmedTitle),
            )
        }
        if (!isShowingLiveThreads) return

        viewModelScope.launch {
            runCatching {
                withRelayRequestLock {
                    relayBootstrapService.renameThread(threadId = threadId, name = trimmedTitle)
                }
            }.onFailure { error ->
                Log.w(TAG, "thread rename sync failed: ${error.message}", error)
                snackbarMessage = error.message ?: "Conversation renamed locally only."
            }
        }
    }

    fun archiveThread(threadId: String) {
        val subtreeThreadIds = collectSubtreeThreadIds(threadSummaries, threadId)
        val hiddenThreadIds = if (subtreeThreadIds.isEmpty()) setOf(threadId) else subtreeThreadIds
        hideThreadsLocally(hiddenThreadIds)
        updateLiveThreadSessionState { state ->
            state.copy(
                selectedThreadId = selectedThreadId,
                archivedThreadIds = state.archivedThreadIds + hiddenThreadIds,
                deletedThreadIds = state.deletedThreadIds - hiddenThreadIds,
            )
        }
        snackbarMessage = "Conversation archived."
        if (!isShowingLiveThreads) return

        viewModelScope.launch {
            runCatching {
                withRelayRequestLock {
                    relayBootstrapService.archiveThread(threadId = threadId, unarchive = false)
                }
            }.onFailure { error ->
                Log.w(TAG, "thread archive sync failed: ${error.message}", error)
            }
        }
    }

    fun deleteThread(threadId: String) {
        val descendantThreadIds = collectDescendantThreadIds(threadSummaries, threadId)
        val hiddenThreadIds = descendantThreadIds + threadId
        hideThreadsLocally(hiddenThreadIds)
        forgetRenderableAttachmentMessages(hiddenThreadIds)
        clearRuntimeOverridesForDeletedThread(threadId)
        updateLiveThreadSessionState { state ->
            state.copy(
                selectedThreadId = selectedThreadId,
                archivedThreadIds = (state.archivedThreadIds + descendantThreadIds) - threadId,
                deletedThreadIds = state.deletedThreadIds + threadId,
                renamedTitlesByThreadId = state.renamedTitlesByThreadId - threadId,
            )
        }
        snackbarMessage = "Conversation deleted."
        if (!isShowingLiveThreads) return

        viewModelScope.launch {
            runCatching {
                withRelayRequestLock {
                    relayBootstrapService.archiveThread(threadId = threadId, unarchive = false)
                }
            }.onFailure { error ->
                Log.w(TAG, "thread delete sync failed: ${error.message}", error)
            }
        }
    }

    // --- Derived state ---
    val bridge: BridgeSnapshot
        get() = toBridgeSnapshot(
            payload = acceptedPairingPayload,
            trustedReconnectRecord = trustedReconnectRecord,
            bootstrapPhase = bootstrapPhase,
            bootstrapVerification = bootstrapVerification,
            deviceHistory = deviceHistory,
        )

    val draftMessage: String
        get() = selectedThreadId?.let { draftsByThreadId[it] }.orEmpty()

    val selectedThread: ThreadSummary?
        get() = selectedThreadId?.let { id -> threadSummaries.firstOrNull { it.id == id } }

    val selectedDetail: ThreadDetail?
        get() = selectedThreadId?.let { threadDetails[it] }

    val isSendingTurn: Boolean
        get() = liveThreadActionState?.threadId == selectedThreadId &&
            liveThreadActionState?.action == LiveThreadAction.Sending

    val isStoppingTurn: Boolean
        get() = liveThreadActionState?.threadId == selectedThreadId &&
            liveThreadActionState?.action == LiveThreadAction.Stopping

    val isContinuingTurn: Boolean
        get() = liveThreadActionState?.threadId == selectedThreadId &&
            liveThreadActionState?.action == LiveThreadAction.Continuing

    init {
        trustedReconnectRecord = trustedReconnectStore.read()
        deviceHistory = deviceHistoryStore.readAll()
        // Migrate current trusted record to history if not already present
        trustedReconnectRecord?.let { record ->
            if (deviceHistory.none { it.macDeviceId == record.macDeviceId }) {
                deviceHistoryStore.write(record.toDeviceHistoryEntry())
                deviceHistory = deviceHistoryStore.readAll()
            }
        }
        val persistedRuntimeConfig = runtimeConfigStore.read()
        selectedModelId = persistedRuntimeConfig.selectedModelId
        globalSelectedReasoningEffort = persistedRuntimeConfig.selectedReasoningEffort
        globalSelectedServiceTier = persistedRuntimeConfig.selectedServiceTier
        selectedAccessMode = persistedRuntimeConfig.selectedAccessMode
        threadRuntimeOverridesByThreadId = persistedRuntimeConfig.threadOverridesByThreadId
        val persisted = liveThreadSessionStore.read().selectedThreadId
        if (persisted != null && SampleShellData.threads.any { it.id == persisted }) {
            selectedThreadId = persisted
        }
        relayBootstrapService.setSessionLossListener { error ->
            handOffLiveSessionLoss(error)
        }
        relayBootstrapService.setRuntimeEventListener { event ->
            handleRuntimeEvent(event)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopLiveThreadSyncLoop()
        relayBootstrapService.setSessionLossListener(null)
        relayBootstrapService.setRuntimeEventListener(null)
    }

    fun setAppInForeground(isForeground: Boolean) {
        if (isAppInForeground == isForeground) {
            return
        }
        isAppInForeground = isForeground
        if (isForeground) {
            attemptForegroundReconnect()
            restartLiveThreadSyncLoop(immediate = true)
        } else {
            stopLiveThreadSyncLoop()
        }
    }

    // --- Navigation ---

    fun selectThread(threadId: String) {
        selectionLockThreadId = null
        showThread(threadId, reloadDetail = true)
    }

    fun updateDrawerVisibility(isOpen: Boolean) {
        isDrawerOpen = isOpen
    }

    private fun showThread(
        threadId: String,
        reloadDetail: Boolean,
    ) {
        selectedThreadId = threadId
        currentScreen = AppScreen.Home
        if (isShowingLiveThreads) {
            updateLiveThreadSessionState { state ->
                state.copy(selectedThreadId = threadId)
            }
        }
        if (reloadDetail) {
            loadLiveThreadDetail(threadId)
        }
        refreshContextWindowUsage()
    }

    fun openSettings() {
        currentScreen = AppScreen.Settings
    }

    fun openPairing() {
        currentScreen = AppScreen.Pairing
    }

    fun navigateHome() {
        currentScreen = AppScreen.Home
    }

    // --- Draft management ---

    fun updateDraftMessage(value: String) {
        val threadId = selectedThreadId ?: return
        draftsByThreadId = if (value.isBlank()) {
            draftsByThreadId - threadId
        } else {
            draftsByThreadId + (threadId to value)
        }
    }

    // --- Pairing actions ---

    fun acceptScannedPairingPayload(payload: PairingQrPayload) {
        acceptScannedPairingPayload(payload = payload, autoConnect = false)
    }

    fun acceptScannedPairingPayload(
        payload: PairingQrPayload,
        autoConnect: Boolean,
    ) {
        stagePairingPayload(
            payload = payload,
            statusTitle = if (autoConnect) "Connecting to your device" else "QR code scanned",
            statusBody = if (autoConnect) {
                "Checking the scanned device and opening a secure connection."
            } else {
                payload.relayGuidanceMessage()
            },
            clearManualInput = true,
        )
        if (autoConnect) {
            startBootstrap(openConversationOnSuccess = currentScreen != AppScreen.Settings)
        }
    }

    private fun stagePairingPayload(
        payload: PairingQrPayload,
        statusTitle: String,
        statusBody: String,
        clearManualInput: Boolean = false,
    ) {
        stagedReconnectDevice = null
        stagedPairingPayload = payload
        if (clearManualInput) {
            pairingCodeInput = ""
        }
        if (!relayBootstrapService.hasActiveSession()) {
            acceptedPairingPayload = null
            bootstrapPhase = RelayBootstrapPhase.Idle
            bootstrapVerification = null
        }
        pairingStatusMessage = PairingStatusMessage(
            tone = PairingStatusTone.Success,
            title = statusTitle,
            body = statusBody,
        )
    }

    fun validatePairingCode() {
        when (val result = validatePairingQrCode(pairingCodeInput)) {
            is PairingQrValidationResult.Success -> {
                stagePairingPayload(
                    payload = result.payload,
                    statusTitle = "Payload accepted",
                    statusBody = result.payload.relayGuidanceMessage(),
                )
            }
            is PairingQrValidationResult.Invalid -> {
                pairingStatusMessage = PairingStatusMessage(
                    tone = PairingStatusTone.Error,
                    title = "Pairing code rejected",
                    body = result.message,
                )
            }
            is PairingQrValidationResult.UpdateRequired -> {
                pairingStatusMessage = PairingStatusMessage(
                    tone = PairingStatusTone.UpdateRequired,
                    title = "Bridge update required",
                    body = result.message,
                )
            }
        }
    }

    fun clearPairing() {
        val shouldDisconnect = shouldDisconnectActiveSessionWhenClearingPairing(
            isShowingLiveThreads = isShowingLiveThreads,
            bootstrapPhase = bootstrapPhase,
        )
        if (shouldDisconnect || !isShowingLiveThreads) {
            stopLiveThreadSyncLoop()
        }
        if (shouldDisconnect) {
            relayBootstrapService.disconnectActiveSession()
        }
        pairingCodeInput = ""
        stagedReconnectDevice = null
        stagedPairingPayload = null
        pairingStatusMessage = null
        if (!isShowingLiveThreads) {
            acceptedPairingPayload = null
            bootstrapPhase = RelayBootstrapPhase.Idle
            bootstrapVerification = null
            resetConversationPreview(clearSavedSelection = true, clearDrafts = true)
        }
    }

    fun startBootstrap(
        openConversationOnSuccess: Boolean = currentScreen != AppScreen.Settings,
    ) {
        stagedReconnectDevice?.let { entry ->
            startSavedDeviceSwitch(
                entry = entry,
                openConversationOnSuccess = openConversationOnSuccess,
            )
            return
        }
        val payload = stagedPairingPayload ?: return
        stopLiveThreadSyncLoop()
        relayBootstrapService.disconnectActiveSession()
        liveThreadSessionStore.clear()
        resetConversationPreview(clearSavedSelection = true)
        acceptedPairingPayload = payload
        pairingStatusMessage = PairingStatusMessage(
            tone = PairingStatusTone.Success,
            title = "Connecting to your device",
            body = "Opening a secure connection to the selected device.",
        )
        bootstrapVerification = null
        bootstrapPhase = RelayBootstrapPhase.Connecting

        viewModelScope.launch {
            try {
                val verification = withRelayRequestLock {
                    relayBootstrapService.bootstrap(
                        payload = payload,
                        onPhaseChanged = { nextPhase ->
                            bootstrapPhase = nextPhase
                            pairingStatusMessage = when (nextPhase) {
                                RelayBootstrapPhase.Connecting -> PairingStatusMessage(
                                    tone = PairingStatusTone.Success,
                                    title = "Connecting to your device",
                                    body = "Opening the relay and waiting for your device.",
                                )
                                RelayBootstrapPhase.Handshaking -> PairingStatusMessage(
                                    tone = PairingStatusTone.Success,
                                    title = "Verifying your device",
                                    body = "Making sure this phone is talking to the correct device.",
                                )
                                else -> pairingStatusMessage
                            }
                        },
                    )
                }
                bootstrapVerification = verification
                bootstrapPhase = RelayBootstrapPhase.Verified
                stagedPairingPayload = null
                val liveThreads = fetchLiveThreadListWithArchivedReconciliation()
                val previousRecord = trustedReconnectRecord
                trustedReconnectRecord = relayBootstrapService.readTrustedReconnectRecord()
                recordDeviceUsage()
                // Prompt naming dialog for newly paired devices
                trustedReconnectRecord?.let { newRecord ->
                    if (previousRecord?.macDeviceId != newRecord.macDeviceId) {
                        val existingEntry = deviceHistory.find { it.macDeviceId == newRecord.macDeviceId }
                        if (existingEntry?.customName == null) {
                            pendingDeviceRenameId = newRecord.macDeviceId
                            pendingDeviceRenameSuggestedName = newRecord.displayName
                        }
                    }
                }
                threadDetails = emptyMap()
                liveThreadActionState = null
                isShowingLiveThreads = true
                if (openConversationOnSuccess) {
                    currentScreen = AppScreen.Home
                }
                val recoveredThreadId = applyLiveThreadList(
                    liveThreads,
                    preferredThreadId = if (isShowingLiveThreads) selectedThreadId else null,
                )
                liveThreads.firstOrNull { it.id == recoveredThreadId }?.let { thread ->
                    threadDetails = mapOf(thread.id to loadingLiveThreadDetail(thread))
                    loadLiveThreadDetail(thread.id)
                }
                pairingStatusMessage = PairingStatusMessage(
                    tone = PairingStatusTone.Success,
                    title = "Connected to your device",
                    body = if (liveThreads.isEmpty()) {
                        "You are connected, but this device has no conversations yet."
                    } else {
                        "Connection ready. Your conversations are loaded."
                    },
                )
                loadModelsFromRelay()
            } catch (error: RelayBootstrapException) {
                Log.e(TAG, "bootstrap failed: ${error.message}", error)
                bootstrapPhase = RelayBootstrapPhase.Idle
                bootstrapVerification = null
                resetConversationPreview()
                pairingStatusMessage = PairingStatusMessage(
                    tone = PairingStatusTone.Error,
                    title = "Couldn't connect to your device",
                    body = error.message ?: "The connection did not complete.",
                )
                stagedPairingPayload = payload
                acceptedPairingPayload = null
            } catch (error: Exception) {
                Log.e(TAG, "bootstrap failed unexpectedly: ${error.message}", error)
                relayBootstrapService.disconnectActiveSession()
                bootstrapPhase = RelayBootstrapPhase.Idle
                bootstrapVerification = null
                resetConversationPreview()
                pairingStatusMessage = PairingStatusMessage(
                    tone = PairingStatusTone.Error,
                    title = "Couldn't connect to your device",
                    body = error.message ?: "The connection did not complete.",
                )
                stagedPairingPayload = payload
                acceptedPairingPayload = null
            }
        }
    }

    fun forgetTrustedMac() {
        val macId = trustedReconnectRecord?.macDeviceId
        stopLiveThreadSyncLoop()
        relayBootstrapService.disconnectActiveSession()
        relayBootstrapService.clearTrustedReconnectRecord()
        trustedReconnectRecord = null
        stagedPairingPayload = null
        acceptedPairingPayload = null
        pairingStatusMessage = null
        bootstrapPhase = RelayBootstrapPhase.Idle
        bootstrapVerification = null
        resetConversationPreview(clearSavedSelection = true, clearDrafts = true)
        // Remove from device history too
        if (macId != null) {
            deviceHistoryStore.remove(macId)
            deviceHistory = deviceHistoryStore.readAll()
        }
    }

    // --- Device history management ---

    fun openDevices() {
        deviceHistory = deviceHistoryStore.readAll()
        currentScreen = AppScreen.Settings
    }

    fun selectDeviceSwitchTarget(entry: DeviceHistoryEntry) {
        if (trustedReconnectRecord?.macDeviceId == entry.macDeviceId) {
            reconnectTrustedMac(openConversation = currentScreen != AppScreen.Settings)
            return
        }
        stagedReconnectDevice = entry
        stagedPairingPayload = null
        pairingStatusMessage = PairingStatusMessage(
            tone = PairingStatusTone.Success,
            title = "Switch target selected",
            body = "Ready to switch to ${entry.displayLabel()} when you tap Connect.",
        )
    }

    fun renameDevice(macDeviceId: String, newName: String) {
        deviceHistoryStore.rename(macDeviceId, newName)
        deviceHistory = deviceHistoryStore.readAll()
        // Also update the trusted record display name if it's the active device
        trustedReconnectRecord?.let { record ->
            if (record.macDeviceId == macDeviceId) {
                val updated = record.copy(displayName = newName.trim().ifEmpty { null })
                trustedReconnectStore.write(updated)
                trustedReconnectRecord = updated
            }
        }
    }

    fun forgetDevice(macDeviceId: String) {
        // If this is the active device, also clear the active connection
        if (trustedReconnectRecord?.macDeviceId == macDeviceId) {
            forgetTrustedMac()
        } else {
            deviceHistoryStore.remove(macDeviceId)
            deviceHistory = deviceHistoryStore.readAll()
        }
    }

    fun reconnectDevice(entry: DeviceHistoryEntry) {
        selectDeviceSwitchTarget(entry)
    }

    fun dismissDeviceRename() {
        // If there's a suggested name from the bridge, auto-apply it instead of leaving unnamed
        val macDeviceId = pendingDeviceRenameId
        val suggestedName = pendingDeviceRenameSuggestedName
        if (macDeviceId != null && suggestedName != null) {
            renameDevice(macDeviceId, suggestedName)
        }
        pendingDeviceRenameId = null
        pendingDeviceRenameSuggestedName = null
    }

    fun confirmDeviceRename(macDeviceId: String, name: String) {
        if (name.isNotBlank()) {
            renameDevice(macDeviceId, name)
        } else {
            // Fall back to bridge-suggested name if user submits empty
            pendingDeviceRenameSuggestedName?.let { suggested ->
                renameDevice(macDeviceId, suggested)
            }
        }
        pendingDeviceRenameId = null
        pendingDeviceRenameSuggestedName = null
    }

    fun recordDeviceUsage() {
        trustedReconnectRecord?.let { record ->
            val entry = record.toDeviceHistoryEntry().copy(
                lastUsedAt = System.currentTimeMillis(),
            )
            deviceHistoryStore.write(entry)
            deviceHistory = deviceHistoryStore.readAll()
        }
    }

    // --- Reconnect ---

    fun reconnectTrustedMac(
        openConversation: Boolean = currentScreen != AppScreen.Settings,
    ) {
        viewModelScope.launch {
            reconnectTrustedLiveSession(openConversation = openConversation)
        }
    }

    fun attemptForegroundReconnect() {
        val savedRecord = relayBootstrapService.readTrustedReconnectRecord() ?: trustedReconnectRecord
        trustedReconnectRecord = savedRecord
        if (!shouldAttemptTrustedAutoReconnect(
                hasSavedTrustedMac = savedRecord != null,
                hasActiveSession = relayBootstrapService.hasActiveSession(),
                isRecoveringTrustedLiveSession = isRecoveringTrustedLiveSession,
                currentScreen = currentScreen,
                stagedPairingPayload = stagedPairingPayload,
                pairingCodeInput = pairingCodeInput,
                bootstrapPhase = bootstrapPhase,
            )
        ) {
            return
        }
        val nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
        if (isTrustedAutoReconnectCoolingDown(
                lastAttemptElapsedRealtimeMs = lastTrustedAutoReconnectAttemptElapsedRealtimeMs,
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
            )
        ) {
            return
        }
        lastTrustedAutoReconnectAttemptElapsedRealtimeMs = nowElapsedRealtimeMs

        viewModelScope.launch {
            val recovered = reconnectTrustedLiveSession(
                preferredThreadId = liveThreadSessionStore.read().selectedThreadId ?: selectedThreadId,
                reloadSelectedDetail = false,
                openConversation = false,
                resetDetailCache = false,
                statusTitle = if (isShowingLiveThreads) "Restoring live session" else "Restoring saved device",
                statusBody = if (isShowingLiveThreads) {
                    "Reconnecting to your saved device after the foreground return."
                } else {
                    "Found a saved trusted device and is reopening the live session."
                },
            )
            if (recovered && selectedThreadId != null) {
                loadLiveThreadDetail(selectedThreadId!!)
            }
        }
    }

    private suspend fun reconnectTrustedLiveSession(
        requestedRecord: TrustedReconnectRecord? = null,
        preferredThreadId: String? = null,
        reloadSelectedDetail: Boolean = true,
        openConversation: Boolean = true,
        resetDetailCache: Boolean = reloadSelectedDetail,
        statusTitle: String = "Resolving trusted device",
        statusBody: String = "Looking up the live bridge session for the saved device before reconnecting.",
    ): Boolean {
        if (isRecoveringTrustedLiveSession) return false
        val targetRecord = requestedRecord ?: (relayBootstrapService.readTrustedReconnectRecord() ?: trustedReconnectRecord)
        if (targetRecord == null) {
            return false
        }
        if (!trustedReconnectMutex.tryLock()) {
            return false
        }
        isRecoveringTrustedLiveSession = true

        pairingStatusMessage = PairingStatusMessage(
            tone = PairingStatusTone.Success,
            title = statusTitle,
            body = statusBody,
        )
        stagedPairingPayload = null
        stagedReconnectDevice = requestedRecord?.toDeviceHistoryEntry()
        acceptedPairingPayload = null
        bootstrapVerification = null
        bootstrapPhase = RelayBootstrapPhase.Connecting

        return try {
            val reconnectResult = withRelayRequestLock {
                relayBootstrapService.reconnectTrustedSession(
                    trustedRecord = targetRecord,
                    onPhaseChanged = { nextPhase ->
                        bootstrapPhase = nextPhase
                        pairingStatusMessage = when (nextPhase) {
                            RelayBootstrapPhase.Connecting -> PairingStatusMessage(
                                tone = PairingStatusTone.Success,
                                title = "Reconnecting saved device",
                                body = "Android resolved the live relay session and is opening the trusted reconnect path.",
                            )
                            RelayBootstrapPhase.Handshaking -> PairingStatusMessage(
                                tone = PairingStatusTone.Success,
                                title = "Trusted secure handshake running",
                                body = "The relay is open and Android is verifying the trusted reconnect handshake.",
                            )
                            else -> pairingStatusMessage
                        }
                    },
                )
            }
            acceptedPairingPayload = reconnectResult.payload
            trustedReconnectRecord = reconnectResult.trustedRecord
            stagedReconnectDevice = null
            bootstrapVerification = reconnectResult.verification
            bootstrapPhase = RelayBootstrapPhase.Verified
            recordDeviceUsage()
            val liveThreads = fetchLiveThreadListWithArchivedReconciliation()
            if (resetDetailCache) threadDetails = emptyMap()
            liveThreadActionState = null
            isShowingLiveThreads = true
            if (openConversation) currentScreen = AppScreen.Home
            val recoveredThreadId = applyLiveThreadList(
                liveThreads,
                preferredThreadId = preferredThreadId ?: selectedThreadId,
            )
            if (reloadSelectedDetail) {
                liveThreads.firstOrNull { it.id == recoveredThreadId }?.let { thread ->
                    if (isPendingMaterializationThread(thread.id)) {
                        threadDetails = mapOf(
                            thread.id to pendingMaterializationThreadDetail(thread, threadDetails[thread.id]),
                        )
                    } else {
                        threadDetails = mapOf(thread.id to loadingLiveThreadDetail(thread))
                        val liveDetail = withRelayRequestLock {
                            relayBootstrapService.fetchThreadDetail(thread.id)
                        }
                        applyLiveThreadDetail(liveDetail)
                    }
                }
            }
            restartLiveThreadSyncLoop(immediate = !reloadSelectedDetail)
            val usedFallback = reconnectResult.reconnectPath == TrustedReconnectPath.SavedSessionFallback
            pairingStatusMessage = PairingStatusMessage(
                tone = PairingStatusTone.Success,
                title = if (usedFallback) "Saved session reconnect verified" else "Trusted reconnect verified",
                body = if (liveThreads.isEmpty()) {
                    "Connected, but your device returned no conversations yet."
                } else {
                    "Reconnected and loaded your live conversations."
                },
            )
            loadModelsFromRelay()
            true
        } catch (error: RelayBootstrapException) {
            Log.e(TAG, "trusted reconnect failed: ${error.message}", error)
            relayBootstrapService.disconnectActiveSession()
            bootstrapPhase = RelayBootstrapPhase.Idle
            bootstrapVerification = null
            pairingStatusMessage = PairingStatusMessage(
                tone = PairingStatusTone.Error,
                title = "Trusted reconnect failed",
                body = error.message ?: "The saved device could not be reconnected.",
            )
            false
        } catch (error: Exception) {
            Log.e(TAG, "trusted reconnect failed: ${error.message}", error)
            relayBootstrapService.disconnectActiveSession()
            bootstrapPhase = RelayBootstrapPhase.Idle
            bootstrapVerification = null
            pairingStatusMessage = PairingStatusMessage(
                tone = PairingStatusTone.Error,
                title = "Trusted reconnect failed",
                body = error.message ?: "The saved device could not be reconnected.",
            )
            false
        } finally {
            isRecoveringTrustedLiveSession = false
            trustedReconnectMutex.unlock()
        }
    }

    private fun startSavedDeviceSwitch(
        entry: DeviceHistoryEntry,
        openConversationOnSuccess: Boolean,
    ) {
        viewModelScope.launch {
            reconnectTrustedLiveSession(
                requestedRecord = entry.toTrustedReconnectRecord().copy(
                    lastUsedAt = System.currentTimeMillis(),
                ),
                openConversation = openConversationOnSuccess,
                statusTitle = "Switching device",
                statusBody = "Disconnecting from the current device and reconnecting to ${entry.displayLabel()}.",
            )
        }
    }

    // --- Thread actions ---

    fun startNewThread(preferredProjectPath: String? = null) {
        if (isCreatingThread) {
            return
        }
        if (!isShowingLiveThreads) {
            snackbarMessage = "Reconnect to your device first."
            return
        }

        val normalizedProjectPath = preferredProjectPath
            ?.trim()
            ?.takeIf(String::isNotEmpty)

        isCreatingThread = true
        viewModelScope.launch {
            try {
                val createdThread = try {
                    withRelayRequestLock {
                        relayBootstrapService.startThread(
                            preferredProjectPath = normalizedProjectPath,
                            modelIdentifier = effectiveModelIdentifier,
                        )
                    }
                } catch (error: RelayBootstrapException) {
                    if (!recoverLiveSessionIfNeeded(error, selectedThreadId ?: "new-thread")) {
                        snackbarMessage = error.message ?: "Unable to create a chat right now."
                        return@launch
                    }
                    try {
                        withRelayRequestLock {
                            relayBootstrapService.startThread(
                                preferredProjectPath = normalizedProjectPath,
                                modelIdentifier = effectiveModelIdentifier,
                            )
                        }
                    } catch (retryError: Exception) {
                        snackbarMessage = retryError.message ?: "Unable to create a chat right now."
                        return@launch
                    }
                } catch (error: Exception) {
                    snackbarMessage = error.message ?: "Unable to create a chat right now."
                    return@launch
                }

                pendingCreatedThreadSummary = createdThread
                markThreadPendingMaterialization(createdThread.id)
                selectionLockThreadId = createdThread.id
                threadSummaries = listOf(createdThread) + threadSummaries.filterNot { it.id == createdThread.id }
                threadDetails = threadDetails + (createdThread.id to freshLiveThreadDetail(createdThread))
                showThread(createdThread.id, reloadDetail = false)
                restartLiveThreadSyncLoop(immediate = false)
            } finally {
                isCreatingThread = false
            }
        }
    }

    fun sendTurn() {
        val threadId = selectedThreadId ?: return
        val message = draftMessage.trim()
        val attachmentsToSend = pendingAttachments
        if (!isShowingLiveThreads) {
            snackbarMessage = "Reconnect to your device first."
            return
        }
        if (threadDetails[threadId]?.isRunning == true) {
            snackbarMessage = "Thread is still running. Stop it first."
            return
        }
        if (message.isEmpty() && attachmentsToSend.isEmpty()) {
            snackbarMessage = "Type a message or attach an image before sending."
            return
        }
        val summary = threadSummaries.firstOrNull { it.id == threadId } ?: return
        val previousSummaries = threadSummaries
        val previousDetail = threadDetails[threadId]
        val optimisticDetail = optimisticDetailAfterSend(
            summary = summary,
            previousDetail = previousDetail,
            userInput = message,
            attachments = attachmentsToSend,
        )
        val optimisticPreview = previewLabelForSend(message, attachmentsToSend)

        threadSummaries = threadSummaries.map {
            if (it.id == threadId) {
                it.copy(
                    status = ThreadStatus.Running,
                    preview = optimisticPreview,
                    lastUpdatedLabel = "Now",
                )
            }
            else it
        }
        threadDetails = threadDetails + (threadId to optimisticDetail)
        setLiveThreadStatusOverride(threadId, ThreadStatus.Running)
        updateDraftForThread(threadId, "")
        clearAttachments()
        liveThreadActionState = LiveThreadActionState(threadId, LiveThreadAction.Sending)
        val collaborationMode = if (isPlanModeArmed) CollaborationModeKind.Plan else null
        isPlanModeArmed = false

        viewModelScope.launch {
            pauseLiveThreadSyncLoop()
            try {
                withRelayRequestLock {
                    relayBootstrapService.sendTurnStart(
                        threadId = threadId,
                        userInput = message,
                        attachments = attachmentsToSend,
                        projectPath = summary.projectPath,
                        modelIdentifier = effectiveModelIdentifier,
                        reasoningEffort = selectedReasoningEffort ?: effectiveReasoningEffort,
                        serviceTier = selectedServiceTier?.wireValue,
                        accessMode = selectedAccessMode,
                        collaborationMode = collaborationMode,
                    )
                }
            } catch (error: RelayBootstrapException) {
                if (!recoverLiveSessionIfNeeded(error, threadId)) {
                    revertSendFailure(
                        threadId = threadId,
                        message = message,
                        attachments = attachmentsToSend,
                        previousSummaries = previousSummaries,
                        previousDetail = previousDetail,
                        errorMessage = error.message,
                    )
                    restartLiveThreadSyncLoop(immediate = false)
                    return@launch
                }
                try {
                    withRelayRequestLock {
                        relayBootstrapService.sendTurnStart(
                            threadId = threadId,
                            userInput = message,
                            attachments = attachmentsToSend,
                            projectPath = summary.projectPath,
                            modelIdentifier = effectiveModelIdentifier,
                            reasoningEffort = selectedReasoningEffort ?: effectiveReasoningEffort,
                            serviceTier = selectedServiceTier?.wireValue,
                            accessMode = selectedAccessMode,
                            collaborationMode = collaborationMode,
                        )
                    }
                } catch (retryError: Exception) {
                    revertSendFailure(
                        threadId = threadId,
                        message = message,
                        attachments = attachmentsToSend,
                        previousSummaries = previousSummaries,
                        previousDetail = previousDetail,
                        errorMessage = retryError.message,
                    )
                    restartLiveThreadSyncLoop(immediate = false)
                    return@launch
                }
            } catch (error: Exception) {
                revertSendFailure(
                    threadId = threadId,
                    message = message,
                    attachments = attachmentsToSend,
                    previousSummaries = previousSummaries,
                    previousDetail = previousDetail,
                    errorMessage = error.message,
                )
                restartLiveThreadSyncLoop(immediate = false)
                return@launch
            }

            rememberRenderableAttachmentMessages(threadId = threadId, detail = optimisticDetail)
            liveThreadActionState = null
            restartLiveThreadSyncLoop(immediate = true)
            if (!isPendingMaterializationThread(threadId)) {
                runCatching {
                    refreshLiveThreadAfterAction(threadId) { detail, index ->
                        detail.entries.size > optimisticDetail.entries.size || index == 3
                    }
                }.onFailure { error ->
                    Log.w(TAG, "post-send refresh failed: ${error.message}", error)
                }
            }
        }
    }

    fun stopTurn() {
        val threadId = selectedThreadId ?: return
        if (!isShowingLiveThreads) {
            snackbarMessage = "Reconnect to your device first."
            return
        }
        val summary = threadSummaries.firstOrNull { it.id == threadId } ?: return
        val previousDetail = threadDetails[threadId]
        if (previousDetail != null && !previousDetail.canInterrupt) {
            snackbarMessage = "No active run to stop."
            return
        }

        threadDetails = threadDetails + (threadId to optimisticDetailWhileStopping(summary, previousDetail))
        setLiveThreadStatusOverride(threadId, ThreadStatus.Running)
        liveThreadActionState = LiveThreadActionState(threadId, LiveThreadAction.Stopping)

        viewModelScope.launch {
            pauseLiveThreadSyncLoop()
            try {
                withRelayRequestLock {
                    relayBootstrapService.interruptTurn(
                        threadId = threadId,
                        turnId = previousDetail?.activeTurnId,
                    )
                }
            } catch (error: RelayBootstrapException) {
                if (!recoverLiveSessionIfNeeded(error, threadId)) {
                    revertControlFailure(threadId, summary, previousDetail, error.message)
                    restartLiveThreadSyncLoop(immediate = false)
                    return@launch
                }
                try {
                    withRelayRequestLock {
                        relayBootstrapService.interruptTurn(
                            threadId = threadId,
                            turnId = previousDetail?.activeTurnId,
                        )
                    }
                } catch (retryError: Exception) {
                    revertControlFailure(threadId, summary, previousDetail, retryError.message)
                    restartLiveThreadSyncLoop(immediate = false)
                    return@launch
                }
            } catch (error: Exception) {
                revertControlFailure(threadId, summary, previousDetail, error.message)
                restartLiveThreadSyncLoop(immediate = false)
                return@launch
            }

            runCatching {
                refreshLiveThreadAfterAction(threadId) { detail, index ->
                    !detail.isRunning || index == 3
                }
            }
            liveThreadActionState = null
            snackbarMessage = "Run stopped."
            restartLiveThreadSyncLoop(immediate = true)
        }
    }

    fun continueTurn() {
        val threadId = selectedThreadId ?: return
        if (!isShowingLiveThreads) {
            snackbarMessage = "Reconnect to your device first."
            return
        }
        val summary = threadSummaries.firstOrNull { it.id == threadId } ?: return
        val previousSummaries = threadSummaries
        val previousDetail = threadDetails[threadId]
        if (previousDetail?.isRunning == true) {
            snackbarMessage = "Thread is still running. Stop it first."
            return
        }

        val optimisticDetail = optimisticDetailAfterSend(
            summary = summary,
            previousDetail = previousDetail,
            userInput = "continue",
            attachments = emptyList(),
        )
        threadSummaries = threadSummaries.map {
            if (it.id == threadId) it.copy(status = ThreadStatus.Running, preview = "continue", lastUpdatedLabel = "Now")
            else it
        }
        threadDetails = threadDetails + (threadId to optimisticDetail)
        setLiveThreadStatusOverride(threadId, ThreadStatus.Running)
        liveThreadActionState = LiveThreadActionState(threadId, LiveThreadAction.Continuing)
        val collaborationMode = if (isPlanModeArmed) CollaborationModeKind.Plan else null
        isPlanModeArmed = false

        viewModelScope.launch {
            pauseLiveThreadSyncLoop()
            try {
                withRelayRequestLock {
                    relayBootstrapService.continueThread(
                        threadId = threadId,
                        projectPath = summary.projectPath,
                        modelIdentifier = effectiveModelIdentifier,
                        reasoningEffort = selectedReasoningEffort ?: effectiveReasoningEffort,
                        serviceTier = selectedServiceTier?.wireValue,
                        accessMode = selectedAccessMode,
                        collaborationMode = collaborationMode,
                    )
                }
            } catch (error: RelayBootstrapException) {
                if (!recoverLiveSessionIfNeeded(error, threadId)) {
                    revertContinueFailure(threadId, previousSummaries, previousDetail, error.message)
                    restartLiveThreadSyncLoop(immediate = false)
                    return@launch
                }
                try {
                    withRelayRequestLock {
                        relayBootstrapService.continueThread(
                            threadId = threadId,
                            projectPath = summary.projectPath,
                            modelIdentifier = effectiveModelIdentifier,
                            reasoningEffort = selectedReasoningEffort ?: effectiveReasoningEffort,
                            serviceTier = selectedServiceTier?.wireValue,
                            accessMode = selectedAccessMode,
                            collaborationMode = collaborationMode,
                        )
                    }
                } catch (retryError: Exception) {
                    revertContinueFailure(threadId, previousSummaries, previousDetail, retryError.message)
                    restartLiveThreadSyncLoop(immediate = false)
                    return@launch
                }
            } catch (error: Exception) {
                revertContinueFailure(threadId, previousSummaries, previousDetail, error.message)
                restartLiveThreadSyncLoop(immediate = false)
                return@launch
            }

            liveThreadActionState = null
            snackbarMessage = "Continue sent."
            restartLiveThreadSyncLoop(immediate = true)
            runCatching {
                refreshLiveThreadAfterAction(threadId) { detail, index ->
                    detail.entries.size > optimisticDetail.entries.size || index == 3
                }
            }.onFailure { error ->
                Log.w(TAG, "post-continue refresh failed: ${error.message}", error)
            }
        }
    }

    // --- Internal helpers ---

    private fun attachmentPreservationSourceDetail(
        threadId: String,
        existingDetail: ThreadDetail?,
    ): ThreadDetail? {
        return buildAttachmentPreservationDetail(
            threadId = threadId,
            existingDetail = existingDetail,
            cachedMessages = cachedAttachmentMessagesByThreadId[threadId].orEmpty(),
        )
    }

    private fun persistImageAttachmentCache() {
        imageAttachmentCacheStore.write(
            ImageAttachmentCacheState(messagesByThreadId = cachedAttachmentMessagesByThreadId),
        )
    }

    private fun rememberRenderableAttachmentMessages(
        threadId: String,
        detail: ThreadDetail,
    ) {
        val normalizedThreadId = threadId.trim().takeIf(String::isNotEmpty) ?: return
        val incomingMessages = extractRenderableAttachmentMessages(detail)
        if (incomingMessages.isEmpty()) {
            return
        }

        val existingMessages = cachedAttachmentMessagesByThreadId[normalizedThreadId].orEmpty()
        val mergedMessages = mergeCachedAttachmentMessages(
            existing = existingMessages,
            incoming = incomingMessages,
        )
        if (mergedMessages == existingMessages) {
            return
        }

        cachedAttachmentMessagesByThreadId =
            cachedAttachmentMessagesByThreadId + (normalizedThreadId to mergedMessages)
        persistImageAttachmentCache()
    }

    private fun forgetRenderableAttachmentMessages(threadIds: Set<String>) {
        val normalizedThreadIds = threadIds.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        if (normalizedThreadIds.isEmpty()) {
            return
        }

        val remainingMessages = cachedAttachmentMessagesByThreadId - normalizedThreadIds
        if (remainingMessages == cachedAttachmentMessagesByThreadId) {
            return
        }

        cachedAttachmentMessagesByThreadId = remainingMessages
        persistImageAttachmentCache()
    }

    private fun updateDraftForThread(threadId: String, value: String) {
        draftsByThreadId = if (value.isBlank()) {
            draftsByThreadId - threadId
        } else {
            draftsByThreadId + (threadId to value)
        }
    }

    private fun resetConversationPreview(
        clearSavedSelection: Boolean = false,
        clearDrafts: Boolean = false,
    ) {
        stopLiveThreadSyncLoop()
        threadSummaries = SampleShellData.threads
        threadDetails = SampleShellData.details
        liveThreadStatusOverrides = emptyMap()
        runtimeAssistantEntriesByThreadId = emptyMap()
        liveThreadActionState = null
        pendingCreatedThreadSummary = null
        pendingMaterializationThreadIds = emptySet()
        selectionLockThreadId = null
        lastLiveThreadListRefreshElapsedRealtimeMs = null
        selectedThreadId = null
        isShowingLiveThreads = false
        if (clearSavedSelection) liveThreadSessionStore.clear()
        if (clearDrafts) draftsByThreadId = emptyMap()
    }

    private fun updateLiveThreadSessionState(
        transform: (LiveThreadSessionState) -> LiveThreadSessionState,
    ) {
        val current = liveThreadSessionStore.read()
        liveThreadSessionStore.write(transform(current))
    }

    private fun hideThreadsLocally(hiddenThreadIds: Set<String>) {
        if (hiddenThreadIds.isEmpty()) {
            return
        }
        if (pendingCreatedThreadSummary?.id in hiddenThreadIds) {
            pendingCreatedThreadSummary = null
        }
        if (selectionLockThreadId in hiddenThreadIds) {
            selectionLockThreadId = null
        }
        pendingMaterializationThreadIds = pendingMaterializationThreadIds - hiddenThreadIds
        val remainingThreads = threadSummaries.filterNot { it.id in hiddenThreadIds }
        threadSummaries = remainingThreads
        threadDetails = threadDetails - hiddenThreadIds
        if (selectedThreadId in hiddenThreadIds) {
            selectedThreadId = remainingThreads.firstOrNull()?.id
        }
    }

    private suspend fun fetchLiveThreadListWithArchivedReconciliation(): List<ThreadSummary> {
        val liveThreads = withRelayRequestLock {
            relayBootstrapService.fetchThreadList()
        }
        val liveThreadIds = liveThreads.mapTo(linkedSetOf()) { it.id }
        val archivedThreads = runCatching {
            withRelayRequestLock {
                relayBootstrapService.fetchThreadList(archived = true)
            }
        }.onFailure { error ->
            Log.w(TAG, "archived thread list sync failed: ${error.message}", error)
        }.getOrDefault(emptyList())
        val archivedOnlyThreads = archivedThreads.filterNot { it.id in liveThreadIds }
        if (archivedOnlyThreads.isNotEmpty()) {
            updateLiveThreadSessionState { state ->
                mergeArchivedThreadsIntoSessionState(
                    sessionState = state,
                    archivedThreads = archivedOnlyThreads,
                )
            }
        }
        return liveThreads
    }

    private fun setLiveThreadStatusOverride(threadId: String, status: ThreadStatus?) {
        liveThreadStatusOverrides = if (status == null) {
            liveThreadStatusOverrides - threadId
        } else {
            liveThreadStatusOverrides + (threadId to status)
        }
    }

    private fun handOffLiveSessionLoss(error: RelayBootstrapException) {
        val savedRecord = relayBootstrapService.readTrustedReconnectRecord() ?: trustedReconnectRecord
        trustedReconnectRecord = savedRecord
        stopLiveThreadSyncLoop()
        acceptedPairingPayload = null
        bootstrapPhase = RelayBootstrapPhase.Idle
        bootstrapVerification = null
        liveThreadActionState = null
        runtimeAssistantEntriesByThreadId = emptyMap()
        isShowingLiveThreads = false
        pairingStatusMessage = PairingStatusMessage(
            tone = PairingStatusTone.Error,
            title = if (savedRecord != null && error.isRecoverableSessionLoss) "Live session lost" else "Live session ended",
            body = "${error.message ?: "The live session ended."} ${if (savedRecord != null) "Reconnect saved device to reopen." else ""}".trim(),
        )
        snackbarMessage = error.message ?: "The live session ended."
    }

    private fun applyLiveThreadList(
        liveThreads: List<ThreadSummary>,
        preferredThreadId: String? = null,
    ): String? {
        lastLiveThreadListRefreshElapsedRealtimeMs = SystemClock.elapsedRealtime()
        val sessionState = liveThreadSessionStore.read()
        val mergedThreads = liveThreads.map { incoming ->
            val existing = threadSummaries.firstOrNull { it.id == incoming.id }
            if (
                incoming.projectPath == "Unknown project" &&
                existing != null &&
                existing.projectPath != "Unknown project"
            ) {
                incoming.copy(projectPath = existing.projectPath)
            } else {
                incoming
            }
        }
        val pendingThread = pendingCreatedThreadSummary
        val liveThreadsWithPending = when {
            pendingThread == null -> mergedThreads
            mergedThreads.any { it.id == pendingThread.id } -> {
                pendingCreatedThreadSummary = null
                mergedThreads
            }
            selectionLockThreadId?.trim() == pendingThread.id -> {
                listOf(pendingThread) + mergedThreads
            }
            else -> mergedThreads
        }
        val decoratedThreads = applyLocalThreadSessionState(liveThreadsWithPending, sessionState)
        val merged = overlayThreadSummaryStatuses(decoratedThreads, threadDetails, liveThreadStatusOverrides)
        threadSummaries = merged
        val recovered = resolveRecoveredSelectedThreadId(
            threads = merged,
            currentSelectedThreadId = selectionLockThreadId ?: preferredThreadId,
            persistedSelectedThreadId = sessionState.selectedThreadId,
        )
        if (recovered != null) {
            selectedThreadId = recovered
            liveThreadSessionStore.write(sessionState.copy(selectedThreadId = recovered))
        } else {
            selectedThreadId = null
            liveThreadSessionStore.write(sessionState.copy(selectedThreadId = null))
        }
        return recovered
    }

    private fun applyLiveThreadDetail(detail: ThreadDetail) {
        markThreadMaterialized(detail.threadId)
        val preservationSource = attachmentPreservationSourceDetail(
            threadId = detail.threadId,
            existingDetail = threadDetails[detail.threadId],
        )
        val preservedDetail = preserveLocalRenderableAttachments(
            existingDetail = preservationSource,
            incomingDetail = detail,
        )
        val mergedDetail = mergeRuntimeAssistantEntries(preservedDetail)
        rememberRenderableAttachmentMessages(detail.threadId, mergedDetail)
        threadDetails = threadDetails + (detail.threadId to mergedDetail)
        setLiveThreadStatusOverride(detail.threadId, mergedDetail.status)
        threadSummaries = threadSummaries.map { syncThreadSummaryWithDetail(it, mergedDetail) }
        if (detail.threadId == selectedThreadId) {
            refreshContextWindowUsage()
        }
    }

    private fun loadLiveThreadDetail(threadId: String) {
        if (!isShowingLiveThreads) return
        val summary = threadSummaries.firstOrNull { it.id == threadId } ?: return
        val existing = threadDetails[threadId]
        if (isPendingMaterializationThread(threadId)) {
            threadDetails = threadDetails + (threadId to pendingMaterializationThreadDetail(summary, existing))
            return
        }
        val shouldReload = existing == null
            || existing.stateLabel.contains("failed", ignoreCase = true)
            || existing.stateLabel.contains("Loading", ignoreCase = true)
        if (!shouldReload) {
            restartLiveThreadSyncLoop(immediate = true)
            return
        }

        threadDetails = threadDetails + (threadId to loadingLiveThreadDetail(summary))
        viewModelScope.launch {
            try {
                val liveDetail = withRelayRequestLock {
                    relayBootstrapService.fetchThreadDetail(threadId)
                }
                applyLiveThreadDetail(liveDetail)
            } catch (error: RelayBootstrapException) {
                if (isThreadNotMaterializedMessage(error.message)) {
                    handleThreadNotMaterialized(threadId, summary, existing)
                    return@launch
                }
                val recovered = recoverLiveSessionIfNeeded(error, threadId)
                if (recovered) {
                    runCatching {
                        val retryDetail = withRelayRequestLock {
                            relayBootstrapService.fetchThreadDetail(threadId)
                        }
                        applyLiveThreadDetail(retryDetail)
                    }.onFailure { retryError ->
                        val relayError = retryError as? RelayBootstrapException
                        if (relayError != null && isThreadNotMaterializedMessage(relayError.message)) {
                            handleThreadNotMaterialized(threadId, summary, existing)
                        } else {
                            threadDetails = threadDetails + (threadId to failedLiveThreadDetail(summary, retryError.message))
                        }
                    }
                    return@launch
                }
                threadDetails = threadDetails + (threadId to failedLiveThreadDetail(summary, error.message))
            } catch (error: Exception) {
                threadDetails = threadDetails + (threadId to failedLiveThreadDetail(summary, error.message))
            } finally {
                restartLiveThreadSyncLoop(immediate = false)
            }
        }
    }

    private suspend fun recoverLiveSessionIfNeeded(
        error: RelayBootstrapException,
        preferredThreadId: String,
    ): Boolean {
        if (!relayBootstrapService.shouldRecoverLiveSession(error)) return false
        return reconnectTrustedLiveSession(
            preferredThreadId = preferredThreadId,
            reloadSelectedDetail = false,
            openConversation = false,
            resetDetailCache = false,
            statusTitle = "Recovering live session",
            statusBody = "The previous live connection expired. Reconnecting.",
        )
    }

    private suspend fun refreshLiveThreadAfterAction(
        threadId: String,
        completionPredicate: (ThreadDetail, Int) -> Boolean,
    ): Boolean {
        val delays = listOf(200L, 600L, 1_200L, 2_000L)
        for ((index, delayMs) in delays.withIndex()) {
            if (delayMs > 0L) delay(delayMs)
            val liveThreads = try {
                fetchLiveThreadListWithArchivedReconciliation()
            } catch (error: RelayBootstrapException) {
                if (!recoverLiveSessionIfNeeded(error, threadId)) throw error
                fetchLiveThreadListWithArchivedReconciliation()
            }
            applyLiveThreadList(liveThreads, preferredThreadId = threadId)
            val detail = try {
                withRelayRequestLock {
                    relayBootstrapService.fetchThreadDetail(threadId)
                }
            } catch (error: RelayBootstrapException) {
                if (!recoverLiveSessionIfNeeded(error, threadId)) throw error
                withRelayRequestLock {
                    relayBootstrapService.fetchThreadDetail(threadId)
                }
            }
            applyLiveThreadDetail(detail)
            if (completionPredicate(detail, index)) return true
        }
        return false
    }

    private fun restartLiveThreadSyncLoop(immediate: Boolean) {
        stopLiveThreadSyncLoop()
        if (!shouldRunLiveThreadSyncLoop(
                isAppInForeground = isAppInForeground,
                isShowingLiveThreads = isShowingLiveThreads,
                hasActiveSession = relayBootstrapService.hasActiveSession(),
                isRecoveringTrustedLiveSession = isRecoveringTrustedLiveSession,
            )
        ) {
            return
        }

        liveThreadSyncJob = viewModelScope.launch {
            var forceListRefresh = immediate
            while (shouldRunLiveThreadSyncLoop(
                    isAppInForeground = isAppInForeground,
                    isShowingLiveThreads = isShowingLiveThreads,
                    hasActiveSession = relayBootstrapService.hasActiveSession(),
                    isRecoveringTrustedLiveSession = isRecoveringTrustedLiveSession,
                )
            ) {
                if (liveThreadActionState != null) {
                    delay(liveThreadRunningSyncIntervalMs)
                    continue
                }
                try {
                    syncLiveThreadsOnce(forceListRefresh = forceListRefresh)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: RelayBootstrapException) {
                    Log.w(TAG, "live sync failed: ${error.message}", error)
                } catch (error: Exception) {
                    Log.w(TAG, "live sync failed unexpectedly: ${error.message}", error)
                }
                forceListRefresh = false
                delay(
                    liveThreadSyncDelayMs(
                        selectedThreadId = selectedThreadId,
                        selectedDetail = selectedDetail,
                        liveThreadActionState = liveThreadActionState,
                    ),
                )
            }
        }
    }

    private fun stopLiveThreadSyncLoop() {
        liveThreadSyncJob?.cancel()
        liveThreadSyncJob = null
    }

    private suspend fun pauseLiveThreadSyncLoop() {
        val currentJob = liveThreadSyncJob ?: return
        liveThreadSyncJob = null
        currentJob.cancelAndJoin()
    }

    private suspend fun syncLiveThreadsOnce(forceListRefresh: Boolean) {
        val preferredThreadId = selectedThreadId
        val shouldRefreshList = forceListRefresh || run {
            val lastRefresh = lastLiveThreadListRefreshElapsedRealtimeMs ?: return@run true
            SystemClock.elapsedRealtime() - lastRefresh >= liveThreadListSyncIntervalMs
        }

        if (shouldRefreshList) {
            val liveThreads = try {
                fetchLiveThreadListWithArchivedReconciliation()
            } catch (error: RelayBootstrapException) {
                val recoveryThreadId = preferredThreadId ?: selectedThreadId
                if (recoveryThreadId == null || !recoverLiveSessionIfNeeded(error, recoveryThreadId)) {
                    throw error
                }
                fetchLiveThreadListWithArchivedReconciliation()
            }
            applyLiveThreadList(liveThreads, preferredThreadId = preferredThreadId)
        }

        val detailThreadId = selectedThreadId ?: preferredThreadId ?: return
        if (isPendingMaterializationThread(detailThreadId)) {
            return
        }
        val detail = try {
            withRelayRequestLock {
                relayBootstrapService.fetchThreadDetail(detailThreadId)
            }
        } catch (error: RelayBootstrapException) {
            if (isThreadNotMaterializedMessage(error.message)) {
                threadSummaries.firstOrNull { it.id == detailThreadId }?.let { summary ->
                    handleThreadNotMaterialized(detailThreadId, summary, threadDetails[detailThreadId])
                }
                return
            }
            if (!recoverLiveSessionIfNeeded(error, detailThreadId)) throw error
            val retryDetail = try {
                withRelayRequestLock {
                    relayBootstrapService.fetchThreadDetail(detailThreadId)
                }
            } catch (retryError: RelayBootstrapException) {
                if (isThreadNotMaterializedMessage(retryError.message)) {
                    threadSummaries.firstOrNull { it.id == detailThreadId }?.let { summary ->
                        handleThreadNotMaterialized(detailThreadId, summary, threadDetails[detailThreadId])
                    }
                    return
                }
                throw retryError
            }
            retryDetail
        }
        applyLiveThreadDetail(detail)
    }

    private suspend fun <T> withRelayRequestLock(block: suspend () -> T): T {
        return relayRequestMutex.withLock {
            block()
        }
    }

    private fun handleRuntimeEvent(event: LiveThreadRuntimeEvent) {
        if (!isShowingLiveThreads) {
            return
        }

        when (event) {
            is LiveThreadRuntimeEvent.ThreadListChanged -> queueRuntimeThreadListRefresh(event.threadIdHint)
            is LiveThreadRuntimeEvent.ThreadStatusChanged -> applyRuntimeThreadStatusChanged(event)
            is LiveThreadRuntimeEvent.TurnStarted -> applyRuntimeTurnStarted(event)
            is LiveThreadRuntimeEvent.TurnCompleted -> applyRuntimeTurnCompleted(event)
            is LiveThreadRuntimeEvent.AssistantDelta -> applyRuntimeAssistantDelta(event)
            is LiveThreadRuntimeEvent.AssistantCompleted -> applyRuntimeAssistantCompleted(event)
            is LiveThreadRuntimeEvent.StructuredEntryUpdated -> applyRuntimeStructuredEntry(event)
            is LiveThreadRuntimeEvent.UserMessageEcho -> applyRuntimeUserMessageEcho(event)
            is LiveThreadRuntimeEvent.ErrorNotification -> applyRuntimeErrorNotification(event)
        }
    }

    private fun queueRuntimeThreadListRefresh(threadIdHint: String?) {
        if (hasPendingRuntimeThreadListRefresh || !relayBootstrapService.hasActiveSession()) {
            return
        }
        hasPendingRuntimeThreadListRefresh = true

        viewModelScope.launch {
            try {
                val preferredThreadId = selectedThreadId ?: threadIdHint
                val liveThreads = try {
                    fetchLiveThreadListWithArchivedReconciliation()
                } catch (error: RelayBootstrapException) {
                    val recoveryThreadId = preferredThreadId ?: return@launch
                    if (!recoverLiveSessionIfNeeded(error, recoveryThreadId)) {
                        throw error
                    }
                    fetchLiveThreadListWithArchivedReconciliation()
                }
                val previousSelectedThreadId = selectedThreadId
                val recoveredThreadId = applyLiveThreadList(
                    liveThreads,
                    preferredThreadId = preferredThreadId,
                )
                val selectedAfterRefresh = selectedThreadId
                if (
                    selectedAfterRefresh != null
                    && selectedAfterRefresh != previousSelectedThreadId
                    && !isPendingMaterializationThread(selectedAfterRefresh)
                    && threadDetails[selectedAfterRefresh] == null
                ) {
                    loadLiveThreadDetail(selectedAfterRefresh)
                } else if (
                    recoveredThreadId != null
                    && !isPendingMaterializationThread(recoveredThreadId)
                    && threadDetails[recoveredThreadId] == null
                ) {
                    loadLiveThreadDetail(recoveredThreadId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RelayBootstrapException) {
                Log.w(TAG, "runtime thread-list refresh failed: ${error.message}", error)
            } catch (error: Exception) {
                Log.w(TAG, "runtime thread-list refresh failed unexpectedly: ${error.message}", error)
            } finally {
                hasPendingRuntimeThreadListRefresh = false
            }
        }
    }

    private fun applyRuntimeTurnStarted(event: LiveThreadRuntimeEvent.TurnStarted) {
        markThreadMaterialized(event.threadId)
        setLiveThreadStatusOverride(event.threadId, ThreadStatus.Running)
        val detail = threadDetails[event.threadId] ?: return
        val updated = detail.copy(
            stateLabel = runtimeStateLabel(ThreadStatus.Running),
            status = ThreadStatus.Running,
            activeTurnId = event.turnId ?: detail.activeTurnId,
            hasInterruptibleTurnWithoutId = event.turnId == null || detail.hasInterruptibleTurnWithoutId,
        )
        threadDetails = threadDetails + (event.threadId to updated)
        threadSummaries = threadSummaries.map { syncThreadSummaryWithDetail(it, updated) }
    }

    private fun applyRuntimeTurnCompleted(event: LiveThreadRuntimeEvent.TurnCompleted) {
        markThreadMaterialized(event.threadId)
        finalizeRuntimeStructuredEntries(event.threadId)
        val detail = threadDetails[event.threadId] ?: return
        val updated = detail.copy(
            stateLabel = runtimeStateLabel(event.status),
            status = event.status,
            activeTurnId = null,
            hasInterruptibleTurnWithoutId = false,
        )
        setLiveThreadStatusOverride(event.threadId, event.status)
        threadDetails = threadDetails + (event.threadId to mergeRuntimeAssistantEntries(updated))
        threadSummaries = threadSummaries.map { syncThreadSummaryWithDetail(it, updated) }
    }

    private fun applyRuntimeAssistantDelta(event: LiveThreadRuntimeEvent.AssistantDelta) {
        markThreadMaterialized(event.threadId)
        val entryId = runtimeAssistantEntryId(
            turnId = event.turnId,
            itemId = event.itemId,
        )
        val existingEntry = runtimeAssistantEntriesByThreadId[event.threadId]
            ?.get(entryId)
        val nextBody = appendRuntimeDelta(existingEntry?.body.orEmpty(), event.delta)
        if (nextBody.isBlank()) {
            return
        }

        val entry = TimelineEntry(
            id = entryId,
            speaker = "Codex",
            timestampLabel = "Now",
            body = nextBody,
        )
        storeRuntimeAssistantEntry(event.threadId, entryId, entry)
        val detail = threadDetails[event.threadId] ?: return
        val updated = mergeRuntimeAssistantEntries(
            detail.copy(
                stateLabel = runtimeStateLabel(ThreadStatus.Running),
                status = ThreadStatus.Running,
                activeTurnId = event.turnId,
            ),
        )
        setLiveThreadStatusOverride(event.threadId, ThreadStatus.Running)
        threadDetails = threadDetails + (event.threadId to updated)
        threadSummaries = threadSummaries.map { syncThreadSummaryWithDetail(it, updated) }
    }

    private fun applyRuntimeAssistantCompleted(event: LiveThreadRuntimeEvent.AssistantCompleted) {
        markThreadMaterialized(event.threadId)
        val entryId = runtimeAssistantEntryId(
            turnId = event.turnId,
            itemId = event.itemId,
        )
        val entry = TimelineEntry(
            id = entryId,
            speaker = "Codex",
            timestampLabel = "Now",
            body = event.text,
        )
        storeRuntimeAssistantEntry(event.threadId, entryId, entry)
        val detail = threadDetails[event.threadId] ?: return
        val updated = mergeRuntimeAssistantEntries(detail)
        threadDetails = threadDetails + (event.threadId to updated)
        threadSummaries = threadSummaries.map { syncThreadSummaryWithDetail(it, updated) }
    }

    private fun applyRuntimeStructuredEntry(event: LiveThreadRuntimeEvent.StructuredEntryUpdated) {
        markThreadMaterialized(event.threadId)
        storeRuntimeAssistantEntry(
            threadId = event.threadId,
            entryId = event.entry.id,
            entry = event.entry,
        )
        // When a completed (non-streaming) structured entry arrives, finalize it
        // explicitly so any lingering streaming indicator is cleared immediately
        // instead of waiting for turn/completed.
        if (!event.entry.isStreaming) {
            finalizeRuntimeStructuredEntryById(event.threadId, event.entry.id)
        }
        val detail = threadDetails[event.threadId] ?: return
        val updated = mergeRuntimeAssistantEntries(
            detail.copy(
                stateLabel = runtimeStateLabel(ThreadStatus.Running),
                status = if (event.turnId != null) ThreadStatus.Running else detail.status,
                activeTurnId = event.turnId ?: detail.activeTurnId,
            ),
        )
        if (event.turnId != null) {
            setLiveThreadStatusOverride(event.threadId, ThreadStatus.Running)
        }
        threadDetails = threadDetails + (event.threadId to updated)
        threadSummaries = threadSummaries.map { syncThreadSummaryWithDetail(it, updated) }
    }

    private fun applyRuntimeThreadStatusChanged(event: LiveThreadRuntimeEvent.ThreadStatusChanged) {
        setLiveThreadStatusOverride(event.threadId, event.status)
        val detail = threadDetails[event.threadId] ?: return
        val updated = detail.copy(
            stateLabel = runtimeStateLabel(event.status),
            status = event.status,
        )
        threadDetails = threadDetails + (event.threadId to updated)
        threadSummaries = threadSummaries.map { syncThreadSummaryWithDetail(it, updated) }
    }

    private fun applyRuntimeUserMessageEcho(event: LiveThreadRuntimeEvent.UserMessageEcho) {
        // User message echo from desktop — add to timeline if not already present
        val detail = threadDetails[event.threadId] ?: return
        val alreadyHasMessage = detail.entries.any { entry ->
            entry.speaker.equals("You", ignoreCase = true) &&
                entry.body.trim() == event.text.trim()
        }
        if (alreadyHasMessage) return
        val entry = TimelineEntry(
            id = "user-echo-${System.currentTimeMillis()}",
            speaker = "You",
            timestampLabel = "Now",
            body = event.text,
        )
        val updated = detail.copy(entries = detail.entries + entry)
        threadDetails = threadDetails + (event.threadId to updated)
    }

    private fun applyRuntimeErrorNotification(event: LiveThreadRuntimeEvent.ErrorNotification) {
        val threadId = event.threadId ?: selectedThreadId ?: return
        val detail = threadDetails[threadId]
        if (detail != null) {
            val errorEntry = TimelineEntry(
                id = "error-${System.currentTimeMillis()}",
                speaker = "System",
                timestampLabel = "Now",
                body = event.message,
                kind = TimelineEntryKind.System,
            )
            val updated = detail.copy(
                entries = detail.entries + errorEntry,
                status = ThreadStatus.Failed,
                stateLabel = "Error",
            )
            setLiveThreadStatusOverride(threadId, ThreadStatus.Failed)
            threadDetails = threadDetails + (threadId to updated)
            threadSummaries = threadSummaries.map { syncThreadSummaryWithDetail(it, updated) }
        }
        snackbarMessage = event.message
    }

    private fun storeRuntimeAssistantEntry(
        threadId: String,
        entryId: String,
        entry: TimelineEntry,
    ) {
        val existingById = runtimeAssistantEntriesByThreadId[threadId].orEmpty()
        runtimeAssistantEntriesByThreadId = runtimeAssistantEntriesByThreadId + (
            threadId to (existingById + (entryId to entry))
        )
    }

    private fun mergeRuntimeAssistantEntries(detail: ThreadDetail): ThreadDetail {
        val runtimeEntries = runtimeAssistantEntriesByThreadId[detail.threadId].orEmpty()
        if (runtimeEntries.isEmpty()) {
            return detail
        }

        val mergedEntries = detail.entries.toMutableList()
        val remainingEntries = runtimeEntries.toMutableMap()

        for ((entryId, overlayEntry) in runtimeEntries) {
            val matchingServerEntryIndex = mergedEntries.indexOfFirst { existing ->
                existing.id == overlayEntry.id
                    || (
                        existing.kind == overlayEntry.kind
                            && existing.speaker == overlayEntry.speaker
                            && normalizeRuntimeBody(existing.body) == normalizeRuntimeBody(overlayEntry.body)
                            && existing.title == overlayEntry.title
                    )
            }
            if (matchingServerEntryIndex >= 0) {
                remainingEntries.remove(entryId)
                continue
            }

            val existingOverlayIndex = mergedEntries.indexOfFirst { it.id == overlayEntry.id }
            if (existingOverlayIndex >= 0) {
                mergedEntries[existingOverlayIndex] = overlayEntry
            } else {
                mergedEntries += overlayEntry
            }
        }

        runtimeAssistantEntriesByThreadId = if (remainingEntries.isEmpty()) {
            runtimeAssistantEntriesByThreadId - detail.threadId
        } else {
            runtimeAssistantEntriesByThreadId + (detail.threadId to remainingEntries)
        }

        return detail.copy(entries = mergedEntries)
    }

    private fun runtimeAssistantEntryId(
        turnId: String?,
        itemId: String?,
    ): String {
        val normalizedTurnId = turnId?.trim().orEmpty()
        val normalizedItemId = itemId?.trim().orEmpty()
        val suffix = when {
            normalizedItemId.isNotEmpty() -> normalizedItemId
            normalizedTurnId.isNotEmpty() -> normalizedTurnId
            else -> "latest"
        }
        return "runtime-assistant-$suffix"
    }

    private fun appendRuntimeDelta(
        existingBody: String,
        delta: String,
    ): String {
        val trimmedDelta = delta.trim()
        if (trimmedDelta.isEmpty()) {
            return existingBody
        }
        return when {
            existingBody.isBlank() -> trimmedDelta
            existingBody.endsWith(trimmedDelta) -> existingBody
            else -> existingBody + trimmedDelta
        }
    }

    private fun normalizeRuntimeBody(value: String): String {
        return value.trim().replace("\r\n", "\n")
    }

    private fun runtimeStateLabel(status: ThreadStatus): String {
        return when (status) {
            ThreadStatus.Running -> "Running"
            ThreadStatus.Waiting -> "Waiting"
            ThreadStatus.Completed -> "Completed"
            ThreadStatus.Failed -> "Failed"
        }
    }

    private fun revertSendFailure(
        threadId: String,
        message: String,
        attachments: List<ImageAttachment>,
        previousSummaries: List<ThreadSummary>,
        previousDetail: ThreadDetail?,
        errorMessage: String?,
    ) {
        liveThreadActionState = null
        updateDraftForThread(threadId, message)
        pendingAttachments = attachments
        threadSummaries = previousSummaries
        val summary = previousSummaries.firstOrNull { it.id == threadId }
        threadDetails = threadDetails + (threadId to failedTurnStartDetail(summary, previousDetail, errorMessage))
        setLiveThreadStatusOverride(threadId, previousDetail?.status ?: summary?.status)
        snackbarMessage = errorMessage ?: "Send failed."
    }

    private fun revertControlFailure(
        threadId: String,
        summary: ThreadSummary,
        previousDetail: ThreadDetail?,
        errorMessage: String?,
    ) {
        liveThreadActionState = null
        threadDetails = threadDetails + (threadId to failedControlDetail(summary, previousDetail, errorMessage))
        setLiveThreadStatusOverride(threadId, previousDetail?.status ?: summary.status)
        snackbarMessage = errorMessage ?: "Control request failed."
    }

    private fun revertContinueFailure(
        threadId: String,
        previousSummaries: List<ThreadSummary>,
        previousDetail: ThreadDetail?,
        errorMessage: String?,
    ) {
        liveThreadActionState = null
        threadSummaries = previousSummaries
        val summary = previousSummaries.firstOrNull { it.id == threadId }
        threadDetails = threadDetails + (threadId to failedTurnStartDetail(summary, previousDetail, errorMessage))
        setLiveThreadStatusOverride(threadId, previousDetail?.status ?: summary?.status)
        snackbarMessage = errorMessage ?: "Continue failed."
    }

    private fun isPendingMaterializationThread(threadId: String?): Boolean {
        val normalizedThreadId = threadId?.trim()?.takeIf(String::isNotEmpty) ?: return false
        return normalizedThreadId in pendingMaterializationThreadIds
    }

    private fun markThreadPendingMaterialization(threadId: String?) {
        val normalizedThreadId = threadId?.trim()?.takeIf(String::isNotEmpty) ?: return
        pendingMaterializationThreadIds = pendingMaterializationThreadIds + normalizedThreadId
    }

    private fun markThreadMaterialized(threadId: String?) {
        val normalizedThreadId = threadId?.trim()?.takeIf(String::isNotEmpty) ?: return
        if (normalizedThreadId !in pendingMaterializationThreadIds) {
            return
        }
        pendingMaterializationThreadIds = pendingMaterializationThreadIds - normalizedThreadId
    }

    private fun handleThreadNotMaterialized(
        threadId: String,
        summary: ThreadSummary,
        existingDetail: ThreadDetail?,
    ) {
        markThreadPendingMaterialization(threadId)
        threadDetails = threadDetails + (threadId to pendingMaterializationThreadDetail(summary, existingDetail))
    }

    private fun pendingMaterializationThreadDetail(
        summary: ThreadSummary,
        existingDetail: ThreadDetail?,
    ): ThreadDetail {
        val preservedDetail = existingDetail?.takeUnless { detail ->
            detail.stateLabel.contains("loading", ignoreCase = true)
                || detail.stateLabel.contains("failed", ignoreCase = true)
        }
        return preservedDetail ?: freshLiveThreadDetail(summary)
    }

    private fun finalizeRuntimeStructuredEntryById(threadId: String, entryId: String) {
        val existingEntries = runtimeAssistantEntriesByThreadId[threadId].orEmpty()
        val entry = existingEntries[entryId] ?: return
        if (!entry.isStreaming) return
        val finalized = if (entry.kind == TimelineEntryKind.Thinking) {
            entry.copy(body = normalizeThinkingEntryBody(entry.body), isStreaming = false)
        } else {
            entry.copy(isStreaming = false)
        }
        if (finalized.kind == TimelineEntryKind.Thinking && normalizeThinkingEntryBody(finalized.body).isBlank()) {
            runtimeAssistantEntriesByThreadId = runtimeAssistantEntriesByThreadId + (
                threadId to (existingEntries - entryId)
            )
        } else {
            runtimeAssistantEntriesByThreadId = runtimeAssistantEntriesByThreadId + (
                threadId to (existingEntries + (entryId to finalized))
            )
        }
    }

    private fun finalizeRuntimeStructuredEntries(threadId: String) {
        val existingEntries = runtimeAssistantEntriesByThreadId[threadId].orEmpty()
        if (existingEntries.isEmpty()) {
            return
        }
        val finalizedEntries = existingEntries.mapValues { (_, entry) ->
            if (entry.kind == TimelineEntryKind.Thinking) {
                entry.copy(
                    body = normalizeThinkingEntryBody(entry.body),
                    isStreaming = false,
                )
            } else {
                entry.copy(isStreaming = false)
            }
        }.filterValues { entry ->
            !(entry.kind == TimelineEntryKind.Thinking && normalizeThinkingEntryBody(entry.body).isBlank())
        }
        runtimeAssistantEntriesByThreadId = if (finalizedEntries.isEmpty()) {
            runtimeAssistantEntriesByThreadId - threadId
        } else {
            runtimeAssistantEntriesByThreadId + (threadId to finalizedEntries)
        }
    }

    // --- Detail factory helpers ---

    private fun loadingLiveThreadDetail(summary: ThreadSummary) = ThreadDetail(
        threadId = summary.id,
        subtitle = "Loading...",
        stateLabel = "Loading live thread detail",
        status = summary.status,
        entries = listOf(
            TimelineEntry(
                id = "${summary.id}-loading",
                speaker = "System",
                timestampLabel = summary.lastUpdatedLabel,
                body = "Reading conversation from your device.",
            ),
        ),
    )

    private fun freshLiveThreadDetail(summary: ThreadSummary) = ThreadDetail(
        threadId = summary.id,
        subtitle = summary.projectPath,
        stateLabel = "Ready",
        status = ThreadStatus.Waiting,
        entries = emptyList(),
    )

    private fun failedLiveThreadDetail(summary: ThreadSummary, message: String?) = ThreadDetail(
        threadId = summary.id,
        subtitle = "Failed to load",
        stateLabel = "Live detail failed",
        status = summary.status,
        entries = listOf(
            TimelineEntry(
                id = "${summary.id}-error",
                speaker = "System",
                timestampLabel = summary.lastUpdatedLabel,
                body = message ?: "Could not load conversation.",
            ),
        ),
    )

    private fun optimisticDetailAfterSend(
        summary: ThreadSummary,
        previousDetail: ThreadDetail?,
        userInput: String,
        attachments: List<ImageAttachment>,
    ): ThreadDetail {
        val base = previousDetail ?: ThreadDetail(
            threadId = summary.id, subtitle = "Live", stateLabel = "Running",
            status = ThreadStatus.Running, entries = emptyList(),
        )
        return base.copy(
            stateLabel = "Running",
            status = ThreadStatus.Running,
            entries = base.entries + listOf(
                TimelineEntry(
                    id = "${summary.id}-send-${System.currentTimeMillis()}",
                    speaker = "You",
                    timestampLabel = "Now",
                    body = userInput,
                    attachments = attachments,
                ),
                TimelineEntry(
                    id = "${summary.id}-pending-${System.currentTimeMillis()}",
                    speaker = "System",
                    timestampLabel = "Now",
                    body = "Waiting for bridge...",
                ),
            ),
        )
    }

    private fun optimisticDetailWhileStopping(
        summary: ThreadSummary,
        previousDetail: ThreadDetail?,
    ): ThreadDetail {
        val base = previousDetail ?: ThreadDetail(
            threadId = summary.id, subtitle = "Live", stateLabel = "Running",
            status = ThreadStatus.Running, entries = emptyList(),
        )
        return base.copy(stateLabel = "Stopping...", status = ThreadStatus.Running)
    }

    private fun failedTurnStartDetail(
        summary: ThreadSummary?,
        previousDetail: ThreadDetail?,
        message: String?,
    ) = ThreadDetail(
        threadId = summary?.id ?: previousDetail?.threadId ?: "",
        subtitle = previousDetail?.subtitle ?: "Live",
        stateLabel = "Send failed",
        status = previousDetail?.status ?: summary?.status ?: ThreadStatus.Waiting,
        activeTurnId = previousDetail?.activeTurnId,
        latestTurnId = previousDetail?.latestTurnId,
        hasInterruptibleTurnWithoutId = previousDetail?.hasInterruptibleTurnWithoutId == true,
        entries = (previousDetail?.entries.orEmpty()) + TimelineEntry(
            id = "${summary?.id}-error-${System.currentTimeMillis()}",
            speaker = "System",
            timestampLabel = "Now",
            body = message ?: "Send failed.",
        ),
    )

    private fun failedControlDetail(
        summary: ThreadSummary,
        previousDetail: ThreadDetail?,
        message: String?,
    ) = ThreadDetail(
        threadId = summary.id,
        subtitle = previousDetail?.subtitle ?: "Live",
        stateLabel = "Control failed",
        status = previousDetail?.status ?: summary.status,
        activeTurnId = previousDetail?.activeTurnId,
        latestTurnId = previousDetail?.latestTurnId,
        hasInterruptibleTurnWithoutId = previousDetail?.hasInterruptibleTurnWithoutId == true,
        entries = (previousDetail?.entries.orEmpty()) + TimelineEntry(
            id = "${summary.id}-control-error-${System.currentTimeMillis()}",
            speaker = "System",
            timestampLabel = "Now",
            body = message ?: "Control request failed.",
        ),
    )

    private fun previewLabelForSend(
        message: String,
        attachments: List<ImageAttachment>,
    ): String {
        if (message.isNotBlank()) {
            return message
        }
        return when (attachments.size) {
            0 -> "No preview yet."
            1 -> "Image attachment"
            else -> "${attachments.size} image attachments"
        }
    }

    companion object {
        private fun toBridgeSnapshot(
            payload: PairingQrPayload?,
            trustedReconnectRecord: TrustedReconnectRecord?,
            bootstrapPhase: RelayBootstrapPhase,
            bootstrapVerification: RelayBootstrapVerification?,
            deviceHistory: List<DeviceHistoryEntry> = emptyList(),
        ): BridgeSnapshot {
            val phase = resolveBridgeConnectionPhase(
                payload = payload,
                trustedReconnectRecord = trustedReconnectRecord,
                bootstrapPhase = bootstrapPhase,
                bootstrapVerification = bootstrapVerification,
            )
            // Resolve custom name from device history if available
            fun resolveDeviceName(macDeviceId: String, fallback: String): String {
                val historyEntry = deviceHistory.find { it.macDeviceId == macDeviceId }
                return historyEntry?.customName ?: fallback
            }

            if (payload == null) {
                if (trustedReconnectRecord != null) {
                    return BridgeSnapshot(
                        deviceName = resolveDeviceName(
                            trustedReconnectRecord.macDeviceId,
                            trustedReconnectRecord.maskedMacDeviceLabel(),
                        ),
                        relayLabel = trustedReconnectRecord.relayDisplayLabel(),
                        phase = phase,
                        trustStored = true,
                        lastSeenLabel = when (phase) {
                            ConnectionPhase.Ready -> "Connected"
                            ConnectionPhase.Connecting -> "Connecting..."
                            ConnectionPhase.Handshaking -> "Verifying device..."
                            else -> "Saved device ready"
                        },
                    )
                }
                return SampleShellData.placeholderBridge
            }
            return BridgeSnapshot(
                deviceName = resolveDeviceName(
                    payload.macDeviceId,
                    payload.maskedMacDeviceLabel(),
                ),
                relayLabel = payload.relayDisplayLabel(),
                phase = phase,
                trustStored = trustedReconnectRecord != null,
                lastSeenLabel = when {
                    bootstrapVerification != null -> "Connected"
                    bootstrapPhase == RelayBootstrapPhase.Connecting -> "Connecting..."
                    bootstrapPhase == RelayBootstrapPhase.Handshaking -> "Verifying device..."
                    else -> "QR scanned until ${payload.expiryLabel()}"
                },
            )
        }
    }
}
