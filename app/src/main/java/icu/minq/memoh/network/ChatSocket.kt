package icu.minq.memoh.network

import android.os.Handler
import android.os.Looper
import icu.minq.memoh.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min

interface ChatSocketListener {
    fun onRuntime(state: RuntimeState) {}
    fun onAccepted(runId: String, invocationId: String) {}
    fun onRejected(invocationId: String?, message: String) {}
    fun onControl(controlId: String, applied: Boolean, code: String?) {}
    fun onConnection(connected: Boolean) {}
}

internal enum class WebSocketAuthMode { HEADER, QUERY_FALLBACK }
internal object WebSocketHandshake {
    fun fallbackAfter401(mode: WebSocketAuthMode): WebSocketAuthMode? =
        if (mode == WebSocketAuthMode.HEADER) WebSocketAuthMode.QUERY_FALLBACK else null
    fun request(apiBase: String, botId: String, accessToken: String, mode: WebSocketAuthMode): Request {
        val base = ServerUrl.webSocket(apiBase, botId).replaceFirst("wss://", "https://").toHttpUrl()
        val builder = Request.Builder().url(
            if (mode == WebSocketAuthMode.QUERY_FALLBACK) base.newBuilder().addQueryParameter("token", accessToken).build() else base
        )
        if (mode == WebSocketAuthMode.HEADER) builder.header("Authorization", "Bearer $accessToken")
        return builder.build()
    }
}

