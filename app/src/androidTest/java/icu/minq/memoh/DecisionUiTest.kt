package icu.minq.memoh

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import icu.minq.memoh.data.*
import icu.minq.memoh.model.*
import icu.minq.memoh.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class DecisionUiTest {
    @get:Rule val compose = createComposeRule()
    private fun state(block: MessageBlock) = UiState(screen = Screen.Chat, bot = Bot("b", "Memoh"), session = Session("s", "b"), connected = true,
        runtime = RuntimeState("s", "e", 1, RuntimeRun("r", "t", status = "waiting_decision", messages = listOf(block)), false))
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: instrumentation.targetContext.getExternalFilesDir("qa")!!
        directory.mkdirs()
        compose.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap -> File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    @Test fun approvalIsVisibleAtComposerAndRejectionKeepsReasonAndOption() {
        val block = MessageBlock(1, "tool", name = "执行命令", input = kotlinx.serialization.json.Json.parseToJsonElement("""{"command":"git status"}"""),
            approval = Approval("a", "pending", options = listOf(ApprovalOption("once", "仅本次允许", "allow_once"))))
        var state by mutableStateOf(state(block))
        var decision: List<Any?>? = null
        compose.setContent { MemohTheme { MemohShell(state, UiActions(decide = { id, approve, option, reason -> decision = listOf(id, approve, option, reason); state = state.copy(pendingControls = setOf("control")) })) } }
        compose.onNodeWithText("需要你的批准").assertIsDisplayed()
        compose.onNodeWithText("git status").assertIsDisplayed()
        compose.onNodeWithContentDescription("消息输入框").assertDoesNotExist()
        capture("approval-composer.png")
        compose.onNodeWithText("拒绝", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("拒绝原因（可选）").performTextInput("先说明影响")
        compose.onNodeWithText("确认拒绝").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("a", false, null, "先说明影响"), decision) }
        compose.onNodeWithText("确认拒绝").assertIsNotEnabled()
    }
    @Test fun singleSelectionAndCustomTextAreExclusiveAndCancelUsesDecisionCallback() {
        val input = UserInput("i", "pending", listOf(UserQuestion("q", "选择执行方式", "single_select", listOf(UserOption("once", "执行一次"), UserOption("plan", "只看计划")), allowCustom = true)))
        var result: List<UserAnswer>? = null
        var canceled = false
        var keyboard: androidx.compose.ui.platform.SoftwareKeyboardController? = null
        compose.setContent { keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current; MemohTheme { MemohShell(state(MessageBlock(1, "tool", name = "ask_user", userInput = input)), UiActions(answer = { id, answers, cancel -> assertEquals("i", id); result = answers; canceled = cancel })) } }
        compose.onNodeWithText("提交回答").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("只看计划").performScrollTo().performClick()
        compose.onNodeWithContentDescription("回答：选择执行方式").performScrollTo().performClick().performTextInput("先解释风险")
        compose.runOnIdle { keyboard?.show() }
        Thread.sleep(700)
        capture("user-input-ime.png")
        compose.onNodeWithText("提交回答").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(UserAnswer("q", customText = "先解释风险")), result); assertFalse(canceled) }
        capture("user-input-composer.png")
        compose.onNodeWithText("取消回答").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(canceled); assertTrue(result!!.isEmpty()) }
    }
    @Test fun readonlyDisconnectedAndResolvedRequestsCannotBeSubmitted() {
        val block = MessageBlock(1, "tool", approval = Approval("a", "pending"))
        var state by mutableStateOf(state(block).copy(connected = false))
        compose.setContent { MemohTheme { MemohShell(state) } }
        compose.onNodeWithText("批准", substring = false).assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(connected = true, session = state.session!!.copy(channelType = "telegram")) }
        compose.onNodeWithText("批准", substring = false).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(session = state.session!!.copy(channelType = "local"), runtime = state.runtime.copy(run = state.runtime.run!!.copy(status = "completed"))) }
        compose.onNodeWithText("需要你的批准").assertDoesNotExist()
        compose.onNodeWithText("此批准请求已不可操作").assertExists()
    }
    @Test fun multipleQuestionsValidateTextAndMultiSelectAndSkipOptionalFields() {
        val input = UserInput("multi", "pending", listOf(
            UserQuestion("q1", "需要哪些步骤？", "multi_select", listOf(UserOption("test", "运行测试"), UserOption("review", "检查修改"))),
            UserQuestion("q2", "补充要求", "text"), UserQuestion("q3", "备注", "text", required = false)))
        var answers: List<UserAnswer>? = null
        compose.setContent { MemohTheme { MemohShell(state(MessageBlock(1, "tool", userInput = input)), UiActions(answer = { _, value, _ -> answers = value })) } }
        compose.onNodeWithText("运行测试").performScrollTo().performClick()
        compose.onNodeWithText("检查修改").performScrollTo().performClick()
        compose.onNodeWithText("提交回答").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("回答：补充要求").performScrollTo().performTextInput("完成后说明结果")
        compose.onNodeWithText("提交回答").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(UserAnswer("q1", optionIds = listOf("test", "review")), UserAnswer("q2", text = "完成后说明结果"), UserAnswer("q3", skipped = true)), answers)
        }
    }

}
