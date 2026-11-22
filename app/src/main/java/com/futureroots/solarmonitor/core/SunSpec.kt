package com.futureroots.solarmonitor.core

import javax.net.SocketFactory

class SunspecDevice(
    val unit: Int, val base: Int,
    val manufacturer: String, val model: String, val version: String, val serial: String,
    val invId: Int, val invAddr: Int, val invLen: Int, val ratedW: Double?, val models: List<Int>,
)

class SunspecReading(
    val totalKwh: Double, val wattsNow: Double?, val status: Int?, val statusName: String?,
    val ratedW: Double?, val notImplemented: List<String>, val whWords: List<Int>, val whScaleFactor: Int?,
)

/**
 * قارئ SunSpec Modbus العام (SolarEdge, SMA, Fronius, KACO, GoodWe ...).
 * الخريطة تُكتشف من الجهاز نفسه: "SunS" ← سلسلة النماذج ← Model 1 + نموذج الإنفرتر (101-103 / 111-113).
 */
object SunSpec {
    private val SUNS = intArrayOf(0x5375, 0x6e53)
    private val INVERTER_INT = setOf(101, 102, 103)
    private val INVERTER_FLOAT = setOf(111, 112, 113)

    /** إزاحات الحقول من ID=0 — تُتحقق في dev-tests مقابل json الرسمي (مجلد models). */
    val OFFSETS: Map<String, Map<String, Int>> = mapOf(
        "int" to mapOf("W" to 14, "W_SF" to 15, "WH" to 24, "WH_SF" to 26, "St" to 38),
        "float" to mapOf("W" to 22, "WH" to 32, "St" to 48),
        "common" to mapOf("Mn" to 2, "Md" to 18, "Vr" to 42, "SN" to 50),
        "nameplate" to mapOf("WRtg" to 3, "WRtg_SF" to 4),
    )
    private val OI = OFFSETS.getValue("int")
    private val OF = OFFSETS.getValue("float")
    private val OC = OFFSETS.getValue("common")
    private val ON = OFFSETS.getValue("nameplate")

    private const val NOT_IMPL_SF = -32768
    private val ST_NAMES = mapOf(1 to "OFF", 2 to "SLEEPING", 3 to "STARTING", 4 to "MPPT", 5 to "THROTTLED", 6 to "SHUTTING_DOWN", 7 to "FAULT", 8 to "STANDBY")

    private fun s16(v: Int) = if (v and 0x8000 != 0) v - 0x10000 else v
    private fun u32(w: IntArray, i: Int): Long = w[i].toLong() * 65536L + w[i + 1].toLong()
    private fun f32(w: IntArray, i: Int): Double = java.lang.Float.intBitsToFloat((w[i] shl 16) or w[i + 1]).toDouble()
    private fun str(w: List<Int>): String {
        val sb = StringBuilder()
        for (x in w) { sb.append(((x shr 8) and 0xff).toChar()); sb.append((x and 0xff).toChar()) }
        return sb.toString().replace("\u0000", "").trim()
    }
    private fun sfOk(sf: Int) = sf != NOT_IMPL_SF && sf in -10..10
    private fun notImpl16(v: Int) = v == 0x8000 || v == 0xffff
    private fun notImplAcc32(hi: Int, lo: Int) = (hi == 0 && lo == 0) || (hi == 0xffff && lo == 0xffff) || (hi == 0x8000 && lo == 0)
    private fun hex(hi: Int, lo: Int) = "0x" + (((hi.toLong() shl 16) + lo).toString(16).uppercase().padStart(8, '0'))

    /** null إن لم يكن الجهاز SunSpec؛ وصف كامل إن كان؛ أو يرمي خطأ إن كان SunSpec بلا نموذج إنفرتر. */
    fun identify(
        host: String, socketFactory: SocketFactory, port: Int = 502,
        units: List<Int> = listOf(1), bases: List<Int> = listOf(40000, 0, 50000), timeout: Int = 2000,
    ): SunspecDevice? {
        ModbusClient(host, port, timeout, socketFactory).use { c ->
            c.connect()
            for (unit in units) for (base in bases) {
                val w = try { c.request(unit, 3, base, 2) } catch (_: Exception) { continue }
                if (w[0] == SUNS[0] && w[1] == SUNS[1]) return describe(c, unit, base)
            }
            return null
        }
    }

    private class M(val id: Int, val len: Int, val addr: Int)

