package icu.minq.memoh.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable data class AuthMaterial(val accessToken: String, val expiresAt: String, val apiBase: String, val accountId: String = "")
@Serializable data class LoginRequest(val username: String, val password: String)
@Serializable data class LoginResponse(@SerialName("access_token") val accessToken: String, @SerialName("expires_at") val expiresAt: String)
@Serializable data class RefreshResponse(@SerialName("access_token") val accessToken: String, @SerialName("expires_at") val expiresAt: String)
@Serializable data class CurrentUser(val id: String, val username: String, @SerialName("display_name") val displayName: String = "")
@Serializable data class Bot(val id: String, val name: String = "", @SerialName("display_name") val displayName: String = "", @SerialName("is_active") val active: Boolean = true, val status: String = "", @SerialName("current_user_permissions") val currentUserPermissions: List<String> = emptyList())
@Serializable data class ItemsResponse<T>(val items: List<T> = emptyList(), @SerialName("next_cursor") val nextCursor: String? = null)
@Serializable data class Session(val id: String, @SerialName("bot_id") val botId: String, val title: String = "", val type: String = "chat", @SerialName("session_mode") val sessionMode: String = "chat", @SerialName("runtime_type") val runtimeType: String = "model", @SerialName("channel_type") val channelType: String = "local", @SerialName("workdir_id") val workdirId: String? = null, @SerialName("updated_at") val updatedAt: String? = null, @SerialName("bot_agent_id") val botAgentId: String? = null, val metadata: Map<String, JsonElement> = emptyMap(), @SerialName("runtime_metadata") val runtimeMetadata: Map<String, JsonElement> = emptyMap())
@Serializable data class BotSettings(@SerialName("default_bot_agent_id") val defaultBotAgentId: String? = null, @SerialName("chat_model_id") val chatModelId: String? = null, @SerialName("chat_runtime") val chatRuntime: String = "model", @SerialName("chat_acp_agent_id") val chatAcpAgentId: String? = null, @SerialName("chat_acp_project_path") val projectPath: String? = null, @SerialName("chat_acp_project_mode") val projectMode: String? = null, @SerialName("reasoning_effort") val reasoningEffort: String = "")
@Serializable data class WorkdirsResponse(val workdirs: List<Workdir> = emptyList())
@Serializable data class Workdir(val id: String, val name: String = "", val path: String = "", val archived: Boolean = false, @SerialName("target_kind") val targetKind: String = "")
@Serializable data class CreateSessionRequest(val title: String = "", @SerialName("channel_type") val channelType: String = "local", @SerialName("bot_agent_id") val botAgentId: String? = null, val type: String? = null, @SerialName("session_mode") val sessionMode: String? = null, @SerialName("runtime_type") val runtimeType: String? = null, val metadata: Map<String, String> = emptyMap(), @SerialName("runtime_metadata") val runtimeMetadata: Map<String, String> = emptyMap(), @SerialName("workdir_id") val workdirId: String? = null)

@Serializable data class HistoryResponse(val items: List<ChatTurn> = emptyList())
@Serializable data class ChatTurn(@SerialName("turn_id") val turnId: String = "", val role: String, val text: String = "", val messages: List<MessageBlock> = emptyList(), val timestamp: String = "", val id: String? = null, val attachments: List<ChatAttachment> = emptyList())
@Serializable(with = MessageBlockSerializer::class)
data class MessageBlock(val id: Int = 0, val type: String, val content: String = "", val name: String = "", val input: JsonElement? = null, val output: JsonElement? = null, val toolCallId: String = "", val progress: List<JsonElement> = emptyList(), val running: Boolean = false, val approval: Approval? = null, val userInput: UserInput? = null, val raw: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()))
@Serializable data class Approval(@SerialName("approval_id") val approvalId: String, val status: String, @SerialName("can_approve") val canApprove: Boolean = false, val options: List<ApprovalOption> = emptyList(), @SerialName("selected_option_id") val selectedOptionId: String? = null)
@Serializable data class ApprovalOption(val id: String, val name: String = "", val kind: String = "")
@Serializable data class UserInput(@SerialName("user_input_id") val userInputId: String, val status: String, val questions: List<UserQuestion> = emptyList(), @SerialName("can_respond") val canRespond: Boolean = false)
@Serializable data class UserQuestion(val id: String, val text: String, val kind: String)

@Serializable data class RuntimeRun(val run_id: String, val turn_id: String, val invocation_id: String? = null, val generation: String = "", val status: String, val started_at: String = "", val updated_at: String = "", val messages: List<MessageBlock> = emptyList(), val request_user_turn: ChatTurn? = null, val error_code: String? = null, val error: String? = null)
@Serializable data class RuntimeSnapshot(val bot_id: String = "", val session_id: String, val epoch: String, val seq: Long, val current_run_view: RuntimeRun? = null, val updated_at: String = "")
@Serializable data class RunPatch(val run_id: String, val status: String? = null, val error_code: String? = null, val error: String? = null, val updated_at: String? = null)
@Serializable data class MessageAppend(val id: Int, val type: String, val content: String)
@Serializable data class ProgressAppend(val id: Int, val progress: JsonElement, val input: JsonElement? = null)
@Serializable data class RuntimeDelta(val current_run_view: RuntimeRun? = null, val run: RunPatch? = null, val message_appends: List<MessageAppend> = emptyList(), val progress_appends: List<ProgressAppend> = emptyList(), val message_upserts: List<MessageBlock> = emptyList(), val reset_messages: Boolean = false)

data class RuntimeState(val sessionId: String = "", val epoch: String = "", val seq: Long = -1, val run: RuntimeRun? = null, val needsSnapshot: Boolean = true)

val terminalStatuses = setOf("completed", "aborted", "errored", "lost")
fun RuntimeRun?.isTerminal() = this?.status in terminalStatuses
fun RuntimeRun?.isWaitingApproval() = this?.status == "waiting_decision" || this?.messages.orEmpty().any { it.approval?.status == "pending" }
fun Session?.isExternalChannel() = this?.channelType.orEmpty().trim().let { it.isNotEmpty() && !it.equals("local", ignoreCase = true) }
