package hr.mojatv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import hr.mojatv.app.data.Category
import hr.mojatv.app.data.LiveChannel
import hr.mojatv.app.data.Prefs
import hr.mojatv.app.data.Programme
import hr.mojatv.app.data.Repo
import hr.mojatv.app.player.PlayerActivity
import kotlinx.coroutines.delay

const val CAT_FAV = "__fav"
const val CAT_ALL = "__all"

fun channelsFor(catId: String, all: List<LiveChannel>, prefs: Prefs): List<LiveChannel> = when (catId) {
    CAT_ALL -> all
    CAT_FAV -> {
        val f = prefs.favorites
        all.filter { it.streamId in f }
    }
    else -> all.filter { it.categoryId == catId }
}

@Composable
private fun rememberLiveData(): Pair<Pair<List<Category>, List<LiveChannel>>?, String?> {
    var data by remember { mutableStateOf(Repo.liveCached()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (data == null) runCatching { Repo.live() }
            .onSuccess { data = it }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
    }
    return data to error
}

private fun withSyntheticCategories(cats: List<Category>) =
    listOf(Category(CAT_FAV, S.favorites), Category(CAT_ALL, S.allChannels)) + cats

@Composable
fun CategoryColumn(categories: List<Category>, selected: String?, onSelect: (String) -> Unit) {
    LazyColumn(
        Modifier.width(260.dp).fillMaxHeight().background(C.panel),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(categories, key = { it.id }) { cat ->
            FocusBox(
                onClick = { onSelect(cat.id) },
                modifier = Modifier.fillMaxWidth(),
                selected = cat.id == selected,
                color = Color.Transparent,
            ) {
                Text(
                    cat.name, Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    color = if (cat.id == selected) Color.White else C.text,
                    fontWeight = if (cat.id == selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun LiveScreen(prefs: Prefs) {
    val ctx = LocalContext.current
    val (data, error) = rememberLiveData()
    var selectedCat by rememberSaveable { mutableStateOf(prefs.lastCategoryId ?: CAT_ALL) }
    var favVersion by remember { mutableIntStateOf(0) }

    if (error != null) { ErrorText(S.errorLoadingChannels(error)); return }
    val (cats, chans) = data ?: run { Loading(S.loadingChannels); return }
    val categories = remember(cats, L10n.lang) { withSyntheticCategories(cats) }
    val favs = remember(favVersion) { prefs.favorites }
    val list = remember(selectedCat, chans, favVersion) { channelsFor(selectedCat, chans, prefs) }

    Row(Modifier.fillMaxSize()) {
        CategoryColumn(categories, selectedCat) { selectedCat = it }
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (list.isEmpty()) item {
                Text(
                    if (selectedCat == CAT_FAV) S.noFavoriteChannels else S.noChannels,
                    Modifier.padding(24.dp), color = C.dim,
                )
            }
            items(list, key = { it.streamId }) { ch ->
                ChannelRow(
                    ch, isFav = ch.streamId in favs,
                    onClick = {
                        prefs.lastCategoryId = selectedCat
                        Repo.playlist = list
                        ctx.startActivity(PlayerActivity.liveIntent(ctx, ch.streamId))
                    },
                    onLongClick = { prefs.toggleFavorite(ch.streamId); favVersion++ },
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(ch: LiveChannel, isFav: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    LaunchedEffect(ch.streamId) { Repo.loadEpg(ch.streamId) }
    val now = Repo.currentProgramme(ch.streamId)
    FocusBox(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ch.num.toString(), Modifier.width(48.dp), color = C.dim, fontSize = 14.sp)
            AsyncImage(model = ch.icon, contentDescription = null, modifier = Modifier.size(48.dp, 36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ch.name, color = C.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (now != null) {
                    Text(
                        "${hhmm(now.start)} – ${hhmm(now.end)}  ${now.title}",
                        color = C.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val nowSec = System.currentTimeMillis() / 1000
                    val frac = ((nowSec - now.start).toFloat() / (now.end - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth(0.6f).height(3.dp).padding(top = 2.dp),
                        color = C.accent, trackColor = C.cell,
                    )
                }
            }
            if (isFav) Text("★", color = C.fav, fontSize = 18.sp)
        }
    }
}

// ---------------- TV guide grid ----------------

private const val DP_PER_MIN = 5f
private const val WINDOW_MIN = 12 * 60
private val CHANNEL_COL = 220.dp
private val ROW_HEIGHT = 56.dp

@Composable
fun GuideScreen(prefs: Prefs) {
    val ctx = LocalContext.current
    val (data, error) = rememberLiveData()
    var selectedCat by rememberSaveable { mutableStateOf(prefs.lastCategoryId ?: CAT_ALL) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // Grid starts 30 min before the current half hour and spans WINDOW_MIN minutes.
    val rangeStart = remember { (System.currentTimeMillis() / 1000 / 1800) * 1800 - 1800 }
    val rangeEnd = rangeStart + WINDOW_MIN * 60L
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        val nowMin = (now - rangeStart) / 60f
        scroll.scrollTo(with(density) { ((nowMin - 20) * DP_PER_MIN).dp.roundToPx() }.coerceAtLeast(0))
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis() / 1000
        }
    }

    if (error != null) { ErrorText(S.errorLoadingChannels(error)); return }
    val (cats, chans) = data ?: run { Loading(S.loadingChannels); return }
    val categories = remember(cats, L10n.lang) { withSyntheticCategories(cats) }
    val list = remember(selectedCat, chans) { channelsFor(selectedCat, chans, prefs) }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            Modifier.fillMaxWidth().background(C.panel),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(categories, key = { it.id }) { cat ->
                FocusBox(
                    onClick = { selectedCat = cat.id }, selected = cat.id == selectedCat,
                    color = Color.Transparent, shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        cat.name, Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        color = if (cat.id == selectedCat) Color.White else C.dim, fontSize = 14.sp, maxLines = 1,
                    )
                }
            }
        }
        // Time header
        Row(Modifier.fillMaxWidth().height(30.dp).background(C.panel)) {
            Box(Modifier.width(CHANNEL_COL).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text(hhmm(now), color = C.accent, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll, enabled = false)) {
                Row {
                    for (t in rangeStart until rangeEnd step 1800L) {
                        Box(Modifier.width((30 * DP_PER_MIN).dp).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                            Text(hhmm(t), Modifier.padding(start = 6.dp), color = C.dim, fontSize = 12.sp)
                        }
                    }
                }
                Box(
                    Modifier.offset(x = ((now - rangeStart) / 60f * DP_PER_MIN).dp)
                        .width(2.dp).fillMaxHeight().background(Color(0xFFFF5252)),
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.streamId }) { ch ->
                GuideRow(ch, scroll, rangeStart, rangeEnd, now) {
                    prefs.lastCategoryId = selectedCat
                    Repo.playlist = list
                    ctx.startActivity(PlayerActivity.liveIntent(ctx, ch.streamId))
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    ch: LiveChannel,
    scroll: androidx.compose.foundation.ScrollState,
    rangeStart: Long,
    rangeEnd: Long,
    now: Long,
    onPlay: () -> Unit,
) {
    LaunchedEffect(ch.streamId) { Repo.loadEpg(ch.streamId) }
    val progs: List<Programme>? = Repo.epg[ch.streamId]
    val totalWidth = (WINDOW_MIN * DP_PER_MIN).dp

    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(bottom = 2.dp)) {
        FocusBox(onClick = onPlay, modifier = Modifier.width(CHANNEL_COL).fillMaxHeight().padding(end = 2.dp), color = C.panel) {
            Row(Modifier.padding(horizontal = 8.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = ch.icon, contentDescription = null, modifier = Modifier.size(40.dp, 30.dp))
                Spacer(Modifier.width(8.dp))
                Text(ch.name, color = C.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
            when {
                progs == null -> Box(Modifier.width(totalWidth).fillMaxHeight().background(C.cell.copy(alpha = 0.4f)))
                progs.isEmpty() -> FocusBox(onClick = onPlay, modifier = Modifier.width(totalWidth).fillMaxHeight(), color = C.cell.copy(alpha = 0.4f)) {
                    Text(S.noEpgData, Modifier.padding(8.dp), color = C.dim, fontSize = 13.sp)
                }
                else -> {
                    var cursor = rangeStart
                    for (p in progs) {
                        val s = maxOf(p.start, rangeStart)
                        val e = minOf(p.end, rangeEnd)
                        if (e <= s || s < cursor) continue
                        if (s > cursor) Spacer(Modifier.width(((s - cursor) / 60f * DP_PER_MIN).dp))
                        cursor = e
                        val isNow = now in p.start until p.end
                        FocusBox(
                            onClick = onPlay,
                            modifier = Modifier.width(((e - s) / 60f * DP_PER_MIN).dp).fillMaxHeight().padding(end = 2.dp),
                            color = if (isNow) C.cellNow else C.cell,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                Text(
                                    p.title, color = if (isNow) Color.White else C.text, fontSize = 14.sp,
                                    fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text("${hhmm(p.start)} – ${hhmm(p.end)}", color = C.dim, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                    if (cursor < rangeEnd) Spacer(Modifier.width(((rangeEnd - cursor) / 60f * DP_PER_MIN).dp))
                }
            }
        }
    }
}
