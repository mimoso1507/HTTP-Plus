package com.httpplus.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import java.io.File
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.PowerManager

/** Servicio en primer plano: mantiene el túnel SSH (con reconexión) y el proxy local. */
class TunnelService : VpnService() {
    private val hev by lazy { TProxyService() }
    private var vpnPfd: ParcelFileDescriptor? = null
    @Volatile private var lastUdpAlert = 0L
    @Volatile private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    @Volatile private var tunnelRef: SshTunnel? = null

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private fun startVpn(cfg: Config) {
        if (vpnPfd != null) return
        if (!TProxyService.loaded) { Bus.log("Motor VPN no disponible en este APK; sigue el proxy"); return }
        try {
            val b = Builder().setSession("HTTP Plus").setMtu(1500)
                .addAddress("198.18.0.1", 32).addAddress("fc00::1", 128)
                .addRoute("0.0.0.0", 0).addRoute("::", 0)
                .addDnsServer("198.18.0.2")
            try { b.addDisallowedApplication(packageName) } catch (_: Exception) {}   // la propia app sale directo, si no se haría un bucle
            val pfd = b.establish() ?: throw java.io.IOException("VPN no autorizada")
            val yml = "tunnel:\n  mtu: 1500\n  ipv4: 198.18.0.1\n  ipv6: 'fc00::1'\n" +
                "socks5:\n  port: ${cfg.proxyPort}\n  address: 127.0.0.1\n  udp: 'udp'\n" +
                "mapdns:\n  address: 198.18.0.2\n  port: 53\n  network: 100.64.0.0\n  netmask: 255.192.0.0\n  cache-size: 10000\n" +
                "misc:\n  log-level: warn\n"
            val f = File(filesDir, "tun.yml")
            f.writeText(yml)
            if (!hev.TProxyStartService(f.absolutePath, pfd.fd)) { pfd.close(); throw java.io.IOException("el motor VPN no arrancó") }
            vpnPfd = pfd
            Bus.setVpn(true)
            Bus.log("VPN activa: todas las apps salen por el túnel")
        } catch (e: Throwable) {
            Bus.log("VPN: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun stopVpn() {
        try { if (vpnPfd != null) hev.TProxyStopService() } catch (_: Throwable) {}
        try { vpnPfd?.close() } catch (_: Exception) {}
        vpnPfd = null
        Bus.setVpn(false)
    }

    private fun udpAlert() {
        val now = System.currentTimeMillis()
        if (now - lastUdpAlert < 60000) return
        lastUdpAlert = now
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("alert", "Avisos", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, Notification.Builder(this, "alert")
            .setContentTitle("UDP / QUIC detectado")
            .setContentText("Una app o equipo intentó usar UDP o QUIC. No pasa por el túnel y se rechazó.")
            .setStyle(Notification.BigTextStyle().bigText("Una app o equipo intentó usar UDP o QUIC (llamadas, juegos, algunos videos). El túnel SSH solo lleva TCP, así que se rechazó; la mayoría de apps reintenta por TCP."))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopWork()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(1, notification("Conectando..."))
        if (worker == null) {
            val cfg = Store.load(this)
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "httpplus:tunnel").also { it.acquire() }
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "httpplus:wifi").also { it.acquire() }
            } catch (_: Exception) {}
            watchNetwork()
            Bus.udpListener = { udpAlert() }
            Bus.set(running = true, connected = false, status = "Conectando...")
            worker = Thread { run(cfg) }.also { it.isDaemon = true; it.start() }
        }
        return START_STICKY      // si Android cierra el servicio, lo vuelve a iniciar
    }

