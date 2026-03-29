package dev.remodex.android.feature.runtime

import android.content.Context
import dev.remodex.android.model.AccessMode
import dev.remodex.android.model.ModelOption
import dev.remodex.android.model.ServiceTier
import org.json.JSONArray
import org.json.JSONObject

data class ThreadRuntimeOverride(
    val reasoningEffort: String? = null,
    val serviceTier: ServiceTier? = null,
    val overridesReasoning: Boolean = false,
    val overridesServiceTier: Boolean = false,
) {
    val isEmpty: Boolean
        get() = !overridesReasoning && !overridesServiceTier
}

data class RuntimeConfigState(
    val selectedModelId: String? = null,
    val selectedReasoningEffort: String? = null,
    val selectedServiceTier: ServiceTier? = null,
    val selectedAccessMode: AccessMode = AccessMode.OnRequest,
    val threadOverridesByThreadId: Map<String, ThreadRuntimeOverride> = emptyMap(),
)

interface RuntimeConfigPersistence {
    fun read(): RuntimeConfigState
    fun write(state: RuntimeConfigState)
    fun clear()
}

class SharedPrefsRuntimeConfigStore(
    context: Context,
) : RuntimeConfigPersistence {
    private val preferences = context.getSharedPreferences(
        "dev.remodex.android.runtime_config",
        Context.MODE_PRIVATE,
    )

    override fun read(): RuntimeConfigState {
        val raw = preferences.getString(KEY_STATE, null)?.trim().orEmpty()
        if (raw.isEmpty()) {
            return RuntimeConfigState()
        }

        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return RuntimeConfigState()
        val selectedModelId = json.optString("selectedModelId").trim().takeIf(String::isNotEmpty)
        val selectedReasoningEffort = json.optString("selectedReasoningEffort").trim().takeIf(String::isNotEmpty)
        val selectedServiceTier = json.optString("selectedServiceTier").trim()
            .takeIf(String::isNotEmpty)
            ?.let { rawTier -> ServiceTier.entries.firstOrNull { it.wireValue == rawTier } }
        val selectedAccessMode = json.optString("selectedAccessMode").trim()
            .takeIf(String::isNotEmpty)
            ?.let { rawMode -> AccessMode.entries.firstOrNull { it.name == rawMode } }
            ?: AccessMode.OnRequest
        val threadOverridesByThreadId = buildMap {
            val overrideObjects = json.optJSONObject("threadOverridesByThreadId") ?: JSONObject()
            val keys = overrideObjects.keys()
            while (keys.hasNext()) {
                val threadId = keys.next().trim()
                if (threadId.isEmpty()) {
                    continue
                }
                val overrideObject = overrideObjects.optJSONObject(threadId) ?: continue
                val threadOverride = ThreadRuntimeOverride(
                    reasoningEffort = overrideObject.optString("reasoningEffort").trim().takeIf(String::isNotEmpty),
                    serviceTier = overrideObject.optString("serviceTier").trim()
                        .takeIf(String::isNotEmpty)
                        ?.let { rawTier -> ServiceTier.entries.firstOrNull { it.wireValue == rawTier } },
                    overridesReasoning = overrideObject.optBoolean("overridesReasoning"),
                    overridesServiceTier = overrideObject.optBoolean("overridesServiceTier"),
                )
                if (!threadOverride.isEmpty) {
                    put(threadId, threadOverride)
                }
            }
        }

        return RuntimeConfigState(
            selectedModelId = selectedModelId,
            selectedReasoningEffort = selectedReasoningEffort,
            selectedServiceTier = selectedServiceTier,
            selectedAccessMode = selectedAccessMode,
            threadOverridesByThreadId = threadOverridesByThreadId,
        )
    }

    override fun write(state: RuntimeConfigState) {
        val serialized = JSONObject()
            .put("selectedModelId", state.selectedModelId ?: JSONObject.NULL)
            .put("selectedReasoningEffort", state.selectedReasoningEffort ?: JSONObject.NULL)
            .put("selectedServiceTier", state.selectedServiceTier?.wireValue ?: JSONObject.NULL)
            .put("selectedAccessMode", state.selectedAccessMode.name)
            .put(
                "threadOverridesByThreadId",
                JSONObject().apply {
                    state.threadOverridesByThreadId.toSortedMap().forEach { (threadId, threadOverride) ->
                        if (!threadOverride.isEmpty) {
                            put(
                                threadId,
                                JSONObject()
                                    .put("reasoningEffort", threadOverride.reasoningEffort ?: JSONObject.NULL)
                                    .put("serviceTier", threadOverride.serviceTier?.wireValue ?: JSONObject.NULL)
                                    .put("overridesReasoning", threadOverride.overridesReasoning)
                                    .put("overridesServiceTier", threadOverride.overridesServiceTier),
                            )
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

    private companion object {
        private const val KEY_STATE = "runtime_config_state"
    }
}

internal fun normalizeRuntimeConfigState(
    state: RuntimeConfigState,
    availableModels: List<ModelOption>,
): RuntimeConfigState {
    if (availableModels.isEmpty()) {
        return state
    }

    val resolvedModel = resolveSelectedModelOption(
        availableModels = availableModels,
        selectedModelId = state.selectedModelId,
    ) ?: availableModels.firstOrNull { it.isDefault } ?: availableModels.first()

    val supportedReasoningEfforts = resolvedModel.supportedReasoningEfforts.map { it.effort.trim() }
        .filter(String::isNotEmpty)
        .toSet()

    val normalizedReasoning = when {
        supportedReasoningEfforts.isEmpty() -> null
        state.selectedReasoningEffort in supportedReasoningEfforts -> state.selectedReasoningEffort
        resolvedModel.defaultReasoningEffort in supportedReasoningEfforts -> resolvedModel.defaultReasoningEffort
        "medium" in supportedReasoningEfforts -> "medium"
        else -> resolvedModel.supportedReasoningEfforts.firstOrNull()?.effort
    }

    return state.copy(
        selectedModelId = resolvedModel.id,
        selectedReasoningEffort = normalizedReasoning,
    )
}

internal fun resolveSelectedModelOption(
    availableModels: List<ModelOption>,
    selectedModelId: String?,
): ModelOption? {
    if (availableModels.isEmpty()) {
        return null
    }
    val normalizedSelectedModelId = selectedModelId?.trim()?.takeIf(String::isNotEmpty)
    if (normalizedSelectedModelId != null) {
        availableModels.firstOrNull { model ->
            model.id == normalizedSelectedModelId || model.model == normalizedSelectedModelId
        }?.let { return it }
    }
    return availableModels.firstOrNull { it.isDefault } ?: availableModels.firstOrNull()
}

internal fun resolveEffectiveReasoningEffort(
    availableModels: List<ModelOption>,
    selectedModelId: String?,
    globalReasoningEffort: String?,
    threadRuntimeOverride: ThreadRuntimeOverride?,
): String {
    val model = resolveSelectedModelOption(availableModels, selectedModelId)
    val supportedReasoningEfforts = model?.supportedReasoningEfforts
        ?.map { it.effort.trim() }
        ?.filter(String::isNotEmpty)
        ?.toSet()
        .orEmpty()

    if (threadRuntimeOverride?.overridesReasoning == true) {
        val overrideEffort = threadRuntimeOverride.reasoningEffort?.trim()?.takeIf(String::isNotEmpty)
        if (overrideEffort != null && (supportedReasoningEfforts.isEmpty() || overrideEffort in supportedReasoningEfforts)) {
            return overrideEffort
        }
    }

    val normalizedGlobalReasoning = globalReasoningEffort?.trim()?.takeIf(String::isNotEmpty)
    if (normalizedGlobalReasoning != null && (supportedReasoningEfforts.isEmpty() || normalizedGlobalReasoning in supportedReasoningEfforts)) {
        return normalizedGlobalReasoning
    }

    val modelDefault = model?.defaultReasoningEffort?.trim()?.takeIf(String::isNotEmpty)
    if (modelDefault != null && (supportedReasoningEfforts.isEmpty() || modelDefault in supportedReasoningEfforts)) {
        return modelDefault
    }

    if (supportedReasoningEfforts.isEmpty()) {
        return "medium"
    }

    if ("medium" in supportedReasoningEfforts) {
        return "medium"
    }

    return model?.supportedReasoningEfforts?.firstOrNull()?.effort ?: "medium"
}

internal fun resolveEffectiveServiceTier(
    globalServiceTier: ServiceTier?,
    threadRuntimeOverride: ThreadRuntimeOverride?,
): ServiceTier? {
    if (threadRuntimeOverride?.overridesServiceTier == true) {
        return threadRuntimeOverride.serviceTier
    }
    return globalServiceTier
}
