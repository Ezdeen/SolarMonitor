// محاكاة كاملة للنواة على JVM: إنفرتر Modbus وهمي (Sungrow + SunSpec) + n8n/منصة/تدقيق وهمية.
// التشغيل: انظر dev-tests/README.md
import com.futureroots.solarmonitor.core.*
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.SocketFactory
import kotlin.concurrent.thread

var pass = 0; var fail = 0
fun check(c: Boolean, msg: String) { if (c) { pass++; println("  PASS $msg") } else { fail++; println("  FAIL $msg") } }

// ---------- إنفرتر Modbus TCP وهمي ----------
class FakeModbus(val port: Int, val regs: (Int, Int, Int, Int) -> IntArray?) {
    private val ss = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
    @Volatile var closed = false
    init {
        thread(isDaemon = true) {
            while (!closed) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { serve(s) }
            }
        }
    }
    private fun serve(s: Socket) {
        try {
            val i = s.getInputStream(); val o = s.getOutputStream()
            while (true) {
                val h = ByteArray(12); var n = 0
                while (n < 12) { val r = i.read(h, n, 12 - n); if (r < 0) return; n += r }
                val unit = h[6].toInt() and 0xff; val fc = h[7].toInt() and 0xff
                val addr = ((h[8].toInt() and 0xff) shl 8) or (h[9].toInt() and 0xff)
                val qty = ((h[10].toInt() and 0xff) shl 8) or (h[11].toInt() and 0xff)
                val w = regs(unit, fc, addr, qty)
                if (w == null) {
                    o.write(byteArrayOf(h[0], h[1], 0, 0, 0, 3, h[6], (fc or 0x80).toByte(), 2))
                } else {
                    val resp = ByteArray(9 + w.size * 2)
                    resp[0] = h[0]; resp[1] = h[1]; resp[4] = 0; resp[5] = (3 + w.size * 2).toByte(); resp[6] = h[6]; resp[7] = h[7]; resp[8] = (w.size * 2).toByte()
                    w.forEachIndexed { k, v -> resp[9 + k * 2] = (v shr 8).toByte(); resp[10 + k * 2] = v.toByte() }
                    o.write(resp)
                }
                o.flush()
            }
        } catch (_: Exception) { } finally { try { s.close() } catch (_: Exception) { } }
    }
    fun close() { closed = true; try { ss.close() } catch (_: Exception) { } }
}

fun strWords(s: String, words: Int): IntArray {
    val b = s.toByteArray(Charsets.ISO_8859_1).copyOf(words * 2)
    return IntArray(words) { ((b[it * 2].toInt() and 0xff) shl 8) or (b[it * 2 + 1].toInt() and 0xff) }
}

/** Sungrow: fc4 unit1، إزاحة -1: سيريال 4990 (6 كلمات)، يومي 5003 (×0.1)، إجمالي 5004-5005 (low-high). */
class SungrowSim { @Volatile var dailyRaw = 123; @Volatile var totalKwh = 5000L
    fun regs(unit: Int, fc: Int, addr: Int, qty: Int): IntArray? {
        if (unit != 1 || fc != 4) return null
        return when (addr) {
            4989 -> strWords("A2250012345", 6)
            5002 -> intArrayOf(dailyRaw)
            5003 -> intArrayOf((totalKwh and 0xffff).toInt(), (totalKwh shr 16).toInt())
            else -> null
        }
    }
}

