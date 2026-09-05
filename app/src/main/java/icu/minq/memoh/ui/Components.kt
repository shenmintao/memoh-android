package icu.minq.memoh.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import icu.minq.memoh.R
import icu.minq.memoh.model.Bot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun Bot.label() = displayName.ifBlank { name.ifBlank { "未命名机器人" } }

@Composable internal fun MemohLogo(size: Dp = 40.dp) {
    Image(painterResource(R.drawable.ic_memoh), "Memoh", Modifier.size(size))
}

@Composable internal fun BotAvatar(bot: Bot?, size: Dp = 36.dp) {
    Surface(shape = RoundedCornerShape(size * .3f), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Text(bot?.label()?.take(1)?.uppercase() ?: "M", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable internal fun StatusDot(online: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(6.dp).clip(CircleShape).background(if (online) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline))
}

@Composable internal fun QuietIconButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick, enabled = enabled) { Icon(icon, label, Modifier.size(21.dp), tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant) }
}

@Composable internal fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) }, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium, shape = RoundedCornerShape(12.dp),
        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(19.dp)) },
        trailingIcon = { if (value.isNotEmpty()) QuietIconButton(Icons.Default.Close, "清除搜索", { onChange("") }) },
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
}

@Composable internal fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MemohLogo(56.dp)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}

@Composable internal fun ErrorCard(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.ErrorOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
            Text(text.ifBlank { "回复失败，请稍后重试" }, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable internal fun NoticeCard(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
        Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun sessionDate(timestamp: String?): String = runCatching {
    val date = Instant.parse(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    when (date) {
        LocalDate.now() -> "今天"
        LocalDate.now().minusDays(1) -> "昨天"
        else -> date.format(DateTimeFormatter.ofPattern("M月d日"))
    }
}.getOrDefault("")
