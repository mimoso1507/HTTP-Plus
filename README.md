# HTTP Plus

App Android nativa (Kotlin + Compose): túnel SSH (directo o sobre SSL con SNI) y proxy local HTTP/SOCKS5 para compartir la conexión.

- Host, puerto, usuario, contraseña, SNI, DNS opcional (consultado por el túnel), filtros de anuncios/adultos.
- Importa los datos de la cuenta desde el portapapeles (`host=...`, `puerto=...`, `usuario=...`, `contrasena=...`, `sni=...` o `host:puerto@usuario:clave`).
- Proxy en `0.0.0.0:<puerto>`: otros equipos de tu WiFi/hotspot lo usan poniendo IP + puerto del teléfono.
- No usa VpnService en esta versión (primera versión: proxy).

El APK se compila en GitHub Actions y se publica en Releases.
