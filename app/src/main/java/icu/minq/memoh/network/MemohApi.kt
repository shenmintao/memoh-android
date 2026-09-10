package icu.minq.memoh.network

import icu.minq.memoh.model.*
import icu.minq.memoh.security.AuthStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val status: Int, override val message: String) : Exception(message)

/** The generation identifies an account session, not an individual access token. */
class MemohApi(val client: OkHttpClient, val json: Json, private val tokenStore: AuthStore) {
    private data class Lease(val auth: AuthMaterial, val generation: Long)
    private val authLock = Any()
    private val refreshMutex = Mutex()
    private val generation = MutableStateFlow(0L)
    val authChanges = generation.asStateFlow()
    val authEpoch: Long get() = synchronized(authLock) { generation.value }
    private val media = "application/json; charset=utf-8".toMediaType()

    suspend fun login(baseInput: String, username: String, password: String): CurrentUser {
        val base = ServerUrl.normalize(baseInput)
        val epoch = synchronized(authLock) {
            tokenStore.clear()
            ++generation.value
        }
        val response = execute(Request.Builder().url("$base/auth/login").post(
            json.encodeToString(LoginRequest.serializer(), LoginRequest(username.trim(), password)).toRequestBody(media)
        ).build())
        val login = json.decodeFromString(LoginResponse.serializer(), response)
        val provisional = AuthMaterial(login.accessToken, login.expiresAt, base)
        val user = json.decodeFromString(CurrentUser.serializer(), execute(authorizedRequest(provisional, "users/me", "GET", null)))
        currentCoroutineContext().ensureActive()
        synchronized(authLock) {
            requireEpoch(epoch)
            tokenStore.write(provisional.copy(accountId = user.id))
        }
        return user
    }

    suspend fun me(): CurrentUser = get("users/me", CurrentUser.serializer())
    suspend fun bots(): List<Bot> = get("bots", ItemsResponse.serializer(Bot.serializer())).items
    suspend fun settings(botId: String): BotSettings = get("bots/$botId/settings", BotSettings.serializer())
    suspend fun workdirs(botId: String): List<Workdir> = get("bots/$botId/workdirs", WorkdirsResponse.serializer()).workdirs.filterNot { it.archived }
    suspend fun sessions(botId: String): List<Session> = get("bots/$botId/sessions?types=chat,discuss,acp_agent&limit=50", ItemsResponse.serializer(Session.serializer())).items
    suspend fun session(botId: String, sessionId: String): Session = get("bots/$botId/sessions/$sessionId", Session.serializer())
    suspend fun deleteSession(botId: String, sessionId: String) {
        authenticated("bots/$botId/sessions/$sessionId", "DELETE", null)
    }
    suspend fun history(botId: String, sessionId: String): List<ChatTurn> = get("bots/$botId/messages?session_id=$sessionId&limit=50", HistoryResponse.serializer()).items
    suspend fun workspaceTargets(botId: String): List<WorkspaceTarget> = get("bots/$botId/workspace-targets", WorkspaceTargets.serializer()).targets.filter { it.targetId.isNotBlank() && it.kind.isNotBlank() }
    suspend fun models(): List<ChatModel> = get("models", ListSerializer(ChatModel.serializer())).filter { it.id.isNotBlank() && it.type == "chat" && it.enable }
    suspend fun agentModels(botId: String, agentId: String): ExternalModels = get("bots/$botId/agents/$agentId/models", ExternalModels.serializer())
    suspend fun providers(): List<ModelProvider> = get("providers", ListSerializer(ModelProvider.serializer()))
    suspend fun ensureACPRuntime(botId: String, sessionId: String): ACPRuntime = json.decodeFromString(ACPRuntime.serializer(), authenticated("bots/$botId/sessions/$sessionId/acp-runtime", "POST", null))
    suspend fun setACPModel(botId: String, sessionId: String, modelId: String): ACPRuntime = json.decodeFromString(ACPRuntime.serializer(), authenticated(
        "bots/$botId/sessions/$sessionId/acp-runtime/model", "PATCH", json.encodeToString(ModelSelection.serializer(), ModelSelection(modelId)).toRequestBody(media)))
    suspend fun setACPReasoning(botId: String, sessionId: String, effort: String): ACPRuntime = json.decodeFromString(ACPRuntime.serializer(), authenticated(
        "bots/$botId/sessions/$sessionId/acp-runtime/reasoning", "PATCH", json.encodeToString(ReasoningSelection.serializer(), ReasoningSelection(effort)).toRequestBody(media)))