/** SunSpec: base 40000، Model 1 (66) + Model 103 (50) + Model 120 (26) + نهاية. */
class SunspecSim { @Volatile var wh = 12_345_678L; @Volatile var rated = 10000
    val mem = HashMap<Int, Int>()
    fun regs(unit: Int, fc: Int, addr: Int, qty: Int): IntArray? {
        if (unit != 1 || fc != 3) return null
        build()
        return IntArray(qty) { mem[addr + it] ?: 0 }
    }
    private fun build() {
        mem.clear()
        mem[40000] = 0x5375; mem[40001] = 0x6e53
        mem[40002] = 1; mem[40003] = 66
        strWords("SolarEdge", 16).forEachIndexed { k, v -> mem[40002 + 2 + k] = v }
        strWords("SE10K-RW", 16).forEachIndexed { k, v -> mem[40002 + 18 + k] = v }
        strWords("0004.0015", 8).forEachIndexed { k, v -> mem[40002 + 42 + k] = v }
        strWords("7E1234AB", 16).forEachIndexed { k, v -> mem[40002 + 50 + k] = v }
        val a = 40070                                   // بداية Model 103 (ID عند a، L عند a+1)
        mem[a] = 103; mem[a + 1] = 50
        mem[a + 14] = 4200; mem[a + 15] = 0              // W = 4200 W (SF=0)
        mem[a + 24] = ((wh shr 16) and 0xffff).toInt(); mem[a + 25] = (wh and 0xffff).toInt()
        mem[a + 26] = 0                                  // WH_SF = 0
        mem[a + 38] = 4                                  // St = MPPT
        val b = a + 52                                   // Model 120
        mem[b] = 120; mem[b + 1] = 26; mem[b + 3] = rated / 10; mem[b + 4] = 1   // WRtg × 10^1
        val e = b + 28
        mem[e] = 0xffff; mem[e + 1] = 0
    }
}

