package com.clipcells.app.copy

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.clipcells.app.data.ClipCellsDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Копирует РОВНО ОДНО сообщение очереди за вызов (v0.12.2: интервалы убраны
 * полностью по требованию — авто-темпа не существует, очередь двигают только тапы
 * пользователя). Каждый тап по активной ячейке — ACTION_ADVANCE: следующее сообщение
 * в буфере за ~16 мс (замер телеметрией), прогресс nextIndex лежит в БД и переживает
 * всё. Очередь на пустое → finish; тап по завершённой/другой ячейке → новая очередь.
 *
 * Android 10+ игнорирует setPrimaryClip у приложения без фокуса (проверено на
 * устройстве) — сервис вызывается только из открытого приложения, буфер пишется
 * легально. Сервис живёт миллисекунды (одно сообщение) и уходит: ноль простоя.
 */
class CopyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueDao by lazy { ClipCellsDatabase.get(this).queueDao() }
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                scope.launch {
                    queueDao.deleteQueue()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }

        // START и ADVANCE делают одно и то же: следующее (или первое) сообщение
        // — единица работы, никакого цикла, никакого таймера.
        scope.launch { copyNext() }
        return START_NOT_STICKY
    }

    private suspend fun copyNext() {
        val queue = queueDao.getQueue() ?: return stopSelf()
        val items = queueDao.getItems()
        if (items.isEmpty() || queue.nextIndex !in 0 until items.size) {
            queueDao.finish(queue.revision)
            return stopSelf()
        }

        val startedAt = SystemClock.elapsedRealtime()
        val index = queue.nextIndex
        val item = items[index]
        clipboard.setPrimaryClip(ClipData.newPlainText(queue.title, item.text))
        // Ревизионный гвард: очередь заменили/отменили — тихо уходим.
        if (queueDao.advance(queue.revision, index + 1) == 0) return stopSelf()
        android.util.Log.i(
            TAG, "copied ${index + 1}/${items.size} in ${SystemClock.elapsedRealtime() - startedAt}ms"
        )
        if (index + 1 >= items.size) {
            queueDao.finish(queue.revision)
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ClipCellsCopy"

        const val ACTION_START = "com.clipcells.app.action.START_COPY"
        const val ACTION_CANCEL = "com.clipcells.app.action.CANCEL_COPY"
        const val ACTION_ADVANCE = "com.clipcells.app.action.ADVANCE_COPY"

        fun startIntent(context: Context) =
            Intent(context, CopyService::class.java).setAction(ACTION_START)

        fun advanceIntent(context: Context) =
            Intent(context, CopyService::class.java).setAction(ACTION_ADVANCE)
    }
}
