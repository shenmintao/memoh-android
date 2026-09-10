package icu.minq.memoh.ui

import icu.minq.memoh.data.StoredSteering
import icu.minq.memoh.data.supplementedMessages
import icu.minq.memoh.model.*

/** A tool loop is many lazy items, even when the server stores it as one turn. */
internal sealed class ChatRow(val key: String, val contentType: String) {
    class Turn(key: String, val turn: ChatTurn) : ChatRow(key, turn.role)
    class Block(key: String, val block: MessageBlock, val turnKey: String, val streaming: Boolean) : ChatRow(key, block.type)
    class Copy(key: String, val blocks: List<MessageBlock>) : ChatRow(key, "copy")
    class Status(key: String, val label: String) : ChatRow(key, "status")
    class Error(key: String, val error: String) : ChatRow(key, "error")
    class Stopped(key: String) : ChatRow(key, "stopped")
}

internal fun chatRows(history: List<ChatTurn>, live: RuntimeRun?, supplements: List<StoredSteering>, terminal: RuntimeRun?): List<ChatRow> = buildList {
    fun assistant(key: String, turnKey: String, blocks: List<MessageBlock>, streaming: Boolean) {
        blocks.forEachIndexed { index, block ->
            add(ChatRow.Block("$key:block:${block.id}:${block.type}:$index", block, turnKey, streaming))
        }
        if (!streaming && blocks.any { it.type == "text" && it.content.isNotBlank() }) add(ChatRow.Copy("$key:copy", blocks))
    }
    history.forEachIndexed { index, turn ->
        val key = "history:${turn.id ?: turn.turnId}:${turn.role}:$index"
        if (turn.role == "assistant") assistant(key, turn.turnId.ifBlank { turn.id ?: key },
            turn.messages.ifEmpty { if (turn.text.isNotBlank()) listOf(MessageBlock(type = "text", content = turn.text)) else emptyList() }, false)
        else add(ChatRow.Turn(key, turn))
    }
    live?.let { run ->
        val key = "live:${run.run_id}"
        val turnKey = run.turn_id.ifBlank { key }
        run.request_user_turn?.let { add(ChatRow.Turn("$key:request", it)) }
        assistant(key, turnKey, supplementedMessages(run, supplements), !run.isTerminal())
        if (!run.isTerminal()) add(ChatRow.Status("$key:status", run.waitingLabel()))
        run.visibleError()?.let { error ->
            if (run.messages.none { it.type == "error" && it.content.trim() == error }) add(ChatRow.Error("$key:error", error))
        }
        if (run.status == "aborted") add(ChatRow.Stopped("$key:stopped"))
    }
    terminal?.let { run ->
        run.visibleError()?.let { error ->
            if (history.none { turn -> turn.messages.any { it.type == "error" && it.content.trim() == error } }) add(ChatRow.Error("terminal:error", error))
        }
        if (run.status == "aborted") add(ChatRow.Stopped("terminal:stopped"))
    }
}
