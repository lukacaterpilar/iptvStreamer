package hr.mojatv.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Source for a live TS stream that survives dropped connections. When the server closes the stream or stops
 * sending data, it quietly reopens the same URL and keeps feeding bytes, so the player plays on from its
 * buffer instead of failing; the TS extractor resyncs on the next packet by itself.
 * Gives up (throws) only after [MAX_ATTEMPTS] reconnects in a row deliver nothing.
 */
@UnstableApi
class ReconnectingDataSource(
    private val upstreamFactory: DataSource.Factory,
    private val onReconnect: () -> Unit,
) : DataSource {

    class Factory(private val upstream: DataSource.Factory, private val onReconnect: () -> Unit) : DataSource.Factory {
        override fun createDataSource(): DataSource = ReconnectingDataSource(upstream, onReconnect)
    }

    private companion object {
        const val MAX_ATTEMPTS = 6
        const val RETRY_DELAY_MS = 500L
    }

    private val listeners = mutableListOf<TransferListener>()
    private var upstream: DataSource? = null
    private var reopenSpec: DataSpec? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        upstream?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        // A live stream starts wherever the server is now, so a reconnect always asks for it from the top.
        reopenSpec = dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build()
        upstream = newUpstream().also { it.open(dataSpec) }
        return C.LENGTH_UNSET.toLong() // never ends
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var attempt = 0
        while (true) {
            upstream?.let { src ->
                try {
                    val n = src.read(buffer, offset, length)
                    if (n != C.RESULT_END_OF_INPUT) return n
                } catch (e: IOException) {
                    if (cancelled()) throw e // the player stopped this load; don't fight it
                }
            }
            if (++attempt > MAX_ATTEMPTS) throw IOException("Live stream lost after $MAX_ATTEMPTS reconnects")
            reconnect(attempt)
        }
    }

    private fun reconnect(attempt: Int) {
        closeUpstream()
        if (attempt > 1) {
            try {
                Thread.sleep(RETRY_DELAY_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            }
        }
        if (cancelled()) throw InterruptedIOException()
        val src = newUpstream()
        try {
            src.open(reopenSpec!!)
            upstream = src
            onReconnect()
        } catch (e: IOException) {
            runCatching { src.close() }
            if (cancelled()) throw e
        }
    }

    private fun cancelled() = Thread.currentThread().isInterrupted

    private fun newUpstream(): DataSource =
        upstreamFactory.createDataSource().also { src -> listeners.forEach(src::addTransferListener) }

    private fun closeUpstream() {
        val src = upstream ?: return
        upstream = null
        runCatching { src.close() }
    }

    override fun getUri(): Uri? = upstream?.uri ?: reopenSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders ?: emptyMap()

    override fun close() {
        closeUpstream()
    }
}
