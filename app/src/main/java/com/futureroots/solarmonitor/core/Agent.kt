package com.futureroots.solarmonitor.core

import java.net.InetSocketAddress
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.net.SocketFactory
import kotlin.math.abs
import kotlin.math.max

/** ما تحتاجه النواة من البيئة (أندرويد يربط المقابس بشبكة Wi-Fi، والاختبارات على JVM تستعمل الافتراضي). */
interface AgentEnv {
    val socketFactory: SocketFactory
    fun localNetworks(): List<LocalNet>
    fun readArp(): Map<String, String> = emptyMap()
}

class CancelledException : RuntimeException("أُلغي التشغيل")

enum class RunStatus { OK, PARTIAL, NO_DEVICES, CONFIG_ERROR, CANCELLED }

class RunResult(
    val status: RunStatus, val found: Int, val sent: Int, val failures: Int, val pendingOutbox: Int, val message: String,
    val hosts: Map<String, String> = emptyMap(),
    val missing: List<String> = emptyList(),     // أرقام أدخلها المستخدم ولم تُوجد على الشبكة
    val ignored: List<String> = emptyList(),     // إنفرترات وُجدت لكنها غير مُدخلة (تُتجاهل)
)

/**
 * نسخة أندرويد من agent.mjs:
 * مسح الشبكة ← قراءة الإنفرتر (SunSpec أولاً ثم ملفات profiles) ← n8n (هاش + Hedera) ← المنصة + جهات التدقيق (outbox).
 * الإنتاج = فرق العداد الإجمالي منذ آخر قراءة ناجحة (يعوّض ساعات الغياب تلقائياً).
 */
