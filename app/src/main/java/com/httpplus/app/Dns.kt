package com.httpplus.app

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Consulta DNS (registro A) por TCP a través del túnel SSH, así no se filtra nada fuera del túnel. */
object Dns {
    private class Entry(val ip: String, val until: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    fun isIp(h: String): Boolean = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(h) || h.contains(':')

    fun resolve(name: String, server: String, tunnel: SshTunnel): String? {
        val key = name.lowercase()
        cache[key]?.let { if (it.until > System.currentTimeMillis()) return it.ip }
        var conn: TunnelConn? = null
        try {
            val bos = ByteArrayOutputStream()
            val d = DataOutputStream(bos)
            d.writeShort((Math.random() * 65535).toInt())
            d.writeShort(0x0100)
            d.writeShort(1); d.writeShort(0); d.writeShort(0); d.writeShort(0)
            for (label in key.split(".")) {
                val b = label.toByteArray()
                d.writeByte(b.size); d.write(b)
            }
            d.writeByte(0); d.writeShort(1); d.writeShort(1)
            val q = bos.toByteArray()

            conn = tunnel.open(server, 53)
            conn.output.write(byteArrayOf((q.size shr 8).toByte(), q.size.toByte()))
            conn.output.write(q)
            conn.output.flush()

            val lenB = ByteArray(2)
            readFully(conn, lenB)
            val len = ((lenB[0].toInt() and 0xFF) shl 8) or (lenB[1].toInt() and 0xFF)
            if (len < 12 || len > 4096) return null
            val r = ByteArray(len)
            readFully(conn, r)

            val an = ((r[6].toInt() and 0xFF) shl 8) or (r[7].toInt() and 0xFF)
            var pos = 12
            pos = skipName(r, pos) + 4                 // pregunta: nombre + tipo + clase
            for (i in 0 until an) {
                pos = skipName(r, pos)
                if (pos + 10 > r.size) return null
                val type = ((r[pos].toInt() and 0xFF) shl 8) or (r[pos + 1].toInt() and 0xFF)
                val rdlen = ((r[pos + 8].toInt() and 0xFF) shl 8) or (r[pos + 9].toInt() and 0xFF)
                pos += 10
                if (type == 1 && rdlen == 4 && pos + 4 <= r.size) {
                    val ip = "${r[pos].toInt() and 0xFF}.${r[pos + 1].toInt() and 0xFF}.${r[pos + 2].toInt() and 0xFF}.${r[pos + 3].toInt() and 0xFF}"
                    cache[key] = Entry(ip, System.currentTimeMillis() + 300_000)
                    return ip
                }
                pos += rdlen
            }
        } catch (_: Exception) {
        } finally {
            conn?.close()
        }
        return null
    }

    private fun skipName(b: ByteArray, start: Int): Int {
        var p = start
        while (p < b.size) {
            val l = b[p].toInt() and 0xFF
            if (l == 0) return p + 1
            if (l and 0xC0 == 0xC0) return p + 2
            p += l + 1
        }
        return p
    }

    private fun readFully(c: TunnelConn, buf: ByteArray) {
        var o = 0
        while (o < buf.size) {
            val n = c.input.read(buf, o, buf.size - o)
            if (n < 0) throw java.io.IOException("DNS cortado")
            o += n
        }
    }
}
