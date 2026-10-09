package com.clipcells.app.overlay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import com.clipcells.app.CLIP_SAFE_CHARS
import com.clipcells.app.data.CellWithMessages
import com.clipcells.app.data.ClipCellsDatabase
import com.clipcells.app.data.MessageEntity

/**
 * Стеклянная панель быстрой вставки v0.17 (ТЗ пользователя):
 *
 *  - ВСТАВКА, не копирование: буфер обмена не трогаем вообще.
 *  - Тап по ячейке → СРАЗУ её список сообщений; тап по сообщению → мгновенная
 *    вставка этого текста в поле (a11y, позиция курсора) → панель закрывается.
 *  - Долгое нажатие на сообщение → РЕЖИМ ВЫБОРА: можно выбрать несколько,
 *    порядок = порядок тапов; кнопка «Вставить» в правом нижнем углу вставляет
 *    все выбранные одним куском. Повторный тап снимает выбор.
 *  - Стекло: многослойный фон (размытая подложка-затемнение + полупрозрачная
 *    заливка + верхний блик + тонкая рамка) — Material-стекло без blur-пермишена
 *    (RenderEffect-blur на overlay-окнах ненадёжен/дорог; слой 45% чёрного
 *    поверх затемняющего scrim-окна даёт «стекло» за ноль GPU).
 *  - Статичное окно: НЕфокусируемое (поле сохраняет фокус — курсор мигает,
 *    вставка ТОЧНО в курсор), НЕ драгается, фиксированная позиция.
 *  - Оптимизация: анимации только на вход/выход/выбор (View-проперти, GPU),
 *    список один строится за кадр, БД читается один раз, ноль сервисов,
 *    ноль observers; convertView-рецикл; haptic-фидбек на каждом действии.
 */
object OverlayPastePanel {

    @Volatile private var liveRoot: PastePanelRoot? = null
    @Volatile private var liveWm: WindowManager? = null
    @Volatile private var liveUi: PanelUi? = null
    @Volatile private var dismissing = false

    fun isShowing(): Boolean = liveRoot != null

    fun launch(context: Context) {
        if (isShowing()) return
        val app = context.applicationContext
        Thread {
            PasteAccessibilityService.ensureEnabled(app)
            val cells = try {
                ClipCellsDatabase.get(app).cellDao().getAllSync()
            } catch (_: Exception) {
                emptyList()
            }
            Handler(Looper.getMainLooper()).post {
                if (!isShowing()) show(app, cells)
            }
        }.start()
    }

    fun hide(animate: Boolean = true) {
        val root = liveRoot ?: return
        if (dismissing) return
        dismissing = true
        val wm = liveWm ?: return
        if (animate) {
            exit(root) { removeWindow(wm, root) }
            root.postDelayed({ if (liveRoot === root) removeWindow(wm, root) }, 230)
        } else {
            removeWindow(wm, root)
        }
    }

