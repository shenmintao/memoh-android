package icu.minq.memoh.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import icu.minq.memoh.BuildConfig
import icu.minq.memoh.data.*
import icu.minq.memoh.model.*
import kotlinx.coroutines.launch

internal enum class AppWindowWidthClass { Compact, Medium, Expanded }
internal fun classifyWindowWidth(widthDp: Float) = when {
    widthDp >= 768f -> AppWindowWidthClass.Expanded
    widthDp >= 600f -> AppWindowWidthClass.Medium
    else -> AppWindowWidthClass.Compact
}

internal data class UiActions(
    val login: (String, String, String, Boolean) -> Unit = { _, _, _, _ -> },
    val selectBot: (Bot) -> Unit = {}, val openSession: (Session) -> Unit = {},
    val createSession: (String, String?) -> Unit = { _, _ -> },
    val refreshBots: () -> Unit = {}, val refreshSessions: () -> Unit = {},
    val editDraft: (String) -> Unit = {}, val send: (String) -> Unit = {}, val stop: () -> Unit = {},
    val decide: (String, Boolean, String?, String) -> Unit = { _, _, _, _ -> },
    val showBots: () -> Unit = {}, val logout: () -> Unit = {}, val clearError: () -> Unit = {},
    val openPending: () -> Unit = {}, val resumePending: () -> Unit = {}, val acknowledgeUnknown: (String) -> Unit = {},
    val forgetLogin: () -> Unit = {},
    val chooseFiles: () -> Unit = {}, val removeAttachment: (String) -> Unit = {}, val retryAttachment: (String) -> Unit = {},
    val refreshModels: () -> Unit = {}, val selectModel: (String) -> Unit = {},
    val refreshDevices: () -> Unit = {}, val selectDevice: (String) -> Unit = {},
    val selectReasoning: (String) -> Unit = {},
    val answer: (String, List<UserAnswer>, Boolean) -> Unit = { _, _, _ -> },
)

@Composable fun MemohApp(app: AppState, startVisibleSend: (() -> Unit) -> Unit) {
    val state by app.state.collectAsStateWithLifecycle()
    val files = rememberLauncherForActivityResult(remember { OpenChatDocuments() }, app::attachFiles)
    MemohShell(state, UiActions(app::login, app::selectBot, app::openSession, app::createSession,
        app::refreshBots, app::refreshSessions, app::editDraft, { text -> startVisibleSend { app.send(text) } },
        app::stop, app::decide, app::showBots, app::logout, app::clearError, app::openPending, app::resumePending, app::acknowledgeUnknown, app::forgetLogin,
        chooseFiles = { if (app.beginFileSelection()) runCatching { files.launch(arrayOf("*/*")) }.onFailure { app.filePickerUnavailable() } },
        removeAttachment = app::removeAttachment, retryAttachment = app::retryAttachment,
        refreshModels = app::refreshModels, selectModel = app::selectModel, refreshDevices = app::refreshDevices, selectDevice = app::selectDevice, selectReasoning = app::selectReasoning, answer = app::answer))
}

private class OpenChatDocuments : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: android.content.Context, input: Array<String>): android.content.Intent =
        super.createIntent(context, input).addCategory(android.content.Intent.CATEGORY_OPENABLE)
}

/** Native composition of the official mobile bar + navigation sheet + chat reading plane. */
@Composable internal fun MemohShell(state: UiState, actions: UiActions = UiActions()) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var create by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var logout by remember { mutableStateOf(false) }
    val canCreate = state.settingsAvailable && state.bot?.currentUserPermissions.orEmpty().any { it.equals("chat", true) || it.equals("manage", true) }
    LaunchedEffect(state.screen, state.bot?.id, state.session?.id) { drawer.close(); create = false }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        val sidebar = classifyWindowWidth(maxWidth.value) == AppWindowWidthClass.Expanded
        val close: () -> Unit = { focus.clearFocus(); keyboard?.hide(); scope.launch { drawer.close() }; Unit }
        BackHandler(drawer.isOpen && !sidebar) { close() }
        if (state.screen == Screen.Login) LoginScreen(state, actions.login, actions.forgetLogin)
        else {
            val nav: @Composable () -> Unit = {
                WorkspaceNavigation(state, actions, { create = true; close() }, { settings = true; close() }, close)
            }
            val main: @Composable () -> Unit = {
                Column(Modifier.fillMaxSize().imePadding()) {
                    MobileTopBar(state, sidebar, { scope.launch { drawer.open() } }, { create = true }, canCreate, { settings = true })
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                    Box(Modifier.weight(1f)) {
                        when (state.screen) {
                            Screen.Bots -> BotScreen(state, actions.selectBot, actions.refreshBots)
                            Screen.Sessions -> SessionHome(state, { create = true }, actions.openSession, canCreate)
                            Screen.Chat -> ChatScreen(state, actions)
                            else -> Unit
                        }
                    }
                    PendingBanner(state, actions)
                }
            }
            if (sidebar) Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(288.dp).fillMaxHeight()) { nav() }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                Box(Modifier.weight(1f)) { main() }
            } else ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(288.dp), drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    drawerShape = androidx.compose.ui.graphics.RectangleShape, windowInsets = WindowInsets(0, 0, 0, 0)) { nav() }
            }) { main() }
        }
        state.error?.takeIf { it.isNotBlank() }?.let { error ->
            Surface(Modifier.align(Alignment.BottomCenter).widthIn(max = 720.dp).padding(12.dp), color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error.take(500), Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                    QuietIconButton(Icons.Default.Close, "关闭错误提示", actions.clearError)
                }
            }
        }
    }
    }
    if (create && canCreate) CreateSessionDialog(state, { create = false }, actions.createSession)
    if (settings) AlertDialog(onDismissRequest = { settings = false }, icon = { MemohLogo(44.dp) }, title = { Text("Memoh") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.user?.displayName?.ifBlank { state.user.username } ?: "账户", style = MaterialTheme.typography.titleMedium)
            Text("外观跟随系统设置", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Android ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton({ settings = false }) { Text("完成") } }, dismissButton = { TextButton({ settings = false; logout = true }) { Text("退出登录", color = MaterialTheme.colorScheme.error) } })
    if (logout) AlertDialog(onDismissRequest = { logout = false }, title = { Text("退出登录？") }, text = { Text("退出后需要重新连接服务器。当前未发送的草稿将被清除。") },
        confirmButton = { TextButton({ logout = false; actions.logout() }) { Text("退出登录") } }, dismissButton = { TextButton({ logout = false }) { Text("取消") } })
}

