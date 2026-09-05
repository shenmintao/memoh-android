package icu.minq.memoh.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable class RememberedLogin(val server: String, val username: String, val password: String)

interface LoginStore {
    fun read(): RememberedLogin?
    fun write(value: RememberedLogin)
    fun clear()
}

/** Opt-in login form memory, independent of refresh tokens so explicit logout keeps the form. */
class EncryptedLoginStore(context: Context, private val storageName: String = "remembered_login") : LoginStore {
    private val prefs = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val alias = "memoh.login.$storageName.aes.v1"
    private val aad = "icu.minq.memoh:login:$storageName:v1".toByteArray(Charsets.UTF_8)

    @Synchronized override fun read(): RememberedLogin? {
        return try {
            val nonce = prefs.getString("iv", null) ?: return null
            val data = prefs.getString("data", null) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(nonce, Base64.NO_WRAP)))
            cipher.updateAAD(aad)
            Json.decodeFromString(RememberedLogin.serializer(), String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8))
        } catch (_: Exception) { clear(); null }
    }

    @Synchronized override fun write(value: RememberedLogin) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val payload = Json.encodeToString(RememberedLogin.serializer(), value).toByteArray(Charsets.UTF_8)
        val encrypted = cipher.doFinal(payload)
        check(prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "无法保存登录信息" }
    }

    @Synchronized override fun clear() {
        check(prefs.edit().clear().commit()) { "无法清除登录信息" }
        runCatching { keyStore().deleteEntry(alias) }
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
