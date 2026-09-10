package icu.minq.memoh.runtime

import icu.minq.memoh.data.reconciledHistory
import icu.minq.memoh.model.ChatTurn
import icu.minq.memoh.model.MessageBlock
import icu.minq.memoh.model.RuntimeRun
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptReconcilerTest {
    @Test fun `ledger only terminal projection preserves last saved reply`() {
        val history = listOf(ChatTurn("t", "user", "question"), ChatTurn("t", "assistant", "saved reply"))
        for (status in listOf("completed", "errored", "aborted", "lost")) {
            assertEquals(history, reconciledHistory(history, RuntimeRun("r", "t", status = status)))
        }
    }

    @Test fun `terminal projection with actual messages still replaces saved twin`() {
        val history = listOf(ChatTurn("t", "user", "question"), ChatTurn("t", "assistant", "saved reply"))
        val run = RuntimeRun("r", "t", status = "completed", messages = listOf(MessageBlock(type = "text", content = "reply")))
        assertEquals(listOf(history.first()), reconciledHistory(history, run))
    }

    @Test fun `active runtime turn replaces settled user and assistant twins`() {
        val history = listOf(
            ChatTurn(turnId = "turn-1", role = "user", text = "hello"),
            ChatTurn(turnId = "turn-1", role = "assistant"),
            ChatTurn(turnId = "turn-0", role = "assistant"),
        )
        val run = RuntimeRun(
            run_id = "run-1",
            turn_id = "turn-1",
            status = "waiting_decision",
            request_user_turn = history.first(),
        )

        val result = reconciledHistory(history, run)
        assertEquals(listOf("turn-0:assistant"), result.map { "${it.turnId}:${it.role}" })
    }
}
