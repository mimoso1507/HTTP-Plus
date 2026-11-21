package com.httpplus.app

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val running: Boolean = false,
    val connected: Boolean = false,
    val status: String = "Detenido",
    val clients: Int = 0,
    val log: List<String> = emptyList(),
    val upBps: Long = 0,
    val downBps: Long = 0,
    val upTotal: Long = 0,        // de la conexión actual
    val downTotal: Long = 0,
    val histVer: Int = 0,         // sube cada vez que cambia el historial
    val udpCount: Int = 0,        // intentos de UDP/QUIC bloqueados desde que arrancó la app
    val vpnOn: Boolean = false
)

object Bus {
    val state = MutableStateFlow(UiState())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(msg: String) {
        val line = fmt.format(Date()) + " " + msg
        state.value = state.value.let { it.copy(log = (it.log + line).takeLast(150)) }
    }

    fun setStats(upBps: Long, downBps: Long, upTotal: Long, downTotal: Long) {
        state.value = state.value.copy(upBps = upBps, downBps = downBps, upTotal = upTotal, downTotal = downTotal)
    }

    @Volatile var udpListener: (() -> Unit)? = null

    /** Un equipo o app intentó usar UDP (QUIC, llamadas, juegos): no pasa por el túnel y se rechaza. */
    fun udpAttempt() {
        state.value = state.value.let { it.copy(udpCount = it.udpCount + 1) }
        udpListener?.invoke()
    }

    fun setVpn(on: Boolean) { state.value = state.value.copy(vpnOn = on) }

    fun bumpHist() { state.value = state.value.let { it.copy(histVer = it.histVer + 1) } }

    fun set(running: Boolean? = null, connected: Boolean? = null, status: String? = null, clients: Int? = null) {
        state.value = state.value.let {
            it.copy(
                running = running ?: it.running,
                connected = connected ?: it.connected,
                status = status ?: it.status,
                clients = clients ?: it.clients
            )
        }
    }
}
