package icu.minq.memoh.ui

import android.net.Uri
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import icu.minq.memoh.data.UiState
import icu.minq.memoh.data.reconciledHistory
import icu.minq.memoh.model.*
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable internal fun ChatScreen(state: UiState, actions: UiActions) {
    key(state.session?.id) {
        val live = state.runtime.run
        val settled = remember(state.history, live?.turn_id, live?.request_user_turn) { reconciledHistory(state.history, live) }
        val list = rememberLazyListState()
        val dragging by list.interactionSource.collectIsDraggedAsState()
        val scope = rememberCoroutineScope()
        var follow by remember { mutableStateOf(true) }
        val total = settled.size + if (live != null) 1 else 0
        val empty = total == 0 && !state.loading
        val running = live != null && !live.isTerminal()
        LaunchedEffect(dragging, list.canScrollForward) {
            if (dragging) follow = !list.canScrollForward
            else if (!list.canScrollForward) follow = true
        }
        LaunchedEffect(total, live?.messages, live?.status) {
            if (total > 0 && follow && !list.isScrollInProgress) list.animateScrollToItem(total - 1, Int.MAX_VALUE)
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
                    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        itemsIndexed(settled, key = { index, turn -> "${turn.id ?: turn.turnId}:${turn.role}:$index" }) { _, turn -> TurnView(turn, state, actions) }
                        live?.let { run -> item("live-${run.run_id}") {
                            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                run.request_user_turn?.let { TurnView(it, state, actions) }
                                AssistantMessage(run.messages, state, actions, running)
                                run.visibleError()?.let { error -> if (run.messages.none { it.type == "error" && it.content.trim() == error }) ErrorCard(error) }
                                if (run.status == "aborted") Text("已停止生成", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } }
                    }
                    if (list.canScrollForward && !follow) SmallFloatingActionButton({ follow = true; scope.launch { list.animateScrollToItem((total - 1).coerceAtLeast(0), Int.MAX_VALUE) } },
                        Modifier.align(Alignment.BottomCenter).padding(10.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Icon(Icons.Default.ArrowDownward, "回到最新消息", Modifier.size(20.dp))
                    }
                }
                Box(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { Composer(state, actions) }
            }
        }
    }
}

@Composable private fun Composer(state: UiState, actions: UiActions) {
    val readOnly = state.session.isExternalChannel()
    val running = state.runtime.run?.let { !it.isTerminal() } == true
    val canSend = !readOnly && state.connected && state.connectionFailure == null && !running && state.pending?.blocksSend != true && !state.sendInFlight && !state.loading && !state.composer.modelChanging && !state.composer.modelUncertain && state.attachments.all { it.payload != null }
    val dir = state.workdirs.firstOrNull { it.id == state.session?.workdirId }
    var models by remember { mutableStateOf(false) }
    var modelAnchorTop by remember { mutableIntStateOf(0) }
    var devices by remember { mutableStateOf(false) }
    Column {
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
                            if (state.draft.isBlank()) Text(if (readOnly) "外部渠道会话仅供查看" else "发送消息…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    if (running && !readOnly) FilledIconButton(actions.stop, enabled = state.connected && state.pendingControls.isEmpty(), modifier = Modifier.size(40.dp),
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
            running -> if (state.runtime.run.isWaitingApproval()) "等待你的批准" else "正在回复…"
            else -> dir?.name ?: if (state.session?.canSelectDevice() == true && state.composer.targetId.isNotBlank()) state.composer.targetLabel() else ""
        }
        if (hint.isNotBlank()) Text(hint, Modifier.padding(start = 8.dp, top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (devices) DevicePicker(state, actions) { devices = false }
}

@Composable private fun TurnView(turn: ChatTurn, state: UiState, actions: UiActions) {
    when (turn.role) {
        "user" -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MessageAttachments(turn.attachments + turn.messages.filter { it.type == "attachments" }.flatMap { it.attachments() })
            if (turn.text.isNotBlank()) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 620.dp)) {
                SelectionContainer { Text(turn.text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyLarge) }
            }
        }
        "assistant" -> AssistantMessage(turn.messages.ifEmpty { if (turn.text.isNotBlank()) listOf(MessageBlock(type = "text", content = turn.text)) else emptyList() }, state, actions, false, allowDecisions = false)
        else -> NoticeCard(turn.text.ifBlank { "系统消息" })
    }
}

