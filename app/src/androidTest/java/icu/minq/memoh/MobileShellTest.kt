package icu.minq.memoh

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import icu.minq.memoh.data.*
import icu.minq.memoh.model.*
import icu.minq.memoh.security.RememberedLogin
import icu.minq.memoh.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class MobileShellTest {
    @get:Rule val compose = createComposeRule()
    private val bot = Bot("b", "Memoh", currentUserPermissions = listOf("manage"))
    private val sessions = listOf(Session("s", "b", "为新的想法留一点空间"), Session("s2", "b", "整理项目计划"), Session("s3", "b", "今天的阅读笔记"))
    private fun chat() = UiState(screen = Screen.Chat, user = CurrentUser("u", "demo"), bot = bot,
        bots = listOf(bot, Bot("b2", "研究助手")), sessions = sessions, session = sessions[0], settingsAvailable = true, connected = true,
        history = listOf(ChatTurn("old", "user", "帮我整理一下这个项目的下一步。"), ChatTurn("old", "assistant", messages = listOf(MessageBlock(0, "text", "可以从三个方面开始：\n\n- 明确这次迭代要解决的问题\n- 把工作拆成可以验证的小任务\n- 为每一步留下一点记录")))),
        runtime = RuntimeState("s", "e", 4, RuntimeRun("r", "t", status = "completed", error = "", request_user_turn = ChatTurn("t", "user", "先从界面开始吧，保持简单。"),
            messages = listOf(MessageBlock(0, "reasoning", "先梳理页面结构，再确定信息层级。"), MessageBlock(1, "text", "好的。让对话成为中心，让导航随时可达。\n\n**下一步**\n\n我们可以先完成登录、会话切换和消息输入，再逐步打磨细节。"))), false))

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File) ?: context.getExternalFilesDir("qa")!!
        dir.mkdirs()
        val width = context.resources.configuration.screenWidthDp
        File(dir, "$name-$width.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun completedReplyHasNoFalseErrorAndNextMessageCanBeSent() {
        var state by mutableStateOf(chat().copy(runtime = chat().runtime.copy(run = chat().runtime.run!!.copy(status = "running", error = null))))
        var sent = ""
        var keyboard: androidx.compose.ui.platform.SoftwareKeyboardController? = null
        compose.setContent { keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current; MemohTheme { MemohShell(state, UiActions(editDraft = { state = state.copy(draft = it) }, send = { sent = it })) } }
        compose.runOnIdle { state = state.copy(runtime = state.runtime.copy(run = state.runtime.run!!.copy(status = "completed", error = ""))) }
        compose.onNodeWithText("发生错误").assertDoesNotExist()
        compose.onNodeWithText("回复失败，请稍后重试").assertDoesNotExist()
        compose.onNodeWithContentDescription("消息输入框").performTextInput("继续")
        screenshot("chat-keyboard")
        compose.onNodeWithContentDescription("发送", useUnmergedTree = true).assertExists().performClick()
        compose.runOnIdle { assertEquals("继续", sent); state = state.copy(draft = "") }
        compose.runOnIdle { keyboard?.hide() }
        screenshot("chat-light")
    }

    @Test fun actualRuntimeFailureRemainsVisible() {
        compose.setContent { MemohTheme { MemohShell(chat().copy(history = emptyList(), runtime = RuntimeState("s", "e", 4, RuntimeRun("r", "t", status = "errored", error = "服务暂时不可用"), false))) } }
        compose.onNodeWithText("服务暂时不可用").assertIsDisplayed()
        screenshot("chat-error")
    }

    @Test fun navigationSearchSelectsSessionAndClosesSheet() {
        var state by mutableStateOf(chat())
        compose.setContent { MemohTheme { MemohShell(state, UiActions(openSession = { state = state.copy(session = it, runtime = RuntimeState(it.id), history = emptyList()) })) } }
        val nav = compose.onAllNodesWithContentDescription("打开导航")
        if (nav.fetchSemanticsNodes().isNotEmpty()) nav[0].performClick()
        screenshot("navigation")
        compose.onNodeWithTag("navigation-list").performScrollToNode(hasText("搜索会话"))
        compose.onNodeWithText("搜索会话").performTextInput("项目")
        compose.onNodeWithTag("navigation-list").performScrollToNode(hasText("整理项目计划"))
        compose.onNodeWithText("整理项目计划").performClick()
        compose.runOnIdle { assertEquals("s2", state.session?.id) }
        compose.onNodeWithText("我们从哪里开始？").assertIsDisplayed()
    }

    @Test fun loginRemembersThreeFieldsAndCanForgetThem() {
        var remembered = false
        var submitted: List<String>? = null
        var forgotten = false
        compose.setContent { MemohTheme { MemohShell(UiState(rememberedLogin = RememberedLogin("https://memoh.example", "demo", "sample-password")),
            UiActions(login = { url, user, pass, keep -> submitted = listOf(url, user, pass); remembered = keep }, forgetLogin = { forgotten = true })) } }
        compose.onNodeWithText("https://memoh.example").assertExists()
        compose.onNodeWithText("demo").assertExists()
        screenshot("login-light")
        compose.onNodeWithContentDescription("密码").performScrollTo().performClick()
        screenshot("login-keyboard")
        compose.onNodeWithText("继续").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("https://memoh.example", "demo", "sample-password"), submitted); assertTrue(remembered) }
        compose.onNodeWithText("记住登录信息").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(forgotten) }
    }

    @Test fun darkChatKeepsOfficialReadingLayout() {
        compose.setContent { MemohTheme(darkTheme = true) { MemohShell(chat()) } }
        compose.onNodeWithText("发生错误").assertDoesNotExist()
        screenshot("chat-dark")
    }
}
