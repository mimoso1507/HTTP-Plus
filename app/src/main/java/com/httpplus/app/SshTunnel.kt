package com.httpplus.app

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Conexión abierta dentro del túnel SSH (direct-tcpip). */
class TunnelConn(private val ch: ChannelDirectTCPIP, val input: InputStream, val output: OutputStream) {
    fun close() {
        try { ch.disconnect() } catch (_: Exception) {}
    }
}

/**
 * SSH sobre TLS: abre el socket TLS poniendo el SNI que se indique y deja que JSch hable SSH por encima.
 * El certificado no se verifica porque con SNI de relleno nunca coincide con el servidor real.
 */
private class TlsSocketFactory(private val sni: String) : SocketFactory {
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }

    override fun createSocket(host: String, port: Int): Socket {
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.keepAlive = true
        raw.connect(InetSocketAddress(host, port), 15000)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustAll), null)
        val name = sni.ifBlank { host }
        val ss = ctx.socketFactory.createSocket(raw, name, port, true) as SSLSocket
        try {
            val params = ss.sslParameters
            params.serverNames = listOf(SNIHostName(name))
            ss.sslParameters = params
        } catch (_: Exception) { /* SNI no válido (por ejemplo una IP): se sigue sin él */ }
        ss.soTimeout = 0
        ss.startHandshake()
        return ss
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
}

private class PlainSocketFactory : SocketFactory {
    override fun createSocket(host: String, port: Int): Socket {
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.keepAlive = true
        raw.connect(InetSocketAddress(host, port), 15000)
        return raw
    }
    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
}

class SshTunnel(private val cfg: Config) {
    @Volatile private var session: Session? = null
    @Volatile private var openFails = 0

    fun connect() {
        close()
        val jsch = JSch()
        val s = jsch.getSession(cfg.user, cfg.host, cfg.port)
        s.setPassword(cfg.pass)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        s.setServerAliveInterval(10000)
        s.setServerAliveCountMax(3)
        s.setSocketFactory(if (cfg.mode == "ssl") TlsSocketFactory(cfg.sni) else PlainSocketFactory())
        s.connect(20000)
        session = s
    }

    fun isAlive(): Boolean = session?.isConnected == true

    /** Mensaje de mantenimiento: si el servidor no responde, se cierra para que el servicio reconecte. */
    fun ping() {
        val s = session ?: return
        try { s.sendKeepAliveMsg() } catch (_: Exception) { close() }
    }

    fun open(host: String, port: Int): TunnelConn {
        val s = session ?: throw IOException("sin túnel")
        try {
            val ch = s.openChannel("direct-tcpip") as ChannelDirectTCPIP
            val input = ch.inputStream
            val output = ch.outputStream
            ch.setHost(host)
            ch.setPort(port)
            ch.connect(15000)
            openFails = 0
            return TunnelConn(ch, input, output)
        } catch (e: Exception) {
            // Si el servidor cerró el túnel sin avisar, varios fallos seguidos fuerzan la reconexión
            if (!s.isConnected || ++openFails >= 4) { openFails = 0; close() }
            throw e
        }
    }

    fun close() {
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
    }
}