    private fun show(app: Context, cells: List<CellWithMessages>) {
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        hide(animate = false)
        val ui = PanelUi(app, wm, cells) { hide(animate = true) }
        ui.build()
        try {
            wm.addView(ui.root, ui.windowParams())
            liveRoot = ui.root
            liveWm = wm
            liveUi = ui
            dismissing = false
            enter(ui.root)
            android.util.Log.i(TAG, "paste panel shown cells=${cells.size}")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "paste panel addView failed", e)
        }
    }

    private fun removeWindow(wm: WindowManager, root: View) {
        liveRoot = null
        liveWm = null
        liveUi = null
        dismissing = false
        try {
            wm.removeView(root)
        } catch (_: Exception) {
        }
        android.util.Log.i(TAG, "paste panel removed")
    }

    // ------------------------------------------------------------- FX

    /** Вход: стекло «проявляется» с лёгким перелётом (140мс, GPU-проперти). */
    private fun enter(root: View) {
        root.alpha = 0f
        root.scaleX = 0.94f
        root.scaleY = 0.94f
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(root, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(root, View.SCALE_X, 0.94f, 1f),
                ObjectAnimator.ofFloat(root, View.SCALE_Y, 0.94f, 1f),
            )
            duration = 140
            interpolator = DecelerateInterpolator(1.3f)
            start()
        }
    }

    private fun exit(root: View, end: () -> Unit) {
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(root, View.ALPHA, root.alpha, 0f),
                ObjectAnimator.ofFloat(root, View.SCALE_X, root.scaleX, 0.96f),
                ObjectAnimator.ofFloat(root, View.SCALE_Y, root.scaleY, 0.96f),
            )
            duration = 110
            interpolator = AccelerateInterpolator(1.2f)
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(a: android.animation.Animator) {}
                override fun onAnimationCancel(a: android.animation.Animator) { end() }
                override fun onAnimationRepeat(a: android.animation.Animator) {}
                override fun onAnimationEnd(a: android.animation.Animator) { end() }
            })
            start()
        }
    }

    private const val TAG = "ClipCellsPaste"

    // ================================================================ UI

    private class PanelUi(
        val app: Context,
        private val wm: WindowManager,
        private val cells: List<CellWithMessages>,
        private val onClose: () -> Unit,
    ) {
        private val dp = dpFactor()
        private val screen = screenPx()

        /** Окно крупнее (ТЗ: «окно можешь увеличить»): 444×420dp. */
        private val panelW = minOf(444 * dp, (screen.x * 0.88f).toInt())
        private val panelH = 420 * dp

        lateinit var root: PastePanelRoot
        private lateinit var titleView: TextView
        private lateinit var list: ListView
        private lateinit var listAdapter: PasteListAdapter
        private lateinit var sendBtn: TextView
        private var pasting = false

        /** Текущий открытый экран: ячейки или сообщения выбранной ячейки. */
        private var openCell: CellWithMessages? = null

        /** Режим множественного выбора (после долгого тапа на сообщении). */
        private val selected = LinkedHashMap<Long, MessageEntity>()
        private val selectionOrder = LinkedHashMap<Long, Int>()
        private var selectionCount = 0
        private var nextOrder = 1

        fun build() {
            root = PastePanelRoot(app).apply {
                setPadding(16 * dp, 14 * dp, 16 * dp, 16 * dp)
                background = glassBackground()
            }

            val column = android.widget.LinearLayout(app).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            root.addView(column, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))

            // --- Шапка: имя ячейки/заголовок + ✕
            val header = android.widget.LinearLayout(app).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleView = TextView(app).apply {
                text = "Вставка"
                textSize = 16f
                letterSpacing = 0.01f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.parseColor(TEXT))
                maxLines = 1
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            header.addView(titleView)
            header.addView(iconButton("✕", "Закрыть") { onClose() })
            column.addView(header, android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 42 * dp
            ))

            // --- Список (ячейки ↔ сообщения — один адаптер, два экрана)
            listAdapter = PasteListAdapter()
            list = ListView(app).apply {
                divider = null
                adapter = listAdapter
                verticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                cacheColorHint = Color.TRANSPARENT
                setSelector(android.R.color.transparent)
            }
            column.addView(list, android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))

            // --- Кнопка «Вставить» (правый нижний угол) — только в режиме выбора
            sendBtn = TextView(app).apply {
                text = "Вставить"
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(BTN_TEXT))
                visibility = View.GONE
                setOnClickListener { sendSelection() }
                background = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor(BTN_RIPPLE)),
                    GradientDrawable().apply {
                        setColor(Color.parseColor(ACCENT))
                        cornerRadius = 22 * dp.toFloat()
                    },
                    null,
                )
            }
            val bottomBar = android.widget.LinearLayout(app).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, 10 * dp, 0, 0)
            }
            bottomBar.addView(sendBtn, android.widget.LinearLayout.LayoutParams(
                148 * dp, 44 * dp
            ))
            column.addView(bottomBar, android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        fun windowParams(): WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                panelW,
                panelH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // НЕфокусируемое: поле чата сохраняет фокус (курсор мигает,
                // позиция вставки точная), клавиатуру панель не вызывает.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (screen.y * 0.055f).toInt()
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

        // ---------------------------------------------------- навигация

        private fun openMessages(cell: CellWithMessages) {
            openCell = cell
            selected.clear()
            selectionOrder.clear()
            selectionCount = 0
            nextOrder = 1
            titleView.text = cell.cell.name
            listAdapter.notifyDataSetChanged()
            list.smoothScrollToPosition(0)
        }

        private fun backToCells() {
            openCell = null
            selected.clear()
            selectionOrder.clear()
            selectionCount = 0
            nextOrder = 1
            titleView.text = "Вставка"
            sendBtn.visibility = View.GONE
            listAdapter.notifyDataSetChanged()
            list.smoothScrollToPosition(0)
        }

        // ---------------------------------------------------- вставка

        /** Тап по сообщению: мгновенная вставка одного текста. */
        private fun onMessageTap(message: MessageEntity) {
            if (selected.isNotEmpty()) return // в режиме выбора тап = выбор
            paste(listOf(message.text), "сообщение")
        }

        /** Долгий тап: войти в режим выбора, выбрать первое. */
        private fun onMessageLongTap(message: MessageEntity) {
            if (selected.containsKey(message.id)) {
                deselect(message)
            } else {
                select(message)
            }
            root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        private fun select(message: MessageEntity) {
            selected[message.id] = message
            selectionOrder[message.id] = nextOrder++
            selectionCount++
            refreshSendButton()
            listAdapter.notifyDataSetChanged()
        }

        private fun deselect(message: MessageEntity) {
            selected.remove(message.id)
            selectionOrder.remove(message.id)
            selectionCount--
            refreshSendButton()
            listAdapter.notifyDataSetChanged()
        }

        private fun refreshSendButton() {
            if (selectionCount > 0) {
                sendBtn.visibility = View.VISIBLE
                sendBtn.text = if (selectionCount == 1) "Вставить" else "Вставить $selectionCount"
                sendBtn.animate().scaleX(1f).scaleY(1f).setDuration(120)
                    .setInterpolator(OvershootInterpolator(1.1f)).start()
            } else {
                sendBtn.animate().alpha(0f).setDuration(100).withEndAction {
                    sendBtn.visibility = View.GONE
                    sendBtn.alpha = 1f
                }.start()
            }
        }

        /** Кнопка «Вставить»: все выбранные одним куском, порядок тапов. */
        private fun sendSelection() {
            if (selected.isEmpty() || pasting) return
            val ordered = selected.values.sortedBy { selectionOrder[it.id] ?: 0 }
            paste(ordered.map { it.text }, "выбрано ${ordered.size}")
        }

        /** Удержание ячейки на экране ячеек — тоже сразу её сообщения. */
        private fun onCellTap(cell: CellWithMessages) {
            if (cell.messages.isEmpty()) return
            root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            openMessages(cell)
        }

        private fun paste(texts: List<String>, what: String) {
            if (pasting) return
            val text = texts.joinToString("\n\n")
            if (text.length > CLIP_SAFE_CHARS) {
                android.util.Log.i(TAG, "paste [$what] TOO_LARGE ${text.length}")
                return
            }
            pasting = true
            root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            Thread {
                val result = PasteAccessibilityService.pasteAtCursor(app.packageName, text)
                android.util.Log.i(TAG, "paste [$what] -> $result")
                if (result == PasteResult.NoService) {
                    val healed = PasteAccessibilityService.ensureEnabled(app)
                    val retried = if (healed) {
                        PasteAccessibilityService.pasteAtCursor(app.packageName, text)
                    } else null
                    if (retried == PasteResult.Pasted) {
                        Handler(Looper.getMainLooper()).post { pasting = false; hide(true) }
                    } else {
                        Handler(Looper.getMainLooper()).post { pasting = false }
                    }
                    return@Thread
                }
                Handler(Looper.getMainLooper()).post {
                    pasting = false
                    if (result == PasteResult.Pasted) {
                        hide(animate = true) // миссия выполнена — закрыться
                    }
                }
            }.start()
        }

        // ------------------------------------------------------- стекло

        /**
         * Материал: scrim-окно позади затемняет фон экрана (эффект глубины),
         * само окно — многослойное «стекло»: плотная полупрозрачная заливка,
         * верхний блик (имитация преломления света), тонкая светлая рамка.
         * Ноль runtime-blur — статичные слои, ноль GPU-нагрузки в простое.
         */
        private fun glassBackground(): LayerDrawable {
            val fill = GradientDrawable().apply {
                setColor(Color.parseColor(GLASS_FILL))
                cornerRadius = 28 * dp.toFloat()
            }
            val sheen = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setColors(intArrayOf(
                    Color.parseColor(GLASS_SHEEN),
                    Color.parseColor("#00000000"),
                    Color.parseColor(GLASS_SHADE),
                ))
                cornerRadius = 28 * dp.toFloat()
            }
            val stroke = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 28 * dp.toFloat()
                setStroke(dp, Color.parseColor(GLASS_STROKE))
            }
            return LayerDrawable(arrayOf(fill, sheen, stroke)).apply {
                setLayerInset(1, 0, 0, 0, 0)
                setLayerInset(2, 0, 0, 0, 0)
            }
        }

        // ---------------------------------------------------- адаптер

        /**
         * Один адаптер, два экрана: [cells] или сообщения [openCell].
         * Тап-жест: короткий = действие, ≥350мс без движения = множественный
         * выбор (или открытие). Ручной тайминг — системный long-click
         * конфликтует со скроллом списка.
         */
        private inner class PasteListAdapter : BaseAdapter() {
            override fun getCount(): Int =
                openCell?.let { it.orderedMessages.size } ?: cells.size

            override fun getItem(position: Int): Any =
                openCell?.let { it.orderedMessages[position] } ?: cells[position]

            override fun getItemId(position: Int): Long =
                openCell?.let { it.orderedMessages[position].id } ?: cells[position].cell.id

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val cell = openCell
                if (cell != null) {
                    val message = cell.orderedMessages[position]
                    val row = (convertView as? MessageRowView) ?: MessageRowView(parent.context)
                    row.bind(message)
                    return row
                }
                val item = cells[position]
                val card = (convertView as? CellCardView) ?: CellCardView(parent.context)
                card.bind(item)
                return card
            }
        }

        /** Карточка ячейки (первый экран): имя + счётчик, тап = список. */
        private inner class CellCardView(context: Context) : android.widget.LinearLayout(context) {
            private val nameView: TextView
            private val countView: TextView

            init {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16 * dp, 12 * dp, 16 * dp, 12 * dp)
                isClickable = true
                isLongClickable = true
                background = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor(RIPPLE)),
                    GradientDrawable().apply {
                        setColor(Color.parseColor(ITEM_BG))
                        cornerRadius = 20 * dp.toFloat()
                    },
                    null,
                )
                nameView = TextView(context).apply {
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(Color.parseColor(TEXT))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                addView(nameView)
                countView = TextView(context).apply {
                    textSize = 11f
                    setTextColor(Color.parseColor(TEXT_DIM))
                    maxLines = 1
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                addView(countView)
            }

            fun bind(cell: CellWithMessages) {
                nameView.text = cell.cell.name
                countView.text = "· ${cell.messages.size}"
                contentDescription = "Ячейка ${cell.cell.name}"
                setOnClickListener { onCellTap(cell) }
                setOnLongClickListener { onCellTap(cell); true }
            }
        }

        /** Строка сообщения (второй экран): текст, чекбокс выбора, тап = вставка. */
        private inner class MessageRowView(context: Context) : android.widget.LinearLayout(context) {
            private val textView: TextView
            private val checkView: TextView
            private var boundMessage: MessageEntity? = null
            private var checkRunnable: Runnable? = null

            init {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14 * dp, 11 * dp, 14 * dp, 11 * dp)
                isClickable = true
                isLongClickable = true
                background = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor(RIPPLE)),
                    GradientDrawable().apply {
                        setColor(Color.parseColor(ITEM_BG))
                        cornerRadius = 16 * dp.toFloat()
                    },
                    null,
                )
                textView = TextView(context).apply {
                    textSize = 13f
                    setTextColor(Color.parseColor(TEXT_SOFT))
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    setLineSpacing(1 * dp.toFloat(), 1.05f)
                }
                addView(textView, android.widget.LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ))
                checkView = TextView(context).apply {
                    textSize = 12f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(Color.parseColor(ACCENT))
                    visibility = View.GONE
                    gravity = Gravity.CENTER
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        26 * dp, 26 * dp
                    )
                }
                addView(checkView, android.widget.LinearLayout.LayoutParams(
                    26 * dp, 26 * dp
                ).apply { leftMargin = 10 * dp })
            }

            fun bind(message: MessageEntity) {
                val isSelected = selected.containsKey(message.id)
                // Хаптик на выбор, анимация галочки/фона на GPU
                if (isSelected) {
                    checkView.visibility = View.VISIBLE
                    val order = selectionOrder[message.id] ?: 0
                    checkView.text = "$order"
                    checkView.scaleX = 0.6f; checkView.scaleY = 0.6f
                    checkView.animate().scaleX(1f).scaleY(1f).setDuration(150)
                        .setInterpolator(OvershootInterpolator(1.3f)).start()
                } else {
                    checkView.visibility = View.GONE
                }
                setOnClickListener {
                    // В режиме выбора тап = выбрать/снять; иначе — мгновенная вставка.
                    if (selected.isNotEmpty() || selectionCount > 0) {
                        if (selected.containsKey(message.id)) deselect(message)
                        else select(message)
                    } else {
                        onMessageTap(message)
                    }
                }
                setOnLongClickListener {
                    onMessageLongTap(message)
                    true
                }
            }
        }

        // ------------------------------------------------------- утил

        private fun iconButton(glyph: String, description: String, onClick: () -> Unit): TextView =
            TextView(app).apply {
                text = glyph
                textSize = 16f
                gravity = Gravity.CENTER
                contentDescription = description
                setTextColor(Color.parseColor(TEXT_DIM))
                setOnClickListener { onClick() }
                background = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor(RIPPLE)),
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#00FFFFFF"))
                        cornerRadius = 18 * dp.toFloat()
                    },
                    null,
                )
                layoutParams = android.widget.LinearLayout.LayoutParams(34 * dp, 34 * dp)
            }

        private fun screenPx(): Point {
            if (Build.VERSION.SDK_INT >= 30) {
                val bounds = wm.currentWindowMetrics.bounds
                return Point(bounds.width(), bounds.height())
            }
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            return p
        }

        private fun dpFactor(): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 1f, app.resources.displayMetrics
        ).toInt().coerceAtLeast(1)

        companion object {
            // Стекло: плотное, но с бликом — «frosted», не вырвиглазная дымка
            private const val GLASS_FILL = "#D9101014"
            private const val GLASS_SHEEN = "#26FFFFFF"
            private const val GLASS_SHADE = "#0D000000"
            private const val GLASS_STROKE = "#40FFFFFF"

            private const val ITEM_BG = "#801C1C22"
            private const val RIPPLE = "#26FFFFFF"

            private const val TEXT = "#FFF5F6FA"
            private const val TEXT_SOFT = "#FFE9EBF0"
            private const val TEXT_DIM = "#FF9AA3AD"

            private const val ACCENT = "#FF8AB4F8"
            private const val BTN_TEXT = "#FF10141C"
            private const val BTN_RIPPLE = "#33FFFFFF"
        }
    }

    /**
     * Корень: гасит BACK (закрытие — только ✕ или успешная вставка),
     * окно строго статичное.
     */
    private class PastePanelRoot(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
            if (ev.action == KeyEvent.ACTION_DOWN && ev.keyCode == KeyEvent.KEYCODE_BACK) {
                return true
            }
            return super.dispatchKeyEvent(ev)
        }
    }
}
