package com.futureroots.solarmonitor

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
import java.util.concurrent.Executors

/**
 * خدمة قصيرة العمر تُستخدم فقط عند ضغط أزرار «إعادة المحاولة» و«فحص فقط» (والتشغيل الأول بعد الربط).
 * التشغيل الدوري نفسه صامت تماماً عبر Scheduler/SyncJobService بلا أي إشعار.
 * إشعارها (أثناء التشغيل اليدوي فقط) بأدنى أهمية ومخفي من شاشة القفل.
 */
class MonitorService : Service() {
    companion object {
        const val ACTION_RUN_NOW = "com.futureroots.solarmonitor.RUN_NOW"   // قراءة وإرسال الآن
        const val ACTION_TEST = "com.futureroots.solarmonitor.TEST"         // مسح وقراءة بلا إرسال
        private const val CHANNEL = "monitor_silent"
        private const val NOTIF_ID = 1

        fun start(ctx: Context, action: String = ACTION_RUN_NOW) {
            ctx.startForegroundService(Intent(ctx, MonitorService::class.java).setAction(action))
        }
    }

    private val worker = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("monitor")   // القناة القديمة (كانت ظاهرة)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "تشغيل يدوي", NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            setSound(null, null)
            enableVibration(false)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg("جارٍ التنفيذ…")   // مطلوب خلال 5 ثوانٍ من startForegroundService
        val mode = when (intent?.action) {
            ACTION_TEST -> Mode.TEST
            ACTION_RUN_NOW -> Mode.NOW
            else -> Mode.SCHEDULED
        }
        worker.execute {
            var outcome = Outcome.NOT_DUE
            try { outcome = CycleRunner.run(this, mode) { updateNotif(it) } } catch (_: Throwable) { }
            Scheduler.scheduleNext(this, outcome, -1)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_sun)
            .setContentTitle("مراقب الطاقة الشمسية")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open)
        return b.build()
    }

    private fun startFg(text: String) {
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(NOTIF_ID, n)
    }

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }
}
