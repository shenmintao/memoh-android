package icu.minq.memoh

import android.app.Application
import icu.minq.memoh.data.PendingOperationStore
import icu.minq.memoh.data.ModelSelectionStore
import icu.minq.memoh.data.MemoryModelSelectionStore
import icu.minq.memoh.data.PreferencesModelSelectionStore
import icu.minq.memoh.network.MemohApi
import icu.minq.memoh.security.TokenStore
import icu.minq.memoh.security.AuthStore
import icu.minq.memoh.security.LoginStore
import icu.minq.memoh.security.EncryptedLoginStore
import kotlinx.serialization.json.Json

class MemohApplication : Application() {
    lateinit var container: AppContainer
    override fun onCreate() {
        super.onCreate()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
        val tokens = TokenStore(this, json)
        val api = MemohApi(MemohApi.defaultClient(), json, tokens)
        container = AppContainer(api, tokens, PendingOperationStore(this), EncryptedLoginStore(this), PreferencesModelSelectionStore(this), icu.minq.memoh.security.EncryptedSteeringStore(this))
    }
}

data class AppContainer(val api: MemohApi, val tokenStore: AuthStore, val pendingStore: PendingOperationStore, val loginStore: LoginStore? = null,
    val modelSelectionStore: ModelSelectionStore = MemoryModelSelectionStore(),
    val steeringStore: icu.minq.memoh.data.SteeringStore = icu.minq.memoh.data.MemorySteeringStore())