    private fun describe(c: ModbusClient, unit: Int, base: Int): SunspecDevice {
        val models = ArrayList<M>()
        var addr = base + 2
        for (i in 0 until 64) {
            val h = c.request(unit, 3, addr, 2)
            val id = h[0]; val len = h[1]
            if (id == 0xffff || (id == 0 && len == 0)) break
            models.add(M(id, len, addr)); addr += 2 + len
        }
        val common = models.find { it.id == 1 } ?: throw IllegalStateException("SunSpec بلا Model 1")
        val cw = c.request(unit, 3, common.addr, minOf(2 + common.len, 125)).toList()
        fun sl(a: Int, n: Int) = cw.subList(minOf(a, cw.size), minOf(a + n, cw.size))
        val mn = str(sl(OC.getValue("Mn"), 16))
        val md = str(sl(OC.getValue("Md"), 16))
        val vr = str(sl(OC.getValue("Vr"), 8))
        val sn = str(sl(OC.getValue("SN"), 16))
        if (sn.isEmpty()) throw IllegalStateException("SunSpec: السيريال فارغ")
        val inv = models.find { it.id in INVERTER_INT || it.id in INVERTER_FLOAT }
            ?: throw IllegalStateException("SunSpec بلا نموذج إنفرتر (101-103/111-113). النماذج الموجودة: ${models.joinToString(",") { it.id.toString() }}")
        var ratedW: Double? = null                       // Model 120: القدرة الاسمية لفحص معقولية الأرقام
        models.find { it.id == 120 }?.let { np ->
            try {
                val n = c.request(unit, 3, np.addr, 5)
                val rt = n[ON.getValue("WRtg")]; val sf = s16(n[ON.getValue("WRtg_SF")])
                if (rt != 0xffff && rt != 0 && sfOk(sf)) ratedW = rt * Math.pow(10.0, sf.toDouble())
            } catch (_: Exception) { }
        }
        return SunspecDevice(unit, base, mn, md, vr, sn, inv.id, inv.addr, inv.len, ratedW, models.map { it.id })
    }

    fun readInverter(host: String, dev: SunspecDevice, socketFactory: SocketFactory, port: Int = 502, timeout: Int = 4000): SunspecReading {
        ModbusClient(host, port, timeout, socketFactory).use { c ->
            c.connect()
            val w = c.request(dev.unit, 3, dev.invAddr, 2 + dev.invLen)
            if (w[0] != dev.invId) throw IllegalStateException("SunSpec: تغيّرت خريطة الجهاز (توقعنا نموذج ${dev.invId} فوجدنا ${w[0]})")
            val totalWh: Double
            var wattsNow: Double? = null
            var status: Int? = null
            val notImplemented = ArrayList<String>()
            val whWords: List<Int>
            val whSf: Int?
            if (dev.invId in INVERTER_INT) {
                if (w.size < 39) throw IllegalStateException("نموذج الإنفرتر أقصر من المتوقع")
                val wh = OI.getValue("WH")
                if (notImplAcc32(w[wh], w[wh + 1])) throw IllegalStateException("عداد الطاقة WH غير مُنفَّذ/غير صالح (${hex(w[wh], w[wh + 1])}) — لم يُحوَّل إلى إنتاج")
                val sf = s16(w[OI.getValue("WH_SF")])
                if (!sfOk(sf)) throw IllegalStateException("معامل قياس الطاقة WH_SF غير صالح ($sf)")
                totalWh = u32(w, wh) * Math.pow(10.0, sf.toDouble())
                val wsf = s16(w[OI.getValue("W_SF")])
                if (notImpl16(w[OI.getValue("W")]) || !sfOk(wsf)) notImplemented.add("W")
                else wattsNow = s16(w[OI.getValue("W")]) * Math.pow(10.0, wsf.toDouble())
                if (notImpl16(w[OI.getValue("St")])) notImplemented.add("St") else status = w[OI.getValue("St")]
                whWords = listOf(w[wh], w[wh + 1]); whSf = sf
            } else {
                if (w.size < 49) throw IllegalStateException("نموذج الإنفرتر أقصر من المتوقع")
                val wh = OF.getValue("WH")
                totalWh = f32(w, wh)
                if (totalWh.isNaN() || totalWh.isInfinite()) throw IllegalStateException("عداد الطاقة WH غير مُنفَّذ (NaN — ${hex(w[wh], w[wh + 1])}) — لم يُحوَّل إلى إنتاج")
                val p = f32(w, OF.getValue("W"))
                if (!p.isNaN() && !p.isInfinite()) wattsNow = p else notImplemented.add("W")
                if (notImpl16(w[OF.getValue("St")])) notImplemented.add("St") else status = w[OF.getValue("St")]
                whWords = listOf(w[wh], w[wh + 1]); whSf = null
            }
            if (totalWh.isNaN() || totalWh.isInfinite() || totalWh <= 0) throw IllegalStateException("قيمة العداد الإجمالي غير صالحة ($totalWh)")
            return SunspecReading(totalWh / 1000, wattsNow, status, status?.let { ST_NAMES[it] }, dev.ratedW, notImplemented, whWords, whSf)
        }
    }
}
