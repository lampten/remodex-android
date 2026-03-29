package dev.remodex.android.feature.runtime

import dev.remodex.android.model.AccessMode
import dev.remodex.android.model.ModelOption
import dev.remodex.android.model.ReasoningDisplayOption
import dev.remodex.android.model.ServiceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeConfigStoreTest {
    @Test
    fun normalizesToDefaultModelAndCompatibleReasoningEffort() {
        val normalized = normalizeRuntimeConfigState(
            state = RuntimeConfigState(
                selectedModelId = "missing-model",
                selectedReasoningEffort = "xhigh",
                selectedServiceTier = ServiceTier.Fast,
                selectedAccessMode = AccessMode.FullAccess,
            ),
            availableModels = sampleModels(),
        )

        assertEquals("gpt-5.4", normalized.selectedModelId)
        assertEquals("medium", normalized.selectedReasoningEffort)
        assertEquals(ServiceTier.Fast, normalized.selectedServiceTier)
        assertEquals(AccessMode.FullAccess, normalized.selectedAccessMode)
    }

    @Test
    fun effectiveReasoningPrefersSupportedThreadOverrideBeforeGlobalSelection() {
        val effort = resolveEffectiveReasoningEffort(
            availableModels = sampleModels(),
            selectedModelId = "gpt-5.4",
            globalReasoningEffort = "medium",
            threadRuntimeOverride = ThreadRuntimeOverride(
                reasoningEffort = "high",
                overridesReasoning = true,
            ),
        )

        assertEquals("high", effort)
    }

    @Test
    fun effectiveReasoningFallsBackFromUnsupportedThreadOverrideToGlobalSelection() {
        val effort = resolveEffectiveReasoningEffort(
            availableModels = sampleModels(),
            selectedModelId = "gpt-5.4-mini",
            globalReasoningEffort = "medium",
            threadRuntimeOverride = ThreadRuntimeOverride(
                reasoningEffort = "xhigh",
                overridesReasoning = true,
            ),
        )

        assertEquals("medium", effort)
    }

    @Test
    fun effectiveServiceTierHonorsThreadLevelNormalOverride() {
        val resolved = resolveEffectiveServiceTier(
            globalServiceTier = ServiceTier.Fast,
            threadRuntimeOverride = ThreadRuntimeOverride(
                serviceTier = null,
                overridesServiceTier = true,
            ),
        )

        assertNull(resolved)
    }

    private fun sampleModels(): List<ModelOption> {
        return listOf(
            ModelOption(
                id = "gpt-5.4",
                model = "gpt-5.4",
                displayName = "GPT-5.4",
                isDefault = true,
                supportedReasoningEfforts = listOf(
                    ReasoningDisplayOption("medium", "Medium"),
                    ReasoningDisplayOption("high", "High"),
                ),
                defaultReasoningEffort = "medium",
            ),
            ModelOption(
                id = "gpt-5.4-mini",
                model = "gpt-5.4-mini",
                displayName = "GPT-5.4 Mini",
                supportedReasoningEfforts = listOf(
                    ReasoningDisplayOption("low", "Low"),
                    ReasoningDisplayOption("medium", "Medium"),
                ),
                defaultReasoningEffort = "low",
            ),
        )
    }
}
