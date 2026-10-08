package com.clipcells.app.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
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

        /**
         * Границы сфокусированного редактируемого поля на экране — чтобы
         * панель вставки ВСТАЛА НЕ ЗАКРЫВАЯ его (требование пользователя:
         * поле обязано оставаться видимым, иначе вставлять некуда).
         * Binder-чтение — с любого потока, бюджет узлов ограничен.
         */
        fun focusedFieldBounds(selfPkg: String): Rect? {
            val service = instance ?: return null
            return try {
                val node = findEditableNode(service, selfPkg) ?: return null
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.isEmpty) null else rect
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * Вставка текста В ПОЗИЦИЮ КУРСОРА сфокусированного поля (не заменяет
         * весь текст): читает текущий текст и выделение узла, склеивает
         * «до курсора + вставка + после» и пишет одним ACTION_SET_TEXT.
         * Нет текста/выделения — аппенд в конец; поле пустое — просто запись.
         * Binder-вызов — звать с рабочего потока.
         */
        fun pasteAtCursor(selfPkg: String, text: String): PasteResult {
            val service = instance ?: return PasteResult.NoService
            return try {
                val node = findEditableNode(service, selfPkg) ?: return PasteResult.NoField
                val current = node.text
                val args = Bundle()
                val payload = if (current.isNullOrEmpty()) {
                    text
                } else {
                    val start = node.textSelectionStart.coerceIn(0, current.length)
                    val end = node.textSelectionEnd.coerceIn(start, current.length)
                    // Выделение есть — заменяем его; курсор — вставляем в него.
                    buildString {
                        append(current.subSequence(0, start))
                        append(text)
                        append(current.subSequence(end, current.length))
                    }
                }
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, payload
                )
                val ok = try {
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                } catch (_: Throwable) { false }
                if (ok) PasteResult.Pasted
                else PasteResult.Rejected(node.className?.toString() ?: "?")
            } catch (_: Throwable) {
                PasteResult.NoField
            }
        }

        /** Общий поиск целевого узла: сфокусированное поле, иначе первый editable. */
        private fun findEditableNode(
            service: PasteAccessibilityService,
            selfPkg: String,
        ): AccessibilityNodeInfo? {
            val windowList = try { service.windows } catch (_: Throwable) { null } ?: return null
            for (window in windowList.sortedByDescending { it.isActive }) {
                val root = try { window.root } catch (_: Throwable) { null } ?: continue
                if (root.packageName == selfPkg) continue
                val focused = try {
                    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                } catch (_: Throwable) { null }
                if (focused != null && focused.isEditable) return focused
                findFirstEditable(root)?.let { return it }
            }
            return null
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
