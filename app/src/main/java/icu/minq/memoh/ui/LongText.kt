package icu.minq.memoh.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily

internal const val TEXT_PAGE_CHARS = 6000

/** Bounds layout work, retaining every character (including surrogate pairs). */
internal fun textPageRange(text: CharSequence, page: Int, size: Int = TEXT_PAGE_CHARS): IntRange {
    require(size > 1)
    var start = (page.coerceAtLeast(0).toLong() * size).coerceAtMost(text.length.toLong()).toInt()
    var end = (start.toLong() + size).coerceAtMost(text.length.toLong()).toInt()
    if (start > 0 && start < text.length && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start--
    if (end > 0 && end < text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) end--
    return start until end
}

@Composable internal fun PageControls(page: Int, pages: Int, label: String, select: (Int) -> Unit) {
    if (pages > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton({ select(page - 1) }, enabled = page > 0) { Text("上一页") }
        Text("$label · ${page + 1}/$pages", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
        TextButton({ select(page + 1) }, enabled = page + 1 < pages) { Text("下一页") }
    }
}

@Composable internal fun LongPlainText(text: String, monospace: Boolean = false) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val pages = ((text.length - 1).coerceAtLeast(0) / TEXT_PAGE_CHARS) + 1
    val page = selected.coerceIn(0, pages - 1)
    val visible = remember(text, page) { text.substring(textPageRange(text, page)) }
    Column {
        PageControls(page, pages, "完整内容") { selected = it }
        SelectionContainer {
            Text(visible, style = if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge)
        }
    }
}
