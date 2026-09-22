package com.clipcells.app.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewTreeObserver

/**
 * Clipboard writer for the overlay that respects the Android 10+ rule proven
 * on this device (see the v0.5.0 experiment in STATE.md): setPrimaryClip from
 * an app whose uid does NOT own the focused window is silently ignored.
 *
 * The panel is a focusable window, so a tap-copy already runs while focused.
 * The rare unfocused instant — focus transfer still in flight right after the
 * panel appeared — is covered by a pending write that flushes the moment the
 * panel gains window focus. Nothing is ever lost, nothing writes "в никуда".
 */
class OverlayCopier(
    context: Context,
    private val focusView: View,
    private val onResult: (Result) -> Unit,
    private val cancelCopyQueue: () -> Unit,
) {

    sealed interface Result {
        /** Text landed in the clipboard. [order] is the human hint like "2/5". */
        data class Copied(val label: String, val order: String) : Result

        /** Write deferred until the panel gains focus. */
        data class WaitingForFocus(val label: String, val order: String) : Result
    }

    private val appContext = context.applicationContext
    private val clipboard =
        appContext.getSystemService(ClipboardManager::class.java)

    private var pending: Pending? = null

    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        if (hasFocus) flushPending()
    }

    /**
     * Window-focus listeners registered on a view that is not yet attached
     * to a window live on a floating observer and MAY not survive the
     * attach merge — so we (re)register at attach time, which is also the
     * moment the view gains its real ViewTreeObserver. Idempotent with the
     * direct registration for the already-attached case.
     */
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        }

        override fun onViewDetachedFromWindow(v: View) {
            try {
                v.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            } catch (_: Exception) {
            }
        }
    }

    init {
        if (focusView.isAttachedToWindow) {
            focusView.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        }
        focusView.addOnAttachStateChangeListener(attachListener)
    }

    /**
     * Puts [text] into the clipboard as [label]. Cancels the in-app copy queue
     * first: a running CopyService would fight the overlay for the buffer.
     */
    fun copy(label: String, text: String, order: String) {
        try {
            cancelCopyQueue()
        } catch (_: Exception) {
            // Queue cancel is best-effort; copying must proceed regardless.
        }

        val item = Pending(label, text, order)
        if (focusView.hasWindowFocus()) {
            write(item)
        } else {
            pending = item
            onResult(Result.WaitingForFocus(label, order))
        }
    }

    private fun flushPending() {
        val item = pending ?: return
        pending = null
        write(item)
    }

    private fun write(item: Pending) {
        try {
            clipboard?.setPrimaryClip(ClipData.newPlainText(item.label, item.text))
            onResult(Result.Copied(item.label, item.order))
        } catch (_: Exception) {
            // Clipboard service hiccup — keep it pending, the next focus
            // change (or tap) retries the write.
            pending = item
            onResult(Result.WaitingForFocus(item.label, item.order))
        }
    }

    fun dispose() {
        try {
            focusView.removeOnAttachStateChangeListener(attachListener)
            focusView.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
        } catch (_: Exception) {
            // Observer already dead with the detached view — nothing to clean.
        }
        pending = null
    }

    private data class Pending(val label: String, val text: String, val order: String)
}
