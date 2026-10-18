package hr.mojatv.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hr.mojatv.app.data.Prefs
import hr.mojatv.app.data.Updater
import kotlinx.coroutines.delay

private const val UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60_000L
private const val FIRST_CHECK_DELAY_MS = 20_000L

/** Text for the settings row describing the current [Updater.state]. */
fun Updater.State.label(s: Strings): String = when (this) {
    Updater.State.Idle -> s.updIdle
    Updater.State.Checking -> s.updChecking
    Updater.State.UpToDate -> s.updLatest
    is Updater.State.Downloading -> s.updDownloading(percent)
    is Updater.State.Ready -> s.updReady(release.version)
    is Updater.State.Error -> s.updError(message)
}

/** Opens the installer for a downloaded update, or explains what the user has to allow first. */
fun installUpdate(ctx: Context, ready: Updater.State.Ready) {
    if (!Updater.install(ctx, ready.file)) Toast.makeText(ctx, S.updAllowInstall, Toast.LENGTH_LONG).show()
}

/**
 * Background check while the home screen is alive: shortly after start (the network may still be coming up
 * after boot) and then every few hours, as long as automatic updates are enabled in settings.
 */
@Composable
fun AutoUpdateChecker(prefs: Prefs) {
    val ctx = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        delay(FIRST_CHECK_DELAY_MS)
        while (true) {
            if (prefs.autoUpdate) {
                Updater.checkAndDownload(ctx)
                if (Updater.state is Updater.State.Error) { delay(5 * 60_000L); continue } // retry sooner
            }
            delay(UPDATE_CHECK_INTERVAL_MS)
        }
    }
}

/**
 * Bottom-of-screen prompt shown once a new version has been downloaded. "Later" hides it until the next
 * app start; the settings screen still offers the install.
 */
@Composable
fun UpdatePrompt(modifier: Modifier = Modifier) {
    val ready = Updater.state as? Updater.State.Ready ?: return
    if (Updater.promptDismissed) return
    val ctx = LocalContext.current
    val s = S
    val focus = remember { FocusRequester() }
    LaunchedEffect(ready.release.version) { runCatching { focus.requestFocus() } }

    Column(
        modifier.width(640.dp).background(Color(0xF2121C2E), RoundedCornerShape(12.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(s.updPrompt(ready.release.version), color = C.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        if (ready.release.notes.isNotEmpty()) {
            Text(ready.release.notes, color = C.dim, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PromptButton(s.updInstall, Modifier.focusRequester(focus), accent = true) { installUpdate(ctx, ready) }
            PromptButton(s.updLater) { Updater.promptDismissed = true }
        }
    }
}

@Composable
private fun PromptButton(text: String, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    FocusBox(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp),
        color = if (accent) C.accent.copy(alpha = 0.6f) else C.cell,
    ) {
        Text(
            text, Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium,
        )
    }
}