@Composable private fun AssistantMessage(blocks: List<MessageBlock>, state: UiState, actions: UiActions, streaming: Boolean, allowDecisions: Boolean = true) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block -> key(block.id, block.type) {
            when (block.type) {
                "text" -> MarkdownText(block.content)
                "reasoning" -> CollapsibleDetail("思考过程", block.content, Icons.Default.AutoAwesome)
                "tool" -> ToolCard(block, state, actions, allowDecisions)
                "error" -> ErrorCard(block.content)
                "notice" -> if (block.content.isNotBlank()) NoticeCard(block.content)
                "attachments" -> MessageAttachments(block.attachments())
                else -> CollapsibleDetail("消息详情 · ${block.type}", block.raw.toString(), Icons.Default.Info)
            }
        } }
        if (streaming) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            Text(if (state.runtime.run.isWaitingApproval()) "等待批准" else "正在思考…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (blocks.any { it.type == "text" && it.content.isNotBlank() }) {
            IconButton({ clipboard.setText(AnnotatedString(blocks.filter { it.type == "text" }.joinToString("\n\n") { it.content })); copied = true }, Modifier.size(40.dp)) {
                Icon(if (copied) Icons.Default.Check else Icons.Default.ContentCopy, if (copied) "已复制回复" else "复制回复", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun CollapsibleDetail(title: String, text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ChevronRight, if (expanded) "收起详情" else "展开详情", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(10.dp)) {
            SelectionContainer { Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable private fun ToolCard(block: MessageBlock, state: UiState, actions: UiActions, allowDecisions: Boolean) {
    val approval = block.approval?.takeIf { it.status == "pending" && it.canApprove && allowDecisions }
    val canDecide = state.connected && state.pendingControls.isEmpty() && !state.session.isExternalChannel()
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Terminal, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(block.name.ifBlank { "工具调用" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (block.running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ChevronRight, if (expanded) "收起工具详情" else "展开工具详情", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.input?.let { Text("输入", style = MaterialTheme.typography.labelMedium); DetailText(it.toString()) }
                block.output?.let { Text("结果", style = MaterialTheme.typography.labelMedium); DetailText(it.toString()) }
                block.progress.takeLast(3).forEach { DetailText(it.toString()) }
            }
            if (approval != null) Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.pendingControls.isNotEmpty()) "正在确认你的选择…" else "需要你的批准", style = MaterialTheme.typography.bodyMedium)
                if (approval.options.isEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ actions.decide(approval.approvalId, true, null) }, enabled = canDecide) { Text("批准") }
                    OutlinedButton({ actions.decide(approval.approvalId, false, null) }, enabled = canDecide) { Text("拒绝") }
                } else approval.options.forEach { option ->
                    val reject = option.kind.startsWith("reject", true)
                    val label = option.name.ifBlank { when (option.kind) { "allow_once" -> "仅本次允许"; "allow_always" -> "始终允许"; "reject_once" -> "仅本次拒绝"; "reject_always" -> "始终拒绝"; else -> "选择" } }
                    OutlinedButton({ actions.decide(approval.approvalId, !reject, option.id) }, enabled = canDecide) { Text(label) }
                }
            }
            block.userInput?.takeIf { it.status == "pending" }?.let { input ->
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("请在 Memoh 网页端回答以下问题", style = MaterialTheme.typography.bodyMedium)
                    input.questions.forEach { Text(it.text, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable private fun DetailText(text: String) {
    SelectionContainer { Text(text.take(8000), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable private fun MarkdownText(markdown: String) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.create(context) }
    val ink = MaterialTheme.colorScheme.onSurface.toArgb()
    val link = MaterialTheme.colorScheme.primary.toArgb()
    val safe = remember(markdown) { markdown.replace(Regex("""(?i)\]\((javascript|file|content|intent):[^)]*\)"""), "](已阻止的不安全链接)") }
    AndroidView(modifier = Modifier.fillMaxWidth(), factory = { TextView(it).apply {
        textSize = 16f; setLineSpacing(0f, 1.45f); setTextIsSelectable(true); movementMethod = LinkMovementMethod.getInstance(); includeFontPadding = false
    } }, update = { view ->
        view.setTextColor(ink); view.setLinkTextColor(link)
        if (view.tag != safe) {
            markwon.setMarkdown(view, safe); view.tag = safe
            (view.text as? Spannable)?.let { text -> text.getSpans(0, text.length, URLSpan::class.java).forEach { span ->
                if (Uri.parse(span.url).scheme?.lowercase() !in setOf("https", "http")) text.removeSpan(span)
            } }
        }
    })
}
