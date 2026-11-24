package com.futureroots.solarmonitor

import android.content.Context
import android.os.PowerManager
import com.futureroots.solarmonitor.core.Agent
import com.futureroots.solarmonitor.core.Config
import com.futureroots.solarmonitor.core.FileStateStore
import com.futureroots.solarmonitor.core.Net
import com.futureroots.solarmonitor.core.NetGuard
import com.futureroots.solarmonitor.core.Profile
import com.futureroots.solarmonitor.core.Profiles
import com.futureroots.solarmonitor.core.RunStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** نتيجة محاولة التشغيل (تحدد ما تفعله الجدولة بعدها). */
enum class Outcome { RAN, AWAY, NOT_DUE, BUSY, NOT_CONFIGURED }

/** وضع التشغيل: مجدول (يحترم الموعد) أو يدوي (فوراً) أو اختبار بلا إرسال. */
enum class Mode { SCHEDULED, NOW, TEST }

/**
 * دورة قراءة وإرسال واحدة، مستقلة عن أي إشعار. تُستدعى من SyncJobService (صامتاً في الخلفية)
 * ومن MonitorService (عند ضغط الأزرار اليدوية فقط).
 */
object CycleRunner {
    private val running = AtomicBoolean(false)
    @Volatile private var agent: Agent? = null

    fun cancel() { agent?.cancel() }

    fun run(ctx: Context, mode: Mode, onStatus: (String) -> Unit = {}): Outcome {
        val app = ctx.applicationContext
        val cfg = Prefs.config(app)
        if (cfg == null || cfg.serials.isEmpty()) return Outcome.NOT_CONFIGURED
        if (mode == Mode.SCHEDULED) {
            // مرة واحدة لكل فترة: يعمل فقط إن بدأت فترة جديدة منذ آخر تشغيل ناجح (يشمل اللحاق بعد غياب الشبكة)
            val step = cfg.intervalMinutes * 60_000L
            if (System.currentTimeMillis() / step <= Prefs.lastRunAt(app) / step) return Outcome.NOT_DUE
        }
        if (!running.compareAndSet(false, true)) return Outcome.BUSY
        try {
            return cycle(app, cfg, mode, onStatus)
        } catch (e: Throwable) {
            LogStore.add(app, "خطأ غير متوقع: ${e.javaClass.simpleName}: ${e.message}")
            val t = "خطأ: ${e.message}"
            Prefs.setStatus(app, t, true); onStatus(t)
            return Outcome.RAN
        } finally {
            running.set(false)
        }
    }

    private fun cycle(ctx: Context, cfg: Config, mode: Mode, onStatus: (String) -> Unit): Outcome {
        val dry = mode == Mode.TEST
        val force = mode != Mode.SCHEDULED
        fun status(t: String, ran: Boolean) { Prefs.setStatus(ctx, t, ran); onStatus(t) }
        fun log(l: String) = LogStore.add(ctx, l)

        val wl = ctx.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "solarmonitor:cycle")
        wl.setReferenceCounted(false)
        wl.acquire(10 * 60_000L)
        try {
            val wifi = WifiProbe.current(ctx)
            val d = NetGuard.decide(wifi?.ip, wifi?.ssid, cfg.ssids, Prefs.learnedSsids(ctx), Prefs.learnedSubnets(ctx))
            if (wifi == null) { status("غير متصل بشبكة Wi-Fi — بانتظار العودة إلى الموقع", false); log("تخطي: لا يوجد Wi-Fi"); return Outcome.AWAY }
            if (!d.run) {
                if (!force) { status("${d.reason} — بانتظار العودة إلى الموقع", false); log("تخطي: ${d.reason}"); return Outcome.AWAY }
                log("تنبيه: ${d.reason} — تشغيل يدوي رغم ذلك")
            }
            val res = newAgent(ctx, cfg, wifi).also { agent = it }.run(dry = dry)
            if (res.found > 0) Prefs.learn(ctx, wifi.ssid, Net.subnet24(wifi.ip))
            val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val pending = if (res.pendingOutbox > 0) "، معلّق ${res.pendingOutbox}" else ""
            val miss = if (res.missing.isNotEmpty()) "\nلم يُعثر على: ${res.missing.joinToString("، ")}" else ""
            val text = when (res.status) {
                RunStatus.NO_DEVICES -> "$hhmm: لم يُعثر على أي من الأجهزة المُدخلة (${cfg.serials.joinToString("، ")}) على هذه الشبكة. تأكد أنك على واي فاي الموقع وأن الأرقام صحيحة." +
                    if (res.ignored.isNotEmpty()) "\nوُجدت أجهزة أخرى غير مُدخلة: ${res.ignored.joinToString("، ")}" else ""
                RunStatus.CONFIG_ERROR -> res.message
                RunStatus.CANCELLED -> "أُلغي"
                else -> (if (dry) "اختبار $hhmm: وُجد ${res.found} من ${cfg.serials.size} (بلا إرسال)" else "$hhmm: وُجد ${res.found} من ${cfg.serials.size}، أُرسل ${res.sent}$pending") + miss
            }
            status(text, res.status != RunStatus.CANCELLED)
            return Outcome.RAN
        } finally {
            agent = null
            if (wl.isHeld) wl.release()
        }
    }

    private fun newAgent(ctx: Context, cfg: Config, wifi: WifiState): Agent =
        Agent(cfg, loadProfiles(ctx), FileStateStore(File(ctx.filesDir, "state.json")), AndroidEnv(wifi)) { LogStore.add(ctx, it) }

    private fun loadProfiles(ctx: Context): List<Profile> {
        val files = ArrayList<Pair<String, String>>()
        try { ctx.assets.list("profiles")?.forEach { n -> files.add(n to ctx.assets.open("profiles/$n").bufferedReader().use { it.readText() }) } } catch (_: Exception) { }
        try { File(ctx.filesDir, "profiles").listFiles()?.forEach { files.add(it.name to it.readText()) } } catch (_: Exception) { }   // ملفات إضافية اختيارية
        val loaded = Profiles.load(files)
        loaded.problems.forEach { LogStore.add(ctx, "!! ملف تعريف: $it") }
        return loaded.list
    }
}
