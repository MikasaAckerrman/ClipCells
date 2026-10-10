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
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.clipcells.app.R
import com.clipcells.app.CLIP_SAFE_CHARS
import com.clipcells.app.data.CellWithMessages
import com.clipcells.app.data.ClipCellsDatabase
import com.clipcells.app.data.MessageEntity
import kotlin.math.abs

/**
 * Стеклянная панель быстрой вставки v0.20 (ТЗ пользователя):
 *
 *  - ВСТАВКА, не копирование: буфер обмена не трогаем вообще.
 *  - Экран 1 — ячейки («Minis ×5»); тап → Экран 2 — сообщения плитками 4 в ряд.
 *  - Тап по плитке → мгновенная вставка текста (a11y, позиция курсора) →
 *    панель закрывается.
 *  - Долгое нажатие на плитку → ПРОСМОТР ПОЛНОГО ТЕКСТА: отдельная
 *    «карточка-вьюер» с анимацией, скроллом текста и кнопкой-стрелкой
 *    выхода; остальные плитки в это время скрыты. Внутри вьюера —
 *    «Выбрать» для мультивыбора (порядок = порядок тапов, бейдж 1..N),
 *    «Вставить N» в правом нижнем углу.
 *  - АНТИ-МЕРЦАНИЕ (окончательное): окно WindowManager НЕ трогается вовсе —
 *    ни layerType, ни alpha корня, ни масштаб окна (любая манипуляция
 *    поверхностью overlay-окна на этом ROM даёт вспышку кадра). Всё, что
 *    видно, лежит на ОДНОЙ карточке-контенте; закрытие = фейд карточки
 *    (view-проперти, композитор), removeView уже на полностью прозрачном
 *    окне. Один аниматор на окно, страховка 600мс, задержка 120мс после
 *    binder-нагрузки вставки.
 *  - Кнопки ✕ и стрелка — один размер (34dp) и один вес штриха; клик
 *    подсвечивается белой волной (ripple 40% белого, круг в границах кнопки).
 */
object OverlayPastePanel {

    @Volatile private var liveRoot: PastePanelRoot? = null
    @Volatile private var liveWm: WindowManager? = null
    @Volatile private var liveUi: PanelUi? = null
    @Volatile private var dismissing = false

    /** Единственный активный аниматор окна. */
    @Volatile private var windowAnim: AnimatorSet? = null

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

