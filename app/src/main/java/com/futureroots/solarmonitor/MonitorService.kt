package com.futureroots.solarmonitor

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
import com.futureroots.solarmonitor.core.Agent
import com.futureroots.solarmonitor.core.Config
import com.futureroots.solarmonitor.core.FileStateStore
import com.futureroots.solarmonitor.core.NetGuard
import com.futureroots.solarmonitor.core.Net
import com.futureroots.solarmonitor.core.Profile
import com.futureroots.solarmonitor.core.Profiles
import com.futureroots.solarmonitor.core.RunStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * خدمة أمامية خفيفة (أيقونة "مراقب الطاقة الشمسية نشط") تمنع أندرويد من إيقاف التطبيق.
 * الجدولة: AlarmManager (setAndAllowWhileIdle) يوقظ الخدمة كل 15/60 دقيقة عند حدود الفترة،
 * وكل دورة تعمل تحت WakeLock مؤقت ثم ينام الهاتف. خارج شبكة المشروع: صمت تام بلا مسح.
 */
class MonitorService : Service() {
    companion object {
        const val ACTION_ENSURE = "com.futureroots.solarmonitor.ENSURE"     // تأكد أن الخدمة حيّة؛ شغّل دورة فقط إن حان موعدها
        const val ACTION_TICK = "com.futureroots.solarmonitor.TICK"         // من المنبّه
        const val ACTION_RUN_NOW = "com.futureroots.solarmonitor.RUN_NOW"   // من الزر: قراءة وإرسال الآن
        const val ACTION_TEST = "com.futureroots.solarmonitor.TEST"         // من الزر: مسح وقراءة بلا إرسال
        const val ACTION_STOP = "com.futureroots.solarmonitor.STOP"
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 1

        fun start(ctx: Context, action: String = ACTION_ENSURE) {
            ctx.startForegroundService(Intent(ctx, MonitorService::class.java).setAction(action))
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    @Volatile private var agent: Agent? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "المراقبة", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_ENSURE
        startFg(Prefs.status(this))                                   // يجب خلال 5 ثوانٍ من startForegroundService
        if (action == ACTION_STOP) {
            Prefs.setEnabled(this, false)
            cancelAlarm(); agent?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        val cfg = Prefs.config(this)
<<<<<<< HEAD
        if (cfg == null || cfg.serials.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
=======
        if (cfg == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
        Prefs.setEnabled(this, true)

        val stepMs = cfg.intervalMinutes * 60_000L
        val due = System.currentTimeMillis() - Prefs.lastRunAt(this) >= stepMs * 9 / 10
        val shouldRun = when (action) { ACTION_TICK, ACTION_RUN_NOW, ACTION_TEST -> true; else -> due }
        if (shouldRun && running.compareAndSet(false, true)) {
            worker.execute {
                try { cycle(cfg, dry = action == ACTION_TEST, force = action == ACTION_RUN_NOW || action == ACTION_TEST) }
                catch (e: Throwable) { log("خطأ غير متوقع: ${e.javaClass.simpleName}: ${e.message}"); setStatus("خطأ: ${e.message}", true) }
                finally { running.set(false); scheduleNext() }
            }
        } else if (!shouldRun) scheduleNext()
        return START_STICKY
    }

    override fun onDestroy() {
        agent?.cancel()
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------- دورة واحدة ----------
    private fun cycle(cfg: Config, dry: Boolean, force: Boolean) {
        val wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "solarmonitor:cycle")
        wl.setReferenceCounted(false)
        wl.acquire(10 * 60_000L)
        try {
            val wifi = WifiProbe.current(this)
            val d = NetGuard.decide(wifi?.ip, wifi?.ssid, cfg.ssids, Prefs.learnedSsids(this), Prefs.learnedSubnets(this))
            if (wifi == null) { setStatus("غير متصل بشبكة Wi-Fi — بانتظار العودة إلى الموقع", false); log("تخطي: لا يوجد Wi-Fi"); return }
            if (!d.run) {
                if (!force) { setStatus("${d.reason} — بانتظار العودة إلى الموقع", false); log("تخطي: ${d.reason}"); return }
                log("تنبيه: ${d.reason} — تشغيل يدوي رغم ذلك")
            }
            val res = newAgent(cfg, wifi).also { agent = it }.run(dry = dry)
            if (res.found > 0) Prefs.learn(this, wifi.ssid, Net.subnet24(wifi.ip))
            val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
<<<<<<< HEAD
            val pending = if (res.pendingOutbox > 0) "، معلّق ${res.pendingOutbox}" else ""
            val miss = if (res.missing.isNotEmpty()) "\nلم يُعثر على: ${res.missing.joinToString("، ")}" else ""
            val text = when (res.status) {
                RunStatus.NO_DEVICES -> "$hhmm: لم يُعثر على أي من الأجهزة المُدخلة (${cfg.serials.joinToString("، ")}) على هذه الشبكة. تأكد أنك على واي فاي الموقع وأن الأرقام صحيحة." +
                    if (res.ignored.isNotEmpty()) "\nوُجدت أجهزة أخرى غير مُدخلة: ${res.ignored.joinToString("، ")}" else ""
                RunStatus.CONFIG_ERROR -> res.message
                RunStatus.CANCELLED -> "أُلغي"
                else -> (if (dry) "اختبار $hhmm: وُجد ${res.found} من ${cfg.serials.size} (بلا إرسال)" else "$hhmm: وُجد ${res.found} من ${cfg.serials.size}، أُرسل ${res.sent}$pending") + miss
=======
            val text = when (res.status) {
                RunStatus.OK -> if (dry) "اختبار $hhmm: وُجد ${res.found} إنفرتر (بلا إرسال)" else "آخر تشغيل $hhmm: ${res.found} إنفرتر، أُرسل ${res.sent}" + if (res.pendingOutbox > 0) "، معلّق ${res.pendingOutbox}" else ""
                RunStatus.PARTIAL -> "آخر تشغيل $hhmm: ${res.message}" + if (res.pendingOutbox > 0) "، معلّق ${res.pendingOutbox}" else ""
                RunStatus.NO_DEVICES -> "$hhmm: ${res.message}"
                RunStatus.CONFIG_ERROR -> res.message
                RunStatus.CANCELLED -> "أُلغي"
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
            }
            setStatus(text, res.status != RunStatus.CANCELLED)
        } finally {
            agent = null
            if (wl.isHeld) wl.release()
        }
    }

    private fun newAgent(cfg: Config, wifi: WifiState): Agent =
        Agent(cfg, loadProfiles(), FileStateStore(File(filesDir, "state.json")), AndroidEnv(wifi)) { log(it) }

    private fun loadProfiles(): List<Profile> {
        val files = ArrayList<Pair<String, String>>()
        try { assets.list("profiles")?.forEach { n -> files.add(n to assets.open("profiles/$n").bufferedReader().use { it.readText() }) } } catch (_: Exception) { }
        try { File(filesDir, "profiles").listFiles()?.forEach { files.add(it.name to it.readText()) } } catch (_: Exception) { }   // ملفات إضافية اختيارية
        val loaded = Profiles.load(files)
        loaded.problems.forEach { log("!! ملف تعريف: $it") }
        return loaded.list
    }

    // ---------- الجدولة ----------
    private fun alarmIntent(): PendingIntent = PendingIntent.getForegroundService(
        this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_TICK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** الموعد التالي = بداية الفترة القادمة (:00/:15/:30/:45 أو بداية الساعة) + 20 ثانية. */
    private fun scheduleNext() {
        if (!Prefs.enabled(this) || !Prefs.isActivated(this)) return
        val step = Prefs.interval(this) * 60_000L
        val at = (System.currentTimeMillis() / step + 1) * step + 20_000L
        getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent())
    }

    private fun cancelAlarm() { getSystemService(AlarmManager::class.java).cancel(alarmIntent()) }

    // ---------- الإشعار ----------
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_sun)
            .setContentTitle("مراقب الطاقة الشمسية نشط")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    private fun startFg(text: String) {
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(NOTIF_ID, n)
    }

    private fun setStatus(text: String, ran: Boolean) {
        Prefs.setStatus(this, text, ran)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    private fun log(line: String) = LogStore.add(this, line)
}