    suspend fun createSession(botId: String, title: String, workdirId: String?, settings: BotSettings): Session {
        val runtime = settings.chatRuntime.trim().ifEmpty { "model" }
        val external = runtime == "acp_agent" || runtime == "codex" || runtime == "claude-code"
        val metadata = if (runtime == "acp_agent") mapOf(
            "acp_agent_id" to (settings.chatAcpAgentId ?: ""),
            "project_path" to (settings.projectPath ?: "/data"),
            "acp_project_mode" to (settings.projectMode ?: "project")
        ).filterValues { it.isNotBlank() } else emptyMap()
        val body = CreateSessionRequest(
            title = title, botAgentId = settings.defaultBotAgentId?.takeIf { it.isNotBlank() },
            type = "chat", sessionMode = "chat", runtimeType = if (external) runtime else "model",
            runtimeMetadata = metadata, workdirId = workdirId,
        )
        return post("bots/$botId/sessions", body, CreateSessionRequest.serializer(), Session.serializer())
    }

    fun currentAuth(): AuthMaterial? = synchronized(authLock) { tokenStore.read() }
    fun clearAuth() = synchronized(authLock) { tokenStore.clear(); generation.value++ }
    fun clearAuthIfToken(accessToken: String, expectedEpoch: Long = authEpoch) = synchronized(authLock) {
        if (generation.value == expectedEpoch && tokenStore.read()?.accessToken == accessToken) {
            tokenStore.clear(); generation.value++
        }
    }

    /** Migrate pre-v0.1.2 encrypted records after the authenticated /users/me response. */
    fun rememberAccount(id: String) = synchronized(authLock) {
        tokenStore.read()?.let { if (it.accountId != id) tokenStore.write(it.copy(accountId = id)) }
    }

    suspend fun freshAuth(forceRefresh: Boolean = false, rejectedAccessToken: String? = null): AuthMaterial =
        freshLease(forceRefresh, rejectedAccessToken).auth

    private suspend fun freshLease(forceRefresh: Boolean = false, rejectedAccessToken: String? = null): Lease = refreshMutex.withLock {
        val current = synchronized(authLock) { Lease(tokenStore.read() ?: throw ApiException(401, "请先登录"), generation.value) }
        val expiring = runCatching { Instant.parse(current.auth.expiresAt).isBefore(Instant.now().plusSeconds(60)) }.getOrDefault(false)
        if (forceRefresh && rejectedAccessToken != null && current.auth.accessToken != rejectedAccessToken) return@withLock current
        if (!forceRefresh && !expiring) return@withLock current
        refreshLocked(current)
    }

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T {
        val text = authenticated(path, "GET", null)
        return withContext(Dispatchers.Default) { json.decodeFromString(serializer, text) }
    }
    private suspend fun <B, T> post(path: String, body: B, bodySerializer: KSerializer<B>, responseSerializer: KSerializer<T>): T =
        json.decodeFromString(responseSerializer, authenticated(path, "POST", json.encodeToString(bodySerializer, body).toRequestBody(media)))

    private suspend fun authenticated(path: String, method: String, body: RequestBody?): String =
        authenticatedRequest(path, method, body, ::execute)

    private suspend fun <T> authenticatedRequest(path: String, method: String, body: RequestBody?, fetch: suspend (Request) -> T): T {
        val first = freshLease()
        try {
            val text = fetch(authorizedRequest(first.auth, path, method, body))
            synchronized(authLock) { requireEpoch(first.generation) }
            return text
        } catch (failure: ApiException) {
            if (failure.status != 401) throw failure
        }
        val retry = refreshMutex.withLock {
            val installed = synchronized(authLock) {
                requireEpoch(first.generation)
                Lease(tokenStore.read() ?: throw ApiException(401, "登录已失效"), generation.value)
            }
            if (installed.auth.accessToken != first.auth.accessToken) installed else refreshLocked(installed)
        }
        return try {
            val text = fetch(authorizedRequest(retry.auth, path, method, body))
            synchronized(authLock) { requireEpoch(retry.generation) }
            text
        } catch (failure: ApiException) {
            if (failure.status == 401) clearAuthIfToken(retry.auth.accessToken, retry.generation)
            throw failure
        }
    }

