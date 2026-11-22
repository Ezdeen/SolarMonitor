package com.futureroots.solarmonitor.core

import javax.net.SocketFactory

class Field(val address: Int, val words: Int, val scale: Double, val wordOrder: String)

class Profile(
    val id: String, val brand: String, val model: String, val status: String, val priority: Int,
    val function: Int, val unit: Int?, val addressOffset: Int?,
    val serial: Field, val serialRegex: Regex, val total: Field, val daily: Field?, val ratedW: Double?,
)

class ProfileMatch(val profile: Profile, val serial: String, val unit: Int, val offset: Int)

class ProfileReading(
    val daily: Double?, val dailyInvalid: Boolean, val totalKwh: Double,
    val rawDaily: IntArray?, val rawTotal: IntArray,
)

class LoadedProfiles(val list: List<Profile>, val problems: List<String>)

/** مكتبة ملفات التعريف: خريطة سجلات Modbus لكل ماركة في ملف JSON (assets/profiles). SunSpec يُجرَّب قبلها. */
object Profiles {
    private const val BUILTIN_JSON = """{"id":"sungrow-sg-rt","brand":"Sungrow","model":"SG RT series (مدمج)","status":"verified","priority":10,
      "function":4,"unit":1,"addressOffset":-1,
      "serial":{"address":4990,"words":6,"pattern":"^[A-Z0-9]{6,20}$"},
      "daily":{"address":5003,"words":1,"scale":0.1},"total":{"address":5004,"words":2,"wordOrder":"low-high","scale":1}}"""

    private fun isInt(v: Int?, lo: Int, hi: Int) = v != null && v in lo..hi

    private fun field(f: Map<String, Any?>?, name: String, optional: Boolean = false): Field? {
        if (f == null) { if (optional) return null; throw IllegalArgumentException("الحقل $name مطلوب") }
        val address = f["address"].asInt()
        val words = f["words"].asInt()
        if (!isInt(address, 0, 65535)) throw IllegalArgumentException("$name.address يجب أن يكون عدداً صحيحاً 0..65535")
        if (!isInt(words, 1, if (name == "serial") 30 else 2)) throw IllegalArgumentException("$name.words خارج المسموح")
        val scale = if (f["scale"] == null) 1.0 else (f["scale"].asDouble() ?: throw IllegalArgumentException("$name.scale يجب أن يكون رقماً موجباً"))
        if (!(scale > 0)) throw IllegalArgumentException("$name.scale يجب أن يكون رقماً موجباً")
        val wo = f["wordOrder"].asStr() ?: "high-low"
        if (wo != "high-low" && wo != "low-high") throw IllegalArgumentException("$name.wordOrder غير مفهوم")
        return Field(address!!, words!!, scale, wo)
    }

    fun validate(p: Map<String, Any?>): Profile {
        val id = p["id"].asStr()
        if (id.isNullOrEmpty()) throw IllegalArgumentException("id مطلوب")
        val status = p["status"].asStr()
        if (status != "verified" && status != "unverified") throw IllegalArgumentException("status يجب أن يكون verified أو unverified")
        val fn = p["function"].asInt()
        if (fn != 3 && fn != 4) throw IllegalArgumentException("function يجب أن يكون 3 أو 4")
        val unit = p["unit"].asInt()
        if (unit != null && !isInt(unit, 0, 247)) throw IllegalArgumentException("unit خارج 0..247")
        val off = p["addressOffset"].asInt()
        if (off != null && !isInt(off, -10, 10)) throw IllegalArgumentException("addressOffset غير معقول")
        val serialMap = p["serial"].asMap()
        val serial = field(serialMap, "serial")!!
        val re = try { Regex(serialMap?.get("pattern").asStr() ?: "^[A-Z0-9]{6,20}$") } catch (_: Exception) { throw IllegalArgumentException("serial.pattern تعبير نمطي غير صالح") }
        val total = field(p["total"].asMap(), "total")!!
        val daily = field(p["daily"].asMap(), "daily", optional = true)
        return Profile(
            id, p["brand"].asStr() ?: id, p["model"].asStr() ?: "", status, p["priority"].asInt() ?: 100,
            fn!!, unit, off, serial, re, total, daily, p["ratedW"].asDouble(),
        )
    }

