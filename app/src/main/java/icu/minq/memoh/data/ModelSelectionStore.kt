package icu.minq.memoh.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable data class SavedModelSelection(val modelId: String = "", val reasoningEffort: String = "")

interface ModelSelectionStore {
    fun read(key: String): SavedModelSelection?
    fun write(key: String, selection: SavedModelSelection)
}

class MemoryModelSelectionStore : ModelSelectionStore {
    private val values = mutableMapOf<String, SavedModelSelection>()
    override fun read(key: String) = values[key]
    override fun write(key: String, selection: SavedModelSelection) { values[key] = selection }
}

/** Only model IDs and effort tokens. No credentials, messages or attachment contents. */
class PreferencesModelSelectionStore(context: Context, storageName: String = "model_selections_v1") : ModelSelectionStore {
    private val prefs = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private fun entryKey(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    @Synchronized override fun read(key: String): SavedModelSelection? {
        val data = prefs.getString(entryKey(key), null) ?: return null
        return runCatching { json.decodeFromString(SavedModelSelection.serializer(), data) }.getOrNull()
    }

    @Synchronized override fun write(key: String, selection: SavedModelSelection) {
        // Commit this tiny preference before acknowledging the selection, including an immediate force-stop.
        check(prefs.edit().putString(entryKey(key), json.encodeToString(SavedModelSelection.serializer(), selection)).commit()) {
            "无法记住模型设置，请重试"
        }
    }
}