@Composable private fun MobileTopBar(state: UiState, sidebar: Boolean, openNav: () -> Unit, create: () -> Unit, canCreate: Boolean, settings: () -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!sidebar) QuietIconButton(Icons.Default.Menu, "打开导航", openNav) else Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { MemohLogo(24.dp) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when (state.screen) { Screen.Bots -> "Memoh"; Screen.Chat -> state.session?.title?.ifBlank { "新会话" } ?: "新会话"; else -> state.bot?.label() ?: "Memoh" },
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.screen == Screen.Chat) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    StatusDot(state.connected)
                    Text(when { state.session.isExternalChannel() -> "外部渠道 · 只读"; state.connected -> state.bot?.label().orEmpty(); state.connectionFailure != null -> "连接不可用"; else -> "正在连接…" },
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (canCreate) QuietIconButton(Icons.Default.Add, "新建会话", create)
            else QuietIconButton(Icons.Default.Settings, "设置", settings)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
    }
}

@Composable private fun CreateSessionDialog(state: UiState, dismiss: () -> Unit, create: (String, String?) -> Unit) {
    var title by remember { mutableStateOf("") }
    var selected by remember(state.workdirs) { mutableStateOf(state.workdirs.singleOrNull()?.id) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("新建会话") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("标题（可选）") }, singleLine = true)
            Text("工作目录", style = MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton({ expanded = true }, Modifier.fillMaxWidth(), enabled = state.workdirsAvailable) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(state.workdirs.firstOrNull { it.id == selected }?.name ?: "不绑定工作目录", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded, { expanded = false }) {
                    DropdownMenuItem({ Text("不绑定工作目录") }, { selected = null; expanded = false })
                    state.workdirs.forEach { dir -> DropdownMenuItem({ Column { Text(dir.name); Text(dir.path, style = MaterialTheme.typography.bodySmall) } }, { selected = dir.id; expanded = false }) }
                }
            }
            state.workdirs.firstOrNull { it.id == selected }?.let { Text(it.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!state.workdirsAvailable) Text("当前账户无法读取工作目录，将创建未绑定会话。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { Button({ create(title, selected); dismiss() }) { Text("创建会话") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}

@Composable private fun PendingBanner(state: UiState, actions: UiActions) {
    val pending = state.pending ?: return
    var dismissId by remember { mutableStateOf<String?>(null) }
    // An already visible successful reply does not need an extra status banner.
    val visible = state.screen == Screen.Chat && state.session?.id == pending.sessionId && state.bot?.id == pending.botId
    if (pending.phase == PendingPhase.UNKNOWN || (pending.terminal && !visible)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(if (pending.terminal) "上次任务状态已更新" else "执行结果尚未确认，后台监听已暂停", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(actions.openPending) { Text("查看会话") }
                    if (!pending.terminal) TextButton(actions.resumePending) { Text("继续监听") }
                    TextButton({ dismissId = pending.invocationId }) { Text("解除等待") }
                }
            }
        }
    }
    dismissId?.let { id -> AlertDialog(onDismissRequest = { dismissId = null }, title = { Text("解除本地等待？") }, text = { Text("这不会停止远程任务。请先核对会话结果，避免重复执行。") },
        confirmButton = { TextButton({ actions.acknowledgeUnknown(id); dismissId = null }) { Text("已核对，解除") } }, dismissButton = { TextButton({ dismissId = null }) { Text("取消") } }) }
}
