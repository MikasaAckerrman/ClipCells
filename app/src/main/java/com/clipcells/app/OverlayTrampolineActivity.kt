package com.clipcells.app

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.clipcells.app.overlay.OverlayService

/**
 * Invisible trampoline for "window only, never the app": external launchers
 * (shell tests, automation, the future donut launcher) start THIS activity —
 * fully translucent, no UI, own task, gone in the same frame — while it
 * legally starts the foreground service (Android 15 denies FGS starts from
 * non-app callers). The user's current app stays on screen untouched; only
 * the floating panel appears.
 *
 * Same proven pattern as Copy-as-File's SaveActivity trampoline.
 */
class OverlayTrampolineActivity : Activity() {

    private companion object {
        const val EXTRA_TEST_COPY = "test_copy"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Settings.canDrawOverlays(this)) {
            OverlayService.start(this, showPanel = intent.getBooleanExtra(
                OverlayService.EXTRA_SHOW_PANEL, true))
            // Dev/CI hook: prove the focus-aware clipboard path end-to-end
            // without touching the screen.
            if (intent.getBooleanExtra(EXTRA_TEST_COPY, false)) {
                OverlayService.testCopy(this)
            }
        } else {
            Toast.makeText(this, R.string.overlay_permission_missing, Toast.LENGTH_SHORT).show()
        }

        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
