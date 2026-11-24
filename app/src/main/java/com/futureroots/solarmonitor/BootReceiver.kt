package com.futureroots.solarmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** يُعيد الجدولة تلقائياً بعد إعادة تشغيل الهاتف أو تحديث التطبيق، دون أن يفتحه المستخدم (وبلا أي إشعار). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.isActivated(context) && Prefs.enabled(context)) {
            LogStore.add(context, "إقلاع/تحديث: إعادة الجدولة تلقائياً")
            Scheduler.ensure(context)
            Scheduler.kick(context)
        }
    }
}
