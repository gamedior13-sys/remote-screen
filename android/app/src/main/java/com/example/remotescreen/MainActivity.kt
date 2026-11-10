package com.example.remotescreen

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.os.PowerManager
import android.net.Uri
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.random.Random

/**
 * One screen: device ID (+ copy), password, a single Start/Stop button and live status.
 * The server address is built in (DEFAULT_SERVER) and can be changed under "Nâng cao".
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** Public signaling server. Change here (or in the app under "Nâng cao") if you host it elsewhere. */
        const val DEFAULT_SERVER = "https://remote-screen-phong.onrender.com"

        private const val BG = 0xFF0B0D12.toInt()
        private const val CARD = 0xFF161A23.toInt()
        private const val CARD2 = 0xFF1C212C.toInt()
        private const val LINE = 0xFF2A3140.toInt()
        private const val TEXT = 0xFFEEF1F7.toInt()
        private const val MUTED = 0xFF8A93A8.toInt()
        private const val ACCENT = 0xFF4F8CFF.toInt()
        private const val ACCENT2 = 0xFF7B5CFF.toInt()
        private const val OK = 0xFF2FD17A.toInt()
        private const val WARN = 0xFFFFB547.toInt()
        private const val BAD = 0xFFFF5D6C.toInt()
    }

    private lateinit var serverEdit: EditText
    private lateinit var passwordEdit: EditText
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var a11yRow: LinearLayout
    private lateinit var battRow: LinearLayout
    private lateinit var macroBox: LinearLayout
    private var macroSig = ""
    private lateinit var updText: TextView
    private lateinit var updBtn: Button
    private var updLatest: Long = 0
    private lateinit var awBtn: Button
    private lateinit var awText: TextView
    private lateinit var startBtn: Button
    private lateinit var advanced: LinearLayout
    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }
    private val ticker = object : Runnable {
        override fun run() { refresh(); statusText.postDelayed(this, 1000) }
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == Activity.RESULT_OK && data != null) {
                val svc = Intent(this, ShareService::class.java)
                    .putExtra(ShareService.EXTRA_RESULT_DATA, data)
                    .putExtra(ShareService.EXTRA_SERVER, server())
                    .putExtra(ShareService.EXTRA_ID, deviceId())
                    .putExtra(ShareService.EXTRA_PASSWORD, passwordEdit.text.toString())
                startForegroundService(svc)
                statusText.postDelayed({ refresh() }, 600)
            } else {
                toast("Bạn đã từ chối quyền chia sẻ màn hình")
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    private fun deviceId(): String =
        prefs.getString("id", null) ?: Random.nextInt(100_000_000, 999_999_999).toString()
            .also { prefs.edit().putString("id", it).apply() }

    private fun server(): String = serverEdit.text.toString().trim().ifEmpty { DEFAULT_SERVER }

    // ------------------------------------------------------------------ UI helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun gradient(radius: Int) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ACCENT, ACCENT2)).apply {
        cornerRadius = dp(radius).toFloat()
    }

    private fun label(text: String, size: Float = 13f, color: Int = MUTED, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 20, LINE)
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }

    private fun input(hintText: String) = EditText(this).apply {
        hint = hintText; setHintTextColor(0xFF5D6579.toInt()); setTextColor(TEXT); textSize = 16f
        background = rounded(BG, 14, LINE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        isSingleLine = true
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.statusBarColor = BG
        @Suppress("DEPRECATION")
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(28))
        }

        // ---- header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
            addView(label("Remote Screen", 20f, TEXT, true))
            addView(label("Cho phép điều khiển máy này từ xa"))
        })
        root.addView(header)

        // ---- ID card
        val idCard = card()
        idCard.addView(label("ID CỦA MÁY NÀY", 12f, MUTED, true))
        val idRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        idRow.addView(TextView(this).apply {
            text = deviceId().chunked(3).joinToString(" ")
            textSize = 30f; setTextColor(TEXT); setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        idRow.addView(Button(this).apply {
            text = "Copy"; isAllCaps = false; textSize = 14f; setTextColor(TEXT)
            background = rounded(CARD2, 12, LINE); stateListAnimator = null
            setOnClickListener { copyId() }
        }, LinearLayout.LayoutParams(dp(84), dp(44)))
        idCard.addView(idRow, lp(6))
        idCard.addView(label("Gửi ID này cho người điều khiển. Họ mở trang web Remote Screen rồi nhập ID và mật khẩu.", 12.5f), lp(8))
        root.addView(idCard, lp(24))

        // ---- password + advanced
        val pwCard = card()
        pwCard.addView(label("MẬT KHẨU KẾT NỐI", 12f, MUTED, true))
        passwordEdit = input("Tối thiểu 6 ký tự").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("password", ""))
        }
        pwCard.addView(passwordEdit, lp(8))

        val advToggle = label("Nâng cao ▾", 13f, ACCENT).apply { setPadding(0, dp(14), 0, 0) }
        pwCard.addView(advToggle)
        advanced = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        serverEdit = input(DEFAULT_SERVER).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("server2", ""))
            textSize = 14f
        }
        advanced.addView(label("Địa chỉ máy chủ (để trống = mặc định)", 12.5f), lp(10))
        advanced.addView(serverEdit, lp(6))
        pwCard.addView(advanced)
        advToggle.setOnClickListener {
            val show = advanced.visibility != View.VISIBLE
            advanced.visibility = if (show) View.VISIBLE else View.GONE
            advToggle.text = if (show) "Nâng cao ▴" else "Nâng cao ▾"
        }
        root.addView(pwCard, lp(12))

        // ---- status
        val stCard = card()
        val stRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusDot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(MUTED) } }
        stRow.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)))
        statusText = label("", 15f, TEXT, true).apply { setPadding(dp(10), 0, 0, 0) }
        stRow.addView(statusText)
        stCard.addView(stRow)

        a11yRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(0x22FFB547, 12); setPadding(dp(12), dp(10), dp(8), dp(10))
        }
        a11yRow.addView(
            label("Chưa bật quyền điều khiển: chỉ xem được, chưa bấm được", 12.5f, WARN),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        a11yRow.addView(Button(this).apply {
            text = "Bật"; isAllCaps = false; setTextColor(Color.BLACK); textSize = 13f
            background = rounded(WARN, 10); stateListAnimator = null
            setOnClickListener { openA11y() }
        }, LinearLayout.LayoutParams(dp(64), dp(38)))
        stCard.addView(a11yRow, lp(12))

        // Long idle-game sessions: let the app run without battery restrictions.
        battRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(0x22FFB547, 12); setPadding(dp(12), dp(10), dp(8), dp(10))
        }
        battRow.addView(
            label("Nên tắt tối ưu pin để treo game lâu không bị Android tắt app", 12.5f, WARN),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        battRow.addView(Button(this).apply {
            text = "Cho phép"; isAllCaps = false; setTextColor(Color.BLACK); textSize = 13f
            background = rounded(WARN, 10); stateListAnimator = null
            setOnClickListener { askBattery() }
        }, LinearLayout.LayoutParams(dp(88), dp(38)))
        stCard.addView(battRow, lp(10))
        root.addView(stCard, lp(12))

        // ---- main button
        startBtn = Button(this).apply {
            isAllCaps = false; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
            stateListAnimator = null
            setOnClickListener { onToggle() }
        }
        root.addView(startBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(18) })

        root.addView(label("Khi đang chia sẻ, thanh thông báo luôn hiện \"Đang chia sẻ màn hình\" kèm nút Dừng.", 12f).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(14), dp(8), 0)
        })

        // ---- auto-wake: stay reachable and start sharing by itself when someone connects
        root.addView(label("TỰ ĐÁNH THỨC TỪ XA", 12f, MUTED, true).apply { letterSpacing = 0.08f; setPadding(dp(4), dp(26), 0, dp(8)) })
        val awCard = card()
        awText = label("Khi bật: có người nhập ID là máy tự mở app và chia sẻ, không cần bấm. Cần quyền Trợ năng + Hiện trên ứng dụng khác + bỏ tối ưu pin.", 12.5f)
        awCard.addView(awText)
        awBtn = Button(this).apply {
            isAllCaps = false; textSize = 15f; setTypeface(typeface, Typeface.BOLD); stateListAnimator = null
            setOnClickListener { toggleAutoWake() }
        }
        awCard.addView(awBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(12) })
        root.addView(awCard, lp(0))

        // ---- this phone can also control other devices (Android / PC)
        root.addView(label("ĐIỀU KHIỂN MÁY KHÁC", 12f, MUTED, true).apply { letterSpacing = 0.08f; setPadding(dp(4), dp(26), 0, dp(8)) })
        root.addView(Button(this).apply {
            text = "Điều khiển máy khác bằng ID"; isAllCaps = false; textSize = 16f; setTextColor(TEXT)
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(CARD2, 16, LINE); stateListAnimator = null
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ControllerActivity::class.java).putExtra("server", server()))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))

        // ---- version / self-update
        root.addView(label("PHIÊN BẢN", 12f, MUTED, true).apply { letterSpacing = 0.08f; setPadding(dp(4), dp(26), 0, dp(8)) })
        val updCard = card()
        val updRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        updText = label("Bản ${Updater.currentName(this)} · đang kiểm tra…", 13.5f, TEXT)
        updRow.addView(updText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        updBtn = Button(this).apply {
            text = "Cập nhật"; isAllCaps = false; textSize = 14f; setTextColor(TEXT)
            background = rounded(CARD2, 12, LINE); stateListAnimator = null
            setOnClickListener { startUpdate(force = updLatest <= Updater.currentCode(this@MainActivity)) }
        }
        updRow.addView(updBtn, LinearLayout.LayoutParams(dp(104), dp(42)))
        updCard.addView(updRow)
        root.addView(updCard, lp(0))

        // ---- macros (auto click) saved from the controller; they run on this phone even when nobody is connected
        root.addView(label("AUTO CLICK / MACRO", 12f, MUTED, true).apply { letterSpacing = 0.08f; setPadding(dp(4), dp(26), 0, dp(8)) })
        macroBox = card()
        root.addView(macroBox, lp(0))

        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; addView(root) }
        setContentView(FrameLayout(this).apply { setBackgroundColor(BG); addView(scroll) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeAutostart()
    }

    /** After a self-update: start sharing again by itself (the accessibility service confirms the dialog). */
    private fun maybeAutostart() {
        val want = intent?.getBooleanExtra("autostart", false) == true || prefs.getBoolean("autostart", false)
        if (!want || ShareService.running) return
        prefs.edit().putBoolean("autostart", false).apply()
        intent?.removeExtra("autostart")
        if (passwordEdit.text.length < 6) return
        Updater.allowAutoConfirm(this, 2 * 60_000L)
        statusText.postDelayed({ if (!ShareService.running) onToggle() }, 800)
    }

    private fun checkUpdate() {
        val server = server()
        Thread {
            val info = Updater.latest(server)
            runOnUiThread {
                val cur = Updater.currentCode(this)
                updLatest = info?.optLong("versionCode", 0) ?: 0
                if (info == null) { updText.text = "Bản ${Updater.currentName(this)} · không kiểm tra được"; return@runOnUiThread }
                if (updLatest > cur) {
                    updText.text = "Có bản mới ${info.optString("versionName")} (đang dùng ${Updater.currentName(this)})"
                    updText.setTextColor(WARN)
                    updBtn.text = "Cập nhật"; updBtn.setTextColor(Color.WHITE); updBtn.background = gradient(12)
                } else {
                    updText.text = "Bản ${Updater.currentName(this)} · mới nhất"
                    updText.setTextColor(TEXT)
                    updBtn.text = "Cài lại"; updBtn.setTextColor(TEXT); updBtn.background = rounded(CARD2, 12, LINE)
                }
            }
        }.start()
    }

    private fun startUpdate(force: Boolean) {
        Updater.update(this, server(), force) { st ->
            runOnUiThread {
                when {
                    st == "checking" -> { updText.text = "Đang kiểm tra…" }
                    st == "latest" -> { updText.text = "Đang dùng bản mới nhất"; toast("Đã là bản mới nhất") }
                    st == "downloading" -> { updText.text = "Đang tải bản mới…" }
                    st == "installing" -> { updText.text = "Đang cài, app sẽ tự mở lại và chia sẻ tiếp…" }
                    st == "need_permission" -> {
                        toast("Bật \"Cho phép từ nguồn này\" rồi bấm Cập nhật lại")
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    }
                    st.startsWith("error:") -> { updText.text = "Lỗi cập nhật: " + st.removePrefix("error:"); updText.setTextColor(BAD) }
                }
                Unit
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkUpdate()
        maybeAutostart()
        statusText.removeCallbacks(ticker)
        ticker.run()
    }

    override fun onPause() {
        statusText.removeCallbacks(ticker)
        super.onPause()
    }

    // ------------------------------------------------------------------ actions

    private fun copyId() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Remote Screen ID", deviceId()))
        // Android 13+ shows its own "copied" confirmation.
        if (Build.VERSION.SDK_INT < 33) toast("Đã copy ID ${deviceId().chunked(3).joinToString(" ")}")
    }

    private fun openA11y() {
        toast("Chọn \"Remote Screen Input\" rồi bật")
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun batteryOk(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    @android.annotation.SuppressLint("BatteryLife")
    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Throwable) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun renderMacros() {
        val svc = ControlAccessibilityService.instance
        val list = ControlAccessibilityService.loadMacros(this)
        val running = svc?.macroRunning()
        val sig = "$list|$running|${svc != null}"
        if (sig == macroSig) return
        macroSig = sig
        macroBox.removeAllViews()
        if (list.length() == 0) {
            macroBox.addView(label("Chưa có macro. Trên iPhone mở bảng điều khiển → Auto click để ghi thao tác, macro sẽ lưu vào máy này.", 12.5f))
            return
        }
        for (k in 0 until list.length()) {
            val m = list.getJSONObject(k)
            val name = m.optString("name")
            val on = name == running
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val loops = m.optInt("loops")
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(name, 15f, TEXT, true))
                addView(label("${m.optJSONArray("steps")?.length() ?: 0} thao tác · " + if (loops == 0) "lặp mãi" else "lặp $loops lần", 12f))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = if (on) "Dừng" else "Chạy"; isAllCaps = false; textSize = 14f
                setTextColor(if (on) BAD else Color.WHITE)
                background = if (on) rounded(0x33FF5D6C, 12, BAD) else gradient(12); stateListAnimator = null
                setOnClickListener {
                    val s = ControlAccessibilityService.instance
                    if (s == null) { openA11y(); return@setOnClickListener }
                    if (on) s.stopMacro() else if (s.runMacro(name)) { toast("Mở game rồi để macro tự chạy"); moveTaskToBack(true) }
                    macroSig = ""; renderMacros()
                }
            }, LinearLayout.LayoutParams(dp(76), dp(40)))
            macroBox.addView(row, lp(if (k == 0) 0 else 12))
        }
    }

    private fun onToggle() {
        if (ShareService.running) {
            startService(Intent(this, ShareService::class.java).setAction(ShareService.ACTION_STOP))
            statusText.postDelayed({ refresh() }, 600)
            return
        }
        val pw = passwordEdit.text.toString()
        if (pw.length < 6) { passwordEdit.error = "Tối thiểu 6 ký tự"; passwordEdit.requestFocus(); return }
        val custom = serverEdit.text.toString().trim()
        if (custom.isNotEmpty() && !Regex("^(wss?|https?)://.+").matches(custom)) {
            toast("Địa chỉ máy chủ phải bắt đầu bằng https:// hoặc wss://")
            return
        }
        prefs.edit().putString("password", pw).putString("server2", custom).apply()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun refresh() {
        val running = ShareService.running
        val color: Int
        val text: String
        if (!running) {
            color = MUTED; text = "Chưa chia sẻ"
        } else when (ShareService.state) {
            ShareService.STATE_READY -> { color = OK; text = "Sẵn sàng, chờ người điều khiển" }
            ShareService.STATE_CONTROLLED -> { color = ACCENT; text = "Đang được điều khiển" }
            else -> { color = WARN; text = "Đang kết nối máy chủ…" }
        }
        (statusDot.background as GradientDrawable).setColor(color)
        statusText.text = text

        a11yRow.visibility = if (ControlAccessibilityService.instance != null) View.GONE else View.VISIBLE
        battRow.visibility = if (batteryOk()) View.GONE else View.VISIBLE
        renderAutoWake()
        renderMacros()

        startBtn.text = if (running) "Dừng chia sẻ" else "Bắt đầu chia sẻ màn hình"
        startBtn.background = if (running) rounded(0x33FF5D6C, 16, BAD) else gradient(16)
        startBtn.setTextColor(if (running) BAD else Color.WHITE)
    }

    private fun autoWakeOn() = prefs.getBoolean("autoShare", false)

    private fun canOverlay() = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)

    private fun renderAutoWake() {
        if (!::awBtn.isInitialized) return
        val on = autoWakeOn()
        awBtn.text = if (on) "Đang bật · bấm để tắt" else "Bật tự đánh thức"
        awBtn.background = if (on) rounded(0x3322C55E, 14, OK) else rounded(CARD2, 14, LINE)
        awBtn.setTextColor(if (on) OK else TEXT)
        if (on) {
            val miss = ArrayList<String>()
            if (ControlAccessibilityService.instance == null) miss.add("Trợ năng")
            if (!canOverlay()) miss.add("Hiện trên ứng dụng khác")
            if (!batteryOk()) miss.add("bỏ tối ưu pin")
            awText.text = if (miss.isEmpty()) "Đang bật. Máy sẽ tự mở app + chia sẻ khi có người nhập ID."
                else "Đang bật nhưng còn thiếu quyền: ${miss.joinToString(", ")}. Bấm nút để cấp nốt."
            awText.setTextColor(if (miss.isEmpty()) OK else WARN)
            if (!ShareService.running && !StandbyService.running) StandbyService.startIfEnabled(this)
        } else {
            awText.text = "Khi bật: có người nhập ID là máy tự mở app và chia sẻ, không cần bấm. Cần quyền Trợ năng + Hiện trên ứng dụng khác + bỏ tối ưu pin."
            awText.setTextColor(MUTED)
        }
    }

    private fun toggleAutoWake() {
        if (autoWakeOn()) {
            // Need all three permissions still? re-request the missing one; only turn OFF when fully set up.
            if (ControlAccessibilityService.instance == null) { openA11y(); return }
            if (!canOverlay()) { requestOverlay(); return }
            if (!batteryOk()) { askBattery(); return }
            prefs.edit().putBoolean("autoShare", false).apply()
            startService(Intent(this, StandbyService::class.java).setAction(StandbyService.ACTION_STOP))
            toast("Đã tắt tự đánh thức")
        } else {
            if (passwordEdit.text.length < 6) { passwordEdit.error = "Nhập mật khẩu trước"; passwordEdit.requestFocus(); return }
            prefs.edit().putString("password", passwordEdit.text.toString()).putBoolean("autoShare", true).apply()
            when {
                ControlAccessibilityService.instance == null -> { toast("Bật quyền Trợ năng cho Remote Screen"); openA11y() }
                !canOverlay() -> { toast("Cho phép Hiện trên ứng dụng khác"); requestOverlay() }
                !batteryOk() -> askBattery()
                else -> toast("Đã bật. Máy luôn sẵn sàng được đánh thức.")
            }
            StandbyService.startIfEnabled(this)
        }
        renderAutoWake()
    }

    private fun requestOverlay() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Throwable) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) } catch (_: Throwable) {}
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
