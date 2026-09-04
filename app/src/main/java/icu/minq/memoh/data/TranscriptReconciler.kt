package icu.minq.memoh.data

import icu.minq.memoh.model.ChatTurn
import icu.minq.memoh.model.RuntimeRun

/** Live runtime projection owns the active turn; settled twins must not render beside it. */
fun reconciledHistory(history: List<ChatTurn>, run: RuntimeRun?): List<ChatTurn> {
    val turnId = run?.turn_id?.trim().orEmpty()
    if (turnId.isEmpty()) return history
    return history.filterNot { turn ->
        turn.turnId == turnId && (turn.role == "assistant" || (turn.role == "user" && run?.request_user_turn != null))
    }
}
