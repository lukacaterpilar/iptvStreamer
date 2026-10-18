package hr.mojatv.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Colours offered for the app title in settings; names come from the current language's strings. */
val TITLE_COLORS: List<Int> = listOf(
    0xFFFF5CA8.toInt(), 0xFF3D8BFF.toInt(), 0xFF43D17A.toInt(), 0xFFFF9F43.toInt(),
    0xFFFF5252.toInt(), 0xFFB06CFF.toInt(), 0xFFFFD54F.toInt(), 0xFFFFFFFF.toInt(),
)

/** One IPTV subscription. Favourites, progress and last channel are stored separately per profile. */
data class Profile(val id: Int, val name: String, val server: String, val username: String, val password: String) {
    val credentials get() = Credentials(server, username, password)
}

/**
 * Everything the app stores on the device. Global settings live in the main file; everything tied to a
 * subscription (favourites, playback positions, last channel…) lives in a per-profile file.
 */
class Prefs(context: Context) {
    private val ctx = context.applicationContext
    private val sp = ctx.getSharedPreferences("mojatv", Context.MODE_PRIVATE)

    init { migrateSingleAccount() }

    /** Per-profile store. Profile 0 keeps using the main file so data from the single-account days survives. */
    private val psp: SharedPreferences
        get() {
            val id = activeProfileId
            return if (id <= 0) sp else ctx.getSharedPreferences("mojatv_p$id", Context.MODE_PRIVATE)
        }

    // ---- profiles ----

