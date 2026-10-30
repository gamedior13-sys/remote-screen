package com.example.remotescreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
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
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
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

        @Volatile
        var running = false
            private set
    }

    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
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
    private fun startCapture(permissionData: Intent) {
        val f = factory!!
        val metrics = resources.displayMetrics
        val longSide = maxOf(metrics.widthPixels, metrics.heightPixels).toFloat()
        val scale = minOf(1f, MAX_LONG_SIDE / longSide)
        val w = (metrics.widthPixels * scale).toInt() and 1.inv()
        val h = (metrics.heightPixels * scale).toInt() and 1.inv()

        helper = SurfaceTextureHelper.create("capture", eglBase!!.eglBaseContext)
        videoSource = f.createVideoSource(true /* isScreencast */)
        capturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
            override fun onStop() { shutdown() }
        })
        capturer!!.initialize(helper, applicationContext, videoSource!!.capturerObserver)
        capturer!!.startCapture(w, h, FPS)
        videoTrack = f.createVideoTrack("screen0", videoSource)
    }

    // ---------------------------------------------------------------- signaling

    private fun connectSignaling() {
        if (stopping) return
        ws = http.newWebSocket(Request.Builder().url(wsUrl()).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "register").put("id", deviceId).put("password", password).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try { handleSignaling(JSONObject(text)) } catch (t: Throwable) { Log.e(TAG, "signal", t) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure", t)
                closePeer()
                retryLater()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
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
            "registered" -> Log.i(TAG, "registered as ${m.getString("id")}")
            "error" -> Log.w(TAG, "server error: ${m.optString("message")}")
            "peer-joined" -> startPeer()
            "peer-left" -> closePeer()
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
                override fun onStateChange() {}
                override fun onMessage(buffer: DataChannel.Buffer) {
                    val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                    try { handleControl(JSONObject(String(bytes, Charsets.UTF_8))) } catch (t: Throwable) { Log.w(TAG, "ctrl", t) }
                }
            })
        }

        val sender = peer.addTrack(videoTrack, listOf("screen"))
        // Favour smooth, low-latency frames over sharpness when the link is congested.
        sender.parameters.let { p ->
            p.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            p.encodings.forEach { e -> e.maxBitrateBps = MAX_BITRATE_BPS; e.maxFramerate = FPS }
            sender.setParameters(p)
        }

        peer.createOffer(object : SimpleSdp() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peer.setLocalDescription(SimpleSdp(), sdp)
                sendSignal(JSONObject().put("sdp", JSONObject().put("type", sdp.type.canonicalForm()).put("sdp", sdp.description)))
            }
        }, MediaConstraints())
    }

    private fun closePeer() {
        try { dc?.close(); dc?.dispose() } catch (_: Throwable) {}
        try { pc?.close(); pc?.dispose() } catch (_: Throwable) {}
        dc = null
        pc = null
    }

    private fun handleControl(o: JSONObject) {
        val svc = ControlAccessibilityService.instance ?: return
        when (o.getString("t")) {
            "tap" -> svc.tap(o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.optLong("d", 50))
            "swipe" -> svc.swipe(
                o.getDouble("x1").toFloat(), o.getDouble("y1").toFloat(),
                o.getDouble("x2").toFloat(), o.getDouble("y2").toFloat(), o.optLong("d", 200)
            )
            "key" -> svc.key(o.getString("k"))
            "text" -> svc.text(o.getString("s"))
        }
    }

    // ---------------------------------------------------------------- teardown

    private fun shutdown() {
        if (stopping) { stopSelf(); return }
        stopping = true
        running = false
        try { ws?.close(1000, "bye") } catch (_: Throwable) {}
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
