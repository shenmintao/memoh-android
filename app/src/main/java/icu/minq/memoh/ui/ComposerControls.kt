package icu.minq.memoh.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import icu.minq.memoh.data.UiState
import icu.minq.memoh.model.*
import java.util.Locale

internal fun fileSizeLabel(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable internal fun AttachmentTray(state: UiState, actions: UiActions) {
    if (state.attachments.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.attachments, key = { it.id }) { file ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, if (file.error == null) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.error)) {
                    Row(Modifier.widthIn(max = 290.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (file.preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(if (file.payload?.type == "image") Icons.Default.Image else Icons.Default.Description, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.widthIn(max = 170.dp).padding(horizontal = 8.dp, vertical = 10.dp)) {
                            Text(file.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(file.error ?: if (file.preparing) "正在读取…" else fileSizeLabel(file.size), style = MaterialTheme.typography.bodySmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (file.error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                        }
                        if (file.error != null) IconButton({ actions.retryAttachment(file.id) }, enabled = !state.sendInFlight, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.Refresh, "重试附件 ${file.name}", Modifier.size(18.dp))
                        }
                        IconButton({ actions.removeAttachment(file.id) }, enabled = !state.sendInFlight, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.Close, "移除附件 ${file.name}", Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        Text("文件将随消息发送 · 最多 10 个，合计 8 MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun DevicePicker(state: UiState, actions: UiActions, dismiss: () -> Unit) {
    val config = state.composer
    val session = state.session
    val folder = state.workdirs.firstOrNull { it.id == session?.workdirId }
    val selectable = session?.canSelectDevice() == true
    val locked = state.loading || state.sendInFlight || config.targetsLoading || config.modelChanging || state.pending?.blocksSend == true || state.runtime.run?.let { !it.isTerminal() } == true
    AlertDialog(onDismissRequest = dismiss, modifier = Modifier.imePadding(), properties = DialogProperties(decorFitsSystemWindows = false), title = { Text("选择设备") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                !session?.workdirId.isNullOrBlank() -> {
                    Text("此会话已绑定工作目录，执行设备由该目录决定。")
                    Text(folder?.let { "${it.name}\n${it.path}" } ?: "工作目录信息暂不可用", style = MaterialTheme.typography.bodySmall)
                }
                session?.isAgentRuntime() == true -> Text("此会话的执行设备由 Agent 配置决定。")
                session.isExternalChannel() -> Text("外部渠道会话仅供查看。")
                else -> {
                    Text("选择本次对话使用的工作区", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (config.targetsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    config.targetsError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 340.dp)) {
                        items(config.targets, key = { it.targetId }) { target ->
                            PickerRow(target.label(), if (target.available()) "在线${if (target.primary) " · 默认" else ""}" else "离线",
                                target.targetId == config.targetId, !locked && target.available()) { actions.selectDevice(target.targetId) }
                        }
                        if (config.targets.isEmpty() && !config.targetsLoading) item { Text("暂无可选设备", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(dismiss) { Text("完成") } }, dismissButton = {
        if (selectable) TextButton(actions.refreshDevices, enabled = !config.targetsLoading) { Text("刷新") }
    })
}

@Composable private fun PickerRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = click).padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (enabled || selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Default.Check, "已选择 $title", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable internal fun MessageAttachments(files: List<ChatAttachment>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { file -> Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.widthIn(max = 320.dp).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (file.type == "image") Icons.Default.Image else Icons.Default.Description, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Text(file.name.ifBlank { if (file.type == "image") "图片" else "文件" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } }
    }
}
