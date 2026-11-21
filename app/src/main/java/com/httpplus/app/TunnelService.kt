package com.httpplus.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.PowerManager

/** Servicio en primer plano: mantiene el túnel SSH (con reconexión) y el proxy local. */
class TunnelService : Service() {
    @Volatile private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    @Volatile private var tunnelRef: SshTunnel? = null

    override fun onBind(intent: Intent?): IBinder? = null

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
        try {
            proxy = ProxyServer(cfg.proxyPort, tunnel, cfg)
            proxy.start()
            Bus.log("Proxy escuchando en el puerto ${cfg.proxyPort}")
            while (!Thread.currentThread().isInterrupted) {
                if (!tunnel.isAlive()) {
                    Bus.set(connected = false, status = "Conectando...")
                    try {
                        Bus.log("Conectando a ${cfg.host}:${cfg.port} (${if (cfg.mode == "ssl") "SSL, SNI ${cfg.sni.ifBlank { cfg.host }}" else "SSH directo"})")
                        tunnel.connect()
                        Bus.set(connected = true, status = "Conectado")
                        Bus.log("Túnel SSH conectado")
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
                Thread.sleep(3000)
                if (++tick % 3 == 0) tunnel.ping()
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Bus.log("Error del proxy: ${e.message}")
        } finally {
            proxy?.stop()
            tunnel.close()
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
