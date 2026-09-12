package com.clipcells.app.copy

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.clipcells.app.data.ClipCellsDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Пишет элементы очереди в системный буфер с интервалом.
 * Android 10+ игнорирует setPrimaryClip у приложения без фокуса (проверено на устройстве),
 * поэтому при уходе приложения в фон очередь останавливается и продолжается при возврате —
 * прогресс хранится в БД (nextIndex), повторы записей исключены.
 */
class CopyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private val queueDao by lazy { ClipCellsDatabase.get(this).queueDao() }
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }

    private val appObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) {
            val job = activeJob
            if (job?.isActive == true) {
                job.cancel()
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(appObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            activeJob?.cancel()
            scope.launch {
                queueDao.deleteQueue()
                stopSelf()
            }
            return START_NOT_STICKY
        }

        activeJob?.cancel()
        activeJob = scope.launch { runQueue() }
        return START_NOT_STICKY
    }

    private suspend fun runQueue() {
        val queue = queueDao.getQueue() ?: return stopSelf()
        val items = queueDao.getItems()
        if (items.isEmpty() || queue.nextIndex !in 0..items.size) {
            queueDao.finish(queue.revision)
            return stopSelf()
        }

        for (index in queue.nextIndex until items.size) {
                val current = queueDao.getQueue()
                if (current?.revision != queue.revision) return

                val item = items[index]
                clipboard.setPrimaryClip(ClipData.newPlainText(queue.title, item.text))
                if (queueDao.advance(queue.revision, index + 1) == 0) return
                if (index < items.lastIndex) delay(queue.intervalMillis)
            }
            queueDao.finish(queue.revision)
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

        fun startIntent(context: Context) = Intent(context, CopyService::class.java).setAction(ACTION_START)
    }
}
