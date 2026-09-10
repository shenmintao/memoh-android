package icu.minq.memoh.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.ext.tables.Table
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface MarkdownPart {
    data class Prose(val text: Spanned) : MarkdownPart
    data class Grid(val table: Table) : MarkdownPart
    data class Quote(val parts: List<MarkdownPart>) : MarkdownPart
    data class Item(val marker: String, val parts: List<MarkdownPart>) : MarkdownPart
}

internal fun markdownRenderer(context: Context): Markwon = Markwon.builder(context)
    .usePlugin(object : AbstractMarkwonPlugin() {
        override fun configureParser(builder: Parser.Builder) {
            builder.extensions(listOf(TablesExtension.create()))
        }

        override fun configureVisitor(builder: MarkwonVisitor.Builder) {
            // Only interpret line breaks; arbitrary HTML remains inactive.
            builder.on(HtmlInline::class.java) { visitor, node ->
                if (node.literal.matches(Regex("(?i)<br\\s*/?>"))) visitor.forceNewLine()
            }
        }
    }).build()

// Parse the complete document before separating blocks, so reference links, escaped
// pipes, fenced code and tables inside lists/quotes keep CommonMark semantics.
internal fun markdownParts(markwon: Markwon, markdown: String): List<MarkdownPart> {
    fun hasTable(node: Node): Boolean = node is TableBlock || node.children().any { hasTable(it) }
    fun blocks(parent: Node): List<MarkdownPart> = buildList {
        var prose = Document()
        fun flush() {
            if (prose.firstChild != null) {
                val text = safeMarkdown(markwon.render(prose))
                if (text.isNotEmpty()) add(MarkdownPart.Prose(text))
                prose = Document()
            }
        }
        // Snapshot before moving prose nodes into a renderable document.
        parent.children().toList().forEach { node ->
            when {
                node is TableBlock -> {
                    flush()
                    Table.parse(markwon, node)?.let { add(MarkdownPart.Grid(it)) }
                }
                hasTable(node) -> {
                    flush()
                    when (node) {
                        is BlockQuote -> add(MarkdownPart.Quote(blocks(node)))
                        is ListBlock -> node.children().toList().forEachIndexed { index, item ->
                            val marker = if (node is OrderedList) "${node.startNumber + index}${node.delimiter}" else "•"
                            add(MarkdownPart.Item(marker, blocks(item)))
                        }
                        else -> addAll(blocks(node))
                    }
                }
                else -> prose.appendChild(node)
            }
        }
        flush()
    }
    return blocks(markwon.parse(markdown))
}

private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }

internal fun safeMarkdown(content: Spanned): Spanned = SpannableStringBuilder(content).apply {
    getSpans(0, length, URLSpan::class.java).forEach { span ->
        if (Uri.parse(span.url).scheme?.lowercase() !in setOf("https", "http")) removeSpan(span)
    }
}

@Composable internal fun MarkdownText(markdown: String, streaming: Boolean = false, onInteract: () -> Unit = {}) {
    val context = LocalContext.current
    val markwon = remember(context) { markdownRenderer(context) }
    val lock = remember(markwon) { Mutex() }
    val latest by rememberUpdatedState(markdown to streaming)
    val parts by produceState<List<MarkdownPart>?>(null, markwon) {
        snapshotFlow { latest }.conflate().collect { (text, running) ->
            value = withContext(Dispatchers.Default) { lock.withLock { markdownParts(markwon, text) } }
            // Keep the most recent pending update, without parsing every token.
            if (running) delay(120)
        }
    }
    if (parts == null) Text("正在排版…", Modifier.heightIn(min = 24.dp), style = MaterialTheme.typography.bodySmall)
    else MarkdownParts(parts!!, markwon, onInteract)
}

@Composable private fun MarkdownParts(parts: List<MarkdownPart>, markwon: Markwon, onInteract: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val pages = ((parts.size - 1).coerceAtLeast(0) / 8) + 1
    val page = selected.coerceIn(0, pages - 1)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageControls(page, pages, "消息内容") { onInteract(); selected = it }
        parts.drop(page * 8).take(8).forEach { part ->
            when (part) {
                is MarkdownPart.Prose -> {
                    var textPage by rememberSaveable { mutableIntStateOf(0) }
                    val textPages = ((part.text.length - 1).coerceAtLeast(0) / TEXT_PAGE_CHARS) + 1
                    val current = textPage.coerceIn(0, textPages - 1)
                    val shown = remember(part.text, current) {
                        val range = textPageRange(part.text, current)
                        part.text.subSequence(range.first, range.last + 1) as Spanned
                    }
                    PageControls(current, textPages, "完整内容") { onInteract(); textPage = it }
                    val ink = MaterialTheme.colorScheme.onSurface.toArgb()
                    val link = MaterialTheme.colorScheme.primary.toArgb()
                    AndroidView(modifier = Modifier.fillMaxWidth(), factory = { markdownTextView(it) }, update = { view ->
                        view.setTextColor(ink); view.setLinkTextColor(link)
                        if (view.tag !== shown) {
                            view.text = shown; view.tag = shown
                        }
                    })
                }
                is MarkdownPart.Grid -> MarkdownTable(part.table, onInteract)
                is MarkdownPart.Quote -> {
                    val border = MaterialTheme.colorScheme.outlineVariant
                    Box(Modifier.fillMaxWidth().drawBehind { drawLine(border, Offset.Zero, Offset(0f, size.height), 3.dp.toPx()) }.padding(start = 15.dp)) {
                        MarkdownParts(part.parts, markwon, onInteract)
                    }
                }
                is MarkdownPart.Item -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(part.marker, style = MaterialTheme.typography.bodyLarge)
                    Box(Modifier.weight(1f)) { MarkdownParts(part.parts, markwon, onInteract) }
                }
            }
        }
    }
}

