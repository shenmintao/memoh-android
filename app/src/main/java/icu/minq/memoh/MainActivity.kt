package icu.minq.memoh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.savedstate.SavedStateRegistryOwner
import icu.minq.memoh.data.AppState
import icu.minq.memoh.data.Screen
import icu.minq.memoh.service.PendingReplyService
import icu.minq.memoh.ui.MemohApp
import icu.minq.memoh.ui.MemohTheme

class MainActivity : ComponentActivity() {
    private lateinit var appState: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MemohApplication).container
        appState = ViewModelProvider(this, AppStateFactory(this, intent.extras, application, container))[AppState::class.java]
        setContent {
            LaunchedEffect(Unit) {
                appState.bootstrap(
                    intent.getStringExtra(PendingReplyService.EXTRA_BOT).orEmpty(),
                    intent.getStringExtra(PendingReplyService.EXTRA_SESSION).orEmpty(),
                )
            }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            val state by appState.state.collectAsStateWithLifecycle()
            MemohTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BackHandler(enabled = state.screen == Screen.Chat || state.screen == Screen.Sessions) { appState.back() }
                    MemohApp(appState) { send ->
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        send()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appState.bootstrap(
            intent.getStringExtra(PendingReplyService.EXTRA_BOT).orEmpty(),
            intent.getStringExtra(PendingReplyService.EXTRA_SESSION).orEmpty(),
        )
    }

    private class AppStateFactory(
        owner: SavedStateRegistryOwner,
        defaultArgs: Bundle?,
        private val application: android.app.Application,
        private val container: AppContainer,
    ) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
        override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T {
            @Suppress("UNCHECKED_CAST")
            return AppState(application, container, handle) as T
        }
    }
}
