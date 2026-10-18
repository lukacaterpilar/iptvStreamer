package hr.mojatv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hr.mojatv.app.data.Credentials
import hr.mojatv.app.data.LoginError
import hr.mojatv.app.data.Prefs
import hr.mojatv.app.data.XtreamApi
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Accepts either a bare server address plus username/password, or a full
 * `get.php?username=…&password=…` M3U link pasted into the server field.
 */
fun parseCredentials(server: String, user: String, pass: String): Credentials? {
    val s = server.trim().ifEmpty { return null }
    val url = (if (s.contains("://")) s else "http://$s").toHttpUrlOrNull() ?: return null
    val u = url.queryParameter("username")?.takeIf { it.isNotBlank() } ?: user.trim()
    val p = url.queryParameter("password")?.takeIf { it.isNotBlank() } ?: pass.trim()
    if (u.isEmpty() || p.isEmpty()) return null
    val port = if (url.port != HttpUrl.defaultPort(url.scheme)) ":${url.port}" else ""
    return Credentials("${url.scheme}://${url.host}$port", u, p)
}

/**
 * Sign-in form. Creates a new [hr.mojatv.app.data.Profile] and makes it active.
 * In [addMode] (adding a second subscription) Back/Cancel returns without changes.
 */
@Composable
fun LoginScreen(prefs: Prefs, addMode: Boolean = false, onCancel: () -> Unit = {}, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("http://") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val serverFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { serverFocus.requestFocus() } }
    if (addMode) BackHandler(onBack = onCancel)

    fun submit() {
        if (busy) return
        val creds = parseCredentials(server, user, pass)
        if (creds == null) {
            error = S.loginFillAll
            return
        }
        busy = true
        error = null
        scope.launch {
            val result = runCatching { XtreamApi(creds).login() }
            busy = false
            result.onFailure { error = S.loginConnError(it.message ?: it.javaClass.simpleName) }
            result.onSuccess { err ->
                when (err) {
                    null -> {
                        val profileName = name.trim().ifEmpty { creds.server.substringAfter("://").substringBefore(":") }
                        prefs.addProfile(profileName, creds)
                        onDone()
                    }
                    LoginError.BadResponse -> error = S.apiBadResponse
                    LoginError.BadCredentials -> error = S.apiBadCredentials
                    is LoginError.Inactive -> error = S.apiInactive(err.status)
                }
            }
        }
    }

    SpaceBackground {
        // Form sits left of centre so the phone-keyboard QR panel on the right never covers it.
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 120.dp).width(520.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(prefs.appName, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color(prefs.appColor))
            Text(S.loginSubtitle, color = C.dim)
            RemoteTextField(
                value = name, onValueChange = { name = it }, label = S.profileNameLabel,
                modifier = Modifier.fillMaxWidth(), imeAction = ImeAction.Next,
            )
            RemoteTextField(
                value = server, onValueChange = { server = it }, label = S.loginServer,
                modifier = Modifier.fillMaxWidth().focusRequester(serverFocus), imeAction = ImeAction.Next,
            )
            RemoteTextField(
                value = user, onValueChange = { user = it }, label = S.loginUser,
                modifier = Modifier.fillMaxWidth(), imeAction = ImeAction.Next,
            )
            RemoteTextField(
                value = pass, onValueChange = { pass = it }, label = S.loginPass,
                modifier = Modifier.fillMaxWidth(), password = true, imeAction = ImeAction.Done,
            )
            error?.let { Text(it, color = Color(0xFFFF8A80)) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusBox(onClick = ::submit, color = C.accent, modifier = Modifier.weight(1f)) {
                    Text(
                        if (busy) S.loginBusy else S.loginButton,
                        Modifier.fillMaxWidth().padding(14.dp),
                        fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center,
                    )
                }
                if (addMode) FocusBox(onClick = onCancel, color = C.cell) {
                    Text(S.cancel, Modifier.padding(horizontal = 24.dp, vertical = 14.dp), color = C.text, fontWeight = FontWeight.Bold)
                }
            }
            Text(S.loginHint, color = C.dim, fontSize = 12.sp)
        }
        RemoteInputOverlay(Modifier.align(Alignment.CenterEnd).padding(end = 60.dp))
    }
}
