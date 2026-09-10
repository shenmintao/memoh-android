package icu.minq.memoh.data

import icu.minq.memoh.model.RuntimeRun
import icu.minq.memoh.model.isTerminal
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Serializable data class StoredSteering(val id: String, val runId: String, val text: String, val status: String = "pending", val turnId: String = "", val afterMessageId: Int? = null) {
    val inFlight get() = status in setOf("pending", "cached", "queued", "unknown")
    val settled get() = status in setOf("applied", "rejected")
    fun observe(run: RuntimeRun?): StoredSteering {
        // A confirmed consumption receipt survives stale snapshots and a later run.
        val receipt = run?.takeIf { it.run_id == runId }?.let { it.steer_queue.firstOrNull { item -> item.id == id } ?: it.steer?.takeIf { item -> item.id == id } }
        val located = if (run?.run_id == runId) copy(turnId = run.turn_id, afterMessageId = receipt?.after_message_id ?: afterMessageId) else this
        if (settled) return located
        return when {
            receipt != null && receipt.status in setOf("applied", "rejected") -> located.copy(status = if (receipt.error == "steer_status_unknown") "unknown" else receipt.status)
            inFlight && (run == null || run.run_id != runId || run.isTerminal()) -> located.copy(status = "unknown")
            receipt != null -> located.copy(status = if (receipt.status == "pending") "cached" else receipt.status)
            else -> located
        }
    }
    fun label() = when (status) {
        "pending" -> "补充内容已暂存，正在提交…"
        "cached" -> "已缓存，等待一并插入"
        "queued" -> "已排队，等待下一次执行间隙插入"
        "applied" -> "补充内容已插入"
        "rejected" -> "补充内容未插入，可取回修改"
        else -> "插入状态待核对，内容已保留"
    }
}
fun steeringKey(accountKey: String, botId: String, sessionId: String) = "$accountKey:$botId:$sessionId"

/** Both the foreground chat and its read-only monitor share this store. */
fun SteeringStore.observe(key: String, run: RuntimeRun?): StoredSteering? = synchronized(this) {
    val previous = readQueue(key)
    val next = previous.map { it.observe(run) }
    if (next != previous) writeQueue(key, next)
    next.lastOrNull()
}

interface SteeringStore {
    val changes: StateFlow<Long>
    fun readQueue(key: String): List<StoredSteering>
    fun writeQueue(key: String, value: List<StoredSteering>)
    fun read(key: String): StoredSteering? = readQueue(key).lastOrNull()
    fun write(key: String, value: StoredSteering?) = writeQueue(key, listOfNotNull(value))
}
class MemorySteeringStore : SteeringStore {
    private val values = mutableMapOf<String, List<StoredSteering>>()
    private val revision = MutableStateFlow(0L)
    override val changes = revision.asStateFlow()
    @Synchronized override fun readQueue(key: String) = values[key].orEmpty()
    @Synchronized override fun writeQueue(key: String, value: List<StoredSteering>) {
        if (values[key] == value) return
        if (value.isEmpty()) values.remove(key) else values[key] = value
        revision.value++
    }
}
