package com.example.remotescreen

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
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

        /** Pushes JSON messages (macro state, game alerts) to the controller while one is connected. */
        @Volatile
        var eventListener: ((JSONObject) -> Unit)? = null

        const val ACTION_MACRO_STOP = "com.example.remotescreen.MACRO_STOP"
        private const val MACRO_CHANNEL = "macro"
        private const val MACRO_NOTIF = 7

        /** Saved macros live in shared preferences so they survive restarts and can be started from the app. */
        fun loadMacros(ctx: Context): JSONArray =
            try { JSONArray(ctx.getSharedPreferences("game", MODE_PRIVATE).getString("macros", "[]")) } catch (_: Throwable) { JSONArray() }

        fun saveMacros(ctx: Context, a: JSONArray) =
            ctx.getSharedPreferences("game", MODE_PRIVATE).edit().putString("macros", a.toString()).apply()
    }

    private val main = Handler(Looper.getMainLooper())

    private var lastFocusKey: String? = null

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { stopMacro() }
    }

    override fun onServiceConnected() {
        instance = this
        val prefs = getSharedPreferences("game", MODE_PRIVATE)
        watchPkg = prefs.getString("watchPkg", null)
        watchLabel = prefs.getString("watchLabel", null)
        relaunch = prefs.getBoolean("relaunch", false)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stopReceiver, IntentFilter(ACTION_MACRO_STOP), RECEIVER_NOT_EXPORTED)
        else registerReceiver(stopReceiver, IntentFilter(ACTION_MACRO_STOP))
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopMacro()
        try { unregisterReceiver(stopReceiver) } catch (_: Throwable) {}
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            onForeground(event.packageName?.toString())
            maybeAutoConfirm(event.packageName?.toString())
        }
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

    private fun run(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 2000))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun tap(nx: Float, ny: Float, durationMs: Long) {
        val (w, h) = screenSize()
        val x = nx.coerceIn(0f, 1f) * w
        val y = ny.coerceIn(0f, 1f) * h
        // Vài ROM từ chối path chỉ có moveTo → thêm lineTo 1px cho chắc.
        val p = Path().apply { moveTo(x, y); lineTo(x + 1f, y) }
        // < 120 ms là chạm thường, dài hơn là long-press
        val d = if (durationMs < 120) 50 else durationMs
        if (!run(p, d)) {
            // dispatchGesture trả false khi đang bận gesture khác → thử lại 1 lần sau 40ms
            main.postDelayed({ run(p, d) }, 40)
        }
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

    // ================================================================ self-update helper
    // Only while our own updater asked for it (a few minutes): press "Install" in the package installer
    // and "Start now" in the screen-capture dialog, so a remote update needs nobody at the phone.
    private val confirmWords = listOf("Bắt đầu ngay", "Start now", "Bắt đầu", "Start", "Chia sẻ màn hình", "Share screen", "Start sharing", "Cài đặt", "Install", "Cập nhật", "Update", "Tiếp tục", "Continue")
    private val dontAskWords = listOf("Không hiển thị lại", "Don't show again", "Do not show again")
    // Nhận diện lựa chọn trong ô xổ "phạm vi" của hộp thoại chia sẻ (Android 14+): dựa theo nội dung chữ,
    // vì mỗi hãng ghi khác nhau ("Toàn màn hình" / "Toàn bộ màn hình" / "Chia sẻ toàn bộ màn hình"...).
    private fun isEntireText(t: String): Boolean {
        val s = t.lowercase()
        return (s.contains("toàn") && s.contains("màn hình")) ||
               ((s.contains("entire") || s.contains("full")) && s.contains("screen"))
    }
    private fun isSingleText(t: String): Boolean {
        val s = t.lowercase()
        return (s.contains("một") && s.contains("ứng dụng")) ||
               (s.contains("single") && s.contains("app"))
    }

    private fun maybeAutoConfirm(pkg: String?) {
        if (pkg == null || !Updater.autoConfirmActive(this)) return
        if (!(pkg.contains("packageinstaller") || pkg == "com.android.systemui" || pkg.contains("permissioncontroller"))) return
        // Hộp thoại chia sẻ của Samsung cần tới 2 bước (mở ô xổ → chọn toàn màn hình → bấm Bắt đầu),
        // mỗi lần confirmNow chỉ làm MỘT bước nên lên lịch nhiều mốc để đi hết chuỗi.
        for (d in longArrayOf(250, 700, 1300, 2000, 2800, 3800, 5000, 6500)) main.postDelayed({ confirmNow() }, d)
    }

    /** Node bấm được: chính nó hoặc cha trong vài cấp (để bỏ qua đoạn mô tả không bấm được). */
    private fun clickableTarget(node: AccessibilityNodeInfo?, maxUp: Int = 2): AccessibilityNodeInfo? {
        var c = node; var up = 0
        while (c != null && up <= maxUp) {
            if (c.isClickable && c.isEnabled) return c
            c = c.parent; up++
        }
        return null
    }

    /** Gốc của MỌI cửa sổ đang hiện — danh sách ô xổ mở ra ở cửa sổ popup riêng, không nằm trong cửa sổ active. */
    private fun allRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try { rootInActiveWindow?.let { roots.add(it) } } catch (_: Throwable) {}
        try { for (w in windows) { try { w.root?.let { roots.add(it) } } catch (_: Throwable) {} } } catch (_: Throwable) {}
        return roots
    }

    /** Tìm một lựa chọn bấm được khớp [match] trên tất cả cửa sổ (nhãn trong ô xổ/popup). */
    private fun findClickableOption(roots: List<AccessibilityNodeInfo>, searchKey: String, match: (String) -> Boolean): AccessibilityNodeInfo? {
        for (r in roots) {
            val hits = try { r.findAccessibilityNodeInfosByText(searchKey) } catch (_: Throwable) { emptyList<AccessibilityNodeInfo>() }
            for (n in hits) {
                val t = n.text?.toString()?.trim() ?: continue
                if (match(t)) clickableTarget(n, 2)?.let { return it }
            }
        }
        return null
    }

    private fun confirmNow() {
        if (!Updater.autoConfirmActive(this)) return
        val root = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return
        val pkg = root.packageName?.toString() ?: return
        if (!(pkg.contains("packageinstaller") || pkg == "com.android.systemui" || pkg.contains("permissioncontroller"))) return
        for (w in dontAskWords) for (n in root.findAccessibilityNodeInfosByText(w)) {
            if (n.isCheckable && !n.isChecked) n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // Hộp thoại chia sẻ màn hình: máy Samsung có ô xổ "Một ứng dụng / Toàn (bộ) màn hình".
        // Danh sách xổ ra nằm ở cửa sổ popup riêng nên phải dò qua TẤT CẢ cửa sổ; mỗi nhịp làm ĐÚNG MỘT việc.
        if (pkg == "com.android.systemui") {
            val roots = allRoots()
            val entire = findClickableOption(roots, "màn hình") { isEntireText(it) }
                ?: findClickableOption(roots, "screen") { isEntireText(it) }
            val single = findClickableOption(roots, "ứng dụng") { isSingleText(it) }
                ?: findClickableOption(roots, "app") { isSingleText(it) }
            when {
                entire != null && single != null -> { entire.performAction(AccessibilityNodeInfo.ACTION_CLICK); return }
                single != null && entire == null -> { single.performAction(AccessibilityNodeInfo.ACTION_CLICK); return }
            }
        }
        for (w in confirmWords) for (n in root.findAccessibilityNodeInfosByText(w)) {
            if (n.text?.toString()?.trim() != w) continue
            var c: AccessibilityNodeInfo? = n
            while (c != null && !c.isClickable) c = c.parent
            if (c != null && c.isEnabled && (c.className?.contains("Button") == true || n.className?.contains("Button") == true)) {
                c.performAction(AccessibilityNodeInfo.ACTION_CLICK); return
            }
        }
    }

    // ================================================================ realtime multi-touch ("game mode")
    // Each finger is a continued stroke. Every short segment we dispatch one gesture that continues all
    // fingers that are down, so several fingers can be held and moved at the same time.

    private class Finger(var x: Float, var y: Float) {
        var ex = x; var ey = y                       // where the last dispatched segment ended
        var stroke: GestureDescription.StrokeDescription? = null
        var up = false
    }
    private val fingers = LinkedHashMap<Int, Finger>()
    private var inFlight = false
    private var touchW = 0; private var touchH = 0
    private val SEG = 16L

    fun touchDown(i: Int, nx: Float, ny: Float) = main.post {
        if (Build.VERSION.SDK_INT < 26) { tap(nx, ny, 50); return@post }
        if (fingers.isEmpty()) { val (w, h) = screenSize(); touchW = w; touchH = h }
        if (fingers.size >= 10) return@post
        fingers[i] = Finger(nx.coerceIn(0f, 1f) * touchW, ny.coerceIn(0f, 1f) * touchH)
        pump()
    }

    fun touchMove(i: Int, nx: Float, ny: Float) = main.post {
        val f = fingers[i] ?: return@post
        if (f.up) return@post
        f.x = nx.coerceIn(0f, 1f) * touchW; f.y = ny.coerceIn(0f, 1f) * touchH
        pump()
    }

    fun touchUp(i: Int) = main.post { fingers[i]?.up = true; pump() }

    fun touchCancel() = main.post { fingers.values.forEach { it.up = true }; pump() }

    private fun pump() {
        if (inFlight || fingers.isEmpty() || Build.VERSION.SDK_INT < 26) return
        val b = GestureDescription.Builder()
        val live = fingers.values.toList()
        for (f in live) {
            val path = Path().apply {
                moveTo(f.ex, f.ey)
                if (f.x != f.ex || f.y != f.ey) lineTo(f.x, f.y)
            }
            val cont = !f.up
            val st = f.stroke
            f.stroke = if (st == null) GestureDescription.StrokeDescription(path, 0, if (cont) SEG else 40L, cont)
                       else st.continueStroke(path, 0, SEG, cont)
            f.ex = f.x; f.ey = f.y
            b.addStroke(f.stroke!!)
        }
        inFlight = true
        val ok = dispatchGesture(b.build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { afterSegment(false) }
            override fun onCancelled(g: GestureDescription?) { afterSegment(true) }
        }, main)
        if (!ok) afterSegment(true)
    }

    private fun afterSegment(cancelled: Boolean) {
        inFlight = false
        fingers.entries.removeAll { it.value.up }
        // A cancelled gesture can't be continued. Pressing again would create stray taps, so the
        // fingers are dropped; the controller starts a new touch on its next move.
        if (cancelled) { fingers.clear(); return }
        if (fingers.isNotEmpty()) pump()
    }

    // ================================================================ foreground app watch
    private var currentPkg: String? = null
    private var watchPkg: String? = null
    private var watchLabel: String? = null
    private var relaunch = false
    private var awaySince = 0L
    private var alerted = false

    private fun isTransient(p: String) = p == packageName || p == "com.android.systemui" || p.contains("inputmethod") ||
        p.contains("keyboard") || p == "android"

    private fun onForeground(pkg: String?) {
        if (pkg == null || isTransient(pkg)) return
        currentPkg = pkg
        val w = watchPkg ?: return
        if (pkg == w) {
            if (alerted) emit(JSONObject().put("t", "alert").put("k", "game_back").put("msg", "${watchLabel ?: "Game"} đã mở lại"))
            awaySince = 0; alerted = false
        } else if (awaySince == 0L) {
            awaySince = SystemClock.uptimeMillis()
            main.postDelayed(::checkAway, 4000)
        }
    }

    private fun checkAway() {
        val w = watchPkg ?: return
        if (awaySince == 0L || currentPkg == w || alerted) return
        alerted = true
        emit(JSONObject().put("t", "alert").put("k", "game_exit")
            .put("msg", "${watchLabel ?: "Game"} đã bị thoát" + if (relaunch) " · đang mở lại…" else ""))
        if (relaunch) main.postDelayed({ openWatched() }, 1500)
    }

    fun openWatched(): Boolean {
        val w = watchPkg ?: return false
        return try {
            val i = packageManager.getLaunchIntentForPackage(w) ?: return false
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)); true
        } catch (_: Throwable) { false }
    }

    /** Pin the app currently on screen as "the game" (or clear it). */
    fun setWatch(on: Boolean, relaunchOn: Boolean) {
        relaunch = relaunchOn
        if (on) {
            val p = currentPkg
            if (p != null) {
                watchPkg = p
                watchLabel = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() } catch (_: Throwable) { p }
            }
        } else { watchPkg = null; watchLabel = null }
        awaySince = 0; alerted = false
        getSharedPreferences("game", MODE_PRIVATE).edit().putString("watchPkg", watchPkg).putString("watchLabel", watchLabel)
            .putBoolean("relaunch", relaunch).apply()
        emitState()
    }

    // ================================================================ macros / auto click
    // A macro = list of steps {t:"tap"|"swipe", x,y,(x2,y2), d, w(wait before, ms)}, repeated `loops` times
    // (0 = forever) with `gap` ms between rounds. Runs entirely on the phone.
    private var macro: JSONObject? = null
    private var macroStep = 0
    private var macroRound = 0
    private val macroTick = Runnable { macroNext() }

    fun runMacro(name: String): Boolean {
        val a = loadMacros(this)
        for (k in 0 until a.length()) if (a.getJSONObject(k).optString("name") == name) {
            stopMacro(false)
            macro = a.getJSONObject(k); macroStep = 0; macroRound = 0
            showMacroNotification(name)
            main.postDelayed(macroTick, 600)
            emitState(); return true
        }
        return false
    }

    fun stopMacro(notify: Boolean = true) {
        main.removeCallbacks(macroTick)
        val was = macro != null
        macro = null
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(MACRO_NOTIF)
        if (was && notify) emitState()
    }

    private fun macroNext() {
        val m = macro ?: return
        val steps = m.optJSONArray("steps") ?: return stopMacro()
        if (steps.length() == 0) return stopMacro()
        // Never click blindly outside the game: wait while another app is in front.
        val w = watchPkg
        if (w != null && currentPkg != null && currentPkg != w) { main.postDelayed(macroTick, 1500); return }
        if (macroStep >= steps.length()) {
            macroStep = 0; macroRound++
            val loops = m.optInt("loops", 0)
            if (loops in 1..macroRound) { stopMacro(); return }
            main.postDelayed(macroTick, m.optLong("gap", 500).coerceAtLeast(50)); return
        }
        val s = steps.getJSONObject(macroStep++)
        val f = { k: String -> s.optDouble(k, 0.5).toFloat() }
        when (s.optString("t")) {
            "tap" -> tap(f("x"), f("y"), s.optLong("d", 50))
            "swipe" -> swipe(f("x"), f("y"), f("x2"), f("y2"), s.optLong("d", 300))
        }
        val next = if (macroStep < steps.length()) steps.getJSONObject(macroStep).optLong("w", 300) else 0L
        main.postDelayed(macroTick, maxOf(next, s.optLong("d", 50) + 30))
    }

    private fun showMacroNotification(name: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(MACRO_CHANNEL, "Macro", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getBroadcast(this, 1, Intent(ACTION_MACRO_STOP).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, MACRO_CHANNEL) else Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Đang chạy macro: $name")
            .setContentText("Bấm Dừng để tắt auto")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Dừng", stop).build())
            .build()
        try { nm.notify(MACRO_NOTIF, n) } catch (_: Throwable) {}
    }

    fun saveMacro(m: JSONObject) {
        val a = loadMacros(this); val out = JSONArray()
        for (k in 0 until a.length()) if (a.getJSONObject(k).optString("name") != m.optString("name")) out.put(a.getJSONObject(k))
        out.put(m); saveMacros(this, out); emitState()
    }

    fun deleteMacro(name: String) {
        val a = loadMacros(this); val out = JSONArray()
        for (k in 0 until a.length()) if (a.getJSONObject(k).optString("name") != name) out.put(a.getJSONObject(k))
        saveMacros(this, out); if (macro?.optString("name") == name) stopMacro(); emitState()
    }

    fun macroRunning(): String? = macro?.optString("name")

    /** Everything the controller's game panel shows. */
    fun stateJson(): JSONObject {
        val list = JSONArray()
        val a = loadMacros(this)
        for (k in 0 until a.length()) {
            val m = a.getJSONObject(k)
            list.put(JSONObject().put("name", m.optString("name")).put("n", m.optJSONArray("steps")?.length() ?: 0)
                .put("loops", m.optInt("loops")).put("gap", m.optLong("gap")))
        }
        return JSONObject().put("t", "game").put("macros", list).put("running", macroRunning() ?: JSONObject.NULL)
            .put("watch", watchLabel ?: JSONObject.NULL).put("relaunch", relaunch)
            .put("current", currentPkg?.let { p -> try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() } catch (_: Throwable) { p } } ?: JSONObject.NULL)
    }

    fun emitState() = emit(stateJson())
    private fun emit(o: JSONObject) { try { eventListener?.invoke(o) } catch (_: Throwable) {} }
}
