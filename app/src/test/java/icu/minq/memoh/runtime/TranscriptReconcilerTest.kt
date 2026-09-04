package icu.minq.memoh.runtime

import icu.minq.memoh.data.reconciledHistory
import icu.minq.memoh.model.ChatTurn
import icu.minq.memoh.model.RuntimeRun
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptReconcilerTest {
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
