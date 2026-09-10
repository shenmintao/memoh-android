package icu.minq.memoh.ui

import android.graphics.Bitmap
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import icu.minq.memoh.data.ChatImageLoader
import icu.minq.memoh.model.ChatAttachment
import icu.minq.memoh.network.ApiException
import kotlinx.coroutines.CancellationException

internal val LocalChatImages = staticCompositionLocalOf<ChatImageLoader?> { null }
internal val LocalImageBot = staticCompositionLocalOf { "" }

@Composable internal fun ChatImage(file: ChatAttachment, onOpen: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 320.dp).fillMaxWidth().clickable { onOpen(); open = true }.testTag("image-attachment")) {
        ImageContent(file, false, Modifier.fillMaxWidth().height(190.dp))
        Text(file.name.ifBlank { "图片" }, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text("点击查看大图", Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties()) {
        // Measure inside the actual window. Compose 1.7's non-default width path uses
        // screenHeightDp, which can include system bars and clip the footer on Android 15+.
        val window = (LocalView.current.parent as DialogWindowProvider).window
        SideEffect { window.setLayout(MATCH_PARENT, MATCH_PARENT) }
        Column(Modifier.fillMaxSize().background(Color.Black).testTag("image-preview")) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(file.name.ifBlank { "图片" }, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton({ open = false }) { Icon(Icons.Default.Close, "关闭图片", tint = Color.White) }
            }
            ImageContent(file, true, Modifier.fillMaxWidth().weight(1f))
            Text("双指缩放 · 双击放大或还原", Modifier.align(Alignment.CenterHorizontally).padding(12.dp), color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun ImageContent(file: ChatAttachment, fullSize: Boolean, modifier: Modifier) {
    val loader = LocalChatImages.current
    val bot = LocalImageBot.current
    var attempt by remember { mutableIntStateOf(0) }
    var bitmap by remember(file, fullSize, loader, bot) { mutableStateOf<Bitmap?>(null) }
    var error by remember(file, fullSize, loader, bot) { mutableStateOf<String?>(null) }
    LaunchedEffect(file, fullSize, loader, bot, attempt) {
        error = null
        try {
            bitmap = requireNotNull(loader) { "图片服务未就绪" }.load(file, bot, fullSize)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            error = when ((failure as? ApiException)?.status) {
                401 -> "登录已失效，请重新登录"
                403 -> "没有查看这张图片的权限"
                404 -> "图片已不存在"
                413 -> "图片过大，暂时无法预览"
                else -> "图片加载失败，请重试"
            }
        }
    }
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val loaded = bitmap
        when {
            loaded != null -> {
                var scale by remember(loaded) { mutableFloatStateOf(1f) }
                var offset by remember(loaded) { mutableStateOf(Offset.Zero) }
                val gestures = if (!fullSize) Modifier else Modifier
                    .pointerInput(loaded) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2f; offset = Offset.Zero }) }
                    .pointerInput(loaded) { detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset = if (scale == 1f) Offset.Zero else Offset(
                            (offset.x + pan.x).coerceIn(-size.width * (scale - 1) / 2, size.width * (scale - 1) / 2),
                            (offset.y + pan.y).coerceIn(-size.height * (scale - 1) / 2, size.height * (scale - 1) / 2))
                    } }
                Image(loaded.asImageBitmap(), file.name.ifBlank { "图片" }, Modifier.fillMaxSize().then(gestures)
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                    .semantics { stateDescription = "${(scale * 100).toInt()}%" }
                    .testTag(if (fullSize) "image-full" else "image-thumbnail"), contentScale = ContentScale.Fit)
            }
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error!!, color = if (fullSize) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                TextButton({ attempt++ }) { Icon(Icons.Default.Refresh, null); Text("重试图片") }
            }
            else -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
        }
    }
}
