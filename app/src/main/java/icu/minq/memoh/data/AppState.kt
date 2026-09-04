package icu.minq.memoh.data

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import icu.minq.memoh.AppContainer
import icu.minq.memoh.model.*
import icu.minq.memoh.network.ApiException
import icu.minq.memoh.network.ChatSocket
import icu.minq.memoh.network.ChatSocketListener
import icu.minq.memoh.service.PendingReplyService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface Screen { data object Login : Screen; data object Bots : Screen; data object Sessions : Screen; data object Chat : Screen }
data class UiState(
    val screen: Screen = Screen.Login,
    val loading: Boolean = false,
    val error: String? = null,
    val user: CurrentUser? = null,
    val bots: List<Bot> = emptyList(),
    val bot: Bot? = null,
    val sessions: List<Session> = emptyList(),
    val session: Session? = null,
    val workdirs: List<Workdir> = emptyList(),
    val settings: BotSettings = BotSettings(),
    val settingsAvailable: Boolean = false,
    val workdirsAvailable: Boolean = false,
    val history: List<ChatTurn> = emptyList(),
    val runtime: RuntimeState = RuntimeState(),
    val connected: Boolean = false,
    val connectionFailure: String? = null,
    val pendingInvocationId: String? = null,
    val pendingControls: Set<String> = emptySet(),
)

