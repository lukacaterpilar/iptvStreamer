package hr.mojatv.app.data

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

sealed class LoginError {
    data object BadResponse : LoginError()
    data object BadCredentials : LoginError()
    data class Inactive(val status: String) : LoginError()
}

val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
    }
    .build()

/** Client for the Xtream Codes `player_api.php` API. */
class XtreamApi(private val c: Credentials) {

    private val base = c.server.trimEnd('/')

    fun liveUrl(streamId: Int, ext: String) = "$base/live/${c.username}/${c.password}/$streamId.$ext"
    fun movieUrl(m: Movie) = "$base/movie/${c.username}/${c.password}/${m.streamId}.${m.extension}"
    fun episodeUrl(e: Episode) = "$base/series/${c.username}/${c.password}/${e.id}.${e.extension}"

    private fun apiUrl(params: Map<String, String>): String {
        val b = "$base/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", c.username)
            .addQueryParameter("password", c.password)
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build().toString()
    }

    private suspend fun <T> get(params: Map<String, String>, parse: (JsonReader) -> T): T =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(apiUrl(params)).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("Empty response")
                JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use(parse)
            }
        }

    private suspend fun getText(params: Map<String, String>): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(apiUrl(params)).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
    }

    /** Returns null if the login is valid, otherwise why it failed. Network errors throw. */
    suspend fun login(): LoginError? {
        val text = getText(emptyMap())
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return LoginError.BadResponse
        val user = json.optJSONObject("user_info") ?: return LoginError.BadCredentials
        if (user.optInt("auth", 0) != 1) return LoginError.BadCredentials
        val status = user.optString("status")
        if (status.isNotEmpty() && !status.equals("Active", ignoreCase = true)) return LoginError.Inactive(status)
        return null
    }

    suspend fun liveCategories() = get(mapOf("action" to "get_live_categories"), ::readCategories)
    suspend fun vodCategories() = get(mapOf("action" to "get_vod_categories"), ::readCategories)
    suspend fun seriesCategories() = get(mapOf("action" to "get_series_categories"), ::readCategories)

    suspend fun liveStreams(): List<LiveChannel> = get(mapOf("action" to "get_live_streams")) { r ->
        readObjects(r) { o ->
            LiveChannel(
                streamId = o.int("stream_id") ?: return@readObjects null,
                name = o.str("name") ?: "",
                icon = o.str("stream_icon")?.takeIf { it.isNotBlank() },
                categoryId = o.str("category_id") ?: "",
                epgChannelId = o.str("epg_channel_id")?.takeIf { it.isNotBlank() },
                num = o.int("num") ?: 0,
            )
        }
    }

    /** Movies in one category, or every movie when [categoryId] is null. */
    suspend fun movies(categoryId: String?): List<Movie> =
        get(vodParams("get_vod_streams", categoryId)) { r ->
            readObjects(r) { o ->
                Movie(
                    streamId = o.int("stream_id") ?: return@readObjects null,
                    name = o.str("name") ?: "",
                    poster = o.str("stream_icon")?.takeIf { it.isNotBlank() },
                    categoryId = o.str("category_id") ?: categoryId ?: "",
                    extension = o.str("container_extension")?.takeIf { it.isNotBlank() } ?: "mp4",
                    rating = o.str("rating")?.takeIf { it.isNotBlank() && it != "0" },
                )
            }
        }

    suspend fun series(categoryId: String?): List<Series> =
        get(vodParams("get_series", categoryId)) { r ->
            readObjects(r) { o ->
                Series(
                    seriesId = o.int("series_id") ?: return@readObjects null,
                    name = o.str("name") ?: "",
                    cover = o.str("cover")?.takeIf { it.isNotBlank() },
                    categoryId = o.str("category_id") ?: categoryId ?: "",
                    plot = o.str("plot"),
                    rating = o.str("rating")?.takeIf { it.isNotBlank() && it != "0" },
                )
            }
        }

    private fun vodParams(action: String, categoryId: String?): Map<String, String> =
        if (categoryId == null) mapOf("action" to action) else mapOf("action" to action, "category_id" to categoryId)

    suspend fun movieInfo(streamId: Int): MovieInfo {
        val json = JSONObject(getText(mapOf("action" to "get_vod_info", "vod_id" to streamId.toString())))
        val info = json.optJSONObject("info") ?: JSONObject()
        return MovieInfo(
            plot = info.optString("plot").ifBlank { info.optString("description") },
            genre = info.optString("genre"),
            releaseDate = info.optString("releasedate").ifBlank { info.optString("release_date") },
            duration = info.optString("duration"),
            cast = info.optString("cast").ifBlank { info.optString("actors") },
            director = info.optString("director"),
        )
    }

    suspend fun seriesInfo(seriesId: Int): SeriesInfo {
        val json = JSONObject(getText(mapOf("action" to "get_series_info", "series_id" to seriesId.toString())))
        val info = json.optJSONObject("info") ?: JSONObject()
        val seasons = sortedMapOf<Int, List<Episode>>()
        // "episodes" is usually an object keyed by season number, but some panels send an array of arrays.
        val episodesAny = json.opt("episodes")
        val seasonArrays: List<Pair<Int, JSONArray>> = when (episodesAny) {
            is JSONObject -> episodesAny.keys().asSequence().mapNotNull { k ->
                episodesAny.optJSONArray(k)?.let { (k.toIntOrNull() ?: 0) to it }
            }.toList()
            is JSONArray -> (0 until episodesAny.length()).mapNotNull { i ->
                episodesAny.optJSONArray(i)?.let { arr ->
                    (arr.optJSONObject(0)?.optInt("season", i + 1) ?: (i + 1)) to arr
                }
            }
            else -> emptyList()
        }
        for ((season, arr) in seasonArrays) {
            seasons[season] = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val ei = e.optJSONObject("info") ?: JSONObject()
                Episode(
                    id = e.optString("id"),
                    season = e.optInt("season", season),
                    episodeNum = e.optInt("episode_num", i + 1),
                    title = e.optString("title"),
                    extension = e.optString("container_extension").ifBlank { "mp4" },
                    plot = ei.optString("plot"),
                    image = ei.optString("movie_image").ifBlank { null },
                    duration = ei.optString("duration"),
                )
            }.sortedBy { it.episodeNum }
        }
        return SeriesInfo(info.optString("plot"), info.optString("genre"), seasons)
    }

    /** Short EPG (current + upcoming) for one channel. Titles are base64-encoded by the API. */
    suspend fun shortEpg(streamId: Int, limit: Int = 30): List<Programme> {
        val json = JSONObject(
            getText(mapOf("action" to "get_short_epg", "stream_id" to streamId.toString(), "limit" to limit.toString()))
        )
        val arr = json.optJSONArray("epg_listings") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            val start = p.optString("start_timestamp").toLongOrNull() ?: return@mapNotNull null
            val end = p.optString("stop_timestamp").toLongOrNull()
                ?: p.optString("end_timestamp").toLongOrNull() ?: return@mapNotNull null
            Programme(b64(p.optString("title")), b64(p.optString("description")), start, end)
        }.sortedBy { it.start }
    }

    private fun b64(s: String): String =
        runCatching { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(s).trim()

    private fun readCategories(r: JsonReader): List<Category> = readObjects(r) { o ->
        Category(o.str("category_id") ?: return@readObjects null, o.str("category_name") ?: "")
    }

    /** Streams a JSON array of flat objects; nested values are skipped. Non-array responses yield an empty list. */
    private fun <T> readObjects(r: JsonReader, map: (Map<String, String?>) -> T?): List<T> {
        val out = ArrayList<T>()
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue()
            return out
        }
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) {
                r.skipValue(); continue
            }
            val fields = HashMap<String, String?>()
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName()
                when (r.peek()) {
                    JsonToken.STRING, JsonToken.NUMBER -> fields[name] = r.nextString()
                    JsonToken.BOOLEAN -> fields[name] = r.nextBoolean().toString()
                    JsonToken.NULL -> { r.nextNull(); fields[name] = null }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            map(fields)?.let(out::add)
        }
        r.endArray()
        return out
    }

    private fun Map<String, String?>.str(k: String) = this[k]
    private fun Map<String, String?>.int(k: String) = this[k]?.toDoubleOrNull()?.toInt()
}
