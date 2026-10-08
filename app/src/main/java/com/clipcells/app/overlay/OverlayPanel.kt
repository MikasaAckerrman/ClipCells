package com.clipcells.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Point
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.clipcells.app.data.CellWithMessages
import com.clipcells.app.data.ClipCellsDatabase
import kotlin.math.abs

/**
 * The floating ClipCells window — the Copy-as-File overlay architecture:
 *
 *  - ZERO services. The visible overlay window itself keeps the process
 *    alive and exempt from the freezer (proven on this device by the
 *    Copy-as-File v2.5 process checks: window open = process alive with no
 *    service, window closed = do_freezer_trap, 0 CPU). No notification,
 *    no foreground service, nothing to start or stop — the ✕ button simply
 *    removes the window and the OS parks the process.
 *  - ZERO idle drain. No flow subscriptions, no observers, no timers: the
 *    cells snapshot is read once per show on a worker thread; the window
 *    content is static, so there is nothing to invalidate, redraw or
 *    compose while the user is not touching it. No background work at all.
 *  - Dragging moves the WINDOW (WindowManager.updateViewLayout: an OS-level
 *    surface transaction, no measure/layout pass, no re-draw) — the same
 *    mechanism every floating-window app uses; the card translation variant
 *    needs a fullscreen modal window, which would block the app beneath.
 *  - Closes ONLY via its ✕ button (BACK is consumed by the root on
 *    purpose); taps outside the panel pass through to the app underneath
 *    (FLAG_NOT_TOUCH_MODAL).
 *  - Tap a cell copies ALL its messages as ONE clipboard piece (v0.13:
 *    instant whole-cell copy — no queues, no counters). Long-press opens
 *    the cell's message list; tapping a message copies just that one.
 *    Every write goes through [OverlayCopier] — clipboard writes only while
 *    the panel window holds focus (the Android 10+ rule), with a
 *    pending-flush retry for the focus-transfer instant.
 */
object OverlayPanel {

    @Volatile private var liveRoot: OverlayPanelRoot? = null
    @Volatile private var liveWm: WindowManager? = null
    @Volatile private var liveUi: PanelUi? = null
    @Volatile private var dismissing = false

    fun isShowing(): Boolean = liveRoot != null

    /**
     * One-shot load + show. Safe from any context (trampoline, main app
     * toggle): the DB query runs on a worker, the window is added on main.
     * Double-taps coalesce into a single load.
     */
    fun launch(context: Context, testCopy: Boolean = false) {
        if (isShowing()) return
        val app = context.applicationContext
        Thread {
            val cells = try {
                ClipCellsDatabase.get(app).cellDao().getAllSync()
            } catch (_: Exception) {
                emptyList()
            }
            Handler(Looper.getMainLooper()).post {
                if (!isShowing()) {
                    show(app, cells)
                    if (testCopy) testCopy()
                }
            }
        }.start()
    }

    fun hide(animate: Boolean = true) {
        val root = liveRoot ?: return
        if (dismissing) return // one dismissal per window — no animator fights
        dismissing = true
        val wm = liveWm ?: return
        if (animate) {
            exit(root) {
                removeWindow(wm, root)
            }
            // Safety net: removal happens even if the animator stalls.
            root.postDelayed({
                if (liveRoot === root) removeWindow(wm, root)
            }, 230)
        } else {
            removeWindow(wm, root)
        }
    }

    /** Dev/CI hook: end-to-end clipboard proof without touching the screen. */
    fun testCopy() {
        liveUi?.copier?.copy("ClipCells", "CLIPCELLS_OVERLAY_FOCUS_TEST", "тест")
    }

    private fun show(app: Context, cells: List<CellWithMessages>) {
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        hide(animate = false) // replace a stale window if one survived
        val ui = PanelUi(app, wm, cells) { hide(animate = true) }
        ui.build()
        try {
            wm.addView(ui.root, ui.windowParams())
            liveRoot = ui.root
            liveWm = wm
            liveUi = ui
            dismissing = false
            enter(ui.root)
        } catch (e: Exception) {
            android.util.Log.w("ClipCellsOverlay", "panel addView failed", e)
            ui.copier.dispose()
        }
    }

