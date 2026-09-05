package icu.minq.memoh.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

// Attachment bytes never enter saved state or a generated toString().
@OptIn(ExperimentalSerializationApi::class)
@Serializable class ChatAttachment(
    @EncodeDefault val type: String = "file", val name: String = "", @EncodeDefault val mime: String = "application/octet-stream",
    val base64: String? = null, val size: Long? = null,
    @SerialName("content_hash") val contentHash: String? = null,
)

@Serializable data class ChatModel(
    val id: String = "", val name: String = "", @SerialName("model_id") val modelId: String = "",
    val type: String = "chat", val enable: Boolean = true, val default: Boolean = false,
    val description: String = "",
    @SerialName("provider_id") val providerId: String = "", val reasoning: ReasoningOptions? = null,
    @SerialName("reasoning_efforts") val reasoningEfforts: List<ReasoningEffort> = emptyList(),
    @SerialName("default_reasoning_effort") val defaultReasoningEffort: String = "",
) { fun label() = listOf(name, modelId, id).firstOrNull { it.isNotBlank() && !uuidPattern.matches(it) } ?: "未命名模型" }
private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
@Serializable data class ModelProvider(val id: String = "", val name: String = "")
@Serializable data class ReasoningOptions(val supported: Boolean = false,
    @SerialName("can_disable") val canDisable: Boolean = false, val efforts: List<String> = emptyList(),
    @SerialName("default_effort") val defaultEffort: String = "")
@Serializable data class ReasoningEffort(val id: String = "", val name: String = "") {
    fun label() = when (id) {
        "none", "disable" -> "关闭"; "minimal" -> "最低"; "low" -> "低"; "medium" -> "中"
        "high" -> "高"; "xhigh" -> "更高"; "max" -> "最高"; "adaptive" -> "自适应"
        else -> name.ifBlank { id }
    }
}
@Serializable data class ExternalModels(val models: List<ChatModel> = emptyList(), @SerialName("configured_model_id") val configuredModelId: String = "",
    @SerialName("configured_reasoning_effort") val configuredReasoningEffort: String = "")
@Serializable data class ACPModels(@SerialName("available_models") val availableModels: List<ChatModel> = emptyList(), @SerialName("current_model_id") val currentModelId: String = "", val supported: Boolean = false)
@Serializable data class ACPReasoning(@SerialName("available_efforts") val availableEfforts: List<ReasoningEffort> = emptyList(),
    @SerialName("current_effort") val currentEffort: String = "", val supported: Boolean = false)
@Serializable data class ACPRuntime(@SerialName("runtime_id") val runtimeId: String = "", val models: ACPModels? = null, val reasoning: ACPReasoning? = null)
@Serializable data class ModelSelection(@SerialName("model_id") val modelId: String)
@Serializable data class ReasoningSelection(@SerialName("reasoning_effort") val reasoningEffort: String)

@Serializable data class WorkspaceTargets(val targets: List<WorkspaceTarget> = emptyList())
@Serializable data class WorkspaceTarget(
    @SerialName("target_id") val targetId: String = "", val kind: String = "", val name: String = "",
    val online: Boolean? = null, val status: String = "", val primary: Boolean = false,
) {
    fun label() = if (kind == "native") "服务器工作区" else name.ifBlank { "未命名电脑" }
    fun available() = kind == "native" || (status.ifBlank { if (online == true) "online" else "offline" } == "online" && online != false)
}

fun Session.isAgentRuntime() = runtimeType in setOf("acp_agent", "codex", "claude-code") || type == "acp_agent"
fun Session.canSelectDevice() = !isAgentRuntime() && workdirId.isNullOrBlank() && !isExternalChannel()
fun Session.metadataString(key: String): String = ((runtimeMetadata[key] ?: metadata[key]) as? JsonPrimitive)?.contentOrNull.orEmpty()
fun Session.workspaceTargetId(): String = metadataString("workspace_target_id").ifBlank {
    ((metadata["workspace_target"] as? JsonObject)?.get("target_id") as? JsonPrimitive)?.contentOrNull.orEmpty()
}

fun MessageBlock.attachments(): List<ChatAttachment> = (raw["attachments"] as? JsonArray).orEmpty().mapNotNull {
    runCatching { attachmentJson.decodeFromJsonElement(ChatAttachment.serializer(), it) }.getOrNull()
}
private val attachmentJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }
