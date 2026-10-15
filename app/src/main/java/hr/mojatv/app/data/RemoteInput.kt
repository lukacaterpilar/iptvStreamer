package hr.mojatv.app.data

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import hr.mojatv.app.ui.S
import kotlin.concurrent.thread

/**
 * Lets the user type into the focused TV text field from a phone browser on the same Wi-Fi.
 * [RemoteInputServer] serves a small page; whatever is submitted there is published as [incoming].
 */
object RemoteInput {
    const val PORT = 8765

    /** Label of the text field that currently has focus on the TV, or null. */
    var activeLabel by mutableStateOf<String?>(null)

    /** Last text sent from the phone, with a sequence number so re-sending the same text still applies. */
    var incoming by mutableStateOf<Pair<Long, String>?>(null)

    /** Address to show in the QR code, null while the server is not running. */
    var url by mutableStateOf<String?>(null)

    private var seq = 0L
    private val main = Handler(Looper.getMainLooper())

    internal fun publish(text: String) {
        main.post { incoming = ++seq to text }
    }

    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}

/** Minimal HTTP/1.1 server: GET / (page), GET /state (focused field as JSON), POST /send (text=…). */
class RemoteInputServer {
    private var socket: ServerSocket? = null

    @Synchronized
    fun start() {
        if (socket != null) return
        val s = runCatching { ServerSocket(RemoteInput.PORT) }.getOrNull() ?: return
        socket = s
        RemoteInput.url = RemoteInput.localIp()?.let { "http://$it:${RemoteInput.PORT}" }
        thread(name = "remote-input", isDaemon = true) {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(client) } }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        RemoteInput.url = null
    }

    private fun handle(client: Socket): Unit = client.use { c ->
        c.soTimeout = 5000
        val input = c.getInputStream()
        val head = readUntilBlankLine(input) ?: return@use
        val lines = head.split("\r\n")
        val (method, path) = lines.first().split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        val contentLength = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) String(input.readNBytesCompat(contentLength), Charsets.UTF_8) else ""

        val response: Pair<String, String> = when {
            method == "GET" && path.startsWith("/state") -> "application/json" to stateJson()
            method == "POST" && path.startsWith("/send") -> {
                val text = body.split("&")
                    .map { it.substringBefore("=") to it.substringAfter("=", "") }
                    .firstOrNull { it.first == "text" }?.second
                    ?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
                RemoteInput.publish(text)
                "application/json" to stateJson()
            }
            else -> "text/html" to page()
        }
        val bytes = response.second.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: ${response.first}; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        val out = c.getOutputStream()
        out.write(headers.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun stateJson(): String {
        val label = RemoteInput.activeLabel?.let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" } ?: "null"
        return "{\"label\":$label}"
    }

    private fun readUntilBlankLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < 16_384) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8 or b) and 0xFFFFFFFF.toInt()
            if (last4 == 0x0D0A0D0A) return String(buf.toByteArray(), Charsets.ISO_8859_1).removeSuffix("\r\n\r\n")
        }
        return null
    }

    private fun InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(out, off, n - off)
            if (r < 0) break
            off += r
        }
        return out.copyOf(off)
    }

    private fun page(): String {
        val t = S
        return PAGE
            .replace("%TITLE%", t.webTitle).replace("%CONNECTING%", t.webConnecting)
            .replace("%FIELD%", t.webField).replace("%SELECT%", t.webSelectField)
            .replace("%NOCONN%", t.webNoConnection).replace("%PLACEHOLDER%", t.webPlaceholder)
            .replace("%SEND%", t.webSend).replace("%CLEAR%", t.webClear)
            .replace("%SENT%", t.webSent).replace("%CLEARED%", t.webCleared)
    }

    companion object {
        private val PAGE = """
<!doctype html>
<html lang="hr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%TITLE%</title>
<style>
 body{margin:0;background:#0b1220;color:#e8eef8;font-family:system-ui,sans-serif;padding:20px}
 h1{font-size:20px;margin:0 0 6px}
 #field{color:#8a99b4;margin-bottom:16px;min-height:22px}
 #field b{color:#ff5ca8}
 input{width:100%;box-sizing:border-box;font-size:18px;padding:14px;border-radius:10px;border:1px solid #3d8bff;background:#121c2e;color:#fff}
 button{width:100%;margin-top:12px;font-size:18px;padding:14px;border:0;border-radius:10px;background:#3d8bff;color:#fff;font-weight:bold}
 button.alt{background:#1a2740}
 #ok{margin-top:14px;color:#43d17a;min-height:22px;text-align:center}
</style></head><body>
<h1>%TITLE%</h1>
<div id="field">%CONNECTING%</div>
<form id="f">
 <input id="t" name="text" autocomplete="off" autocapitalize="off" placeholder="%PLACEHOLDER%">
 <button type="submit">%SEND%</button>
 <button type="button" class="alt" id="clear">%CLEAR%</button>
</form>
<div id="ok"></div>
<script>
 const field=document.getElementById('field'), t=document.getElementById('t'), ok=document.getElementById('ok');
 function show(s){ field.innerHTML = s.label ? '%FIELD%: <b>'+s.label+'</b>' : '%SELECT%'; }
 async function poll(){ try{ show(await (await fetch('/state')).json()); }catch(e){ field.textContent='%NOCONN%'; } }
 async function send(text){
   const r=await fetch('/send',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({text})});
   show(await r.json()); ok.textContent = text ? '%SENT%' : '%CLEARED%'; setTimeout(()=>ok.textContent='',1500);
 }
 document.getElementById('f').onsubmit = e => { e.preventDefault(); send(t.value); t.value=''; t.focus(); };
 document.getElementById('clear').onclick = () => send('');
 poll(); setInterval(poll,1500); t.focus();
</script></body></html>
""".trimIndent()
    }
}
