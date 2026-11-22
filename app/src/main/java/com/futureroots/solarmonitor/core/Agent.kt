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

<<<<<<< HEAD
class RunResult(
    val status: RunStatus, val found: Int, val sent: Int, val failures: Int, val pendingOutbox: Int, val message: String,
    val hosts: Map<String, String> = emptyMap(),
    val missing: List<String> = emptyList(),     // أرقام أدخلها المستخدم ولم تُوجد على الشبكة
    val ignored: List<String> = emptyList(),     // إنفرترات وُجدت لكنها غير مُدخلة (تُتجاهل)
)
=======
class RunResult(val status: RunStatus, val found: Int, val sent: Int, val failures: Int, val pendingOutbox: Int, val message: String, val hosts: Map<String, String> = emptyMap())
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f

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
<<<<<<< HEAD
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
=======
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
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
<<<<<<< HEAD
            if (ip == null || has(found, sn)) continue
            checkCancel()
            if (portOpen(ip, 2000)) probeHost(ip, found)
            if (has(found, sn)) log("  $sn: على عنوانه المعروف $ip")
        }
        val lost = known.filter { !has(found, it) && state.macs.containsKey(it) }
=======
            if (ip == null || found.containsKey(sn)) continue
            checkCancel()
            if (portOpen(ip, 2000)) probeHost(ip, found)
            if (found.containsKey(sn)) log("  $sn: على عنوانه المعروف $ip")
        }
        val lost = known.filter { !found.containsKey(it) && state.macs.containsKey(it) }
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
        if (lost.isEmpty()) return
        log("  تغيّر عنوان ${lost.joinToString(", ")}؟ البحث بالـ MAC في جدول ARP ...")
        Net.pokeArp(nets().flatMap { it.hosts }, 1000)
        val arp = env.readArp()
        for (sn in lost) {
<<<<<<< HEAD
            if (has(found, sn)) continue
=======
            if (found.containsKey(sn)) continue
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
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
<<<<<<< HEAD
        val lostNow = known.filter { !has(found, it) }
=======
        val lostNow = known.filter { !found.containsKey(it) }
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
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
    private fun bucketStamp(): String {
        val step = cfg.intervalMinutes * 60_000L
        return iso(System.currentTimeMillis() / step * step)
    }

    // ---------- صندوق الإرسال (outbox): لا تضيع قراءة إن تعطلت جهة ----------
    private fun deliver(item: OutboxItem) {
        if (item.kind == "platform") {
            val r = Http.postJson("${cfg.ecoUrl}/api/integrations/inverter", mapOf("Authorization" to "Bearer ${cfg.ecoKey}"), item.body)
            val cum = r["cumulativeCheck"].asMap()?.get("status")
            log("  [منصة] ${item.label}: hashMatchStatus=${r["hashMatchStatus"]} duplicate=${r["duplicate"] == true} cumulative=$cum")
            if (r["hashMatchStatus"] != "match") log("  !! المنصة سجّلت عدم تطابق للهاش (${item.label})")
        } else {
            val r = Http.postJson("${item.url}/ingest", mapOf("Authorization" to "Bearer ${item.key}"), item.body)
            log("  [تدقيق ${item.url}] ${item.label}: id=${r["id"]} hashStatus=${r["hashStatus"]} duplicate=${r["duplicate"] == true}${if (r["conflict"] != null && r["conflict"] != false) " CONFLICT!" else ""}")
        }
    }

    private fun flushOutbox() {
        val keep = ArrayList<OutboxItem>()
        for (item in state.outbox.toList()) {
            checkCancel()
            try { deliver(item) }
            catch (e: CancelledException) { throw e }
            catch (e: Exception) {
                item.attempts++
                val age = System.currentTimeMillis() - (try { Instant.parse(item.createdAt).toEpochMilli() } catch (_: Exception) { 0L })
                if (age > cfg.maxOutboxDays * 86_400_000L) log("  تخلّينا عن ${item.kind} ${item.label} بعد ${cfg.maxOutboxDays} أيام: ${e.message}")
                else { keep.add(item); log("  [${item.kind}] ${item.label} لم يُسلَّم (محاولة ${item.attempts}) — سيُعاد: ${e.message}") }
            }
        }
        state.outbox.clear(); state.outbox.addAll(keep)
        store.save(state)
    }

    // ---------- التنفيذ ----------
    fun run(dry: Boolean = false, exceptional: Boolean = false, forceScan: Boolean = false): RunResult {
        if (!dry && (cfg.n8nUrl.isBlank() || cfg.n8nKey.isBlank() || cfg.ecoKey.isBlank()))
            return RunResult(RunStatus.CONFIG_ERROR, 0, 0, 0, 0, "الإعدادات ناقصة — أعد التفعيل")
        state = store.load()
        return try { runInner(dry, exceptional, forceScan) }
        catch (e: CancelledException) { try { store.save(state) } catch (_: Exception) { }; RunResult(RunStatus.CANCELLED, 0, 0, 0, state.outbox.size, "أُلغي") }
    }

    private fun runInner(dry: Boolean, exceptional: Boolean, forceScan: Boolean): RunResult {
        val timestamp = if (exceptional) iso(System.currentTimeMillis()) else bucketStamp()
        log("[${iso(System.currentTimeMillis())}] run v$VERSION ${if (dry) "(DRY) " else ""}${if (exceptional) "(EXCEPTIONAL) " else ""}ts=$timestamp")
        log("ملفات التعريف: ${profiles.joinToString(", ") { it.id + if (it.status == "verified") "" else "*" }}")
        if (!dry && state.outbox.isNotEmpty()) { log("إعادة إرسال ${state.outbox.size} عنصر معلّق..."); flushOutbox() }

        // 1) تحديد الأجهزة (IP ليس معرّفاً دائماً: السيريال هو المعرّف)
        var failures = 0; var sent = 0
<<<<<<< HEAD
        val seen = (state.devices.keys + state.hosts.keys).distinct()
        val known = if (allowed.isEmpty()) seen else allowed.map { a -> seen.firstOrNull { canon(it) == a } ?: a }
        val hosts = LinkedHashMap<String, String>()
        var scanned = locateDevices(hosts, known, forceFull = forceScan)
        applyFilter(hosts)
        fun missing() = known.filter { !has(hosts, it) }
=======
        val known = (state.devices.keys + state.hosts.keys).distinct()
        val hosts = LinkedHashMap<String, String>()
        var scanned = locateDevices(hosts, known, forceFull = forceScan)
        fun missing() = known.filter { !hosts.containsKey(it) }
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
        if (missing().isNotEmpty() && scanned) {   // جهاز معروف لم يظهر: مسح ثانٍ بمهلة أطول قبل الاستسلام
            log("لم يظهر: ${missing().joinToString(", ")} — إعادة المسح بمهلة أطول بعد 10 ثوانٍ")
            sleep(10_000)
            scanned = locateDevices(hosts, known, cfg.scanTimeoutMs * 2, forceFull = true)
<<<<<<< HEAD
            applyFilter(hosts)
=======
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
        }
        for (sn in missing()) { failures++; log("!! الإنفرتر $sn غير موجود على الشبكة (آخر عنوان معروف: ${state.hosts[sn] ?: "غير معروف"})") }
        if (hosts.isNotEmpty()) {
            val arp = env.readArp()
            for ((sn, ip) in hosts) arp[ip]?.let { state.macs[sn] = it }
        }
        state.hosts.putAll(hosts); state.lastScan = System.currentTimeMillis()
        if (hosts.isEmpty()) {
            log("لم يُعثر على أي إنفرتر على الشبكة"); store.save(state)
<<<<<<< HEAD
            return RunResult(RunStatus.NO_DEVICES, 0, 0, failures, state.outbox.size, "لم يُعثر على إنفرتر مطابق على هذه الشبكة", missing = missing(), ignored = ignored.toList())
=======
            return RunResult(RunStatus.NO_DEVICES, 0, 0, failures, state.outbox.size, "لم يُعثر على إنفرتر على هذه الشبكة")
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
        }

        // 2) قراءة ← n8n ← (منصة + مدققون)
        for ((serial, host) in hosts.entries.toList()) {
            checkCancel()
            val tag = "$host ($serial)"
            try {
                val dev = devInfo.getValue(serial)
                val prev = state.devices[serial]
                val r: Reading = when (dev) {
                    is Dev.Ss -> {
                        val d = dev.d
                        val x = gate.run(host) { SunSpec.readInverter(host, d, env.socketFactory, cfg.port) }
                        Reading(null, x.totalKwh, null, x.ratedW, "SunSpec ${d.manufacturer} ${d.model}", x.wattsNow, linkedMapOf(
                            "unit" to d.unit, "source" to "modbus-tcp-sunspec",
                            "sunspec" to linkedMapOf("base" to d.base, "inverterModelId" to d.invId, "manufacturer" to d.manufacturer, "model" to d.model, "version" to d.version,
                                "notImplemented" to x.notImplemented, "whWords" to x.whWords, "whScaleFactor" to x.whScaleFactor),
                            "acPowerW" to x.wattsNow, "status" to x.status, "statusName" to x.statusName))
                    }
                    is Dev.Pr -> {
                        val p = dev.m.profile
                        val x = gate.run(host) { Profiles.readDevice(host, dev.m, env.socketFactory, cfg.port) }
                        val ev = linkedMapOf<String, Any?>(
                            "unit" to dev.m.unit, "source" to "modbus-tcp-fc0${p.function}",
                            "profile" to linkedMapOf("id" to p.id, "status" to p.status, "addressOffset" to dev.m.offset),
                            "registers" to linkedMapOf("dailyAddr" to p.daily?.address, "totalAddr" to p.total.address))
                        if (x.dailyInvalid) ev["dailyInvalid"] = true
                        ev["rawDailyRegister"] = x.rawDaily?.let { if (it.size == 1) it[0] else it.toList() }
                        ev["rawTotalWords"] = x.rawTotal.toList()
                        Reading(x.daily, x.totalKwh, p.total.scale, p.ratedW, "${p.brand} ${p.id}", null, ev)
                    }
                }
                val total = jsRound(r.totalKwh)
                val daily = r.daily
                val hasDaily = daily != null
                val nowDate = localDate()
                val dateChanged = prev != null && prev.date.isNotEmpty() && prev.date != nowDate

                // يوم جديد والعداد اليومي صفر تماماً: ننتظر أول قيمة موجبة حقيقية (لا نسجّل صفراً كاذباً)
                if (daily != null && dateChanged && daily == 0.0) {
                    log("$tag: يوم جديد (${prev?.date} ← $nowDate) والعداد اليومي 0 — انتظار أول قيمة موجبة، لا إرسال"); continue
                }

                // الإنتاج = فرق العداد الإجمالي منذ آخر قراءة ناجحة (تراكمي لا يتصفّر)، مع حواجز معقولية
                val productionNow: Double; val derivation: String; var crossCheck: Map<String, Any?>? = null
                if (prev == null) { productionNow = 0.0; derivation = "first-reading" }
                else {
                    val diff = r.totalKwh - prev.total
                    val hours = max((Instant.parse(timestamp).toEpochMilli() - Instant.parse(prev.timestamp).toEpochMilli()) / 3.6e6, 0.0)
                    if (diff < -0.05) throw IllegalStateException("العداد الإجمالي نقص (${prev.total} ← ${"%.1f".format(r.totalKwh)} kWh): استبدال جهاز أو خطأ في معامل القياس — لم يُرسل شيء")
                    val rw = r.ratedW
                    if (rw != null && hours > 0 && diff > max(1.0, (rw / 1000) * hours * 1.5))
                        throw IllegalStateException("الزيادة ${"%.1f".format(diff)} kWh أكبر من الممكن لإنفرتر ${rw}W خلال ${"%.1f".format(hours)} ساعة — لم يُرسل شيء")
                    productionNow = toFixed(max(diff, 0.0), 1); derivation = "delta-of-total"
                    if (daily != null) {             // العداد اليومي للتحقق فقط: لا يغيّر الرقم المُرسل
                        val tol = max(1.5, (r.totalResolution?.takeIf { it != 0.0 } ?: 0.1) * 1.5)
                        val pd = prev.daily
                        val dailyDelta = if (!dateChanged && pd != null) toFixed(daily - pd, 1) else null
                        val ok = if (dateChanged) diff + tol >= daily else if (dailyDelta == null) true else abs(dailyDelta - diff) <= tol
                        crossCheck = linkedMapOf("status" to (if (ok) "ok" else "mismatch"), "dailyKwh" to daily, "dailyDelta" to dailyDelta, "totalDelta" to toFixed(diff, 3), "dateChanged" to dateChanged)
                        if (!ok) log("  تنبيه: العداد اليومي لا يطابق فرق الإجمالي (يومي ${dailyDelta ?: daily} ↔ إجمالي ${"%.1f".format(diff)}) — اعتمدنا الإجمالي")
                    }
                }
                log("$tag: [${r.label}] total=${"%.1f".format(r.totalKwh)}kWh${if (daily != null) " daily=${Json.numToString(daily)}" else ""}${if (r.power != null) " power=${Json.numToString(r.power)}W" else ""} productionNow=${Json.numToString(productionNow)} ($derivation${if (dateChanged) ", يوم جديد" else ""})")
                val evidence = linkedMapOf<String, Any?>("readAt" to iso(System.currentTimeMillis()), "host" to host)
                evidence.putAll(r.ev)
                evidence["totalKwh"] = toFixed(r.totalKwh, 3); evidence["dailyKwh"] = daily
                evidence["previousTotalKwh"] = prev?.total; evidence["previousTimestamp"] = prev?.timestamp
                evidence["localDate"] = nowDate; evidence["previousLocalDate"] = prev?.date
                evidence["crossCheck"] = crossCheck; evidence["derivation"] = derivation
                // لا نحفظ عداداً يومياً مشبوهاً (هبوط لحظي داخل اليوم نفسه) حتى لا يُربك المقارنة التالية
                val pd2 = prev?.daily
                val suspicious = daily != null && crossCheck?.get("status") == "mismatch" && !dateChanged && pd2 != null && daily < pd2
                val keepDaily: Double? = if (daily != null && !suspicious) daily else pd2
                val stateEntry = DeviceState(toFixed(r.totalKwh, 3), timestamp, nowDate, dev.driverName, if (hasDaily) keepDaily else null)
                val payload = linkedMapOf<String, Any?>("serialNumber" to serial, "productionNow" to productionNow, "productionTotal" to total, "timestamp" to timestamp)

                if (prev?.timestamp == timestamp) { log("  أُرسلت قراءة هذه الفترة — تخطي"); continue }
                if (dry) { log("  (dry) canonical=" + canonical(serial, productionNow, total, timestamp)); continue }
                if (dev is Dev.Pr && dev.m.profile.status != "verified" && !cfg.allowUnverified)
                    throw IllegalStateException("ملف التعريف ${dev.m.profile.id} غير مُتحقَّق — لا إرسال فعلي. طابق الأرقام مع تطبيق الشركة ثم غيّر status إلى verified")

                // الخطوة 1: n8n يحسب الهاش ويسجله على Hedera
                val n8n = Http.postWithRetry(cfg.n8nUrl, mapOf("x-agent-key" to cfg.n8nKey), payload, log = log)
                val n8nHash = n8n["n8nHash"].asStr(); val txId = n8n["hederaTransactionId"]?.toString()
                if (n8nHash.isNullOrEmpty() || txId.isNullOrEmpty()) throw IllegalStateException("رد n8n ناقص: " + Json.stringify(n8n).take(200))
                val accountId = n8n["hederaAccountId"]?.toString()?.takeIf { it.isNotEmpty() } ?: if (txId.contains('@')) txId.substringBefore('@') else null
                val topicId = n8n["hederaTopicId"]?.toString()?.takeIf { it.isNotEmpty() }
                val consensusAt = n8n["hederaConsensusAt"]?.toString()?.takeIf { it.isNotEmpty() }
                log("  n8n hash=$n8nHash\n  hedera tx=$txId account=$accountId topic=$topicId")
                if (sha256(canonical(serial, productionNow, total, timestamp)) != n8nHash) { failures++; log("  !! هاش n8n يختلف عن حساب الـ Agent — صيغة الهاش في n8n غير مطابقة") }

                // الخطوتان 2 و3: نُخزّن أولاً في الـ outbox (لا يضيع شيء) ثم نُسلّم
                val label = "$serial@$timestamp"; val nowIso = iso(System.currentTimeMillis())
                val platform = LinkedHashMap<String, Any?>(payload)
                platform["n8nHash"] = n8nHash; platform["hederaTransactionId"] = txId
                consensusAt?.let { platform["hederaConsensusAt"] = it }; accountId?.let { platform["hederaAccountId"] = it }; topicId?.let { platform["hederaTopicId"] = it }
                state.outbox.add(OutboxItem("platform", label, nowIso, null, null, platform))
                for (a in cfg.audits) {
                    val body = LinkedHashMap<String, Any?>(payload)
                    body["n8nHash"] = n8nHash
                    body["hedera"] = linkedMapOf("transactionId" to txId, "accountId" to accountId, "topicId" to topicId, "consensusAt" to consensusAt)
                    body["evidence"] = evidence; body["agentVersion"] = VERSION
                    state.outbox.add(OutboxItem("audit", label, nowIso, a.url, a.key, body))
                }
                state.devices[serial] = stateEntry
                sent++
                store.save(state)                  // الحالة + الـ outbox معاً قبل أي اتصال بالمنصة/المدقق
                flushOutbox()
            } catch (e: CancelledException) { throw e }
            catch (e: Exception) { failures++; log("$tag: FAILED — ${e.message}") }
        }
        store.save(state)
        if (state.outbox.isNotEmpty()) log("معلّق للإرسال لاحقاً: ${state.outbox.size}")
        val status = if (failures == 0) RunStatus.OK else RunStatus.PARTIAL
<<<<<<< HEAD
        return RunResult(status, hosts.size, sent, failures, state.outbox.size, if (failures == 0) "تمت القراءة بنجاح" else "اكتملت مع $failures خطأ", hosts, missing(), ignored.toList())
=======
        return RunResult(status, hosts.size, sent, failures, state.outbox.size, if (failures == 0) "تمت القراءة بنجاح" else "اكتملت مع $failures خطأ", hosts)
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
    }
}
