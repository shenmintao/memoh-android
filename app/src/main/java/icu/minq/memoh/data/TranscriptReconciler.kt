package icu.minq.memoh.data

import icu.minq.memoh.model.ChatTurn
import icu.minq.memoh.model.RuntimeRun
import icu.minq.memoh.model.MessageBlock
import icu.minq.memoh.model.isTerminal

/** Live runtime projection owns the active turn; settled twins must not render beside it. */
fun reconciledHistory(history: List<ChatTurn>, run: RuntimeRun?): List<ChatTurn> {
    val turnId = run?.turn_id?.trim().orEmpty()
    if (turnId.isEmpty()) return history
    return history.filterNot { turn ->
        (turn.turnId == turnId || (turn.runId.isNotBlank() && turn.runId == run?.run_id)) && ((turn.role == "assistant" && (run?.isTerminal() == false || run?.messages?.isNotEmpty() == true)) ||
            (turn.role == "user" && run?.request_user_turn != null))
    }
}

/** Persisted turns retain the user/assistant boundaries within a single run. */
fun displayedRun(history: List<ChatTurn>, run: RuntimeRun?): RuntimeRun? = run?.takeUnless { current ->
    if (!current.isTerminal()) return@takeUnless false
    val assistants = history.filter { (it.turnId == current.turn_id || (it.runId.isNotBlank() && it.runId == current.run_id)) && it.role == "assistant" }
    val saved = assistants.flatMap { turn -> turn.messages.ifEmpty { listOf(MessageBlock(type = "text", content = turn.text)) } }
    val savedText = saved.filter { it.type == "text" || it.type == "error" }.map { it.type to it.content.trim() }.toHashSet()
    val savedTools = saved.filter { it.type == "tool" }.map { it.toolCallId }.toHashSet()
    assistants.isNotEmpty() && current.messages.all { block ->
        when (block.type) {
            "text", "error" -> (block.type to block.content.trim()) in savedText
            "tool" -> block.toolCallId in savedTools
            else -> true
        }
    }
}

/** A consumption receipt is the authority; local submission alone is not a user turn. */
fun supplementedMessages(run: RuntimeRun, stored: List<StoredSteering>): List<MessageBlock> {
    val receipts = (run.steer_queue + listOfNotNull(run.steer)).distinctBy { it.id }
    val supplements = (receipts.map { receipt ->
        val local = stored.firstOrNull { it.runId == run.run_id && it.id == receipt.id }
        StoredSteering(receipt.id, run.run_id, receipt.text.ifBlank { local?.text.orEmpty() },
            if (local?.settled == true) local.status else if (receipt.error == "steer_status_unknown") "unknown" else receipt.status, run.turn_id,
            receipt.after_message_id ?: local?.afterMessageId)
    } + stored.filter { it.runId == run.run_id && receipts.none { receipt -> receipt.id == it.id } })
        .filter { it.status == "applied" && it.text.isNotBlank() }
    val blocks = run.messages.sortedBy { it.id }
    val pending = supplements.mapIndexed { index, supplement ->
        (supplement.afterMessageId ?: (blocks.maxOfOrNull { it.id } ?: -1)) to
            MessageBlock(id = -1000 - index, type = "user_message", content = supplement.text)
    }.toMutableList()
    return buildList {
        for (block in blocks) {
            val before = pending.filter { it.first < block.id }
            addAll(before.map { it.second }); pending.removeAll(before.toSet())
            add(block)
        }
        addAll(pending.map { it.second })
    }
}

/** Remove receipts only after their text is present in durable user turns.
 * A batch is stored as joined text. Repeated identical submissions are counted,
 * and the initial user message cannot accidentally acknowledge a supplement.
 */
fun confirmedSupplementIds(history: List<ChatTurn>, stored: List<StoredSteering>): Set<String> = buildSet {
    for ((runId, records) in stored.filter { it.status == "applied" && it.turnId.isNotBlank() }.groupBy { it.runId }) {
        val remaining = records.toMutableList()
        val initialTurnId = records.first().turnId
        val users = history.filter { (it.runId == runId || it.turnId == initialTurnId) && it.role == "user" }
        val supplements = if (users.firstOrNull()?.turnId == initialTurnId) users.drop(1) else users
        for (turn in supplements) {
            var consumed = 0
            for (count in 1..remaining.size) {
                if (remaining.take(count).joinToString("\n\n") { it.text.trim() } == turn.text.trim()) { consumed = count; break }
            }
            if (consumed > 0) {
                addAll(remaining.take(consumed).map { it.id })
                repeat(consumed) { remaining.removeAt(0) }
            }
        }
    }
}
