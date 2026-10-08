package com.clipcells.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.clipcells.app.CLIP_SAFE_CHARS
import com.clipcells.app.data.CellWithMessages
import com.clipcells.app.data.ClipCellsDatabase

/**
 * Панель быстрой ВСТАВКИ из шторки (v0.15, ТЗ пользователя):
 *
 *  - Статичное окно: НЕ плавает, НЕ перетаскивается, НЕ закрывает поле ввода —
 *    позиция вычисляется по границам сфокусированного поля (через a11y):
 *    панель встаёт НАД ним (или под, если сверху нет места).
 *  - Клавиатуру НЕ вызывает вообще: окно FLAG_NOT_FOCUSABLE и в нём нет ни
 *    одного текст-поля — IME не может появиться по нашей вине; фокус
 *    приложения под панелью не трогаем.
 *  - Тап по ячейке = текст ВСТАВЛЯЕТСЯ прямо в поле (a11y ACTION_SET_TEXT,
 *    в позицию курсора) — мимо буфера обмена и мимо истории клавиатуры.
 *    После успешной вставки панель сама закрывается; ✕ — ручное закрытие.
 *  - Сетка 3 колонки, 3 строки видно (+скролл, намёк 4-й), тёмно-серый стиль.
 *  - Батарея: ноль сервисов (окно держит процесс живым и вне фризера —
 *    архитектура Copy as File), БД читается одноразово при открытии,
 *    ноль наблюдателей. Удержание ячейки — список сообщений (вставка одного).
 *
 * Большой текст: ACTION_SET_TEXT идёт binder-транзакцией с лимитом ~1МБ —
 * свыше CLIP_SAFE_CHARS отказываем честно (вставляй сообщения по одному).
 */
object OverlayPastePanel {

    @Volatile private var liveRoot: PastePanelRoot? = null
    @Volatile private var liveWm: WindowManager? = null
    @Volatile private var liveUi: PanelUi? = null
    @Volatile private var dismissing = false

    fun isShowing(): Boolean = liveRoot != null

