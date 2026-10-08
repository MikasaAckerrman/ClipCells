package com.clipcells.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Плавающая кнопка «Вставить» (v0.14): появляется после копирования ячейки.
 * Тап — текст встанет ПРЯМО в сфокусированное поле приложения под окном
 * (через a11y, в обход буфера и Gboard). Долгое нажатие — убрать кнопку.
 * ОКНО НЕ ЗАБИРАЕТ ФОКУС (FLAG_NOT_FOCUSABLE) — курсор в поле цели остаётся.
 */
object PasteFab {

    @Volatile private var liveRoot: View? = null
    @Volatile private var liveWm: WindowManager? = null
    @Volatile private var pendingText: String? = null
    private val main = Handler(Looper.getMainLooper())

    fun isShowing(): Boolean = liveRoot != null

    fun show(context: Context, label: String, text: String) {
        val app = context.applicationContext
        pendingText = text
        main.post {
            if (isShowing()) {
                (liveRoot as? FabRow)?.updateLabel(label)
                return@post
            }
            val wm = app.getSystemService(WindowManager::class.java) ?: return@post
            val row = FabRow(app, label)
            val params = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 48 * dp(app)
            }
            try {
                wm.addView(row, params)
                liveRoot = row
                liveWm = wm
                row.alpha = 0f
                row.animate().alpha(1f).setDuration(120).start()
            } catch (e: Exception) {
                android.util.Log.w("ClipCellsOverlay", "fab addView failed", e)
            }
        }
    }

    private fun hide() {
        val root = liveRoot ?: return
        val wm = liveWm
        main.post {
            try {
                wm?.removeView(root)
            } catch (_: Exception) {
            }
        }
        liveRoot = null
        liveWm = null
        pendingText = null
    }

    /** Одна строка-кнопка: [⎘ Вставить «label»] [✕]. */
    private class FabRow(context: Context, label: String) : LinearLayout(context) {

        private val mainLabel: TextView
        private val selfPkg = context.packageName
        private val dp = dp(context)

        init {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(6 * dp, 6 * dp, 6 * dp, 6 * dp)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0101010"))
                cornerRadius = 22 * dp.toFloat()
                setStroke(dp, Color.parseColor("#26FFFFFF"))
            }

            mainLabel = TextView(context).apply {
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.parseColor("#FF8AB4F8"))
                setPadding(14 * dp, 10 * dp, 14 * dp, 10 * dp)
                isClickable = true
            }
            updateLabel(label)
            addView(mainLabel)

            addView(TextView(context).apply {
                text = "✕"
                textSize = 13f
                gravity = Gravity.CENTER
                contentDescription = "Убрать кнопку вставки"
                setTextColor(Color.parseColor("#FF9E9E9E"))
                setPadding(10 * dp, 10 * dp, 12 * dp, 10 * dp)
                isClickable = true
                setOnClickListener { hide() }
            })

            bindTap(mainLabel)
        }

        fun updateLabel(label: String) {
            mainLabel.text = "⎘ Вставить «${label.take(18)}»"
            contentDescription = "Вставить ячейку $label"
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun bindTap(view: TextView) {
            var downTime = 0L
            var moved = false
            val slop = ViewConfiguration.get(context).scaledTouchSlop * 2
            var downX = 0f
            var downY = 0f
            view.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downTime = android.os.SystemClock.elapsedRealtime()
                        downX = ev.rawX; downY = ev.rawY; moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(ev.rawX - downX) > slop ||
                            kotlin.math.abs(ev.rawY - downY) > slop
                        ) moved = true
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            v.performClick()
                            if (android.os.SystemClock.elapsedRealtime() - downTime > 480) {
                                hide() // долгое нажатие
                            } else {
                                pasteNow()
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        private fun pasteNow() {
            val text = pendingText ?: return hide()
            Thread {
                val result = PasteAccessibilityService.pasteIntoFocusedField(selfPkg, text)
                main.post {
                    when (result) {
                        PasteResult.Pasted -> {
                            performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                            hide()
                        }
                        PasteResult.NoService -> {
                            Toast.makeText(
                                context,
                                "Вставка недоступна — включите сервис в специальных возможностях ClipCells",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        PasteResult.NoField -> {
                            Toast.makeText(
                                context,
                                "Нет поля ввода — тапните в поле под кнопкой и нажмите ещё раз",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is PasteResult.Rejected -> {
                            Toast.makeText(
                                context,
                                "Поле не приняло текст (${result.nodeClass}) — используйте «Поделиться»",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }.start()
        }
    }

    private fun dp(context: Context): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 1f, context.resources.displayMetrics
    ).toInt().coerceAtLeast(1)
}
