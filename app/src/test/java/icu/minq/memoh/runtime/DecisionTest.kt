package icu.minq.memoh.runtime

import icu.minq.memoh.data.*
import icu.minq.memoh.model.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DecisionTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun `omitted capability permits pending decisions but explicit false and ended requests do not`() {
        val approval = json.decodeFromString(Approval.serializer(), """{"approval_id":"a","status":"pending"}""")
        assertTrue(approval.canDecide())
        assertFalse(approval.copy(canApprove = false).canDecide())
        assertFalse(approval.copy(status = "approved").canDecide())
        val input = json.decodeFromString(UserInput.serializer(), """{"user_input_id":"q","status":"pending","questions":[{"id":"q1","text":"Choice","kind":"single_select","options":[{"id":"o1","label":"One","description":"Details"}],"allow_custom":true,"required":false}]}""")
        assertTrue(input.canAnswer()); assertTrue(input.questions.single().allowCustom)
        assertFalse(input.questions.single().required); assertEquals("Details", input.questions.single().options.single().description)
        assertFalse(input.copy(canRespond = false).canAnswer())
    }
    @Test fun `pending queue only includes the active turn and live resolution replaces history`() {
        val block = MessageBlock(1, "tool", toolCallId = "tool", approval = Approval("a", "pending"))
        val history = listOf(ChatTurn("old", "assistant", messages = listOf(block.copy(id = 2, toolCallId = "old"))), ChatTurn("t", "assistant", messages = listOf(block)))
        val run = RuntimeRun("r", "t", status = "waiting_decision")
        assertEquals(listOf(block), run.decisionBlocks(history))
        assertTrue(run.copy(messages = listOf(block.copy(approval = block.approval!!.copy(status = "approved")))).decisionBlocks(history).isEmpty())
        assertTrue(run.copy(status = "completed").decisionBlocks(history).isEmpty())
        assertTrue(UiState(runtime = RuntimeState(run = run), history = history, resolvedDecisions = setOf("approval:a")).pendingDecisions().isEmpty())
    }
    @Test fun `unknown option kinds cannot be interpreted as approval`() {
        assertTrue(ApprovalOption("once", kind = " allow_once ").approves())
        assertFalse(ApprovalOption("unknown", name = "Allow", kind = "new_kind").approves())
        assertFalse(ApprovalOption("reject", kind = "reject_once").approves())
    }
    private val choice = UserQuestion("q", "选择", "single_select", listOf(UserOption("a", "A"), UserOption("b", "B")), allowCustom = true)
    @Test fun `answers respect required optional custom and selection rules`() {
        val input = UserInput("i", "pending", listOf(choice, UserQuestion("note", "备注", "text", required = false)))
        val skip = UserAnswer("note", skipped = true)
        assertTrue(input.accepts(listOf(UserAnswer("q", optionIds = listOf("a")), skip)))
        assertTrue(input.accepts(listOf(UserAnswer("q", customText = "custom"), skip)))
        assertFalse(input.accepts(listOf(UserAnswer("q", optionIds = listOf("a", "b")), skip)))
        assertFalse(input.accepts(listOf(UserAnswer("q", optionIds = listOf("a"), customText = "both"), skip)))
        assertFalse(input.accepts(listOf(UserAnswer("q", optionIds = listOf("unknown")), skip)))
        assertFalse(input.accepts(listOf(UserAnswer("q", skipped = true), skip)))
        assertFalse(input.accepts(listOf(skip, skip)))
        assertFalse(input.accepts(listOf(UserAnswer("q", optionIds = listOf("a")))))
    }
    @Test fun `multi select custom exclusivity and text answers are validated`() {
        val input = UserInput("i", "pending", listOf(choice.copy(kind = "multi_select")))
        val combined = listOf(UserAnswer("q", optionIds = listOf("a", "b"), customText = "extra"))
        assertTrue(input.accepts(combined))
        assertFalse(input.copy(questions = listOf(choice.copy(kind = "multi_select", customExclusive = true))).accepts(combined))
        assertFalse(input.accepts(listOf(UserAnswer("q", optionIds = listOf("a", "a")))))
        val text = input.copy(questions = listOf(UserQuestion("q", "说明", "text")))
        assertFalse(text.accepts(listOf(UserAnswer("q", text = "  "))))
        assertTrue(text.accepts(listOf(UserAnswer("q", text = "answer"))))
    }
    @Test fun `confirmed receipt survives a stale pending snapshot and is scoped to its run`() {
        val run = RuntimeRun("r", "t", status = "waiting_decision", messages = listOf(MessageBlock(1, "tool", approval = Approval("a", "pending"))))
        val receipt = DecisionReceipt("r", "a", "approval", "approved", "once")
        assertTrue(receipt.unresolved(run))
        assertEquals("approved", receipt.apply(run)!!.messages.single().approval!!.status)
        assertFalse(receipt.unresolved(receipt.apply(run)))
        assertFalse(receipt.unresolved(run.copy(status = "completed")))
        assertEquals(run.copy(run_id = "other"), receipt.apply(run.copy(run_id = "other")))
        assertEquals("等待你的回答", run.copy(messages = listOf(MessageBlock(1, "tool", userInput = UserInput("i", "pending")))).waitingLabel())
    }
    @Test fun `decision notifications exclude completed and explicitly unavailable requests`() {
        val block = MessageBlock(1, "tool", approval = Approval("a", "pending", canApprove = false))
        val run = RuntimeRun("r", "t", status = "waiting_decision", messages = listOf(block))
        assertTrue(icu.minq.memoh.service.ReplyNotificationPolicy.decisionIds(run).isEmpty())
        val pending = run.copy(messages = listOf(block.copy(approval = block.approval!!.copy(canApprove = true))))
        assertEquals(setOf("approval:a"), icu.minq.memoh.service.ReplyNotificationPolicy.decisionIds(pending))
        assertTrue(icu.minq.memoh.service.ReplyNotificationPolicy.decisionIds(pending.copy(status = "completed")).isEmpty())
    }

}
