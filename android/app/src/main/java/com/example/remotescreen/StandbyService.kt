package com.example.remotescreen

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Lightweight "standby" presence: keeps ONE signaling socket registered (standby=true) so the server
 * can wake this device on demand — without capturing the screen. When a controller asks for this ID,
 * the server sends {type:"wake"}; we then turn the screen on and launch MainActivity in auto-start
 * mode, which requests screen capture and lets the accessibility service confirm the system dialog.
 * It yields to ShareService: whenever real sharing is running, standby stops itself.
 */
class StandbyService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.remotescreen.STANDBY_STOP"
        const val ACTION_WATCHDOG = "com.example.remotescreen.STANDBY_WATCHDOG"
        private const val TAG = "StandbyService"
        private const val CHANNEL = "standby"
        private const val CHANNEL_WAKE = "wake"
        private const val NOTIF_WAKE = 9

        @Volatile var running = false
            private set

        private const val NOTIF_FULLSCREEN = 9

        /** Thông báo "toàn màn hình": cách duy nhất để app ở nền bật màn hình/mở app khi máy đang ngủ. */
        fun showFullScreenWake(ctx: Context) {
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= 26) {
                    nm.createNotificationChannel(
                        NotificationChannel("wake", "Đánh thức máy", NotificationManager.IMPORTANCE_HIGH).apply {
                            setShowBadge(false)
                            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        }
                    )
                }
                val open = PendingIntent.getActivity(
                    ctx, 11,
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        .putExtra("autostart", true),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val n = Notification.Builder(ctx, "wake")
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle("Remote Screen: có người muốn kết nối")
                    .setContentText("Đang mở màn hình để chia sẻ…")
                    .setCategory(Notification.CATEGORY_CALL)
                    .setPriority(Notification.PRIORITY_MAX)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setContentIntent(open)
                    .setFullScreenIntent(open, true)
                    .setAutoCancel(true)
                    .setTimeoutAfter(60_000)
                    .build()
                nm.notify(NOTIF_FULLSCREEN, n)
            } catch (t: Throwable) { Log.w(TAG, "fullscreen notify", t) }
        }

        /** Start standby if the owner enabled auto-wake and a password is set. */
        fun startIfEnabled(ctx: Context) {
            val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            // "Tự đánh thức" luôn luôn bật: lần đầu chạy bản mới sẽ tự chuyển cờ cũ sang BẬT.
            if (!p.getBoolean("autoWakeMigrated", false)) {
                p.edit().putBoolean("autoShare", true).putBoolean("autoWakeMigrated", true).apply()
            }
            if (!p.getBoolean("autoShare", true)) return
            if ((p.getString("password", "") ?: "").length < 6) return
            if (ShareService.running) return
            try { ctx.startForegroundService(Intent(ctx, StandbyService::class.java)) } catch (t: Throwable) { Log.w(TAG, "start", t) }
        }
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    private var stopping = false
    private var lastWake = 0L

    private fun prefs() = getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun server() = (prefs().getString("server2", "") ?: "").trim().trimEnd('/').ifEmpty { MainActivity.DEFAULT_SERVER }
    private fun deviceId() = prefs().getString("id", "") ?: ""
    private fun password() = prefs().getString("password", "") ?: ""
    private fun wsUrl(): String {
        val base = server().replaceFirst("http://", "ws://").replaceFirst("https://", "wss://")
        return "$base/ws"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        val fromWatchdog = intent?.action == ACTION_WATCHDOG
        if (running) {
            // Đang sống: nếu vòng tự kiểm tra vừa nổ, hẹn vòng kế tiếp theo số phút hiện tại.
            if (fromWatchdog) scheduleWatchdog()
            return START_STICKY
        }
        // Nothing to do if sharing is already live, or the owner turned auto-wake off.
        if (ShareService.running || !prefs().getBoolean("autoShare", true) || password().length < 6 || deviceId().isBlank()) {
            stopSelf(); return START_NOT_STICKY
        }
        startForegroundNotification()
        running = true
        connect()
        scheduleWatchdog()
        return START_STICKY
    }

    /** Số phút tự kiểm tra do chủ máy khai báo trong app (mặc định 10, nhận 1–240). */
    private fun watchdogMin(): Int = prefs().getInt("watchdogMin", 10).coerceIn(1, 240)

    /** Hẹn một vòng tự kiểm tra: nổ xong tự lên lịch vòng kế tiếp theo số phút hiện tại. */
    private fun scheduleWatchdog() {
        try {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            val op = PendingIntent.getService(
                this, 12, Intent(this, StandbyService::class.java).setAction(ACTION_WATCHDOG),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val show = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val ms = watchdogMin().toLong() * 60_000L
            // setAlarmClock: loại báo thức được MIỄN khởi động service nền trên Android 12+.
            am.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + ms, show), op)
        } catch (t: Throwable) { Log.w(TAG, "watchdog", t) }
    }

    private fun cancelWatchdog() {
        try {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            am.cancel(PendingIntent.getService(
                this, 12, Intent(this, StandbyService::class.java).setAction(ACTION_WATCHDOG),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ))
        } catch (_: Throwable) {}
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Vuốt tắt app khỏi đa nhiệm: hẹn 2 giây sau tự chạy lại chế độ chờ (setAlarmClock
        // là loại báo thức được phép mở service nền trên Android 12+).
        try {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            val op = PendingIntent.getService(
                this, 11, Intent(this, StandbyService::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val show = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 2000, show), op)
        } catch (t: Throwable) { Log.w(TAG, "taskRemoved", t) }
        super.onTaskRemoved(rootIntent)
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sẵn sàng điều khiển", NotificationManager.IMPORTANCE_MIN))
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, StandbyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Remote Screen: sẵn sàng")
            .setContentText("Chờ lệnh đánh thức — ID ${deviceId()}")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Tắt sẵn sàng", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2, n)
        }
    }

    private fun connect() {
        if (stopping) return
        if (ShareService.running) { shutdown(); return }   // real sharing owns the ID now
        ws = http.newWebSocket(Request.Builder().url(wsUrl()).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (ShareService.running) { try { webSocket.close(1000, "sharing") } catch (_: Throwable) {}; return }
                val reg = JSONObject().put("type", "register").put("id", deviceId()).put("password", password()).put("standby", true)
                val fcm = prefs().getString("fcm", "") ?: ""
                if (fcm.isNotEmpty()) reg.put("fcm", fcm)
                webSocket.send(reg.toString())
                thread(name = "standby-ka") {
                    while (!stopping) {
                        try { Thread.sleep(60_000) } catch (_: InterruptedException) { break }
                        if (stopping || ws !== webSocket) break
                        try { webSocket.send("{\"type\":\"ka\"}") } catch (_: Throwable) { break }
                    }
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    if (JSONObject(text).optString("type") == "wake") doWake()
                } catch (t: Throwable) { Log.w(TAG, "msg", t) }
            }
            // Cờ riêng cho từng socket: tránh cảnh onFailure chạy trước khi ws được gán xong
            // (mất mạng thì OkHttp báo lỗi rất nhanh) làm mất luôn lần nối lại.
            private val done = java.util.concurrent.atomic.AtomicBoolean(false)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (!stopping && done.compareAndSet(false, true)) retryLater() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (!stopping && done.compareAndSet(false, true)) retryLater() }
        })
    }

    private fun retryLater() {
        if (stopping) return
        thread(name = "standby-retry") {
            Thread.sleep(5000)
            if (stopping) return@thread
            if (ShareService.running) { shutdown(); return@thread }
            connect()
        }
    }

    /** A controller asked for this device: wake the screen and launch the capture flow. */
    private fun doWake() {
        val now = System.currentTimeMillis()
        if (ShareService.running || now - lastWake < 8000) return
        lastWake = now
        prefs().edit().putBoolean("autostart", true).apply()
        Updater.allowAutoConfirm(this, 3 * 60_000L)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        // Turn the screen on (cannot bypass a PIN/pattern lock — Android forbids that).
        try {
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "remotescreen:wake"
            )
            wl.acquire(60_000)
        } catch (t: Throwable) { Log.w(TAG, "wake lock", t) }
        // Đăng thông báo toàn màn hình TRƯỚC: hệ thống mới có cớ bật màn hình lên.
        showWakeNotification()
        val launched = try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra("autostart", true)
            )
            true
        } catch (t: Throwable) { Log.w(TAG, "launch", t); false }
        saveWakeDiag(launched, pm)
    }

    private fun wakeChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_WAKE, "Đánh thức máy", NotificationManager.IMPORTANCE_HIGH).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private fun showWakeNotification() = showFullScreenWake(applicationContext)

    /** Ghi lại vì sao lần đánh thức vừa rồi thành/bại — hiện trong app để debug từ xa. */
    private fun saveWakeDiag(launched: Boolean, pm: PowerManager) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val overlay = android.provider.Settings.canDrawOverlays(this)
            val fsi = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
            val hhmmss = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val diag = hhmmss +
                " · " + (if (pm.isInteractive) "màn hình đang bật" else "màn hình tắt") +
                " · overlay=" + (if (overlay) "OK" else "THIẾU") +
                " · toàn màn hình=" + (if (fsi) "OK" else "THIẾU") +
                " · mở app=" + (if (launched) "OK" else "BỊ CHẶN")
            prefs().edit().putString("wakeDiag", diag).apply()
            FcmPush.reportDiag(applicationContext, diag)
        } catch (t: Throwable) { Log.w(TAG, "diag", t) }
    }

    private fun shutdown() {
        if (stopping) { stopSelf(); return }
        stopping = true
        running = false
        try { ws?.close(1000, "bye") } catch (_: Throwable) {}
        try { ws?.cancel() } catch (_: Throwable) {}
        ws = null
        cancelWatchdog()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopping) { stopping = true; running = false; try { ws?.cancel() } catch (_: Throwable) {} }
        super.onDestroy()
    }
}
