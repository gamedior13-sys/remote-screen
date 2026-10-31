package com.example.remotescreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Foreground service: captures the screen, keeps a signaling connection to the server,
 * and for every controller that joins builds a WebRTC peer connection
 * (video track + "ctrl" data channel for input).
 */
class ShareService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.remotescreen.STOP"
        const val EXTRA_RESULT_DATA = "data"
        const val EXTRA_SERVER = "server"
        const val EXTRA_ID = "id"
        const val EXTRA_PASSWORD = "password"
        private const val TAG = "ShareService"
        private const val CHANNEL = "share"
        private const val MAX_LONG_SIDE = 1280
        private const val FPS = 30
        private const val MAX_BITRATE_BPS = 4_000_000

        const val STATE_CONNECTING = 0
        const val STATE_READY = 1
        const val STATE_CONTROLLED = 2

        @Volatile
        var running = false
            private set

        /** Connection state shown in the app UI. */
        @Volatile
        var state = STATE_CONNECTING
            private set
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var pc: PeerConnection? = null
    private var dc: DataChannel? = null
    private var iceServers: List<PeerConnection.IceServer> = emptyList()
    private var serverUrl = ""
    private var deviceId = ""
    private var password = ""
    private var stopping = false

    // Current capture / encoding settings (changed by the controller's quality menu).
    private var sender: RtpSender? = null
    private var fps = FPS
    private var longSideLimit = MAX_LONG_SIDE
    private var maxBitrate = MAX_BITRATE_BPS
    private var capW = 0
    private var capH = 0
    private var displayListener: DisplayManager.DisplayListener? = null

    // Long idle-game sessions: keep CPU, Wi-Fi and (dimmed) screen awake, react to network changes.
    private val mainH = Handler(Looper.getMainLooper())
    private var cpuLock: PowerManager.WakeLock? = null
    private var screenLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        @Suppress("DEPRECATION")
        val permissionData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        serverUrl = intent.getStringExtra(EXTRA_SERVER).orEmpty().trim().trimEnd('/')
        deviceId = intent.getStringExtra(EXTRA_ID).orEmpty()
        password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        if (permissionData == null || serverUrl.isEmpty()) { shutdown(); return START_NOT_STICKY }

        // The foreground service (type mediaProjection) must be running BEFORE the projection is created.
        startForegroundNotification()
        running = true
        state = STATE_CONNECTING
        acquireLocks()

        thread(name = "init") {
            try {
                fetchIceServers()
                initWebRtc()
                startCapture(permissionData)
                connectSignaling()
            } catch (t: Throwable) {
                Log.e(TAG, "startup failed", t)
                shutdown()
            }
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- notification

    private fun startForegroundNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Đang chia sẻ màn hình")
            .setContentText("ID $deviceId — bấm Dừng để ngắt")
            .setOngoing(true)
            .addAction(0, "Dừng", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    // ---------------------------------------------------------------- stay alive

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "remotescreen:cpu").apply { setReferenceCounted(false); acquire() }
            // The screen must stay on, otherwise capture goes black and most games pause. Dim to save battery.
            if (getSharedPreferences("game", MODE_PRIVATE).getBoolean("keepScreen", true)) {
                screenLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "remotescreen:screen")
                    .apply { setReferenceCounted(false); acquire() }
            }
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "remotescreen:wifi").apply { setReferenceCounted(false); acquire() }
        } catch (t: Throwable) { Log.w(TAG, "locks", t) }
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            netCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Network came back (or switched Wi-Fi <-> 4G): reconnect at once instead of waiting for timeouts.
                    if (!stopping && state == STATE_CONNECTING) { try { ws?.cancel() } catch (_: Throwable) {} }
                }
            }.also { cm.registerDefaultNetworkCallback(it) }
        } catch (t: Throwable) { Log.w(TAG, "net cb", t) }
    }

    private fun releaseLocks() {
        try { cpuLock?.release() } catch (_: Throwable) {}
        try { screenLock?.release() } catch (_: Throwable) {}
        try { wifiLock?.release() } catch (_: Throwable) {}
        try { netCallback?.let { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } } catch (_: Throwable) {}
        cpuLock = null; screenLock = null; wifiLock = null; netCallback = null
    }

    private fun deviceStats(): JSONObject {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val plugged = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return JSONObject().put("t", "stat").put("batt", if (lvl >= 0) lvl * 100 / maxOf(scale, 1) else -1)
            .put("chg", plugged).put("temp", temp)
    }

    private val statTick = object : Runnable {
        override fun run() {
            if (dc == null || stopping) return
            thread { sendToController(deviceStats()) }
            mainH.postDelayed(this, 10_000)
        }
    }

    // ---------------------------------------------------------------- setup

    private fun httpBase() = serverUrl.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")
    private fun wsUrl(): String {
        val base = serverUrl.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://")
        return "$base/ws"
    }

    private fun fetchIceServers() {
        val list = mutableListOf<PeerConnection.IceServer>()
        try {
            http.newCall(Request.Builder().url("${httpBase()}/config").build()).execute().use { r ->
                val arr: JSONArray = JSONObject(r.body!!.string()).getJSONArray("iceServers")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val urls = o.get("urls").let { u ->
                        if (u is JSONArray) List(u.length()) { u.getString(it) } else listOf(u.toString())
                    }
                    val b = PeerConnection.IceServer.builder(urls)
                    if (o.has("username")) b.setUsername(o.getString("username"))
                    if (o.has("credential")) b.setPassword(o.getString("credential"))
                    list.add(b.createIceServer())
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "using default STUN", t)
            list.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }
        iceServers = list
    }

    private fun initWebRtc() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
        )
        val egl = EglBase.create().also { eglBase = it }
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    /**
     * The capture runs for the whole service lifetime (not per controller) because on Android 14+
     * the permission result can create only ONE MediaProjection.
     */
    /** Real size of the screen in its CURRENT rotation. */
    private fun realScreen(): Pair<Int, Int> {
        val d = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        d.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    private fun captureSize(): Pair<Int, Int> {
        val (sw, sh) = realScreen()
        val scale = minOf(1f, MAX_LONG_SIDE / maxOf(sw, sh).toFloat())
        return ((sw * scale).toInt() and 1.inv()) to ((sh * scale).toInt() and 1.inv())
    }

    /** Follow screen rotation / quality changes so the video always matches the real screen. */
    @Synchronized
    private fun updateCapture() {
        val c = capturer ?: return
        val (w, h) = captureSize()
        if (w == capW && h == capH) return
        if (resizeCapture(c, w, h)) { capW = w; capH = h; applyOutputFormat() }
    }

    /**
     * Resize the running screen capture in place (VirtualDisplay.resize).
     * ScreenCapturerAndroid.changeCaptureFormat() would re-create the virtual display, which
     * Android 14+ forbids for one MediaProjection and leaves the stream black.
     */
    private fun resizeCapture(c: ScreenCapturerAndroid, w: Int, h: Int): Boolean = try {
        val cls = ScreenCapturerAndroid::class.java
        fun field(n: String) = cls.getDeclaredField(n).apply { isAccessible = true }
        val vd = field("virtualDisplay").get(c) as android.hardware.display.VirtualDisplay
        val st = field("surfaceTextureHelper").get(c) as SurfaceTextureHelper
        field("width").setInt(c, w)
        field("height").setInt(c, h)
        val dpi = resources.displayMetrics.densityDpi
        st.handler.post {
            try {
                st.setTextureSize(w, h)
                vd.resize(w, h, dpi)
            } catch (t: Throwable) { Log.w(TAG, "resize failed", t) }
        }
        true
    } catch (t: Throwable) {
        Log.w(TAG, "resize not supported", t)
        false
    }

    /** Scale / frame-rate cap applied to the outgoing video (does not touch the capture itself). */
    private fun applyOutputFormat() {
        val src = videoSource ?: return
        if (capW <= 0 || capH <= 0) return
        val scale = minOf(1f, longSideLimit / maxOf(capW, capH).toFloat())
        val w = (capW * scale).toInt() and 1.inv()
        val h = (capH * scale).toInt() and 1.inv()
        try { src.adaptOutputFormat(maxOf(w, h), minOf(w, h), fps) } catch (t: Throwable) { Log.w(TAG, "adapt", t) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateCapture()
    }

    private fun startCapture(permissionData: Intent) {
        val f = factory!!
        val (w, h) = captureSize()
        capW = w; capH = h

        helper = SurfaceTextureHelper.create("capture", eglBase!!.eglBaseContext)
        videoSource = f.createVideoSource(true /* isScreencast */)
        capturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
            override fun onStop() { shutdown() }
        })
        capturer!!.initialize(helper, applicationContext, videoSource!!.capturerObserver)
        capturer!!.startCapture(w, h, FPS)
        videoTrack = f.createVideoTrack("screen0", videoSource)

        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) updateCapture() }
        }.also { dm.registerDisplayListener(it, Handler(Looper.getMainLooper())) }
    }

    private fun applySenderParams() {
        val s = sender ?: return
        try {
            val p = s.parameters
            // Favour smooth, low-latency frames over sharpness when the link is congested.
            p.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            p.encodings.forEach { e -> e.maxBitrateBps = maxBitrate; e.maxFramerate = fps }
            s.setParameters(p)
        } catch (t: Throwable) { Log.w(TAG, "sender params", t) }
    }

    private fun applyQuality(newFps: Int, longSide: Int, bitrate: Int) {
        fps = newFps.coerceIn(5, 60)
        longSideLimit = longSide.coerceIn(480, 1920)
        maxBitrate = bitrate.coerceIn(200_000, 10_000_000)
        applyOutputFormat()
        applySenderParams()
    }

    // ---------------------------------------------------------------- signaling

    private fun connectSignaling() {
        if (stopping) return
        ws = http.newWebSocket(Request.Builder().url(wsUrl()).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "register").put("id", deviceId).put("password", password).toString())
                // App-level keep-alive: free cloud hosts stay awake only while they receive messages.
                thread(name = "keepalive") {
                    while (!stopping) {
                        try { Thread.sleep(60_000) } catch (_: InterruptedException) { break }
                        if (stopping || ws !== webSocket) break
                        webSocket.send("{\"type\":\"ka\"}")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try { handleSignaling(JSONObject(text)) } catch (t: Throwable) { Log.e(TAG, "signal", t) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure", t)
                state = STATE_CONNECTING
                closePeer()
                retryLater()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                state = STATE_CONNECTING
                closePeer()
                retryLater()
            }
        })
    }

    private fun retryLater() {
        if (stopping) return
        thread(name = "retry") {
            Thread.sleep(3000)
            if (!stopping) connectSignaling()
        }
    }

    private fun sendSignal(data: JSONObject) {
        ws?.send(JSONObject().put("type", "signal").put("data", data).toString())
    }

    private fun handleSignaling(m: JSONObject) {
        when (m.getString("type")) {
            "registered" -> { Log.i(TAG, "registered as ${m.getString("id")}"); state = STATE_READY }
            "error" -> Log.w(TAG, "server error: ${m.optString("message")}")
            "peer-joined" -> { state = STATE_CONTROLLED; startPeer() }
            "peer-left" -> { state = STATE_READY; closePeer() }
            "signal" -> {
                val d = m.getJSONObject("data")
                val p = pc ?: return
                if (d.has("sdp")) {
                    val s = d.getJSONObject("sdp")
                    p.setRemoteDescription(
                        SimpleSdp(),
                        SessionDescription(SessionDescription.Type.fromCanonicalForm(s.getString("type")), s.getString("sdp"))
                    )
                } else if (d.has("candidate")) {
                    val c = d.getJSONObject("candidate")
                    p.addIceCandidate(IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.getString("candidate")))
                }
            }
        }
    }

    // ---------------------------------------------------------------- peer connection

    private fun startPeer() {
        closePeer()
        fetchIceServers()  // TURN credentials may be short-lived: refresh them for every session
        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val peer = factory!!.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                sendSignal(
                    JSONObject().put(
                        "candidate",
                        JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)
                    )
                )
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) { Log.i(TAG, "pc state $s") }
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
        }) ?: return
        pc = peer

        dc = peer.createDataChannel("ctrl", DataChannel.Init()).also { ch ->
            ch.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(prev: Long) {}
                override fun onStateChange() {
                    if (ch.state() == DataChannel.State.OPEN) {
                        ControlAccessibilityService.imeListener = { on, text, pw ->
                            sendToController(JSONObject().put("t", "ime").put("on", on).put("text", text).put("pw", pw))
                        }
                        ControlAccessibilityService.eventListener = { o -> sendToController(o) }
                        sendToController(JSONObject().put("t", "hello").put("caps", JSONArray().put("mt").put("macro").put("stat").put("watch"))
                            .put("a11y", ControlAccessibilityService.instance != null))
                        mainH.post {
                            ControlAccessibilityService.instance?.let { it.reportFocus(true); it.emitState() }
                            mainH.removeCallbacks(statTick); statTick.run()
                        }
                    }
                }
                override fun onMessage(buffer: DataChannel.Buffer) {
                    val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                    try { handleControl(JSONObject(String(bytes, Charsets.UTF_8))) } catch (t: Throwable) { Log.w(TAG, "ctrl", t) }
                }
            })
        }

        sender = peer.addTrack(videoTrack, listOf("screen"))
        applySenderParams()

        peer.createOffer(object : SimpleSdp() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peer.setLocalDescription(SimpleSdp(), sdp)
                sendSignal(JSONObject().put("sdp", JSONObject().put("type", sdp.type.canonicalForm()).put("sdp", sdp.description)))
            }
        }, MediaConstraints())
    }

    private fun sendToController(o: JSONObject) {
        val ch = dc ?: return
        try {
            if (ch.state() == DataChannel.State.OPEN) ch.send(DataChannel.Buffer(ByteBuffer.wrap(o.toString().toByteArray(Charsets.UTF_8)), false))
        } catch (t: Throwable) { Log.w(TAG, "dc send", t) }
    }

    private fun closePeer() {
        ControlAccessibilityService.imeListener = null
        ControlAccessibilityService.eventListener = null
        mainH.removeCallbacks(statTick)
        ControlAccessibilityService.instance?.touchCancel()
        try { dc?.close(); dc?.dispose() } catch (_: Throwable) {}
        try { pc?.close(); pc?.dispose() } catch (_: Throwable) {}
        dc = null
        pc = null
        sender = null
    }

    private fun handleControl(o: JSONObject) {
        if (o.optString("t") == "quality") {
            applyQuality(o.optInt("fps", fps), o.optInt("long", longSideLimit), o.optInt("br", maxBitrate))
            return
        }
        val svc = ControlAccessibilityService.instance ?: return
        val f = { k: String -> o.getDouble(k).toFloat() }
        when (o.getString("t")) {
            // realtime multi-touch (game mode)
            "td" -> svc.touchDown(o.getInt("i"), f("x"), f("y"))
            "tm" -> { val p = o.getJSONArray("p"); for (k in 0 until p.length()) { val a = p.getJSONArray(k); svc.touchMove(a.getInt(0), a.getDouble(1).toFloat(), a.getDouble(2).toFloat()) } }
            "tu" -> svc.touchUp(o.getInt("i"))
            "tc" -> svc.touchCancel()
            // macros, game watch
            "macro_save" -> mainH.post { svc.saveMacro(o.getJSONObject("m")) }
            "macro_del" -> mainH.post { svc.deleteMacro(o.getString("name")) }
            "macro_run" -> mainH.post { svc.runMacro(o.getString("name")) }
            "macro_stop" -> mainH.post { svc.stopMacro() }
            "watch" -> mainH.post { svc.setWatch(o.optBoolean("on"), o.optBoolean("relaunch")) }
            "open_game" -> mainH.post { svc.openWatched() }
            "game_state" -> mainH.post { svc.emitState() }
            "keep_screen" -> { getSharedPreferences("game", MODE_PRIVATE).edit().putBoolean("keepScreen", o.optBoolean("on", true)).apply() }
            "tap" -> svc.tap(o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.optLong("d", 50))
            "swipe" -> svc.swipe(
                o.getDouble("x1").toFloat(), o.getDouble("y1").toFloat(),
                o.getDouble("x2").toFloat(), o.getDouble("y2").toFloat(), o.optLong("d", 200)
            )
            "key" -> svc.key(o.getString("k"))
            "text" -> svc.text(o.getString("s"))
            "settext" -> svc.setText(o.getString("s"))
        }
    }

    // ---------------------------------------------------------------- teardown

    private fun shutdown() {
        if (stopping) { stopSelf(); return }
        stopping = true
        running = false
        releaseLocks()
        state = STATE_CONNECTING
        try { ws?.close(1000, "bye") } catch (_: Throwable) {}
        try {
            displayListener?.let { (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it) }
        } catch (_: Throwable) {}
        displayListener = null
        closePeer()
        try { capturer?.stopCapture() } catch (_: Throwable) {}
        try { capturer?.dispose() } catch (_: Throwable) {}
        try { videoTrack?.dispose() } catch (_: Throwable) {}
        try { videoSource?.dispose() } catch (_: Throwable) {}
        try { helper?.dispose() } catch (_: Throwable) {}
        try { factory?.dispose() } catch (_: Throwable) {}
        try { eglBase?.release() } catch (_: Throwable) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopping) shutdown()
        super.onDestroy()
    }

    /** SdpObserver with empty defaults. */
    open class SimpleSdp : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { Log.w(TAG, "sdp create failed: $error") }
        override fun onSetFailure(error: String?) { Log.w(TAG, "sdp set failed: $error") }
    }
}