// ---------- n8n / المنصة / التدقيق الوهمية ----------
class FakeCloud {
    val n8nBodies = ArrayList<Map<String, Any?>>(); val ecoBodies = ArrayList<Map<String, Any?>>(); val auditBodies = ArrayList<Map<String, Any?>>()
    @Volatile var ecoFail = false; @Volatile var n8nKeyOk = "KEY-N8N"
    val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port get() = server.address.port
    init {
        fun reply(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: Any?) {
            val b = Json.stringify(body).toByteArray(); ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.createContext("/webhook") { ex ->
            val body = Json.parse(ex.requestBody.readBytes().toString(Charsets.UTF_8)).asMap()!!
            if (ex.requestHeaders.getFirst("x-agent-key") != n8nKeyOk) { reply(ex, 401, mapOf("error" to "bad key")); return@createContext }
            n8nBodies.add(body)
            val canon = Agent.canonical(body["serialNumber"] as String, (body["productionNow"] as Number).toDouble(), (body["productionTotal"] as Number).toLong(), body["timestamp"] as String)
            reply(ex, 200, mapOf("n8nHash" to Agent.sha256(canon), "hederaTransactionId" to "0.0.1234@1700000000.123456789", "hederaTopicId" to "0.0.55"))
        }
        server.createContext("/api/integrations/inverter") { ex ->
            val body = Json.parse(ex.requestBody.readBytes().toString(Charsets.UTF_8)).asMap()!!
            if (ecoFail) { reply(ex, 503, mapOf("error" to "down")); return@createContext }
            if (ex.requestHeaders.getFirst("Authorization") != "Bearer ECO-KEY") { reply(ex, 401, mapOf("error" to "auth")); return@createContext }
            ecoBodies.add(body); reply(ex, 200, mapOf("hashMatchStatus" to "match", "duplicate" to false, "cumulativeCheck" to mapOf("status" to "ok")))
        }
        server.createContext("/audit/ingest") { ex ->
            val body = Json.parse(ex.requestBody.readBytes().toString(Charsets.UTF_8)).asMap()!!
            auditBodies.add(body); reply(ex, 200, mapOf("id" to auditBodies.size, "hashStatus" to "match"))
        }
        server.start()
    }
}

class MemStore : StateStore {
    var st = State()
    override fun load(): State = State.fromMap(Json.parse(Json.stringify(st.toMap())).asMap()!!)   // نسخة عميقة كما يفعل القرص
    override fun save(state: State) { st = State.fromMap(Json.parse(Json.stringify(state.toMap())).asMap()!!) }
}

class TestEnv(val nets: List<LocalNet> = emptyList()) : AgentEnv {
    override val socketFactory: SocketFactory = SocketFactory.getDefault()
    override fun localNetworks() = nets
}

fun cfgFor(cloud: FakeCloud, modbusPort: Int, hosts: List<String> = listOf("127.0.0.1"), subnet: String? = null, interval: Int = 60) = Config(
    n8nUrl = "http://127.0.0.1:${cloud.port}/webhook", n8nKey = "KEY-N8N", ecoUrl = "http://127.0.0.1:${cloud.port}", ecoKey = "ECO-KEY",
    audits = listOf(Audit("http://127.0.0.1:${cloud.port}/audit", "AUD-KEY")),
    hosts = hosts, subnet = subnet, port = modbusPort, cooldownMs = 30, scanTimeoutMs = 300, timeZone = "Asia/Jerusalem", intervalMinutes = interval,
)

fun profilesList(): List<Profile> = Profiles.load(File("app/src/main/assets/profiles").listFiles()!!.map { it.name to it.readText() }).list

fun main() {
    val quiet: (String) -> Unit = { if (System.getenv("VERBOSE") != null) println("      | $it") }

    println("== 1) تنسيق الأرقام يطابق JavaScript (للهاش) ==")
    val samples = doubleArrayOf(0.0, 5.0, 12.3, 12.34, 0.05, 0.15, 1.25, 1.35, 2.675, 100.0, 1234567.8, 0.1 + 0.2, 99.95, 5.55)
    File("/tmp/kt_numbers.txt").writeText(samples.joinToString("\n") { "${toFixed(it, 1).let { v -> Json.numToString(v) }}" })
    println("  (تُقارَن مع Node في run-sim.sh)")

    println("== 2) إزاحات SunSpec مقابل json الرسمي ==")
    val SIZE = mapOf("int16" to 1, "uint16" to 1, "enum16" to 1, "bitfield16" to 1, "sunssf" to 1, "acc16" to 1, "pad" to 1, "count" to 1, "int32" to 2, "uint32" to 2, "acc32" to 2, "float32" to 2, "bitfield32" to 2, "enum32" to 2, "acc64" to 4, "int64" to 4, "uint64" to 4)
    fun offsets(id: Int): Map<String, Int> {
        val m = Json.parse(File("dev-tests/models/model_$id.json").readText()).asMap()!!
        var off = 0; val o = HashMap<String, Int>()
        for (p in m["group"].asMap()!!["points"].asList()!!) { val pm = p.asMap()!!; o[pm["name"] as String] = off; off += pm["size"].asInt() ?: SIZE.getValue(pm["type"] as String) }
        return o
    }
    for ((ids, key) in listOf(listOf(101, 102, 103) to "int", listOf(111, 112, 113) to "float", listOf(1) to "common", listOf(120) to "nameplate"))
        for (id in ids) { val real = offsets(id); for ((n, off) in SunSpec.OFFSETS.getValue(key)) check(real[n] == off, "model $id $n: كودنا=$off الرسمي=${real[n]}") }

    println("== 3) Sungrow: قراءة أولى ثم فرق العداد + الهاش + المنصة + التدقيق ==")
    run {
        val cloud = FakeCloud(); val sg = SungrowSim(); val srv = FakeModbus(15021, sg::regs); val store = MemStore()
        val cfg = cfgFor(cloud, 15021)
        var r = Agent(cfg, profilesList(), store, TestEnv(), quiet).run()
        check(r.status == RunStatus.OK && r.sent == 1, "التشغيل الأول OK وأُرسلت قراءة (${r.message})")
        check(cloud.n8nBodies.size == 1 && cloud.n8nBodies[0]["serialNumber"] == "A2250012345", "n8n استلم السيريال A2250012345")
        check(cloud.n8nBodies[0]["productionNow"].asDouble() == 0.0 && cloud.n8nBodies[0]["productionTotal"].asLong() == 5000L, "first-reading: now=0 total=5000")
        check(cloud.ecoBodies.size == 1 && cloud.auditBodies.size == 1, "المنصة والتدقيق استلما")
        check(store.st.outbox.isEmpty(), "الـ outbox فارغ بعد التسليم")
        val ts1 = cloud.n8nBodies[0]["timestamp"] as String
        check(Regex("""\d{4}-\d\d-\d\dT\d\d:00:00\.000Z""").matches(ts1), "الطابع مقرَّب للساعة بصيغة toISOString: $ts1")

        // نفس الساعة ← تخطي
        r = Agent(cfg, profilesList(), store, TestEnv(), quiet).run()
        check(r.sent == 0, "نفس الفترة ← لا إرسال مكرر")

        // ساعة لاحقة (قراءة استثنائية): +5 kWh
        sg.totalKwh = 5005; sg.dailyRaw = 173
        r = Agent(cfg, profilesList(), store, TestEnv(), quiet).run(exceptional = true)
        check(r.sent == 1 && cloud.n8nBodies.size == 2, "قراءة ثانية أُرسلت")
        check(cloud.n8nBodies[1]["productionNow"].asDouble() == 5.0, "productionNow = فرق الإجمالي = 5 (${cloud.n8nBodies[1]["productionNow"]})")
        check(Json.stringify(cloud.n8nBodies[1]).contains("\"productionNow\":5,"), "يُرسَل 5 لا 5.0 (مطابقة JS)")

        // عداد نقص ← رفض
        sg.totalKwh = 4000
        r = Agent(cfg, profilesList(), store, TestEnv(), quiet).run(exceptional = true)
        check(r.sent == 0 && r.failures == 1 && cloud.n8nBodies.size == 2, "نقصان العداد ← لا إرسال")
        srv.close(); cloud.server.stop(0)
    }

    println("== 4) السيناريو الأهم: غياب ساعات ثم عودة (delta-since-previous) ==")
    run {
        val cloud = FakeCloud(); val ss = SunspecSim(); val srv = FakeModbus(15022, ss::regs); val store = MemStore()
        val cfg = cfgFor(cloud, 15022)
        var r = Agent(cfg, profilesList(), store, TestEnv(), quiet).run()
        check(r.sent == 1 && cloud.n8nBodies[0]["serialNumber"] == "7E1234AB", "SunSpec اكتُشف: السيريال 7E1234AB")
        check(cloud.n8nBodies[0]["productionTotal"].asLong() == 12346L, "total = round(12345.678) = 12346")
        val ev = cloud.auditBodies[0]["evidence"].asMap()!!
        check((ev["sunspec"].asMap()!!["manufacturer"]) == "SolarEdge" && ev["acPowerW"].asDouble() == 4200.0 && ev["statusName"] == "MPPT", "أدلة التدقيق: الماركة والقدرة والحالة")

        // نحاكي غياب 5 ساعات: نقدّم الحالة المحفوظة للخلف، والعداد يزيد 40 kWh
        val d = store.st.devices.getValue("7E1234AB")
        store.st.devices["7E1234AB"] = DeviceState(d.total, Agent.iso(System.currentTimeMillis() - 5 * 3600_000L), d.date, d.driver, null)
        ss.wh += 40_000
        r = Agent(cfg, profilesList(), store, TestEnv(), quiet).run(exceptional = true)
        check(r.sent == 1 && cloud.n8nBodies.size == 2, "عودة بعد الغياب: أُرسلت قراءة")
        check(Math.abs(cloud.n8nBodies[1]["productionNow"].asDouble()!! - 40.0) < 0.05, "الفرق 40 kWh عُوِّض كاملاً (${cloud.n8nBodies[1]["productionNow"]})")

        // قفزة مستحيلة (10 kW × ساعة واحدة) ← رفض
        ss.wh += 500_000
        r = Agent(cfg, profilesList(), store, TestEnv(), quiet).run(exceptional = true)
        check(r.sent == 0 && r.failures == 1, "قفزة 500 kWh خلال ثوانٍ ← مرفوضة بحاجز المعقولية")

        // الإنفرتر يختفي (خرج صاحب المشروع من الشبكة)
        srv.close(); Thread.sleep(100)
        val beforeState = store.st.devices.getValue("7E1234AB").total
        r = Agent(cfg.copy(hosts = listOf("127.0.0.1")), profilesList(), store, TestEnv(), quiet).run(exceptional = true)
        check(r.status == RunStatus.NO_DEVICES && store.st.devices.getValue("7E1234AB").total == beforeState, "غياب الإنفرتر: NO_DEVICES والحالة لم تتغير")
        cloud.server.stop(0)
    }

    println("== 5) صندوق الإرسال: المنصة معطّلة ثم تعود ==")
    run {
        val cloud = FakeCloud(); val sg = SungrowSim(); val srv = FakeModbus(15023, sg::regs); val store = MemStore()
        val cfg = cfgFor(cloud, 15023)
        cloud.ecoFail = true
        var r = Agent(cfg, profilesList(), store, TestEnv(), quiet).run()
        check(r.sent == 1 && r.pendingOutbox == 1 && store.st.outbox.size == 1, "n8n نجح، المنصة فشلت ← عنصر واحد معلّق بالـ outbox")
        check(store.st.devices.isNotEmpty(), "الحالة حُفظت رغم تعطل المنصة (لا تضيع القراءة)")
        check(cloud.auditBodies.size == 1, "جهة التدقيق استلمت رغم تعطل المنصة")
        cloud.ecoFail = false
        r = Agent(cfg, profilesList(), store, TestEnv(), quiet).run()
        check(store.st.outbox.isEmpty() && cloud.ecoBodies.size == 1, "عادت المنصة ← سُلِّم المعلّق وفرغ الـ outbox")
        srv.close(); cloud.server.stop(0)
    }

    println("== 6) مفتاح n8n خاطئ ← لا شيء يُرسل ولا تُحفظ حالة ==")
    run {
        val cloud = FakeCloud(); cloud.n8nKeyOk = "OTHER"; val sg = SungrowSim(); val srv = FakeModbus(15024, sg::regs); val store = MemStore()
        val r = Agent(cfgFor(cloud, 15024), profilesList(), store, TestEnv(), quiet).run()
        check(r.sent == 0 && r.failures >= 1 && store.st.devices.isEmpty() && cloud.ecoBodies.isEmpty(), "401 من n8n ← لا حالة ولا إرسال للمنصة")
        srv.close(); cloud.server.stop(0)
    }

    println("== 7) المسح التلقائي للنطاق (بدون hosts) ==")
    run {
        val cloud = FakeCloud(); val sg = SungrowSim(); val srv = FakeModbus(15025, sg::regs); val store = MemStore()
        val cfg = cfgFor(cloud, 15025, hosts = emptyList(), subnet = "127.0.0")
        val r = Agent(cfg, profilesList(), store, TestEnv(), quiet).run()
        check(r.sent == 1 && store.st.hosts.values.contains("127.0.0.1"), "المسح وجد الإنفرتر على 127.0.0.1 وأرسل")
        // تشغيل ثانٍ: مسار العنوان المعروف دون مسح كامل
        val logs = ArrayList<String>()
        Agent(cfg, profilesList(), store, TestEnv()) { logs.add(it) }.run(exceptional = true)
        check(logs.any { it.contains("على عنوانه المعروف") } && logs.none { it.contains("مسح 127.0.0.1-") }, "التشغيل الثاني استعمل آخر IP دون مسح كامل")
        srv.close(); cloud.server.stop(0)
    }

    println("== 8) رمز التفعيل ==")
    run {
        val json = """{"v":1,"name":"مصنع الاختبار","n8n":"https://n8n.example.com/webhook/x","key":"K1","eco":"https://eco.example.com/","ecoKey":"K2","audits":["https://a.example.com|AK"],"ssid":"Factory-WiFi","tz":"Asia/Jerusalem","interval":15}"""
        val tok = Activation.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        val c = Activation.parse(tok)
        check(c.n8nKey == "K1" && c.ecoUrl == "https://eco.example.com" && c.intervalMinutes == 15 && c.ssids == listOf("Factory-WiFi") && c.audits.size == 1 && c.customer == "مصنع الاختبار", "فك الرمز صحيح (مع Unicode)")
        for ((bad, why) in listOf("garbage" to "نص عشوائي", Activation.PREFIX + "!!!" to "base64 تالف", json.replace("https://n8n", "http://n8n") to "http غير آمن", json.replace("\"key\":\"K1\",", "") to "مفتاح ناقص"))
            check(try { Activation.parse(bad); false } catch (_: ActivationException) { true }, "يرفض: $why")
    }

    println("== 9) قرار الشبكة (NetGuard) ==")
    run {
        check(!NetGuard.decide(null, null, emptyList(), emptySet(), emptySet()).run, "بلا Wi-Fi ← لا عمل")
        check(NetGuard.decide("192.168.0.20", "Factory", listOf("Factory"), emptySet(), emptySet()).run, "SSID مطابق ← يعمل")
        check(!NetGuard.decide("10.0.0.5", "Cafe", listOf("Factory"), emptySet(), emptySet()).run, "SSID مختلف ← صمت")
        check(NetGuard.decide("192.168.0.20", null, listOf("Factory"), emptySet(), setOf("192.168.0")).run, "SSID غير مقروء + نفس النطاق المتعلَّم ← يعمل")
        check(!NetGuard.decide("192.168.8.4", null, listOf("Factory"), emptySet(), setOf("192.168.0")).run, "SSID غير مقروء + نطاق مختلف ← صمت")
        check(NetGuard.decide("192.168.0.20", "Factory-5G", emptyList(), setOf("Factory-5G"), setOf("192.168.0")).run, "شبكة متعلَّمة ← يعمل")
        check(NetGuard.decide("192.168.1.9", "Any", emptyList(), emptySet(), emptySet()).run, "أول تشغيل بلا قيود ← يعمل (للاكتشاف)")
    }

    println("== 10) حساب نطاق الشبكة ==")
    run {
        val n = Net.networkFor("192.168.1.45", 24)!!
        check(n.first == "192.168.1.1" && n.last == "192.168.1.254" && n.hosts.size == 253 && "192.168.1.45" !in n.hosts, "192.168.1.45/24 ← 1-254 بدون الجهاز نفسه")
        val m = Net.networkFor("10.5.3.9", 16)!!
        check(m.first == "10.5.3.1" && m.last == "10.5.3.254", "/16 ← يُمسح /24 المحيط فقط")
        check(Net.networkFor("192.168.1.9", 31) == null && Net.networkFor("169.254.1.1", 16) == null, "/31 و link-local مستبعدان")
    }

    println("== 11) مطابقة أرقام الإنفرترات التي أدخلها المستخدم ==")
    run {
        val cloud = FakeCloud(); val sg = SungrowSim(); val srv = FakeModbus(15026, sg::regs)
        var store = MemStore(); val base = cfgFor(cloud, 15026)
        var r = Agent(base.copy(serials = listOf("A2250012345", "ZZZ999")), profilesList(), store, TestEnv(), quiet).run()
        check(r.found == 1 && r.sent == 1 && r.missing == listOf("ZZZ999"), "رقمان مُدخلان، وُجد واحد ← يُرسل المطابق ويُبلَغ عن المفقود (${r.missing})")
        store = MemStore()
        val n = cloud.n8nBodies.size
        r = Agent(base.copy(serials = listOf("OTHER123")), profilesList(), store, TestEnv(), quiet).run()
        check(r.status == RunStatus.NO_DEVICES && r.sent == 0 && cloud.n8nBodies.size == n && r.ignored == listOf("A2250012345") && r.missing == listOf("OTHER123"), "إنفرتر موجود لكنه غير مُدخل ← يُتجاهل ولا يُرسل شيء")
        store = MemStore()
        r = Agent(base.copy(serials = listOf("a2250012345")), profilesList(), store, TestEnv(), quiet).run()
        check(r.sent == 1 && r.missing.isEmpty(), "المطابقة لا تتأثر بحالة الأحرف")
        srv.close(); cloud.server.stop(0)
    }

    println("\nالنتيجة: $pass نجح، $fail فشل")
    System.exit(if (fail == 0) 0 else 1)
}