    // Cuando cambia la red (WiFi <-> datos, o vuelve la señal) se reconecta de inmediato en vez de esperar al tiempo de espera
    private fun watchNetwork() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            var first = true
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (first) { first = false; return }
                    Bus.log("La red cambió, reconectando")
                    tunnelRef?.close()
                }
                override fun onLost(network: Network) {
                    Bus.log("Se perdió la red")
                    tunnelRef?.close()
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (_: Exception) {}
    }

    private fun run(cfg: Config) {
        val tunnel = SshTunnel(cfg)
        tunnelRef = tunnel
        var proxy: ProxyServer? = null
        var delay = 2000L
        var tick = 0
        var rec: Rec? = null
        var baseUp = 0L; var baseDown = 0L
        var lastUp = 0L; var lastDown = 0L; var lastT = System.currentTimeMillis()
        fun saveRec(final: Boolean) {
            val r = rec ?: return
            r.up = Stats.up.get() - baseUp
            r.down = Stats.down.get() - baseDown
            if (final) r.end = System.currentTimeMillis()
            History.upsert(applicationContext, r)
            Bus.bumpHist()
        }
        try {
            proxy = ProxyServer(cfg.proxyPort, tunnel, cfg)
            proxy.start()
            Bus.log("Proxy escuchando en el puerto ${cfg.proxyPort}")
            while (!Thread.currentThread().isInterrupted) {
                if (!tunnel.isAlive()) {
                    if (rec != null) { saveRec(true); rec = null; Bus.setStats(0, 0, 0, 0) }
                    Bus.set(connected = false, status = "Conectando...")
                    try {
                        Bus.log("Conectando a ${cfg.host}:${cfg.port} (${if (cfg.mode == "ssl") "SSL, SNI ${cfg.sni.ifBlank { cfg.host }}" else "SSH directo"})")
                        tunnel.connect()
                        Bus.set(connected = true, status = "Conectado")
                        baseUp = Stats.up.get(); baseDown = Stats.down.get()
                        lastUp = baseUp; lastDown = baseDown; lastT = System.currentTimeMillis()
                        rec = Rec(System.currentTimeMillis(), cfg.host, System.currentTimeMillis(), 0, 0, 0)
                        saveRec(false)
                        Bus.log("Túnel SSH conectado")
                        if (cfg.vpn) startVpn(cfg)
                        notify("Conectado - proxy puerto ${cfg.proxyPort}")
                        delay = 2000L
                    } catch (e: Exception) {
                        Bus.set(connected = false, status = "Error, reintentando")
                        Bus.log("Error: ${e.message ?: e.javaClass.simpleName}")
                        notify("Sin conexión, reintentando")
                        Thread.sleep(delay)
                        delay = (delay * 2).coerceAtMost(30000L)
                        continue
                    }
                }
                Thread.sleep(1000)
                tick++
                if (tick % 9 == 0) tunnel.ping()
                if (rec != null) {
                    val now = System.currentTimeMillis()
                    val u = Stats.up.get(); val d = Stats.down.get()
                    val dt = (now - lastT).coerceAtLeast(1)
                    Bus.setStats((u - lastUp) * 1000 / dt, (d - lastDown) * 1000 / dt, u - baseUp, d - baseDown)
                    lastUp = u; lastDown = d; lastT = now
                    if (tick % 10 == 0) saveRec(false)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Bus.log("Error del proxy: ${e.message}")
        } finally {
            saveRec(true)
            stopVpn()
            Bus.udpListener = null
            proxy?.stop()
            tunnel.close()
            Bus.setStats(0, 0, 0, 0)
            Bus.set(running = false, connected = false, status = "Detenido", clients = 0)
            Bus.log("Detenido")
        }
    }

    private fun stopWork() {
        worker?.interrupt()
        worker = null
        try { wake?.release() } catch (_: Exception) {}
        wake = null
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
        try { netCb?.let { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } } catch (_: Exception) {}
        netCb = null
    }

    override fun onDestroy() {
        stopWork()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("tunnel", "Túnel", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TunnelService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "tunnel")
            .setContentTitle("HTTP Plus")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
    }

    companion object {
        const val ACTION_STOP = "com.httpplus.app.STOP"
    }
}
