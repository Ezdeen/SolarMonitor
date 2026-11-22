package com.futureroots.solarmonitor.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * JSON صغير بلا مكتبات (يعمل على أندرويد وعلى JVM العادي).
 * القيم: Map / List / String / Long / Double / Boolean / null.
 */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text.removePrefix("\uFEFF"))
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != p.s.length) throw IllegalArgumentException("JSON: نص زائد عند ${p.i}")
        return v
    }

    fun stringify(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    /** يطابق String(number) في JavaScript للأعداد العادية: 12.0 ← "12"، 12.3 ← "12.3" (مهم لتطابق الهاش مع n8n). */
    fun numToString(d: Double): String =
        if (d.isNaN() || d.isInfinite()) "null" else BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v.toString())
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> sb.append(numToString(v))
            is Float -> sb.append(numToString(v.toDouble()))
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString()); sb.append(':'); write(sb, x)
                }
                sb.append('}')
            }
            is IntArray -> write(sb, v.toList())
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, x)
                }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun fail(msg: String): Nothing = throw IllegalArgumentException("JSON: $msg عند $i")

        fun value(): Any? {
            if (i >= s.length) fail("نهاية مفاجئة")
            val c = s[i]
            return when {
                c == '{' -> obj()
                c == '[' -> arr()
                c == '"' -> str()
                c == 't' -> lit("true", true)
                c == 'f' -> lit("false", false)
                c == 'n' -> lit("null", null)
                c == '-' || c.isDigit() -> num()
                else -> fail("رمز غير متوقع '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("قيمة غير مفهومة")
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("مفتاح متوقع")
                val k = str(); ws()
                if (i >= s.length || s[i] != ':') fail("':' متوقعة")
                i++; ws()
                m[k] = value(); ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; return m }
                fail("',' أو '}' متوقعة")
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws(); l.add(value()); ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; return l }
                fail("',' أو ']' متوقعة")
            }
        }

        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("نص غير مغلق")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("هروب ناقص")
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            '/', '\\', '"' -> sb.append(e)
                            'u' -> {
                                if (i + 4 > s.length) fail("\\u ناقص")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> fail("هروب غير معروف")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val st = i
            while (i < s.length && s[i] in "+-0123456789.eE") i++
            val t = s.substring(st, i)
            return if (t.none { it == '.' || it == 'e' || it == 'E' }) (t.toLongOrNull() ?: t.toDouble()) else t.toDouble()
        }
    }
}

/** مطابقة سلوك JavaScript: Number(x.toFixed(d)) — تقريب نصف للأعلى على القيمة الثنائية الدقيقة. */
fun toFixed(x: Double, digits: Int): Double = BigDecimal(x).setScale(digits, RoundingMode.HALF_UP).toDouble()

/** مطابقة Math.round في JavaScript (نصف للأعلى). */
fun jsRound(x: Double): Long = Math.floor(x + 0.5).toLong()

@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>
fun Any?.asList(): List<Any?>? = (this as? List<*>)?.let { it as List<Any?> }
fun Any?.asStr(): String? = this as? String
fun Any?.asInt(): Int? = (this as? Number)?.toInt()
fun Any?.asLong(): Long? = (this as? Number)?.toLong()
fun Any?.asDouble(): Double? = (this as? Number)?.toDouble()
