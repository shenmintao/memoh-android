package icu.minq.memoh.network

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import icu.minq.memoh.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import kotlin.math.min

interface ChatSocketListener {
    fun onRuntime(state: RuntimeState) {}
    fun onAccepted(runId: String, invocationId: String) {}
    fun onRejected(invocationId: String?, message: String) {}
    fun onControl(controlId: String, applied: Boolean, code: String?) {}
    fun onConnection(connected: Boolean) {}
}

/** Owns all mutable WebSocket/protocol state on one HandlerThread. */
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

class ChatSocket(
    private val api: MemohApi,
    private val botId: String,
    private val sessionId: String,
    private val listener: ChatSocketListener,
) {
    private val thread = HandlerThread("memoh-chat-$sessionId").apply { start() }
    private val serial = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: WebSocket? = null
    private var closed = false
    private var reconnectDelay = 1_000L
    private var state = RuntimeState(sessionId = sessionId)
    private val reliable = linkedMapOf<String, String>()
    @Volatile private var usable = false

    fun connect() = serial.post { connectSerial(WebSocketAuthMode.HEADER, forceRefresh = false, rejectedAccessToken = null) }

    fun isUsable(): Boolean = usable

    fun sendMessage(text: String, invocationId: String = UUID.randomUUID().toString(), onQueued: (Boolean) -> Unit = {}) {
        val payload = buildJsonObject {
            put("type", "message"); put("invocation_id", invocationId); put("session_id", sessionId); put("text", text)
            putJsonArray("attachments") {}
        }.toString()
        serial.post {
            if (closed || !usable || socket == null) {
                postMain { onQueued(false) }
                return@post
            }
            reliable["invocation:$invocationId"] = payload
            val sent = socket?.send(payload) == true
            if (!sent) reliable.remove("invocation:$invocationId")
            postMain { onQueued(sent) }
        }
    }

    fun abort(runId: String): String? = control("abort", runId) { }

    fun approve(runId: String, approvalId: String, approve: Boolean, optionId: String? = null): String? =
        control("tool_approval_response", runId) {
            put("decision_id", approvalId)
            put("decision", if (approve) "approve" else "reject")
            optionId?.takeIf { it.isNotBlank() }?.let { put("option_id", it) }
        }

    private fun control(type: String, runId: String, extras: JsonObjectBuilder.() -> Unit): String? {
        if (!usable || closed) return null
        val controlId = UUID.randomUUID().toString()
        val payload = buildJsonObject {
            put("type", type); put("run_id", runId); put("session_id", sessionId); put("control_id", controlId); extras()
        }.toString()
        serial.post {
            if (closed || !usable) return@post
            reliable["control:$controlId"] = payload
            socket?.send(payload)
        }
        return controlId
    }

    fun close() {
        usable = false
        serial.post {
            if (closed) return@post
            closed = true
            serial.removeCallbacksAndMessages(null)
            socket?.close(1000, "screen closed")
            socket = null
            reliable.clear()
            authScope.cancel()
            thread.quitSafely()
        }
    }

    private fun connectSerial(mode: WebSocketAuthMode, forceRefresh: Boolean, rejectedAccessToken: String?) {
        if (closed) return
        authScope.launch {
            val auth = runCatching { api.freshAuth(forceRefresh, rejectedAccessToken) }.getOrElse {
                postMain { listener.onConnection(false); listener.onRejected(null, "登录已失效，请重新登录") }
                return@launch
            }
            serial.post {
                if (closed) return@post
                // Prefer a header so credentials stay out of URLs/server request logs. The query
                // form is a single compatibility fallback only after an explicit header-handshake 401.
                val request = WebSocketHandshake.request(auth.apiBase, botId, auth.accessToken, mode)
                lateinit var candidate: WebSocket
                candidate = api.client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serial.post {
                            if (closed || socket !== candidate) { webSocket.close(1000, "stale"); return@post }
                            reconnectDelay = 1_000L
                            usable = true
                            postMain { listener.onConnection(true) }
                            subscribeSerial()
                            reliable.values.toList().forEach(webSocket::send)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        serial.post { if (!closed && socket === candidate) handleSerial(text) }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        serial.post { if (socket === candidate) disconnectedSerial(transient = true) }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        serial.post {
                            if (socket !== candidate || closed) return@post
                            val status = response?.code
                            val fallback = if (status == 401) WebSocketHandshake.fallbackAfter401(mode) else null
                            when {
                                fallback != null -> {
                                    socket = null
                                    usable = false
                                    postMain { listener.onConnection(false) }
                                    connectSerial(fallback, forceRefresh = true, rejectedAccessToken = auth.accessToken)
                                }
                                status == 401 -> {
                                    socket = null
                                    usable = false
                                    api.clearAuthIfToken(auth.accessToken)
                                    postMain {
                                        listener.onConnection(false)
                                        listener.onRejected(null, "实时连接认证失败，请重新登录")
                                    }
                                }
                                status != null && status in 400..499 -> {
                                    socket = null
                                    usable = false
                                    postMain {
                                        listener.onConnection(false)
                                        listener.onRejected(null, "实时连接被服务器拒绝（HTTP $status），请检查会话和机器人权限")
                                    }
                                }
                                else -> disconnectedSerial(transient = true)
                            }
                        }
                    }
                })
                socket = candidate
            }
        }
    }

    private fun subscribeSerial() {
        socket?.send(buildJsonObject {
            put("type", "runtime_subscribe"); put("session_id", sessionId)
            if (state.epoch.isNotBlank() && !state.needsSnapshot) putJsonObject("cursor") { put("epoch", state.epoch); put("seq", state.seq) }
        }.toString())
    }

    private fun handleSerial(text: String) {
        val event = runCatching { api.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (event["type"]?.jsonPrimitive?.contentOrNull) {
            "run_accepted" -> {
                val invocation = event.string("invocation_id")
                reliable.remove("invocation:$invocation")
                postMain { listener.onAccepted(event.string("run_id"), invocation) }
            }
            "run_rejected" -> {
                val invocation = event.string("invocation_id")
                reliable.remove("invocation:$invocation")
                postMain { listener.onRejected(invocation, event.string("message").ifBlank { "请求未被接受" }) }
            }
            "error" -> {
                val invocation = event["invocation_id"]?.jsonPrimitive?.contentOrNull
                invocation?.let { reliable.remove("invocation:$it") }
                postMain { listener.onRejected(invocation, event.string("message").ifBlank { "实时请求失败" }) }
            }
            "control_ack" -> {
                val id = event.string("control_id")
                reliable.remove("control:$id")
                postMain { listener.onControl(id, event["applied"]?.jsonPrimitive?.booleanOrNull == true, event["code"]?.jsonPrimitive?.contentOrNull) }
            }
            "runtime_snapshot" -> {
                val snap = runCatching { api.json.decodeFromJsonElement(RuntimeSnapshot.serializer(), event.getValue("snapshot")) }.getOrNull() ?: return
                state = RuntimeReducer.snapshot(state, event.string("session_id"), event.string("epoch"), event.long("seq"), snap)
                postMain { listener.onRuntime(state) }
                if (state.needsSnapshot) subscribeSerial()
            }
            "runtime_delta" -> {
                val delta = runCatching { api.json.decodeFromJsonElement(RuntimeDelta.serializer(), event.getValue("delta")) }.getOrNull() ?: return
                state = RuntimeReducer.delta(state, event.string("session_id"), event.string("epoch"), event.long("seq"), delta)
                postMain { listener.onRuntime(state) }
                if (state.needsSnapshot) subscribeSerial()
            }
            "runtime_dropped" -> { state = state.copy(needsSnapshot = true); subscribeSerial() }
        }
    }

    private fun disconnectedSerial(transient: Boolean) {
        usable = false
        socket = null
        postMain { listener.onConnection(false) }
        if (closed || !transient) return
        serial.postDelayed({ connectSerial(WebSocketAuthMode.HEADER, forceRefresh = false, rejectedAccessToken = null) }, reconnectDelay)
        reconnectDelay = min((reconnectDelay * 1.5).toLong(), 10_000L)
    }

    private fun postMain(block: () -> Unit) = main.post(block)
    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull ?: -1
}
