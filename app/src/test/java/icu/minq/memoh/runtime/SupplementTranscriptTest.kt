package icu.minq.memoh.runtime

import icu.minq.memoh.data.*
import icu.minq.memoh.model.*
import org.junit.Assert.*
import org.junit.Test

class SupplementTranscriptTest {
    private val blocks = listOf(MessageBlock(0, "text", "before"), MessageBlock(1, "tool", name = "exec", toolCallId = "call"), MessageBlock(2, "text", "after"))
    private val queue = listOf(SteerState("a", "applied", "one", after_message_id = 1), SteerState("b", "applied", "two", after_message_id = 1), SteerState("c", "queued", "waiting"))
    private val run = RuntimeRun("r", "t", status = "running", messages = blocks, request_user_turn = ChatTurn("t", "user", "start"), steer_queue = queue)

    @Test fun consumedBatchAppearsInOrderBeforeSubsequentOutputAndPendingStaysOut() {
        val projected = supplementedMessages(run, emptyList())
        assertEquals(listOf("before", "", "one", "two", "after"), projected.map { it.content })
        assertEquals(listOf("text", "tool", "user_message", "user_message", "text"), projected.map { it.type })
        assertEquals(projected.size, projected.map { it.id }.toSet().size)
        assertEquals(projected, supplementedMessages(run.copy(steer = queue[1]), listOf(StoredSteering("a", "r", "one", "applied"))))
    }

    @Test fun rejectedAndUnknownReceiptsNeverBecomeUserMessages() {
        val pending = run.copy(steer_queue = listOf(SteerState("a", "rejected", "rejected"), SteerState("b", "rejected", "uncertain", "steer_status_unknown")))
        assertEquals(blocks, supplementedMessages(pending, emptyList()))
    }

    @Test fun receiptLocationSurvivesBackgroundSnapshotAndStaleReceipt() {
        val local = StoredSteering("a", "r", "one", afterMessageId = -1).observe(run)
        assertEquals(1, local.afterMessageId)
        assertEquals("t", local.turnId)
        val stale = run.copy(steer_queue = listOf(queue.first().copy(status = "queued")))
        assertEquals("applied", local.observe(stale).status)
        assertEquals(1, supplementedMessages(stale, listOf(local)).count { it.type == "user_message" })
    }

    @Test fun durableHistoryOwnsAllTurnsInCompletedRunWithoutHidingSupplement() {
        val history = listOf(ChatTurn("t", "user", "start", runId = "r"),
            ChatTurn("t", "assistant", messages = blocks.take(2), runId = "r"),
            ChatTurn("inserted", "user", "one\n\ntwo", runId = "r"),
            ChatTurn("inserted", "assistant", messages = listOf(blocks.last()), runId = "r"))
        assertTrue(reconciledHistory(history, run).isEmpty())
        assertNull(displayedRun(history, run.copy(status = "completed")))
        assertEquals(history, reconciledHistory(history, displayedRun(history, run.copy(status = "completed"))))
        assertNotNull(displayedRun(history.dropLast(1), run.copy(status = "completed")))
        assertNotNull(displayedRun(history, run))
    }

    @Test fun cleanupWaitsForDurableBatchAndCountsIdenticalMessages() {
        val stored = listOf(StoredSteering("a", "r", "same", "applied", "t"), StoredSteering("b", "r", "same", "applied", "t"), StoredSteering("c", "r", "later", "queued", "t"))
        val original = ChatTurn("t", "user", "same", runId = "r")
        assertTrue(confirmedSupplementIds(listOf(original), stored).isEmpty())
        assertEquals(setOf("a", "b"), confirmedSupplementIds(listOf(original, ChatTurn("next", "user", "same\n\nsame", runId = "r")), stored))
        assertEquals(setOf("a"), confirmedSupplementIds(listOf(original, ChatTurn("next", "user", "same", runId = "r")), stored))
        assertTrue(confirmedSupplementIds(listOf(ChatTurn("unrelated", "user", "same", runId = "other")), stored).isEmpty())
    }
}
