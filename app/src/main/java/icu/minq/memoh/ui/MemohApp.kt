package icu.minq.memoh.ui

import android.net.Uri
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import icu.minq.memoh.data.AppState
import icu.minq.memoh.data.Screen
import icu.minq.memoh.data.UiState
import icu.minq.memoh.data.reconciledHistory
import icu.minq.memoh.model.*
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemohApp(app: AppState, startVisibleSend: (() -> Unit) -> Unit) {
    val state by app.state.collectAsStateWithLifecycle()
    Scaffold(snackbarHost = { SnackbarHost(remember { SnackbarHostState() }) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state.screen) {
                Screen.Login -> LoginScreen(state, app::login)
                Screen.Bots -> BotScreen(state, app::selectBot, app::refreshBots, app::logout)
                Screen.Sessions -> SessionScreen(state, app::openSession, app::createSession, app::refreshSessions, app::back, app::logout)
                Screen.Chat -> ChatScreen(state, { text -> startVisibleSend { app.send(text) } }, app::stop, app::decide, app::back, app::logout)
            }
            if (state.loading) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .12f)).clickable { }, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error?.let { ErrorBanner(it, app::clearError, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

@Composable private fun LoginScreen(state: UiState, login: (String, String, String) -> Unit) {
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Forum, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp)); Text("登录 Memoh", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("请输入你的服务器和账户信息。服务器地址不会预置。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("服务器地址") }, placeholder = { Text("https://your-memoh.example") }, singleLine = true)
        Spacer(Modifier.height(10.dp)); OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("用户名") }, singleLine = true)
        Spacer(Modifier.height(10.dp)); OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Spacer(Modifier.height(18.dp)); Button({ login(server, username, password); password = "" }, enabled = server.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !state.loading, modifier = Modifier.fillMaxWidth()) { Text("登录") }
        Spacer(Modifier.height(12.dp)); Text("仅支持 HTTPS。密码只用于本次登录，不会保存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BotScreen(state: UiState, select: (Bot) -> Unit, refresh: () -> Unit, logout: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("机器人") }, actions = { IconButton(refresh) { Icon(Icons.Default.Refresh, "刷新") }; IconButton(logout) { Icon(Icons.AutoMirrored.Filled.Logout, "退出登录") } }) }) { padding ->
        ContentList(state.bots, state.loading, "还没有可用机器人", Modifier.padding(padding)) { bot ->
            ListItem(headlineContent = { Text(bot.displayName.ifBlank { bot.name.ifBlank { "未命名机器人" } }) }, supportingContent = { Text(if (bot.active) bot.status.ifBlank { "可用" } else "已停用") }, leadingContent = { Icon(Icons.Default.SmartToy, null) }, modifier = Modifier.clickable { select(bot) })
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SessionScreen(state: UiState, open: (Session) -> Unit, create: (String, String?) -> Unit, refresh: () -> Unit, back: () -> Unit, logout: () -> Unit) {
    var dialog by remember { mutableStateOf(false) }
    val canCreate = state.settingsAvailable && state.bot?.currentUserPermissions.orEmpty().any { it.equals("chat", true) || it.equals("manage", true) }
    Scaffold(topBar = { TopAppBar(title = { Text(state.bot?.displayName?.ifBlank { state.bot.name } ?: "会话") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }, actions = { IconButton(refresh) { Icon(Icons.Default.Refresh, "刷新") }; IconButton(logout) { Icon(Icons.AutoMirrored.Filled.Logout, "退出") } }) }, floatingActionButton = { if (canCreate) FloatingActionButton({ dialog = true }) { Icon(Icons.Default.Add, "新建会话") } }) { padding ->
        ContentList(state.sessions, state.loading, "暂无会话，点击 + 新建", Modifier.padding(padding)) { session ->
            ListItem(headlineContent = { Text(session.title.ifBlank { "新会话" }) }, supportingContent = { Text(listOf(session.runtimeType, session.updatedAt.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")) }, leadingContent = { Icon(Icons.Default.ChatBubbleOutline, null) }, modifier = Modifier.clickable { open(session) })
            HorizontalDivider()
        }
    }
    if (dialog) CreateSessionDialog(state.workdirs, state.workdirsAvailable, { dialog = false }, { title, workdir -> dialog = false; create(title, workdir) })
}

@Composable private fun CreateSessionDialog(workdirs: List<Workdir>, workdirsAvailable: Boolean, dismiss: () -> Unit, create: (String, String?) -> Unit) {
    var title by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var selected by remember(workdirs) { mutableStateOf(workdirs.singleOrNull()?.id) }
    val selectedName = workdirs.firstOrNull { it.id == selected }?.let { "${it.name}  ${it.path}" } ?: "不绑定工作目录"
    AlertDialog(onDismissRequest = dismiss, title = { Text("新建会话") }, text = {
        Column {
            OutlinedTextField(title, { title = it }, label = { Text("标题（可选）") }, singleLine = true)
            Spacer(Modifier.height(12.dp)); Text("工作目录", style = MaterialTheme.typography.labelLarge)
            Box { OutlinedButton({ expanded = true }, Modifier.fillMaxWidth(), enabled = workdirsAvailable) { Text(selectedName, maxLines = 1) }
                DropdownMenu(expanded, { expanded = false }) {
                    DropdownMenuItem({ Text("不绑定工作目录") }, { selected = null; expanded = false })
                    workdirs.forEach { w -> DropdownMenuItem({ Text("${w.name} · ${w.path}") }, { selected = w.id; expanded = false }) }
                }
            }
            if (!workdirsAvailable) Text("当前账号没有读取工作目录的权限；将创建未绑定会话。", style = MaterialTheme.typography.bodySmall)
            else if (workdirs.isEmpty()) Text("服务器未提供活动工作目录；仍可创建未绑定会话。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { Button({ create(title, selected) }) { Text("创建") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ChatScreen(state: UiState, send: (String) -> Unit, stop: () -> Unit, decide: (String, Boolean, String?) -> Unit, back: () -> Unit, logout: () -> Unit) {
    var draft by remember(state.session?.id) { mutableStateOf("") }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val live = state.runtime.run
    val settled = remember(state.history, live?.turn_id, live?.request_user_turn) { reconciledHistory(state.history, live) }
    val readOnly = state.session.isExternalChannel()
    val running = live != null && !live.isTerminal()
    val canSend = !readOnly && state.connected && state.connectionFailure == null && !running && state.pendingInvocationId == null
    val allCount = settled.size + if (live != null) 1 else 0
    LaunchedEffect(allCount, live?.messages) { if (allCount > 0) list.animateScrollToItem(allCount - 1) }
    Scaffold(topBar = { TopAppBar(title = { Column { Text(state.session?.title?.ifBlank { "新会话" } ?: "聊天"); Text(when { readOnly -> "外部渠道 · 只读"; state.connected -> "实时连接"; state.connectionFailure != null -> "连接不可用"; else -> "正在重连" }, style = MaterialTheme.typography.labelSmall, color = if (state.connected && !readOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) } }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }, actions = { IconButton(logout) { Icon(Icons.AutoMirrored.Filled.Logout, "退出") } }) }, bottomBar = {
        Surface(shadowElevation = 8.dp) { Row(Modifier.navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text(if (readOnly) "外部渠道会话仅供查看" else "发送消息") }, enabled = !readOnly, readOnly = readOnly, maxLines = 5)
            Spacer(Modifier.width(8.dp))
            if (running && !readOnly) IconButton(stop, enabled = state.connected) { Icon(Icons.Default.StopCircle, "停止生成", tint = MaterialTheme.colorScheme.error) }
            if (!readOnly) FilledIconButton({ val text = draft; if (text.isNotBlank()) { draft = ""; send(text); scope.launch { if (allCount > 0) list.animateScrollToItem(allCount - 1) } } }, enabled = draft.isNotBlank() && canSend) { Icon(Icons.AutoMirrored.Filled.Send, "发送") }
        } }
    }) { padding ->
        if (state.history.isEmpty() && live == null && !state.loading) EmptyState("开始一段新对话", Modifier.padding(padding))
        else LazyColumn(Modifier.padding(padding).fillMaxSize(), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(settled, key = { it.turnId + it.role }) { TurnCard(it, null, decide, readOnly) }
            live?.let { run -> item("live-${run.run_id}") {
                run.request_user_turn?.let { TurnCard(it, run, decide, readOnly) }
                AssistantBlocks(run.messages, run, decide, streaming = !run.isTerminal(), readOnly = readOnly)
                run.error?.let { ErrorCard(it) }
            } }
        }
    }
}

@Composable private fun TurnCard(turn: ChatTurn, run: RuntimeRun?, decide: (String, Boolean, String?) -> Unit, readOnly: Boolean) {
    if (turn.role == "user") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), modifier = Modifier.widthIn(max = 340.dp)) { Text(turn.text, Modifier.padding(14.dp)) } }
    else if (turn.role == "assistant") AssistantBlocks(turn.messages, run, decide, false, readOnly)
    else Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) { Text("系统消息", Modifier.padding(12.dp)) }
}

@Composable private fun AssistantBlocks(blocks: List<MessageBlock>, run: RuntimeRun?, decide: (String, Boolean, String?) -> Unit, streaming: Boolean, readOnly: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        blocks.forEach { block -> when (block.type) {
            "text" -> MarkdownText(block.content, streaming)
            "reasoning" -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(12.dp)) { Text("思考过程", fontWeight = FontWeight.Bold); Text(block.content) } }
            "tool" -> ToolCard(block, run, decide, readOnly)
            "error" -> ErrorCard(block.content)
            "notice" -> NoticeCard(block.content)
            else -> NoticeCard("未识别消息块：${block.type}\n${block.raw.toString().take(2000)}")
        } }
        if (streaming && blocks.isEmpty()) LinearProgressIndicator(Modifier.width(120.dp))
    }
}

@Composable private fun ToolCard(block: MessageBlock, run: RuntimeRun?, decide: (String, Boolean, String?) -> Unit, readOnly: Boolean) {
    Card { Column(Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Build, null); Spacer(Modifier.width(8.dp)); Text(block.name.ifBlank { "工具调用" }, fontWeight = FontWeight.Bold); if (block.running) { Spacer(Modifier.width(8.dp)); CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) } }
        block.input?.let { Text(it.toString().take(1000), style = MaterialTheme.typography.bodySmall) }
        block.output?.let { Text(it.toString().take(1000), style = MaterialTheme.typography.bodySmall) }
        block.progress.takeLast(3).forEach { Text(it.toString().take(500), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        block.approval?.takeIf { it.status == "pending" && it.canApprove && run != null }?.let { approval ->
            Text(if (readOnly) "该工具需要批准（外部渠道只读）" else "该工具需要批准", color = MaterialTheme.colorScheme.tertiary)
            if (!readOnly) ApprovalActions(approval, decide)
        }
        block.userInput?.takeIf { it.status == "pending" }?.let { input ->
            Text("等待用户输入（当前 Android 版本仅只读）", color = MaterialTheme.colorScheme.tertiary)
            input.questions.forEach { Text("• ${it.text}") }
        }
    } }
}

@Composable private fun ApprovalActions(approval: Approval, decide: (String, Boolean, String?) -> Unit) {
    if (approval.options.isEmpty()) {
        Row {
            Button({ decide(approval.approvalId, true, null) }) { Text("批准") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton({ decide(approval.approvalId, false, null) }) { Text("拒绝") }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        approval.options.forEach { option ->
            val reject = option.kind.startsWith("reject", ignoreCase = true)
            val label = option.name.ifBlank {
                when (option.kind.lowercase()) {
                    "allow_once" -> "仅本次允许"
                    "allow_always" -> "始终允许"
                    "reject_once" -> "仅本次拒绝"
                    "reject_always" -> "始终拒绝"
                    else -> option.kind.ifBlank { "选择" }
                }
            }
            if (reject) OutlinedButton({ decide(approval.approvalId, false, option.id) }) { Text(label) }
            else Button({ decide(approval.approvalId, true, option.id) }) { Text(label) }
        }
    }
}

@Composable private fun MarkdownText(markdown: String, streaming: Boolean) {
    if (streaming) Text(markdown)
    else {
        val context = LocalContext.current
        val markwon = remember { Markwon.create(context) }
        val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
        val safe = remember(markdown) { markdown.replace(Regex("""(?i)\]\((javascript|file|content|intent):[^)]*\)"""), "](已阻止的不安全链接)") }
        AndroidView(factory = { TextView(it).apply { textSize = 16f; setTextColor(textColor); movementMethod = LinkMovementMethod.getInstance() } }, update = { view ->
            markwon.setMarkdown(view, safe)
            (view.text as? Spannable)?.let { text ->
                text.getSpans(0, text.length, URLSpan::class.java).forEach { span ->
                    if (Uri.parse(span.url).scheme?.lowercase() !in setOf("https", "http")) text.removeSpan(span)
                }
            }
        })
    }
}

@Composable private fun ErrorCard(text: String) { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Row(Modifier.padding(12.dp)) { Icon(Icons.Default.ErrorOutline, null); Spacer(Modifier.width(8.dp)); Text(text.ifBlank { "发生错误" }) } } }
@Composable private fun NoticeCard(text: String) { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Text(text, Modifier.padding(12.dp)) } }

@Composable private fun <T> ContentList(values: List<T>, loading: Boolean, empty: String, modifier: Modifier = Modifier, row: @Composable (T) -> Unit) {
    if (values.isEmpty() && !loading) EmptyState(empty, modifier) else LazyColumn(modifier.fillMaxSize()) { items(values) { row(it) } }
}
@Composable private fun EmptyState(text: String, modifier: Modifier = Modifier) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Inbox, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline); Spacer(Modifier.height(8.dp)); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
@Composable private fun ErrorBanner(message: String, dismiss: () -> Unit, modifier: Modifier = Modifier) { Card(modifier.padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(message.take(500), Modifier.weight(1f)); IconButton(dismiss) { Icon(Icons.Default.Close, "关闭") } } } }
