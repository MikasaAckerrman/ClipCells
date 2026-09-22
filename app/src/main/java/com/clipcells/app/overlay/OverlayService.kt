package com.clipcells.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.clipcells.app.MainActivity
import com.clipcells.app.R
import com.clipcells.app.copy.CopyService
import com.clipcells.app.data.CellRepository
import com.clipcells.app.data.ClipCellsDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hosts the ClipCells floating overlay: the tool bubble plus the cells panel.
 *
 * Foreground service (specialUse, per the PRD constraint) started ONLY by an
 * explicit user action from the main activity — the process must outlive the
 * activity so the windows stay above every app.
 *
 * Power discipline: while the panel is closed NOTHING observes the database
 * (the Room subscription exists only between panel show and panel hide), the
 * bubble is a single static view, and the notification is LOW priority. The
 * service never polls, never wakes, has no network permission.
 *
 * Intent hooks (also used by the future donut launcher and by process-level
 * tests — no screen taps needed):
 *  - ACTION_START (+EXTRA_SHOW_PANEL) — ensure bubble, optionally open panel
 *  - ACTION_STOP — remove everything and stop
 *  - ACTION_DISMISS_PANEL — close just the panel (bubble stays)
 *  - ACTION_TEST_COPY — write a test string through the focus-aware copier
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cursor = CellCursor()
    private val repository by lazy { CellRepository(ClipCellsDatabase.get(this)) }

    private var bubble: OverlayBubble? = null
    private var panel: OverlayPanelController? = null
    private var cellsJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.i(TAG, "onStartCommand action=${intent?.action} showPanel=${intent?.getBooleanExtra(EXTRA_SHOW_PANEL, false)}")
        when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISMISS_PANEL -> panel?.hide()
            ACTION_TEST_COPY -> {
                showPanel()
                panel?.copier?.copy(
                    "ClipCells", "CLIPCELLS_OVERLAY_FOCUS_TEST", "тест"
                )
            }
            else -> {
                if (!startForegroundCompat()) return START_NOT_STICKY
                ensureBubble()
                if (intent?.getBooleanExtra(EXTRA_SHOW_PANEL, false) == true) showPanel()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------- windows

    private fun ensureBubble() {
        if (bubble == null) {
            bubble = OverlayBubble(
                context = this,
                wm = getSystemService(android.view.WindowManager::class.java),
                onTap = { showPanel() },
                onLongPress = { stopOverlay() },
            )
        }
        bubble?.show()
    }

    private fun showPanel() {
        var existing = panel
        if (existing == null) {
            existing = OverlayPanelController(
                context = this,
                wm = getSystemService(android.view.WindowManager::class.java),
                cursor = cursor,
                onClose = { panel?.hide() },
                cancelCopyQueue = ::cancelCopyQueue,
            ).also { controller ->
                controller.create()
                panel = controller
            }
            cellsJob = scope.launch {
                repository.observeCells().collect { cells ->
                    panel?.updateCells(cells)
                }
            }
        }
        existing.show()
    }

    private fun cancelCopyQueue() {
        // The in-app sequential queue and the overlay would fight over the
        // clipboard; the overlay tap is the newer user intent, so it wins.
        try {
            startService(
                Intent(this, CopyService::class.java).setAction(CopyService.ACTION_CANCEL)
            )
        } catch (_: Exception) {
            // Queue not running (or a background-start refusal) — nothing to cancel.
        }
    }

    private fun stopOverlay() {
        val stop = Intent(this, OverlayService::class.java).setAction(ACTION_STOP)
        try {
            startService(stop)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun teardown() {
        cellsJob?.cancel()
        cellsJob = null
        panel?.hide()
        panel?.copier?.dispose()
        panel = null
        bubble?.hide()
        bubble = null
        isRunning = false
    }

    // ------------------------------------------------------------- fg notification

    private fun startForegroundCompat(): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            return true
        } catch (typed: Exception) {
            // Some OEM builds reject the explicitly-typed call. Fall back to
            // the manifest-declared type before giving up; log both paths —
            // a silent swallow here cost a debugging cycle once already.
            android.util.Log.w(TAG, "typed startForeground(specialUse) failed", typed)
            return try {
                startForeground(NOTIFICATION_ID, buildNotification())
                true
            } catch (plain: Exception) {
                android.util.Log.e(TAG, "startForeground failed entirely", plain)
                stopSelf()
                false
            }
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Плавающее окно", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Кружок и панель ячеек поверх приложений"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val open = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val stop = android.app.PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_float_window)
            .setContentTitle("ClipCells — плавающее окно")
            .setContentText("Тап по ячейке в панели копирует сообщение")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(0, "Скрыть", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.clipcells.app.action.OVERLAY_START"
        const val ACTION_STOP = "com.clipcells.app.action.OVERLAY_STOP"
        const val ACTION_DISMISS_PANEL = "com.clipcells.app.action.OVERLAY_DISMISS_PANEL"
        const val ACTION_TEST_COPY = "com.clipcells.app.action.OVERLAY_TEST_COPY"
        const val EXTRA_SHOW_PANEL = "show_panel"

        private const val TAG = "ClipCellsOverlay"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 42

        /** In-process liveness flag for the main screen toggle. */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context, showPanel: Boolean = false) {
            val intent = Intent(context, OverlayService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SHOW_PANEL, showPanel)
            try {
                // minSdk 26 — startForegroundService always exists.
                context.startForegroundService(intent)
            } catch (_: Exception) {
                // Background start refused — the toggle UI is foreground,
                // so this only happens in exotic races; the flag stays false.
            }
        }

        fun stop(context: Context) {
            if (!isRunning) return
            try {
                context.startService(
                    Intent(context, OverlayService::class.java).setAction(ACTION_STOP)
                )
            } catch (_: Exception) {
            }
        }
    }
}
