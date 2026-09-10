package icu.minq.memoh.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import icu.minq.memoh.data.UiState
import icu.minq.memoh.data.supplements
import icu.minq.memoh.data.reconciledHistory
import icu.minq.memoh.data.displayedRun
import icu.minq.memoh.data.pendingDecisions
import icu.minq.memoh.model.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlin.math.roundToInt

@Composable internal fun ChatScreen(state: UiState, actions: UiActions) {
    CompositionLocalProvider(LocalImageBot provides state.bot?.id.orEmpty()) {
    key(state.bot?.id, state.session?.id) {
        var expandedTool by rememberSaveable { mutableStateOf<String?>(null) }
        var follow by remember { mutableStateOf(true) }
        val pauseFollow = { follow = false }
        val toggleTool: (String) -> Unit = { id -> follow = false; expandedTool = if (expandedTool == id) null else id }
        val live = remember(state.history, state.runtime.run) { displayedRun(state.history, state.runtime.run) }
        val settled = remember(state.history, live?.turn_id, live?.status, live?.messages?.isNotEmpty(), live?.request_user_turn) { reconciledHistory(state.history, live) }
        val terminal = state.runtime.run?.takeIf { live == null }
        val rows = remember(settled, live, state.steeringQueue, state.steering, terminal) { chatRows(settled, live, state.supplements(), terminal) }
        // Start at the tail without composing/measuring all earlier tool steps.
        val list = rememberLazyListState(initialFirstVisibleItemIndex = rows.size)
        val scope = rememberCoroutineScope()
        val empty = rows.isEmpty() && !state.loading
        LaunchedEffect(list) {
            var released: Job? = null
            list.interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> { released?.cancel(); follow = false }
                    is DragInteraction.Stop -> released = launch {
                        withFrameNanos { }
                        snapshotFlow { list.isScrollInProgress }.first { !it }
                        if (!list.canScrollForward) follow = true
                    }
                }
            }
        }
        LaunchedEffect(list, follow) {
            if (follow) snapshotFlow { list.layoutInfo }.collect { layout ->
                // Layout, including asynchronous Markdown, owns tail growth. Never
                // restart an animation per token or take over a reader's gesture.
                if (layout.totalItemsCount > 0 && !list.isScrollInProgress && list.canScrollForward) {
                    val tail = layout.visibleItemsInfo.lastOrNull()?.takeIf { it.index == layout.totalItemsCount - 1 }
                    if (tail == null) list.scrollToItem(layout.totalItemsCount - 1)
                    else {
                        val growth = tail.offset + tail.size + layout.afterContentPadding - layout.viewportEndOffset
                        if (growth > 0) list.scrollBy(growth.toFloat())
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (empty) {
                Box(Modifier.weight(1f).widthIn(max = 704.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp)) {
                        Text("我们从哪里开始？", Modifier.padding(start = 16.dp, bottom = 28.dp), style = MaterialTheme.typography.headlineMedium)
                        Composer(state, actions)
                    }
                }
            } else {
                Box(Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth()) {
                    LazyColumn(Modifier.fillMaxSize().testTag("chat-timeline"), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(rows, key = { it.key }, contentType = { it.contentType }) { row ->
                            when (row) {
                                is ChatRow.Turn -> TurnView(row.turn, pauseFollow)
                                is ChatRow.Block -> AssistantBlock(row.block, state, row.streaming, row.turnKey, expandedTool, toggleTool, pauseFollow)
                                is ChatRow.Copy -> CopyReply(row.blocks)
                                is ChatRow.Status -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                                    Text(row.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                is ChatRow.Error -> ErrorCard(row.error)
                                is ChatRow.Stopped -> Text("已停止生成", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item("chat-bottom", contentType = "bottom") { Spacer(Modifier.fillMaxWidth().height(1.dp).testTag("chat-bottom")) }
                    }
                    if (list.canScrollForward && !follow) SmallFloatingActionButton({ scope.launch { list.scrollToItem(rows.size); follow = true } },
                        Modifier.align(Alignment.BottomCenter).padding(10.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Icon(Icons.Default.ArrowDownward, "回到最新消息", Modifier.size(20.dp))
                    }
                }
                Box(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { Composer(state, actions) }
            }
        }
    }
}
}

@Composable private fun Composer(state: UiState, actions: UiActions) {
    val readOnly = state.session.isExternalChannel()
    val decisions = state.pendingDecisions()
    if (!readOnly && decisions.isNotEmpty()) {
        DecisionPanel(state, actions, decisions)
        return
    }
    val running = state.runtime.run?.let { !it.isTerminal() } == true
    val canSend = !readOnly && state.connected && state.connectionFailure == null && !running && state.pending?.blocksSend != true && !state.sendInFlight && !state.loading && !state.composer.modelsLoading && !state.composer.modelChanging && !state.composer.modelUncertain && state.attachments.all { it.payload != null }
    val supplementMode = running && !readOnly && (state.draft.isNotBlank() || state.attachments.isNotEmpty())
    val canSteer = state.draft.isNotBlank() && state.runtime.steerSupported && state.connected && state.connectionFailure == null &&
        state.runtime.run?.status == "running" && !state.runtime.needsSnapshot && state.attachments.isEmpty() &&
        (state.runtime.steerQueueSupported || (state.supplements().none { it.inFlight } && state.runtime.run?.steer?.status !in setOf("pending", "queued")))
    val dir = state.workdirs.firstOrNull { it.id == state.session?.workdirId }
    var models by remember { mutableStateOf(false) }
    var modelAnchorTop by remember { mutableIntStateOf(0) }
    var devices by remember { mutableStateOf(false) }
    Column {
        val supplements = state.supplements().filter { it.status != "applied" }
        if (supplements.isNotEmpty()) Column {
            if (supplements.size > 1) Text("补充队列 · ${supplements.count { it.inFlight }} 条待处理", style = MaterialTheme.typography.labelMedium, modifier=Modifier.padding(bottom=4.dp))
            Column(Modifier.heightIn(max=184.dp).verticalScroll(rememberScrollState())) {
                supplements.forEach { supplement ->
                    key(supplement.id) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(supplement.label(), style = MaterialTheme.typography.labelMedium)
                                Text(supplement.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                if (supplement.status in setOf("rejected", "unknown", "applied")) Row {
                                    if (supplement.status != "applied") TextButton({ actions.recoverSteering(supplement.id) }) { Text("复制到输入框") }
                                    if (!supplement.inFlight || (!state.runtime.needsSnapshot && (state.runtime.run?.run_id != supplement.runId || state.runtime.run.isTerminal()))) TextButton({ actions.dismissSteering(supplement.id) }) { Text("关闭") }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (supplementMode) Text(when {
            state.attachments.isNotEmpty() -> "运行中仅支持文字补充，附件请在下一条消息发送"
            !state.runtime.steerSupported -> "服务器未开启运行中补充"
            else -> "补充到当前回复"
        }, Modifier.padding(start = 8.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceBright,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                AttachmentTray(state, actions)
                BasicTextField(state.draft, actions.editDraft, Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 6.dp).semantics { contentDescription = "消息输入框" },
                    enabled = !readOnly, readOnly = readOnly, maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { field ->
                        Box {
                            if (state.draft.isBlank()) Text(if (readOnly) "外部渠道会话仅供查看" else if (running) "输入补充要求…" else "发送消息…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            field()
                        }
                    })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(actions.chooseFiles, enabled = !readOnly && !state.sendInFlight, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Add, "添加文件", Modifier.size(22.dp))
                    }
                    IconButton({ devices = true; actions.refreshDevices() }, enabled = !readOnly, modifier = Modifier.size(44.dp)) {
                        Icon(if (state.session?.workdirId.isNullOrBlank()) Icons.Default.Computer else Icons.Default.FolderOpen,
                            "选择设备：${if (state.session?.workdirId.isNullOrBlank()) state.composer.targetLabel() else dir?.name ?: "已绑定工作目录"}", Modifier.size(20.dp))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { Box(Modifier.onGloballyPositioned { modelAnchorTop = it.boundsInWindow().top.roundToInt() }) {
                    Surface(onClick = { models = true; actions.refreshModels() }, enabled = !readOnly, modifier = Modifier.widthIn(max = 240.dp).semantics { contentDescription = "选择模型" }, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(state.composer.composerLabel(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (state.composer.modelChanging || state.composer.modelsLoading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                            else Icon(Icons.Default.ExpandMore, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (models) ModelPicker(state, actions, modelAnchorTop) { models = false }
                    } }
                    Spacer(Modifier.width(8.dp))
                    if (supplementMode) FilledIconButton(actions.steer, enabled = canSteer, modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface)) {
                        Icon(Icons.Default.ArrowUpward, "提交补充内容", Modifier.size(20.dp))
                    } else if (running && !readOnly) FilledIconButton(actions.stop, enabled = state.connected && state.pendingControls.isEmpty(), modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface)) {
                        Icon(Icons.Default.Stop, "停止生成", Modifier.size(20.dp))
                    } else FilledIconButton({ if (canSend && (state.draft.isNotBlank() || state.attachments.isNotEmpty())) actions.send(state.draft) }, enabled = canSend && (state.draft.isNotBlank() || state.attachments.isNotEmpty()), modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, disabledContentColor = MaterialTheme.colorScheme.outline)) {
                        Icon(Icons.Default.ArrowUpward, "发送", Modifier.size(20.dp))
                    }
                }
            }
        }
        val hint = when {
            readOnly -> "在原渠道中回复此会话"
            state.sendInFlight -> if (state.attachments.isNotEmpty()) "正在提交消息和附件…" else "正在提交消息…"
            state.composer.modelChanging -> "正在更新模型设置…"
            state.composer.modelUncertain -> "模型设置未确认，请打开模型选择器重试"
            state.attachments.any { it.preparing } -> "正在读取文件…"
            state.connectionFailure != null -> "连接不可用，请重新打开会话"
            !state.connected -> "正在连接服务器…"
            state.pending?.blocksSend == true && !running -> "正在确认上一条消息的状态…"
            running -> state.runtime.run.waitingLabel()
            else -> dir?.name ?: if (state.session?.canSelectDevice() == true && state.composer.targetId.isNotBlank()) state.composer.targetLabel() else ""
        }
        if (hint.isNotBlank()) Text(hint, Modifier.padding(start = 8.dp, top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (devices) DevicePicker(state, actions) { devices = false }
}

@Composable private fun TurnView(turn: ChatTurn, onInteract: () -> Unit = {}) {
    when (turn.role) {
        "user" -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val files = remember(turn.attachments, turn.messages) { turn.attachments + turn.messages.filter { it.type == "attachments" }.flatMap { it.attachments() } }
            MessageAttachments(files, onInteract)
            if (turn.text.isNotBlank()) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 620.dp)) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { LongPlainText(turn.text) }
            }
        }
        else -> NoticeCard(turn.text.ifBlank { "系统消息" })
    }
}

@Composable private fun AssistantBlock(block: MessageBlock, state: UiState, streaming: Boolean, turnKey: String, expandedTool: String?, toggleTool: (String) -> Unit, pauseFollow: () -> Unit) {
    when (block.type) {
        "text" -> MarkdownText(block.content, streaming, pauseFollow)
        "user_message" -> TurnView(ChatTurn(turnId = turnKey, role = "user", text = block.content), pauseFollow)
        "reasoning" -> CollapsibleDetail("思考过程", block.content, Icons.Default.AutoAwesome, pauseFollow)
        "tool" -> {
            val toolKey = "${turnKey.length}:$turnKey:${block.toolCallId.ifBlank { "block:${block.id}" }}"
            ToolCard(block, state, expandedTool == toolKey) { toggleTool(toolKey) }
        }
        "error" -> ErrorCard(block.content)
        "notice" -> if (block.content.isNotBlank()) NoticeCard(block.content)
        "attachments" -> MessageAttachments(remember(block.raw) { block.attachments() }, pauseFollow)
        else -> {
            var expanded by rememberSaveable { mutableStateOf(false) }
            TextButton({ pauseFollow(); expanded = !expanded }) { Text("消息详情 · ${block.type}") }
            if (expanded) JsonDetail(block.raw)
        }
    }
}

@Composable private fun CopyReply(blocks: List<MessageBlock>) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    IconButton({ clipboard.setText(AnnotatedString(blocks.filter { it.type == "text" }.joinToString("\n\n") { it.content })); copied = true }, Modifier.size(40.dp)) {
        Icon(if (copied) Icons.Default.Check else Icons.Default.ContentCopy, if (copied) "已复制回复" else "复制回复", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun CollapsibleDetail(title: String, text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, pauseFollow: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().clickable { pauseFollow(); expanded = !expanded }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ChevronRight, if (expanded) "收起详情" else "展开详情", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(10.dp)) {
            Box(Modifier.padding(12.dp)) { LongPlainText(text) }
        }
    }
}

@Composable private fun ToolCard(block: MessageBlock, state: UiState, expanded: Boolean, toggle: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Terminal, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(block.name.ifBlank { "工具调用" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (block.running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ChevronRight, if (expanded) "收起工具详情" else "展开工具详情", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.input?.let { Text("输入", style = MaterialTheme.typography.labelMedium); JsonDetail(it) }
                block.output?.let { Text("结果", style = MaterialTheme.typography.labelMedium); JsonDetail(it) }
                block.progress.takeLast(3).forEach { JsonDetail(it) }
            }
            block.approval?.let { approval ->
                Text(when (approval.status) {
                    "pending" -> if (state.pendingDecisions().any { it.approval?.approvalId == approval.approvalId }) "批准请求见下方输入区" else "此批准请求已不可操作"
                    "approved" -> "已批准"; "rejected" -> "已拒绝"; "resolved" -> "请求已处理"; else -> "批准请求已结束"
                }, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
            block.userInput?.let { input ->
                Text(when (input.status) {
                    "pending" -> if (state.pendingDecisions().any { it.userInput?.userInputId == input.userInputId }) "请在下方输入区回答" else "此回答请求已不可操作"
                    "submitted" -> "已提交回答"; "canceled" -> "已取消回答"; else -> "回答请求已结束"
                }, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun JsonDetail(content: JsonElement) {
    val text by produceState<String?>(null, content) {
        value = withContext(Dispatchers.Default) { content.toString() }
    }
    text?.let { LongPlainText(it, monospace = true) } ?: Text("正在加载详情…", style = MaterialTheme.typography.bodySmall)
}
