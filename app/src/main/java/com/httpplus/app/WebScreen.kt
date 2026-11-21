package com.httpplus.app

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Navegador interno para crear una cuenta SSH gratis en la página de un proveedor.
 * Al terminar, "Capturar cuenta" lee el texto de la página y rellena host, puerto, usuario y contraseña.
 * El captcha y el formulario los completas tú; la app no los evita.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(onBack: () -> Unit, onCapture: (Config) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var url by remember { mutableStateOf("https://fastssh.com/") }
    var msg by remember { mutableStateOf("Crea la cuenta en la página y pulsa Capturar cuando muestre los datos.") }
    var web by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else onBack()
    }

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(url, { url = it }, Modifier.weight(1f), singleLine = true, label = { Text("Dirección del proveedor") })
            OutlinedButton(onClick = {
                var u = url.trim()
                if (!u.startsWith("http")) u = "https://$u"
                url = u
                web?.loadUrl(u)
            }) { Text("Ir") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onBack) { Text("Volver") }
            Button(onClick = {
                web?.evaluateJavascript("(function(){return document.body.innerText})()") { raw ->
                    val text = unquoteJs(raw)
                    val base = Store.load(ctx)
                    val c = parseImport(text, base)
                    if (c != null && c.host.isNotBlank() && c.user.isNotBlank()) onCapture(c)
                    else msg = "No encontré host y usuario en esta página. Abre la página que muestra los datos de la cuenta."
                }
            }) { Text("Capturar cuenta") }
        }
        Text(msg, style = MaterialTheme.typography.bodySmall)
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    loadUrl(url)
                    web = this
                }
            }
        )
    }
}

// evaluateJavascript devuelve el texto como cadena JSON ("...\n..."): se quitan comillas y escapes
private fun unquoteJs(raw: String?): String {
    if (raw == null || raw == "null") return ""
    var s = raw
    if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        if (ch == '\\' && i + 1 < s.length) {
            when (val n = s[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append(' '); i += 2 }
                'r' -> { i += 2 }
                'u' -> {
                    if (i + 5 < s.length) { sb.append(s.substring(i + 2, i + 6).toIntOrNull(16)?.toChar() ?: '?'); i += 6 }
                    else i += 2
                }
                else -> { sb.append(n); i += 2 }
            }
        } else { sb.append(ch); i++ }
    }
    return sb.toString()
}