    /**
     * Одноразовая загрузка + показ. Позиция — по границам сфокусированного
     * поля ( Binder-чтение в рабочем потоке, окно в главном).
     */
    fun launch(context: Context) {
        if (isShowing()) return
        val app = context.applicationContext
        Thread {
            val bounds = PasteAccessibilityService.focusedFieldBounds(app.packageName)
            val cells = try {
                ClipCellsDatabase.get(app).cellDao().getAllSync()
            } catch (_: Exception) {
                emptyList()
            }
            Handler(Looper.getMainLooper()).post {
                if (!isShowing()) show(app, cells, bounds)
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

    private fun show(app: Context, cells: List<CellWithMessages>, field: Rect?) {
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        hide(animate = false)
        val ui = PanelUi(app, wm, cells, field) { hide(animate = true) }
        ui.build()
        try {
            wm.addView(ui.root, ui.windowParams())
            liveRoot = ui.root
            liveWm = wm
            liveUi = ui
            dismissing = false
            enter(ui.root)
            android.util.Log.i(TAG, "paste panel shown cells=${cells.size} field=${field ?: "none"}")
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

    private fun enter(root: View) {
        root.alpha = 0f
        root.scaleX = 0.97f
        root.scaleY = 0.97f
        android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(root, View.ALPHA, 0f, 1f),
                android.animation.ObjectAnimator.ofFloat(root, View.SCALE_X, 0.97f, 1f),
                android.animation.ObjectAnimator.ofFloat(root, View.SCALE_Y, 0.97f, 1f),
            )
            duration = 140
            interpolator = android.view.animation.DecelerateInterpolator(1.3f)
            start()
        }
    }

    private fun exit(root: View, end: () -> Unit) {
        android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(root, View.ALPHA, root.alpha, 0f),
                android.animation.ObjectAnimator.ofFloat(root, View.SCALE_X, root.scaleX, 0.97f),
                android.animation.ObjectAnimator.ofFloat(root, View.SCALE_Y, root.scaleY, 0.97f),
            )
            duration = 110
            interpolator = android.view.animation.AccelerateInterpolator(1.2f)
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
        private val field: Rect?,
        private val onClose: () -> Unit,
    ) {
        private val dp = dpFactor()
        private val screen = screenPx()

        /** Фиксированные размеры окна: детерминированная статичная панель. */
        private val panelW = minOf(360 * dp, (screen.x * 0.72f).toInt())
        private val panelH = 288 * dp

        lateinit var root: PastePanelRoot
        private lateinit var feedbackView: TextView
        private lateinit var contentFrame: FrameLayout
        private lateinit var grid: GridView
        private lateinit var emptyView: TextView
        private var detailList: ListView? = null
        private var detailCell: CellWithMessages? = null
        private val cellsAdapter = CellsAdapter()
        private var messagesAdapter: MessagesAdapter? = null
        private var pasting = false

        private val revertFeedback = Runnable {
            if (feedbackView.isAttachedToWindow) showHint()
        }

        fun build() {
            root = PastePanelRoot(app).apply {
                setPadding(12 * dp, 10 * dp, 12 * dp, 12 * dp)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(PANEL_BG))
                    cornerRadius = 22 * dp.toFloat()
                    setStroke(dp, Color.parseColor(STROKE))
                }
            }
            val column = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
            root.addView(column, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))

            // Заголовок БЕЗ драга — окно строго статичное (ТЗ).
            val header = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(app).apply {
                text = "Вставка"
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.parseColor(TEXT))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            header.addView(iconButton("‹", "Назад к ячейкам") { closeDetail() }.apply {
                visibility = View.GONE
                backBtn = this
            })
            header.addView(iconButton("✕", "Закрыть") { onClose() })
            column.addView(header, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 34 * dp
            ))

