package hr.mojatv.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hr.mojatv.app.data.Diagnostics
import hr.mojatv.app.data.Prefs
import hr.mojatv.app.data.Repo
import hr.mojatv.app.data.TITLE_COLORS
import hr.mojatv.app.data.Updater
import hr.mojatv.app.player.PlayerActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

enum class Tab { LIVE, GUIDE, MOVIES, SERIES, SETTINGS, REFRESH }

private fun Tab.label(s: Strings) = when (this) {
    Tab.LIVE -> s.tabLive
    Tab.GUIDE -> s.tabGuide
    Tab.MOVIES -> s.tabMovies
    Tab.SERIES -> s.tabSeries
    Tab.SETTINGS -> s.tabSettings
    Tab.REFRESH -> s.tabRefresh
}

private const val DAY_MS = 24 * 60 * 60_000L

/**
 * Main screen: tab bar + current tab. [onProfileChanged] is called after the active profile was
 * switched, added or deleted; the caller re-creates this screen for the new profile.
 */
@Composable
fun HomeScreen(prefs: Prefs, autoPlayLast: Boolean, onProfileChanged: () -> Unit) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }
    var autoPlayed by rememberSaveable { mutableStateOf(false) }
    var appName by remember { mutableStateOf(prefs.appName) }
    var appColor by remember { mutableIntStateOf(prefs.appColor) }
    // Bumping this re-creates the current screen so it reloads from the (cleared) cache.
    var dataVersion by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var addingProfile by remember { mutableStateOf(false) }
    val s = S

    fun refresh() {
        Repo.clear()
        prefs.lastRefresh = System.currentTimeMillis()
        dataVersion++
        message = s.refreshed
    }

    // A fresh process always fetches fresh data, so count that as a refresh.
    remember { prefs.lastRefresh = System.currentTimeMillis(); true }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10 * 60_000L)
            if (prefs.refreshDaily && System.currentTimeMillis() - prefs.lastRefresh > DAY_MS) refresh()
        }
    }
    LaunchedEffect(message) {
        if (message != null) { delay(3500); message = null }
    }
    AutoUpdateChecker(prefs)

    // Resume the last watched channel on a fresh start (also right after boot, so retry while the network comes up).
    LaunchedEffect(Unit) {
        if (!autoPlayLast || autoPlayed || prefs.lastChannelId < 0) return@LaunchedEffect
        autoPlayed = true
        repeat(5) { attempt ->
            val result = runCatching { Repo.live() }
            if (result.isSuccess) {
                val (_, chans) = result.getOrThrow()
                val list = channelsFor(prefs.lastCategoryId ?: CAT_ALL, chans, prefs)
                if (list.any { it.streamId == prefs.lastChannelId }) {
                    Repo.playlist = list
                    ctx.startActivity(PlayerActivity.liveIntent(ctx, prefs.lastChannelId))
                }
                return@LaunchedEffect
            }
            delay(3000L * (attempt + 1))
        }
    }

    if (addingProfile) {
        LoginScreen(prefs, addMode = true, onCancel = { addingProfile = false }) {
            addingProfile = false
            onProfileChanged()
        }
        return
    }

    BackHandler(enabled = tab != Tab.LIVE) { tab = Tab.LIVE }

    SpaceBackground {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(C.panel).padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column {
                    Text(appName, color = Color(appColor), fontWeight = FontWeight.Bold, fontSize = 30.sp, maxLines = 1)
                    val profiles = remember { prefs.profiles }
                    if (profiles.size > 1) prefs.activeProfile?.let {
                        Text(it.name, color = C.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(20.dp))
                Tab.entries.forEach { t ->
                    FocusBox(
                        onClick = { if (t == Tab.REFRESH) refresh() else tab = t },
                        selected = tab == t,
                        color = Color.Transparent,
                        shape = RoundedCornerShape(22.dp),
                    ) {
                        Text(
                            t.label(s), Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            fontSize = 20.sp, maxLines = 1,
                            color = if (tab == t) Color.White else C.dim,
                            fontWeight = if (tab == t) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Clock()
            }
            Box(Modifier.weight(1f)) {
                key(dataVersion) {
                    when (tab) {
                        Tab.LIVE -> LiveScreen(prefs)
                        Tab.GUIDE -> GuideScreen(prefs)
                        Tab.MOVIES -> MoviesScreen(prefs)
                        Tab.SERIES -> SeriesScreen(prefs)
                        Tab.SETTINGS -> SettingsScreen(
                            prefs, appName, appColor,
                            onAppName = { appName = it; prefs.appName = it },
                            onAppColor = { appColor = it; prefs.appColor = it },
                            onAddProfile = { addingProfile = true },
                            onProfileChanged = onProfileChanged,
                        )
                        Tab.REFRESH -> {}
                    }
                }
            }
        }
        message?.let {
            Text(
                it, Modifier.align(Alignment.BottomCenter).padding(24.dp)
                    .background(C.accent, RoundedCornerShape(8.dp)).padding(horizontal = 20.dp, vertical = 10.dp),
                color = Color.White, fontWeight = FontWeight.Medium,
            )
        }
        RemoteInputOverlay(Modifier.align(Alignment.TopEnd).padding(top = 104.dp, end = 24.dp))
        UpdatePrompt(Modifier.align(Alignment.BottomCenter).padding(bottom = if (message != null) 72.dp else 24.dp))
    }
}

@Composable
fun Clock() {
    var now by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1000)
        }
    }
    Text(hhmm(now), color = C.text, fontSize = 24.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
}