    /** files: (اسم الملف، نصه). الملفات التي تبدأ بـ _ أو لا تنتهي بـ .json تُتجاهل. */
    fun load(files: List<Pair<String, String>>): LoadedProfiles {
        val list = ArrayList<Profile>(); val problems = ArrayList<String>()
        for ((name, text) in files) {
            if (!name.endsWith(".json") || name.startsWith("_")) continue
            try { list.add(validate(Json.parse(text).asMap() ?: throw IllegalArgumentException("ليس كائن JSON"))) }
            catch (e: Exception) { problems.add("$name: ${e.message}") }
        }
        val seen = HashSet<String>(); val uniq = ArrayList<Profile>()
        for (p in list) { if (!seen.add(p.id)) problems.add("${p.id}: id مكرر — تجاهلنا الثاني") else uniq.add(p) }
        if (uniq.isEmpty()) {
            problems.add("لا توجد ملفات تعريف صالحة — استُعمل Sungrow المدمج")
            return LoadedProfiles(listOf(validate(Json.parse(BUILTIN_JSON).asMap()!!)), problems)
        }
        uniq.sortWith(compareBy<Profile> { it.priority }.thenBy { it.id })
        return LoadedProfiles(uniq, problems)
    }

    private fun ascii(w: IntArray): String {
        val sb = StringBuilder()
        for (x in w) { sb.append(((x shr 8) and 0xff).toChar()); sb.append((x and 0xff).toChar()) }
        return sb.toString().replace(Regex("[\u0000 ]+"), "").trim()
    }

    /** قيم "غير مُنفَّذة": 0xFFFF لسجل واحد، 0xFFFFFFFF / 0x80000000 لسجلين — لا تُحوَّل إلى أرقام إنتاج. */
    private fun unimpl(raw: Long, f: Field) = if (f.words == 1) raw == 0xffffL else raw == 0xffffffffL || raw == 0x80000000L
    private fun decode(w: IntArray, f: Field): Long =
        if (f.words == 1) w[0].toLong()
        else if (f.wordOrder == "low-high") w[0].toLong() + w[1].toLong() * 65536L
        else w[0].toLong() * 65536L + w[1].toLong()

    /** يجرّب الملفات بالترتيب (الذي نجح سابقاً على هذا العنوان أولاً)؛ أول ملف يعطي سيريال مفهوماً يُعتمد. */
    fun identify(
        host: String, list: List<Profile>, socketFactory: SocketFactory,
        hint: String? = null, port: Int = 502, timeout: Int = 6000, defaultUnit: Int = 1, defaultOffset: Int = 0,
    ): ProfileMatch {
        val ordered = if (hint != null) list.filter { it.id == hint } + list.filter { it.id != hint } else list
        val errors = ArrayList<String>()
        ModbusClient(host, port, timeout, socketFactory).use { c ->
            c.connect()
            for (p in ordered) {
                try {
                    val unit = p.unit ?: defaultUnit; val offset = p.addressOffset ?: defaultOffset
                    val sn = ascii(c.request(unit, p.function, p.serial.address + offset, p.serial.words))
                    if (!p.serialRegex.containsMatchIn(sn)) throw IllegalStateException("سيريال غير مفهوم \"${sn.take(24)}\"")
                    return ProfileMatch(p, sn, unit, offset)
                } catch (e: Exception) { errors.add("${p.id}: ${e.message}") }
            }
        }
        throw IllegalStateException(if (errors.isEmpty()) "لا توجد ملفات تعريف" else errors.joinToString(" | "))
    }

    fun readDevice(host: String, m: ProfileMatch, socketFactory: SocketFactory, port: Int = 502, timeout: Int = 6000): ProfileReading {
        val p = m.profile
        ModbusClient(host, port, timeout, socketFactory).use { c ->
            c.connect()
            var daily: Double? = null; var rawDaily: IntArray? = null; var dailyInvalid = false
            if (p.daily != null) {            // العداد اليومي للتحقق/العرض فقط؛ قيمته غير المُنفَّذة لا توقف القراءة
                rawDaily = c.request(m.unit, p.function, p.daily.address + m.offset, p.daily.words)
                val rd = decode(rawDaily, p.daily)
                if (unimpl(rd, p.daily)) dailyInvalid = true else daily = toFixed(rd * p.daily.scale, 1)
            }
            val rawTotal = c.request(m.unit, p.function, p.total.address + m.offset, p.total.words)
            val rt = decode(rawTotal, p.total)
            if (unimpl(rt, p.total)) throw IllegalStateException("العداد الإجمالي غير مُنفَّذ (قيمة $rt) — لم يُحوَّل إلى إنتاج")
            val totalKwh = rt * p.total.scale
            if (totalKwh.isNaN() || totalKwh.isInfinite() || totalKwh < 0) throw IllegalStateException("قيمة العداد الإجمالي غير صالحة ($totalKwh)")
            return ProfileReading(daily, dailyInvalid, totalKwh, rawDaily, rawTotal)
        }
    }
}
