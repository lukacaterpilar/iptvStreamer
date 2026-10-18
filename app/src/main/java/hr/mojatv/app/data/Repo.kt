package hr.mojatv.app.data

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Process-wide in-memory cache shared by the browser screens and the player. */
object Repo {
    var api: XtreamApi? = null
        private set

    fun init(c: Credentials) {
        if (api == null || currentCreds != c) {
            api = XtreamApi(c)
            currentCreds = c
            clear()
        }
    }

    private var currentCreds: Credentials? = null

    private val liveMutex = Mutex()
    private var liveCategories: List<Category>? = null
    private var liveChannels: List<LiveChannel>? = null

    fun liveCached(): Pair<List<Category>, List<LiveChannel>>? {
        val c = liveCategories ?: return null
        val ch = liveChannels ?: return null
        return c to ch
    }

    suspend fun live(): Pair<List<Category>, List<LiveChannel>> = liveMutex.withLock {
        val a = api!!
        val cats = liveCategories ?: a.liveCategories().also { liveCategories = it }
        val chans = liveChannels ?: a.liveStreams().also { liveChannels = it }
        cats to chans
    }

    /** Channel list the player zaps through with up/down. Set right before opening the player. */
    var playlist: List<LiveChannel> = emptyList()

    // --- EPG ---
    private const val EPG_MAX_AGE_MS = 30 * 60_000L

    val epg = mutableStateMapOf<Int, List<Programme>>()
    private val epgFetchedAt = HashMap<Int, Long>()
    private val epgLoading = HashSet<Int>()
    private val epgSemaphore = Semaphore(4)

    /** Fetches the short EPG for a channel unless a fresh copy is cached. Safe to call often. */
    suspend fun loadEpg(streamId: Int) {
        val now = System.currentTimeMillis()
        synchronized(epgLoading) {
            val fetched = epgFetchedAt[streamId]
            val fresh = fetched != null && now - fetched < EPG_MAX_AGE_MS
            if (fresh || !epgLoading.add(streamId)) return
        }
        try {
            val list = epgSemaphore.withPermit { runCatching { api!!.shortEpg(streamId) }.getOrDefault(emptyList()) }
            epg[streamId] = list
            synchronized(epgLoading) { epgFetchedAt[streamId] = System.currentTimeMillis() }
        } finally {
            synchronized(epgLoading) { epgLoading.remove(streamId) }
        }
    }

    fun currentProgramme(streamId: Int, nowSec: Long = System.currentTimeMillis() / 1000): Programme? =
        epg[streamId]?.firstOrNull { nowSec in it.start until it.end }

    fun nextProgramme(streamId: Int, nowSec: Long = System.currentTimeMillis() / 1000): Programme? =
        epg[streamId]?.firstOrNull { it.start >= nowSec }

    /** Subscription details, fetched once per session by the settings screen. */
    var account: AccountInfo? = null

    // --- VOD / series ---
    var vodCategories: List<Category>? = null
    var seriesCategories: List<Category>? = null
    val movies = HashMap<String, List<Movie>>()
    val series = HashMap<String, List<Series>>()

    private val vodMutex = Mutex()
    private var allMovies: List<Movie>? = null
    private var allSeries: List<Series>? = null

    /** Every movie regardless of category; used by search, favourites and "Nastavi gledati". */
    suspend fun allMovies(): List<Movie> = vodMutex.withLock {
        allMovies ?: api!!.movies(null).also { allMovies = it }
    }

    suspend fun allSeries(): List<Series> = vodMutex.withLock {
        allSeries ?: api!!.series(null).also { allSeries = it }
    }

    suspend fun moviesIn(categoryId: String): List<Movie> =
        movies[categoryId] ?: api!!.movies(categoryId).also { movies[categoryId] = it }

    suspend fun seriesIn(categoryId: String): List<Series> =
        series[categoryId] ?: api!!.series(categoryId).also { series[categoryId] = it }

    fun clear() {
        liveCategories = null; liveChannels = null
        epg.clear()
        synchronized(epgLoading) { epgFetchedAt.clear() }
        vodCategories = null; seriesCategories = null
        movies.clear(); series.clear()
        allMovies = null; allSeries = null
        account = null
    }
}
