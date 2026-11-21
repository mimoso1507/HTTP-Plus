package com.httpplus.app

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Proxy local HTTP (CONNECT y http://) y SOCKS5 que saca todo por el túnel SSH. Escucha en todas las interfaces. */
class ProxyServer(private val port: Int, private val tunnel: SshTunnel, private val cfg: Config) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val active = AtomicInteger(0)
    private val filter = Filter(cfg)
    @Volatile private var stopped = false

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
        server = ss
        pool.execute {
            while (!stopped) {
                try {
                    val c = ss.accept()
                    pool.execute { handle(c) }
                } catch (_: IOException) {
                    if (stopped) break
                }
            }
        }
    }

    fun stop() {
        stopped = true
        try { server?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun handle(c: Socket) {
        Bus.set(clients = active.incrementAndGet())
        try {
            c.tcpNoDelay = true
            c.soTimeout = 30000
            val inp = BufferedInputStream(c.getInputStream(), 16384)
            val out = c.getOutputStream()
            inp.mark(1)
            val first = inp.read()
            if (first < 0) return
            inp.reset()
            if (first == 5) socks5(c, inp, out) else http(c, inp, out)
        } catch (_: Exception) {
        } finally {
            try { c.close() } catch (_: Exception) {}
            Bus.set(clients = active.decrementAndGet())
        }
    }

    // Resuelve (con el DNS elegido, por el túnel) y abre la conexión dentro del túnel
    private fun openRemote(host: String, port: Int): TunnelConn {
        if (filter.blocked(host)) throw IOException("bloqueado: $host")
        var target = host
        if (cfg.dns.isNotBlank() && !Dns.isIp(host)) {
            Dns.resolve(host, cfg.dns.trim(), tunnel)?.let { target = it }
        }
        return tunnel.open(target, port)
    }

    private fun relay(c: Socket, clientIn: InputStream, clientOut: OutputStream, conn: TunnelConn) {
        c.soTimeout = 0
        val t = Thread {
            copy(conn.input, clientOut, Stats.down)
            try { c.shutdownOutput() } catch (_: Exception) {}
        }
        t.isDaemon = true
        t.start()
        copy(clientIn, conn.output, Stats.up)
        conn.close()
        try { t.join(2000) } catch (_: Exception) {}
    }

    private fun copy(a: InputStream, b: OutputStream, counter: java.util.concurrent.atomic.AtomicLong) {
        val buf = ByteArray(16384)
        try {
            while (true) {
                val n = a.read(buf)
                if (n < 0) break
                b.write(buf, 0, n)
                b.flush()
                counter.addAndGet(n.toLong())
            }
        } catch (_: Exception) {}
    }

    // ---- HTTP ----
    private fun readLine(inp: InputStream): String? {
        val bo = ByteArrayOutputStream()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (bo.size() == 0) null else bo.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) bo.write(b)
            if (bo.size() > 16384) throw IOException("línea muy larga")
        }
        return bo.toString("ISO-8859-1")
    }

    private fun http(c: Socket, inp: InputStream, out: OutputStream) {
        val reqLine = readLine(inp) ?: return
        val headers = ArrayList<String>()
        while (true) {
            val l = readLine(inp) ?: break
            if (l.isEmpty()) break
            headers.add(l)
        }
        val parts = reqLine.split(" ")
        if (parts.size < 3) return
        val method = parts[0]
        val target = parts[1]
        val ver = parts[2]

        if (method.equals("CONNECT", true)) {
            val i = target.lastIndexOf(':')
            val host = if (i > 0) target.substring(0, i) else target
            val port = if (i > 0) target.substring(i + 1).toIntOrNull() ?: 443 else 443
            val conn = try { openRemote(host, port) } catch (e: Exception) {
                out.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray()); return
            }
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            out.flush()
            relay(c, inp, out, conn)
            return
        }

        // http://host[:puerto]/ruta
        val m = Regex("^http://([^/:]+)(?::(\\d+))?(/.*)?$", RegexOption.IGNORE_CASE).find(target)
        if (m == null) { out.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray()); return }
        val host = m.groupValues[1]
        val port = m.groupValues[2].toIntOrNull() ?: 80
        val path = m.groupValues[3].ifEmpty { "/" }
        val conn = try { openRemote(host, port) } catch (e: Exception) {
            out.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray()); return
        }
        val sb = StringBuilder()
        sb.append(method).append(' ').append(path).append(' ').append(ver).append("\r\n")
        for (h in headers) {
            val k = h.substringBefore(':').trim().lowercase()
            if (k == "proxy-connection" || k == "connection" || k == "keep-alive") continue
            sb.append(h).append("\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        conn.output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        conn.output.flush()
        relay(c, inp, out, conn)
    }

    // ---- SOCKS5 ----
    private fun readN(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var o = 0
        while (o < n) {
            val r = inp.read(b, o, n - o)
            if (r < 0) throw IOException("cortado")
            o += r
        }
        return b
    }

    private fun socks5(c: Socket, inp: InputStream, out: OutputStream) {
        val h = readN(inp, 2)
        readN(inp, h[1].toInt() and 0xFF)                 // métodos ofrecidos
        out.write(byteArrayOf(5, 0)); out.flush()         // sin autenticación
        val r = readN(inp, 4)
        if (r[0].toInt() != 5 || r[1].toInt() != 1) {     // solo CONNECT
            if (r[1].toInt() == 3) Bus.udpAttempt()          // UDP ASSOCIATE: alguien quiere usar UDP/QUIC
            out.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); return
        }
        val host = when (r[3].toInt()) {
            1 -> readN(inp, 4).joinToString(".") { (it.toInt() and 0xFF).toString() }
            3 -> { val l = readN(inp, 1)[0].toInt() and 0xFF; String(readN(inp, l), Charsets.ISO_8859_1) }
            4 -> InetAddress.getByAddress(readN(inp, 16)).hostAddress ?: ""
            else -> { out.write(byteArrayOf(5, 8, 0, 1, 0, 0, 0, 0, 0, 0)); return }
        }
        val pb = readN(inp, 2)
        val port = ((pb[0].toInt() and 0xFF) shl 8) or (pb[1].toInt() and 0xFF)
        val conn = try { openRemote(host, port) } catch (e: Exception) {
            out.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); return
        }
        out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
        relay(c, inp, out, conn)
    }
}
