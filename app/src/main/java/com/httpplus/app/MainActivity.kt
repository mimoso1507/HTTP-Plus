package com.httpplus.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxWidth()) {
                    var page by remember { mutableStateOf("main") }
                    var imported by remember { mutableStateOf<Config?>(null) }
                    if (page == "web") WebScreen(
                        onBack = { page = "main" },
                        onCapture = { c -> imported = c; page = "main" }
                    ) else Screen(imported, { imported = null }, { page = "web" })
                }
            }
        }
    }
}

private fun fmtDate(ms: Long): String = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

private fun fmtDur(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "${s / 3600} h ${(s % 3600) / 60} min" else if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
}

private fun localAddresses(): List<String> {
    val r = ArrayList<String>()
    try {
        for (ni in NetworkInterface.getNetworkInterfaces()) {
            if (!ni.isUp || ni.isLoopback) continue
            for (a in ni.inetAddresses) {
                if (a is Inet4Address && !a.isLoopbackAddress) r.add(ni.name + ": " + a.hostAddress)
            }
        }
    } catch (_: Exception) {}
    return r
}

@Composable
private fun Screen(imported: Config?, onImportedUsed: () -> Unit, onWeb: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var cfg by remember { mutableStateOf(Store.load(ctx)) }
    var portText by remember { mutableStateOf(cfg.port.toString()) }
    var proxyText by remember { mutableStateOf(cfg.proxyPort.toString()) }
    var addrs by remember { mutableStateOf(localAddresses()) }
    var msg by remember { mutableStateOf("") }
    val ui by Bus.state.collectAsState()

    if (imported != null) {
        cfg = imported; portText = imported.port.toString()
        msg = "Cuenta capturada de la página"
        onImportedUsed()
    }

    var hist by remember { mutableStateOf(History.load(ctx).sortedByDescending { it.start }) }
    androidx.compose.runtime.LaunchedEffect(ui.histVer) { hist = History.load(ctx).sortedByDescending { it.start } }
    var askDel by remember { mutableStateOf<Rec?>(null) }
    var askAll by remember { mutableStateOf(false) }

    askDel?.let { r ->
        AlertDialog(
            onDismissRequest = { askDel = null },
            title = { Text("¿Borrar este registro?") },
            text = { Text("${fmtDate(r.start)}  ·  ${r.host}") },
            confirmButton = { TextButton(onClick = { History.delete(ctx, r.id); hist = History.load(ctx).sortedByDescending { it.start }; askDel = null }) { Text("Borrar") } },
            dismissButton = { TextButton(onClick = { askDel = null }) { Text("Cancelar") } }
        )
    }
    if (askAll) {
        AlertDialog(
            onDismissRequest = { askAll = false },
            title = { Text("¿Borrar todo el historial?") },
            text = { Text("Se pierden los datos de todas las conexiones anteriores.") },
            confirmButton = { TextButton(onClick = { History.clear(ctx); hist = emptyList(); askAll = false }) { Text("Borrar todo") } },
            dismissButton = { TextButton(onClick = { askAll = false }) { Text("Cancelar") } }
        )
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun current(): Config = cfg.copy(
        port = portText.toIntOrNull() ?: 443,
        proxyPort = (proxyText.toIntOrNull() ?: 8080).coerceIn(1024, 65535)
    )

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("HTTP Plus", style = MaterialTheme.typography.headlineSmall)
        Text(
            (if (ui.connected) "● Conectado" else if (ui.running) "● " + ui.status else "○ Detenido") +
                if (ui.running) "   · clientes: ${ui.clients}" else "",
            style = MaterialTheme.typography.titleMedium
        )

        if (ui.running) {
            Text("↑ ${fmtSpeed(ui.upBps)}     ↓ ${fmtSpeed(ui.downBps)}", style = MaterialTheme.typography.titleLarge)
            Text("Esta conexión:  subida ${fmtBytes(ui.upTotal)}  ·  bajada ${fmtBytes(ui.downTotal)}")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = cfg.mode == "ssl", onClick = { cfg = cfg.copy(mode = "ssl") }, label = { Text("SSH sobre SSL + SNI") })
            FilterChip(selected = cfg.mode == "ssh", onClick = { cfg = cfg.copy(mode = "ssh") }, label = { Text("SSH directo") })
        }
        OutlinedTextField(cfg.host, { cfg = cfg.copy(host = it.trim()) }, Modifier.fillMaxWidth(), label = { Text("Host") }, singleLine = true)
        OutlinedTextField(
            portText, { portText = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Puerto") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(cfg.user, { cfg = cfg.copy(user = it.trim()) }, Modifier.fillMaxWidth(), label = { Text("Usuario") }, singleLine = true)
        OutlinedTextField(
            cfg.pass, { cfg = cfg.copy(pass = it) }, Modifier.fillMaxWidth(), label = { Text("Contraseña") },
            singleLine = true, visualTransformation = PasswordVisualTransformation()
        )
        if (cfg.mode == "ssl") {
            OutlinedTextField(cfg.sni, { cfg = cfg.copy(sni = it.trim()) }, Modifier.fillMaxWidth(), label = { Text("SNI (vacío = el host)") }, singleLine = true)
        }
        OutlinedTextField(
            cfg.dns, { cfg = cfg.copy(dns = it.trim()) }, Modifier.fillMaxWidth(),
            label = { Text("DNS (IP, opcional; se consulta por el túnel)") }, singleLine = true
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(cfg.blockAds, { cfg = cfg.copy(blockAds = it) }); Text("Bloquear anuncios")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(cfg.blockAdult, { cfg = cfg.copy(blockAdult = it) }); Text("Bloquear adultos")
        }
        OutlinedTextField(
            cfg.customBlock, { cfg = cfg.copy(customBlock = it) }, Modifier.fillMaxWidth(),
            label = { Text("Otros dominios a bloquear (separados por coma)") }
        )
        OutlinedTextField(
            proxyText, { proxyText = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Puerto del proxy local (1024-65535)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val t = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
                val n = parseImport(t, cfg)
                if (n != null) { cfg = n; portText = n.port.toString(); msg = "Datos importados del portapapeles" }
                else msg = "El portapapeles no tiene datos de cuenta reconocibles"
            }) { Text("Importar del portapapeles") }
            OutlinedButton(onClick = { Store.save(ctx, current()); msg = "Guardado" }) { Text("Guardar") }
        }
        OutlinedButton(onClick = { Store.save(ctx, current()); onWeb() }, Modifier.fillMaxWidth()) { Text("Crear cuenta SSH gratis (abrir página)") }

        if (!ui.running) {
            Button(onClick = {
                val c = current()
                if (c.host.isBlank() || c.user.isBlank()) { msg = "Falta el host o el usuario"; return@Button }
                Store.save(ctx, c)
                if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                ctx.startForegroundService(Intent(ctx, TunnelService::class.java))
                addrs = localAddresses()
                msg = ""
            }, Modifier.fillMaxWidth()) { Text("Conectar") }
        } else {
            Button(onClick = {
                ctx.startService(Intent(ctx, TunnelService::class.java).setAction(TunnelService.ACTION_STOP))
            }, Modifier.fillMaxWidth()) { Text("Detener") }
        }
        OutlinedButton(onClick = {
            try {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + ctx.packageName)))
            } catch (_: Exception) {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }, Modifier.fillMaxWidth()) { Text("Evitar que Android cierre la app (batería)") }
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.primary)

        Text("Compartir internet", style = MaterialTheme.typography.titleMedium)
        Text(
            "En el otro dispositivo, en su WiFi, pon el proxy manual con una de estas IP y el puerto ${proxyText.ifEmpty { "8080" }} " +
                "(HTTP o SOCKS5). Activa antes tu punto de acceso o conecta ambos a la misma red.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(onClick = { addrs = localAddresses() }) { Text("Actualizar IP") }
        if (addrs.isEmpty()) Text("Sin red activa") else addrs.forEach { Text("$it  :  ${proxyText.ifEmpty { "8080" }}") }

        Text("Historial de conexiones", style = MaterialTheme.typography.titleMedium)
        if (hist.isEmpty()) Text("(sin registros)", style = MaterialTheme.typography.bodySmall)
        else {
            Text("Total: subida ${fmtBytes(hist.sumOf { it.up })}  ·  bajada ${fmtBytes(hist.sumOf { it.down })}", style = MaterialTheme.typography.bodySmall)
            hist.forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${fmtDate(r.start)}  ·  ${r.host}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "↑ ${fmtBytes(r.up)}   ↓ ${fmtBytes(r.down)}   ·   " + (if (r.end == 0L) "en curso" else fmtDur(r.end - r.start)),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = { askDel = r }) { Text("Borrar") }
                }
            }
            OutlinedButton(onClick = { askAll = true }) { Text("Borrar todo el historial") }
        }

        Text("Registro", style = MaterialTheme.typography.titleMedium)
        Text(ui.log.takeLast(40).joinToString("\n").ifEmpty { "(vacío)" }, style = MaterialTheme.typography.bodySmall)
    }
}
