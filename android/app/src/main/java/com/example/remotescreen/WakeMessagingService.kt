package com.example.remotescreen

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
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
    const val PROJECT_ID = ""
    const val APPLICATION_ID = ""
    const val API_KEY = ""
    const val SENDER_ID = ""
    val enabled: Boolean get() = PROJECT_ID.isNotEmpty() && APPLICATION_ID.isNotEmpty() && API_KEY.isNotEmpty()
}

/** Khởi tạo Firebase ngay khi tiến trình app bắt đầu (kể cả khi app đã bị Android giết). */
class FcmInitProvider : ContentProvider() {
    override fun onCreate(): Boolean = try {
        val ctx = context ?: return true
        if (FcmConfig.enabled && FirebaseApp.getApps(ctx).isEmpty()) {
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
        true
    } catch (t: Throwable) { false }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}

/** Nhận thông báo đẩy FCM: app bị Android giết ngầm vẫn được gọi dậy qua đây. */
class WakeMessagingService : FirebaseMessagingService() {

    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).build()
    private fun prefs() = getSharedPreferences("cfg", MODE_PRIVATE)

    override fun onNewToken(token: String) {
        prefs().edit().putString("fcm", token).apply()
        sendToServer(token)
    }

    override fun onMessageReceived(m: RemoteMessage) {
        if (m.data["action"] == "wake") StandbyService.startIfEnabled(this)
    }

    private fun sendToServer(token: String) {
        thread(name = "fcm-token") {
            try {
                val p = prefs()
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
}