    fun hide(animate: Boolean = true, delayMs: Long = 0L) {
        val root = liveRoot ?: return
        if (dismissing) return
        dismissing = true
        val wm = liveWm ?: return
        val card = root.getChildAt(0) ?: return removeWindow(wm, root)
        if (animate) {
            exit(card, delayMs) { removeWindow(wm, root) }
            root.postDelayed(
                { if (liveRoot === root) removeWindow(wm, root) },
                delayMs + 600,
            )
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
            enter(ui.card)
            android.util.Log.i(TAG, "paste panel shown cells=${cells.size}")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "paste panel addView failed", e)
        }
    }

    private fun removeWindow(wm: WindowManager, root: View) {
        windowAnim?.cancel()
        windowAnim = null
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
    // ФЕЙДИТСЯ ТОЛЬКО КАРТОЧКА (view-проперти): окно-поверхность не
    // перестраивается — вспышкам взяться неоткуда.

    private fun enter(card: View) {
        windowAnim?.cancel()
        card.alpha = 0f
        card.scaleX = 0.94f
        card.scaleY = 0.94f
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(card, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(card, View.SCALE_X, 0.94f, 1f),
                ObjectAnimator.ofFloat(card, View.SCALE_Y, 0.94f, 1f),
            )
            duration = 150
            interpolator = DecelerateInterpolator(1.3f)
            windowAnim = this
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(a: android.animation.Animator) {}
                override fun onAnimationCancel(a: android.animation.Animator) { if (windowAnim === this@apply) windowAnim = null }
                override fun onAnimationRepeat(a: android.animation.Animator) {}
                override fun onAnimationEnd(a: android.animation.Animator) { if (windowAnim === this@apply) windowAnim = null }
            })
            start()
        }
    }

    private fun exit(card: View, delayMs: Long, end: () -> Unit) {
        windowAnim?.cancel() // играющий вход не спорит с выходом
        AnimatorSet().apply {
            startDelay = delayMs
            playTogether(
                ObjectAnimator.ofFloat(card, View.ALPHA, card.alpha, 0f),
                ObjectAnimator.ofFloat(card, View.SCALE_X, card.scaleX, 0.96f),
                ObjectAnimator.ofFloat(card, View.SCALE_Y, card.scaleY, 0.96f),
            )
            duration = 160
            interpolator = AccelerateInterpolator(1.15f)
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

        /** Landscape: статичная правая колонна (правый край, по центру
            вертикали). Portrait: прежнее верх-центр. */
        private val landscape = screen.x > screen.y
        private val panelW =
            if (landscape) minOf(444 * dp, (screen.x * 0.40f).toInt())
            else minOf(444 * dp, (screen.x * 0.88f).toInt())
        private val panelH =
            // Ландшафт: 84% высоты — нижние/верхние углы НЕ уходят под жестовую
            // полосу и скруглённые углы экрана (метрики окна включают navbar,
            // 92% прижимали низ в зону жестов — «окно за экраном»).
            if (landscape) (screen.y * 0.84f).toInt().coerceAtLeast(340 * dp)
            else 420 * dp

        /** Сторона квадратной плитки сообщения: 4 в ряд. */
        private val tileSide = (panelW - 32 * dp - 3 * (8 * dp)) / 4

        lateinit var root: PastePanelRoot
        lateinit var card: FrameLayout
        private lateinit var titleView: TextView
        private lateinit var cellsList: ListView
        private lateinit var cellsAdapter: CellsAdapter
        private lateinit var grid: GridView
        private lateinit var gridAdapter: MessageTilesAdapter
        private lateinit var sendBtn: TextView
        private var pasting = false

        /** Открытая ячейка (экран сообщений). */
        private var openCell: CellWithMessages? = null

        /** Вьюер полного текста (зажатие плитки). */
        private var viewer: FrameLayout? = null
        private var viewerTitle: TextView? = null
        private var viewerText: TextView? = null
        private var viewerSelectBtn: TextView? = null
        private var viewerMessage: MessageEntity? = null

        /** Последовательность выбранных (порядок тапов) — бейджи 1..N. */
        private val selectionSequence = ArrayList<Long>()
        private val selected = HashMap<Long, MessageEntity>()

        fun build() {
            // Root — прозрачный контейнер: ОКНО не анимируем вообще.
            root = PastePanelRoot(app)
            card = FrameLayout(app).apply {
                setPadding(16 * dp, 14 * dp, 16 * dp, 16 * dp)
                background = glassBackground()
            }
            root.addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))

            val column = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
            card.addView(column, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))

            // --- Шапка: [стрелка] заголовок ✕
            val header = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(iconButton(R.drawable.ic_back, "Назад к ячейкам") { backToCells() }.apply {
                visibility = View.GONE
                backBtn = this
            })
            titleView = TextView(app).apply {
                text = "Вставка"
                textSize = 16f
                letterSpacing = 0.01f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.parseColor(TEXT))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            header.addView(titleView)
            header.addView(iconButton(R.drawable.ic_close, "Закрыть") { onClose() })
            column.addView(header, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 42 * dp
            ))

            // --- Экран 1: ячейки
            cellsAdapter = CellsAdapter()
            cellsList = ListView(app).apply {
                divider = null
                adapter = cellsAdapter
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                cacheColorHint = Color.TRANSPARENT
                setSelector(android.R.color.transparent)
            }
            column.addView(cellsList, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))

            // --- Экран 2: сообщения — квадратные плитки 4 в ряд
            gridAdapter = MessageTilesAdapter()
            grid = GridView(app).apply {
                numColumns = 4
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                verticalSpacing = 8 * dp
                horizontalSpacing = 8 * dp
                adapter = gridAdapter
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                cacheColorHint = Color.TRANSPARENT
                setSelector(android.R.color.transparent)
                visibility = View.GONE
            }
            column.addView(grid, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))

            // --- «Вставить N» — правый нижний угол
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
            val bottomBar = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, 10 * dp, 0, 0)
            }
            bottomBar.addView(sendBtn, LinearLayout.LayoutParams(148 * dp, 44 * dp))
            column.addView(bottomBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        private var backBtn: View? = null

        fun windowParams(): WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                panelW,
                panelH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                // Окно СТРОГО статично: портрет — верх-центр; ландшафт —
                // правая колонна (правый край, вертикально по центру).
                if (landscape) {
                    // Правая колонна с отступом от края: рамка стекла видна,
                    // окно не «уходит за экран» (END-гравитация: x = отступ
                    // от правого края внутрь).
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    x = 18 * dp
                } else {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = (screen.y * 0.055f).toInt()
                }
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
            selectionSequence.clear()
            titleView.text = cell.cell.name
            backBtn?.visibility = View.VISIBLE
            cellsList.visibility = View.GONE
            grid.visibility = View.VISIBLE
            sendBtn.visibility = View.GONE
            gridAdapter.notifyDataSetChanged()
            grid.smoothScrollToPosition(0)
        }

        private fun backToCells() {
            closeViewer()
            if (openCell == null) return
            openCell = null
            selected.clear()
            selectionSequence.clear()
            titleView.text = "Вставка"
            backBtn?.visibility = View.GONE
            grid.visibility = View.GONE
            cellsList.visibility = View.VISIBLE
            sendBtn.visibility = View.GONE
            cellsAdapter.notifyDataSetChanged()
            cellsList.smoothScrollToPosition(0)
        }

        // ---------------------------------------------------- вьюер

        /** Зажатие плитки: полный текст с анимацией, скроллом, «Выбрать». */
        private fun openViewer(message: MessageEntity) {
            if (viewer != null) return
            closeViewer()
            viewerMessage = message

            val v = FrameLayout(app).apply {
                background = glassBackground()
                setPadding(16 * dp, 14 * dp, 16 * dp, 16 * dp)
                alpha = 0f
                scaleX = 0.92f
                scaleY = 0.92f
            }
            val col = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
            v.addView(col, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))

            val header = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(iconButton(R.drawable.ic_back, "Назад к плиткам") { closeViewer() })
            viewerTitle = TextView(app).apply {
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.parseColor(TEXT))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            header.addView(viewerTitle)
            viewerSelectBtn = TextView(app).apply {
                textSize = 13f
                setTextColor(Color.parseColor(ACCENT))
                setOnClickListener {
                    val msg = viewerMessage ?: return@setOnClickListener
                    if (selected.containsKey(msg.id)) deselect(msg) else select(msg)
                    refreshViewerSelectState()
                }
                background = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor(RIPPLE)),
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#00FFFFFF"))
                        cornerRadius = 14 * dp.toFloat()
                        setStroke(dp, Color.parseColor(STROKE_SOFT))
                    },
                    null,
                )
                setPadding(12 * dp, 6 * dp, 12 * dp, 6 * dp)
            }
            header.addView(viewerSelectBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            col.addView(header, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 42 * dp
            ))

            val scroller = ScrollView(app).apply {
                isVerticalScrollBarEnabled = true
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            viewerText = TextView(app).apply {
                textSize = 13f
                setTextColor(Color.parseColor(TEXT_SOFT))
                setLineSpacing(2 * dp.toFloat(), 1.05f)
                setPadding(4 * dp, 6 * dp, 4 * dp, 6 * dp)
            }
            scroller.addView(viewerText, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            col.addView(scroller, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))

            card.addView(v, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            viewer = v

            val idx = openCell?.orderedMessages?.indexOfFirst { it.id == message.id } ?: -1
            viewerTitle?.text = if (idx >= 0) "Сообщение ${idx + 1}" else "Сообщение"
            viewerText?.text = message.text.trim()
            refreshViewerSelectState()

            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170)
                .setInterpolator(DecelerateInterpolator(1.25f)).start()
            root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        private fun closeViewer() {
            val v = viewer ?: return
            viewer = null
            viewerMessage = null
            viewerTitle = null
            viewerText = null
            viewerSelectBtn = null
            v.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(130)
                .setInterpolator(AccelerateInterpolator(1.1f))
                .withEndAction {
                    (v.parent as? ViewGroup)?.removeView(v)
                }.start()
        }

        private fun refreshViewerSelectState() {
            val msg = viewerMessage
            val btn = viewerSelectBtn
            if (msg != null && btn != null) {
                if (selected.containsKey(msg.id)) {
                    btn.text = "Выбрано ${selectionSequence.indexOf(msg.id) + 1}"
                } else {
                    btn.text = "Выбрать"
                }
            }
        }

        // ---------------------------------------------------- выбор

        private fun onTileTap(message: MessageEntity) {
            if (viewer != null) return
            if (selectionSequence.isNotEmpty()) {
                if (selected.containsKey(message.id)) deselect(message) else select(message)
                return
            }
            paste(listOf(message.text), "сообщение")
        }

        private fun onTileLongTap(message: MessageEntity) {
            if (viewer != null) return
            openViewer(message)
        }

        private fun select(message: MessageEntity) {
            selected[message.id] = message
            selectionSequence.add(message.id)
            refreshSendButton()
            gridAdapter.notifyDataSetChanged()
        }

        private fun deselect(message: MessageEntity) {
            selected.remove(message.id)
            selectionSequence.remove(message.id)
            refreshSendButton()
            gridAdapter.notifyDataSetChanged()
        }

        private fun refreshSendButton() {
            val count = selectionSequence.size
            if (count > 0) {
                val wasGone = sendBtn.visibility != View.VISIBLE
                sendBtn.visibility = View.VISIBLE
                sendBtn.text = if (count == 1) "Вставить" else "Вставить $count"
                if (wasGone) {
                    sendBtn.alpha = 0f
                    sendBtn.animate().alpha(1f).setDuration(120)
                        .setInterpolator(OvershootInterpolator(1.1f)).start()
                }
            } else {
                sendBtn.animate().alpha(0f).setDuration(100).withEndAction {
                    sendBtn.visibility = View.GONE
                    sendBtn.alpha = 1f
                }.start()
            }
        }

        private fun sendSelection() {
            if (selectionSequence.isEmpty() || pasting) return
            val ordered = selectionSequence.mapNotNull { selected[it] }
            paste(ordered.map { it.text }, "выбрано ${ordered.size}")
        }

        // ---------------------------------------------------- вставка

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
                    Handler(Looper.getMainLooper()).post {
                        pasting = false
                        if (retried == PasteResult.Pasted) hide(animate = true, delayMs = 120)
                    }
                    return@Thread
                }
                // Не-редактируемое поле (терминал/WebView) или отказ SET_TEXT —
                // УНИВЕРСАЛЬНЫЙ путь: буфер + ACTION_PASTE. Работает в Termux
                // и любых View, поддерживающих paste.
                if (result == PasteResult.Pasted) {
                    Handler(Looper.getMainLooper()).post {
                        pasting = false
                        hide(animate = true, delayMs = 120)
                    }
                } else if (result is PasteResult.NotEditable || result is PasteResult.Rejected) {
                    Handler(Looper.getMainLooper()).post {
                        pasteViaClipboard(text, what)
                    }
                } else {
                    Handler(Looper.getMainLooper()).post { pasting = false }
                }
            }.start()
        }

        /**
         * Буферная вставка для «не полей» (терминалы, WebView): панель на МИГ
         * делается фокусируемой (легальная запись буфера Android 10+), затем
         * a11y шлёт ACTION_PASTE сфокусированному узлу — текст читает само
         * целевое приложение. Окно возвращается в НЕфокусируемое состояние.
         */
        private fun pasteViaClipboard(text: String, what: String) {
            val lp = root.layoutParams as? WindowManager.LayoutParams
            if (lp == null) { pasting = false; return }
            val latch = java.util.concurrent.CountDownLatch(1)
            // Слушатель ставим ДО переключения флагов — иначе событие теряем.
            val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                if (hasFocus) latch.countDown()
            }
            root.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            if (root.hasWindowFocus()) latch.countDown()
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            try {
                wm.updateViewLayout(root, lp)
            } catch (_: Exception) {
                latch.countDown()
            }
            Thread {
                try { latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
                try {
                    (app.getSystemService(android.content.ClipboardManager::class.java))
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("ClipCells", text))
                } catch (_: Exception) {
                }
                val pasted = PasteAccessibilityService.performPasteAction(app.packageName)
                android.util.Log.i(TAG, "paste [$what] clipboard-fallback -> $pasted")
                root.post {
                    try {
                        root.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
                    } catch (_: Exception) {
                    }
                    // Окно снова НЕфокусируемое: фокус — приложению под панелью.
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    try {
                        wm.updateViewLayout(root, lp)
                    } catch (_: Exception) {
                    }
                    pasting = false
                    if (pasted) {
                        hide(animate = true, delayMs = 120)
                    }
                    // Молчаливый отказ: терминал не поддержал ACTION_PASTE —
                    // текст УЖЕ в буфере, приложение вставит своим механизмом.
                }
            }.start()
        }

        // ------------------------------------------------------- стекло

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
            return LayerDrawable(arrayOf(fill, sheen, stroke))
        }

        // ---------------------------------------------------- адаптеры

        private inner class CellsAdapter : BaseAdapter() {
            override fun getCount() = cells.size
            override fun getItem(position: Int): Any = cells[position]
            override fun getItemId(position: Int): Long = cells[position].cell.id

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val cell = cells[position]
                val row = (convertView as? CellRowView) ?: CellRowView(parent.context)
                row.bind(cell)
                return row
            }
        }

        private inner class CellRowView(context: Context) : LinearLayout(context) {
            private val titleView: TextView
            private val counterView: TextView

            init {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16 * dp, 12 * dp, 16 * dp, 12 * dp)
                isClickable = true
                background = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor(RIPPLE)),
                    GradientDrawable().apply {
                        setColor(Color.parseColor(ITEM_BG))
                        cornerRadius = 18 * dp.toFloat()
                    },
                    null,
                )
                titleView = TextView(context).apply {
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(Color.parseColor(TEXT))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                addView(titleView, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ))
                counterView = TextView(context).apply {
                    textSize = 13f
                    setTextColor(Color.parseColor(TEXT_DIM))
                }
                addView(counterView, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = 8 * dp })
            }

            fun bind(cell: CellWithMessages) {
                titleView.text = cell.cell.name
                counterView.text = "×${cell.messages.size}"
                contentDescription = "Ячейка ${cell.cell.name}, ${cell.messages.size} сообщений"
                setOnClickListener {
                    if (cell.messages.isEmpty()) return@setOnClickListener
                    root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    openMessages(cell)
                }
            }
        }

        private inner class MessageTilesAdapter : BaseAdapter() {
            override fun getCount(): Int = openCell?.orderedMessages?.size ?: 0
            override fun getItem(position: Int): Any =
                openCell?.orderedMessages?.getOrNull(position) ?: Unit

            override fun getItemId(position: Int): Long =
                openCell?.orderedMessages?.getOrNull(position)?.id ?: 0L

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val cell = openCell ?: return View(parent.context)
                val messages = cell.orderedMessages
                if (position !in messages.indices) return View(parent.context)
                val message = messages[position]
                val tile = (convertView as? MessageTileView) ?: MessageTileView(parent.context)
                tile.bind(message)
                return tile
            }
        }

        /**
         * Плитка: БЕЗ внутренних ScrollView (они поглощали тачи — плитки
         * «не нажимались»). Тап = вставка, зажатие = вьюер полного текста.
         */
        private inner class MessageTileView(context: Context) : FrameLayout(context) {
            private val textView: TextView
            private val badgeView: TextView
            private var boundId: Long = -1L
            private var wasSelected = false

            init {
                isClickable = true
                isLongClickable = true
                textView = TextView(context).apply {
                    textSize = 11f
                    setTextColor(Color.parseColor(TEXT_SOFT))
                    maxLines = 8
                    ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(1 * dp.toFloat(), 1.02f)
                    setPadding(10 * dp, 10 * dp, 10 * dp, 10 * dp)
                }
                addView(textView, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
                badgeView = TextView(context).apply {
                    textSize = 12f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(Color.parseColor(BTN_TEXT))
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                }
                addView(badgeView, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = 8 * dp
                    rightMargin = 8 * dp
                })
                layoutParams = AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT, tileSide
                )
                setOnClickListener {
                    val msg = messageFromTag() ?: return@setOnClickListener
                    onTileTap(msg)
                }
                setOnLongClickListener {
                    val msg = messageFromTag() ?: return@setOnLongClickListener false
                    onTileLongTap(msg)
                    true
                }
            }

            private fun messageFromTag(): MessageEntity? =
                openCell?.orderedMessages?.firstOrNull { it.id == boundId }

            fun bind(message: MessageEntity) {
                boundId = message.id
                val isSelected = selected.containsKey(message.id)
                textView.text = message.text.trim()
                val bg = GradientDrawable().apply {
                    setColor(
                        if (isSelected) Color.parseColor(ITEM_BG_SELECTED)
                        else Color.parseColor(ITEM_BG)
                    )
                    cornerRadius = 16 * dp.toFloat()
                    if (isSelected) setStroke(2 * dp, Color.parseColor(ACCENT))
                }
                background = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor(RIPPLE)), bg, null
                )
                if (isSelected) {
                    badgeView.visibility = View.VISIBLE
                    badgeView.text = "${(selectionSequence.indexOf(message.id) + 1)}"
                    if (!wasSelected) {
                        badgeView.scaleX = 0.6f
                        badgeView.scaleY = 0.6f
                        badgeView.animate().scaleX(1f).scaleY(1f).setDuration(150)
                            .setInterpolator(OvershootInterpolator(1.3f)).start()
                    }
                } else {
                    badgeView.visibility = View.GONE
                }
                wasSelected = isSelected
                contentDescription = "Сообщение: ${message.text.take(40)}"
            }
        }

        // ------------------------------------------------------- утил

        /** Кнопка-иконка 34dp (✕ и стрелка — одинаковые): белая волна при нажатии. */
        private fun iconButton(iconRes: Int, description: String, onClick: () -> Unit): View =
            android.widget.ImageView(app).apply {
                setImageResource(iconRes)
                contentDescription = description
                setOnClickListener { onClick() }
                background = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor(WAVE)),
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#00FFFFFF"))
                        cornerRadius = 17 * dp.toFloat()
                    },
                    null,
                )
                layoutParams = LinearLayout.LayoutParams(34 * dp, 34 * dp)
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
            private const val GLASS_FILL = "#D9101014"
            private const val GLASS_SHEEN = "#26FFFFFF"
            private const val GLASS_SHADE = "#0D000000"
            private const val GLASS_STROKE = "#40FFFFFF"
            private const val STROKE_SOFT = "#33FFFFFF"

            private const val ITEM_BG = "#F01C1C22"
            private const val ITEM_BG_SELECTED = "#F0262B36"
            private const val RIPPLE = "#26FFFFFF"

            /** Белая волна на кнопках — видимая (40% белого). */
            private const val WAVE = "#66FFFFFF"

            private const val TEXT = "#FFF5F6FA"
            private const val TEXT_SOFT = "#FFE9EBF0"
            private const val TEXT_DIM = "#FF9AA3AD"

            private const val ACCENT = "#FF8AB4F8"
            private const val BTN_TEXT = "#FF10141C"
            private const val BTN_RIPPLE = "#33FFFFFF"
        }
    }

    /** Корень: прозрачный контейнер (окно не анимируем), BACK гасится. */
    private class PastePanelRoot(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
            if (ev.action == KeyEvent.ACTION_DOWN && ev.keyCode == KeyEvent.KEYCODE_BACK) {
                return true
            }
            return super.dispatchKeyEvent(ev)
        }
    }
}
