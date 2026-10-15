package hr.mojatv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hr.mojatv.app.data.Prefs
import hr.mojatv.app.data.RemoteInputServer
import hr.mojatv.app.data.Repo
import hr.mojatv.app.ui.AppTheme
import hr.mojatv.app.ui.HomeScreen
import hr.mojatv.app.ui.L10n
import hr.mojatv.app.ui.Lang
import hr.mojatv.app.ui.LoginScreen
import hr.mojatv.app.ui.MojaTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_FROM_BOOT = "from_boot"
    }

    /** Phone-keyboard web server; only runs while this screen is in front. */
    private val remoteInput = RemoteInputServer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        L10n.lang = Lang.fromCode(prefs.language)
        AppTheme.background = prefs.background
        prefs.credentials?.let(Repo::init)
        // Auto-play the last channel only on a fresh start, not after rotation/process restore.
        val autoPlay = savedInstanceState == null && prefs.resumeLastChannel

        setContent {
            MojaTheme {
                var loggedIn by mutableStateOf(prefs.credentials != null)
                // Bumped whenever the active profile changes so the home screen starts fresh.
                var homeKey by mutableIntStateOf(0)
                if (loggedIn) {
                    key(homeKey) {
                        HomeScreen(
                            prefs = prefs,
                            autoPlayLast = autoPlay && homeKey == 0,
                            onProfileChanged = {
                                val c = prefs.credentials
                                if (c != null) {
                                    Repo.init(c)
                                    homeKey++
                                } else {
                                    Repo.clear()
                                    loggedIn = false
                                }
                            },
                        )
                    }
                } else {
                    LoginScreen(prefs) {
                        prefs.credentials?.let(Repo::init)
                        homeKey++
                        loggedIn = true
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        remoteInput.start()
    }

    override fun onStop() {
        remoteInput.stop()
        super.onStop()
    }
}
