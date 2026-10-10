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
    /** Поле есть и сфокусировано, но НЕ редактируемое (терминалы, WebView):
     *  прямой записи нет — панель обязана пройти буферным путём ACTION_PASTE. */
    data class NotEditable(val nodeClass: String) : PasteResult
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
         * SELF-HEAL (v0.15.3): системный список a11y-сервисов регулярно
         * ЗАТИРАЕТСЯ (ROM-чистки, другие приложения дописывают «только себя»).
         * Приложение само дописывает себя в КОНЕЦ списка (чужие записи не
         * трогаем, разделитель — двоеточие) и поднимает общий выключатель.
         * Нужен WRITE_SECURE_SETTINGS (выдаётся рутом один раз, переживает
         * обновления); без него — тихий no-op и обычный фидбек.
         */
        fun ensureEnabled(context: Context): Boolean {
            if (isEnabledInSettings(context)) return true
            val mine = ComponentName(context, PasteAccessibilityService::class.java)
                .flattenToString()
            return try {
                val current = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )?.trim().orEmpty()
                val merged = when {
                    current.isEmpty() || current == "null" -> mine
                    current.split(':').any { it.equals(mine, ignoreCase = true) } -> current
                    else -> "$current:$mine"
                }
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    merged,
                )
                Settings.Secure.putInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1,
                )
                android.util.Log.i("ClipCellsPaste", "a11y self-healed: $merged")
                true
            } catch (e: Exception) {
                android.util.Log.w("ClipCellsPaste", "a11y self-heal failed", e)
                false
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
        fun pasteAtCursor(selfPkg: String, text: String): PasteResult =
            pasteAtCursorInternal(selfPkg, text, includeSelf = false)

        /** Автотест E2E: не скипать собственный пакет (поле теста — наше). */
        fun pasteAtCursorForTest(selfPkg: String, text: String): PasteResult =
            pasteAtCursorInternal(selfPkg, text, includeSelf = true)

        private fun pasteAtCursorInternal(
            selfPkg: String,
            text: String,
            includeSelf: Boolean,
        ): PasteResult {
            val service = instance ?: return PasteResult.NoService
            return try {
                // 1) Сфокусированный узел ЛЮБОГО типа (терминал не editable!):
                //    не редактируемый — буферный путь (ACTION_PASTE).
                findFocusedNode(service, selfPkg, includeSelf)?.let { focused ->
                    if (!focused.isEditable) {
                        return if (focused.isPassword) {
                            PasteResult.Rejected("парольное поле")
                        } else {
                            PasteResult.NotEditable(focused.className?.toString() ?: "?")
                        }
                    }
                }
                val node = findEditableNode(service, selfPkg, includeSelf) ?: return PasteResult.NoField
                // Парольные поля не трогаем — политика и безопасность.
                if (node.isPassword) return PasteResult.Rejected("парольное поле")
                val ok = setWithCursor(node, text)
                if (ok) PasteResult.Pasted
                // Узел мог протухнуть (перестройка окна чата за время вставки) —
                // перевзять СВЕЖИЙ узел и повторить один раз.
                val fresh = findEditableNode(service, selfPkg, includeSelf)
                if (!ok && fresh != null && fresh !== node) {
                    if (setWithCursor(fresh, text)) return PasteResult.Pasted
                }
                PasteResult.Rejected(node.className?.toString() ?: "?")
            } catch (_: Throwable) {
                PasteResult.NoField
            }
        }

        /** Сфокусированный узел ЛЮБОГО типа в чужом окне (терминал, WebView). */
        private fun findFocusedNode(
            service: PasteAccessibilityService,
            selfPkg: String,
            includeSelf: Boolean,
        ): AccessibilityNodeInfo? {
            try {
                val windowList = service.windows
                for (window in windowList.sortedByDescending { it.isActive }) {
                    val root = try { window.root } catch (_: Throwable) { null } ?: continue
                    if (!includeSelf && root.packageName == selfPkg) continue
                    val focused = try {
                        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    } catch (_: Throwable) { null }
                    if (focused != null) return focused
                }
            } catch (_: Throwable) {
            }
            return null
        }

        /**
         * SET_TEXT с учётом позиции КУРСОРА (не заменяет весь текст поля).
         * Панель фокусируема (IME спрятан) — поле могло потерять фокус и
         * границы выделения неизвестны (-1): тогда текст ДОПИСЫВАЕТСЯ В КОНЕЦ,
         * а не в начало (гарантия: не вклинивается перед введённым).
         */
        private fun setWithCursor(node: AccessibilityNodeInfo, text: String): Boolean {
            val current = node.text
            val args = Bundle()
            val payload = if (current.isNullOrEmpty()) {
                text
            } else {
                val rawStart = node.textSelectionStart
                val rawEnd = node.textSelectionEnd
                val selectionKnown = rawStart in 0..current.length &&
                    rawEnd in rawStart..current.length
                if (!selectionKnown) {
                    // Курсор неизвестен — дописать в конец.
                    current.toString() + text
                } else {
                    // Выделение есть — заменяем его; курсор — вставляем в него.
                    buildString {
                        append(current.subSequence(0, rawStart))
                        append(text)
                        append(current.subSequence(rawEnd, current.length))
                    }
                }
            }
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, payload
            )
            return try {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } catch (_: Throwable) { false }
        }

        /**
         * Фолбэк для полей, отвергших SET_TEXT (WebView/кастомные вью): буфер
         * кладёт ПАНЕЛЬ (пока её окно мигом фокусируемо — легальная запись),
         * здесь — только ACTION_FOCUS + ACTION_PASTE: читает буфер само
         * целевое приложение (оно в фокусе — чтение легально).
         */
        fun performPasteAction(selfPkg: String): Boolean =
            performPasteActionInternal(selfPkg, includeSelf = false)

        fun performPasteActionForTest(selfPkg: String): Boolean =
            performPasteActionInternal(selfPkg, includeSelf = true)

        /**
         * ТРЕТИЙ ЭШЕЛОН — Termux и любые View без ACTION_PASTE: сервис
         * СИНТЕЗИРУЕТ долгое нажатие в центр сфокусированного узла
         * (dispatchGesture — официальное API a11y-сервисов) → в выпавшем меню
         * (контекстное меню Termux / тулбар выделения Android) находит пункт
         * «Вставить» и кликает его — терминал читает буфер СВОИМ механизмом.
         * Звать ПОСЛЕ записи буфера, с рабочего потока.
         */
        fun pasteViaLongPressMenu(selfPkg: String): Boolean {
            val service = instance ?: return false
            return try {
                val node = findFocusedNode(service, selfPkg, includeSelf = false) ?: return false
                if (node.isPassword) return false
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.isEmpty) return false
                val cx = rect.exactCenterX()
                val cy = rect.exactCenterY()
                val path = android.graphics.Path().apply { moveTo(cx, cy) }
                val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 650)
                val dispatched = service.dispatchGesture(
                    android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(stroke).build(), null, null,
                )
                if (!dispatched) return false
                try { Thread.sleep(700) } catch (_: InterruptedException) { return false }

                // Меню всплыло: ищем кликабельный «Вставить»/«Paste» во всех окнах.
                for (attempt in 1..3) {
                    val windows = try { service.windows } catch (_: Throwable) { null } ?: break
                    for (window in windows.sortedByDescending { it.isActive }) {
                        val root = try { window.root } catch (_: Throwable) { null } ?: continue
                        val candidates =
                            root.findAccessibilityNodeInfosByText("Вставить") +
                                root.findAccessibilityNodeInfosByText("Paste") +
                                root.findAccessibilityNodeInfosByText("PASTE")
                        for (c in candidates) {
                            val clickable = if (c.isClickable) c else c.parent
                            if (clickable != null) {
                                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                android.util.Log.i(
                                    "ClipCellsPaste",
                                    "long-press menu paste: clicked ${c.text} in ${window.root?.packageName}"
                                )
                                return true
                            }
                        }
                    }
                    try { Thread.sleep(250) } catch (_: InterruptedException) { break }
                }
                false
            } catch (_: Throwable) {
                false
            }
        }

        private fun performPasteActionInternal(selfPkg: String, includeSelf: Boolean): Boolean {
            val service = instance ?: return false
            return try {
                // Сфокусированный узел ЛЮБОГО типа: терминалы (Termux) и WebView
                // не «editable», но вставку через ACTION_PASTE поддерживают —
                // буфер к этому моменту уже записан панелью.
                val node = findFocusedNode(service, selfPkg, includeSelf)
                    ?: findEditableNode(service, selfPkg, includeSelf)
                    ?: return false
                if (node.isPassword) return false
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * Общий поиск целевого узла: сфокусированное поле, иначе первый editable.
         * Ретраи: шторка сворачивается ~300-500мс — окно чата появляется в
         * списке интерактивных окон не мгновенно; 3 попытки с паузой 200мс.
         */
        private fun findEditableNode(
            service: PasteAccessibilityService,
            selfPkg: String,
            includeSelf: Boolean = false,
        ): AccessibilityNodeInfo? {
            repeat(3) { attempt ->
                try {
                    // 1) Полный список интерактивных окон (требует
                    // flagRetrieveInteractiveWindows в конфиге сервиса).
                    val windowList = service.windows
                    for (window in windowList.sortedByDescending { it.isActive }) {
                        val root = try { window.root } catch (_: Throwable) { null } ?: continue
                        if (!includeSelf && root.packageName == selfPkg) continue
                        val focused = try {
                            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        } catch (_: Throwable) { null }
                        if (focused != null && focused.isEditable) return focused
                        findFirstEditable(root)?.let { return it }
                    }
                    // 2) Активное окно напрямую — когда список ещё не обновился.
                    val activeRoot = try { service.rootInActiveWindow } catch (_: Throwable) { null }
                    if (activeRoot != null && (includeSelf || activeRoot.packageName != selfPkg)) {
                        val focused = try {
                            activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        } catch (_: Throwable) { null }
                        if (focused != null && focused.isEditable) return focused
                        findFirstEditable(activeRoot)?.let { return it }
                    }
                } catch (_: Throwable) {
                }
                if (attempt < 2) {
                    try { Thread.sleep(200) } catch (_: InterruptedException) { return null }
                }
            }
            return null
        }

        /** Fallback: первый редактируемый узел окна (обход без фокуса ввода). */
        private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            // Бюджет 2000: дерево чата широкое (список сообщений), поле ввода
            // лежит глубоко — BFS должен его достичь.
            var budget = 2_000
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
