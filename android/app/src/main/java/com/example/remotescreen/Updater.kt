package com.example.remotescreen

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Self-update: compares the installed version with server/public/version.json (written by CI),
 * downloads remote-screen.apk and installs it with PackageInstaller. After the update the app
 * restarts sharing by itself, and the accessibility service confirms the system dialogs
 * (install / "start recording") for a short time window, so it also works from far away.
 */
object Updater {
    private const val TAG = "Updater"
    const val ACTION_STATUS = "com.example.remotescreen.INSTALL_STATUS"
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    @Volatile var busy = false
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun currentCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }

    fun currentName(ctx: Context): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }

    /** Newest build published on the server, or null. Blocking: call off the main thread. */
    fun latest(server: String): JSONObject? = try {
        http.newCall(Request.Builder().url(server.trimEnd('/') + "/version.json?t=" + System.currentTimeMillis()).build()).execute().use { r ->
            if (!r.isSuccessful) null else JSONObject(r.body!!.string())
        }
    } catch (t: Throwable) { Log.w(TAG, "check", t); null }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Let the accessibility service press "Install" / "Start now" for us during the next minutes. */
    fun allowAutoConfirm(ctx: Context, ms: Long = 4 * 60_000L) =
        prefs(ctx).edit().putLong("autoConfirmUntil", System.currentTimeMillis() + ms).apply()

    fun autoConfirmActive(ctx: Context) = prefs(ctx).getLong("autoConfirmUntil", 0) > System.currentTimeMillis()

    /**
     * Download + install. status() receives short texts for the UI / controller:
     * "checking", "latest", "need_permission", "downloading", "installing", "error:...".
     */
    fun update(ctx: Context, server: String, force: Boolean = false, status: (String) -> Unit) {
        if (busy) { status("busy"); return }
        busy = true
        val app = ctx.applicationContext
        thread(name = "updater") {
            try {
                status("checking")
                val info = latest(server)
                val code = info?.optLong("versionCode", 0) ?: 0
                if (info == null) { status("error:Không đọc được thông tin phiên bản"); return@thread }
                if (!force && code <= currentCode(app)) { status("latest"); return@thread }
                if (!canInstall(app)) { status("need_permission"); return@thread }
                status("downloading")
                val file = File(app.cacheDir, "update.apk")
                http.newCall(Request.Builder().url(server.trimEnd('/') + "/" + info.optString("apk", "remote-screen.apk") + "?v=$code").build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    file.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
                }
                status("installing")
                // After the update: restart sharing automatically and confirm the system dialogs.
                prefs(app).edit().putBoolean("autostart", true).apply()
                allowAutoConfirm(app)
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                val id = installer.createSession(params)
                installer.openSession(id).use { s ->
                    s.openWrite("app.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        s.fsync(out)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(app, 7, Intent(app, UpdateReceiver::class.java).setAction(ACTION_STATUS), flags)
                    s.commit(pi.intentSender)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "update failed", t)
                status("error:" + (t.message ?: t.javaClass.simpleName))
            } finally {
                busy = false
            }
        }
    }
}

/** Installer status + "app was updated" → show the confirm screen / restart sharing. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Updater.ACTION_STATUS -> {
                val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    try { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (t: Throwable) { Log.w("Updater", "confirm", t) }
                } else if (st != PackageInstaller.STATUS_SUCCESS) {
                    Log.w("Updater", "install status $st ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                    ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putBoolean("autostart", false).apply()
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Any update (also one installed by hand from far away) must bring sharing back,
                // otherwise nobody could reach the phone any more.
                val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                if ((p.getString("password", "") ?: "").length >= 6) {
                    p.edit().putBoolean("autostart", true).apply()
                    Updater.allowAutoConfirm(ctx, 3 * 60_000L)
                    try {
                        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("autostart", true))
                    } catch (t: Throwable) { Log.w("Updater", "relaunch", t) }
                }
            }
        }
    }
}
