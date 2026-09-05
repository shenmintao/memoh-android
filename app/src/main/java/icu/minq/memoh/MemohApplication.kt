package icu.minq.memoh

import android.app.Application
import icu.minq.memoh.data.PendingOperationStore
import icu.minq.memoh.network.MemohApi
import icu.minq.memoh.security.TokenStore
import icu.minq.memoh.security.AuthStore
import kotlinx.serialization.json.Json

class MemohApplication : Application() {
    lateinit var container: AppContainer
    override fun onCreate() {
        super.onCreate()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
        val tokens = TokenStore(this, json)
        val api = MemohApi(MemohApi.defaultClient(), json, tokens)
        container = AppContainer(api, tokens, PendingOperationStore(this))
    }
}

data class AppContainer(val api: MemohApi, val tokenStore: AuthStore, val pendingStore: PendingOperationStore)
