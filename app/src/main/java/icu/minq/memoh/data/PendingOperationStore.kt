package icu.minq.memoh.data

import android.content.Context
import icu.minq.memoh.model.RuntimeRun
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** One process-wide observable record. It never contains auth or message text. */
class PendingOperationStore(context: Context, storageName: String = "pending_reply") {
    private val prefs = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow(load())
    val changes = mutable.asStateFlow()

    private fun load(): PendingOperation? {
        prefs.getString("operation", null)?.let { encoded ->
            return runCatching { json.decodeFromString(PendingOperation.serializer(), encoded) }.getOrNull()
        }
        // Legacy records cannot safely be attributed to the next account. They require user acknowledgement.
        val bot = prefs.getString("bot", null) ?: return null
        val session = prefs.getString("session", null) ?: return null
        val invocation = prefs.getString("invocation", null) ?: return null
        return PendingOperation(bot, session, invocation, phase = PendingPhase.UNKNOWN)
    }

    @Synchronized fun read(): PendingOperation? = mutable.value
    @Synchronized fun start(value: PendingOperation): Boolean {
        if (mutable.value?.blocksSend == true) return false
        save(value)
        return true
    }
    @Synchronized fun update(expected: PendingOperation, transform: (PendingOperation) -> PendingOperation): Boolean {
        val current = mutable.value ?: return false
        if (!expected.sameIdentity(current)) return false
        save(transform(current))
        return true
    }
    @Synchronized fun observe(accountKey: String, botId: String, sessionId: String, run: RuntimeRun?) {
        val current = mutable.value ?: return
        if (current.accountKey != accountKey || current.botId != botId || current.sessionId != sessionId) return
        val next = PendingPolicy.observe(current, run)
        if (next != current) save(next)
    }
    @Synchronized fun clearIfMatches(expected: PendingOperation): Boolean {
        if (!expected.sameIdentity(mutable.value)) return false
        save(null)
        return true
    }
    @Synchronized fun clear() = save(null)

    private fun save(value: PendingOperation?) {
        val edit = prefs.edit().clear()
        if (value != null) edit.putString("operation", json.encodeToString(PendingOperation.serializer(), value))
        // The small identity record must survive a process kill immediately after send admission.
        check(edit.commit()) { "无法保存待处理状态" }
        mutable.value = value
    }
}
