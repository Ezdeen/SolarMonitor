package com.futureroots.solarmonitor

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** سجل نصي صغير (آخر ~24 كيلوبايت) يُعرض في الواجهة للتشخيص. لا يحوي مفاتيح. */
object LogStore {
    private const val MAX = 48_000
    private const val KEEP = 24_000
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private fun file(c: Context) = File(c.filesDir, "agent.log")

    @Synchronized
    fun add(c: Context, line: String) {
        try {
            val f = file(c)
            if (f.length() > MAX) f.writeText(f.readText().takeLast(KEEP))
            f.appendText("${fmt.format(Date())}  $line\n")
        } catch (_: Exception) { }
    }

    @Synchronized
    fun tail(c: Context, chars: Int = 6000): String = try { file(c).readText().takeLast(chars) } catch (_: Exception) { "" }

    @Synchronized
    fun clear(c: Context) { try { file(c).delete() } catch (_: Exception) { } }
}