            feedbackView = TextView(app).apply {
                textSize = 10f
                setTextColor(Color.parseColor(TEXT_FAINT))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            showHint()
            column.addView(feedbackView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            contentFrame = FrameLayout(app)
            grid = GridView(app).apply {
                numColumns = 3
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                verticalSpacing = 8 * dp
                horizontalSpacing = 8 * dp
                adapter = cellsAdapter
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            contentFrame.addView(grid)
            emptyView = TextView(app).apply {
                text = "Нет ячеек — создайте в приложении"
                textSize = 11f
                setTextColor(Color.parseColor(TEXT_DIM))
                gravity = Gravity.CENTER
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            contentFrame.addView(emptyView)
            if (cells.isEmpty()) {
                grid.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
            }
            column.addView(contentFrame, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }

        fun windowParams(): WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                panelW,
                panelH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // НЕ фокусируемое окно: клавиатура не вызывается ВООБЩЕ,
                // фокус приложения и поля ввода под панелью не трогаем.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // Статичное размещение: не поверх поля ввода (границы — из a11y).
                x = ((screen.x - panelW) / 2).coerceAtLeast(0)
                y = placeY(field, panelH, screen.y, 12 * dp)
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

        /** Куда встать: над полем (стандарт), под ним, или верх экрана. */
        private fun placeY(field: Rect?, panelH: Int, screenH: Int, margin: Int): Int {
            val f = field ?: return (screenH * 0.10f).toInt()
            val above = f.top - panelH - margin
            val below = f.bottom + margin
            return when {
                above >= 0 -> above                                   // над полем
                below + panelH <= screenH -> below                     // под полем
                else -> (f.top * 0.18f).toInt().coerceIn(0, above.coerceAtLeast(0)) // тесно: прижаться вверх, поле не трогать
            }
        }

        // --------------------------------------------------------- вставка

        private fun onCellTap(cell: CellWithMessages) {
            val texts = cell.orderedMessages.map { it.text.trim() }.filter { it.isNotEmpty() }
            if (texts.isEmpty()) {
                feedback("Пустая ячейка", ACCENT)
                return
            }
            paste(texts.joinToString("\n\n"), "ячейка целиком")
        }

        private fun onMessageTap(cell: CellWithMessages, position: Int) {
            val messages = cell.orderedMessages
            if (position !in messages.indices) return
            paste(messages[position].text, "сообщение ${position + 1}")
        }

        private fun paste(text: String, what: String) {
            if (pasting) return
            if (text.length > CLIP_SAFE_CHARS) {
                feedback("Текст ${text.length} симв. — поле держит ~1МБ, вставь по одному", ACCENT)
                return
            }
            pasting = true
            root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            Thread {
                val result = PasteAccessibilityService.pasteAtCursor(app.packageName, text)
                Handler(Looper.getMainLooper()).post {
                    pasting = false
                    android.util.Log.i(TAG, "paste [$what] -> $result")
                    when (result) {
                        PasteResult.Pasted -> {
                            feedback("Вставлено: $what", OK_GREEN)
                            // Автозакрытие: миссия панели выполнена одним тапом.
                            root.postDelayed({ hide(animate = true) }, 90)
                        }
                        PasteResult.NoService ->
                            feedback("Сервис «Прямая вставка» не включён", ACCENT)
                        PasteResult.NoField ->
                            feedback("Нет поля ввода под панелью", ACCENT)
                        is PasteResult.Rejected ->
                            feedback("Поле не приняло текст (${result.nodeClass})", ACCENT)
                    }
                }
            }.start()
        }

        private fun feedback(text: String, color: String) {
            feedbackView.removeCallbacks(revertFeedback)
            feedbackView.text = text
            feedbackView.setTextColor(Color.parseColor(color))
            feedbackView.postDelayed(revertFeedback, 2_500L)
        }

        private fun showHint() {
            feedbackView.text = "Тап — вставить в поле · удержание — одно сообщение"
            feedbackView.setTextColor(Color.parseColor(TEXT_FAINT))
        }

        // --------------------------------------------------------- детально

        private fun openDetail(cell: CellWithMessages) {
            detailCell = cell
            backBtn?.visibility = View.VISIBLE
            grid.visibility = View.GONE
            emptyView.visibility = View.GONE
            if (detailList == null) {
                detailList = ListView(app).apply {
                    divider = null
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                contentFrame.addView(detailList)
            }
            val adapter = messagesAdapter ?: MessagesAdapter().also { messagesAdapter = it }
            adapter.update(cell)
            detailList?.adapter = adapter
            detailList?.visibility = View.VISIBLE
            root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        private fun closeDetail() {
            if (detailCell == null) return
            detailCell = null
            backBtn?.visibility = View.GONE
            detailList?.visibility = View.GONE
            grid.visibility = if (cells.isEmpty()) View.GONE else View.VISIBLE
            emptyView.visibility = if (cells.isEmpty()) View.VISIBLE else View.GONE
            showHint()
        }

        private var backBtn: TextView? = null

        // ---------------------------------------------------------- вьюхи

        /** Карточка ячейки: имя + счётчик (тёмно-серый стиль). */
        private inner class CellCardView(context: Context) : FrameLayout(context) {
            private val nameView: TextView
            private val countView: TextView

            init {
                isClickable = true
                background = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor(RIPPLE)),
                    GradientDrawable().apply {
                        setColor(Color.parseColor(CELL_BG))
                        cornerRadius = 14 * dp.toFloat()
                        setStroke(dp, Color.parseColor(CELL_STROKE))
                    },
                    null,
                )
                nameView = TextView(context).apply {
                    textSize = 11f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(Color.parseColor(TEXT))
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(1 * dp.toFloat(), 1f)
                }
                addView(nameView, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                    setMargins(6 * dp, 4 * dp, 6 * dp, 4 * dp)
                })
                countView = TextView(context).apply {
                    textSize = 9f
                    setTextColor(Color.parseColor(TEXT_DIM))
                    gravity = Gravity.CENTER
                }
                addView(countView, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = 3 * dp
                })
            }

            fun bind(cell: CellWithMessages) {
                nameView.text = cell.cell.name
                countView.text = "${cell.messages.size} сообщ."
                contentDescription = "Вставить ${cell.cell.name}, ${cell.messages.size} сообщений"
                setOnClickListener { onCellTap(cell) }
                setOnLongClickListener {
                    if (cell.messages.isEmpty()) return@setOnLongClickListener false
                    openDetail(cell)
                    true
                }
            }
        }

        private inner class CellsAdapter : BaseAdapter() {
            override fun getCount() = cells.size
            override fun getItem(position: Int): Any = cells[position]
            override fun getItemId(position: Int): Long = cells[position].cell.id

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val cell = cells[position]
                val card = (convertView as? CellCardView) ?: CellCardView(parent.context).apply {
                    layoutParams = AbsListView.LayoutParams(
                        AbsListView.LayoutParams.MATCH_PARENT, 56 * dp
                    )
                }
                card.bind(cell)
                return card
            }
        }

        private inner class MessageRow(context: Context) : LinearLayout(context) {
            val textView: TextView

            init {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10 * dp, 7 * dp, 10 * dp, 7 * dp)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(CELL_BG))
                    cornerRadius = 10 * dp.toFloat()
                }
                textView = TextView(context).apply {
                    textSize = 11f
                    setTextColor(Color.parseColor(TEXT_SOFT))
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                addView(textView, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
        }

        private inner class MessagesAdapter : BaseAdapter() {
            private var cell: CellWithMessages? = null

            fun update(newCell: CellWithMessages) {
                cell = newCell
                notifyDataSetChanged()
            }

            override fun getCount() = cell?.orderedMessages?.size ?: 0
            override fun getItem(position: Int): Any =
                cell?.orderedMessages?.getOrNull(position) ?: Unit

            override fun getItemId(position: Int): Long =
                cell?.orderedMessages?.getOrNull(position)?.id ?: 0L

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val current = cell ?: return View(parent.context)
                val messages = current.orderedMessages
                if (position !in messages.indices) return View(parent.context)
                val row = (convertView as? MessageRow) ?: MessageRow(parent.context)
                row.textView.text = messages[position].text
                row.setOnClickListener { onMessageTap(current, position) }
                row.contentDescription = "Вставить сообщение ${position + 1}"
                return row
            }
        }

