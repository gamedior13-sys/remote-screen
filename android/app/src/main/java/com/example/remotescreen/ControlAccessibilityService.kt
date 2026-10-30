package com.example.remotescreen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Executes the commands received from the controller: tap, swipe, global keys and text.
 * All coordinates arrive normalised (0..1) relative to the real screen size.
 */
class ControlAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ControlAccessibilityService? = null
            private set

        /** Called with (editableFieldFocused, currentText, isPassword) whenever input focus changes. */
        @Volatile
        var imeListener: ((Boolean, String, Boolean) -> Unit)? = null
    }

    private var lastFocusKey: String? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> reportFocus(false)
        }
    }

    /** Tell the controller whether a text field is focused, so it can show its keyboard. */
    fun reportFocus(force: Boolean) {
        val listener = imeListener ?: return
        val node = try { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Throwable) { null }
        val editable = node != null && node.isEditable
        val key = if (editable) "${node!!.windowId}:${node.hashCode()}" else "none"
        if (!force && key == lastFocusKey) return
        lastFocusKey = key
        if (!editable) { listener(false, "", false); return }
        val pw = node!!.isPassword
        val text = if (pw || node.isShowingHintText) "" else node.text?.toString().orEmpty()
        listener(true, text, pw)
    }
    override fun onInterrupt() {}

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b: Rect = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val m = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            m.widthPixels to m.heightPixels
        }
    }

    private fun run(path: Path, durationMs: Long) {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 2000))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun tap(nx: Float, ny: Float, durationMs: Long) {
        val (w, h) = screenSize()
        val p = Path().apply { moveTo(nx.coerceIn(0f, 1f) * w, ny.coerceIn(0f, 1f) * h) }
        // < 120 ms is a normal tap, longer presses are passed through as long-press
        run(p, if (durationMs < 120) 50 else durationMs)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val (w, h) = screenSize()
        val p = Path().apply {
            moveTo(x1.coerceIn(0f, 1f) * w, y1.coerceIn(0f, 1f) * h)
            lineTo(x2.coerceIn(0f, 1f) * w, y2.coerceIn(0f, 1f) * h)
        }
        run(p, durationMs)
    }

    fun key(name: String) {
        when (name) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "backspace" -> editFocused { current -> current.dropLast(1) }
            "enter" -> {
                val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
                if (Build.VERSION.SDK_INT >= 30) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else {
                    editFocused { it + "\n" }
                }
            }
        }
    }

    fun text(s: String) = editFocused { it + s }

    /** Replace the whole content of the focused field (live mirror of the controller's input box). */
    fun setText(s: String) = editFocused { s }

    /** Replace the text of the focused input field with transform(currentText). */
    private fun editFocused(transform: (String) -> String) {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        // A field showing only its hint reports the hint as text; ignore it.
        val current = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, transform(current))
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