private fun markdownTextView(context: Context) = TextView(context).apply {
    textSize = 16f
    setLineSpacing(0f, 1.45f)
    setTextIsSelectable(true)
    movementMethod = LinkMovementMethod.getInstance()
    includeFontPadding = false
}

@Composable private fun MarkdownTable(table: Table, onInteract: () -> Unit) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurface.toArgb()
    val link = colors.primary.toArgb()
    val border = colors.outlineVariant.toArgb()
    val header = colors.surfaceContainerHigh.toArgb()
    val stripe = colors.surfaceContainerLow.toArgb()
    val background = colors.surface.toArgb()
    val scroll = rememberScrollState()
    val fontPx = with(density) { 14.sp.toPx() }
    val padding = with(density) { 12.dp.roundToPx() }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val rows = table.rows()
    val pages = ((rows.size - 2).coerceAtLeast(0) / 30) + 1
    val page = selected.coerceIn(0, pages - 1)
    val visibleRows = remember(table, page) { rows.take(1) + rows.drop(1 + page * 30).take(30) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val widths = remember(table, available, density) {
            val paint = TextPaint().apply { textSize = fontPx }
            val preferred = (0 until table.rows().first().columns().size).map { column ->
                val measured = table.rows().take(61).maxOf { row ->
                    paint.typeface = if (row.header()) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    ceil(Layout.getDesiredWidth(row.columns()[column].content(), paint)).toInt() + padding * 2
                }
                measured.coerceIn(padding * 5, (fontPx * 17 + padding * 2).roundToInt())
            }
            val minimum = preferred.map { minOf(it, (fontPx * 7 + padding * 2).roundToInt()) }
            val total = preferred.sum()
            when {
                total <= available -> preferred.mapIndexed { index, width ->
                    width + (available - total) / preferred.size + if (index < (available - total) % preferred.size) 1 else 0
                }
                minimum.sum() <= available -> {
                    val flexible = total - minimum.sum()
                    val room = available - minimum.sum()
                    minimum.mapIndexed { index, width -> width + ((preferred[index] - width).toDouble() * room / flexible).toInt() }
                }
                else -> preferred
            }
        }
        val wide = widths.sum() > available
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PageControls(page, pages, "表格 · ${rows.size - 1} 行") { onInteract(); selected = it }
            Surface(shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, colors.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.horizontalScroll(scroll).testTag("markdown-table")) {
                    val presentation = listOf(table, page, widths, fontPx, ink, link, border, header, stripe, background)
                    AndroidView(modifier = Modifier.width(with(density) { widths.sum().toDp() }), factory = { TableLayout(it) }, update = { layout ->
                        if (layout.tag != presentation) {
                            layout.removeAllViews()
                            visibleRows.forEachIndexed { rowIndex, row ->
                                val nativeRow = TableRow(layout.context)
                                row.columns().forEachIndexed { columnIndex, cell ->
                                    nativeRow.addView(markdownTextView(layout.context).apply {
                                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontPx)
                                        setLineSpacing(0f, 1.3f)
                                        setPadding(padding, padding, padding, padding)
                                        setTextColor(ink); setLinkTextColor(link)
                                        if (row.header()) {
                                            setTypeface(typeface, Typeface.BOLD)
                                            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
                                        }
                                        gravity = Gravity.TOP or when (cell.alignment()) {
                                            Table.Alignment.CENTER -> Gravity.CENTER_HORIZONTAL
                                            Table.Alignment.RIGHT -> Gravity.RIGHT
                                            else -> Gravity.LEFT
                                        }
                                        this.background = GradientDrawable().apply {
                                            setColor(if (row.header()) header else if (rowIndex % 2 == 0) stripe else background)
                                            setStroke(1, border)
                                        }
                                        text = safeMarkdown(cell.content())
                                    }, TableRow.LayoutParams(widths[columnIndex], ViewGroup.LayoutParams.MATCH_PARENT))
                                }
                                layout.addView(nativeRow, TableLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                            }
                            layout.tag = presentation
                        }
                    })
                }
            }
            if (wide) Text("左右滑动查看完整表格", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}
