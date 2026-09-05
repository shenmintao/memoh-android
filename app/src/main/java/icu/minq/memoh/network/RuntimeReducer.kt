package icu.minq.memoh.network

import icu.minq.memoh.model.MessageBlock
import icu.minq.memoh.model.RuntimeDelta
import icu.minq.memoh.model.RuntimeSnapshot
import icu.minq.memoh.model.RuntimeState

object RuntimeReducer {
    fun snapshot(state: RuntimeState, eventSessionId: String, eventEpoch: String, eventSeq: Long, snapshot: RuntimeSnapshot): RuntimeState {
        if (snapshot.session_id != eventSessionId || snapshot.epoch != eventEpoch || snapshot.seq != eventSeq) return state.copy(needsSnapshot = true)
        // Re-subscriptions may overlap; an older snapshot must not resurrect a settled decision/run.
        if (state.sessionId == eventSessionId && state.epoch == eventEpoch && eventSeq < state.seq) return state
        return RuntimeState(eventSessionId, eventEpoch, eventSeq, snapshot.current_run_view, false)
    }

    fun delta(state: RuntimeState, sessionId: String, epoch: String, seq: Long, delta: RuntimeDelta): RuntimeState {
        if (state.needsSnapshot || state.sessionId != sessionId || state.epoch != epoch || seq != state.seq + 1) {
            return state.copy(needsSnapshot = true)
        }
        delta.current_run_view?.let { return state.copy(seq = seq, run = it, needsSnapshot = false) }
        var run = state.run
        val patch = delta.run
        if (patch != null) {
            if (run == null || patch.run_id != run.run_id) return state.copy(needsSnapshot = true)
            run = run.copy(status = patch.status ?: run.status, error_code = patch.error_code ?: run.error_code, error = patch.error ?: run.error, updated_at = patch.updated_at ?: run.updated_at)
        }
        if (run != null) {
            var messages = if (delta.reset_messages) emptyList() else run.messages
            delta.message_appends.forEach { append ->
                val index = messages.indexOfFirst { it.id == append.id }
                if (index >= 0 && messages[index].type == append.type) {
                    messages = messages.toMutableList().also { list -> list[index] = list[index].copy(content = list[index].content + append.content) }
                } else if (index < 0) {
                    messages = messages + MessageBlock(id = append.id, type = append.type, content = append.content)
                }
            }
            delta.progress_appends.forEach { append ->
                val index = messages.indexOfFirst { it.id == append.id && it.type == "tool" }
                if (index >= 0) {
                    val current = messages[index]
                    messages = messages.toMutableList().also { list ->
                        list[index] = current.copy(
                            input = append.input ?: current.input,
                            progress = current.progress + append.progress,
                        )
                    }
                }
            }
            delta.message_upserts.forEach { upsert ->
                val toolCallId = upsert.toolCallId.trim()
                val index = messages.indexOfFirst {
                    it.id == upsert.id || (toolCallId.isNotEmpty() && it.type == "tool" && it.toolCallId.trim() == toolCallId)
                }
                messages = if (index >= 0) messages.toMutableList().also { list ->
                    list[index] = upsert.copy(id = list[index].id)
                } else messages + upsert
            }
            run = run.copy(messages = messages.sortedBy { it.id })
        }
        return state.copy(seq = seq, run = run, needsSnapshot = false)
    }
}
