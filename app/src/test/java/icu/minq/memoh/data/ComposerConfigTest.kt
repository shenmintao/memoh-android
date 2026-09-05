package icu.minq.memoh.data

import icu.minq.memoh.model.*
import org.junit.Assert.*
import org.junit.Test

class ComposerConfigTest {
    @Test fun `model and provider UUIDs never become visible labels`() {
        val uuid = "55e6dcc0-c6e8-4366-a91f-61600b217d1b"
        assertEquals("未命名模型", ChatModel(uuid).label())
        assertEquals("gpt-5.6-sol", ChatModel(uuid, modelId = "gpt-5.6-sol").label())
        assertEquals("默认模型", ComposerConfig(defaultModelId = uuid).modelLabel())
        assertEquals("所选模型", ComposerConfig(modelId = uuid).modelLabel())
        assertEquals("其他供应商", ComposerConfig().providerLabel(ChatModel(providerId = uuid)))
    }
    @Test fun `native reasoning follows capabilities when switching models`() {
        val models = listOf(ChatModel("a", reasoning = ReasoningOptions(true, true, listOf("low", "medium", "high"), "medium")),
            ChatModel("b", reasoning = ReasoningOptions(true, false, listOf("low", "high"), "high")), ChatModel("c"))
        val config = ComposerConfig(models = models, modelId = "a", reasoningEffort = "none")
        assertEquals("disable", config.effectiveReasoning())
        assertEquals(listOf("disable", "low", "medium", "high"), config.reasoningOptions().map { it.id })
        assertEquals("high", config.copy(modelId = "b").reconciled().reasoningEffort)
        assertEquals("", config.copy(modelId = "c").reconciled().reasoningEffort)
        assertTrue(config.copy(modelId = "c").reasoningOptions().isEmpty())
    }
    @Test fun `direct runtime resolves configured and per model defaults without invented tiers`() {
        val config = ComposerConfig(agentRuntime = true, models = listOf(ChatModel("a", reasoningEfforts = listOf(ReasoningEffort("high")), defaultReasoningEffort = "high")),
            modelId = "a", reasoningEffort = "low", configuredReasoningEffort = "medium")
        assertEquals("high", config.effectiveReasoning())
        assertEquals(listOf("high"), config.reasoningOptions().map { it.id })
    }
    @Test fun `ACP uses confirmed reasoning and replaces capabilities after model change`() {
        val config = ComposerConfig(acpRuntime = true, reasoningEffort = "low", runtimeReasoning = ACPReasoning(listOf(ReasoningEffort("medium")), "medium", true))
        assertEquals("medium", config.effectiveReasoning())
        assertEquals("", config.withRuntime(ACPRuntime(models = ACPModels(currentModelId = "b"))).effectiveReasoning())
    }
}
