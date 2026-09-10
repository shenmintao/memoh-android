package icu.minq.memoh

import android.widget.TableLayout
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import icu.minq.memoh.data.*
import icu.minq.memoh.model.*
import icu.minq.memoh.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LongSessionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeHistoryOnlyComposesViewport() {
        val blocks = (0 until 200).map { MessageBlock(it, "tool", name = "history-step-$it", toolCallId = "$it") }
        val state = UiState(screen = Screen.Chat, bot = Bot("b", "Memoh"), session = Session("s", "b"), connected = true,
            history = listOf(ChatTurn("t", "assistant", messages = blocks)))
        compose.setContent { MemohTheme { ChatScreen(state, UiActions()) } }
        val count = compose.onAllNodes(hasText("history-step-", substring = true)).fetchSemanticsNodes().size
        android.util.Log.i("LongSessionQA", "composed_history_cards=$count total=200")
        assertTrue("Expected at most a viewport of cards, composed $count", count in 1..30)
    }

    @Test fun largeTurnIsLazyAndReaderPositionSurvivesStreamingAndExpansion() {
        val blocks = (0 until 1000).map { MessageBlock(it, "tool", name = "tool-$it", toolCallId = "call-$it") } +
            MessageBlock(1000, "reasoning", content = "推理过程。".repeat(40000))
        var state by mutableStateOf(UiState(screen = Screen.Chat, bot = Bot("b", "Memoh"), session = Session("s", "b"), connected = true,
            runtime = RuntimeState("s", "e", 1, RuntimeRun("r", "t", status = "running", messages = blocks), false)))
        compose.setContent { MemohTheme { ChatScreen(state, UiActions()) } }
        compose.onNodeWithTag("chat-bottom").assertIsDisplayed()
        compose.onNodeWithText("思考过程").performClick()
        compose.onNodeWithText("完整内容 · 1/34").assertExists()
        assertTrue("Only visible tool cards should be composed", compose.onAllNodes(hasText("tool-", substring = true)).fetchSemanticsNodes().size < 40)
        val timeline = compose.onNodeWithTag("chat-timeline")
        timeline.performScrollToIndex(40)
        val before = compose.onNodeWithText("tool-40").fetchSemanticsNode().positionInRoot.y
        repeat(8) { index ->
            compose.runOnIdle { state = state.copy(runtime = state.runtime.copy(seq = state.runtime.seq + 1,
                run = state.runtime.run!!.copy(messages = blocks + MessageBlock(1001, "text", content = "持续输出 ".repeat(index + 1))))) }
        }
        assertEquals(before, compose.onNodeWithText("tool-40").fetchSemanticsNode().positionInRoot.y, 1f)
        compose.onNodeWithContentDescription("回到最新消息").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("chat-bottom").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat-bottom").assertIsDisplayed()
    }

    @Test fun draggingUpStopsFollowingEverySubsequentOutput() {
        val blocks = (0 until 120).map { MessageBlock(it, "tool", name = "step-$it", toolCallId = "$it") }
        var state by mutableStateOf(UiState(screen = Screen.Chat, bot = Bot("b", "Memoh"), session = Session("s", "b"), connected = true,
            runtime = RuntimeState("s", "e", 1, RuntimeRun("r", "t", status = "running", messages = blocks), false)))
        compose.setContent { MemohTheme { ChatScreen(state, UiActions()) } }
        val timeline = compose.onNodeWithTag("chat-timeline")
        timeline.performTouchInput { swipeDown() }
        timeline.performScrollToIndex(20)
        val before = compose.onNodeWithText("step-20").fetchSemanticsNode().positionInRoot.y
        compose.runOnIdle { state = state.copy(runtime = state.runtime.copy(run = state.runtime.run!!.copy(
            messages = blocks + MessageBlock(120, "text", content = "正文 ".repeat(5000))))) }
        compose.waitForIdle()
        assertEquals(before, compose.onNodeWithText("step-20").fetchSemanticsNode().positionInRoot.y, 1f)
        compose.onNodeWithTag("chat-bottom").assertDoesNotExist()
    }

    @Test fun followingTailStaysAtTheSameScreenPositionAsMarkdownGrows() {
        var text = (1..45).joinToString("\n\n") { "正文第 $it 段" }
        var state by mutableStateOf(UiState(screen = Screen.Chat, bot = Bot("b", "Memoh"), session = Session("s", "b"), connected = true,
            runtime = RuntimeState("s", "e", 1, RuntimeRun("r", "t", status = "running", messages = listOf(MessageBlock(0, "text", content = text))), false)))
        compose.setContent { MemohTheme { ChatScreen(state, UiActions()) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("正在排版…").fetchSemanticsNodes().isEmpty() }
        val before = compose.onNodeWithTag("chat-bottom").fetchSemanticsNode().positionInRoot.y
        repeat(6) { index ->
            text += "\n\n新增段落 $index"
            compose.runOnIdle { state = state.copy(runtime = state.runtime.copy(run = state.runtime.run!!.copy(messages = listOf(MessageBlock(0, "text", content = text))))) }
            // This advances test frames while waiting for the background renderer.
            compose.waitUntil(5000) { nativeTextContains("新增段落 $index") }
            compose.onNodeWithTag("chat-bottom").assertIsDisplayed()
            assertEquals(before, compose.onNodeWithTag("chat-bottom").fetchSemanticsNode().positionInRoot.y, 1f)
        }
        capture("streaming-tail.png")
    }

    @Test fun largeTablePagesWithoutInflatingAllRows() {
        val text = "| 项目 | 结果 |\n| --- | --- |\n" + (1..90).joinToString("\n") { "| 设备$it | 成功 |" }
        compose.setContent { MemohTheme { Surface { Box(Modifier.width(340.dp)) { MarkdownText(text) } } } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("markdown-table").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("表格 · 90 行 · 1/3").assertIsDisplayed()
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("设备1")).check { view, error ->
            if (error != null) throw error
            val table = view.parent.parent as TableLayout
            assertEquals(31, table.childCount)
        }
        repeat(2) { compose.onNodeWithText("下一页").performClick() }
        compose.onNodeWithText("表格 · 90 行 · 3/3").assertIsDisplayed()
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("设备90")).check { view, error ->
            if (error != null) throw error
            assertEquals("设备90", (view as TextView).text.toString())
        }
        capture("large-table-page.png")
    }

    private fun capture(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val directory = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: instrumentation.targetContext.getExternalFilesDir("qa")!!
        directory.mkdirs()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(directory, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun nativeTextContains(needle: String): Boolean {
        var found = false
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fun visit(view: android.view.View) {
                if (view is TextView && view.text.contains(needle)) found = true
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).forEach { visit(it.window.decorView) }
        }
        return found
    }
}
