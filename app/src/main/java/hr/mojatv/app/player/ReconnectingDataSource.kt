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
 *
 * Xtream servers start every connection with a burst of back-buffer (tens of seconds that were already
 * played). Splicing that in as-is makes the timestamps jump backwards, which freezes the picture until the
 * stall watchdog reloads the channel. So this source remembers the last PTS it delivered per PID and, after
 * a reconnect, drops packets until each elementary stream is past that point again, i.e. it resumes exactly
 * where the old connection stopped.
 *
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

        const val PACKET = 188
        const val SYNC: Byte = 0x47
        const val PTS_MASK = (1L shl 33) - 1
        /** PTS differences smaller than this (half the 33-bit range) count as "after", larger ones as wrap. */
        const val PTS_HALF = 1L shl 32
        /** After the first stream catches up, wait at most this long (90 kHz ticks) for the others. */
        const val GRACE_TICKS = 90_000L
        /** Replay this far behind what was played is not a replay but a new timeline; stop filtering. */
        const val MAX_BEHIND_TICKS = 5 * 60 * 90_000L
        /** Safety valve: never drop more than this per reconnect. */
        const val MAX_SKIP_BYTES = 128L * 1024 * 1024
        const val CHUNK = 64 * 1024
    }

    private val listeners = mutableListOf<TransferListener>()
    private var upstream: DataSource? = null
    private var reopenSpec: DataSpec? = null
    private var attempts = 0

    /** Last PTS delivered to the player, per PID. Fed by [track] on everything that leaves [read]. */
    private val lastPts = HashMap<Int, Long>()
    private val carry = ByteArray(PACKET)
    private var carryLen = 0

    // State of the replay filter that runs right after a reconnect.
    private var skipping = false
    private val passed = HashSet<Int>()
    private var deadline = -1L
    private var skippedBytes = 0L
    private val scratch = ByteArray(CHUNK)
    private var scratchLen = 0
    private val out = ByteArray(CHUNK)
    private var outLen = 0
    private var outPos = 0

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        upstream?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        // A live stream starts wherever the server is now, so a reconnect always asks for it from the top.
        reopenSpec = dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build()
        lastPts.clear()
        carryLen = 0
        upstream = newUpstream().also { it.open(dataSpec) }
        return C.LENGTH_UNSET.toLong() // never ends
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        attempts = 0
        while (true) {
            if (outPos < outLen) {
                val n = minOf(length, outLen - outPos)
                System.arraycopy(out, outPos, buffer, offset, n)
                outPos += n
                track(buffer, offset, n)
                return n
            }
            if (skipping) {
                fillFiltered()
                continue
            }
            val n = readUpstream(buffer, offset, length)
            if (n > 0) {
                track(buffer, offset, n)
                return n
            }
        }
    }

    /** Reads from the current connection; on end of stream or failure reconnects and returns 0. */
    private fun readUpstream(buffer: ByteArray, offset: Int, length: Int): Int {
        upstream?.let { src ->
            try {
                val n = src.read(buffer, offset, length)
                if (n != C.RESULT_END_OF_INPUT) return n
            } catch (e: IOException) {
                if (cancelled()) throw e // the player stopped this load; don't fight it
            }
        }
        if (++attempts > MAX_ATTEMPTS) throw IOException("Live stream lost after $MAX_ATTEMPTS reconnects")
        reconnect(attempts)
        return 0
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
            startSkipping()
            onReconnect()
        } catch (e: IOException) {
            runCatching { src.close() }
            if (cancelled()) throw e
        }
    }

    // ---- replay filter ----

    private fun startSkipping() {
        skipping = lastPts.isNotEmpty() // nothing delivered yet = nothing to skip
        passed.clear()
        deadline = -1L
        skippedBytes = 0L
        scratchLen = 0
        outLen = 0
        outPos = 0
        carryLen = 0 // the old connection may have died mid-packet; the new one starts on a packet boundary
    }

    /** Pulls a chunk from the new connection and moves the packets worth delivering into [out]. */
    private fun fillFiltered() {
        val n = readUpstream(scratch, scratchLen, scratch.size - scratchLen)
        if (n <= 0) return // reconnected again (state was reset) or nothing arrived
        scratchLen += n
        outLen = 0
        outPos = 0
        var i = 0
        while (skipping && scratchLen - i >= PACKET) {
            if (scratch[i] != SYNC) { i++; continue } // not aligned: hunt for the next sync byte
            if (keep(scratch, i)) {
                System.arraycopy(scratch, i, out, outLen, PACKET)
                outLen += PACKET
            } else {
                skippedBytes += PACKET
                if (skippedBytes > MAX_SKIP_BYTES) skipping = false
            }
            i += PACKET
        }
        if (skipping) {
            // Keep the partial packet for the next chunk.
            System.arraycopy(scratch, i, scratch, 0, scratchLen - i)
            scratchLen -= i
        } else {
            // Caught up: everything from here on goes through untouched.
            System.arraycopy(scratch, i, out, outLen, scratchLen - i)
            outLen += scratchLen - i
            scratchLen = 0
        }
    }

    /** Decides for one packet of the replayed stream whether the player should see it. May end skipping. */
    private fun keep(b: ByteArray, pos: Int): Boolean {
        val pid = pid(b, pos)
        val pts = packetPts(b, pos)
        if (pts < 0) {
            // Continuation of a PES, or a table (PAT/PMT). Tables repeat constantly, so only pass them once
            // the real data has caught up.
            return pid in passed || (pid !in lastPts && passed.isNotEmpty())
        }
        val last = lastPts[pid]
        when {
            last == null || isAfter(pts, last) -> passed += pid
            behind(pts, last) > MAX_BEHIND_TICKS -> { skipping = false; return true } // different timeline
            else -> return false
        }
        if (deadline < 0) deadline = (pts + GRACE_TICKS) and PTS_MASK
        if (passed.containsAll(lastPts.keys) || isAfter(pts, deadline)) skipping = false
        return true
    }

    // ---- TS bookkeeping ----

    /** Scans bytes handed to the player and records the newest PTS per PID. Handles packets split across reads. */
    private fun track(b: ByteArray, offset: Int, length: Int) {
        var i = offset
        val end = offset + length
        if (carryLen > 0) {
            val need = PACKET - carryLen
            if (length < need) {
                System.arraycopy(b, offset, carry, carryLen, length)
                carryLen += length
                return
            }
            System.arraycopy(b, offset, carry, carryLen, need)
            i += need
            carryLen = 0
            note(carry, 0)
        }
        while (i < end) {
            if (b[i] != SYNC) { i++; continue }
            if (end - i < PACKET) {
                System.arraycopy(b, i, carry, 0, end - i)
                carryLen = end - i
                return
            }
            note(b, i)
            i += PACKET
        }
    }

    private fun note(b: ByteArray, pos: Int) {
        val pts = packetPts(b, pos)
        if (pts >= 0) lastPts[pid(b, pos)] = pts
    }

    private fun pid(b: ByteArray, pos: Int): Int = ((b[pos + 1].toInt() and 0x1F) shl 8) or (b[pos + 2].toInt() and 0xFF)

    /** PTS of the PES packet starting in this TS packet, or -1 if it carries none. */
    private fun packetPts(b: ByteArray, pos: Int): Long {
        if (b[pos + 1].toInt() and 0x40 == 0) return -1 // no payload_unit_start_indicator
        val afc = (b[pos + 3].toInt() shr 4) and 3
        if (afc and 1 == 0) return -1 // adaptation field only
        var p = pos + 4
        if (afc and 2 != 0) p += 1 + (b[pos + 4].toInt() and 0xFF)
        if (p + 14 > pos + PACKET) return -1
        if (b[p].toInt() != 0 || b[p + 1].toInt() != 0 || b[p + 2].toInt() != 1) return -1 // not a PES start
        if (b[p + 6].toInt() and 0xC0 != 0x80) return -1 // no optional header (padding, private_2 ...)
        if (b[p + 7].toInt() and 0x80 == 0) return -1 // PTS_DTS_flags: no PTS
        val q = p + 9
        if ((b[q].toInt() shr 4) and 0x0F !in 2..3) return -1
        return ((b[q].toLong() and 0x0E) shl 29) or
            ((b[q + 1].toLong() and 0xFF) shl 22) or
            ((b[q + 2].toLong() and 0xFE) shl 14) or
            ((b[q + 3].toLong() and 0xFF) shl 7) or
            ((b[q + 4].toLong() and 0xFE) shr 1)
    }

    /** True when [a] comes after [b] on the 33-bit PTS circle. */
    private fun isAfter(a: Long, b: Long): Boolean = ((a - b) and PTS_MASK).let { it in 1 until PTS_HALF }

    /** How far [a] is behind [b] (0 when it is not). */
    private fun behind(a: Long, b: Long): Long = ((b - a) and PTS_MASK).let { if (it < PTS_HALF) it else 0 }

    // ---- plumbing ----

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
        skipping = false
        scratchLen = 0
        outLen = 0
        outPos = 0
    }
}
