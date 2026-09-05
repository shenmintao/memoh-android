package icu.minq.memoh

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import icu.minq.memoh.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AttachmentReaderTest {
    @Test fun readsRealContentUriIncludingBinaryAndEmptyFilesAndRejectsOversize() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val reader = AttachmentReader(resolver)
        val created = mutableListOf<Uri>()
        fun create(suffix: String, bytes: ByteArray): Uri {
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "memoh-test-${UUID.randomUUID()}.$suffix")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Memoh-test")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!
            created += uri
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        }
        try {
            val bytes = byteArrayOf(0, 1, 10, 127, -1, -128)
            val uri = create("bin", bytes)
            val result = reader.read(DraftAttachment("binary", uri.toString()))
            assertArrayEquals(bytes, Base64.getDecoder().decode(result.payload!!.base64!!.substringAfter(',')))
            assertEquals(bytes.size.toLong(), result.size)
            assertTrue(result.name.endsWith(".bin"))
            val empty = reader.read(DraftAttachment("empty", create("txt", byteArrayOf()).toString()))
            assertEquals(0, empty.size)
            assertNotNull(empty.payload)
            val huge = create("bin", ByteArray(AttachmentDraftStore.MAX_BYTES.toInt() + 1))
            val failure = runCatching { reader.read(DraftAttachment("large", huge.toString())) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            resolver.delete(uri, null, null)
            assertTrue(runCatching { reader.read(DraftAttachment("missing", uri.toString())) }.isFailure)
        } finally { created.forEach { resolver.delete(it, null, null) } }
    }
}
