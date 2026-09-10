package icu.minq.memoh
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import icu.minq.memoh.data.*
import icu.minq.memoh.model.*
import icu.minq.memoh.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
class SteeringUiTest {
 private fun capture(name: String) {
  val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
  val directory = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
    ?: instrumentation.targetContext.getExternalFilesDir("qa")!!
  directory.mkdirs()
  compose.waitForIdle()
  instrumentation.uiAutomation.takeScreenshot().let { bitmap -> java.io.File(directory, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
 }

 @get:Rule val compose = createComposeRule()
 @Test fun consumedSupplementsMoveIntoConversationAndDoNotReturnAfterHistoryRefresh() {
  val before = MessageBlock(0,"text",content="Working")
  val after = MessageBlock(1,"text",content="Done")
  var state by mutableStateOf(UiState(screen=Screen.Chat,bot=Bot("b","Memoh"),session=Session("s","b"),connected=true,
   steeringQueue=listOf(StoredSteering("a","r","第一条补充","queued","t"),StoredSteering("b","r","第二条补充","queued","t")),
   runtime=RuntimeState("s","e",1,RuntimeRun("r","t",status="running",messages=listOf(before),request_user_turn=ChatTurn("t","user","原始问题")),false,true,true)))
  compose.setContent { MemohTheme { MemohShell(state,UiActions()) } }
  compose.onNodeWithText("补充队列 · 2 条待处理").assertIsDisplayed()
  compose.runOnIdle { state=state.copy(steeringQueue=state.steeringQueue.map { it.copy(status="applied",afterMessageId=0) },
   runtime=state.runtime.copy(run=state.runtime.run!!.copy(messages=listOf(before,after),steer_queue=listOf(SteerState("a","applied","第一条补充",after_message_id=0),SteerState("b","applied","第二条补充",after_message_id=0))))) }
  compose.onNodeWithText("补充队列 · 2 条待处理").assertDoesNotExist()
  compose.onNodeWithText("补充内容已插入").assertDoesNotExist()
  compose.onAllNodesWithText("第一条补充").assertCountEquals(1)
  compose.onAllNodesWithText("第二条补充").assertCountEquals(1)
  capture("supplements-in-conversation.png")
  compose.runOnIdle { state=state.copy(steeringQueue=emptyList(),history=listOf(ChatTurn("t","user","原始问题",runId="r"),
   ChatTurn("t","assistant",messages=listOf(before),runId="r"),ChatTurn("next","user","第一条补充\n\n第二条补充",runId="r"),
   ChatTurn("next","assistant",messages=listOf(after),runId="r")),runtime=state.runtime.copy(run=state.runtime.run!!.copy(status="completed"))) }
  compose.onAllNodesWithText("原始问题").assertCountEquals(1)
  compose.onAllNodesWithText("第一条补充\n\n第二条补充").assertCountEquals(1)
  compose.onNodeWithText("补充内容已插入").assertDoesNotExist()
  capture("supplements-persisted.png")
 }
 @Test fun pendingQueueStillAllowsAnotherSupplement() {
  val queue=listOf(StoredSteering("one","r","第一条要求","queued"),StoredSteering("two","r","第二条要求","cached"),StoredSteering("three","r","第三条要求","cached"))
  val state=UiState(screen=Screen.Chat,bot=Bot("b","Memoh"),session=Session("s","b"),connected=true,draft="第四条要求",steeringQueue=queue,
   runtime=RuntimeState("s","e",1,RuntimeRun("r","t",status="running"),false,true,true))
  var submitted=0
  compose.setContent { MemohTheme { MemohShell(state,UiActions(steer={ submitted++ })) } }
  compose.onNodeWithText("补充队列 · 3 条待处理").assertIsDisplayed()
  compose.onNodeWithText("第三条要求").performScrollTo().assertIsDisplayed()
  compose.onNodeWithContentDescription("提交补充内容").assertIsEnabled().performClick()
  compose.runOnIdle { assertEquals(1,submitted) }
  capture("steering-multiple.png")
 }
 @Test fun recoveredOldSessionKeepsLastReplyAndCanSend() {
  val state = UiState(screen=Screen.Chat,bot=Bot("b","Memoh"),session=Session("s","b",title="已有会话"),connected=true,draft="继续",
   history=listOf(ChatTurn("t","user","之前的问题"),ChatTurn("t","assistant","已保存的最后一条回复")),
   runtime=RuntimeState("s","",0,RuntimeRun("r","t",status="completed"),false,true))
  var sent = ""
  compose.setContent { MemohTheme { MemohShell(state,UiActions(send={ sent=it })) } }
  compose.waitForIdle()
  androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("已保存的最后一条回复"))
   .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
  compose.onNodeWithText("正在连接服务器…").assertDoesNotExist()
  compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
  compose.runOnIdle { assertEquals("继续",sent) }
  capture("old-session-recovered.png")
 }
 @Test fun activeComposerSwitchesBetweenSubmitAndStopWithReceipt() {
  var state by mutableStateOf(UiState(screen=Screen.Chat,bot=Bot("b","Memoh"),session=Session("s","b"),connected=true,draft="补充要求",
   runtime=RuntimeState("s","e",1,RuntimeRun("r","t",status="running"),false,true)))
  var submitted = 0
  compose.setContent { MemohTheme { MemohShell(state, UiActions(steer={ submitted++; state=state.copy(draft="",steering=StoredSteering("id","r","补充要求","queued")) })) } }
  compose.onNodeWithContentDescription("停止生成").assertDoesNotExist()
  compose.onNodeWithContentDescription("提交补充内容").assertIsEnabled()
  capture("steering-composer.png")
  compose.runOnIdle { state=state.copy(draft="") }
  compose.onNodeWithContentDescription("停止生成").assertIsEnabled()
  compose.onNodeWithContentDescription("提交补充内容").assertDoesNotExist()
  compose.runOnIdle { state=state.copy(draft="补充要求") }
  compose.onNodeWithContentDescription("提交补充内容").performClick()
  compose.runOnIdle { assertEquals(1,submitted) }
  compose.onNodeWithText("已排队，等待下一次执行间隙插入").assertIsDisplayed()
  compose.onNodeWithContentDescription("停止生成").assertIsEnabled()
  capture("steering-queued.png")
  compose.runOnIdle { state=state.copy(steering=state.steering!!.copy(status="applied"),runtime=state.runtime.copy(run=state.runtime.run!!.copy(status="completed")),draft="下一条消息") }
  compose.onNodeWithText("补充内容已插入").assertDoesNotExist()
  compose.onNodeWithText("补充要求").assertIsDisplayed()
  compose.onNodeWithContentDescription("发送").assertIsEnabled()
  compose.onNodeWithContentDescription("停止生成").assertDoesNotExist()
  capture("steering-completed.png")
 }
}
