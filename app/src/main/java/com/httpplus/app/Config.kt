package com.httpplus.app

import android.content.Context

data class Config(
    val mode: String = "ssl",          // "ssl" = SSH sobre TLS con SNI, "ssh" = SSH directo
    val host: String = "",
    val port: Int = 443,
    val user: String = "",
    val pass: String = "",
    val sni: String = "",
    val dns: String = "",              // IP del DNS (se consulta por el túnel); vacío = lo resuelve el servidor SSH
    val proxyPort: Int = 8080,
    val blockAds: Boolean = false,
    val blockAdult: Boolean = false,
    val customBlock: String = ""       // dominios extra separados por coma
)

object Store {
    private fun p(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun load(ctx: Context): Config {
        val s = p(ctx)
        return Config(
            mode = s.getString("mode", "ssl") ?: "ssl",
            host = s.getString("host", "") ?: "",
            port = s.getInt("port", 443),
            user = s.getString("user", "") ?: "",
            pass = s.getString("pass", "") ?: "",
            sni = s.getString("sni", "") ?: "",
            dns = s.getString("dns", "") ?: "",
            proxyPort = s.getInt("proxyPort", 8080),
            blockAds = s.getBoolean("blockAds", false),
            blockAdult = s.getBoolean("blockAdult", false),
            customBlock = s.getString("customBlock", "") ?: ""
        )
    }

    fun save(ctx: Context, c: Config) {
        p(ctx).edit()
            .putString("mode", c.mode).putString("host", c.host).putInt("port", c.port)
            .putString("user", c.user).putString("pass", c.pass).putString("sni", c.sni)
            .putString("dns", c.dns).putInt("proxyPort", c.proxyPort)
            .putBoolean("blockAds", c.blockAds).putBoolean("blockAdult", c.blockAdult)
            .putString("customBlock", c.customBlock)
            .apply()
    }
}

/**
 * Lee los datos de una cuenta pegada como texto.
 * Acepta líneas "clave=valor" o "clave: valor" (tipo, host, puerto, sni, usuario, contrasena, modo, dns)
 * o una sola línea "host:puerto@usuario:clave".
 */
fun parseImport(text: String, base: Config): Config? {
    val t = text.trim()
    if (t.isEmpty()) return null
    var c = base
    var found = false
    for (raw in t.lines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val i = line.indexOfFirst { it == '=' || it == ':' }
        val atForm = Regex("^([^\\s:@]+):(\\d+)@([^:\\s]+):(.+)$").find(line)
        if (atForm != null) {
            val (h, po, u, pw) = atForm.destructured
            c = c.copy(host = h, port = po.toInt(), user = u, pass = pw)
            found = true
            continue
        }
        if (i <= 0) continue
        val k = line.substring(0, i).trim().lowercase()
        val v = line.substring(i + 1).trim()
        when (k) {
            "host", "servidor", "server", "hostname", "ip", "ip address", "host/ip", "host ip", "ssh host" -> { c = c.copy(host = v); found = true }
            "puerto", "port", "ssl/tls port", "ssl port", "tls port", "ssh port" -> { v.toIntOrNull()?.let { c = c.copy(port = it); found = true } }
            "usuario", "user", "username" -> { c = c.copy(user = v); found = true }
            "contrasena", "contraseña", "pass", "password", "clave" -> { c = c.copy(pass = v); found = true }
            "sni" -> { c = c.copy(sni = v); found = true }
            "dns" -> { c = c.copy(dns = v); found = true }
            "tipo", "modo", "mode" -> {
                val lv = v.lowercase()
                c = c.copy(mode = if (lv.contains("ssl") || lv.contains("tls") || lv.contains("stunnel")) "ssl" else "ssh")
                found = true
            }
        }
    }
    return if (found) c else null
}
