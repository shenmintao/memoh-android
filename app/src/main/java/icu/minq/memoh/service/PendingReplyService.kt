package icu.minq.memoh.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import icu.minq.memoh.MainActivity
import icu.minq.memoh.MemohApplication
import icu.minq.memoh.R
import icu.minq.memoh.data.*
import icu.minq.memoh.model.RuntimeState
import icu.minq.memoh.network.ChatSocket
import icu.minq.memoh.network.ChatSocketListener
import kotlinx.coroutines.*

/** Bounded, read-only monitoring of a user-initiated invocation. Never owns or replays prompts. */
class PendingReplyService : Service() {
    private data class ActiveOperation(val pending: PendingOperation, val startId: Int, val epoch: Long)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitor: ChatSocket? = null
    private var active: ActiveOperation? = null
    private var latestStartId = 0
    private var watch: Job? = null
    private var deadline: Job? = null
    private var disconnectedAt: Long? = null
    private val notifiedDecisions = mutableSetOf<String>()
    private val container get() = (application as MemohApplication).container

    override fun onCreate() { super.onCreate(); createChannels() }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val pending = container.pendingStore.read()
        val auth = container.api.currentAuth()
        val valid = pending != null && pending.invocationId == intent?.getStringExtra(EXTRA_INVOCATION) &&
            pending.botId == intent.getStringExtra(EXTRA_BOT) && pending.sessionId == intent.getStringExtra(EXTRA_SESSION) &&
            auth != null && auth.accountId.isNotBlank() && pending.accountKey == PendingPolicy.accountKey(auth.apiBase, auth.accountId)
        if (!valid) {
            // A delayed old start must never stop a newer operation's foreground service.
            if (active == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        pending!!
        watch?.cancel(); deadline?.cancel(); monitor?.close()
        val operation = ActiveOperation(pending, startId, container.api.authEpoch)
        active = operation
        monitored = pending
        disconnectedAt = System.currentTimeMillis()
        notifiedDecisions.clear()
        startForeground(ONGOING_ID, ongoingNotification(operation))

        monitor = ChatSocket(container.api, pending.botId, pending.sessionId, object : ChatSocketListener {
            override fun onRuntime(state: RuntimeState) {
                if (!current(operation)) return
                val run = state.run?.takeIf { it.invocation_id == pending.invocationId } ?: return
                // Persist the supplement receipt before task completion can stop this monitor.
                runCatching { container.steeringStore.observe(steeringKey(pending.accountKey, pending.botId, pending.sessionId), run) }
                container.pendingStore.observe(pending.accountKey, pending.botId, pending.sessionId, run)
                if (!current(operation)) return
                val newDecisions = ReplyNotificationPolicy.decisionIds(run) - notifiedDecisions
                if (newDecisions.isNotEmpty()) {
                    notifiedDecisions += newDecisions
                    notifyResult(operation, if (newDecisions.any { it.startsWith("input:") }) "Memoh 正在等待用户输入，请打开应用查看" else "Memoh 正在等待你的批准")
                }
            }
            override fun onConnection(connected: Boolean) {
                if (!current(operation)) return
                if (connected) disconnectedAt = null
                else if (disconnectedAt == null) disconnectedAt = System.currentTimeMillis()
            }
            override fun onRejected(invocationId: String?, message: String) {
                if (!current(operation)) return
                if (invocationId == pending.invocationId) container.pendingStore.update(pending) { it.copy(phase = PendingPhase.FAILED) }
                else if (invocationId == null) unknown(operation)
            }
        }).also { it.connect() }

        watch = scope.launch {
            container.pendingStore.changes.collect { value ->
                if (!current(operation)) return@collect
                when {
                    !pending.sameIdentity(value) -> stopCurrent(operation)
                    value!!.terminal -> {
                        notifyResult(operation, ReplyNotificationPolicy.result(value.phase))
                        container.pendingStore.clearIfMatches(pending)
                        stopCurrent(operation)
                    }
                    value.phase == PendingPhase.UNKNOWN -> {
                        notifyResult(operation, ReplyNotificationPolicy.result(value.phase))
                        stopCurrent(operation) // Keep uncertainty visible until the user checks/acknowledges it.
                    }
                }
            }
        }
        val startedElapsed = SystemClock.elapsedRealtime()
        deadline = scope.launch {
            while (active === operation) {
                if (container.api.authEpoch != operation.epoch) { stopCurrent(operation); break }
                val value = container.pendingStore.read()
                if (value != null && pending.sameIdentity(value) &&
                    (PendingPolicy.timedOut(value, System.currentTimeMillis(), disconnectedAt) ||
                        SystemClock.elapsedRealtime() - startedElapsed >= PendingPolicy.MONITOR_TIMEOUT_MS)) {
                    unknown(operation)
                    break
                }
                delay(1_000)
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun current(operation: ActiveOperation) = active === operation && container.api.authEpoch == operation.epoch
    private fun unknown(operation: ActiveOperation) {
        if (!current(operation)) return
        container.pendingStore.update(operation.pending) { if (it.terminal) it else it.copy(phase = PendingPhase.UNKNOWN) }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        active?.takeIf { it.startId == startId || latestStartId == startId }?.let { operation ->
            unknown(operation)
            stopCurrent(operation)
        }
    }
    private fun stopCurrent(operation: ActiveOperation) {
        if (active !== operation) return
        active = null
        if (operation.pending.sameIdentity(monitored)) monitored = null
        watch?.cancel(); deadline?.cancel()
        monitor?.close(); monitor = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelfResult(latestStartId)
    }
    override fun onDestroy() {
        active?.let { if (it.pending.sameIdentity(monitored)) monitored = null }
        active = null
        scope.cancel(); monitor?.close(); monitor = null
        super.onDestroy()
    }

    // The system owns the left-hand identity icon. A largeIcon creates a second image on the right.
    private fun notificationBuilder(channel: String) = NotificationCompat.Builder(this, channel)
        .setSmallIcon(R.drawable.ic_stat_memoh)
        .setColor(0xff7948ff.toInt())
        .setContentTitle("Memoh")

    private fun ongoingNotification(operation: ActiveOperation): Notification = notificationBuilder(CHANNEL_PENDING)
        .setContentText(getString(R.string.pending_notification)).setOngoing(true).setOnlyAlertOnce(true)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET).setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(contentIntent(operation)).build()

    private fun notifyResult(operation: ActiveOperation, text: String) {
        val notification = notificationBuilder(CHANNEL_RESULT)
            .setContentText(text).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(notificationBuilder(CHANNEL_RESULT).setContentText("有新的状态更新").build())
            .setContentIntent(contentIntent(operation)).build()
        // Permission denial cannot keep a foreground monitor alive or crash the completion path.
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(RESULT_ID_BASE + (operation.pending.invocationId.hashCode() and 0x0fffffff), notification) }
    }
    private fun contentIntent(operation: ActiveOperation): PendingIntent {
        val pending = operation.pending
        val intent = Intent(this, MainActivity::class.java)
            .setAction("icu.minq.memoh.OPEN_CHAT.${pending.invocationId}")
            .setData(Uri.Builder().scheme("memoh-internal").authority("chat").appendPath(pending.botId).appendPath(pending.sessionId).appendPath(pending.invocationId).build())
            .putExtra(EXTRA_BOT, pending.botId).putExtra(EXTRA_SESSION, pending.sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, pending.invocationId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun createChannels() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_PENDING, getString(R.string.pending_channel), NotificationManager.IMPORTANCE_LOW).apply { lockscreenVisibility = Notification.VISIBILITY_SECRET })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_RESULT, getString(R.string.result_channel), NotificationManager.IMPORTANCE_DEFAULT).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
    }
    companion object {
        private const val CHANNEL_PENDING = "pending_replies"
        private const val CHANNEL_RESULT = "reply_results"
        private const val ONGOING_ID = 1001
        private const val RESULT_ID_BASE = 2000
        const val EXTRA_BOT = "bot_id"
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_INVOCATION = "invocation_id"
        @Volatile private var monitored: PendingOperation? = null
        fun isMonitoring(pending: PendingOperation) = pending.sameIdentity(monitored)
        fun intent(context: Context, pending: PendingOperation) = Intent(context, PendingReplyService::class.java)
            .putExtra(EXTRA_BOT, pending.botId).putExtra(EXTRA_SESSION, pending.sessionId).putExtra(EXTRA_INVOCATION, pending.invocationId)
    }
}
