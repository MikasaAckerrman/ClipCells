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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.clipcells.app.MainActivity
import com.clipcells.app.data.ClipCellsDatabase
import com.clipcells.app.data.CopyQueueEntity
import com.clipcells.app.data.CopyQueueItemEntity
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
    private var inForeground = false
    private var appInForeground = true
    private val queueDao by lazy { ClipCellsDatabase.get(this).queueDao() }
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    private val appObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> appInForeground = true
            Lifecycle.Event.ON_STOP -> {
                appInForeground = false
                promoteIfRunning()
            }
            else -> Unit
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        appInForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ProcessLifecycleOwner.get().lifecycle.addObserver(appObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            activeJob?.cancel()
            scope.launch {
                queueDao.deleteQueue()
                stopNow()
            }
            return START_NOT_STICKY
        }

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
        if (!appInForeground) promote(queue, items)

        try {
            for (index in queue.nextIndex until items.size) {
                val current = queueDao.getQueue()
                if (current?.revision != queue.revision) return

                val item = items[index]
                clipboard.setPrimaryClip(ClipData.newPlainText(queue.title, item.text))
                if (queueDao.advance(queue.revision, index + 1) == 0) return
                if (inForeground) {
                    notifications.notify(
                        NOTIFICATION_ID,
                        notification(queue.title, "Осталось ${items.size - index - 1}", index + 1, items.size),
                    )
                }
                if (index < items.lastIndex) delay(queue.intervalMillis)
            }
            queueDao.finish(queue.revision)
            stopNow()
        } catch (_: CancellationException) {
            throw CancellationException()
        }
    }

    private fun promoteIfRunning() {
        if (inForeground) return
        val job = activeJob ?: return
        if (!job.isActive) return
        scope.launch {
            val queue = queueDao.getQueue() ?: return@launch
            promote(queue, queueDao.getItems())
        }
    }

    private fun promote(queue: CopyQueueEntity, items: List<CopyQueueItemEntity>) {
        if (inForeground) return
        enterForeground(
            notification(queue.title, "Осталось ${items.size - queue.nextIndex}", queue.nextIndex, items.size),
        )
    }

    private fun enterForeground(n: Notification) {
        inForeground = true
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
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
            NotificationChannel(CHANNEL_ID, "Очередь копирования", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Показывает прогресс последовательного копирования в фоне"
                setSound(null, null)
            },
        )
    }

    private fun stopNow() {
        if (inForeground) {
            inForeground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appObserver)
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
