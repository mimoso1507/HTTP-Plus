package hev.htproxy

/** Enlace con hev-socks5-tunnel (tun2socks). El nombre del paquete y de la clase los fija la librería nativa. */
class TProxyService {
    external fun TProxyStartService(configPath: String, fd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
    external fun TProxyGetStats(): LongArray

    companion object {
        val loaded: Boolean = try { System.loadLibrary("hev-socks5-tunnel"); true } catch (_: Throwable) { false }
    }
}
