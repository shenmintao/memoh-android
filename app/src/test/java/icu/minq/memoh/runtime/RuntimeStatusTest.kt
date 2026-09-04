package icu.minq.memoh.runtime

import icu.minq.memoh.model.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeStatusTest {
    @Test fun `all terminal statuses are detected`() {
        listOf("completed", "aborted", "errored", "lost").forEach { assertTrue(RuntimeRun("r", "t", status = it).isTerminal()) }
        assertFalse(RuntimeRun("r", "t", status = "running").isTerminal())
    }

    @Test fun `waiting decision or pending approval is detected`() {
        assertTrue(RuntimeRun("r", "t", status = "waiting_decision").isWaitingApproval())
        val approval = Approval("approval-1", "pending", canApprove = true)
        assertTrue(RuntimeRun("r", "t", status = "running", messages = listOf(MessageBlock(1, "tool", approval = approval))).isWaitingApproval())
        assertFalse(RuntimeRun("r", "t", status = "running").isWaitingApproval())
    }
}
