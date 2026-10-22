package hr.mojatv.app.player

import hr.mojatv.app.ui.S
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C as MediaC
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import hr.mojatv.app.data.Diagnostics
import hr.mojatv.app.data.Prefs
import hr.mojatv.app.data.Repo
import hr.mojatv.app.data.streamHttpClient
import hr.mojatv.app.ui.C
import hr.mojatv.app.ui.FocusBox
import hr.mojatv.app.ui.MojaTheme
import hr.mojatv.app.ui.formatMs
import hr.mojatv.app.ui.hhmm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen player. Live mode zaps through [Repo.playlist] with the D-pad;
 * VOD mode plays a single URL with seek/resume.
 *
 * Remote keys (live): ▲/▼ or CH+/CH- = next/previous channel, OK = info bar (OK again = channel list),
 * ◀ = channel list, 0-9 = channel number, Back = exit.
 * Remote keys (VOD): OK/play-pause = pause, ◀/▶ = ±10 s, RW/FF = ±60 s, Back = exit.
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_STREAM_ID = "stream_id"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_START_MS = "start_ms"
        private const val OVERLAY_MS = 5000L
        private const val MAX_RETRIES = 5
        private const val WATCHDOG_MS = 2000L
        /** Live picture not advancing while "playing" for this long = silent stall; reconnect like a zap. */
        private const val STALL_MS = 8000L
        private const val MAX_BUFFERING_MS = 15_000L
        // Cushion for live TS: play slightly slower until this much is buffered, so dropouts are bridged.
        private const val CUSHION_LOW_MS = 10_000L
        private const val CUSHION_HIGH_MS = 12_000L
        private const val CUSHION_SPEED = 0.97f

        fun liveIntent(ctx: Context, streamId: Int): Intent =
            Intent(ctx, PlayerActivity::class.java).putExtra(EXTRA_MODE, "live").putExtra(EXTRA_STREAM_ID, streamId)

        fun vodIntent(ctx: Context, url: String, title: String, key: String, startMs: Long = 0L): Intent =
            Intent(ctx, PlayerActivity::class.java)
                .putExtra(EXTRA_MODE, "vod").putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_KEY, key).putExtra(EXTRA_START_MS, startMs)
    }

    private lateinit var prefs: Prefs
    private lateinit var diag: Diagnostics
    private var player: ExoPlayer? = null
    private val isLive by lazy { intent.getStringExtra(EXTRA_MODE) == "live" }
    /** Live TS (unlike HLS) is one endless HTTP response, so it gets the reconnecting source and the cushion. */
    private val isLiveTs by lazy { isLive && prefs.liveFormat == "ts" }
    private val playlist get() = Repo.playlist

    private var index by mutableIntStateOf(-1)
    private var overlay by mutableStateOf(false)
    private var listVisible by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var buffering by mutableStateOf(false)
    private var paused by mutableStateOf(false)
    private var digits by mutableStateOf("")

    private var retries = 0
    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay = false }
    private val retry = Runnable { reconnect() }

    // Stall watchdog: some IPTV streams stop delivering frames (or close the connection) without an error,
    // which leaves a frozen picture until the user zaps away and back. Detect that and reload the stream.
    private var lastProgress = -1L
    private var progressAt = 0L
    private var bufferingSince = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            checkStall()
            updateCushion()
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }
    private val commitDigits = Runnable {
        val num = digits.toIntOrNull()
        digits = ""
        val i = playlist.indexOfFirst { it.num == num }
        if (i >= 0) play(i)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        diag = Diagnostics(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Repo.api == null) { finish(); return }

        val http = OkHttpDataSource.Factory(streamHttpClient)
        val dataSource =
            if (isLiveTs) ReconnectingDataSource.Factory(http) { diag.record(Diagnostics.Event.SEAMLESS) } else http
        val p = ExoPlayer.Builder(this)
            .setRenderersFactory(DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSource))
            .setWakeMode(MediaC.WAKE_MODE_NETWORK) // keeps Wi-Fi out of power save while streaming
            .build()
        player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                bufferingSince = if (buffering) System.currentTimeMillis() else 0L
                if (state == Player.STATE_READY) { retries = 0; error = null }
                if (state == Player.STATE_ENDED) {
                    // A live stream never really ends: the server dropped the connection, so reconnect.
                    if (isLive) { diag.record(Diagnostics.Event.ENDED); handler.postDelayed(retry, 500) } else finish()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                paused = !playWhenReady
            }

            override fun onPlayerError(e: PlaybackException) {
                if (isLive) diag.record(Diagnostics.Event.ERROR, e.errorCodeName)
                if (isLive && retries < MAX_RETRIES) {
                    retries++
                    error = S.reconnecting(retries, MAX_RETRIES)
                    handler.removeCallbacks(retry)
                    handler.postDelayed(retry, 2000)
                } else {
                    error = S.playbackError(e.errorCodeName)
                }
            }
        })

        if (isLive) {
            val id = intent.getIntExtra(EXTRA_STREAM_ID, -1)
            val i = playlist.indexOfFirst { it.streamId == id }
            if (i < 0) { finish(); return }
            play(i)
            handler.postDelayed(watchdog, WATCHDOG_MS)
        } else {
            p.setMediaItem(MediaItem.fromUri(intent.getStringExtra(EXTRA_URL)!!))
            p.prepare()
            val start = intent.getLongExtra(EXTRA_START_MS, 0L)
            if (start > 0) p.seekTo(start)
            p.play()
            showOverlay()
        }

        setContent { MojaTheme { PlayerScreen() } }
    }

    private fun play(i: Int) {
        val p = player ?: return
        if (playlist.isEmpty()) return
        index = ((i % playlist.size) + playlist.size) % playlist.size
        val ch = playlist[index]
        retries = 0
        error = null
        handler.removeCallbacks(retry)
        p.setMediaItem(MediaItem.fromUri(Repo.api!!.liveUrl(ch.streamId, prefs.liveFormat)))
        p.prepare()
        p.play()
        prefs.lastChannelId = ch.streamId
        resetStall()
        lifecycleScope.launch { Repo.loadEpg(ch.streamId) }
        showOverlay()
    }

    /** Reloads the current stream from scratch — the same thing that happens when zapping away and back. */
    private fun reconnect() {
        val p = player ?: return
        handler.removeCallbacks(retry)
        if (isLive) {
            val ch = playlist.getOrNull(index) ?: return
            p.stop()
            p.setMediaItem(MediaItem.fromUri(Repo.api!!.liveUrl(ch.streamId, prefs.liveFormat)))
        }
        p.prepare()
        p.play()
        resetStall()
    }

    private fun resetStall() {
        lastProgress = -1L
        progressAt = System.currentTimeMillis()
    }

    private fun checkStall() {
        val p = player ?: return
        val now = System.currentTimeMillis()
        if (!p.playWhenReady || error != null) { resetStall(); return }
        when (p.playbackState) {
            Player.STATE_BUFFERING -> {
                if (bufferingSince > 0 && now - bufferingSince > MAX_BUFFERING_MS) {
                    bufferingSince = now
                    diag.record(Diagnostics.Event.BUFFERING)
                    reconnect()
                }
            }
            Player.STATE_READY -> {
                // Rendered video frames catch a frozen picture even when audio (and the clock) keeps going;
                // fall back to the playback position for audio-only channels.
                val frames = p.videoDecoderCounters?.let { it.ensureUpdated(); it.renderedOutputBufferCount.toLong() }
                val progress = frames ?: p.currentPosition
                if (progress != lastProgress) {
                    lastProgress = progress
                    progressAt = now
                } else if (now - progressAt > STALL_MS) {
                    diag.record(Diagnostics.Event.FROZEN)
                    reconnect()
                }
            }
            else -> resetStall()
        }
    }

    /**
     * Live TS arrives at real-time speed, so the buffer never grows by itself. Playing 3% slower (pitch kept)
     * builds a cushion over a few minutes that the reconnecting source plays from while it reconnects.
     */
    private fun updateCushion() {
        val p = player ?: return
        if (!isLiveTs || p.playbackState != Player.STATE_READY) return
        val buffered = p.totalBufferedDuration
        val speed = when {
            buffered < CUSHION_LOW_MS -> CUSHION_SPEED
            buffered > CUSHION_HIGH_MS -> 1f
            else -> return
        }
        if (p.playbackParameters.speed != speed) p.playbackParameters = PlaybackParameters(speed)
    }

    private fun showOverlay() {
        overlay = true
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, OVERLAY_MS)
    }

    private fun togglePause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        showOverlay()
    }

    private fun seekBy(ms: Long) {
        val p = player ?: return
        p.seekTo((p.currentPosition + ms).coerceIn(0, p.duration.coerceAtLeast(0)))
        showOverlay()
    }

    /** Handled before the view hierarchy so Compose focus never swallows D-pad keys. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!listVisible && event.action == KeyEvent.ACTION_DOWN && handleKey(event.keyCode)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleKey(code: Int): Boolean {
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP ->
                if (isLive) play(index + 1) else return false
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN ->
                if (isLive) play(index - 1) else return false
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (isLive) { if (overlay) listVisible = true else showOverlay() } else togglePause()
            KeyEvent.KEYCODE_DPAD_LEFT -> if (isLive) listVisible = true else seekBy(-10_000)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (isLive) showOverlay() else seekBy(10_000)
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> if (isLive) listVisible = true else showOverlay()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> togglePause()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (!isLive) seekBy(60_000) else return false
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (!isLive) seekBy(-60_000) else return false
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                if (!isLive) return false
                digits = (digits + (code - KeyEvent.KEYCODE_0)).takeLast(4)
                handler.removeCallbacks(commitDigits)
                handler.postDelayed(commitDigits, 2000)
            }
            else -> return false
        }
        return true
    }

    private fun savePosition() {
        val p = player ?: return
        if (isLive) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        prefs.onPlaybackStopped(key, p.currentPosition, p.duration)
    }

    override fun onPause() {
        savePosition()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // A TV player has no reason to keep streaming in the background.
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }

    // ---------------- UI ----------------

    @Composable
    private fun PlayerScreen() {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        isFocusable = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        player = this@PlayerActivity.player
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = C.accent)
            if (paused && !buffering) Text("❚❚", Modifier.align(Alignment.Center), color = Color.White, fontSize = 64.sp)
            error?.let {
                Text(
                    it, Modifier.align(Alignment.TopCenter).padding(24.dp)
                        .background(Color(0xAA000000)).padding(12.dp),
                    color = Color(0xFFFF8A80),
                )
            }
            if (digits.isNotEmpty()) {
                Text(
                    digits, Modifier.align(Alignment.TopEnd).padding(32.dp)
                        .background(Color(0xAA000000)).padding(horizontal = 16.dp, vertical = 4.dp),
                    color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold,
                )
            }
            if (overlay) {
                if (isLive) LiveOverlay(Modifier.align(Alignment.BottomCenter))
                else VodOverlay(Modifier.align(Alignment.BottomCenter))
            }
            if (listVisible) ChannelListOverlay()
        }
    }

    @Composable
    private fun Tick(periodMs: Long): Long {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                delay(periodMs)
            }
        }
        return now
    }

    @Composable
    private fun LiveOverlay(modifier: Modifier) {
        val ch = playlist.getOrNull(index) ?: return
        val nowMs = Tick(1000)
        val nowSec = nowMs / 1000
        val cur = Repo.currentProgramme(ch.streamId, nowSec)
        val next = Repo.nextProgramme(ch.streamId, nowSec)
        Column(
            modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000))))
                .padding(horizontal = 40.dp, vertical = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = ch.icon, contentDescription = null, modifier = Modifier.size(72.dp, 54.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${ch.num}   ${ch.name}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (cur != null) {
                        Text(
                            "${hhmm(cur.start)} – ${hhmm(cur.end)}   ${cur.title}",
                            color = C.text, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val frac = ((nowSec - cur.start).toFloat() / (cur.end - cur.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.fillMaxWidth(0.5f).height(4.dp).padding(top = 4.dp),
                            color = C.accent, trackColor = Color(0x55FFFFFF),
                        )
                    }
                    if (next != null) {
                        Text(
                            "${S.next}: ${hhmm(next.start)}   ${next.title}",
                            color = C.dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(hhmm(nowSec), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            Text(S.liveHelp, color = C.dim, fontSize = 12.sp)
        }
    }

    @Composable
    private fun VodOverlay(modifier: Modifier) {
        Tick(500)
        val p = player ?: return
        val pos = p.currentPosition.coerceAtLeast(0)
        val dur = p.duration.coerceAtLeast(0)
        Column(
            modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000))))
                .padding(horizontal = 40.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                intent.getStringExtra(EXTRA_TITLE) ?: "", color = Color.White, fontSize = 24.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { if (dur > 0) pos.toFloat() / dur else 0f },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = C.accent, trackColor = Color(0x55FFFFFF),
            )
            Row {
                Text(formatMs(pos), color = C.text)
                Spacer(Modifier.weight(1f))
                Text(if (dur > 0) formatMs(dur) else "", color = C.dim)
            }
            Text(S.vodHelp, color = C.dim, fontSize = 12.sp)
        }
    }

    @Composable
    private fun ChannelListOverlay() {
        BackHandler { listVisible = false }
        val state = rememberLazyListState(initialFirstVisibleItemIndex = (index - 4).coerceAtLeast(0))
        val fr = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            delay(80)
            runCatching { fr.requestFocus() }
        }
        Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
            LazyColumn(
                Modifier.width(440.dp).fillMaxHeight().background(C.panel.copy(alpha = 0.96f)),
                state = state,
            ) {
                itemsIndexed(playlist, key = { _, ch -> ch.streamId }) { i, ch ->
                    LaunchedEffect(ch.streamId) { Repo.loadEpg(ch.streamId) }
                    val cur = Repo.currentProgramme(ch.streamId)
                    FocusBox(
                        onClick = { listVisible = false; play(i) },
                        modifier = Modifier.fillMaxWidth().then(if (i == index) Modifier.focusRequester(fr) else Modifier),
                        selected = i == index,
                        color = Color.Transparent,
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ch.num.toString(), Modifier.width(44.dp), color = C.dim, fontSize = 13.sp)
                            AsyncImage(model = ch.icon, contentDescription = null, modifier = Modifier.size(40.dp, 30.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(ch.name, color = C.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (cur != null) Text(cur.title, color = C.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
