package icu.minq.memoh

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import icu.minq.memoh.data.*
import icu.minq.memoh.model.*
import icu.minq.memoh.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class ChatImageTest {
    @get:Rule val compose = createComposeRule()

    private fun picture(width: Int = 720, height: Int = 480): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(220, 235, 250))
            drawRect(width * .1f, height * .15f, width * .9f, height * .65f, Paint().apply { color = Color.rgb(90, 80, 200) })
            drawText("Memoh image preview", width * .1f, height * .83f, Paint().apply { color = Color.DKGRAY; textSize = width / 24f })
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
    }

    @Test fun replyAttachmentLoadsWithOwnerAndOpensZoomablePreview() {
        val bytes = picture()
        var requests = 0
        val attachment = ChatAttachment(type = "image", name = "机器人图片.png", mime = "image/png", contentHash = "a".repeat(64), botId = "owner")
        val loader = ChatImageLoader { file, bot, full ->
            assertEquals("owner", file.botId); assertEquals("current", bot); requests++
            withContext(Dispatchers.Default) { decodeChatBitmap(bytes, if (full) 2560 else 720) }
        }
        compose.setContent { MemohTheme { CompositionLocalProvider(LocalChatImages provides loader, LocalImageBot provides "current") {
            Surface { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) { MessageAttachments(listOf(attachment)) } }
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("image-thumbnail", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("image-thumbnail", useUnmergedTree = true).assertIsDisplayed()
        capture("image-thumbnail.png")
        compose.onNodeWithTag("image-attachment").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("image-full").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("image-full").assertIsDisplayed().performTouchInput { doubleClick() }
        compose.onNodeWithTag("image-full").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "200%"))
        compose.onNodeWithText("双指缩放 · 双击放大或还原").assertIsDisplayed()
        // PixelCopy waits for the transformed layer to draw, unlike a raw device screenshot.
        val zoomed = compose.onNodeWithTag("image-preview").captureToImage().asAndroidBitmap()
        val middle = zoomed.height / 2
        assertEquals(Color.rgb(90, 80, 200), zoomed.getPixel(zoomed.width / 20, middle))
        capture("image-fullscreen.png")
        compose.onNodeWithTag("image-full").performTouchInput { doubleClick() }
        compose.onNodeWithTag("image-full").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "100%"))
        compose.onNodeWithContentDescription("关闭图片").performClick()
        compose.onNodeWithTag("image-preview").assertDoesNotExist()
        assertEquals(2, requests)
    }

    @Test fun failedThumbnailCanRetryWithoutLosingAttachment() {
        val bytes = picture()
        var calls = 0
        val loader = ChatImageLoader { _, _, _ ->
            if (calls++ == 0) throw java.io.IOException("offline")
            withContext(Dispatchers.Default) { decodeChatBitmap(bytes, 720) }
        }
        compose.setContent { MemohTheme { CompositionLocalProvider(LocalChatImages provides loader) { MessageAttachments(listOf(ChatAttachment(type = "image", name = "可重试.png"))) } } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("图片加载失败，请重试").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("重试图片").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("image-thumbnail", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("可重试.png").assertIsDisplayed()
    }

    @Test fun inlineUploadedImageKeepsBytesAndLargeImageDecodeIsBounded() {
        val bytes = picture(4000, 2400)
        assertArrayEquals(bytes, decodeInlineImage("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)))
        val thumbnail = decodeChatBitmap(bytes, 720)
        assertTrue(thumbnail.width <= 720 && thumbnail.height <= 720)
        val full = decodeChatBitmap(bytes, 2560)
        assertTrue(full.width.toLong() * full.height <= 4_000_000)
        assertTrue(runCatching { decodeInlineImage("data:text/html;base64,SGVsbG8=") }.isFailure)
        assertTrue(runCatching { decodeChatBitmap("not an image".toByteArray(), 720) }.isFailure)
        thumbnail.recycle(); full.recycle()
    }

    private fun capture(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val folder = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: instrumentation.targetContext.getExternalFilesDir("qa")!!
        folder.mkdirs()
        instrumentation.uiAutomation.takeScreenshot().let { image -> java.io.File(folder, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}
