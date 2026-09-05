package icu.minq.memoh.data

import icu.minq.memoh.model.*

data class ComposerConfig(
    val models: List<ChatModel> = emptyList(), val modelId: String = "", val defaultModelId: String = "",
    val modelsLoading: Boolean = false, val modelsError: String? = null, val modelChanging: Boolean = false, val modelUncertain: Boolean = false,
    val targets: List<WorkspaceTarget> = emptyList(), val targetId: String = "",
    val targetsLoading: Boolean = false, val targetsError: String? = null,
) {
    fun modelLabel(): String {
        val id = modelId.ifBlank { defaultModelId }
        return models.firstOrNull { it.id == id }?.label() ?: id.ifBlank { "默认模型" }
    }
    fun targetLabel() = targets.firstOrNull { it.targetId == targetId }?.label() ?: if (targetId.isBlank()) "默认设备" else "设备不可用"
}