    var profiles: List<Profile>
        get() = runCatching {
            val arr = JSONArray(sp.getString("profiles", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Profile(o.getInt("id"), o.getString("name"), o.getString("server"), o.getString("username"), o.getString("password"))
            }
        }.getOrDefault(emptyList())
        private set(v) {
            val arr = JSONArray()
            v.forEach {
                arr.put(
                    JSONObject().put("id", it.id).put("name", it.name).put("server", it.server)
                        .put("username", it.username).put("password", it.password)
                )
            }
            sp.edit().putString("profiles", arr.toString()).apply()
        }

    var activeProfileId: Int
        get() = sp.getInt("active_profile", -1)
        set(v) = sp.edit().putInt("active_profile", v).apply()

    val activeProfile: Profile? get() = profiles.firstOrNull { it.id == activeProfileId }

    val credentials: Credentials? get() = activeProfile?.credentials

    fun addProfile(name: String, c: Credentials): Profile {
        val list = profiles
        val id = if (list.isEmpty()) 0 else (list.maxOf { it.id } + 1)
        val p = Profile(id, name, c.server, c.username, c.password)
        profiles = list + p
        activeProfileId = id
        return p
    }

    /** Removes the profile and its data; if it was active, the first remaining profile becomes active. */
    fun removeProfile(id: Int) {
        val remaining = profiles.filter { it.id != id }
        profiles = remaining
        if (id > 0) ctx.getSharedPreferences("mojatv_p$id", Context.MODE_PRIVATE).edit().clear().apply()
        if (activeProfileId == id) activeProfileId = remaining.firstOrNull()?.id ?: -1
    }

    private fun migrateSingleAccount() {
        val server = sp.getString("server", null) ?: return
        val user = sp.getString("username", null)
        val pass = sp.getString("password", null)
        if (user != null && pass != null && profiles.isEmpty()) {
            val name = server.substringAfter("://").substringBefore("/").substringBefore(":")
            profiles = listOf(Profile(0, name, server, user, pass))
            activeProfileId = 0
        }
        sp.edit().remove("server").remove("username").remove("password").apply()
    }

    // ---- global settings ----

    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", true)
        set(v) = sp.edit().putBoolean("auto_start", v).apply()

    var resumeLastChannel: Boolean
        get() = sp.getBoolean("resume_last_channel", true)
        set(v) = sp.edit().putBoolean("resume_last_channel", v).apply()

    var appName: String
        get() = sp.getString("app_name", "Mama TV") ?: "Mama TV"
        set(v) = sp.edit().putString("app_name", v).apply()

    var appColor: Int
        get() = sp.getInt("app_color", TITLE_COLORS[0])
        set(v) = sp.edit().putInt("app_color", v).apply()

    /** Background picture key, see ui.BACKGROUNDS; "none" = plain colour. */
    var background: String
        get() = sp.getString("background", "space") ?: "space"
        set(v) = sp.edit().putString("background", v).apply()

    /** Language code: hr, en, it, de. */
    var language: String
        get() = sp.getString("language", "hr") ?: "hr"
        set(v) = sp.edit().putString("language", v).apply()

    /** true = refresh channel/VOD lists and EPG once a day; false = only with the "Refresh" button. */
    var refreshDaily: Boolean
        get() = sp.getBoolean("refresh_daily", true)
        set(v) = sp.edit().putBoolean("refresh_daily", v).apply()

    /** true = look for a new app version on GitHub at start and every few hours, download it and offer to install. */
    var autoUpdate: Boolean
        get() = sp.getBoolean("auto_update", true)
        set(v) = sp.edit().putBoolean("auto_update", v).apply()

    var lastRefresh: Long
        get() = sp.getLong("last_refresh", 0L)
        set(v) = sp.edit().putLong("last_refresh", v).apply()

    // ---- per-profile: live TV ----

    /** "ts" or "m3u8" — providers differ, so this is per profile. */
    var liveFormat: String
        get() = psp.getString("live_format", "ts") ?: "ts"
        set(v) = psp.edit().putString("live_format", v).apply()

    var lastChannelId: Int
        get() = psp.getInt("last_channel", -1)
        set(v) = psp.edit().putInt("last_channel", v).apply()

    var lastCategoryId: String?
        get() = psp.getString("last_category", null)
        set(v) = psp.edit().putString("last_category", v).apply()

    var favorites: Set<Int>
        get() = intSet("favorites")
        set(v) = putIntSet("favorites", v)

    fun toggleFavorite(id: Int): Boolean = toggleIn("favorites", id)

    // ---- per-profile: movies / series favourites ----

    val movieFavorites: Set<Int> get() = intSet("fav_movies")
    val seriesFavorites: Set<Int> get() = intSet("fav_series")
    fun toggleMovieFavorite(id: Int) = toggleIn("fav_movies", id)
    fun toggleSeriesFavorite(id: Int) = toggleIn("fav_series", id)

    // ---- per-profile: playback progress ----
    // Keys: "movie_<streamId>" or "ep_<seriesId>_<episodeId>".

    fun position(key: String): Long = psp.getLong("pos_$key", 0L)
    fun isWatched(key: String): Boolean = psp.getBoolean("watched_$key", false)

    /** Ids for the "Continue watching" list, most recently watched first. kind = "movie" | "series". */
    fun continueIds(kind: String): List<Int> {
        val prefix = "cw_${kind}_"
        return psp.all.entries
            .filter { it.key.startsWith(prefix) && it.value is Long }
            .sortedByDescending { it.value as Long }
            .mapNotNull { it.key.removePrefix(prefix).toIntOrNull() }
    }

    fun lastSeason(seriesId: Int): Int? = psp.getInt("season_$seriesId", -1).takeIf { it >= 0 }
    fun setLastSeason(seriesId: Int, season: Int) = psp.edit().putInt("season_$seriesId", season).apply()

    /**
     * Called by the player when playback stops. Within the last minute counts as finished:
     * the title is marked watched, its position reset and (for movies) removed from "Continue watching".
     */
    fun onPlaybackStopped(key: String, posMs: Long, durMs: Long) {
        val finished = durMs > 0 && posMs >= durMs - 60_000
        val parts = key.split("_")
        val kind = if (parts.firstOrNull() == "movie") "movie" else "series"
        val id = parts.getOrNull(1)?.toIntOrNull()
        psp.edit().apply {
            when {
                finished -> { remove("pos_$key"); putBoolean("watched_$key", true) }
                posMs > 5_000 -> putLong("pos_$key", posMs)
                else -> return@apply
            }
            if (id != null) {
                if (kind == "movie" && finished) remove("cw_movie_$id")
                else putLong("cw_${kind}_$id", System.currentTimeMillis())
            }
        }.apply()
    }

    // ---- helpers ----

    private fun intSet(key: String): Set<Int> =
        psp.getStringSet(key, emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()

    private fun putIntSet(key: String, v: Set<Int>) =
        psp.edit().putStringSet(key, v.map { it.toString() }.toSet()).apply()

    private fun toggleIn(key: String, id: Int): Boolean {
        val f = intSet(key)
        val now = id !in f
        putIntSet(key, if (now) f + id else f - id)
        return now
    }
}
