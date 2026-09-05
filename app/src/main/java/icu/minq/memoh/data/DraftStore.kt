package icu.minq.memoh.data

/** Memory only: survives Activity recreation, but never writes prompts into saved state/backups. */
class DraftStore {
    private val drafts = linkedMapOf<String, String>()
    fun get(key: String): String = drafts[key].orEmpty()
    fun put(key: String, text: String) {
        if (text.isEmpty()) drafts.remove(key) else {
            drafts.remove(key)
            drafts[key] = text
            while (drafts.size > 32) drafts.remove(drafts.keys.first())
        }
    }
    fun queued(key: String, submitted: String) {
        if (get(key) == submitted) drafts.remove(key)
    }
    fun clear() = drafts.clear()
}
