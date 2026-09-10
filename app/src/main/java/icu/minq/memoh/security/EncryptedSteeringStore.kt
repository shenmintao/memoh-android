package icu.minq.memoh.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

import icu.minq.memoh.data.SteeringStore
import icu.minq.memoh.data.StoredSteering
import java.security.MessageDigest

class EncryptedSteeringStore(context: Context, storageName: String = "steering_v1") : SteeringStore {
    private val revision = MutableStateFlow(0L)
    override val changes = revision.asStateFlow()
    private val prefs = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val alias = "memoh.steering.$storageName.aes.v1"
    private fun slot(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    @Synchronized override fun readQueue(key: String): List<StoredSteering> {
        val name = slot(key)
        val data = prefs.getString(name, null) ?: return emptyList()
        return try {
            val parts = data.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.updateAAD(name.toByteArray())
            val plain = String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
            if (plain.trimStart().startsWith("[")) Json.decodeFromString(ListSerializer(StoredSteering.serializer()), plain)
            else listOf(Json.decodeFromString<StoredSteering>(plain))
        } catch (_: Exception) { emptyList() }
    }
    @Synchronized override fun writeQueue(key: String, value: List<StoredSteering>) {
        val name = slot(key)
        if (value.isEmpty()) { check(prefs.edit().remove(name).commit()); revision.value++; return }
        check(prefs.contains(name) || prefs.all.size < 32) { "请先清理已保存的补充内容" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray())
        val encrypted = cipher.doFinal(Json.encodeToString(ListSerializer(StoredSteering.serializer()), value).toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(name, Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
        revision.value++
    }
    private fun key(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