    /** Only the bot-scoped media route receives the session credential. */
    suspend fun mediaBytes(botId: String, contentHash: String): ByteArray {
        require(botId.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) { "图片所属机器人无效" }
        require(contentHash.matches(Regex("[a-fA-F0-9]{64}"))) { "图片标识无效" }
        return authenticatedRequest("bots/$botId/media/$contentHash", "GET", null, ::executeImage)
    }

    suspend fun publicImageBytes(url: String): ByteArray {
        val parsed = url.toHttpUrl()
        require(parsed.scheme == "https" && parsed.username.isEmpty() && parsed.password.isEmpty()) { "图片地址无效" }
        // No token or authenticated interceptor is attached to external images.
        return executeImage(Request.Builder().url(parsed).get().build())
    }

    private val imageClient by lazy { client.newBuilder().followRedirects(false).followSslRedirects(false).build() }
    private suspend fun executeImage(request: Request): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = imageClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (!it.isSuccessful) throw ApiException(it.code, "图片加载失败")
                        val body = it.body ?: throw IOException("图片为空")
                        if (body.contentLength() > MAX_IMAGE_BYTES) throw ApiException(413, "图片过大")
                        val output = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                if (!continuation.isActive) throw CancellationException()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size().toLong() + count > MAX_IMAGE_BYTES) throw ApiException(413, "图片过大")
                                output.write(buffer, 0, count)
                            }
                        }
                        output.toByteArray()
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

    private suspend fun refreshLocked(lease: Lease): Lease {
        val current = lease.auth
        val request = Request.Builder().url("${current.apiBase}/auth/refresh")
            .header("Authorization", "Bearer ${current.accessToken}").post(ByteArray(0).toRequestBody(media)).build()
        try {
            val refreshed = json.decodeFromString(RefreshResponse.serializer(), execute(request))
            currentCoroutineContext().ensureActive()
            return synchronized(authLock) {
                requireEpoch(lease.generation)
                val installed = tokenStore.read() ?: throw CancellationException("Authentication session ended")
                if (installed.accessToken != current.accessToken) Lease(installed, lease.generation)
                else Lease(current.copy(accessToken = refreshed.accessToken, expiresAt = refreshed.expiresAt), lease.generation)
                    .also { tokenStore.write(it.auth) }
            }
        } catch (failure: ApiException) {
            // Cancellation, IO failures, 429 and 5xx are not evidence of an invalid account.
            if (failure.status == 401 || failure.status == 403) clearAuthIfToken(current.accessToken, lease.generation)
            throw failure
        }
    }

    private fun requireEpoch(expected: Long) {
        if (generation.value != expected) throw CancellationException("Authentication session changed")
    }

    private fun authorizedRequest(auth: AuthMaterial, path: String, method: String, body: RequestBody?): Request {
        val builder = Request.Builder().url(url(auth.apiBase, path)).header("Authorization", "Bearer ${auth.accessToken}")
        return when (method) {
            "GET" -> builder.get().build()
            "POST" -> builder.post(body ?: ByteArray(0).toRequestBody(media)).build()
            "PATCH" -> builder.patch(body ?: ByteArray(0).toRequestBody(media)).build()
            "DELETE" -> builder.delete(body).build()
            else -> error("Unsupported method")
        }
    }

    private fun url(base: String, pathAndQuery: String): String {
        val parts = pathAndQuery.split('?', limit = 2)
        val builder = base.toHttpUrl().newBuilder().addPathSegments(parts[0])
        if (parts.size == 2) parts[1].split('&').forEach { pair ->
            val kv = pair.split('=', limit = 2)
            builder.addQueryParameter(kv[0], kv.getOrElse(1) { "" })
        }
        return builder.build().toString()
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        val body = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            val safe = runCatching { json.parseToJsonElement(body).toString().take(300) }.getOrDefault("请求失败")
                            throw ApiException(it.code, safe)
                        }
                        body
                    }
                    if (continuation.isActive) continuation.resume(text)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

    companion object {
        const val MAX_IMAGE_BYTES = 24 * 1024 * 1024
        fun defaultClient() = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }
}