@Composable
fun SettingsScreen(
    prefs: Prefs,
    appName: String,
    appColor: Int,
    onAppName: (String) -> Unit,
    onAppColor: (Int) -> Unit,
    onAddProfile: () -> Unit,
    onProfileChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val s = S
    var autoStart by remember { mutableStateOf(prefs.autoStart) }
    var resume by remember { mutableStateOf(prefs.resumeLastChannel) }
    var format by remember { mutableStateOf(prefs.liveFormat) }
    var refreshDaily by remember { mutableStateOf(prefs.refreshDaily) }
    var autoUpdate by remember { mutableStateOf(prefs.autoUpdate) }
    val diag = remember { Diagnostics(ctx) }
    var diagVersion by remember { mutableIntStateOf(0) }
    val diagStats = remember(diagVersion) { diag.snapshot() }
    var account by remember { mutableStateOf(Repo.account) }
    var accountFailed by remember { mutableStateOf(false) }
    var accountVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(accountVersion) {
        if (account != null && accountVersion == 0) return@LaunchedEffect
        accountFailed = false
        val a = runCatching { Repo.api?.accountInfo() }.getOrNull()
        if (a != null) { Repo.account = a; account = a } else accountFailed = true
    }
    val scope = rememberCoroutineScope()
    var editingName by remember { mutableStateOf(false) }
    var profilesVersion by remember { mutableIntStateOf(0) }
    var confirmDeleteId by remember { mutableStateOf<Int?>(null) }
    val nameFocus = remember { FocusRequester() }
    val nameRowFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val profiles = remember(profilesVersion) { prefs.profiles }
    val colorIdx = TITLE_COLORS.indexOf(appColor)
    val colorName = s.colorNames.getOrNull(colorIdx) ?: "#%06X".format(appColor and 0xFFFFFF)

    LaunchedEffect(editingName) { if (editingName) runCatching { nameFocus.requestFocus() } }
    fun finishNameEdit() {
        keyboard?.hide()
        editingName = false
        runCatching { nameRowFocus.requestFocus() }
    }
    BackHandler(enabled = editingName) { finishNameEdit() }
    LaunchedEffect(confirmDeleteId) { if (confirmDeleteId != null) { delay(5000); confirmDeleteId = null } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text(s.settings, Modifier.padding(bottom = 8.dp), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.text) }


        item { SectionTitle(s.secAppearance) }
        item {
            SettingRow(s.language, L10n.lang.label) {
                val next = Lang.entries[(L10n.lang.ordinal + 1) % Lang.entries.size]
                L10n.lang = next
                prefs.language = next.code
            }
        }
        item {
            SettingRow(s.appName, appName, modifier = Modifier.focusRequester(nameRowFocus)) {
                if (editingName) finishNameEdit() else editingName = true
            }
        }
        if (editingName) item {
            RemoteTextField(
                value = appName, onValueChange = onAppName, label = s.appName,
                modifier = Modifier.width(720.dp).focusRequester(nameFocus),
                onImeAction = ::finishNameEdit,
            )
        }
        item {
            SettingRow(s.titleColor, colorName, valueColor = Color(appColor)) {
                onAppColor(TITLE_COLORS[(colorIdx + 1) % TITLE_COLORS.size])
            }
        }
        item {
            val bgIdx = BACKGROUNDS.indexOfFirst { it.first == AppTheme.background }.coerceAtLeast(0)
            SettingRow(s.background, s.backgroundNames.getOrElse(bgIdx) { "" }) {
                val next = BACKGROUNDS[(bgIdx + 1) % BACKGROUNDS.size].first
                AppTheme.background = next
                prefs.background = next
            }
        }

        item { SectionTitle(s.secPlayback) }
        item {
            SettingRow(s.autoStart, if (autoStart) s.on else s.off) {
                autoStart = !autoStart; prefs.autoStart = autoStart
            }
        }
        item {
            SettingRow(s.resumeLast, if (resume) s.on else s.off) {
                resume = !resume; prefs.resumeLastChannel = resume
            }
        }
        item {
            SettingRow(s.liveFormat, format.uppercase()) {
                format = if (format == "ts") "m3u8" else "ts"; prefs.liveFormat = format
            }
        }
        item {
            SettingRow(
                s.diagnostics, s.diagSummary(diagStats.seamless, diagStats.reloads),
                valueColor = if (diagStats.reloads > 0) C.accent else C.fav,
                onLongClick = { diag.reset(); diagVersion++ },
            ) { diagVersion++ }
        }
        item {
            val d = diagStats
            Text(
                s.diagDetail(d.frozen, d.ended, d.buffering, d.errors, dmy(d.since)) + (d.lastError?.let { " ($it)" } ?: ""),
                color = C.dim, fontSize = 12.sp,
            )
        }

        item { SectionTitle(s.secData) }
        item {
            SettingRow(s.refreshMode, if (refreshDaily) s.daily else s.manual) {
                refreshDaily = !refreshDaily; prefs.refreshDaily = refreshDaily
            }
        }

        item { SectionTitle(s.secAccount) }
        item {
            val a = account
            val now = System.currentTimeMillis() / 1000
            val daysLeft = a?.expiresAt?.let { ((it - now) / 86_400).toInt() }
            val expired = a != null && (a.expiresAt?.let { it < now } == true || !a.status.equals("Active", ignoreCase = true))
            val value = when {
                a == null -> if (accountFailed) s.subscriptionUnknown else "…"
                expired -> s.subscriptionExpired + (a.status.takeIf { it.isNotBlank() && !it.equals("Active", true) }?.let { " ($it)" } ?: "")
                a.expiresAt == null -> a.status.ifBlank { s.subscriptionUnknown }
                else -> s.subscriptionUntil(dmy(a.expiresAt), daysLeft ?: 0)
            }
            SettingRow(
                s.subscription, value,
                valueColor = when {
                    expired || (daysLeft != null && daysLeft < 7) -> Color(0xFFFF5252)
                    a == null -> C.dim
                    else -> C.fav
                },
            ) { accountVersion++ }
        }
        account?.maxConnections?.let { n -> item { Text(s.maxConnections(n), color = C.dim, fontSize = 12.sp) } }

        item { SectionTitle(s.secUpdate) }
        item {
            SettingRow(s.autoUpdate, if (autoUpdate) s.on else s.off) {
                autoUpdate = !autoUpdate; prefs.autoUpdate = autoUpdate
            }
        }
        item {
            val state = Updater.state
            SettingRow(
                s.updCheck(Updater.currentVersion), state.label(s),
                valueColor = when (state) {
                    is Updater.State.Ready -> C.fav
                    is Updater.State.Error -> Color(0xFFFF8A80)
                    else -> C.accent
                },
            ) {
                when (state) {
                    is Updater.State.Ready -> installUpdate(ctx, state)
                    Updater.State.Checking, is Updater.State.Downloading -> {}
                    else -> scope.launch { Updater.checkAndDownload(ctx.applicationContext) }
                }
            }
        }
        item { Text(s.updNote, color = C.dim, fontSize = 12.sp) }

        item { SectionTitle(s.secProfiles) }
        items(profiles.size, key = { profiles[it].id }) { i ->
            val p = profiles[i]
            val active = p.id == prefs.activeProfileId
            val confirming = confirmDeleteId == p.id
            SettingRow(
                title = p.name,
                value = when {
                    confirming -> s.deleteConfirm
                    active -> s.active
                    else -> p.server.substringAfter("://")
                },
                valueColor = when {
                    confirming -> Color(0xFFFF5252)
                    active -> C.fav
                    else -> C.dim
                },
                onLongClick = { confirmDeleteId = p.id },
            ) {
                when {
                    confirming -> {
                        confirmDeleteId = null
                        prefs.removeProfile(p.id)
                        Toast.makeText(ctx, s.profileDeleted, Toast.LENGTH_SHORT).show()
                        if (active) onProfileChanged() else profilesVersion++
                    }
                    !active -> {
                        prefs.activeProfileId = p.id
                        onProfileChanged()
                    }
                }
            }
        }
        item { SettingRow(s.addProfile, "", valueColor = C.dim, onClick = onAddProfile) }
        item { Text(s.profileHint, color = C.dim, fontSize = 12.sp) }

        item { Text(s.autostartNote, Modifier.padding(top = 16.dp), color = C.dim, fontSize = 12.sp) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = 12.dp, bottom = 2.dp), color = C.dim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun SettingRow(
    title: String,
    value: String,
    valueColor: Color = C.accent,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    FocusBox(onClick = onClick, onLongClick = onLongClick, modifier = modifier.width(720.dp), color = C.panel) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = C.text, fontSize = 16.sp)
            Text(value, color = valueColor, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
