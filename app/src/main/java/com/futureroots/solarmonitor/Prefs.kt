package com.futureroots.solarmonitor

import android.content.Context
import com.futureroots.solarmonitor.core.Activation
import com.futureroots.solarmonitor.core.Config

/** إعدادات التطبيق وحالته البسيطة (SharedPreferences خاصة بالتطبيق). */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("solar", Context.MODE_PRIVATE)

    fun token(c: Context): String? = sp(c).getString("token", null)
    fun isActivated(c: Context) = config(c) != null

    fun config(c: Context): Config? {
        val t = token(c) ?: return null
<<<<<<< HEAD
        return try { Activation.parse(t).copy(intervalMinutes = interval(c), serials = serials(c)) } catch (_: Exception) { null }
=======
        return try { Activation.parse(t).copy(intervalMinutes = interval(c)) } catch (_: Exception) { null }
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
    }

    fun activate(c: Context, token: String, suggestedInterval: Int) {
        sp(c).edit().putString("token", token).putInt("interval", suggestedInterval).putBoolean("enabled", true)
            .remove("learnedSsids").remove("learnedSubnets").remove("lastRunAt").remove("status").apply()
    }

    fun deactivate(c: Context) { sp(c).edit().clear().apply() }

<<<<<<< HEAD
    fun serials(c: Context): List<String> = (sp(c).getString("serials", "") ?: "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    fun setSerials(c: Context, list: List<String>) { sp(c).edit().putString("serials", list.joinToString("\n")).apply() }

=======
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
    fun interval(c: Context): Int = sp(c).getInt("interval", 60).let { if (it == 15) 15 else 60 }
    fun setInterval(c: Context, minutes: Int) { sp(c).edit().putInt("interval", if (minutes == 15) 15 else 60).apply() }

    fun enabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) { sp(c).edit().putBoolean("enabled", v).apply() }

    // الشبكات التي نجح الاكتشاف عليها (تُتعلَّم تلقائياً): أساس قرار "أنا داخل شبكة المشروع أم لا"
    fun learnedSsids(c: Context): Set<String> = sp(c).getStringSet("learnedSsids", emptySet()) ?: emptySet()
    fun learnedSubnets(c: Context): Set<String> = sp(c).getStringSet("learnedSubnets", emptySet()) ?: emptySet()
    fun learn(c: Context, ssid: String?, subnet: String) {
        val e = sp(c).edit()
        if (ssid != null) e.putStringSet("learnedSsids", learnedSsids(c) + ssid)
        e.putStringSet("learnedSubnets", learnedSubnets(c) + subnet).apply()
    }

    fun status(c: Context): String = sp(c).getString("status", "بانتظار أول تشغيل…") ?: ""
    fun lastRunAt(c: Context): Long = sp(c).getLong("lastRunAt", 0L)
    fun setStatus(c: Context, text: String, ran: Boolean) {
        val e = sp(c).edit().putString("status", text)
        if (ran) e.putLong("lastRunAt", System.currentTimeMillis())
        e.apply()
    }
}