class Agent(
    private val cfg: Config,
    private val profiles: List<Profile>,
    private val store: StateStore,
    private val env: AgentEnv,
    private val log: (String) -> Unit,
) {
    companion object {
        const val VERSION = "3.4.0-android"
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)
        fun iso(ms: Long): String = ISO.format(Instant.ofEpochMilli(ms))
        fun canonical(serial: String, productionNow: Double, productionTotal: Long, timestamp: String) =
            listOf(serial, Json.numToString(productionNow), productionTotal.toString(), timestamp).joinToString("|")
        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }
    private fun checkCancel() { if (cancelled || Thread.currentThread().isInterrupted) throw CancelledException() }
    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) { throw CancelledException() } }

    private val gate = Gate(cfg.cooldownMs)
    private fun canon(s: String) = s.trim().uppercase()
    private val allowed: Set<String> = cfg.serials.map { canon(it) }.filter { it.isNotEmpty() }.toSet()
    private val ignored = LinkedHashSet<String>()
    private fun has(found: Map<String, String>, sn: String) = found.keys.any { canon(it) == canon(sn) }
    /** يُبقي فقط الإنفرترات المُدخلة؛ غيرها يُتجاهل ولا يُقرأ ولا يُرسل. */
    private fun applyFilter(found: MutableMap<String, String>) {
        if (allowed.isEmpty()) return
        for (sn in found.keys.filter { canon(it) !in allowed }) {
            log("  تجاهل إنفرتر غير مُدخل: $sn على ${found[sn]}")
            found.remove(sn); devInfo.remove(sn); ignored.add(sn)
        }
    }
    private lateinit var state: State

    private sealed class Dev(val driverName: String) {
        class Ss(val d: SunspecDevice) : Dev("sunspec")
        class Pr(val m: ProfileMatch) : Dev("profile")
    }
    private val devInfo = HashMap<String, Dev>()

    private class Reading(
        val daily: Double?, val totalKwh: Double, val totalResolution: Double?, val ratedW: Double?,
        val label: String, val power: Double?, val ev: Map<String, Any?>,
    )

    // ---------- مسح الشبكة ----------
    private fun nets(): List<LocalNet> = if (cfg.subnet != null) listOf(Net.fromSubnet(cfg.subnet)) else env.localNetworks()

    private fun portOpen(host: String, ms: Int): Boolean = try {
        env.socketFactory.createSocket().use { it.connect(InetSocketAddress(host, cfg.port), ms) }
        gate.touch(host); true
    } catch (_: Exception) { false }

    /** يعيد السيريال إن وُجد إنفرتر على h، وإلا null. كل اتصال Modbus يمر عبر البوابة (تسلسل + تهدئة). */
    private fun probeHost(h: String, found: MutableMap<String, String>): String? {
        if (cfg.sunspec) {                       // 1) SunSpec: معيار عام لمعظم الماركات
            try {
                val d = gate.run(h) { SunSpec.identify(h, env.socketFactory, cfg.port, cfg.sunspecUnits, cfg.sunspecBases, 2000) }
                if (d != null) {
                    found[d.serial] = h; devInfo[d.serial] = Dev.Ss(d)
                    log("  وُجد إنفرتر ${d.serial} على $h [SunSpec: ${d.manufacturer} ${d.model} · نموذج ${d.invId}]")
                    return d.serial
                }
            } catch (e: CancelledException) { throw e } catch (e: Exception) { log("  $h: SunSpec — ${e.message}") }
        }
        try {                                    // 2) مكتبة ملفات التعريف (Sungrow وغيرها)
            val m = gate.run(h) { Profiles.identify(h, profiles, env.socketFactory, state.profileHints[h], cfg.port, 6000, cfg.unit, cfg.offset) }
            found[m.serial] = h; devInfo[m.serial] = Dev.Pr(m)
            state.profileHints[h] = m.profile.id
            log("  وُجد إنفرتر ${m.serial} على $h [${m.profile.brand}: ${m.profile.id}${if (m.profile.status == "verified") "" else " — غير مُتحقَّق"}]")
            return m.serial
        } catch (e: CancelledException) { throw e } catch (e: Exception) {
            log("  $h: المنفذ ${cfg.port} مفتوح لكن لم يُقبل كإنفرتر (لا SunSpec ولا ملف تعريف) — ${e.message}")
            return null
        }
    }

    private fun scanPorts(hosts: List<String>, timeout: Int): List<String> {
        if (hosts.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(cfg.scanBatch, hosts.size))
        try {
            val futures = pool.invokeAll(hosts.map { h -> Callable<String?> { if (portOpen(h, timeout)) h else null } })
            return futures.mapNotNull { try { it.get() } catch (_: Exception) { null } }
        } catch (_: InterruptedException) { throw CancelledException() } finally { pool.shutdownNow() }
    }

    /** المسح الكامل على مرحلتين: (1) فحص منافذ سريع بالتوازي، (2) فحص المفتوحة واحداً واحداً. */
    private fun fullScan(found: MutableMap<String, String>, timeout: Int) {
        for (n in nets()) {
            checkCancel()
            log("${if (n.ip != null) "عنوان هذا الجهاز ${n.ip} (${n.iface}) ← " else ""}مسح ${n.first}-${n.last} على المنفذ ${cfg.port} (دفعات ${cfg.scanBatch}، مهلة ${timeout}ms) ...")
            val done = found.values.toSet()
            val open = scanPorts(n.hosts.filter { it !in done }, timeout)
            for (h in open) { checkCancel(); probeHost(h, found) }
            log("  انتهى ${n.first}-${n.last} — منافذ مفتوحة: ${open.size}")
        }
    }

    /** المسار السريع للأجهزة المعروفة: آخر IP ← ARP عبر MAC (حيث يتاح) دون مسح كامل. */
    private fun resolveKnown(found: MutableMap<String, String>, known: List<String>) {
        for (sn in known) {
            val ip = state.hosts[sn]
            if (ip == null || has(found, sn)) continue
            checkCancel()
            if (portOpen(ip, 2000)) probeHost(ip, found)
            if (has(found, sn)) log("  $sn: على عنوانه المعروف $ip")
        }
        val lost = known.filter { !has(found, it) && state.macs.containsKey(it) }
        if (lost.isEmpty()) return
        log("  تغيّر عنوان ${lost.joinToString(", ")}؟ البحث بالـ MAC في جدول ARP ...")
        Net.pokeArp(nets().flatMap { it.hosts }, 1000)
        val arp = env.readArp()
        for (sn in lost) {
            if (has(found, sn)) continue
            val ip = Net.macToIp(arp, state.macs[sn]) ?: continue
            if (ip == state.hosts[sn]) continue
            log("  $sn: MAC ${state.macs[sn]} ظهر الآن على $ip (كان ${state.hosts[sn]})")
            if (portOpen(ip, cfg.scanTimeoutMs * 2)) probeHost(ip, found)
        }
    }

    /** يعيد true إن أُجري مسح كامل. */
    private fun locateDevices(found: MutableMap<String, String>, known: List<String>, timeout: Int = cfg.scanTimeoutMs, forceFull: Boolean = false): Boolean {
        if (cfg.hosts.isNotEmpty()) {            // عناوين محددة بدل المسح
            log("فحص العناوين المحددة: ${cfg.hosts.joinToString(", ")}")
            for (h in cfg.hosts) {
                if (found.containsValue(h)) continue
                checkCancel()
                if (portOpen(h, 4000)) probeHost(h, found) else log("  $h: المنفذ ${cfg.port} مغلق أو لا يرد")
            }
            return false
        }
        if (!forceFull) resolveKnown(found, known)
        val now = System.currentTimeMillis()
        val stale = now - state.lastFullScan > cfg.fullScanHours * 3600_000L
        val lostNow = known.filter { !has(found, it) }
        // جهاز غائب: نمسح بالكامل لكن لا أكثر من مرة كل lostRescanMinutes (على الهاتف قد نكون خارج شبكة المشروع)
        val lostAllowed = lostNow.isNotEmpty() && now - state.lastLostScan > cfg.lostRescanMinutes * 60_000L
        if (forceFull || stale || known.isEmpty() || lostAllowed) {
            if (lostNow.isNotEmpty()) state.lastLostScan = now
            fullScan(found, timeout); state.lastFullScan = System.currentTimeMillis()
            return true
        }
        if (lostNow.isNotEmpty()) log("جهاز معروف غائب — تأجيل المسح الكامل (آخر مسح قبل أقل من ${cfg.lostRescanMinutes} دقيقة)")
        else log("كل الأجهزة المعروفة وُجدت (${known.size}) — تخطي المسح الكامل (التالي بعد ${cfg.fullScanHours} ساعة من آخر مسح)")
        return false
    }

    // ---------- مساعدات ----------
    private val zone: ZoneId = cfg.timeZone?.let { try { ZoneId.of(it) } catch (_: Exception) { null } } ?: ZoneId.systemDefault()
    private fun localDate(): String = java.time.LocalDate.now(zone).toString()
