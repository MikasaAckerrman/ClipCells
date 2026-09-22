package com.clipcells.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.content.SharedPreferences
import kotlin.math.abs

/**
 * The small persistent ClipCells bubble floating above every app: 56dp dark
 * circle with a 2x2 cells glyph. Tap — the cells panel opens; drag — move
 * (position persisted); long-press — stop the whole overlay (restore from the
 * app's toolbar toggle).
 *
 * The window is NOT focusable: it never steals focus from the app below and
 * never triggers an IME — the bubble is a launcher, not an input surface.
 */
class OverlayBubble(
    private val context: Context,
    private val wm: WindowManager,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
) {

    private val dp = dpFactor()
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var view: FrameLayout? = null
    private var visible = false

    fun isShowing(): Boolean = visible

    fun show() {
        if (visible) return
        if (!Settings.canDrawOverlays(context)) return
        val bubble = build()
        val params = layoutParams().apply {
            val dm = context.resources.displayMetrics
            val saved = prefs.getInt(KEY_X, -1)
            if (saved >= 0) {
                x = saved.coerceIn(0, dm.widthPixels - bubble.layoutParams.width)
                y = prefs.getInt(KEY_Y, (dm.heightPixels * 0.42f).toInt())
                    .coerceIn(0, dm.heightPixels - bubble.layoutParams.height)
            } else {
                x = dm.widthPixels - SIDE - EDGE
                y = (dm.heightPixels * 0.42f).toInt()
            }
        }
        try {
            wm.addView(bubble, params)
            view = bubble
            visible = true
            android.util.Log.i(TAG, "bubble shown")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "bubble addView failed", e)
        }
    }

    fun hide() {
        val v = view ?: return
        android.util.Log.i(TAG, "bubble hide")
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
        view = null
        visible = false
    }

    // ------------------------------------------------------------- window

    private fun layoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    // ------------------------------------------------------------- bubble

    @SuppressLint("ClickableViewAccessibility")
    private fun build(): FrameLayout {
        val bubble = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(SIDE, SIDE)
            isClickable = true
        }

        bubble.addView(View(context).apply {
            background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.parseColor(RIPPLE)),
                GradientDrawable().apply {
                    setColor(Color.parseColor(BG))
                    setStroke(dp, Color.parseColor(STROKE))
                    shape = GradientDrawable.OVAL
                },
                null,
            )
            layoutParams = FrameLayout.LayoutParams(SIDE, SIDE, Gravity.CENTER)
        })
        bubble.addView(CellsGlyph(context).apply {
            layoutParams = FrameLayout.LayoutParams(SIDE, SIDE, Gravity.CENTER)
        })
        bubble.contentDescription = "ClipCells"

        var downX = 0f
        var downY = 0f
        var moved = false
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop * 2

        // Manual long-press: the touch listener owns the whole stream, so the
        // system long-click never fires — time it ourselves.
        val longPress = Runnable {
            if (!moved) onLongPress()
        }

        bubble.setOnTouchListener { v, ev ->
            val lp = v.layoutParams as WindowManager.LayoutParams
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    moved = false
                    v.handler?.postDelayed(longPress, LONG_PRESS_MILLIS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        v.handler?.removeCallbacks(longPress)
                    }
                    if (moved) {
                        val dm = context.resources.displayMetrics
                        lp.x = (lp.x + dx).toInt().coerceIn(0, dm.widthPixels - v.width)
                        lp.y = (lp.y + dy).toInt().coerceIn(0, dm.heightPixels - v.height)
                        downX = ev.rawX
                        downY = ev.rawY
                        try {
                            wm.updateViewLayout(v, lp)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.handler?.removeCallbacks(longPress)
                    if (!moved) {
                        v.performClick()
                        onTap()
                    } else {
                        persistPosition(lp.x, lp.y)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.handler?.removeCallbacks(longPress)
                    if (moved) persistPosition(lp.x, lp.y)
                    true
                }
                else -> false
            }
        }
        return bubble
    }

    private fun persistPosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    /** The ClipCells mark: a 2x2 grid of rounded cells. */
    private class CellsGlyph(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor(ACCENT)
        }
        private val r = RectF()

        override fun onDraw(c: Canvas) {
            val s = width.toFloat()
            paint.strokeWidth = s * 0.055f
            val gap = s * 0.16f
            val cell = (s - gap * 3) / 2
            for (row in 0..1) {
                for (col in 0..1) {
                    val left = gap + col * (cell + gap)
                    val top = gap + row * (cell + gap)
                    r.set(left, top, left + cell, top + cell)
                    c.drawRoundRect(r, cell * 0.30f, cell * 0.30f, paint)
                }
            }
        }
    }

    private fun dpFactor(): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 1f, context.resources.displayMetrics
    ).toInt().coerceAtLeast(1)

    companion object {
        private const val PREFS = "overlay"
        private const val KEY_X = "bubble_x"
        private const val KEY_Y = "bubble_y"

        private const val BG = "#E6101010"
        private const val STROKE = "#33FFFFFF"
        private const val RIPPLE = "#33FFFFFF"
        private const val ACCENT = "#FF8AB4F8"

        /** px — 56dp is recomputed via dp at build time. */
        private const val LONG_PRESS_MILLIS = 480L
        private const val TAG = "ClipCellsOverlay"
    }

    private val SIDE = 56 * dp
    private val EDGE = 16 * dp
}
