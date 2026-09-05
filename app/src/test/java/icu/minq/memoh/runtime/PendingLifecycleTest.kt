package icu.minq.memoh.runtime

import icu.minq.memoh.data.*
import icu.minq.memoh.model.*
import icu.minq.memoh.service.ReplyNotificationPolicy
import org.junit.Assert.*
import org.junit.Test

class PendingLifecycleTest {
    private val pending = PendingOperation("b", "s", "i", "account", 100_000)
    @Test fun `acceptance timeout becomes uncertain not proof of failure`() {
        assertFalse(PendingPolicy.timedOut(pending, 129_999))
        assertTrue(PendingPolicy.timedOut(pending, 130_000))
        assertTrue(pending.copy(phase = PendingPhase.UNKNOWN).blocksSend)
        assertFalse(pending.copy(phase = PendingPhase.UNKNOWN).monitoring)
    }
    @Test fun `accepted operations still have an absolute and offline deadline`() {
        val accepted = pending.copy(phase = PendingPhase.ACCEPTED)
        assertFalse(PendingPolicy.timedOut(accepted, 140_000))
        assertTrue(PendingPolicy.timedOut(accepted, 100_000 + PendingPolicy.MONITOR_TIMEOUT_MS))
        assertTrue(PendingPolicy.timedOut(accepted, 250_000, disconnectedAt = 100_001))
    }
    @Test fun `invalid and future timestamps never monitor forever`() {
        assertTrue(PendingPolicy.timedOut(pending.copy(createdAt = 0), 100_000))
        assertTrue(PendingPolicy.timedOut(pending, 99_000))
    }
    @Test fun `only matching invocation and account may settle a pending record`() {
        val other = RuntimeRun("r", "t", invocation_id = "other", status = "completed")
        assertEquals(pending, PendingPolicy.observe(pending, other))
        assertFalse(pending.sameIdentity(pending.copy(accountKey = "another-account")))
        val result = PendingPolicy.observe(pending, other.copy(invocation_id = "i"))
        assertTrue(result.terminal)
        assertFalse(result.blocksSend)
    }
    @Test fun `unknown can settle but will not silently start monitoring again`() {
        val uncertain = pending.copy(phase = PendingPhase.UNKNOWN)
        val running = RuntimeRun("r", "t", invocation_id = "i", status = "running")
        assertEquals(PendingPhase.UNKNOWN, PendingPolicy.observe(uncertain, running).phase)
        assertEquals(PendingPhase.COMPLETED, PendingPolicy.observe(uncertain, running.copy(status = "completed")).phase)
    }
    @Test fun `notification text is local and generic`() {
        PendingPhase.entries.forEach { phase ->
            val text = ReplyNotificationPolicy.result(phase)
            assertFalse(text.contains("token")); assertFalse(text.contains("/private/"))
        }
        assertEquals("Memoh 回复出错或已停止，请打开应用查看", ReplyNotificationPolicy.result(PendingPhase.FAILED))
    }
    @Test fun `sequential approval ids generate distinct notifications`() {
        val first = RuntimeRun("r", "t", status = "waiting_decision", messages = listOf(MessageBlock(type = "tool", approval = Approval("a1", "pending", true))))
        val second = first.copy(messages = listOf(MessageBlock(type = "tool", approval = Approval("a2", "pending", true))))
        val seen = ReplyNotificationPolicy.decisionIds(first)
        assertEquals(setOf("approval:a2"), ReplyNotificationPolicy.decisionIds(second) - seen)
        assertTrue((ReplyNotificationPolicy.decisionIds(first) - seen).isEmpty())
    }
}