    private fun removeWindow(wm: WindowManager, root: View) {
        liveUi?.copier?.dispose()
        liveRoot = null
        liveWm = null
        liveUi = null
        dismissing = false
        try {
            wm.removeView(root)
        } catch (_: Exception) {
        }
        android.util.Log.i("ClipCellsOverlay", "panel removed")
    }

    // ------------------------------------------------------------- FX

    /** Pop-in: alpha+scale, one shot, then the window is fully static. */
    private fun enter(root: View) {
        root.alpha = 0f
        root.scaleX = 0.96f
        root.scaleY = 0.96f
        android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(root, View.ALPHA, 0f, 1f),
                android.animation.ObjectAnimator.ofFloat(root, View.SCALE_X, 0.96f, 1f),
                android.animation.ObjectAnimator.ofFloat(root, View.SCALE_Y, 0.96f, 1f),
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
                android.animation.ObjectAnimator.ofFloat(root, View.SCALE_X, root.scaleX, 0.96f),
                android.animation.ObjectAnimator.ofFloat(root, View.SCALE_Y, root.scaleY, 0.96f),
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

    // ================================================================ UI

    /**
     * One panel instance per show. Plain Views only (no Compose, no
     * AndroidX): instant creation, static content, nothing to keep warm.
     */
    private class PanelUi(
        val app: Context,
        private val wm: WindowManager,
        private val cells: List<CellWithMessages>,
        private val onClose: () -> Unit,
    ) {
        private val dp = dpFactor()
        private val screen = screenPx()

        lateinit var root: OverlayPanelRoot
        lateinit var copier: OverlayCopier
        private lateinit var feedbackView: TextView
        private lateinit var contentFrame: FrameLayout
        private lateinit var grid: GridView
        private lateinit var emptyView: TextView
        private var detailList: ListView? = null
        private var detailCell: CellWithMessages? = null
        private val cellsAdapter = CellsAdapter()
        private var messagesAdapter: MessagesAdapter? = null

        private val revertFeedback = Runnable {
            if (feedbackView.isAttachedToWindow) showHint()
        }

        private var dragOffsetX = 0
        private var dragOffsetY = 0

        fun build() {
            root = OverlayPanelRoot(app).apply {
                setPadding(12 * dp, 10 * dp, 12 * dp, 12 * dp)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(PANEL_BG))
                    cornerRadius = 22 * dp.toFloat()
                    setStroke(dp, Color.parseColor(STROKE))
                }
            }
            val column = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
            root.addView(column, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            column.addView(buildHeader())
            column.addView(buildFeedback().apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 * dp }
            })
            column.addView(buildContent().apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 * dp }
            })

            copier = OverlayCopier(app, root, ::onCopyResult)
        }

        fun windowParams(): WindowManager.LayoutParams {
            val width = minOf(240 * dp, (screen.x * 0.60f).toInt())
            return WindowManager.LayoutParams(
                width,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }

        // --------------------------------------------------------- header

        private fun buildHeader(): View {
            val header = HeaderRow(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                onDragStart = { dragOffsetX = windowOffsetX(); dragOffsetY = windowOffsetY() }
                onDragMove = { dx, dy -> moveWindowBy(dx, dy) }
            }
            header.addView(iconButton("‹", "Назад к ячейкам") { closeDetail() }.apply {
                visibility = View.GONE
                backBtn = this
            })
            header.addView(TextView(app).apply {
                text = "Ячейки"
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.parseColor(TEXT))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = 4 * dp }
                titleView = this
            })
            header.addView(TextView(app).apply {
                textSize = 11f
                setTextColor(Color.parseColor(TEXT_DIM))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 4 * dp }
                counterView = this
            })
            header.addView(iconButton("✕", "Закрыть панель") { onClose() })
            return header
        }

        private var backBtn: TextView? = null
        private var titleView: TextView? = null
        private var counterView: TextView? = null

        // -------------------------------------------------------- feedback

        private fun buildFeedback(): View {
            feedbackView = TextView(app).apply {
                textSize = 10f
                setTextColor(Color.parseColor(TEXT_FAINT))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            showHint()
            return feedbackView
        }

        // --------------------------------------------------------- content

        private fun buildContent(): View {
            contentFrame = FrameLayout(app)
            grid = GridView(app).apply {
                numColumns = 3
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                verticalSpacing = 8 * dp
                horizontalSpacing = 8 * dp
                adapter = cellsAdapter
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 186 * dp
                )
            }
            contentFrame.addView(grid)
            emptyView = TextView(app).apply {
                text = "Нет ячеек — создайте их в приложении ClipCells"
                textSize = 11f
                setTextColor(Color.parseColor(TEXT_DIM))
                gravity = Gravity.CENTER
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 186 * dp
                )
            }
            contentFrame.addView(emptyView)
            if (cells.isEmpty()) {
                grid.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
            }
            return contentFrame
        }

        // ----------------------------------------------------------- copy

        private fun onCellTap(cell: CellWithMessages) {
            val texts = cell.orderedMessages.map { it.text.trim() }.filter { it.isNotEmpty() }
            if (texts.isEmpty()) {
                feedback("Пустая ячейка", ACCENT)
                return
            }
            root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            copier.copy(cell.cell.name, texts.joinToString("\n"), "сообщений: ${texts.size}")
        }

        private fun onMessageTap(cell: CellWithMessages, position: Int) {
            val messages = cell.orderedMessages
            if (position !in messages.indices) return
            root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            copier.copy(cell.cell.name, messages[position].text, "сообщение ${position + 1}")
        }

        private fun onCopyResult(result: OverlayCopier.Result) {
            when (result) {
                is OverlayCopier.Result.Copied ->
                    feedback("Скопировано: ${result.label} — ${result.order}", OK_GREEN)
                is OverlayCopier.Result.WaitingForFocus ->
                    feedback("Коснитесь окна — текст скопируется", ACCENT)
            }
        }

        private fun feedback(text: String, color: String) {
            feedbackView.removeCallbacks(revertFeedback)
            feedbackView.text = text
            feedbackView.setTextColor(Color.parseColor(color))
            feedbackView.postDelayed(revertFeedback, FEEDBACK_MILLIS)
        }

        private fun showHint() {
            feedbackView.text = "Тап — вся ячейка · удержание — выбор сообщения"
            feedbackView.setTextColor(Color.parseColor(TEXT_FAINT))
        }

        // ---------------------------------------------------------- detail

        private fun openDetail(cell: CellWithMessages) {
            detailCell = cell
            backBtn?.visibility = View.VISIBLE
            titleView?.text = cell.cell.name
            counterView?.text = ""
            grid.visibility = View.GONE
            emptyView.visibility = View.GONE
            feedbackView.visibility = View.GONE

            if (detailList == null) {
                detailList = ListView(app).apply {
                    divider = null
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 186 * dp
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
            feedbackView.visibility = View.VISIBLE
            detailList?.visibility = View.GONE
            titleView?.text = "Ячейки"
            counterView?.text = if (cells.isEmpty()) "" else "${cells.size}"
            grid.visibility = if (cells.isEmpty()) View.GONE else View.VISIBLE
            emptyView.visibility = if (cells.isEmpty()) View.VISIBLE else View.GONE
            showHint()
        }

        // ---------------------------------------------------------- window

        private fun windowOffsetX(): Int =
            (root.layoutParams as? WindowManager.LayoutParams)?.x ?: 0

        private fun windowOffsetY(): Int =
            (root.layoutParams as? WindowManager.LayoutParams)?.y ?: 0

        private fun moveWindowBy(dx: Float, dy: Float) {
            val lp = (root.layoutParams as? WindowManager.LayoutParams) ?: return
            val halfW = ((screen.x - root.width) / 2).coerceAtLeast(0)
            val halfH = ((screen.y - root.height) / 2).coerceAtLeast(0)
            lp.x = (dragOffsetX + dx).toInt().coerceIn(-halfW, halfW)
            lp.y = (dragOffsetY + dy).toInt().coerceIn(-halfH, halfH)
            try {
                wm.updateViewLayout(root, lp)
            } catch (_: Exception) {
            }
        }

        // ----------------------------------------------------------- views

        /** A cell card: name, message count, next-message badge. */
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
                contentDescription = "${cell.cell.name}, ${cell.messages.size} сообщений"
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
                        AbsListView.LayoutParams.MATCH_PARENT, 52 * dp
                    )
                }
                card.bind(cell)
                return card
            }
        }

        /** Message row of the detail list. */
        private inner class MessageRow(context: Context) : LinearLayout(context) {
            val indexView: TextView
            val textView: TextView

            init {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10 * dp, 7 * dp, 10 * dp, 7 * dp)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(CELL_BG))
                    cornerRadius = 10 * dp.toFloat()
                }
                indexView = TextView(context).apply {
                    textSize = 10f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(Color.parseColor(ACCENT))
                }
                addView(indexView, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8 * dp })
                textView = TextView(context).apply {
                    textSize = 11f
                    setTextColor(Color.parseColor(TEXT_SOFT))
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                addView(textView, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
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
            override fun getItem(position: Int): Any = cell?.orderedMessages?.getOrNull(position) ?: Unit
            override fun getItemId(position: Int): Long =
                cell?.orderedMessages?.getOrNull(position)?.id ?: 0L

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val current = cell ?: return View(parent.context)
                val messages = current.orderedMessages
                if (position !in messages.indices) return View(parent.context)
                val row = (convertView as? MessageRow) ?: MessageRow(parent.context)
                row.indexView.text = "${position + 1}."
                row.textView.text = messages[position].text
                row.setOnClickListener { onMessageTap(current, position) }
                row.contentDescription = "Сообщение ${position + 1}"
                return row
            }
        }

        // ---------------------------------------------------------- header

        /**
         * Header row with built-in window dragging. Children (buttons) keep
         * their clicks; movement beyond the slop cancels the pending child
         * click and drags the window instead (surface transaction — smooth).
         */
        private inner class HeaderRow(context: Context) : LinearLayout(context) {
            var onDragStart: (() -> Unit)? = null
            var onDragMove: ((dx: Float, dy: Float) -> Unit)? = null

            private var downX = 0f
            private var downY = 0f
            private var dragging = false
            private val slop = ViewConfiguration.get(context).scaledTouchSlop * 2

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                track(ev)
                return dragging
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                track(ev)
                return dragging || super.onTouchEvent(ev)
            }

            private fun track(ev: MotionEvent) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!dragging &&
                            (abs(ev.rawX - downX) > slop || abs(ev.rawY - downY) > slop)
                        ) {
                            dragging = true
                            onDragStart?.invoke()
                        }
                        if (dragging) onDragMove?.invoke(ev.rawX - downX, ev.rawY - downY)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
                }
            }
        }

        // ---------------------------------------------------------- utils

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
            private const val PANEL_BG = "#F0101010"
            private const val CELL_BG = "#FF1C1C1C"
            private const val CELL_STROKE = "#FF2A2A2A"
            private const val STROKE = "#26FFFFFF"
            private const val RIPPLE = "#22FFFFFF"
            private const val TEXT = "#FFF2F2F2"
            private const val TEXT_SOFT = "#FFE8EAED"
            private const val TEXT_DIM = "#FF9E9E9E"
            private const val TEXT_FAINT = "#FF80868B"
            private const val ACCENT = "#FF8AB4F8"
            private const val OK_GREEN = "#FF7BD88F"
            private const val FEEDBACK_MILLIS = 2_500L
        }
    }

    /**
     * Panel root: eats the BACK key — the panel closes ONLY through its ✕
     * button by explicit product rule.
     */
    private class OverlayPanelRoot(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
            if (ev.action == KeyEvent.ACTION_DOWN && ev.keyCode == KeyEvent.KEYCODE_BACK) {
                return true
            }
            return super.dispatchKeyEvent(ev)
        }
    }
}
