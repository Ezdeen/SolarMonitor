package com.futureroots.solarmonitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.futureroots.solarmonitor.core.AgentEnv
import com.futureroots.solarmonitor.core.LocalNet
import com.futureroots.solarmonitor.core.Net
import java.net.Inet4Address
import javax.net.SocketFactory

class WifiState(val network: Network, val ip: String, val prefix: Int, val ssid: String?)

object WifiProbe {
    /** شبكة Wi-Fi الحالية (إن وُجدت) مع IP الهاتف وطول البادئة؛ SSID قد يكون null إن لم تُمنح صلاحية الموقع. */
    @Suppress("DEPRECATION")
    fun current(ctx: Context): WifiState? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val la = cm.getLinkProperties(n)?.linkAddresses?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: continue
            return WifiState(n, la.address.hostAddress ?: continue, la.prefixLength, readSsid(ctx))
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun readSsid(ctx: Context): String? = try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val s = wm.connectionInfo?.ssid?.trim('"')
        if (s.isNullOrEmpty() || s == "<unknown ssid>" || s == "0x") null else s
    } catch (_: Exception) { null }
}

/** يربط كل مقابس Modbus بشبكة Wi-Fi صراحةً (حتى لا يمر الاتصال المحلي عبر بيانات الجوال حين لا يوجد إنترنت على الراوتر). */
class AndroidEnv(private val wifi: WifiState) : AgentEnv {
    override val socketFactory: SocketFactory get() = wifi.network.socketFactory
    override fun localNetworks(): List<LocalNet> = listOfNotNull(Net.networkFor(wifi.ip, wifi.prefix, "wlan"))
    override fun readArp(): Map<String, String> = Net.readArpProc()
}
