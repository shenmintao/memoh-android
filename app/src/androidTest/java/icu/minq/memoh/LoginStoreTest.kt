package icu.minq.memoh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import icu.minq.memoh.security.EncryptedLoginStore
import icu.minq.memoh.security.RememberedLogin
import org.junit.Assert.*
import org.junit.Test

class LoginStoreTest {
    @Test fun loginFieldsAreEncryptedReloadedAndExplicitlyForgotten() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test_remembered_login"
        val store = EncryptedLoginStore(context, name)
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        store.clear()
        try {
            val value = RememberedLogin("https://test.invalid", "sample-user", "private-test-password")
            store.write(value)
            val saved = EncryptedLoginStore(context, name).read()!!
            assertEquals(value.server, saved.server); assertEquals(value.username, saved.username); assertEquals(value.password, saved.password)
            val diskValues = prefs.all.toString()
            assertFalse(diskValues.contains(value.server)); assertFalse(diskValues.contains(value.username)); assertFalse(diskValues.contains(value.password))
            val oldCipher = prefs.getString("data", null)
            val oldIv = prefs.getString("iv", null)
            store.write(value)
            assertNotEquals(oldCipher, prefs.getString("data", null)); assertNotEquals(oldIv, prefs.getString("iv", null))
            store.clear()
            assertNull(EncryptedLoginStore(context, name).read()); assertTrue(prefs.all.isEmpty())
        } finally { store.clear() }
    }

    @Test fun damagedCiphertextDoesNotPopulateLoginForm() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test_remembered_corruption"
        val store = EncryptedLoginStore(context, name)
        try {
            store.write(RememberedLogin("https://test.invalid", "sample", "sample-password"))
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("data", "aW52YWxpZA==").commit()
            assertNull(store.read())
            assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty())
        } finally { store.clear() }
    }
}
