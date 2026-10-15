package hr.mojatv.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil.compose.AsyncImage
import hr.mojatv.app.data.Category
import hr.mojatv.app.data.Episode
import hr.mojatv.app.data.Movie
import hr.mojatv.app.data.MovieInfo
import hr.mojatv.app.data.Prefs
import hr.mojatv.app.data.Repo
import hr.mojatv.app.data.Series
import hr.mojatv.app.data.SeriesInfo
import hr.mojatv.app.player.PlayerActivity
import kotlinx.coroutines.delay

const val CAT_SEARCH = "__search"
const val CAT_CONTINUE = "__continue"

private val WATCHED_BG = Color(0xFF1B5E3B)
private val FAV_PINK = Color(0xFFFF5CA8)

private fun vodCategories(cats: List<Category>) = listOf(
    Category(CAT_SEARCH, S.search),
    Category(CAT_CONTINUE, S.continueWatching),
    Category(CAT_FAV, S.favorites),
) + cats

/** How a movie or series is displayed in the shared browser. */
private class VodSpec<T : Any>(
    val kind: String, // "movie" | "series" — matches Prefs keys
    val label: () -> String,
    val id: (T) -> Int,
    val name: (T) -> String,
    val poster: (T) -> String?,
    val rating: (T) -> String?,
)

private val MOVIE_SPEC = VodSpec<Movie>("movie", { S.moviesWord }, { it.streamId }, { it.name }, { it.poster }, { it.rating })
private val SERIES_SPEC = VodSpec<Series>("series", { S.seriesWord }, { it.seriesId }, { it.name }, { it.cover }, { it.rating })