/** Single-owner actor. Prompts are sent ONCE: ambiguous delivery is reconciled, never replayed. */
class ChatSocket(
    private val api: MemohApi,
    private val botId: String,
    private val sessionId: String,
    private val listener: ChatSocketListener,
    callback: (((() -> Unit)) -> Unit)? = null,
    private val snapshotTimeoutMillis: Long = 15_000,
) {
    private val serial = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "memoh-chat").apply { isDaemon = true }
    }
    private val dispatch: (() -> Unit) -> Unit = callback ?: Handler(Looper.getMainLooper()).let { handler ->
        { block -> handler.post(block); Unit }
    }
    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val expectedEpoch = api.authEpoch
    private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var usable = false
    private var connecting = false
    private var attempt = 0L
    private var reconnectDelay = 1_000L
    private var reconnect: ScheduledFuture<*>? = null
    private var snapshotTimeout: ScheduledFuture<*>? = null
    private var runtimePublication: ScheduledFuture<*>? = null
    private var state = RuntimeState(sessionId = sessionId)
    private data class Control(val type: String, val runId: String, val timer: ScheduledFuture<*>)
    private val controls = mutableMapOf<String, Control>()

    fun connect() = post { connectSerial(WebSocketAuthMode.HEADER) }
    fun isUsable(): Boolean = usable && !closed

    fun sendMessage(text: String, invocationId: String = UUID.randomUUID().toString(),
        attachments: List<ChatAttachment> = emptyList(), modelId: String = "", workspaceTargetId: String = "", reasoningEffort: String = "",
        onQueued: (Boolean) -> Unit = {}) {
        if (closed) { dispatch { onQueued(false) }; return }
        runCatching { serial.execute {
            val sent = if (!isUsable() || api.authEpoch != expectedEpoch) false else socket?.send(buildJsonObject {
                put("type", "message"); put("invocation_id", invocationId); put("session_id", sessionId); put("text", text)
                putJsonArray("attachments") { attachments.forEach { add(api.json.encodeToJsonElement(ChatAttachment.serializer(), it)) } }
                if (modelId.isNotBlank()) put("model_id", modelId)
                if (workspaceTargetId.isNotBlank()) put("workspace_target_id", workspaceTargetId)
                if (reasoningEffort.isNotBlank()) put("reasoning_effort", reasoningEffort)
            }.toString()) == true
            // Report rejection even when screen-close races the actor; never strand the composer gate.
            // Once OkHttp accepts a frame, delivery may be uncertain. Never retain/replay its text.
            dispatch { onQueued(sent) }
        } }.onFailure { dispatch { onQueued(false) } }
    }

    fun steer(runId: String, id: String, text: String, previousId: String, queue: Boolean = false): String? = control("steer", runId, id) { put("text", text); put("previous_steer_id", previousId); if (queue) put("queue", true) }

    fun abort(runId: String): String? = control("abort", runId) { }
    fun approve(runId: String, approvalId: String, approve: Boolean, optionId: String? = null, reason: String = ""): String? =
        control("tool_approval_response", runId) {
            put("decision_id", approvalId); put("decision", if (approve) "approve" else "reject")
            optionId?.takeIf { it.isNotBlank() }?.let { put("option_id", it) }
            reason.trim().takeIf { it.isNotBlank() }?.let { put("reason", it) }
        }

    fun answer(runId: String, inputId: String, answers: List<UserAnswer>, canceled: Boolean): String? =
        control("user_input_response", runId) {
            put("decision_id", inputId)
            if (canceled) { put("canceled", true); put("reason", "user_canceled") }
            else putJsonArray("answers") { answers.forEach { add(api.json.encodeToJsonElement(UserAnswer.serializer(), it)) } }
        }

    fun resync() = post {
        val previous = socket
        detachSerial()
        previous?.cancel()
        connectSerial(WebSocketAuthMode.HEADER)
    }

    fun refreshRuntime() = post { if (isUsable()) subscribeSerial() }

    private fun control(type: String, runId: String, id: String = UUID.randomUUID().toString(), extras: JsonObjectBuilder.() -> Unit): String? {
        if (!isUsable()) return null
        val payload = buildJsonObject {
            put("type", type); put("run_id", runId); put("session_id", sessionId); put("control_id", id); extras()
        }.toString()
        post {
            if (!isUsable() || api.authEpoch != expectedEpoch || socket?.send(payload) != true) {
                notifyMain { listener.onControl(id, false, "connection_lost") }
            } else controls[id] = Control(type, runId, serial.schedule({
                controls.remove(id)
                notifyMain { listener.onControl(id, false, "ack_timeout_status_unknown") }
            }, 30, TimeUnit.SECONDS))
        }
        return id
    }

    fun close() {
        if (closed) return
        closed = true
        usable = false
        authScope.cancel()
        runCatching { serial.execute {
            attempt++
            reconnect?.cancel(false)
            snapshotTimeout?.cancel(false); snapshotTimeout = null
            runtimePublication?.cancel(false); runtimePublication = null
            controls.values.forEach { it.timer.cancel(false) }; controls.clear()
            socket?.cancel(); socket = null
            serial.shutdown() // Drain already-enqueued send callbacks so their UI gate is released.
        } }
    }

    private fun connectSerial(mode: WebSocketAuthMode, forceRefresh: Boolean = false, rejectedToken: String? = null) {
        if (closed || connecting || socket != null) return
        if (api.authEpoch != expectedEpoch) { failPermanent("登录会话已变化，请重新打开聊天"); return }
        reconnect?.cancel(false); reconnect = null
        connecting = true
        val ticket = ++attempt
        authScope.launch {
            try {
                val auth = api.freshAuth(forceRefresh, rejectedToken)
                post {
                    if (ticket != attempt || closed) return@post
                    connecting = false
                    if (api.authEpoch != expectedEpoch) { failPermanent("登录会话已变化，请重新打开聊天"); return@post }
                    openSerial(auth, mode, ticket)
                }
            } catch (_: CancellationException) {
                post { if (ticket == attempt && !closed) failPermanent("登录会话已变化，请重新打开聊天") }
            } catch (failure: Exception) {
                post {
                    if (ticket != attempt || closed) return@post
                    connecting = false
                    if (api.currentAuth() == null || (failure is ApiException && failure.status in setOf(401, 403))) {
                        failPermanent("登录已失效，请重新登录")
                    } else reconnectSerial()
                }
            }
        }
    }

    private fun openSerial(auth: AuthMaterial, mode: WebSocketAuthMode, ticket: Long) {
        val request = WebSocketHandshake.request(auth.apiBase, botId, auth.accessToken, mode)
        socket = api.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = post {
                if (!current(ticket, webSocket)) { webSocket.cancel(); return@post }
                reconnectDelay = 1_000L
                state = state.copy(needsSnapshot = true)
                subscribeSerial() // The composer becomes usable only after the authoritative snapshot.
            }
            override fun onMessage(webSocket: WebSocket, text: String) = post {
                if (current(ticket, webSocket)) handleSerial(text)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = post {
                if (!current(ticket, webSocket)) return@post
                webSocket.close(code, null)
                // Do not wait indefinitely for onClosed. Detach now and bound cleanup of the old transport.
                reconnectSerial()
                serial.schedule({ webSocket.cancel() }, 2, TimeUnit.SECONDS)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = post {
                if (current(ticket, webSocket)) reconnectSerial()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = post {
                if (!current(ticket, webSocket)) return@post
                val status = response?.code
                val fallback = if (status == 401) WebSocketHandshake.fallbackAfter401(mode) else null
                when {
                    fallback != null -> {
                        detachSerial()
                        connectSerial(fallback, true, auth.accessToken)
                    }
                    status == 401 -> {
                        api.clearAuthIfToken(auth.accessToken, expectedEpoch)
                        failPermanent("实时连接认证失败，请重新登录")
                    }
                    status != null && status in 400..499 && status !in setOf(408, 429) ->
                        failPermanent("实时连接被服务器拒绝（HTTP $status），请检查会话和机器人权限")
                    else -> reconnectSerial()
                }
            }
        })
    }

    private fun current(ticket: Long, candidate: WebSocket): Boolean =
        !closed && ticket == attempt && socket === candidate && api.authEpoch == expectedEpoch

    private fun subscribeSerial() {
        // Coalesce gap recovery and bound the wait for an authoritative response.
        if (snapshotTimeout != null) return
        if (socket?.send(buildJsonObject { put("type", "runtime_subscribe"); put("session_id", sessionId) }.toString()) != true) {
            socket?.cancel()
            reconnectSerial()
            return
        }
        snapshotTimeout = serial.schedule({
            snapshotTimeout = null
            failPermanent("服务器未返回会话状态，请重新打开会话")
        }, snapshotTimeoutMillis, TimeUnit.MILLISECONDS)
    }

    private fun handleSerial(text: String) {
        val event = runCatching { api.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val eventSession = event.string("session_id")
        if (eventSession.isNotBlank() && eventSession != sessionId) return
        when (event.string("type")) {
            "run_accepted" -> notifyMain { listener.onAccepted(event.string("run_id"), event.string("invocation_id")) }
            "run_rejected" -> notifyMain { listener.onRejected(event.string("invocation_id"), event.string("message").ifBlank { "请求未被接受" }) }
            "error" -> {
                val matching = controls.filter { (id, control) ->
                    event.string("session_id") == sessionId && event.string("run_id") == control.runId &&
                        (event.string("control_id").isBlank() || event.string("control_id") == id)
                }.keys.toList()
                if (matching.isNotEmpty() && event.string("invocation_id").isBlank()) matching.forEach { id ->
                    controls.remove(id)?.timer?.cancel(false)
                    notifyMain { listener.onControl(id, false, event.string("code").ifBlank { "control_failed" }) }
                } else notifyMain { listener.onRejected(event.string("invocation_id").takeIf { it.isNotBlank() }, event.string("message").ifBlank { "实时请求失败" }) }
            }
            "control_ack" -> {
                val id = event.string("control_id")
                val pending = controls[id] ?: return
                if (event.string("session_id") != sessionId || event.string("run_id") != pending.runId || event.string("control") != pending.type) return
                val applied = event["applied"]?.jsonPrimitive?.booleanOrNull ?: return
                controls.remove(id)?.timer?.cancel(false)
                notifyMain { listener.onControl(id, applied, event.string("code").takeIf { it.isNotBlank() }) }
            }
            "runtime_snapshot" -> {
                if (event.string("session_id") != sessionId) return
                val snapshot = runCatching { api.json.decodeFromJsonElement(RuntimeSnapshot.serializer(), event.getValue("snapshot")) }.getOrNull()
                if (snapshot == null) {
                    failPermanent("无法读取服务器会话状态，请重新打开会话或更新应用")
                    return
                }
                state = RuntimeReducer.snapshot(state, sessionId, event.string("epoch"), event.long("seq"), snapshot).copy(steerSupported = event["steer_supported"]?.jsonPrimitive?.booleanOrNull == true, steerQueueSupported = event["steer_queue_supported"]?.jsonPrimitive?.booleanOrNull == true)
                if (state.needsSnapshot) {
                    failPermanent("服务器会话状态不一致，请重新打开会话")
                    return
                }
                snapshotTimeout?.cancel(false); snapshotTimeout = null
                publishSerial()
            }
            "runtime_delta" -> {
                if (event.string("session_id") != sessionId) return
                val delta = runCatching { api.json.decodeFromJsonElement(RuntimeDelta.serializer(), event.getValue("delta")) }.getOrNull()
                state = if (delta == null) state.copy(needsSnapshot = true)
                    else RuntimeReducer.delta(state, sessionId, event.string("epoch"), event.long("seq"), delta)
                publishSerial(coalesce = delta != null && delta.run == null && delta.current_run_view == null &&
                    delta.message_upserts.isEmpty() && !delta.reset_messages)
            }
            "runtime_dropped" -> { state = state.copy(needsSnapshot = true); publishSerial() }
        }
    }

    private fun publishSerial(coalesce: Boolean = false) {
        if (state.needsSnapshot) {
            if (usable) { usable = false; notifyMain { listener.onConnection(false) } }
            subscribeSerial()
            return
        }
        if (coalesce && usable) {
            if (runtimePublication == null) runtimePublication = serial.schedule({
                runtimePublication = null
                publishSerial()
            }, 100, TimeUnit.MILLISECONDS)
            return
        }
        runtimePublication?.cancel(false); runtimePublication = null
        val captured = state // Never read the actor's mutable state from a delayed main callback.
        if (!usable) { usable = true; notifyMain { listener.onConnection(true) } }
        notifyMain { listener.onRuntime(captured) }
    }

    private fun detachSerial() {
        attempt++
        runtimePublication?.cancel(false); runtimePublication = null
        snapshotTimeout?.cancel(false); snapshotTimeout = null
        connecting = false
        socket = null
        usable = false
        controls.forEach { (id, control) -> control.timer.cancel(false); notifyMain { listener.onControl(id, false, "connection_lost_status_unknown") } }
        controls.clear()
        notifyMain { listener.onConnection(false) }
    }
    private fun reconnectSerial() {
        detachSerial()
        if (closed) return
        reconnect?.cancel(false)
        reconnect = serial.schedule({ reconnect = null; connectSerial(WebSocketAuthMode.HEADER) }, reconnectDelay, TimeUnit.MILLISECONDS)
        reconnectDelay = min((reconnectDelay * 1.5).toLong(), 10_000L)
    }
    private fun failPermanent(message: String) {
        socket?.cancel()
        detachSerial()
        reconnect?.cancel(false); reconnect = null
        notifyMain { listener.onRejected(null, message) }
    }
    private fun post(block: () -> Unit) { if (!closed) runCatching { serial.execute { if (!closed) block() } } }
    private fun notifyMain(block: () -> Unit) = dispatch { if (!closed) block() }
    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull ?: -1
}
