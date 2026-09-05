package icu.minq.memoh.data

import icu.minq.memoh.model.*

data class ComposerConfig(
    val models: List<ChatModel> = emptyList(), val modelId: String = "", val defaultModelId: String = "",
    val modelsLoading: Boolean = false, val modelsError: String? = null, val modelChanging: Boolean = false, val modelUncertain: Boolean = false,
    val targets: List<WorkspaceTarget> = emptyList(), val targetId: String = "",
    val targetsLoading: Boolean = false, val targetsError: String? = null,
    val providers: List<ModelProvider> = emptyList(), val agentRuntime: Boolean = false, val acpRuntime: Boolean = false,
    val runtimeReasoning: ACPReasoning? = null, val reasoningEffort: String = "", val configuredReasoningEffort: String = "",
) {
    fun selectedModel() = models.firstOrNull { it.id == modelId.ifBlank { defaultModelId } }
    fun modelLabel(): String {
        return selectedModel()?.label() ?: if (modelId.isBlank()) "默认模型" else "所选模型"
    }
    fun providerLabel(model: ChatModel) = providers.firstOrNull { it.id == model.providerId }?.name?.takeIf { it.isNotBlank() } ?: if (model.providerId.isBlank()) "" else "其他供应商"
    fun reasoningOptions(): List<ReasoningEffort> = when {
        acpRuntime -> runtimeReasoning?.takeIf { it.supported }?.availableEfforts.orEmpty()
        agentRuntime -> selectedModel()?.reasoningEfforts.orEmpty()
        else -> selectedModel()?.reasoning?.takeIf { it.supported }?.let { options ->
            (if (options.canDisable) listOf("disable") else emptyList()).plus(options.efforts).map { ReasoningEffort(it) }
        }.orEmpty()
    }.filter { it.id.isNotBlank() }.distinctBy { it.id }
    fun effectiveReasoning(): String {
        val available = reasoningOptions().map { it.id }.toSet()
        if (acpRuntime) return runtimeReasoning?.currentEffort?.takeIf { it in available }.orEmpty()
        val fallback = if (agentRuntime) selectedModel()?.defaultReasoningEffort else selectedModel()?.reasoning?.defaultEffort
        return listOf(reasoningEffort, configuredReasoningEffort, fallback.orEmpty()).map { if (!agentRuntime && it == "none") "disable" else it }
            .firstOrNull { it.isNotBlank() && it in available }.orEmpty()
    }
    fun reasoningLabel() = reasoningOptions().firstOrNull { it.id == effectiveReasoning() }?.label() ?: "默认"
    fun composerLabel() = modelLabel() + if (reasoningOptions().isNotEmpty()) " · ${reasoningLabel()}" else ""
    fun reconciled() = copy(reasoningEffort = effectiveReasoning())
    fun withRuntime(runtime: ACPRuntime) = copy(modelId = runtime.models?.currentModelId.orEmpty(),
        models = runtime.models?.availableModels.orEmpty(), runtimeReasoning = runtime.reasoning, modelUncertain = false).reconciled()
    fun targetLabel() = targets.firstOrNull { it.targetId == targetId }?.label() ?: if (targetId.isBlank()) "默认设备" else "设备不可用"
}
