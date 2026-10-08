package com.clipcells.app.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Результат прямой вставки в поле. */
sealed interface PasteResult {
    object Pasted : PasteResult
    object NoService : PasteResult
    object NoField : PasteResult
    data class Rejected(val nodeClass: String) : PasteResult
}

/**
 * Прямая вставка текста в поле ввода ДРУГОГО приложения — в обход буфера обмена
 * и клавиатуры (v0.14: Gboard затирает системный буфер своим payload ~20МБ и
 * системная вставка ломается; единственный надёжный путь — a11y-запись текста
 * прямо в сфокусированное поле).
 *
 * Пассивный сервис: onAccessibilityEvent ПУСТ (ноль CPU в простое), работа
 * только по тапу на плавающую кнопку «Вставить».
 */
class PasteAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Специально пусто — ничего не следим, ноль расхода.
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: PasteAccessibilityService? = null

        fun isReady(): Boolean = instance != null

        /** Включён ли сервис в системных настройках (для UI-статуса). */
        fun isEnabledInSettings(context: Context): Boolean {
            val component = ComponentName(context, PasteAccessibilityService::class.java)
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val full = component.flattenToString()
            val short = component.flattenToShortString()
            return flat.split(':').any {
                it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true)
            }
        }

        /**
         * Вставляет [text] в сфокусированное редактируемое поле активного
         * чужого окна. Binder-вызов — звать с рабочего потока. Наш собственный
         * оверлей пропускается, свои поля не трогаем.
         */
        fun pasteIntoFocusedField(selfPkg: String, text: String): PasteResult {
            val service = instance ?: return PasteResult.NoService
            return try {
                val windowList = service.windows
                for (window in windowList.sortedByDescending { it.isActive }) {
                    val root = try { window.root } catch (_: Throwable) { null } ?: continue
                    if (root.packageName == selfPkg) continue

                    // Сначала поле с фокусом ввода — куда смотрит курсор.
                    val focused = try {
                        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    } catch (_: Throwable) { null }
                    val node = when {
                        focused != null && focused.isEditable -> focused
                        else -> findFirstEditable(root)
                    } ?: continue

                    val args = Bundle()
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                    )
                    val ok = try {
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    } catch (_: Throwable) { false }
                    if (ok) return PasteResult.Pasted
                    return PasteResult.Rejected(node.className?.toString() ?: "?")
                }
                PasteResult.NoField
            } catch (_: Throwable) {
                PasteResult.NoField
            }
        }

        /** Fallback: первый редактируемый узел окна (обход без фокуса ввода). */
        private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var budget = 400
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.addLast(root)
            while (queue.isNotEmpty() && budget > 0) {
                val node = queue.removeFirst()
                budget--
                if (node.isEditable) return node
                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
                    queue.addLast(child)
                }
            }
            return null
        }
    }
}
