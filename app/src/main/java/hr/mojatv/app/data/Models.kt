package hr.mojatv.app.data

data class Credentials(val server: String, val username: String, val password: String)

data class Category(val id: String, val name: String)

data class LiveChannel(
    val streamId: Int,
    val name: String,
    val icon: String?,
    val categoryId: String,
    val epgChannelId: String?,
    val num: Int,
)

data class Programme(
    val title: String,
    val description: String,
    val start: Long, // epoch seconds
    val end: Long,
)

data class Movie(
    val streamId: Int,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val extension: String,
    val rating: String?,
    /** Epoch seconds when the provider added it; drives "Newly added". */
    val added: Long,
)

data class MovieInfo(
    val plot: String,
    val genre: String,
    val releaseDate: String,
    val duration: String,
    val cast: String,
    val director: String,
    val backdrop: String?,
)

data class Series(
    val seriesId: Int,
    val name: String,
    val cover: String?,
    val categoryId: String,
    val plot: String?,
    val rating: String?,
    /** Epoch seconds of the last change (new episodes etc.); drives "Recently updated". */
    val lastModified: Long,
)

data class Episode(
    val id: String,
    val season: Int,
    val episodeNum: Int,
    val title: String,
    val extension: String,
    val plot: String,
    val image: String?,
    val duration: String,
)

data class SeriesInfo(
    val plot: String,
    val genre: String,
    val releaseDate: String,
    val cast: String,
    val director: String,
    val backdrop: String?,
    val seasons: Map<Int, List<Episode>>,
)

/** Subscription details from the login response. */
data class AccountInfo(val status: String, val expiresAt: Long?, val maxConnections: Int?, val isTrial: Boolean)
