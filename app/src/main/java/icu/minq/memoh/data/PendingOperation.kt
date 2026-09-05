package icu.minq.memoh.data

import icu.minq.memoh.model.RuntimeRun
import icu.minq.memoh.model.isTerminal
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable enum class PendingPhase { WAITING, ACCEPTED, UNKNOWN, COMPLETED, FAILED }
@Serializable data class PendingOperation(
    val botId: String,
    val sessionId: String,
    val invocationId: String,
    val accountKey: String = "",
    val createdAt: Long = 0,
    val phase: PendingPhase = PendingPhase.WAITING,
    val runId: String = "",
    val turnId: String = "",
) {
    val terminal: Boolean get() = phase == PendingPhase.COMPLETED || phase == PendingPhase.FAILED
    val monitoring: Boolean get() = phase == PendingPhase.WAITING || phase == PendingPhase.ACCEPTED
    val blocksSend: Boolean get() = !terminal
    fun sameIdentity(other: PendingOperation?) = other != null &&
        invocationId == other.invocationId && botId == other.botId && sessionId == other.sessionId && accountKey == other.accountKey
}

object PendingPolicy {
    const val ADMISSION_TIMEOUT_MS = 30_000L
    const val MONITOR_TIMEOUT_MS = 30 * 60_000L
    const val OFFLINE_TIMEOUT_MS = 2 * 60_000L

    fun accountKey(apiBase: String, userId: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$apiBase\u0000$userId".toByteArray()).joinToString("") { "%02x".format(it) }

    fun timedOut(value: PendingOperation, now: Long, disconnectedAt: Long? = null): Boolean {
        if (!value.monitoring) return false
        val age = now - value.createdAt
        if (value.createdAt <= 0 || age < 0 || age >= MONITOR_TIMEOUT_MS) return true
        if (value.phase == PendingPhase.WAITING && age >= ADMISSION_TIMEOUT_MS) return true
        return disconnectedAt != null && now - disconnectedAt >= OFFLINE_TIMEOUT_MS
    }

    fun observe(value: PendingOperation, run: RuntimeRun?): PendingOperation {
        if (value.terminal || run == null || run.invocation_id != value.invocationId) return value
        val phase = when {
            run.isTerminal() -> if (run.status == "completed") PendingPhase.COMPLETED else PendingPhase.FAILED
            value.phase == PendingPhase.UNKNOWN -> PendingPhase.UNKNOWN // Only a visible user action restarts monitoring.
            else -> PendingPhase.ACCEPTED
        }
        return value.copy(phase = phase, runId = run.run_id, turnId = run.turn_id)
    }
}
