package com.example.remotescreen

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Cấu hình Firebase Cloud Messaging — điền từ google-services.json (Firebase console).
 * Để trống = FCM tắt, app vẫn chạy bình thường (đánh thức qua socket standby).
 */
object FcmConfig {
    const val PROJECT_ID = "remote-screen-209c3"
    const val APPLICATION_ID = "1:924732649058:android:78acbdecf9035ada9ceb3b"
    const val API_KEY = "AIzaSyAUXnRrLqGDdl_r1JA4JJ8nFVGHYJ9OV7s"
    const val SENDER_ID = "924732649058"
    val enabled: Boolean get() = PROJECT_ID.isNotEmpty() && APPLICATION_ID.isNotEmpty() && API_KEY.isNotEmpty()
}

/**
 * Token FCM: lưu / gửi lên server / chủ động xin.
 * Android chỉ sinh token khi có thành phần hỏi tới, nên phải hỏi ngay khi tiến trình sống —
 * không thể chờ onNewToken (có máy không bao giờ nhận).
 */
internal object FcmPush {
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).build()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun store(ctx: Context, token: String) {
        prefs(ctx).edit().putString("fcm", token).apply()
    }

    /** Gửi token kèm id + mật khẩu để server biết đánh thức máy nào. */
    fun report(ctx: Context, token: String) {
        thread(name = "fcm-token") {
            try {
                val p = prefs(ctx)
                val server = (p.getString("server2", "") ?: "").trim().trimEnd('/').ifEmpty { MainActivity.DEFAULT_SERVER }
                val id = p.getString("id", "") ?: ""
                val pw = p.getString("password", "") ?: ""
                if (id.isBlank() || pw.length < 6) return@thread
                val body = JSONObject().put("id", id).put("password", pw).put("fcm", token).toString()
                    .toRequestBody("application/json".toMediaType())
                http.newCall(Request.Builder().url("$server/fcm").post(body).build()).execute().close()
            } catch (_: Throwable) {}
        }
    }

    /** Xin token ngay (async) rồi lưu + gửi lên server. */
    fun request(ctx: Context) {
        if (!FcmConfig.enabled) return
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
                val token = if (t.isSuccessful) t.result else null
                if (!token.isNullOrBlank()) {
                    store(ctx, token)
                    report(ctx, token)
                }
            }
        } catch (_: Throwable) {}
    }
}

/** Khởi tạo Firebase ngay khi tiến trình app bắt đầu (kể cả khi app đã bị Android giết). */
class FcmInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        return try {
            val ctx = context
            if (ctx != null && FcmConfig.enabled) {
                if (FirebaseApp.getApps(ctx).isEmpty()) {
                    FirebaseApp.initializeApp(
                        ctx,
                        FirebaseOptions.Builder()
                            .setProjectId(FcmConfig.PROJECT_ID)
                            .setApplicationId(FcmConfig.APPLICATION_ID)
                            .setApiKey(FcmConfig.API_KEY)
                            .setGcmSenderId(FcmConfig.SENDER_ID)
                            .build()
                    )
                }
                FcmPush.request(ctx)
            }
            true
        } catch (t: Throwable) { false }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}

/** Nhận thông báo đẩy FCM: app bị Android giết ngầm vẫn được gọi dậy qua đây. */
class WakeMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        FcmPush.store(this, token)
        FcmPush.report(this, token)
    }

    override fun onMessageReceived(m: RemoteMessage) {
        if (m.data["action"] == "wake") StandbyService.startIfEnabled(this)
    }
}