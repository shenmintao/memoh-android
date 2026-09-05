package icu.minq.memoh.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import icu.minq.memoh.model.ChatAttachment
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.Base64

class AttachmentReader(private val resolver: ContentResolver) {
    suspend fun read(draft: DraftAttachment): DraftAttachment {
        val context = currentCoroutineContext()
        return runInterruptible(Dispatchers.IO) {
            val uri = Uri.parse(draft.uri)
            require(uri.scheme == "content") { "请通过系统文件选择器选择文件" }
            var name = "文件"
            var declaredSize = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
                }
            }
            require(declaredSize <= AttachmentDraftStore.MAX_BYTES) { "单个文件不能超过 8 MB" }
            name = name.substringAfterLast('/').substringAfterLast('\\').filterNot { it.isISOControl() }.take(180).ifBlank { "文件" }
            val guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            val mime = (resolver.getType(uri)?.takeUnless { it == "application/octet-stream" } ?: guessed ?: "application/octet-stream")
                .takeIf { it.matches(Regex("[a-zA-Z0-9!#$&^_.+\\-]+/[a-zA-Z0-9!#$&^_.+\\-]+")) } ?: "application/octet-stream"
            val bytes = ByteArrayOutputStream()
            requireNotNull(resolver.openInputStream(uri)) { "无法打开文件，请重新选择" }.use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(bytes.size().toLong() + count <= AttachmentDraftStore.MAX_BYTES) { "单个文件不能超过 8 MB" }
                    bytes.write(buffer, 0, count)
                }
            }
            context.ensureActive()
            val payload = ChatAttachment(type = if (mime.startsWith("image/")) "image" else "file", name = name, mime = mime,
                base64 = "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes.toByteArray())}")
            draft.copy(name = name, size = bytes.size().toLong(), payload = payload, error = null)
        }
    }
}
