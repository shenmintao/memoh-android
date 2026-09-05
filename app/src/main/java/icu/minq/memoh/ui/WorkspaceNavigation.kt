package icu.minq.memoh.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import icu.minq.memoh.data.Screen
import icu.minq.memoh.data.UiState
import icu.minq.memoh.model.*

@Composable internal fun WorkspaceNavigation(state: UiState, actions: UiActions, create: () -> Unit, settings: () -> Unit, close: () -> Unit) {
    var switcher by remember { mutableStateOf(false) }
    var search by rememberSaveable(state.bot?.id) { mutableStateOf("") }
    val canCreate = state.settingsAvailable && state.bot?.currentUserPermissions.orEmpty().any { it.equals("chat", true) || it.equals("manage", true) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp)) {
        Box {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { switcher = true }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.bot == null) MemohLogo(30.dp) else BotAvatar(state.bot, 30.dp)
                Text(state.bot?.label() ?: "Memoh", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.UnfoldMore, "切换机器人", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(switcher, { switcher = false }) {
                state.bots.forEach { bot -> DropdownMenuItem({ Text(bot.label(), maxLines = 1) }, {
                    switcher = false; if (bot.id != state.bot?.id) actions.selectBot(bot); close()
                }, leadingIcon = { BotAvatar(bot, 26.dp) }, trailingIcon = { if (bot.id == state.bot?.id) Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) }
                HorizontalDivider()
                DropdownMenuItem({ Text("所有机器人") }, { switcher = false; actions.showBots(); close() }, leadingIcon = { Icon(Icons.Default.SmartToy, null, Modifier.size(18.dp)) })
            }
        }
        val sessions = state.sessions.filter { search.isBlank() || it.title.contains(search, true) }
        // The whole navigation body scrolls: search and actions remain reachable in short windows.
        LazyColumn(Modifier.weight(1f).testTag("navigation-list"), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item("navigation") {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(18.dp))
                        Text("会话", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (state.bot != null) {
                if (canCreate) item("new") {
                    TextButton(create, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("新建会话", Modifier.weight(1f))
                    }
                }
                item("search") { SearchField(search, { search = it }, "搜索会话") }
                item("recent") {
                    Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("最近会话", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        QuietIconButton(Icons.Default.Refresh, "刷新会话", actions.refreshSessions, !state.loading)
                    }
                }
                if (sessions.isEmpty()) item("empty") { Text(if (search.isBlank()) "暂无会话" else "没有找到匹配的会话", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                items(sessions, key = { "session:${it.id}" }) { session ->
                    Surface(color = if (state.session?.id == session.id) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(10.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { if (session.id != state.session?.id) actions.openSession(session); close() }.padding(horizontal = 12.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(if (session.isExternalChannel()) Icons.Default.Forum else Icons.Default.ChatBubbleOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(session.title.ifBlank { "新会话" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else {
                item("choose") { Text("选择机器人", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.bots, key = { "bot:${it.id}" }) { bot ->
                    Row(Modifier.fillMaxWidth().clickable { actions.selectBot(bot); close() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BotAvatar(bot, 30.dp)
                        Text(bot.label(), maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().clickable(onClick = settings).padding(horizontal = 12.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Settings, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("设置", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            MemohLogo(22.dp)
        }
    }
}

@Composable internal fun BotScreen(state: UiState, select: (Bot) -> Unit, refresh: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 1000.dp).fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("机器人", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                QuietIconButton(Icons.Default.Refresh, "刷新机器人", refresh, !state.loading)
            }
            Text("选择一个机器人开始对话", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            SearchField(search, { search = it }, "搜索机器人")
            Spacer(Modifier.height(20.dp))
            val bots = state.bots.filter { search.isBlank() || it.label().contains(search, true) }
            if (bots.isEmpty() && !state.loading) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(if (search.isBlank()) "暂无机器人" else "没有匹配的机器人", if (search.isBlank()) "在 Memoh 中创建机器人后，点击刷新。" else "试试其他关键词")
            } else LazyVerticalGrid(GridCells.Adaptive(260.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                gridItems(bots, key = { it.id }) { bot ->
                    OutlinedCard(onClick = { select(bot) }, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                BotAvatar(bot, 42.dp)
                                Text(bot.label(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatusDot(bot.active)
                                Text(if (bot.active) "可用" else "已停用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun SessionHome(state: UiState, create: () -> Unit, open: (Session) -> Unit, canCreate: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxWidth(), contentPadding = PaddingValues(28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    MemohLogo(56.dp)
                    Spacer(Modifier.height(20.dp))
                    Text("有什么新想法？", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(state.bot?.label().orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    if (canCreate) Button(create, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface)) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("新建会话")
                    }
                }
            }
            if (state.sessions.isNotEmpty()) {
                item { Text("最近会话", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.sessions.take(8), key = { it.id }) { session ->
                    Row(Modifier.fillMaxWidth().clickable { open(session) }.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(session.title.ifBlank { "新会话" }, Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.bodyMedium, overflow = TextOverflow.Ellipsis)
                        Text(sessionDate(session.updatedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
