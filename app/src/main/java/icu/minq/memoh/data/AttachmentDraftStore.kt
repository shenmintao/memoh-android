package icu.minq.memoh.data

import icu.minq.memoh.model.ChatAttachment

data class DraftAttachment(
    val id: String, val uri: String, val name: String = "文件", val size: Long = 0,
    val payload: ChatAttachment? = null, val error: String? = null,
) { val preparing get() = payload == null && error == null }

/** Memory only. Queue acknowledgements remove only the files that were actually submitted. */
class AttachmentDraftStore {
    private val drafts = mutableMapOf<String, List<DraftAttachment>>()
    fun get(key: String): List<DraftAttachment> = drafts[key].orEmpty()
    fun add(key: String, attachment: DraftAttachment): Boolean {
        val current = get(key)
        if (current.size >= MAX_FILES || current.any { it.uri == attachment.uri }) return false
        drafts[key] = current + attachment
        return true
    }
    fun update(key: String, attachment: DraftAttachment) {
        if (get(key).none { it.id == attachment.id }) return
        val others = get(key).filter { it.id != attachment.id }.sumOf { it.size }
        val allOthers = drafts.values.flatten().filter { it.id != attachment.id }.sumOf { it.size }
        val bounded = when {
            attachment.payload != null && others + attachment.size > MAX_BYTES -> attachment.copy(payload = null, size = 0, error = "附件合计不能超过 8 MB")
            attachment.payload != null && allOthers + attachment.size > MAX_TOTAL_BYTES -> attachment.copy(payload = null, size = 0, error = "附件草稿占用较多，请先发送或移除其他会话的附件")
            else -> attachment
        }
        drafts[key] = get(key).map { if (it.id == attachment.id) bounded else it }
    }
    fun remove(key: String, id: String) {
        val next = get(key).filter { it.id != id }
        if (next.isEmpty()) drafts.remove(key) else drafts[key] = next
    }
    fun queued(key: String, ids: Set<String>) { ids.forEach { remove(key, it) } }
    fun clear() = drafts.clear()
    companion object {
        const val MAX_FILES = 10
        const val MAX_BYTES = 8 * 1024 * 1024L
        private const val MAX_TOTAL_BYTES = 32 * 1024 * 1024L
    }
}