        // ---------------------------------------------------------- утил

        private fun iconButton(glyph: String, description: String, onClick: () -> Unit): TextView =
            TextView(app).apply {
                text = glyph
                textSize = 15f
                gravity = Gravity.CENTER
                contentDescription = description
                setTextColor(Color.parseColor(TEXT_DIM))
                setOnClickListener { onClick() }
                background = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor(RIPPLE)),
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#00FFFFFF"))
                        cornerRadius = 16 * dp.toFloat()
                    },
                    null,
                )
                layoutParams = LinearLayout.LayoutParams(32 * dp, 32 * dp)
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
            private const val PANEL_BG = "#F0101012"
            private const val CELL_BG = "#FF1C1C1E"
            private const val CELL_STROKE = "#FF2A2A2C"
            private const val STROKE = "#26FFFFFF"
            private const val RIPPLE = "#22FFFFFF"
            private const val TEXT = "#FFF2F2F2"
            private const val TEXT_SOFT = "#FFE8EAED"
            private const val TEXT_DIM = "#FF9AA0A6"
            private const val TEXT_FAINT = "#FF80868B"
            private const val ACCENT = "#FF8AB4F8"
            private const val OK_GREEN = "#FF7BD88F"
        }
    }

    /**
     * Корень панели: гасит BACK (закрытие — только ✕ или успешная вставка),
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
