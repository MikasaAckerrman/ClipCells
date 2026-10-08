package com.clipcells.app

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.clipcells.app.overlay.OverlayPanel

/**
 * Invisible trampoline for "window only, never the app": external launchers
 * (shell tests, automation, the future donut launcher) start THIS activity —
 * fully translucent, no UI, own task, gone in the same frame — while it
 * launches the overlay window. The user's current app stays on screen
 * untouched; only the floating panel appears.
 *
 * Copy-as-File pattern: no service is involved anywhere — the visible
 * overlay window itself keeps the process alive and exempt from the
 * freezer, and ✕ removing the window lets the OS park the process.
 */
class OverlayTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Settings.canDrawOverlays(this)) {
            if (intent.getBooleanExtra(EXTRA_PASTE_MODE, false)) {
                // Режим из шторки (плитка «Вставка»): статичная панель
                // быстрой вставки в сфокусированное поле, без клавиатуры.
                com.clipcells.app.overlay.OverlayPastePanel.launch(this)
            } else {
                OverlayPanel.launch(this, testCopy = intent.getBooleanExtra(EXTRA_TEST_COPY, false))
            }
        } else {
            Toast.makeText(this, R.string.overlay_permission_missing, Toast.LENGTH_SHORT).show()
        }

        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_TEST_COPY = "test_copy"
        const val EXTRA_PASTE_MODE = "paste_mode"
    }
}
