package com.futureroots.solarmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** يُشغّل الخدمة تلقائياً بعد إعادة تشغيل الهاتف أو بعد تحديث التطبيق، دون أن يفتحه المستخدم. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.isActivated(context) && Prefs.enabled(context)) {
            LogStore.add(context, "إقلاع/تحديث: تشغيل الخدمة تلقائياً")
            try { MonitorService.start(context) } catch (e: Exception) { LogStore.add(context, "تعذّر تشغيل الخدمة عند الإقلاع: ${e.message}") }
        }
    }
}
