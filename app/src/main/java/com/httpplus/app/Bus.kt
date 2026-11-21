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
    val log: List<String> = emptyList()
)

object Bus {
    val state = MutableStateFlow(UiState())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(msg: String) {
        val line = fmt.format(Date()) + " " + msg
        state.value = state.value.let { it.copy(log = (it.log + line).takeLast(150)) }
    }

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
