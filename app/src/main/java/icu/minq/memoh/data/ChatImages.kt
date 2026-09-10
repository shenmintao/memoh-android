package icu.minq.memoh.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.util.LruCache
import icu.minq.memoh.model.ChatAttachment
import icu.minq.memoh.network.MemohApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.max
import kotlin.math.sqrt

internal fun interface ChatImageLoader {
    suspend fun load(file: ChatAttachment, botId: String, fullSize: Boolean): Bitmap
}

internal class ChatImages(private val api: MemohApi) : ChatImageLoader {
    private val decoding = Semaphore(2)
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    fun clear() = cache.evictAll()

    override suspend fun load(file: ChatAttachment, botId: String, fullSize: Boolean): Bitmap = withContext(Dispatchers.Default) {
        val epoch = api.authEpoch
        val owner = file.botId?.takeIf { it.isNotBlank() } ?: botId
        val source = file.contentHash?.takeIf { it.isNotBlank() } ?: file.base64?.takeIf { it.isNotBlank() } ?: file.url.orEmpty()
        require(source.isNotBlank()) { "这张图片没有可用地址" }
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
        val key = "$epoch:$owner:$digest:$fullSize"
        cache.get(key)?.let { return@withContext it }
        decoding.withPermit {
            cache.get(key)?.let { return@withPermit it }
            val bytes = when {
                !file.contentHash.isNullOrBlank() -> api.mediaBytes(owner, file.contentHash)
                !file.base64.isNullOrBlank() -> decodeInlineImage(file.base64)
                source.startsWith("data:") -> decodeInlineImage(source)
                else -> api.publicImageBytes(source)
            }
            val bitmap = decodeChatBitmap(bytes, if (fullSize) 2560 else 720)
            if (epoch != api.authEpoch) throw CancellationException("Account changed")
            cache.put(key, bitmap)
            bitmap
        }
    }
}

internal fun decodeInlineImage(data: String): ByteArray {
    val payload = if (data.startsWith("data:")) {
        val header = data.substringBefore(',')
        require(header.startsWith("data:image/", true) && header.endsWith(";base64", true)) { "图片数据格式无效" }
        data.substringAfter(',', "")
    } else data
    require(payload.length.toLong() <= (MemohApi.MAX_IMAGE_BYTES.toLong() + 2) / 3 * 4) { "图片过大" }
    return Base64.getDecoder().decode(payload).also { require(it.size <= MemohApi.MAX_IMAGE_BYTES) { "图片过大" } }
}

internal fun decodeChatBitmap(bytes: ByteArray, edge: Int): Bitmap {
    require(bytes.isNotEmpty()) { "图片为空" }
    if (Build.VERSION.SDK_INT >= 28) return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        val width = info.size.width; val height = info.size.height
        require(width > 0 && height > 0) { "图片格式不受支持" }
        val scale = max(1.0, max(max(width, height).toDouble() / edge, sqrt(width.toDouble() * height / 4_000_000)))
        decoder.setTargetSize((width / scale).toInt().coerceAtLeast(1), (height / scale).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片格式不受支持" }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > edge || bounds.outWidth.toDouble() * bounds.outHeight / sample / sample > 4_000_000) sample *= 2
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })) { "无法解码图片" }
    val orientation = runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
    val matrix = Matrix().apply {
        when (orientation) {
            2 -> setScale(-1f, 1f)
            3 -> setRotate(180f)
            4 -> { setRotate(180f); postScale(-1f, 1f) }
            5 -> { setRotate(90f); postScale(-1f, 1f) }
            6 -> setRotate(90f)
            7 -> { setRotate(-90f); postScale(-1f, 1f) }
            8 -> setRotate(-90f)
        }
    }
    if (matrix.isIdentity) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
}
