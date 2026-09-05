package icu.minq.memoh.ui

import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import icu.minq.memoh.data.UiState
import icu.minq.memoh.model.*

/** Kept inside the trigger's Box so the popup follows the composer when the IME moves it. */
@Composable internal fun ModelPicker(state: UiState, actions: UiActions, anchorTop: Int, dismiss: () -> Unit) {
    var search by remember { mutableStateOf("") }
    var reasoning by remember { mutableStateOf(false) }
    val config = state.composer
    val efforts = config.reasoningOptions()
    val locked = state.loading || state.sendInFlight || config.modelChanging || config.modelsLoading || config.modelUncertain ||
        state.pending?.blocksSend == true || state.runtime.run?.let { !it.isTerminal() } == true || state.session.isExternalChannel()
    val density = LocalDensity.current
    val viewport = rememberMenuViewport()
    val width = with(density) { (viewport.width.toDp() - 24.dp).coerceIn(1.dp, 360.dp) }
    val maxHeight = with(density) {
        val visible = (viewport.height.toDp() - 24.dp).coerceAtLeast(1.dp)
        val above = (anchorTop - viewport.top).toDp() - 18.dp
        // Shrink the scrollable list before covering the trigger on short screens.
        if (above >= 176.dp) minOf(above, visible) else visible
    }
    val wide = with(density) { viewport.width.toDp() >= width + 220.dp }
    val margin = with(density) { 12.dp.roundToPx() }
    val position = remember(viewport, margin) { ModelMenuPosition(viewport, margin) }
    val shown = config.models.filter { search.isBlank() || it.label().contains(search, true) || config.providerLabel(it).contains(search, true) }
    val groups = shown.groupBy { it.providerId }
    LaunchedEffect(efforts) { if (efforts.isEmpty()) reasoning = false }
    Popup(popupPositionProvider = position, onDismissRequest = dismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuPanel(Modifier.width(width).heightIn(max = maxHeight).testTag("model-menu")) {
                if (reasoning && !wide) {
                    Row(Modifier.fillMaxWidth().clickable { reasoning = false }.heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ChevronLeft, "返回模型列表", Modifier.size(20.dp))
                        Text("思考强度", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    EffortList(efforts, config.effectiveReasoning(), locked, Modifier.weight(1f, fill = false)) {
                        actions.selectReasoning(it); reasoning = false
                    }
                } else {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(search, { search = it }, Modifier.weight(1f).padding(vertical = 12.dp).semantics { contentDescription = "搜索模型" },
                            singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface), decorationBox = { field ->
                                Box { if (search.isBlank()) Text("搜索模型…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); field() }
                            })
                        if (search.isNotEmpty()) IconButton({ search = "" }, Modifier.size(36.dp)) { Icon(Icons.Default.Close, "清除模型搜索", Modifier.size(16.dp)) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (config.modelsLoading || config.modelChanging) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                    config.modelsError?.let { error ->
                        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            TextButton(actions.refreshModels, enabled = !config.modelsLoading && !config.modelChanging) { Text("重试") }
                        }
                    }
                    LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 320.dp).testTag("model-options"), contentPadding = PaddingValues(8.dp)) {
                        groups.forEach { (provider, models) ->
                            val label = config.providerLabel(models.first())
                            if (label.isNotBlank()) item("provider:$provider") {
                                Text(label, Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            items(models, key = { "model:${it.id}" }) { model ->
                                ModelMenuRow(model.label(), model.id == config.modelId.ifBlank { config.defaultModelId }, !locked) {
                                    focus.clearFocus(); keyboard?.hide(); actions.selectModel(model.id)
                                }
                            }
                        }
                        if (!config.acpRuntime && config.defaultModelId.isNotBlank() && search.isBlank()) item {
                            ModelMenuRow("跟随默认模型", config.modelId.isBlank() && config.selectedModel() == null, !locked) { focus.clearFocus(); keyboard?.hide(); actions.selectModel("") }
                        }
                        if (shown.isEmpty() && !config.modelsLoading) item {
                            Text(if (search.isBlank()) "暂无可选模型" else "没有匹配的模型", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (efforts.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.padding(8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable(enabled = !locked) { focus.clearFocus(); keyboard?.hide(); reasoning = !reasoning }
                            .semantics { contentDescription = "思考强度：${config.reasoningLabel()}" }.heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Lightbulb, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(config.reasoningLabel(), Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodyMedium)
                            Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (reasoning && wide) MenuPanel(Modifier.width(196.dp).heightIn(max = maxHeight).testTag("reasoning-menu")) {
                EffortList(efforts, config.effectiveReasoning(), locked, Modifier.weight(1f, fill = false)) {
                    actions.selectReasoning(it); reasoning = false
                }
            }
        }
    }
}

@Composable private fun MenuPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceBright,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 5.dp) { Column(content = content) }
}

@Composable private fun EffortList(options: List<ReasoningEffort>, current: String, locked: Boolean, modifier: Modifier, select: (String) -> Unit) {
    LazyColumn(modifier.testTag("reasoning-options"), contentPadding = PaddingValues(8.dp)) {
        items(options, key = { it.id }) { option -> ModelMenuRow(option.label(), option.id == current, !locked, bulb = true) { select(option.id) } }
    }
}

@Composable private fun ModelMenuRow(label: String, checked: Boolean, enabled: Boolean, bulb: Boolean = false, click: () -> Unit) {
    val color = if (enabled || checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = click)
        .semantics { selected = checked }.heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (bulb) Icon(Icons.Outlined.Lightbulb, null, Modifier.padding(end = 10.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = color)
        if (checked) Icon(Icons.Default.Check, "已选择 $label", Modifier.padding(start = 10.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal class ModelMenuPosition(private val viewport: IntRect, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val left = viewport.left.coerceAtLeast(0) + margin
        val top = viewport.top.coerceAtLeast(0) + margin
        val right = minOf(viewport.right, windowSize.width) - margin
        val bottom = minOf(viewport.bottom, windowSize.height) - margin
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(left, (right - popupContentSize.width).coerceAtLeast(left))
        val y = (anchorBounds.top - popupContentSize.height - margin / 2).coerceIn(top, (bottom - popupContentSize.height).coerceAtLeast(top))
        return IntOffset(x, y)
    }
}

@Composable private fun rememberMenuViewport(): IntRect {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    var bounds by remember(view, configuration) { mutableStateOf(with(density) { IntRect(0, 0, configuration.screenWidthDp.dp.roundToPx(), configuration.screenHeightDp.dp.roundToPx()) }) }
    DisposableEffect(view, configuration) {
        fun update() {
            val rect = Rect()
            view.getWindowVisibleDisplayFrame(rect)
            val screen = IntArray(2); val window = IntArray(2)
            view.getLocationOnScreen(screen); view.getLocationInWindow(window)
            rect.offset(window[0] - screen[0], window[1] - screen[1])
            if (rect.width() > 0 && rect.height() > 0) bounds = IntRect(rect.left, rect.top, rect.right, rect.bottom)
        }
        val listener = ViewTreeObserver.OnGlobalLayoutListener { update() }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        update()
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return bounds
}
