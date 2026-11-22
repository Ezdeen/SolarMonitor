package com.futureroots.solarmonitor.core

import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class LocalNet(val iface: String, val ip: String?, val prefix: Int, val first: String, val last: String, val hosts: List<String>)

object Net {
    fun ipToInt(ip: String): Long = ip.split('.').fold(0L) { a, o -> a * 256 + o.toLong() }
    fun intToIp(n: Long): String = listOf(3, 2, 1, 0).joinToString(".") { ((n shr (8 * it)) and 0xff).toString() }
    fun subnet24(ip: String): String = ip.substringBeforeLast('.')

    /** يستنتج نطاق المسح من IP هذا الجهاز (مثلاً 192.168.1.45 ← 192.168.1.1-254) دون تدخل يدوي. */
    fun networkFor(ip: String, prefix: Int, iface: String = "wlan"): LocalNet? {
        if (prefix < 1 || prefix > 30 || ip.startsWith("169.254.")) return null
        val me = ipToInt(ip)
        val first: Long; val last: Long
        if (prefix >= 24) {
            val size = 1L shl (32 - prefix); val net = me - (me % size)
            first = net + 1; last = net + size - 2
        } else {                                   // شبكة أكبر من /24: نمسح /24 المحيطة بنا فقط
            val net = me - (me % 256); first = net + 1; last = net + 254
        }
        val hosts = (first..last).filter { it != me }.map { intToIp(it) }
        return LocalNet(iface, ip, prefix, intToIp(first), intToIp(last), hosts)
    }

    /** override: "192.168.1" أو "192.168.1.0/24". */
    fun fromSubnet(override: String): LocalNet {
        val base = override.replace(Regex("/\\d+$"), "").split('.').take(3).joinToString(".")
        return LocalNet("SCAN_SUBNET", null, 24, "$base.1", "$base.254", (1..254).map { "$base.$it" })
    }

    private fun normMac(m: String) = m.lowercase().replace('-', ':')
    private val ARP_RE = Regex("""(\b\d{1,3}(?:\.\d{1,3}){3}\b)[^\n]*?\b([0-9a-f]{2}(?::[0-9a-f]{2}){5})\b""", RegexOption.IGNORE_CASE)

    fun parseArp(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (line in text.lines()) {
            val m = ARP_RE.find(line) ?: continue
            val ip = m.groupValues[1]; val mac = normMac(m.groupValues[2])
            if (mac == "ff:ff:ff:ff:ff:ff" || mac == "00:00:00:00:00:00" || mac.startsWith("01:00:5e")) continue
            if ((ip.substringBefore('.').toIntOrNull() ?: 0) >= 224) continue
            map[ip] = mac
        }
        return map
    }

    /** جدول ARP: يعمل على أندرويد 9 وما دون فقط؛ من 10 فما فوق يكون فارغاً (الحل: إعادة مسح النطاق). */
    fun readArpProc(): Map<String, String> = try { parseArp(File("/proc/net/arp").readText()) } catch (_: Exception) { emptyMap() }

    fun macToIp(arp: Map<String, String>, mac: String?): String? {
        val want = normMac(mac ?: "")
        return arp.entries.firstOrNull { it.value == want }?.key
    }

    /** بايت UDP واحد لكل عنوان فيُجبر النظام على ARP فيمتلئ الجدول (إن كان قابلاً للقراءة). */
    fun pokeArp(ips: List<String>, waitMs: Long = 1000) {
        try {
            DatagramSocket().use { s ->
                for (ip in ips) try { s.send(DatagramPacket(byteArrayOf(0), 1, InetAddress.getByName(ip), 9)) } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        Thread.sleep(waitMs)
    }
}

/** بوابة لكل مضيف: اتصال Modbus واحد في اللحظة + تهدئة بعد آخر استخدام. */
class Gate(private val cooldownMs: Long = 500) {
    private val locks = ConcurrentHashMap<String, Any>()
    private val lastUse = ConcurrentHashMap<String, Long>()

    fun touch(host: String) { lastUse[host] = System.currentTimeMillis() }

    fun <T> run(host: String, fn: () -> T): T = synchronized(locks.getOrPut(host) { Any() }) {
        val wait = (lastUse[host] ?: 0L) + cooldownMs - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        try { fn() } finally { lastUse[host] = System.currentTimeMillis() }
    }
}

/** قرار العمل حسب الشبكة الحالية: خارج شبكة المشروع ← ننتظر بصمت دون مسح ولا استهلاك بطارية. */
object NetGuard {
    class Decision(val run: Boolean, val reason: String)

    fun decide(ip: String?, ssid: String?, expectedSsids: List<String>, learnedSsids: Set<String>, learnedSubnets: Set<String>): Decision {
        if (ip == null) return Decision(false, "غير متصل بشبكة Wi-Fi")
        val want = (expectedSsids + learnedSsids).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (ssid != null && want.isNotEmpty())
            return if (want.any { it.equals(ssid, ignoreCase = true) }) Decision(true, "") else Decision(false, "خارج شبكة المشروع ($ssid)")
        // الـ SSID غير مقروء (لا صلاحية موقع) أو لا شبكة معروفة بعد ← نعتمد النطاق المتعلَّم
        if (learnedSubnets.isNotEmpty() && Net.subnet24(ip) !in learnedSubnets) return Decision(false, "شبكة مختلفة عن شبكة المشروع (${Net.subnet24(ip)}.x)")
        return Decision(true, "")
    }
}