class AppState(
    application: Application,
    private val container: AppContainer,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(UiState(pendingInvocationId = container.pendingStore.read()?.invocationId))
    val state = mutable.asStateFlow()
    private var chatSocket: ChatSocket? = null
    private var loadJob: Job? = null
    private var navigationGeneration = 0L
    private var authGeneration = 0L
    private var bootstrapped = false
    private var terminalReconciliationKey: String? = null
    private val controls = mutableMapOf<String, String>()

    fun login(server: String, username: String, password: String) = launchLoad { generation, auth ->
        val user = container.api.login(server, username, password)
        if (!isCurrent(generation, auth)) return@launchLoad
        val bots = container.api.bots()
        if (!isCurrent(generation, auth)) return@launchLoad
        mutable.value = mutable.value.copy(user = user, bots = bots, screen = Screen.Bots)
    }

    fun bootstrap(deepLinkBotId: String = "", deepLinkSessionId: String = "") {
        val targetBot = deepLinkBotId.ifBlank { savedState.get<String>(KEY_BOT).orEmpty() }
        val targetSession = deepLinkSessionId.ifBlank { savedState.get<String>(KEY_SESSION).orEmpty() }
        if (bootstrapped && deepLinkBotId.isBlank() && deepLinkSessionId.isBlank()) return
        bootstrapped = true
        if (container.api.currentAuth() == null) return
        launchLoad { generation, auth ->
            val user = container.api.me()
            val bots = container.api.bots()
            if (!isCurrent(generation, auth)) return@launchLoad
            mutable.value = mutable.value.copy(user = user, bots = bots, screen = Screen.Bots)
            if (targetBot.isNotBlank()) {
                val bot = bots.firstOrNull { it.id == targetBot } ?: return@launchLoad
                val resources = loadBotResources(bot)
                if (!isCurrent(generation, auth)) return@launchLoad
                commitBot(bot, resources)
                if (targetSession.isNotBlank()) {
                    val session = resources.sessions.firstOrNull { it.id == targetSession }
                        ?: container.api.session(bot.id, targetSession)
                    openSessionNow(bot, session, generation, auth)
                }
            }
        }
    }

    fun refreshBots() = launchLoad { generation, auth ->
        val bots = container.api.bots()
        if (isCurrent(generation, auth)) mutable.value = mutable.value.copy(bots = bots)
    }

    fun selectBot(bot: Bot) = launchLoad { generation, auth ->
        val resources = loadBotResources(bot)
        if (isCurrent(generation, auth)) commitBot(bot, resources)
    }

    fun refreshSessions() = launchLoad { generation, auth ->
        val bot = mutable.value.bot ?: return@launchLoad
        val resources = loadBotResources(bot)
        if (isCurrent(generation, auth)) commitBot(bot, resources)
    }

    fun createSession(title: String, workdirId: String?) = launchLoad { generation, auth ->
        val value = mutable.value
        val bot = value.bot ?: return@launchLoad
        if (!value.settingsAvailable || !bot.canChat()) throw IllegalStateException("没有创建会话所需的聊天/设置权限")
        val created = container.api.createSession(bot.id, title.trim(), workdirId, value.settings)
        if (!isCurrent(generation, auth)) return@launchLoad
        mutable.value = mutable.value.copy(sessions = listOf(created) + mutable.value.sessions)
        openSessionNow(bot, created, generation, auth)
    }

    fun openSession(session: Session) = launchLoad { generation, auth ->
        val bot = mutable.value.bot ?: return@launchLoad
        openSessionNow(bot, session, generation, auth)
    }

    private suspend fun openSessionNow(bot: Bot, session: Session, generation: Long, auth: Long) {
        val history = container.api.history(bot.id, session.id)
        if (!isCurrent(generation, auth)) return
        chatSocket?.close()
        terminalReconciliationKey = null
        controls.clear()
        mutable.value = mutable.value.copy(
            screen = Screen.Chat,
            session = session,
            history = history,
            runtime = RuntimeState(sessionId = session.id),
            connected = false,
            connectionFailure = null,
            pendingControls = emptySet(),
            pendingInvocationId = container.pendingStore.read()?.invocationId,
        )
        savedState[KEY_BOT] = bot.id
        savedState[KEY_SESSION] = session.id

        lateinit var candidate: ChatSocket
        candidate = ChatSocket(container.api, bot.id, session.id, object : ChatSocketListener {
            private fun active() = chatSocket === candidate && isCurrent(generation, auth) && mutable.value.session?.id == session.id

            override fun onRuntime(state: RuntimeState) {
                if (!active()) return
                mutable.value = mutable.value.copy(runtime = state)
                val run = state.run
                val pendingInvocation = container.pendingStore.read()?.invocationId
                if (run.isTerminal() && pendingInvocation != null && pendingInvocation == run?.invocation_id) {
                    // The service owns final notification/clear; release only the visible composer gate.
                    mutable.value = mutable.value.copy(pendingInvocationId = null)
                }
                if (run.isTerminal()) {
                    val key = listOf(session.id, state.epoch, state.seq.toString(), run?.run_id.orEmpty(), run?.status.orEmpty()).joinToString(":")
                    if (terminalReconciliationKey != key) {
                        terminalReconciliationKey = key
                        refreshHistoryAfterTerminal(bot.id, session.id, generation, auth, key)
                    }
                }
            }

            override fun onAccepted(runId: String, invocationId: String) {
                if (active() && container.pendingStore.read()?.invocationId == invocationId) {
                    mutable.value = mutable.value.copy(pendingInvocationId = invocationId)
                }
            }

            override fun onRejected(invocationId: String?, message: String) {
                if (!active()) return
                if (invocationId != null) {
                    PendingReplyService.cancelIfMatching(getApplication(), invocationId)
                    if (mutable.value.pendingInvocationId == invocationId) mutable.value = mutable.value.copy(pendingInvocationId = null)
                } else if (container.api.currentAuth() == null) {
                    authGeneration++
                    navigationGeneration++
                    candidate.close()
                    chatSocket = null
                    savedState[KEY_BOT] = ""; savedState[KEY_SESSION] = ""
                    mutable.value = UiState(error = "登录已失效，请重新登录")
                    return
                } else {
                    mutable.value = mutable.value.copy(connectionFailure = message, connected = false)
                }
                mutable.value = mutable.value.copy(error = message)
            }

            override fun onControl(controlId: String, applied: Boolean, code: String?) {
                if (!active()) return
                val description = controls.remove(controlId) ?: return
                mutable.value = mutable.value.copy(pendingControls = controls.keys)
                if (!applied) mutable.value = mutable.value.copy(error = "$description 未生效${code?.let { "（$it）" }.orEmpty()}")
            }

            override fun onConnection(connected: Boolean) {
                if (active()) mutable.value = mutable.value.copy(connected = connected, connectionFailure = if (connected) null else mutable.value.connectionFailure)
            }
        })
        chatSocket = candidate
        candidate.connect()
    }

    fun send(text: String) {
        val value = mutable.value
        val bot = value.bot ?: return
        val session = value.session ?: return
        val socket = chatSocket
        val blocked = when {
            text.isBlank() -> null
            session.isExternalChannel() -> "外部渠道会话仅供只读查看"
            !value.connected || socket == null || !socket.isUsable() -> "实时连接尚未就绪，无法发送"
            value.runtime.run?.let { !it.isTerminal() } == true -> "当前回复尚未结束"
            container.pendingStore.read() != null -> "已有一条消息正在等待处理"
            else -> null
        }
        if (blocked != null) { mutable.value = mutable.value.copy(error = blocked); return }

        val invocation = UUID.randomUUID().toString()
        container.pendingStore.write(PendingOperation(bot.id, session.id, invocation))
        mutable.value = mutable.value.copy(pendingInvocationId = invocation)
        val started = runCatching {
            ContextCompat.startForegroundService(getApplication(), PendingReplyService.intent(getApplication(), bot.id, session.id, invocation))
        }
        if (started.isFailure) {
            container.pendingStore.clearIfMatches(invocation)
            mutable.value = mutable.value.copy(pendingInvocationId = null, error = "无法启动后台回复监控，请保持应用在前台后重试")
            return
        }
        socket!!.sendMessage(text.trim(), invocation) { queued ->
            if (!queued) {
                PendingReplyService.cancelIfMatching(getApplication(), invocation)
                if (mutable.value.pendingInvocationId == invocation) {
                    mutable.value = mutable.value.copy(pendingInvocationId = null, error = "消息未发送：实时连接已断开")
                }
            }
        }
    }

    fun stop() {
        val id = mutable.value.runtime.run?.run_id ?: return
        val controlId = chatSocket?.abort(id)
        if (controlId == null) mutable.value = mutable.value.copy(error = "实时连接已断开")
        else trackControl(controlId, "停止请求")
    }

    fun decide(approvalId: String, approve: Boolean, optionId: String?) {
        if (mutable.value.session.isExternalChannel()) { mutable.value = mutable.value.copy(error = "外部渠道会话仅供只读查看"); return }
        val run = mutable.value.runtime.run ?: return
        val controlId = chatSocket?.approve(run.run_id, approvalId, approve, optionId)
        if (controlId == null) mutable.value = mutable.value.copy(error = "实时连接已断开")
        else trackControl(controlId, if (approve) "批准请求" else "拒绝请求")
    }

    private fun trackControl(controlId: String, description: String) {
        controls[controlId] = description
        mutable.value = mutable.value.copy(pendingControls = controls.keys)
    }

    fun back() {
        navigationGeneration++
        loadJob?.cancel()
        when (mutable.value.screen) {
            Screen.Chat -> {
                chatSocket?.close(); chatSocket = null
                savedState[KEY_SESSION] = ""
                mutable.value = mutable.value.copy(screen = Screen.Sessions, session = null, history = emptyList(), runtime = RuntimeState(), connected = false, connectionFailure = null)
            }
            Screen.Sessions -> {
                savedState[KEY_BOT] = ""; savedState[KEY_SESSION] = ""
                mutable.value = mutable.value.copy(screen = Screen.Bots, bot = null, sessions = emptyList(), workdirs = emptyList())
            }
            else -> Unit
        }
    }

    fun logout() {
        authGeneration++
        navigationGeneration++
        loadJob?.cancel()
        chatSocket?.close(); chatSocket = null
        getApplication<Application>().stopService(PendingReplyService.intent(getApplication(), "", "", ""))
        container.pendingStore.clear()
        container.api.clearAuth()
        controls.clear()
        savedState[KEY_BOT] = ""; savedState[KEY_SESSION] = ""
        mutable.value = UiState()
        bootstrapped = true
    }

    fun clearError() { mutable.value = mutable.value.copy(error = null) }

    override fun onCleared() {
        navigationGeneration++
        loadJob?.cancel()
        chatSocket?.close()
        chatSocket = null
        super.onCleared()
    }

    private data class BotResources(
        val sessions: List<Session>,
        val settings: BotSettings,
        val settingsAvailable: Boolean,
        val workdirs: List<Workdir>,
        val workdirsAvailable: Boolean,
    )

    private suspend fun loadBotResources(bot: Bot): BotResources {
        val sessions = container.api.sessions(bot.id)
        val settingsAllowed = bot.hasPermission("chat")
        val workdirsAllowed = bot.hasPermission("workspace_read")
        val settings = optionalResource(settingsAllowed, BotSettings()) { container.api.settings(bot.id) }
        val workdirs = optionalResource(workdirsAllowed, emptyList()) { container.api.workdirs(bot.id) }
        return BotResources(sessions, settings.first, settings.second, workdirs.first, workdirs.second)
    }

    private suspend fun <T> optionalResource(allowed: Boolean, fallback: T, load: suspend () -> T): Pair<T, Boolean> {
        if (!allowed) return fallback to false
        return try { load() to true } catch (failure: ApiException) {
            if (failure.status == 403) fallback to false else throw failure
        }
    }

    private fun commitBot(bot: Bot, resources: BotResources) {
        savedState[KEY_BOT] = bot.id
        savedState[KEY_SESSION] = ""
        mutable.value = mutable.value.copy(
            screen = Screen.Sessions,
            bot = bot,
            settings = resources.settings,
            settingsAvailable = resources.settingsAvailable,
            workdirs = resources.workdirs,
            workdirsAvailable = resources.workdirsAvailable,
            sessions = resources.sessions,
        )
    }

    private fun refreshHistoryAfterTerminal(botId: String, sessionId: String, generation: Long, auth: Long, key: String) = viewModelScope.launch {
        runCatching { container.api.history(botId, sessionId) }.onSuccess {
            if (isCurrent(generation, auth) && terminalReconciliationKey == key && mutable.value.session?.id == sessionId) {
                mutable.value = mutable.value.copy(history = it, runtime = RuntimeState(sessionId = sessionId))
            }
        }
    }

    private fun launchLoad(block: suspend (generation: Long, auth: Long) -> Unit) {
        val generation = ++navigationGeneration
        val auth = authGeneration
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true, error = null)
            try {
                block(generation, auth)
            } catch (failure: Exception) {
                if (!isCurrent(generation, auth)) return@launch
                mutable.value = mutable.value.copy(error = failure.message ?: "请求失败")
                if (container.api.currentAuth() == null) {
                    authGeneration++
                    chatSocket?.close(); chatSocket = null
                    mutable.value = UiState(error = "登录已失效，请重新登录")
                }
            } finally {
                if (isCurrent(generation, auth)) mutable.value = mutable.value.copy(loading = false)
            }
        }
    }

    private fun isCurrent(generation: Long, auth: Long) = navigationGeneration == generation && authGeneration == auth
    private fun Bot.hasPermission(permission: String) = currentUserPermissions.any { it.equals("manage", true) || it.equals(permission, true) }
    private fun Bot.canChat() = hasPermission("chat")

    companion object {
        private const val KEY_BOT = "selected_bot_id"
        private const val KEY_SESSION = "selected_session_id"
    }
}
