package hr.mojatv.app.data

import android.content.Context

/**
 * Counts how live playback recovered from problems, so someone can read the numbers out from Settings
 * over the phone. Written from the player's loader thread as well as the main thread.
 */
class Diagnostics(context: Context) {
    enum class Event {
        /** Connection dropped and was restored underneath the player, without interrupting the picture. */
        SEAMLESS,
        /** Server ended the stream; the channel was reloaded. */
        ENDED,
        /** Picture stopped moving although the player was playing; the channel was reloaded. */
        FROZEN,
        /** Buffering took too long; the channel was reloaded. */
        BUFFERING,
        /** Player reported an error. */
        ERROR,
    }

    private companion object { val lock = Any() }

    private val sp = context.applicationContext.getSharedPreferences("mojatv_diag", Context.MODE_PRIVATE)

    init { if (!sp.contains("since")) reset() }

    fun record(event: Event, detail: String? = null) = synchronized(lock) {
        val e = sp.edit().putInt(event.name, count(event) + 1)
        if (detail != null) e.putString("last_error", detail)
        e.apply()
    }

    private fun count(event: Event) = sp.getInt(event.name, 0)

    data class Stats(
        val seamless: Int, val ended: Int, val frozen: Int, val buffering: Int, val errors: Int,
        /** Epoch seconds since the counters were last reset. */
        val since: Long,
        val lastError: String?,
    ) {
        /** Times the channel had to be reloaded (each one is a short visible interruption). */
        val reloads get() = ended + frozen + buffering
    }

    fun snapshot() = Stats(
        count(Event.SEAMLESS), count(Event.ENDED), count(Event.FROZEN), count(Event.BUFFERING), count(Event.ERROR),
        sp.getLong("since", 0), sp.getString("last_error", null),
    )

    fun reset() = synchronized(lock) {
        sp.edit().clear().putLong("since", System.currentTimeMillis() / 1000).apply()
    }
}