/** Loads a category list once and remembers it in [Repo]. */
@Composable
private fun rememberCategories(
    cached: () -> List<Category>?,
    store: (List<Category>) -> Unit,
    fetch: suspend () -> List<Category>,
): Pair<List<Category>?, String?> {
    var cats by remember { mutableStateOf(cached()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (cats == null) runCatching { fetch() }
            .onSuccess { store(it); cats = it }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
    }
    return cats to error
}

@Composable
private fun PosterCard(
    title: String,
    image: String?,
    rating: String?,
    isFav: Boolean,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    FocusBox(
        onClick = onClick, onLongClick = onLongClick, onFocus = onFocus,
        modifier = modifier, color = Color.Transparent, shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(6.dp)) {
            AsyncImage(
                model = image, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(C.cell),
            )
            Text(title, Modifier.padding(top = 6.dp), color = C.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rating != null) Text("★ $rating", color = C.fav, fontSize = 12.sp, maxLines = 1)
                if (isFav) Text("♥", color = FAV_PINK, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, modifier: Modifier = Modifier, color: Color = C.accent, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier, color = color, shape = RoundedCornerShape(20.dp)) {
        Text(text, Modifier.padding(horizontal = 22.dp, vertical = 10.dp), color = Color.White, fontWeight = FontWeight.Bold)
    }
}

/**
 * Category column + poster grid shared by movies and series, with search, favourites,
 * "Nastavi gledati" and focus restore when coming back from a detail page.
 */
@Composable
private fun <T : Any> VodBrowser(
    spec: VodSpec<T>,
    prefs: Prefs,
    categories: List<Category>?,
    error: String?,
    loadCategory: suspend (String) -> List<T>,
    loadAll: suspend () -> List<T>,
    favorites: () -> Set<Int>,
    toggleFav: (Int) -> Boolean,
    detail: @Composable (item: T, favVersion: Int, onFavToggled: () -> Unit, onBack: () -> Unit) -> Unit,
) {
    val ctx = LocalContext.current
    var selectedCat by rememberSaveable(spec.kind) { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<T?>(null) }
    var query by rememberSaveable(spec.kind) { mutableStateOf("") }
    var lastFocusedId by rememberSaveable(spec.kind) { mutableIntStateOf(-1) }
    var pendingRestore by remember { mutableStateOf(false) }
    var favVersion by remember { mutableIntStateOf(0) }
    var resumeVersion by remember { mutableIntStateOf(0) }
    val gridState = rememberLazyGridState()
    val restoreFocus = remember { FocusRequester() }

    LifecycleResumeEffect(Unit) {
        resumeVersion++
        onPauseOrDispose { }
    }

    selected?.let { item ->
        detail(item, favVersion, { favVersion++ }) { selected = null; pendingRestore = true }
        return
    }
    if (error != null) { ErrorText(S.errorLoading(error)); return }
    val cats = categories ?: run { Loading(); return }
    val allCats = remember(cats, L10n.lang) { vodCategories(cats) }
    val current = selectedCat ?: cats.firstOrNull()?.id ?: CAT_SEARCH

    // Synthetic categories depend on favourites / playback progress, so reload them when those change.
    val reloadKey = when (current) {
        CAT_FAV -> favVersion
        CAT_CONTINUE -> resumeVersion
        else -> 0
    }
    var list by remember(current, reloadKey) { mutableStateOf<List<T>?>(null) }
    var listError by remember(current, reloadKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(current, reloadKey) {
        runCatching {
            when (current) {
                CAT_SEARCH -> loadAll()
                CAT_CONTINUE -> {
                    val byId = loadAll().associateBy(spec.id)
                    prefs.continueIds(spec.kind).mapNotNull { byId[it] }
                }
                CAT_FAV -> {
                    val f = favorites()
                    loadAll().filter { spec.id(it) in f }
                }
                else -> loadCategory(current)
            }
        }.onSuccess { list = it }.onFailure { listError = it.message ?: it.javaClass.simpleName }
    }

    val shown = remember(list, query, current) {
        val l = list ?: return@remember null
        if (current != CAT_SEARCH) l
        else if (query.isBlank()) emptyList()
        else l.filter { spec.name(it).contains(query.trim(), ignoreCase = true) }.take(400)
    }
    val favs = remember(favVersion) { favorites() }

    // Put the cursor back on the poster the user came from.
    LaunchedEffect(pendingRestore, shown) {
        if (!pendingRestore || shown == null) return@LaunchedEffect
        val idx = shown.indexOfFirst { spec.id(it) == lastFocusedId }
        if (idx >= 0) {
            if (gridState.layoutInfo.visibleItemsInfo.none { it.index == idx }) gridState.scrollToItem(idx)
            delay(60)
            runCatching { restoreFocus.requestFocus() }
        }
        pendingRestore = false
    }

    Row(Modifier.fillMaxSize()) {
        CategoryColumn(allCats, current) { selectedCat = it }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (current == CAT_SEARCH) {
                RemoteTextField(
                    value = query, onValueChange = { query = it }, label = S.searchLabel(spec.label()),
                    // Leaves room on the right for the phone-keyboard QR panel.
                    modifier = Modifier.fillMaxWidth(0.68f).padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
            Box(Modifier.weight(1f)) {
                when {
                    listError != null -> ErrorText(listError!!)
                    shown == null -> Loading()
                    shown.isEmpty() -> Text(
                        when (current) {
                            CAT_SEARCH -> if (query.isBlank()) S.searchHint else S.noResults(query)
                            CAT_CONTINUE -> S.continueEmpty(spec.label())
                            CAT_FAV -> S.noFavoriteVod
                            else -> S.emptyCategory
                        },
                        Modifier.padding(24.dp), color = C.dim,
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        state = gridState,
                        contentPadding = PaddingValues(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(shown, key = { spec.id(it) }) { item ->
                            val id = spec.id(item)
                            PosterCard(
                                title = spec.name(item), image = spec.poster(item), rating = spec.rating(item),
                                isFav = id in favs,
                                modifier = Modifier.fillMaxWidth()
                                    .then(if (id == lastFocusedId) Modifier.focusRequester(restoreFocus) else Modifier),
                                onFocus = { lastFocusedId = id },
                                onLongClick = {
                                    val added = toggleFav(id)
                                    Toast.makeText(ctx, if (added) S.addedFav else S.removedFav, Toast.LENGTH_SHORT).show()
                                    pendingRestore = true
                                    favVersion++
                                },
                                onClick = { selected = item },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Movies ----------------

@Composable
fun MoviesScreen(prefs: Prefs) {
    val api = Repo.api ?: return
    val (cats, error) = rememberCategories({ Repo.vodCategories }, { Repo.vodCategories = it }, { api.vodCategories() })
    VodBrowser(
        spec = MOVIE_SPEC, prefs = prefs, categories = cats, error = error,
        loadCategory = { Repo.moviesIn(it) }, loadAll = { Repo.allMovies() },
        favorites = { prefs.movieFavorites }, toggleFav = { prefs.toggleMovieFavorite(it) },
    ) { m, favVersion, onFavToggled, onBack -> MovieDetail(m, prefs, favVersion, onFavToggled, onBack) }
}

@Composable
private fun MovieDetail(m: Movie, prefs: Prefs, favVersion: Int, onFavToggled: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val api = Repo.api ?: return
    val key = "movie_${m.streamId}"
    var info by remember { mutableStateOf<MovieInfo?>(null) }
    var resumeMs by remember { mutableLongStateOf(prefs.position(key)) }
    var watched by remember { mutableStateOf(prefs.isWatched(key)) }
    val isFav = remember(favVersion) { m.streamId in prefs.movieFavorites }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(m.streamId) { info = runCatching { api.movieInfo(m.streamId) }.getOrNull() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    LifecycleResumeEffect(key) {
        resumeMs = prefs.position(key)
        watched = prefs.isWatched(key)
        onPauseOrDispose { }
    }

    fun start(from: Long) = ctx.startActivity(PlayerActivity.vodIntent(ctx, api.movieUrl(m), m.name, key, from))

    Row(Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        AsyncImage(
            model = m.poster, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.width(260.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(C.cell),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(m.name, color = C.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            val meta = listOfNotNull(
                info?.releaseDate?.take(4), info?.genre, info?.duration, m.rating?.let { "★ $it" },
                if (watched) S.watched else null,
            ).filter { it.isNotBlank() }
            if (meta.isNotEmpty()) Text(meta.joinToString("   •   "), color = C.dim)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (resumeMs > 0) {
                    PrimaryButton(S.resumeAt(formatMs(resumeMs)), Modifier.focusRequester(playFocus)) { start(resumeMs) }
                    PrimaryButton(S.fromStart) { start(0) }
                } else {
                    PrimaryButton(S.play, Modifier.focusRequester(playFocus)) { start(0) }
                }
                PrimaryButton(
                    if (isFav) S.removeFav else S.addFav,
                    color = if (isFav) FAV_PINK else C.cell,
                ) { prefs.toggleMovieFavorite(m.streamId); onFavToggled() }
            }
            Text(
                info?.plot ?: "", color = C.text, fontSize = 16.sp,
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            )
            info?.cast?.takeIf { it.isNotBlank() }?.let { Text(S.cast(it), color = C.dim, fontSize = 13.sp, maxLines = 2) }
            info?.director?.takeIf { it.isNotBlank() }?.let { Text(S.director(it), color = C.dim, fontSize = 13.sp) }
        }
    }
}

// ---------------- Series ----------------

@Composable
fun SeriesScreen(prefs: Prefs) {
    val api = Repo.api ?: return
    val (cats, error) = rememberCategories({ Repo.seriesCategories }, { Repo.seriesCategories = it }, { api.seriesCategories() })
    VodBrowser(
        spec = SERIES_SPEC, prefs = prefs, categories = cats, error = error,
        loadCategory = { Repo.seriesIn(it) }, loadAll = { Repo.allSeries() },
        favorites = { prefs.seriesFavorites }, toggleFav = { prefs.toggleSeriesFavorite(it) },
    ) { s, favVersion, onFavToggled, onBack -> SeriesDetail(s, prefs, favVersion, onFavToggled, onBack) }
}

@Composable
private fun SeriesDetail(s: Series, prefs: Prefs, favVersion: Int, onFavToggled: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val api = Repo.api ?: return
    var info by remember { mutableStateOf<SeriesInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var season by rememberSaveable { mutableStateOf(prefs.lastSeason(s.seriesId)) }
    var posVersion by remember { mutableIntStateOf(0) }
    val isFav = remember(favVersion) { s.seriesId in prefs.seriesFavorites }
    LaunchedEffect(s.seriesId) {
        runCatching { api.seriesInfo(s.seriesId) }
            .onSuccess { info = it }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
    }
    LifecycleResumeEffect(s.seriesId) {
        posVersion++
        onPauseOrDispose { }
    }

    fun episodeKey(e: Episode) = "ep_${s.seriesId}_${e.id}"

    fun play(e: Episode, from: Long) {
        prefs.setLastSeason(s.seriesId, e.season)
        ctx.startActivity(
            PlayerActivity.vodIntent(
                ctx, api.episodeUrl(e), "${s.name} – S%02dE%02d".format(e.season, e.episodeNum), episodeKey(e), from,
            )
        )
    }

    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncImage(
                model = s.cover, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(C.cell),
            )
            Text(s.name, color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            info?.genre?.takeIf { it.isNotBlank() }?.let { Text(it, color = C.dim, fontSize = 13.sp) }
            PrimaryButton(
                if (isFav) S.removeFav else S.addFav,
                color = if (isFav) FAV_PINK else C.cell,
            ) { prefs.toggleSeriesFavorite(s.seriesId); onFavToggled() }
            Text(
                info?.plot ?: s.plot ?: "", color = C.dim, fontSize = 13.sp,
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            )
        }
        Column(Modifier.weight(1f)) {
            if (error != null) { ErrorText(error!!); return@Column }
            val si = info ?: run { Loading(S.loadingEpisodes); return@Column }
            val seasons = si.seasons.keys.sorted()
            val sel = season?.takeIf { it in seasons } ?: seasons.firstOrNull()
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 10.dp)) {
                items(seasons) { n ->
                    FocusBox(
                        onClick = { season = n }, selected = n == sel,
                        color = C.panel, shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            S.season(n), Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            color = if (n == sel) Color.White else C.dim, fontSize = 14.sp,
                        )
                    }
                }
            }
            val episodes = sel?.let { si.seasons[it] } ?: emptyList()
            if (episodes.isEmpty()) {
                Text(S.noEpisodes, Modifier.padding(16.dp), color = C.dim)
                return@Column
            }
            // Land the cursor on the episode in progress, else the first unwatched one.
            val focusIdx = remember(episodes) {
                episodes.indexOfFirst { prefs.position(episodeKey(it)) > 0 }.takeIf { it >= 0 }
                    ?: episodes.indexOfFirst { !prefs.isWatched(episodeKey(it)) }.takeIf { it >= 0 } ?: 0
            }
            val listState = rememberLazyListState()
            val episodeFocus = remember { FocusRequester() }
            LaunchedEffect(episodes) {
                listState.scrollToItem(focusIdx)
                delay(60)
                runCatching { episodeFocus.requestFocus() }
            }
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(episodes, key = { _, e -> e.id }) { i, e ->
                    val key = episodeKey(e)
                    val pos = remember(key, posVersion) { prefs.position(key) }
                    val watched = remember(key, posVersion) { prefs.isWatched(key) }
                    FocusBox(
                        onClick = { play(e, pos) },
                        onLongClick = { play(e, 0) },
                        modifier = Modifier.fillMaxWidth()
                            .then(if (i == focusIdx) Modifier.focusRequester(episodeFocus) else Modifier),
                        color = if (watched) WATCHED_BG else C.panel,
                        focusedColor = if (watched) Color(0xFF2E8B57) else C.accent.copy(alpha = 0.35f),
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "E%02d".format(e.episodeNum), Modifier.width(48.dp),
                                color = if (watched) Color.White else C.accent, fontWeight = FontWeight.Bold,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(e.title.ifBlank { S.episode(e.episodeNum) }, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val sub = listOfNotNull(
                                    e.duration.takeIf { it.isNotBlank() },
                                    if (watched) S.watched else null,
                                    if (pos > 0) S.watchedUntil(formatMs(pos)) else null,
                                ).joinToString("   •   ")
                                if (sub.isNotEmpty()) Text(sub, color = if (watched) Color(0xFFC8F0D8) else C.dim, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
