package com.futureroots.solarmonitor.core

import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DeviceState(val total: Double, val timestamp: String, val date: String, val driver: String, val daily: Double?)

class OutboxItem(
    val kind: String,                 // "platform" | "audit"
    val label: String, val createdAt: String,
    val url: String?, val key: String?,
    val body: Map<String, Any?>,
    var attempts: Int = 0,
)

/** الحالة المحفوظة بين التشغيلات: آخر عداد لكل إنفرتر (أساس delta-since-previous) + IP/MAC + صندوق الإرسال. */
class State {
    val devices = LinkedHashMap<String, DeviceState>()
    val hosts = LinkedHashMap<String, String>()
    val macs = LinkedHashMap<String, String>()
    val profileHints = LinkedHashMap<String, String>()
    val outbox = ArrayList<OutboxItem>()
    var lastFullScan = 0L
    var lastLostScan = 0L
    var lastScan = 0L

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "devices" to devices.mapValues { (_, d) -> linkedMapOf("total" to d.total, "timestamp" to d.timestamp, "date" to d.date, "driver" to d.driver, "daily" to d.daily) },
        "hosts" to hosts, "macs" to macs, "profileHints" to profileHints,
        "outbox" to outbox.map { linkedMapOf("kind" to it.kind, "label" to it.label, "createdAt" to it.createdAt, "url" to it.url, "key" to it.key, "body" to it.body, "attempts" to it.attempts) },
        "lastFullScan" to lastFullScan, "lastLostScan" to lastLostScan, "lastScan" to lastScan,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>): State {
            val s = State()
            m["devices"].asMap()?.forEach { (sn, v) ->
                val d = v.asMap() ?: return@forEach
                val total = d["total"].asDouble() ?: return@forEach
                s.devices[sn] = DeviceState(total, d["timestamp"].asStr() ?: return@forEach, d["date"].asStr() ?: "", d["driver"].asStr() ?: "", d["daily"].asDouble())
            }
            m["hosts"].asMap()?.forEach { (k, v) -> v.asStr()?.let { s.hosts[k] = it } }
            m["macs"].asMap()?.forEach { (k, v) -> v.asStr()?.let { s.macs[k] = it } }
            m["profileHints"].asMap()?.forEach { (k, v) -> v.asStr()?.let { s.profileHints[k] = it } }
            m["outbox"].asList()?.forEach { x ->
                val o = x.asMap() ?: return@forEach
                s.outbox.add(OutboxItem(o["kind"].asStr() ?: return@forEach, o["label"].asStr() ?: "", o["createdAt"].asStr() ?: return@forEach,
                    o["url"].asStr(), o["key"].asStr(), o["body"].asMap() ?: return@forEach, o["attempts"].asInt() ?: 0))
            }
            s.lastFullScan = m["lastFullScan"].asLong() ?: 0L
            s.lastLostScan = m["lastLostScan"].asLong() ?: 0L
            s.lastScan = m["lastScan"].asLong() ?: 0L
            return s
        }
    }
}

interface StateStore {
    fun load(): State
    fun save(state: State)
}

class FileStateStore(private val file: File) : StateStore {
    override fun load(): State = try { State.fromMap(Json.parse(file.readText()).asMap() ?: emptyMap()) } catch (_: Exception) { State() }
    override fun save(state: State) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(Json.stringify(state.toMap()))
        try { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: Exception) { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}

class HttpStatusException(val status: Int, msg: String) : IOException(msg)

object Http {
    fun postJson(url: String, headers: Map<String, String>, body: Any?, readTimeoutMs: Int = 60000): Map<String, Any?> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000; conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(Json.stringify(body).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val data: Map<String, Any?> = try { Json.parse(text).asMap() ?: mapOf("raw" to text.take(300)) } catch (_: Exception) { mapOf("raw" to text.take(300)) }
            if (code !in 200..299) throw HttpStatusException(code, "HTTP $code $url: ${Json.stringify(data).take(300)}")
            return data
        } finally { conn.disconnect() }
    }

    /**
     * إعادة محاولة لأخطاء n8n المؤقتة فقط (HTTP 5xx أو رفض الاتصال). لا نعيد عند timeout ولا عند 4xx:
     * الـ timeout غامض (قد يكون n8n سجّل على Hedera فعلاً) فتكرار الطلب قد يُنتج معاملة مزدوجة.
     */
    fun postWithRetry(url: String, headers: Map<String, String>, body: Any?, delaysMs: List<Long> = listOf(5000, 15000), log: (String) -> Unit = {}): Map<String, Any?> {
        var i = 0
        while (true) {
            try { return postJson(url, headers, body) }
            catch (e: IOException) {
                val transient = (e is HttpStatusException && e.status >= 500) || e is ConnectException
                if (!transient || i >= delaysMs.size) throw e
                log("  n8n فشل مؤقتاً (${e.message?.take(80)}) — إعادة المحاولة ${i + 1}/${delaysMs.size} بعد ${delaysMs[i] / 1000}ث")
                Thread.sleep(delaysMs[i]); i++
            }
        }
    }
}
