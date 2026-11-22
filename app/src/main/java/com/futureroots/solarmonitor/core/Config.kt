package com.futureroots.solarmonitor.core

import java.util.Base64

class Audit(val url: String, val key: String)

data class Config(
    val n8nUrl: String,
    val n8nKey: String,
    val ecoUrl: String,
    val ecoKey: String,
    val audits: List<Audit> = emptyList(),
    val customer: String? = null,
    val ssids: List<String> = emptyList(),          // شبكات Wi-Fi المسموحة (اختياري؛ تُتعلَّم تلقائياً أيضاً)
    val subnet: String? = null,                     // بديل المسح التلقائي للنطاق
    val hosts: List<String> = emptyList(),          // عناوين محددة بدل المسح
    val timeZone: String? = null,
    val intervalMinutes: Int = 60,                  // 15 أو 60: دقة الطابع الزمني وتكرار الإرسال
    val port: Int = 502,
    val unit: Int = 1,
    val offset: Int = 0,
    val scanTimeoutMs: Int = 1500,
    val scanBatch: Int = 20,
    val cooldownMs: Long = 500,
    val fullScanHours: Int = 24,
    val lostRescanMinutes: Int = 30,                // أقل فاصل بين مسحين كاملين بسبب جهاز غائب (حفظاً للبطارية)
    val maxOutboxDays: Int = 7,
    val allowUnverified: Boolean = false,
    val sunspec: Boolean = true,
    val sunspecUnits: List<Int> = listOf(1),
    val sunspecBases: List<Int> = listOf(40000, 0, 50000),
)

class ActivationException(msg: String) : Exception(msg)

/**
 * رمز التفعيل يُولَّد من منصتك لكل صاحب مشروع ويُعرض نصاً أو QR:
 *   ECO1.<base64url(JSON)>   حيث JSON:
 *   { "v":1, "name":"اسم العميل", "n8n":"https://…/webhook/…", "key":"…", "eco":"https://…", "ecoKey":"…",
 *     "audits":["https://…|key"], "ssid":"MyWifi" أو ["a","b"], "tz":"Asia/Jerusalem", "interval":60,
 *     "subnet":"192.168.0", "hosts":["192.168.0.50"], "units":[1,126], "bases":[40000,0,50000] }
 */
object Activation {
    const val PREFIX = "ECO1."

    fun parse(raw: String): Config {
        val t = raw.trim()
        val json = if (t.startsWith("{")) t else {
            if (!t.startsWith(PREFIX)) throw ActivationException("الرمز غير صالح: يجب أن يبدأ بـ $PREFIX")
            try { String(Base64.getUrlDecoder().decode(t.removePrefix(PREFIX).trim().trimEnd('=')), Charsets.UTF_8) }
            catch (_: Exception) { throw ActivationException("الرمز تالف (تعذّر فك الترميز)") }
        }
        val m = try { Json.parse(json).asMap() } catch (e: Exception) { null } ?: throw ActivationException("الرمز تالف (JSON غير صالح)")
        if (m["v"].asInt() != 1) throw ActivationException("إصدار الرمز غير مدعوم")
        fun need(k: String, label: String): String = m[k].asStr()?.trim()?.takeIf { it.isNotEmpty() } ?: throw ActivationException("الرمز ناقص: $label")
        fun https(u: String, label: String): String {
            if (!u.startsWith("https://")) throw ActivationException("$label يجب أن يبدأ بـ https://")
            return u.trimEnd('/')
        }
        val n8n = https(need("n8n", "رابط n8n"), "رابط n8n")
        val eco = https(need("eco", "رابط المنصة"), "رابط المنصة")
        val audits = (m["audits"].asList() ?: emptyList()).mapNotNull { it.asStr()?.trim()?.takeIf { s -> s.isNotEmpty() } }.map { s ->
            val i = s.indexOf('|')
            if (i < 1 || i == s.length - 1) throw ActivationException("صيغة جهة التدقيق يجب أن تكون url|key")
            Audit(https(s.substring(0, i), "رابط التدقيق"), s.substring(i + 1))
        }
        val ssidAny = m["ssid"]
        val ssids = when (ssidAny) { is String -> ssidAny.split(',').map { it.trim() }; is List<*> -> ssidAny.mapNotNull { (it as? String)?.trim() }; else -> emptyList() }.filter { it.isNotEmpty() }
        val interval = m["interval"].asInt()?.takeIf { it == 15 || it == 60 } ?: 60
        val units = m["units"].asList()?.mapNotNull { it.asInt() }?.filter { it in 0..247 }?.takeIf { it.isNotEmpty() }
        val bases = m["bases"].asList()?.mapNotNull { it.asInt() }?.filter { it >= 0 }?.takeIf { it.isNotEmpty() }
        var cfg = Config(
            n8nUrl = n8n, n8nKey = need("key", "مفتاح n8n"), ecoUrl = eco, ecoKey = need("ecoKey", "مفتاح المنصة"),
            audits = audits, customer = m["name"].asStr()?.trim()?.takeIf { it.isNotEmpty() },
            ssids = ssids, subnet = m["subnet"].asStr()?.trim()?.takeIf { it.isNotEmpty() },
            hosts = m["hosts"].asList()?.mapNotNull { it.asStr()?.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            timeZone = m["tz"].asStr()?.trim()?.takeIf { it.isNotEmpty() }, intervalMinutes = interval,
        )
        if (units != null) cfg = cfg.copy(sunspecUnits = units)
        if (bases != null) cfg = cfg.copy(sunspecBases = bases)
        cfg.timeZone?.let { try { java.time.ZoneId.of(it) } catch (_: Exception) { throw ActivationException("المنطقة الزمنية غير صالحة: $it") } }
        return cfg
    }
}
