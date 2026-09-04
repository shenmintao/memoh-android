package icu.minq.memoh.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import icu.minq.memoh.MainActivity
import icu.minq.memoh.MemohApplication
import icu.minq.memoh.R
import icu.minq.memoh.data.PendingOperation
import icu.minq.memoh.model.RuntimeState
import icu.minq.memoh.model.isTerminal
import icu.minq.memoh.model.isWaitingApproval
import icu.minq.memoh.network.ChatSocket
import icu.minq.memoh.network.ChatSocketListener

class PendingReplyService : Service() {
    private data class ActiveOperation(val pending: PendingOperation, val startId: Int, val generation: Long)

    private val main = Handler(Looper.getMainLooper())
    private var monitor: ChatSocket? = null
    private var active: ActiveOperation? = null
    private var generation = 0L
    private var notifiedWaiting = false
    private val container get() = (application as MemohApplication).container

    override fun onCreate() { super.onCreate(); createChannels() }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pending = PendingOperation(
            intent?.getStringExtra(EXTRA_BOT).orEmpty(),
            intent?.getStringExtra(EXTRA_SESSION).orEmpty(),
            intent?.getStringExtra(EXTRA_INVOCATION).orEmpty(),
        )
        if (pending.botId.isBlank() || pending.sessionId.isBlank() || pending.invocationId.isBlank() || container.pendingStore.read() != pending) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val operation = ActiveOperation(pending, startId, ++generation)
        active = operation
        notifiedWaiting = false
        startForeground(ONGOING_ID, ongoingNotification(operation))
        monitor?.close()
        main.removeCallbacksAndMessages(null)
        main.postDelayed({
            finishIfCurrent(operation, false, "请求未在限定时间内被服务器接受，请打开应用重试")
        }, ADMISSION_TIMEOUT_MS)
        monitor = ChatSocket(container.api, pending.botId, pending.sessionId, object : ChatSocketListener {
            override fun onRuntime(state: RuntimeState) {
                if (!isCurrent(operation)) return
                val run = state.run?.takeIf { it.invocation_id == pending.invocationId } ?: return
                main.removeCallbacksAndMessages(null) // Runtime identity proves admission.
                when {
                    run.isTerminal() -> finishIfCurrent(operation, run.status == "completed", run.error)
                    run.isWaitingApproval() && !notifiedWaiting -> {
                        notifiedWaiting = true
                        notifyResult(operation, "Memoh 正在等待你的批准")
                    }
                }
            }

            override fun onRejected(invocationId: String?, message: String) {
                if (invocationId == null || invocationId == pending.invocationId) finishIfCurrent(operation, false, message)
            }
        }).also { it.connect() }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        generation++
        main.removeCallbacksAndMessages(null)
        monitor?.close()
        monitor = null
        active = null
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        active?.takeIf { it.startId == startId }?.let {
            finishIfCurrent(it, false, "后台等待已超时，请打开应用检查")
        }
    }

    private fun isCurrent(operation: ActiveOperation) = active == operation && generation == operation.generation

    private fun finishIfCurrent(operation: ActiveOperation, success: Boolean, detail: String?) {
        if (!isCurrent(operation)) return
        val text = when {
            success -> "Memoh 回复已完成"
            !detail.isNullOrBlank() -> detail.take(120)
            else -> "Memoh 回复未完成，请打开应用检查"
        }
        notifyResult(operation, text)
        container.pendingStore.clearIfMatches(operation.pending.invocationId)
        stopCurrent(operation)
    }

    private fun stopCurrent(operation: ActiveOperation) {
        if (!isCurrent(operation)) return
        generation++
        active = null
        main.removeCallbacksAndMessages(null)
        monitor?.close()
        monitor = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelfResult(operation.startId)
    }

    private fun ongoingNotification(operation: ActiveOperation): Notification = NotificationCompat.Builder(this, CHANNEL_PENDING)
        .setSmallIcon(R.drawable.ic_notification).setContentTitle("Memoh")
        .setContentText(getString(R.string.pending_notification)).setOngoing(true).setOnlyAlertOnce(true)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET).setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(contentIntent(operation)).build()

    private fun notifyResult(operation: ActiveOperation, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_RESULT).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Memoh").setContentText(text).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(this, CHANNEL_RESULT).setSmallIcon(R.drawable.ic_notification).setContentTitle("Memoh").setContentText("有新的状态更新").build())
            .setContentIntent(contentIntent(operation)).build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(resultNotificationId(operation), notification)
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

    private fun resultNotificationId(operation: ActiveOperation) = RESULT_ID_BASE + (operation.pending.invocationId.hashCode() and 0x0fffffff)

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
        internal const val ADMISSION_TIMEOUT_MS = 30_000L
        const val EXTRA_BOT = "bot_id"
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_INVOCATION = "invocation_id"
        fun intent(context: Context, botId: String, sessionId: String, invocationId: String) = Intent(context, PendingReplyService::class.java)
            .putExtra(EXTRA_BOT, botId).putExtra(EXTRA_SESSION, sessionId).putExtra(EXTRA_INVOCATION, invocationId)

        fun cancelIfMatching(context: Context, invocationId: String) {
            val app = context.applicationContext as? MemohApplication ?: return
            if (app.container.pendingStore.clearIfMatches(invocationId)) {
                context.stopService(Intent(context, PendingReplyService::class.java))
            }
        }
    }
}
