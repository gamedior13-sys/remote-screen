package com.example.remotescreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After the phone (re)starts, bring the standby presence back so the device can be woken remotely
 * again — and, if the owner left auto-start on, MainActivity will re-begin sharing on its own.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a == Intent.ACTION_BOOT_COMPLETED ||
            a == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            a == "android.intent.action.QUICKBOOT_POWERON" ||
            a == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            // "Tự đánh thức" mặc định BẬT: chỉ cần mật khẩu đã lưu là đứng chờ kết nối sau khi máy mở lại.
            if (!p.getBoolean("autoShare", true)) return
            if ((p.getString("password", "") ?: "").length < 6) return
            StandbyService.startIfEnabled(ctx)
        }
    }
}
