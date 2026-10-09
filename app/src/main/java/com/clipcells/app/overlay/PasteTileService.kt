package com.clipcells.app.overlay

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.clipcells.app.OverlayTrampolineActivity

/**
 * Плитка «Вставка ClipCells» в шторке (v0.15, ТЗ пользователя): тап по плитке
 * сворачивает шторку и открывает статичную панель быстрой вставки поверх
 * текущего приложения — тап по ячейке вставляет текст прямо в сфокусированное
 * поле (в обход буфера и клавиатуры).
 *
 * TileService — системно-управляемый сервис: существует только в момент
 * взаимодействия с плиткой, фоново НЕ живёт (ноль батареи). Панель открывает
 * через трамплин: окно TYPE_APPLICATION_OVERLAY само держит процесс живым,
 * пока открыто (архитектура Copy as File — никаких startService/FGS).
 */
class PasteTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // Плитка ВСЕГДА в приглушённом состоянии (STATE_INACTIVE): белый
        // «активный» вид плитки не нужен — доступность в один тап не зависит
        // от подсветки, а спокойная плитка не горит в шторке.
        qsTile?.let {
            it.state = Tile.STATE_INACTIVE
            it.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) return
        val intent = Intent(this, OverlayTrampolineActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(OverlayTrampolineActivity.EXTRA_PASTE_MODE, true)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val pi = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) {
            // ROM-специфика (Vivo): деградация — панель доступна из приложения.
        }
    }
}
