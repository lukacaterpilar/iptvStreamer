package hr.mojatv.app.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import hr.mojatv.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A published app version on GitHub. */
data class Release(val version: String, val apkUrl: String, val notes: String)

/**
 * Self-update through GitHub releases. The newest release's tag (e.g. `v1.5`) is compared with the installed
 * [BuildConfig.VERSION_NAME]; its `MamaTV.apk` asset is downloaded to the cache and handed to the system
 * package installer, which always asks the user to confirm. The whole state is observable from Compose so the
 * settings row and the home-screen prompt show the same thing.
 */
object Updater {
    private const val REPO = "lukacaterpilar/iptvStreamer"
    private const val ASSET = "MamaTV.apk"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data object UpToDate : State()
        data class Downloading(val release: Release, val percent: Int) : State()
        data class Ready(val release: Release, val file: File) : State()
        data class Error(val message: String) : State()
    }

    var state by mutableStateOf<State>(State.Idle)
        private set

    /** Set when the user chose "later" on the prompt so it stays hidden until the next app start. */
    var promptDismissed by mutableStateOf(false)

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    private val busy get() = state is State.Checking || state is State.Downloading

    /**
     * Looks for a newer release and, if there is one, downloads it so that the next step is just "install".
     * Ends in [State.Ready], [State.UpToDate] or [State.Error]. Does nothing while a check or download runs.
     */
    suspend fun checkAndDownload(ctx: Context) {
        if (busy) return
        (state as? State.Ready)?.let { if (it.file.exists()) return }
        state = State.Checking
        try {
            val release = latestRelease()
            if (release == null || !isNewer(release.version, currentVersion)) {
                state = State.UpToDate
                return
            }
            val file = download(ctx, release)
            state = State.Ready(release, file)
        } catch (e: Exception) {
            state = State.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Opens the system installer for the downloaded APK. Returns false when the app is not yet allowed to
     * install packages; the "install unknown apps" settings page is opened instead and the user has to retry.
     */
    fun install(ctx: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(settings)
                return false
            } catch (_: ActivityNotFoundException) {
                // No dedicated page on this device: let the installer show its own "allow" screen.
            }
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        return true
    }

    /** Removes APKs left from earlier updates (except one that is still waiting to be installed). */
    fun cleanup(ctx: Context) {
        val keep = (state as? State.Ready)?.file
        runCatching { updatesDir(ctx).listFiles()?.forEach { if (it != keep) it.delete() } }
    }

    private fun updatesDir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }

    private suspend fun latestRelease(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(API).header("Accept", "application/vnd.github+json").build()
        httpClient.newCall(req).execute().use { resp ->
            if (resp.code == 404) return@withContext null // no release published yet
            if (!resp.isSuccessful) throw IOException("GitHub HTTP ${resp.code}")
            val o = JSONObject(resp.body?.string() ?: throw IOException("Empty response"))
            val assets = o.optJSONArray("assets")
            var url: String? = null
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                if (a.optString("name") == ASSET) url = a.optString("browser_download_url")
            }
            val version = o.optString("tag_name").removePrefix("v").removePrefix("V").trim()
            if (url.isNullOrEmpty() || version.isEmpty()) return@withContext null
            Release(version, url, o.optString("body").trim())
        }
    }

    private suspend fun download(ctx: Context, release: Release): File = withContext(Dispatchers.IO) {
        state = State.Downloading(release, 0)
        val target = File(updatesDir(ctx), "MamaTV-${release.version}.apk")
        val tmp = File(target.path + ".part")
        val req = Request.Builder().url(release.apkUrl).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty download")
            val total = body.contentLength()
            var done = 0L
            var lastPercent = -1
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != lastPercent) { lastPercent = p; state = State.Downloading(release, p) }
                        }
                    }
                }
            }
        }
        if (tmp.length() < 100_000) throw IOException("Downloaded file is too small")
        if (!tmp.renameTo(target)) throw IOException("Cannot store the APK")
        target
    }

    /** `"1.10" > "1.9"`; non-numeric parts count as 0. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
