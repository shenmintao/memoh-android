package icu.minq.memoh.network

import icu.minq.memoh.model.*
import icu.minq.memoh.security.AuthStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, override val message: String) : Exception(message)

class MemohApi(val client: OkHttpClient, val json: Json, private val tokenStore: AuthStore) {
    private val refreshMutex = Mutex()
    private val media = "application/json; charset=utf-8".toMediaType()

    suspend fun login(baseInput: String, username: String, password: String): CurrentUser {
        val base = ServerUrl.normalize(baseInput)
        val response = execute(Request.Builder().url("$base/auth/login").post(json.encodeToString(LoginRequest.serializer(), LoginRequest(username.trim(), password)).toRequestBody(media)).build())
        val login = json.decodeFromString(LoginResponse.serializer(), response)
        tokenStore.write(AuthMaterial(login.accessToken, login.expiresAt, base))
        return me()
    }

    suspend fun me(): CurrentUser = get("users/me", CurrentUser.serializer())
    suspend fun bots(): List<Bot> = get("bots", ItemsResponse.serializer(Bot.serializer())).items
    suspend fun settings(botId: String): BotSettings = get("bots/$botId/settings", BotSettings.serializer())
    suspend fun workdirs(botId: String): List<Workdir> = get("bots/$botId/workdirs", WorkdirsResponse.serializer()).workdirs.filterNot { it.archived }
    suspend fun sessions(botId: String): List<Session> = get("bots/$botId/sessions?types=chat,discuss,acp_agent&limit=50", ItemsResponse.serializer(Session.serializer())).items
    suspend fun session(botId: String, sessionId: String): Session = get("bots/$botId/sessions/$sessionId", Session.serializer())
    suspend fun history(botId: String, sessionId: String): List<ChatTurn> = get("bots/$botId/messages?session_id=$sessionId&limit=50", HistoryResponse.serializer()).items

    suspend fun createSession(botId: String, title: String, workdirId: String?, settings: BotSettings): Session {
        val runtime = settings.chatRuntime.trim().ifEmpty { "model" }
        val external = runtime == "acp_agent" || runtime == "codex" || runtime == "claude-code"
        val metadata = if (runtime == "acp_agent") mapOf(
            "acp_agent_id" to (settings.chatAcpAgentId ?: ""),
            "project_path" to (settings.projectPath ?: "/data"),
            "acp_project_mode" to (settings.projectMode ?: "project")
        ).filterValues { it.isNotBlank() } else emptyMap()
        val body = CreateSessionRequest(
            title = title,
            botAgentId = settings.defaultBotAgentId?.takeIf { it.isNotBlank() },
            type = "chat",
            sessionMode = "chat",
            runtimeType = if (external) runtime else "model",
            runtimeMetadata = metadata,
            // This deployment intentionally supports remote ACP workdirs; preserve the selected id.
            workdirId = workdirId,
        )
        return post("bots/$botId/sessions", body, CreateSessionRequest.serializer(), Session.serializer())
    }

    fun currentAuth(): AuthMaterial? = tokenStore.read()
    fun clearAuth() = tokenStore.clear()
    fun clearAuthIfToken(accessToken: String) {
        if (tokenStore.read()?.accessToken == accessToken) tokenStore.clear()
    }

    /** Returns non-expiring auth and serializes all refreshes, including WebSocket handshakes. */
    suspend fun freshAuth(forceRefresh: Boolean = false, rejectedAccessToken: String? = null): AuthMaterial = refreshMutex.withLock {
        val current = tokenStore.read() ?: throw ApiException(401, "请先登录")
        val expiring = runCatching { Instant.parse(current.expiresAt).isBefore(Instant.now().plusSeconds(60)) }.getOrDefault(false)
        if (forceRefresh && rejectedAccessToken != null && current.accessToken != rejectedAccessToken) return@withLock current
        if (!forceRefresh && !expiring) return@withLock current
        refreshLocked(current)
    }

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T =
        json.decodeFromString(serializer, authenticated(path, "GET", null))

    private suspend fun <B, T> post(path: String, body: B, bodySerializer: KSerializer<B>, responseSerializer: KSerializer<T>): T =
        json.decodeFromString(responseSerializer, authenticated(path, "POST", json.encodeToString(bodySerializer, body).toRequestBody(media)))

    private suspend fun authenticated(path: String, method: String, body: RequestBody?): String {
        val firstAuth = freshAuth()
        try {
            return execute(authorizedRequest(firstAuth, path, method, body))
        } catch (first: ApiException) {
            if (first.status != 401) throw first
        }

        val retryAuth = try {
            refreshMutex.withLock {
                val installed = tokenStore.read() ?: throw ApiException(401, "登录已失效")
                if (installed.accessToken != firstAuth.accessToken) installed else refreshLocked(installed)
            }
        } catch (failure: Exception) {
            tokenStore.clear()
            throw failure
        }

        return try {
            execute(authorizedRequest(retryAuth, path, method, body))
        } catch (retry: ApiException) {
            if (retry.status == 401) tokenStore.clear()
            throw retry
        }
    }

    private suspend fun refreshLocked(current: AuthMaterial): AuthMaterial {
        val request = Request.Builder().url("${current.apiBase}/auth/refresh")
            .header("Authorization", "Bearer ${current.accessToken}")
            .post(ByteArray(0).toRequestBody(media)).build()
        return try {
            val refreshed = json.decodeFromString(RefreshResponse.serializer(), execute(request))
            AuthMaterial(refreshed.accessToken, refreshed.expiresAt, current.apiBase).also(tokenStore::write)
        } catch (failure: Exception) {
            tokenStore.clear()
            throw failure
        }
    }

    private fun authorizedRequest(auth: AuthMaterial, path: String, method: String, body: RequestBody?): Request {
        val builder = Request.Builder().url(url(auth.apiBase, path)).header("Authorization", "Bearer ${auth.accessToken}")
        return when (method) {
            "GET" -> builder.get().build()
            "POST" -> builder.post(body ?: ByteArray(0).toRequestBody(media)).build()
            else -> error("Unsupported method")
        }
    }

    private fun url(base: String, pathAndQuery: String): String {
        val parts = pathAndQuery.split('?', limit = 2)
        val builder = base.toHttpUrl().newBuilder().addPathSegments(parts[0])
        if (parts.size == 2) {
            parts[1].split('&').forEach { pair ->
                val kv = pair.split('=', limit = 2)
                builder.addQueryParameter(kv[0], kv.getOrElse(1) { "" })
            }
        }
        return builder.build().toString()
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val safe = runCatching { json.parseToJsonElement(text).toString().take(300) }.getOrDefault("请求失败")
                throw ApiException(response.code, safe)
            }
            text
        }
    }

    companion object {
        fun defaultClient() = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    }
}
