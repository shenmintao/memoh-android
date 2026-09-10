package icu.minq.memoh.runtime

import icu.minq.memoh.model.*
import icu.minq.memoh.network.RuntimeReducer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RuntimeFeedbackTest {
 @org.junit.Test fun historyFailureExplainsRecoveryWithoutExposingInternalCode() {
  val expected="会话历史保存或同步失败，请刷新并核对最后一条回复后重试"
  org.junit.Assert.assertEquals(expected,icu.minq.memoh.model.RuntimeRun("r","t",status="errored",error="session_runtime.history_inconsistent").visibleError())
  org.junit.Assert.assertEquals(expected,icu.minq.memoh.model.RuntimeRun("r","t",status="errored",error_code="session_runtime.history_inconsistent").visibleError())
 }

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `successful terminal delta with empty error does not display a failure`() {
        val state = RuntimeState("s", "e", 1, RuntimeRun("r", "t", status = "running"), false)
        val patch = json.decodeFromString(RuntimeDelta.serializer(), """{"run":{"run_id":"r","status":"completed","error":"","error_code":""}}""")
        val completed = RuntimeReducer.delta(state, "s", "e", 2, patch).run!!
        assertTrue(completed.isTerminal())
        assertNull(completed.visibleError())
    }

    @Test fun `absent null empty and whitespace error fields are not failures`() {
        for (field in listOf("", """, "error":null""", """, "error":""""", """, "error":"  """")) {
            val payload = """{"run_id":"r","turn_id":"t","status":"completed"$field}"""
            assertNull(json.decodeFromString(RuntimeRun.serializer(), payload).visibleError())
        }
    }

    @Test fun `real failures remain visible even without a message`() {
        val run = RuntimeRun("r", "t", status = "errored")
        assertNotNull(run.visibleError())
        assertTrue(run.copy(error_code = "timeout").visibleError()!!.contains("timeout"))
        assertEquals("provider unavailable", run.copy(error = " provider unavailable ").visibleError())
        assertNotNull(run.copy(status = "lost").visibleError())
        assertNull(run.copy(status = "aborted", error = "").visibleError())
        assertEquals("warning", run.copy(status = "completed", error = "warning").visibleError())
    }
}
