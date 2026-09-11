package com.clipcells.app.copy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.clipcells.app.MainActivity
import com.clipcells.app.data.ClipCellsDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CopyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private val queueDao by lazy { ClipCellsDatabase.get(this).queueDao() }
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            activeJob?.cancel()
            scope.launch {
                queueDao.deleteQueue()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        enterForeground(notification("ClipCells", "Подготовка…", 0, 0))
        activeJob?.cancel()
        activeJob = scope.launch { runQueue() }
        return START_STICKY
    }

    private suspend fun runQueue() {
        val queue = queueDao.getQueue() ?: return stopNow()
        val items = queueDao.getItems()
        if (items.isEmpty() || queue.nextIndex !in 0..items.size) {
            queueDao.finish(queue.revision)
            return stopNow()
        }

        try {
            for (index in queue.nextIndex until items.size) {
                val current = queueDao.getQueue()
                if (current?.revision != queue.revision) return

                val item = items[index]
                clipboard.setPrimaryClip(ClipData.newPlainText(queue.title, item.text))
                if (queueDao.advance(queue.revision, index + 1) == 0) return
                notifications.notify(NOTIFICATION_ID, notification(queue.title, "Скопировано ${index + 1} из ${items.size}", index + 1, items.size))
                if (index < items.lastIndex) delay(queue.intervalMillis)
            }
            queueDao.finish(queue.revision)
            stopNow()
        } catch (_: CancellationException) {
            throw CancellationException()
        }
    }

    private fun enterForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun notification(title: String, text: String, progress: Int, max: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this,
            2,
            Intent(this, CopyService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply { if (max > 0) setProgress(max, progress, false) else setProgress(0, 0, true) }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отменить", cancel)
            .build()
    }

    private fun createChannel() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Очередь копирования", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Показывает прогресс последовательного копирования"
                setSound(null, null)
            },
        )
    }

    private fun stopNow() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.clipcells.app.action.START_COPY"
        const val ACTION_CANCEL = "com.clipcells.app.action.CANCEL_COPY"
        private const val CHANNEL_ID = "copy_queue"
        private const val NOTIFICATION_ID = 4101

        fun startIntent(context: Context) = Intent(context, CopyService::class.java).setAction(ACTION_START)
    }
}
