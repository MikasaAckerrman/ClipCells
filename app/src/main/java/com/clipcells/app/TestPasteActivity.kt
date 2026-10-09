package com.clipcells.app

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import com.clipcells.app.overlay.PasteAccessibilityService
import com.clipcells.app.overlay.PasteResult

/**
 * ПОЛНОСТЬЮ АВТОМАТИЧЕСКИЙ E2E-тест вставки (без тапов по экрану — правило):
 *
 * 1. Своё поле с АВТОФОКУСОМ (курсор реально стоит — как в чате юзера).
 * 2. REALный путь вставки: ensureEnabled (self-heal списка a11y) →
 *    a11y-поиск сфокусированного поля → SET_TEXT в позицию курсора.
 * 3. Readback содержимого поля → вердикт PASS/FAIL в лог (тег ClipCellsE2E).
 *
 * Запуск: adb shell am start -n com.clipcells.app/.TestPasteActivity
 * Проверка: adb shell logcat -d -s ClipCellsE2E
 */
class TestPasteActivity : Activity() {

    private lateinit var field: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        field = EditText(this).apply {
            hint = "E2E поле"
            setText("начало|")
            setSelection(text.length) // курсор в конец
        }
        setContentView(field)

        // Фокус появляется после resume — даём окну встать, затем гоняем тест.
        field.postDelayed({ runE2E() }, 700)
    }

    private fun runE2E() {
        Thread {
            val marker = "CLIPCELLS_E2E_OK_${System.currentTimeMillis()}"
            val ensure = PasteAccessibilityService.ensureEnabled(this)
            val result = PasteAccessibilityService.pasteAtCursorForTest(packageName, marker)
            runOnUiThread {
                val content = field.text.toString()
                val pass = marker in content
                // Курсорная семантика: исходный текст сохранён, маркер дописан.
                val keptPrefix = content.startsWith("начало|")
                Log.i(
                    TAG,
                    "E2E ensure=$ensure paste=$result pass=$pass " +
                        "prefixKept=$keptPrefix len=${content.length} content='${content.take(60)}'"
                )
                Log.i(TAG, "E2E VERDICT: ${if (pass && keptPrefix) "PASS" else "FAIL"}")
                finish()
            }
        }.start()
    }

    private companion object {
        const val TAG = "ClipCellsE2E"
    }
}
