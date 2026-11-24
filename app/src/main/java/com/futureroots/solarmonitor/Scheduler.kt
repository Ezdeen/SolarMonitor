package com.futureroots.solarmonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

/**
 * الجدولة الصامتة (بلا إشعار دائم ولا شاشة قفل):
 *  - منبّه AlarmManager عند حدود كل فترة (3 أو 4 ساعات) يطلق وظيفة فورية.
 *  - وظيفة JobScheduler "لحاق" مشروطة بوجود Wi-Fi ومحفوظة عبر إعادة التشغيل: إن فاتت الفترة لأن الهاتف خارج الشبكة،
 *    تُنفَّذ تلقائياً بمجرد الاتصال بالشبكة.
 *  - الوظائف تبقى مجدولة حتى لو أُغلق التطبيق أو أُفرغت الذاكرة، وتُعاد جدولتها بعد الإقلاع (BootReceiver).
 */
object Scheduler {
    private const val JOB_A = 21      // وظيفة اللحاق (تتبادل مع B كي لا تلغي نفسها وهي تعمل)
    private const val JOB_B = 22
    private const val JOB_KICK = 23   // وظيفة فورية يطلقها المنبّه/الإقلاع
    private const val AWAY_ALARM_RETRY_MS = 30 * 60_000L
    private const val AWAY_JOB_RETRY_MS = 15 * 60_000L

    private fun active(c: Context) = Prefs.isActivated(c) && Prefs.enabled(c) && Prefs.serials(c).isNotEmpty()
    private fun comp(c: Context) = ComponentName(c, SyncJobService::class.java)
    private fun js(c: Context): JobScheduler = c.getSystemService(JobScheduler::class.java)
    private fun stepMs(c: Context) = Prefs.interval(c) * 60_000L
    private fun nextBoundary(c: Context): Long { val s = stepMs(c); return (System.currentTimeMillis() / s + 1) * s + 20_000L }

    private fun alarmPi(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 2, Intent(c, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** يتأكد أن المنبّه ووظيفة اللحاق مجدولان (يُستدعى عند فتح التطبيق). */
    fun ensure(c: Context) {
        if (!active(c)) return
        try {
            val j = js(c)
            if (j.getPendingJob(JOB_A) == null && j.getPendingJob(JOB_B) == null) scheduleJob(c, -1, null)
            scheduleAlarm(c, null)
        } catch (e: Exception) { LogStore.add(c, "تعذّرت الجدولة: ${e.message}") }
    }

    /** يُستدعى بعد كل دورة، أو عند تغيير الفترة. */
    fun scheduleNext(c: Context, outcome: Outcome, avoidJobId: Int) {
        if (!active(c)) { cancelAll(c); return }
        try {
            val away = outcome == Outcome.AWAY
            scheduleJob(c, avoidJobId, if (away) AWAY_JOB_RETRY_MS else null)
            scheduleAlarm(c, if (away) AWAY_ALARM_RETRY_MS else null)
        } catch (e: Exception) { LogStore.add(c, "تعذّرت الجدولة: ${e.message}") }
    }

    fun scheduleAlarm(c: Context, retryMs: Long?) {
        if (!active(c)) return
        var at = nextBoundary(c)
        if (retryMs != null) at = minOf(at, System.currentTimeMillis() + retryMs)
        c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmPi(c))
    }

    private fun scheduleJob(c: Context, avoidId: Int, retryMs: Long?) {
        val j = js(c)
        val id = if (avoidId == JOB_A) JOB_B else JOB_A
        val other = if (id == JOB_A) JOB_B else JOB_A
        if (other != avoidId) j.cancel(other)
        val delay = (retryMs ?: (nextBoundary(c) - System.currentTimeMillis())).coerceAtLeast(1000L)
        val b = JobInfo.Builder(id, comp(c)).setMinimumLatency(delay).setPersisted(true)
        if (Build.VERSION.SDK_INT >= 28) {
            b.setRequiredNetwork(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)   // قد تكون شبكة الموقع بلا إنترنت
                .build())
        } else b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        j.schedule(b.build())
    }

    /** وظيفة فورية بلا شرط شبكة (الدورة نفسها تتحقق من الشبكة وتتخطى بهدوء إن لزم). */
    fun kick(c: Context) {
        if (!active(c)) return
        try {
            val j = js(c)
            if (j.getPendingJob(JOB_KICK) != null) return
            j.schedule(JobInfo.Builder(JOB_KICK, comp(c)).setMinimumLatency(1000L).build())
        } catch (e: Exception) { LogStore.add(c, "تعذّر تشغيل الوظيفة الفورية: ${e.message}") }
    }

    fun cancelAll(c: Context) {
        try {
            val j = js(c)
            j.cancel(JOB_A); j.cancel(JOB_B); j.cancel(JOB_KICK)
            c.getSystemService(AlarmManager::class.java).cancel(alarmPi(c))
        } catch (_: Exception) { }
    }
}

/** ينفّذ الدورة صامتاً في الخلفية بلا إشعار. */
class SyncJobService : JobService() {
    @Volatile private var worker: Thread? = null

    override fun onStartJob(p: JobParameters): Boolean {
        val app = applicationContext
        worker = Thread {
            var outcome = Outcome.NOT_DUE
            try { outcome = CycleRunner.run(app, Mode.SCHEDULED) } catch (_: Throwable) { }
            Scheduler.scheduleNext(app, outcome, p.jobId)
            jobFinished(p, false)
        }.also { it.name = "solar-sync"; it.start() }
        return true
    }

    override fun onStopJob(p: JobParameters): Boolean {
        CycleRunner.cancel()
        return true     // أعد المحاولة لاحقاً
    }
}

/** يستيقظ عند حدود الفترة ويطلق الوظيفة، ويجدول المنبّه التالي فوراً كي لا تنقطع السلسلة. */
class AlarmReceiver : BroadcastReceiver() {
    companion object { const val ACTION = "com.futureroots.solarmonitor.ALARM" }
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.kick(context)
        Scheduler.scheduleAlarm(context, null)
    }
}
