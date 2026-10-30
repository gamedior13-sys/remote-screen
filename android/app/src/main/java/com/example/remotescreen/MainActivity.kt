package com.example.remotescreen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.random.Random

/** Single screen: server address, password, device ID, accessibility shortcut, start/stop sharing. */
class MainActivity : ComponentActivity() {

    private lateinit var serverEdit: EditText
    private lateinit var passwordEdit: EditText
    private lateinit var statusView: TextView
    private lateinit var startBtn: Button
    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == Activity.RESULT_OK && data != null) {
                val svc = Intent(this, ShareService::class.java)
                    .putExtra(ShareService.EXTRA_RESULT_DATA, data)
                    .putExtra(ShareService.EXTRA_SERVER, serverEdit.text.toString())
                    .putExtra(ShareService.EXTRA_ID, deviceId())
                    .putExtra(ShareService.EXTRA_PASSWORD, passwordEdit.text.toString())
                startForegroundService(svc)
                statusView.postDelayed({ refresh() }, 600)
            } else {
                toast("Bạn đã từ chối quyền chia sẻ màn hình")
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    private fun deviceId(): String =
        prefs.getString("id", null) ?: Random.nextInt(100_000_000, 999_999_999).toString()
            .also { prefs.edit().putString("id", it).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(20))
        }

        root.addView(TextView(this).apply { text = "Remote Screen"; textSize = 24f; setTypeface(typeface, Typeface.BOLD) })

        val idView = TextView(this).apply {
            text = deviceId().chunked(3).joinToString(" ")
            textSize = 34f
            gravity = Gravity.CENTER
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(4))
            setTextIsSelectable(true)
        }
        root.addView(idView)
        root.addView(TextView(this).apply { text = "ID của máy này"; gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(16)) })

        serverEdit = EditText(this).apply {
            hint = "Địa chỉ server, vd: ws://192.168.1.5:8080 hoặc wss://remote.example.com"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("server", ""))
        }
        passwordEdit = EditText(this).apply {
            hint = "Mật khẩu kết nối (tối thiểu 6 ký tự)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("password", ""))
        }
        root.addView(serverEdit)
        root.addView(passwordEdit)

        val a11yBtn = Button(this).apply {
            text = "1. Bật quyền điều khiển (Trợ năng)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        startBtn = Button(this).apply { setOnClickListener { onToggle() } }
        statusView = TextView(this).apply { setPadding(0, dp(16), 0, 0) }
        root.addView(a11yBtn)
        root.addView(startBtn)
        root.addView(statusView)

        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun onToggle() {
        if (ShareService.running) {
            startService(Intent(this, ShareService::class.java).setAction(ShareService.ACTION_STOP))
            statusView.postDelayed({ refresh() }, 600)
            return
        }
        val server = serverEdit.text.toString().trim()
        val pw = passwordEdit.text.toString()
        if (!server.startsWith("ws://") && !server.startsWith("wss://") &&
            !server.startsWith("http://") && !server.startsWith("https://")
        ) return toast("Địa chỉ server phải bắt đầu bằng ws://, wss://, http:// hoặc https://")
        if (pw.length < 6) return toast("Mật khẩu tối thiểu 6 ký tự")
        prefs.edit().putString("server", server).putString("password", pw).apply()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun refresh() {
        val running = ShareService.running
        startBtn.text = if (running) "Dừng chia sẻ" else "2. Bắt đầu chia sẻ màn hình"
        val a11y = ControlAccessibilityService.instance != null
        statusView.text = buildString {
            append(if (running) "● Đang chia sẻ — nhập ID + mật khẩu trên máy điều khiển\n" else "○ Chưa chia sẻ\n")
            append(if (a11y) "● Quyền điều khiển: đã bật" else "○ Quyền điều khiển: CHƯA bật (chỉ xem được, không bấm được)")
        }
        statusView.visibility = View.VISIBLE
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
