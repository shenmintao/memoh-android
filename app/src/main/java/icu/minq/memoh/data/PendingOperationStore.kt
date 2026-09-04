package icu.minq.memoh.data

import android.content.Context

data class PendingOperation(val botId: String, val sessionId: String, val invocationId: String)

class PendingOperationStore(context: Context) {
    private val prefs = context.getSharedPreferences("pending_reply", Context.MODE_PRIVATE)
    fun write(value: PendingOperation) = prefs.edit().putString("bot", value.botId).putString("session", value.sessionId).putString("invocation", value.invocationId).apply()
    fun read(): PendingOperation? {
        val bot = prefs.getString("bot", null) ?: return null
        val session = prefs.getString("session", null) ?: return null
        val invocation = prefs.getString("invocation", null) ?: return null
        return PendingOperation(bot, session, invocation)
    }
    @Synchronized fun clearIfMatches(invocationId: String): Boolean {
        val current = read() ?: return false
        if (current.invocationId != invocationId) return false
        prefs.edit().clear().commit()
        return true
    }
    fun clear() = prefs.edit().clear().apply()
}
