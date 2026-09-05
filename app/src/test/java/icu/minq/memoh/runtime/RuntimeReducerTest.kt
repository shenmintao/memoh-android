package icu.minq.memoh.runtime

import icu.minq.memoh.model.*
import icu.minq.memoh.network.RuntimeReducer
import org.junit.Assert.*
import org.junit.Test

class RuntimeReducerTest {
    private val run = RuntimeRun("run-1", "turn-1", status = "running")
    private val snapshot = RuntimeSnapshot(session_id = "session-1", epoch = "epoch-1", seq = 4, current_run_view = run)

    @Test fun `authoritative snapshot replaces projection`() {
        val result = RuntimeReducer.snapshot(RuntimeState(), "session-1", "epoch-1", 4, snapshot)
        assertFalse(result.needsSnapshot)
        assertEquals(4, result.seq)
        assertEquals("run-1", result.run?.run_id)
    }

    @Test fun `delta appends text and upserts blocks`() {
        val initial = RuntimeReducer.snapshot(RuntimeState(), "session-1", "epoch-1", 4, snapshot.copy(current_run_view = run.copy(messages = listOf(MessageBlock(1, "text", "你")))))
        val delta = RuntimeDelta(message_appends = listOf(MessageAppend(1, "text", "好")), message_upserts = listOf(MessageBlock(2, "notice", "提示")))
        val result = RuntimeReducer.delta(initial, "session-1", "epoch-1", 5, delta)
        assertEquals("你好", result.run?.messages?.first()?.content)
        assertEquals("notice", result.run?.messages?.last()?.type)
        assertFalse(result.needsSnapshot)
    }

    @Test fun `tool progress and changed projection id upsert preserve one tool block`() {
        val tool = MessageBlock(id = 4, type = "tool", toolCallId = "call-1")
        val initial = RuntimeReducer.snapshot(RuntimeState(), "session-1", "epoch-1", 4, snapshot.copy(current_run_view = run.copy(messages = listOf(tool))))
        val progressed = RuntimeReducer.delta(initial, "session-1", "epoch-1", 5, RuntimeDelta(
            progress_appends = listOf(ProgressAppend(4, kotlinx.serialization.json.JsonPrimitive("half"), kotlinx.serialization.json.buildJsonObject { put("path", kotlinx.serialization.json.JsonPrimitive("remote")) })),
        ))
        assertEquals("half", progressed.run?.messages?.single()?.progress?.single()?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertNotNull(progressed.run?.messages?.single()?.input)

        val result = RuntimeReducer.delta(progressed, "session-1", "epoch-1", 6, RuntimeDelta(
            message_upserts = listOf(MessageBlock(id = 7, type = "tool", toolCallId = "call-1", name = "updated")),
        ))
        assertEquals(1, result.run?.messages?.size)
        assertEquals(4, result.run?.messages?.single()?.id)
        assertEquals("updated", result.run?.messages?.single()?.name)
    }

    @Test fun `sequence gap requires snapshot recovery without applying delta`() {
        val initial = RuntimeReducer.snapshot(RuntimeState(), "session-1", "epoch-1", 4, snapshot)
        val result = RuntimeReducer.delta(initial, "session-1", "epoch-1", 7, RuntimeDelta(run = RunPatch("run-1", status = "completed")))
        assertTrue(result.needsSnapshot)
        assertEquals("running", result.run?.status)
        assertEquals(4, result.seq)
    }

    @Test fun `epoch mismatch requires snapshot recovery`() {
        val initial = RuntimeReducer.snapshot(RuntimeState(), "session-1", "epoch-1", 4, snapshot)
        assertTrue(RuntimeReducer.delta(initial, "session-1", "epoch-2", 5, RuntimeDelta()).needsSnapshot)
    }
    @Test fun `older snapshot cannot reopen a completed run but a new epoch can replace it`() {
        val initial = RuntimeReducer.snapshot(RuntimeState(), "session-1", "epoch-1", 4, snapshot)
        val completed = RuntimeReducer.delta(initial, "session-1", "epoch-1", 5, RuntimeDelta(run = RunPatch("run-1", status = "completed")))
        assertEquals(completed, RuntimeReducer.snapshot(completed, "session-1", "epoch-1", 4, snapshot))
        val restarted = RuntimeReducer.snapshot(completed, "session-1", "epoch-2", 0, snapshot.copy(epoch = "epoch-2", seq = 0))
        assertEquals("running", restarted.run?.status)
        assertEquals("epoch-2", restarted.epoch)
    }

}
