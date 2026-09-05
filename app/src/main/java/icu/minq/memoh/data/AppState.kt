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
import icu.minq.memoh.security.RememberedLogin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val pending: PendingOperation? = null,
    val pendingControls: Set<String> = emptySet(),
    val draft: String = "",
    val sendInFlight: Boolean = false,
    val rememberedLogin: RememberedLogin? = null,
)

class AppState(application: Application, private val container: AppContainer, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(UiState(pending = container.pendingStore.read(), rememberedLogin = container.loginStore?.read()))
    val state = mutable.asStateFlow()
    private var chatSocket: ChatSocket? = null
    private var recoverySocket: ChatSocket? = null
    private var recoveryJob: Job? = null
    private var loadJob: Job? = null
    private var historyJob: Job? = null
    private var navigationGeneration = 0L
    private var authGeneration = 0L
    private var bootstrapped = false
    private var terminalReconciliationKey: String? = null
    private val controls = mutableMapOf<String, String>()
    private val drafts = DraftStore()
    private var accountKey = ""

    init {
        viewModelScope.launch {
            container.pendingStore.changes.collect { pending ->
                mutable.value = mutable.value.copy(pending = pending)
            }
        }
        viewModelScope.launch {
            container.api.authChanges.collect {
                if (mutable.value.user != null && container.api.currentAuth() == null) resetLocalSession("登录已失效，请重新登录")
            }
        }
    }

    fun login(server: String, username: String, password: String, rememberLogin: Boolean = false) = launchLoad { generation, auth ->
        if (!rememberLogin) container.loginStore?.clear()
        val user = container.api.login(server, username, password)
        if (!isCurrent(generation, auth)) return@launchLoad
        if (rememberLogin) container.loginStore?.write(RememberedLogin(server.trim(), username.trim(), password))
        val bots = container.api.bots()
        if (!isCurrent(generation, auth)) return@launchLoad
        installUser(user, bots)
        recoverPending()
    }

    fun forgetLogin() {
        try {
            container.loginStore?.clear()
            mutable.value = mutable.value.copy(rememberedLogin = null)
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(error = "无法清除已记住的登录信息，请重试")
        }
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
            installUser(user, bots)
            recoverPending()
            if (targetBot.isNotBlank()) {
                val bot = bots.firstOrNull { it.id == targetBot } ?: return@launchLoad
                val resources = loadBotResources(bot)
                if (!isCurrent(generation, auth)) return@launchLoad
                commitBot(bot, resources)
                if (targetSession.isNotBlank()) {
                    val session = resources.sessions.firstOrNull { it.id == targetSession } ?: container.api.session(bot.id, targetSession)
                    openSessionNow(bot, session, generation, auth)
                }
            }
        }
    }

    private fun installUser(user: CurrentUser, bots: List<Bot>) {
        container.api.rememberAccount(user.id)
        accountKey = PendingPolicy.accountKey(container.api.currentAuth()!!.apiBase, user.id)
        mutable.value = mutable.value.copy(user = user, bots = bots, screen = Screen.Bots)
    }

    /** A launch only reconciles IDs. It never repeats a prompt, even after a process death. */
    private fun recoverPending() {
        recoverySocket?.close(); recoverySocket = null; recoveryJob?.cancel()
        val pending = container.pendingStore.read() ?: return
        if (pending.accountKey != accountKey) {
            container.pendingStore.clearIfMatches(pending)
            mutable.value = mutable.value.copy(error = "旧待处理标识已解除；上一条执行结果请查看原会话，不会自动重发")
            return
        }
        if (pending.terminal || PendingReplyService.isMonitoring(pending)) return
        container.pendingStore.update(pending) { it.copy(phase = PendingPhase.UNKNOWN) }
        val expectedAuth = authGeneration
        lateinit var monitor: ChatSocket
        monitor = ChatSocket(container.api, pending.botId, pending.sessionId, object : ChatSocketListener {
            override fun onRuntime(state: RuntimeState) {
                if (recoverySocket !== monitor || expectedAuth != authGeneration || !pending.sameIdentity(container.pendingStore.read())) return
                container.pendingStore.observe(accountKey, pending.botId, pending.sessionId, state.run)
                monitor.close(); recoverySocket = null
                recoveryJob?.cancel()
            }
            override fun onRejected(invocationId: String?, message: String) {
                monitor.close()
                if (recoverySocket === monitor) recoverySocket = null
            }
        })
        recoverySocket = monitor
        monitor.connect()
        recoveryJob = viewModelScope.launch { delay(10_000); monitor.close(); if (recoverySocket === monitor) recoverySocket = null }
    }

    fun openPending() {
        container.pendingStore.read()?.takeIf { it.accountKey == accountKey }?.let { bootstrap(it.botId, it.sessionId) }
    }

    fun resumePending() {
        val pending = container.pendingStore.read()?.takeIf { it.accountKey == accountKey && it.phase == PendingPhase.UNKNOWN } ?: return
        recoverySocket?.close(); recoverySocket = null
        container.pendingStore.update(pending) { it.copy(createdAt = System.currentTimeMillis(), phase = if (it.runId.isBlank()) PendingPhase.WAITING else PendingPhase.ACCEPTED) }
        startMonitor(pending)
    }

    /** Explicit acknowledgement: clears only local uncertainty, never aborts or re-sends server work. */
    fun acknowledgeUnknown(invocationId: String) {
        val pending = container.pendingStore.read()?.takeIf { it.invocationId == invocationId && (it.phase == PendingPhase.UNKNOWN || it.terminal) } ?: return
        recoverySocket?.close(); recoverySocket = null
        container.pendingStore.clearIfMatches(pending)
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
        if (isCurrent(generation, auth)) mutable.value = mutable.value.copy(settings = resources.settings,
            settingsAvailable = resources.settingsAvailable, workdirs = resources.workdirs,
            workdirsAvailable = resources.workdirsAvailable, sessions = resources.sessions)
    }
    fun createSession(title: String, workdirId: String?) = launchLoad { generation, auth ->
        val value = mutable.value
        val bot = value.bot ?: return@launchLoad
        if (!value.settingsAvailable || !bot.hasPermission("chat")) throw IllegalStateException("没有创建会话所需的聊天/设置权限")
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
        historyJob?.cancel(); terminalReconciliationKey = null
        controls.clear()
        mutable.value = mutable.value.copy(screen = Screen.Chat, session = session, history = history,
            runtime = RuntimeState(sessionId = session.id), connected = false, connectionFailure = null,
            pendingControls = emptySet(), draft = drafts.get(draftKey(bot.id, session.id)))
        savedState[KEY_BOT] = bot.id; savedState[KEY_SESSION] = session.id
        lateinit var candidate: ChatSocket
        candidate = ChatSocket(container.api, bot.id, session.id, object : ChatSocketListener {
            private fun active() = chatSocket === candidate && authGeneration == auth && mutable.value.session?.id == session.id
            override fun onRuntime(state: RuntimeState) {
                if (!active()) return
                mutable.value = mutable.value.copy(runtime = state)
                container.pendingStore.observe(accountKey, bot.id, session.id, state.run)
                if (!state.run.isTerminal()) {
                    terminalReconciliationKey = null; historyJob?.cancel()
                } else {
                    val key = runtimeIdentity(state)
                    if (terminalReconciliationKey != key) {
                        terminalReconciliationKey = key
                        historyJob?.cancel()
                        historyJob = refreshHistoryAfterTerminal(bot.id, session.id, auth, key)
                    }
                }
            }
            override fun onAccepted(runId: String, invocationId: String) {
                if (!active()) return
                val pending = container.pendingStore.read()?.takeIf { it.invocationId == invocationId && it.accountKey == accountKey } ?: return
                container.pendingStore.update(pending) { if (it.terminal) it else it.copy(runId = runId, phase = if (it.phase == PendingPhase.UNKNOWN) it.phase else PendingPhase.ACCEPTED) }
            }
            override fun onRejected(invocationId: String?, message: String) {
                if (!active()) return
                if (invocationId != null) {
                    container.pendingStore.read()?.takeIf { it.invocationId == invocationId }?.let { pending ->
                        container.pendingStore.update(pending) { it.copy(phase = PendingPhase.FAILED) }
                    }
                } else if (container.api.currentAuth() == null) {
                    resetLocalSession("登录已失效，请重新登录"); return
                } else mutable.value = mutable.value.copy(connectionFailure = message, connected = false)
                mutable.value = mutable.value.copy(error = message)
            }
            override fun onControl(controlId: String, applied: Boolean, code: String?) {
                if (!active()) return
                val description = controls.remove(controlId) ?: return
                mutable.value = mutable.value.copy(pendingControls = controls.keys.toSet())
                if (!applied) mutable.value = mutable.value.copy(error = "$description 未确认，请查看最新状态${code?.let { "（$it）" }.orEmpty()}")
            }
            override fun onConnection(connected: Boolean) {
                if (active()) mutable.value = mutable.value.copy(connected = connected, connectionFailure = if (connected) null else mutable.value.connectionFailure)
            }
        })
        chatSocket = candidate
        candidate.connect()
    }

    private fun draftKey(botId: String, sessionId: String) = "$accountKey:$botId:$sessionId"
    fun editDraft(text: String) {
        val value = mutable.value
        val bot = value.bot ?: return
        val session = value.session ?: return
        val bounded = text.take(100_000)
        drafts.put(draftKey(bot.id, session.id), bounded)
        mutable.value = value.copy(draft = bounded)
    }

    fun send(text: String) {
        val value = mutable.value
        val bot = value.bot ?: return
        val session = value.session ?: return
        val socket = chatSocket
        if (text.isBlank()) return
        val blocked = when {
            value.sendInFlight -> "消息正在排队"
            session.isExternalChannel() -> "外部渠道会话仅供只读查看"
            !value.connected || socket == null || !socket.isUsable() -> "实时连接尚未就绪，无法发送"
            value.runtime.run?.let { !it.isTerminal() } == true -> "当前回复尚未结束"
            container.pendingStore.read()?.blocksSend == true -> "上一条消息状态待核对，请查看上方提示"
            else -> null
        }
        if (blocked != null) { mutable.value = mutable.value.copy(error = blocked); return }
        val pending = PendingOperation(bot.id, session.id, UUID.randomUUID().toString(), accountKey, System.currentTimeMillis())
        try {
            if (!container.pendingStore.start(pending)) return
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(error = "无法保存待处理记录，消息未发送，草稿已保留"); return
        }
        mutable.value = mutable.value.copy(sendInFlight = true)
        if (!startMonitor(pending)) {
            container.pendingStore.clearIfMatches(pending)
            mutable.value = mutable.value.copy(sendInFlight = false)
            return
        }
        val auth = authGeneration
        val key = draftKey(bot.id, session.id)
        socket!!.sendMessage(text.trim(), pending.invocationId) { queued ->
            if (auth != authGeneration) return@sendMessage
            if (queued) drafts.queued(key, text)
            else container.pendingStore.update(pending) { it.copy(phase = PendingPhase.FAILED) }
            val visible = mutable.value.bot?.id == bot.id && mutable.value.session?.id == session.id
            mutable.value = mutable.value.copy(sendInFlight = false,
                draft = if (visible) drafts.get(key) else mutable.value.draft,
                error = if (!queued) "消息未发送：连接已断开，草稿已保留" else mutable.value.error)
        }
    }

    private fun startMonitor(pending: PendingOperation): Boolean = try {
        ContextCompat.startForegroundService(getApplication(), PendingReplyService.intent(getApplication(), pending))
        true
    } catch (_: Exception) {
        container.pendingStore.update(pending) { it.copy(phase = PendingPhase.UNKNOWN) }
        mutable.value = mutable.value.copy(error = "无法启动后台监控，请保持应用可见；不会自动重发消息")
        false
    }

    fun stop() {
        if (mutable.value.session.isExternalChannel()) return
        val id = mutable.value.runtime.run?.run_id ?: return
        val controlId = chatSocket?.abort(id)
        if (controlId == null) mutable.value = mutable.value.copy(error = "实时连接已断开") else trackControl(controlId, "停止请求")
    }
    fun decide(approvalId: String, approve: Boolean, optionId: String?) {
        if (mutable.value.session.isExternalChannel() || mutable.value.pendingControls.isNotEmpty()) return
        val run = mutable.value.runtime.run ?: return
        val controlId = chatSocket?.approve(run.run_id, approvalId, approve, optionId)
        if (controlId == null) mutable.value = mutable.value.copy(error = "实时连接已断开") else trackControl(controlId, if (approve) "批准请求" else "拒绝请求")
    }
    private fun trackControl(controlId: String, description: String) {
        controls[controlId] = description
        mutable.value = mutable.value.copy(pendingControls = controls.keys.toSet())
    }

    fun back() {
        navigationGeneration++
        loadJob?.cancel(); historyJob?.cancel()
        mutable.value = mutable.value.copy(loading = false)
        when (mutable.value.screen) {
            Screen.Chat -> {
                chatSocket?.close(); chatSocket = null
                savedState[KEY_SESSION] = ""
                controls.clear()
                mutable.value = mutable.value.copy(screen = Screen.Sessions, session = null, history = emptyList(), runtime = RuntimeState(), connected = false, connectionFailure = null, pendingControls = emptySet(), draft = "")
            }
            Screen.Sessions -> {
                savedState[KEY_BOT] = ""; savedState[KEY_SESSION] = ""
                mutable.value = mutable.value.copy(screen = Screen.Bots, bot = null, sessions = emptyList(), workdirs = emptyList())
            }
            else -> Unit
        }
    }

    fun showBots() {
        if (mutable.value.screen == Screen.Chat) back()
        if (mutable.value.screen == Screen.Sessions) back()
    }

    fun logout() { container.api.clearAuth(); resetLocalSession(null) }
    private fun resetLocalSession(error: String?) {
        authGeneration++; navigationGeneration++
        loadJob?.cancel(); historyJob?.cancel(); recoveryJob?.cancel()
        chatSocket?.close(); chatSocket = null
        recoverySocket?.close(); recoverySocket = null
        getApplication<Application>().stopService(android.content.Intent(getApplication(), PendingReplyService::class.java))
        container.pendingStore.clear()
        drafts.clear(); controls.clear(); accountKey = ""
        savedState[KEY_BOT] = ""; savedState[KEY_SESSION] = ""
        mutable.value = UiState(error = error, rememberedLogin = container.loginStore?.read())
        bootstrapped = true
    }
    fun clearError() { mutable.value = mutable.value.copy(error = null) }
    override fun onCleared() {
        navigationGeneration++
        chatSocket?.close(); recoverySocket?.close()
        super.onCleared()
    }

    private data class BotResources(val sessions: List<Session>, val settings: BotSettings, val settingsAvailable: Boolean, val workdirs: List<Workdir>, val workdirsAvailable: Boolean)
    private suspend fun loadBotResources(bot: Bot): BotResources {
        val sessions = container.api.sessions(bot.id)
        val settings = optionalResource(bot.hasPermission("chat"), BotSettings()) { container.api.settings(bot.id) }
        val workdirs = optionalResource(bot.hasPermission("workspace_read"), emptyList()) { container.api.workdirs(bot.id) }
        return BotResources(sessions, settings.first, settings.second, workdirs.first, workdirs.second)
    }
    private suspend fun <T> optionalResource(allowed: Boolean, fallback: T, load: suspend () -> T): Pair<T, Boolean> {
        if (!allowed) return fallback to false
        return try { load() to true } catch (failure: ApiException) { if (failure.status == 403) fallback to false else throw failure }
    }
    private fun commitBot(bot: Bot, resources: BotResources) {
        chatSocket?.close(); chatSocket = null
        historyJob?.cancel(); terminalReconciliationKey = null; controls.clear()
        savedState[KEY_BOT] = bot.id; savedState[KEY_SESSION] = ""
        mutable.value = mutable.value.copy(screen = Screen.Sessions, bot = bot, settings = resources.settings,
            settingsAvailable = resources.settingsAvailable, workdirs = resources.workdirs,
            workdirsAvailable = resources.workdirsAvailable, sessions = resources.sessions, session = null,
            history = emptyList(), runtime = RuntimeState(), connected = false, connectionFailure = null,
            pendingControls = emptySet(), draft = "")
    }
    private fun refreshHistoryAfterTerminal(botId: String, sessionId: String, auth: Long, key: String) = viewModelScope.launch {
        try {
            val history = container.api.history(botId, sessionId)
            val current = mutable.value
            if (auth == authGeneration && current.session?.id == sessionId && terminalReconciliationKey == key && runtimeIdentity(current.runtime) == key) {
                // Keep the terminal projection until matching history exists. Never overwrite a newer run.
                mutable.value = current.copy(history = history)
            }
        } catch (_: CancellationException) { throw CancellationException() }
        catch (_: Exception) { /* The authoritative runtime remains visible; reopening reloads history. */ }
    }
    private fun launchLoad(block: suspend (generation: Long, auth: Long) -> Unit) {
        val generation = ++navigationGeneration
        val auth = authGeneration
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true, error = null)
            try { block(generation, auth) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (isCurrent(generation, auth)) {
                    if (container.api.currentAuth() == null && mutable.value.user != null) resetLocalSession("登录已失效，请重新登录")
                    else mutable.value = mutable.value.copy(error = failure.message ?: "请求失败")
                }
            } finally {
                if (isCurrent(generation, auth)) mutable.value = mutable.value.copy(loading = false)
            }
        }
    }
    private fun isCurrent(generation: Long, auth: Long) = navigationGeneration == generation && authGeneration == auth
    private fun Bot.hasPermission(permission: String) = currentUserPermissions.any { it.equals("manage", true) || it.equals(permission, true) }
    companion object {
        private const val KEY_BOT = "selected_bot_id"
        private const val KEY_SESSION = "selected_session_id"
    }
}

internal fun runtimeIdentity(state: RuntimeState) = listOf(state.sessionId, state.epoch, state.seq.toString(), state.run?.run_id.orEmpty(), state.run?.status.orEmpty()).joinToString(":")
