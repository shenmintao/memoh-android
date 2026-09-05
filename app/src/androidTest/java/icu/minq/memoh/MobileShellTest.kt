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

    private fun screenshot(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File) ?: context.getExternalFilesDir("qa")!!
        dir.mkdirs()
        val width = context.resources.configuration.screenWidthDp
        // Capture the actual window, including popovers/IME; forcing Compose redraw can time out on emulators.
        Thread.sleep(if (dialog) 1_000 else 350)
        compose.waitForIdle()
        val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(dir, "$name-$width.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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

    @Test fun attachmentsCanBeRemovedRetriedAndSentWithoutText() {
        val payload = ChatAttachment(name = "项目说明.pdf", mime = "application/pdf", base64 = "data:application/pdf;base64,YQ==")
        var state by mutableStateOf(chat().copy(draft = "", attachments = listOf(DraftAttachment("a", "content://fixture/a", "项目说明.pdf", 1024, payload))))
        var sent = false
        var retried = false
        var pickerOpened = false
        compose.setContent { MemohTheme { MemohShell(state, UiActions(send = { sent = true }, chooseFiles = { pickerOpened = true },
            removeAttachment = { state = state.copy(attachments = state.attachments.filterNot { file -> file.id == it }) }, retryAttachment = { retried = true })) } }
        compose.onNodeWithText("项目说明.pdf").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(sent) }
        screenshot("composer-attachments")
        compose.onNodeWithContentDescription("移除附件 项目说明.pdf").performClick()
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
        compose.onNodeWithContentDescription("添加文件").performClick()
        compose.runOnIdle { assertTrue(pickerOpened); state = state.copy(attachments = listOf(DraftAttachment("f", "content://fixture/f", "读取失败.txt", error = "无法读取文件"))) }
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
        compose.onNodeWithContentDescription("重试附件 读取失败.txt").performClick()
        compose.runOnIdle { assertTrue(retried) }
    }

    @Test fun modelAndDevicePickersApplySelectionAndDisableOfflineDevices() {
        var state by mutableStateOf(chat().copy(composer = ComposerConfig(models = listOf(ChatModel("m1", "模型一"), ChatModel("m2", "模型二")), defaultModelId = "m1",
            targets = listOf(WorkspaceTarget("native", "native"), WorkspaceTarget("remote:on", "remote", "办公电脑", true, "online"), WorkspaceTarget("remote:off", "remote", "离线电脑", false, "offline")), targetId = "native")))
        compose.setContent { MemohTheme { MemohShell(state, UiActions(selectModel = { state = state.copy(composer = state.composer.copy(modelId = it)) },
            selectDevice = { state = state.copy(composer = state.composer.copy(targetId = it)) })) } }
        compose.onNodeWithContentDescription("选择模型").performClick()
        compose.onNodeWithContentDescription("搜索模型").performTextInput("模型二")
        compose.onNodeWithTag("model-options").performScrollToNode(hasText("模型二"))
        compose.onNodeWithText("完成").assertDoesNotExist()
        screenshot("model-picker-search", dialog = true)
        compose.onNode(hasText("模型二") and !hasSetTextAction() and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals("m2", state.composer.modelId) }
        screenshot("model-picker", dialog = true)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.onNodeWithContentDescription("选择设备：服务器工作区").performClick()
        compose.onNodeWithText("离线电脑").assertIsNotEnabled()
        compose.onNodeWithText("办公电脑").performClick()
        compose.runOnIdle { assertEquals("remote:on", state.composer.targetId) }
        screenshot("device-picker", dialog = true)
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithContentDescription("选择设备：办公电脑").assertIsDisplayed()
    }

    @Test fun modelPopoverGroupsProvidersHidesUUIDAndChangesReasoning() {
        val uuid = "55e6dcc0-c6e8-4366-a91f-61600b217d1b"
        val options = ReasoningOptions(true, false, listOf("low", "medium", "high"), "medium")
        var state by mutableStateOf(chat().copy(history = emptyList(), runtime = RuntimeState("s"), composer = ComposerConfig(
            models = listOf(ChatModel(uuid, "gpt-5.6-sol", providerId = "p", reasoning = options),
                ChatModel("terra", "gpt-5.6-terra", providerId = "p", reasoning = options),
                ChatModel("image", "gpt-image-2", providerId = "p"), ChatModel("luna", "gpt-5.6-luna", providerId = "p", reasoning = options)),
            modelId = uuid, providers = listOf(ModelProvider("p", "OpenAI")), reasoningEffort = "medium")))
        compose.setContent { MemohTheme { MemohShell(state, UiActions(selectModel = { state = state.copy(composer = state.composer.copy(modelId = it).reconciled()) },
            selectReasoning = { state = state.copy(composer = state.composer.copy(reasoningEffort = it)) })) } }
        compose.onNodeWithText("gpt-5.6-sol · 中").assertIsDisplayed()
        compose.onNodeWithContentDescription("选择模型").performClick()
        compose.onNodeWithText("OpenAI").assertIsDisplayed()
        compose.onNodeWithText(uuid, substring = true).assertDoesNotExist()
        screenshot("model-popover", dialog = true)
        compose.onNodeWithContentDescription("思考强度：中").performClick()
        compose.onNodeWithText("低").assertIsDisplayed()
        compose.onNode(hasText("中") and isSelected()).assertIsDisplayed()
        screenshot("model-reasoning", dialog = true)
        compose.onNodeWithText("高").performClick()
        compose.onNodeWithText("gpt-5.6-sol · 高").assertIsDisplayed()
        compose.onNodeWithTag("model-options").performScrollToNode(hasText("gpt-image-2"))
        compose.onNodeWithText("gpt-image-2").performClick()
        compose.runOnIdle { assertEquals("", state.composer.effectiveReasoning()) }
        compose.onNodeWithContentDescription("思考强度：默认").assertDoesNotExist()
        compose.onNodeWithContentDescription("搜索模型").performTextInput("找不到")
        compose.onNodeWithText("没有匹配的模型").assertIsDisplayed()
        screenshot("model-no-results-keyboard", dialog = true)
        compose.onNodeWithContentDescription("清除模型搜索").performClick()
        compose.onNodeWithTag("model-options").performScrollToNode(hasText("gpt-5.6-terra"))
        compose.onNodeWithText("gpt-5.6-terra").performClick()
        compose.onNodeWithContentDescription("思考强度：中").assertIsDisplayed()
    }
}
