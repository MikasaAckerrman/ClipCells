package com.clipcells.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
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
import kotlin.math.abs

/**
 * The floating cells panel: a rounded dark card centered over the current
 * app. Header + feedback strip + a 3-column grid of cells (or the message
 * list of one cell after a long-press).
 *
 * Window contract:
 *  - FOCUSABLE (no FLAG_NOT_FOCUSABLE) but NOT_TOUCH_MODAL: it takes window
 *    focus — the legal precondition for clipboard writes on Android 10+ —
 *    while touches OUTSIDE the panel still reach the app underneath.
 *  - Closes ONLY via its ✕ button ([onClose]); taps outside never close it,
 *    and BACK is consumed by [OverlayPanelRoot] on purpose.
 *  - Dragging the header moves the whole window (gravity CENTER + x/y
 *    offsets), clamped to the screen.
 *
 * Plain Views by design: proven on this device by the Copy-as-File overlays,
 * ~zero warm-up cost, and the code transfers to the future donut launcher
 * app almost verbatim.
 */
internal class OverlayPanelController(
    private val context: Context,
    private val wm: WindowManager,
    private val cursor: CellCursor,
    private val onClose: () -> Unit,
    private val cancelCopyQueue: () -> Unit,
) {

    private val dp = dpFactor()
    private val screen = screenPx()

    private var cells: List<CellWithMessages> = emptyList()
    private var detailCellId: Long? = null

    private lateinit var root: OverlayPanelRoot
    private lateinit var header: HeaderRow
    private lateinit var backBtn: TextView
    private lateinit var titleView: TextView
    private lateinit var counterView: TextView
    private lateinit var feedbackView: TextView
    private lateinit var contentFrame: FrameLayout
    private lateinit var grid: GridView
    private lateinit var emptyView: TextView
    private var detailList: ListView? = null

    private val cellsAdapter = CellsAdapter()
    private var messagesAdapter: MessagesAdapter? = null

    /** Created in [create] once the root view exists (it needs the view). */
    lateinit var copier: OverlayCopier

    private val revertFeedback = Runnable {
        if (feedbackView.isAttachedToWindow) showHint()
    }

    private var dragOffsetX = 0
    private var dragOffsetY = 0

    // ------------------------------------------------------------- build

    fun create() {
        root = OverlayPanelRoot(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(16 * dp, 14 * dp, 16 * dp, 16 * dp)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(PANEL_BG))
                cornerRadius = 26 * dp.toFloat()
                setStroke(dp, Color.parseColor(STROKE))
            }
        }

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        column.addView(buildHeader())
        column.addView(buildFeedback().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6 * dp }
        })
        column.addView(buildContent().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10 * dp }
        })

        copier = OverlayCopier(context, root, ::onCopyResult, cancelCopyQueue)
        refreshHeader()
    }

    private fun buildHeader(): View {
        header = HeaderRow(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            onDragStart = { dragOffsetX = windowOffsetX(); dragOffsetY = windowOffsetY() }
            onDragMove = { dx, dy -> moveWindowBy(dx, dy) }
        }
        backBtn = iconButton("‹", "Назад к ячейкам") { closeDetail() }
        backBtn.visibility = View.GONE
        header.addView(backBtn)

        titleView = TextView(context).apply {
            text = "Ячейки"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor(TEXT))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = 4 * dp }
        }
        header.addView(titleView)

        counterView = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor(TEXT_DIM))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 4 * dp }
        }
        header.addView(counterView)

        header.addView(iconButton("✕", "Закрыть панель") { onClose() })
        return header
    }

    private fun buildFeedback(): View {
        feedbackView = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.parseColor(TEXT_FAINT))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        showHint()
        return feedbackView
    }

    private fun buildContent(): View {
        contentFrame = FrameLayout(context)

        grid = GridView(context).apply {
            numColumns = 3
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = 10 * dp
            horizontalSpacing = 10 * dp
            adapter = cellsAdapter
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, gridHeight()
            )
        }
        contentFrame.addView(grid)

        emptyView = TextView(context).apply {
            text = "Нет ячеек — создайте их в приложении ClipCells"
            textSize = 13f
            setTextColor(Color.parseColor(TEXT_DIM))
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, gridHeight()
            )
        }
        contentFrame.addView(emptyView)
        return contentFrame
    }

    // ------------------------------------------------------------- window

    fun layoutParams(): WindowManager.LayoutParams {
        val width = minOf((screen.x * 0.88f).toInt(), 420 * dp)
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

    fun show() {
        if (root.isAttachedToWindow) return
        try {
            wm.addView(root, layoutParams())
        } catch (_: Exception) {
        }
    }

    fun hide() {
        if (!root.isAttachedToWindow) return
        try {
            wm.removeView(root)
        } catch (_: Exception) {
        }
        closeDetail()
        showHint()
    }

    fun isShowing(): Boolean = root.isAttachedToWindow

    private fun windowOffsetX(): Int = (root.layoutParams as? WindowManager.LayoutParams)?.x ?: 0
    private fun windowOffsetY(): Int = (root.layoutParams as? WindowManager.LayoutParams)?.y ?: 0

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

    // ------------------------------------------------------------- data

    fun updateCells(newCells: List<CellWithMessages>) {
        cells = newCells
        val detail = detailCellId
        if (detail != null) {
            val cell = newCells.firstOrNull { it.cell.id == detail }
            if (cell == null) {
                closeDetail()
            } else {
                (detailList?.adapter as? MessagesAdapter)?.update(cell)
            }
        }
        refreshHeader()
        cellsAdapter.notifyDataSetChanged()
        grid.visibility = if (newCells.isEmpty()) View.GONE else View.VISIBLE
        emptyView.visibility = if (newCells.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshHeader() {
        if (detailCellId != null) return
        titleView.text = "Ячейки"
        counterView.text = if (cells.isEmpty()) "" else "${cells.size}"
    }

    // ------------------------------------------------------------- copy

    private fun onCellTap(cell: CellWithMessages) {
        val messages = cell.orderedMessages
        if (messages.isEmpty()) {
            feedback("Пустая ячейка", ACCENT)
            return
        }
        val index = cursor.advance(cell.cell.id, messages.size)
        val message = messages[index]
        root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        copier.copy(cell.cell.name, message.text, "${index + 1}/${messages.size}")
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
        feedbackView.text = "Тап — следующее сообщение · удержание — выбор"
        feedbackView.setTextColor(Color.parseColor(TEXT_FAINT))
    }

    // ------------------------------------------------------------- detail

    private fun openDetail(cell: CellWithMessages) {
        detailCellId = cell.cell.id
        backBtn.visibility = View.VISIBLE
        titleView.text = cell.cell.name
        counterView.text = ""
        grid.visibility = View.GONE
        emptyView.visibility = View.GONE
        feedbackView.visibility = View.GONE

        if (detailList == null) {
            detailList = ListView(context).apply {
                divider = null
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, gridHeight()
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
        if (detailCellId == null) return
        detailCellId = null
        backBtn.visibility = View.GONE
        feedbackView.visibility = View.VISIBLE
        detailList?.visibility = View.GONE
        refreshHeader()
        grid.visibility = if (cells.isEmpty()) View.GONE else View.VISIBLE
        emptyView.visibility = if (cells.isEmpty()) View.VISIBLE else View.GONE
        showHint()
    }

    // ------------------------------------------------------------- views

    /** A cell card: name, message count, next-message badge. */
    private inner class CellCardView(context: Context) : FrameLayout(context) {
        private val nameView: TextView
        private val countView: TextView
        private val badgeView: TextView

        init {
            isClickable = true
            background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.parseColor(RIPPLE)),
                GradientDrawable().apply {
                    setColor(Color.parseColor(CELL_BG))
                    cornerRadius = 18 * dp.toFloat()
                    setStroke(dp, Color.parseColor(CELL_STROKE))
                },
                null,
            )

            nameView = TextView(context).apply {
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.parseColor(TEXT))
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(2 * dp.toFloat(), 1f)
            }
            addView(nameView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                setMargins(10 * dp, 8 * dp, 10 * dp, 8 * dp)
            })

            countView = TextView(context).apply {
                textSize = 10f
                setTextColor(Color.parseColor(TEXT_DIM))
                gravity = Gravity.CENTER
            }
            addView(countView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = 6 * dp })

            badgeView = TextView(context).apply {
                textSize = 10f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.parseColor(ACCENT))
            }
            addView(badgeView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.END; topMargin = 6 * dp; marginEnd = 8 * dp })
        }

        fun bind(cell: CellWithMessages, peekIndex: Int) {
            nameView.text = cell.cell.name
            countView.text = "${cell.messages.size} сообщ."
            if (cell.messages.size > 1) {
                badgeView.text = "→${peekIndex + 1}/${cell.messages.size}"
                badgeView.visibility = View.VISIBLE
            } else {
                badgeView.visibility = View.INVISIBLE
            }
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
                    AbsListView.LayoutParams.MATCH_PARENT, cellHeight()
                )
            }
            card.bind(cell, cursor.peek(cell.cell.id, cell.messages.size))
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
            setPadding(12 * dp, 10 * dp, 12 * dp, 10 * dp)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(CELL_BG))
                cornerRadius = 12 * dp.toFloat()
            }
            indexView = TextView(context).apply {
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.parseColor(ACCENT))
            }
            addView(indexView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8 * dp })

            textView = TextView(context).apply {
                textSize = 12f
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
        override fun getItem(position: Int): Any = cell!!.orderedMessages[position]
        override fun getItemId(position: Int): Long = cell!!.orderedMessages[position].id

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

    // ------------------------------------------------------------- header

    /**
     * Header row with built-in window dragging. The same unified tracking
     * that Copy-as-File's card uses: children (buttons) still get their
     * clicks, but any movement beyond the slop cancels the pending child
     * click and drags the whole panel instead.
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

    /**
     * Panel root: eats the BACK key — the panel closes ONLY through its ✕
     * button by explicit product rule, and an overlay window receiving BACK
     * must not let anything else react to it either.
     */
    private class OverlayPanelRoot(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
            if (ev.action == KeyEvent.ACTION_DOWN && ev.keyCode == KeyEvent.KEYCODE_BACK) {
                return true
            }
            return super.dispatchKeyEvent(ev)
        }
    }

    // ------------------------------------------------------------- utils

    private fun iconButton(glyph: String, description: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = glyph
            textSize = 18f
            gravity = Gravity.CENTER
            contentDescription = description
            setTextColor(Color.parseColor(TEXT_DIM))
            setOnClickListener { onClick() }
            background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.parseColor(RIPPLE)),
                GradientDrawable().apply {
                    setColor(Color.parseColor("#00FFFFFF"))
                    cornerRadius = 20 * dp.toFloat()
                },
                null,
            )
            layoutParams = LinearLayout.LayoutParams(40 * dp, 40 * dp)
        }

    private fun gridHeight(): Int = (screen.y * 0.50f).toInt()
    private fun cellHeight(): Int = (screen.y * 0.105f).toInt().coerceAtLeast(64 * dp)

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
        TypedValue.COMPLEX_UNIT_DIP, 1f, context.resources.displayMetrics
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
