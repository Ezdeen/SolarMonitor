package com.futureroots.solarmonitor.core

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory

class ModbusException(val code: Int) : IOException("Modbus exception $code")

/** عميل Modbus TCP بسيط: اتصال واحد، طلب واحد في اللحظة (يطابق ModbusClient في sunspec.mjs). */
class ModbusClient(
    private val host: String,
    private val port: Int = 502,
    private val timeoutMs: Int = 2500,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
) : AutoCloseable {
    private var sock: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private var tid = 0

    fun connect(): ModbusClient {
        val s = socketFactory.createSocket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            input = DataInputStream(s.getInputStream())
            output = s.getOutputStream()
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) { }
            throw if (e is SocketTimeoutException) IOException("connect timeout") else e
        }
        sock = s
        return this
    }

    /** يعيد كلمات السجلات (16 بت بلا إشارة). */
    fun request(unit: Int, fc: Int, addr: Int, qty: Int): IntArray {
        if (qty < 1 || qty > 125) throw IllegalArgumentException("qty خارج 1..125")
        if (addr < 0 || addr + qty > 65536) throw IllegalArgumentException("العنوان خارج النطاق")
        val out = output ?: throw IOException("غير متصل")
        val inp = input ?: throw IOException("غير متصل")
        tid = (tid + 1) and 0xffff
        val req = ByteArray(12)
        req[0] = (tid shr 8).toByte(); req[1] = tid.toByte()
        req[4] = 0; req[5] = 6
        req[6] = unit.toByte(); req[7] = fc.toByte()
        req[8] = (addr shr 8).toByte(); req[9] = addr.toByte()
        req[10] = (qty shr 8).toByte(); req[11] = qty.toByte()
        out.write(req); out.flush()

        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        try {
            while (true) {
                val hdr = ByteArray(7)
                inp.readFully(hdr)
                val len = ((hdr[4].toInt() and 0xff) shl 8) or (hdr[5].toInt() and 0xff)
                if (len < 2 || len > 254) throw IOException("طول إطار Modbus غير صالح ($len)")
                val body = ByteArray(len - 1)
                inp.readFully(body)
                val rtid = ((hdr[0].toInt() and 0xff) shl 8) or (hdr[1].toInt() and 0xff)
                if (rtid != tid) {                      // رد متأخر لطلب سابق: نتجاهله
                    if (System.nanoTime() > deadline) throw IOException("timeout")
                    continue
                }
                val fcr = body[0].toInt() and 0xff
                if (fcr and 0x80 != 0) throw ModbusException(body[1].toInt() and 0xff)
                val bc = body[1].toInt() and 0xff
                if (body.size < 2 + bc) throw IOException("رد Modbus ناقص")
                return IntArray(bc / 2) { i -> ((body[2 + i * 2].toInt() and 0xff) shl 8) or (body[3 + i * 2].toInt() and 0xff) }
            }
        } catch (e: SocketTimeoutException) {
            throw IOException("timeout")
        } catch (e: EOFException) {
            throw IOException("الاتصال أُغلق")
        }
    }

    override fun close() {
        try { sock?.close() } catch (_: Exception) { }
    }
}
