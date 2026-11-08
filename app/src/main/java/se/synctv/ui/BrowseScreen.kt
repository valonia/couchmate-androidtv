@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package se.synctv.ui

import se.synctv.t
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import se.synctv.Api
import se.synctv.Category
import se.synctv.Episode
import se.synctv.Item
import se.synctv.Program
import se.synctv.SeriesInfo
import se.synctv.SyncClient
import se.synctv.next
import se.synctv.favKey
import se.synctv.now

enum class Section(private val sv: String, private val en: String, val kind: String) {
    LIVE("TV", "TV", "live"),
    MOVIES("Filmer", "Movies", "vod"),
    SERIES("Serier", "Series", "series"),
    FAVORITES("Favoriter", "Favorites", ""),
    HISTORY("Historik", "History", ""),
    SEARCH("Sök", "Search", ""), // visas som ikon i menyraden
    SOURCE("", "", ""); // en källa från ett plugin (t.ex. Twitch) – namnet kommer från källan

    /** Läses vid varje ritning, så fliknamnen byter språk direkt. */
    val label: String get() = t(sv, en)
}

@Composable
fun BrowseScreen(sync: SyncClient, api: Api, onOpenPlayer: () -> Unit, onSettings: () -> Unit) {
    val state by sync.state.collectAsState()
    val members by sync.members.collectAsState()
    val connected by sync.connected.collectAsState()
    val syncError by sync.error.collectAsState()
    val favorites by sync.favorites.collectAsState()
    val newEpisodes = favorites.sumOf { it.newCount }
    var section by rememberSaveable { mutableStateOf(Section.LIVE) }
    // Källor från plugins (t.ex. Twitch) – hämtas från servern och visas i menyn mellan Favoriter och Historik.
    var sources by remember { mutableStateOf<List<se.synctv.Source>>(emptyList()) }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { api.sources() }.onSuccess { sources = it }
            delay(60_000)
        }
    }
    // Försvinner källan (pluginet avstängt) går vi tillbaka till TV.
    LaunchedEffect(sources, sourceId) {
        if (section == Section.SOURCE && sources.isNotEmpty() && sources.none { it.id == sourceId }) section = Section.LIVE
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { firstFocus.requestFocus() } }

    var showMembers by remember { mutableStateOf(false) }
    BackHandler(enabled = showMembers) { showMembers = false }

    // Tillbaka i innehållet: ett steg närmare menyn (t.ex. tablå → kanaler → grupper → menyn).
    // Står man redan i menyn stängs appen (systemets vanliga tillbaka).
    var railFocused by remember { mutableStateOf(false) }
    val rootView = LocalView.current
    BackHandler(enabled = !railFocused && !showMembers) {
        // Samma som att trycka vänster på fjärren; gör det ingenting hoppar vi direkt till menyn.
        val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        val handled = rootView.dispatchKeyEvent(down)
        rootView.dispatchKeyEvent(up)
        if (!handled) runCatching { firstFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize().background(Palette.Bg)) {
        // ---- menyn till vänster (fälls ihop till ikoner när man går in i innehållet)
        SideNav(
            section = section,
            onSelect = { section = it },
            sources = sources,
            selectedSource = if (section == Section.SOURCE) sourceId else null,
            onSelectSource = { sourceId = it.id; section = Section.SOURCE },
            newEpisodes = newEpisodes,
            media = state?.media,
            onOpenPlayer = onOpenPlayer,
            onStop = { sync.stopPlayback() },
            onSettings = onSettings,
            connected = connected,
            members = members,
            error = syncError,
            onMembers = { showMembers = true },
            selectedFocus = firstFocus,
            onRailFocus = { railFocused = it },
        )

        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 20.dp, end = 24.dp, top = 22.dp, bottom = 20.dp)) {
            when (section) {
                Section.LIVE -> LiveBrowser(sync, api, playingId = state?.media?.takeIf { it.isLive }?.id)
                Section.SEARCH -> SearchScreen(sync, api)
                Section.FAVORITES -> FavoritesScreen(sync, api)
                Section.HISTORY -> HistoryScreen(sync, api)
                Section.SOURCE -> {
                    val src = sources.firstOrNull { it.id == sourceId }
                    if (src != null) androidx.compose.runtime.key(src.id) {
                        SourceScreen(sync, api, src, playingId = state?.media?.takeIf { it.type == "plugin" }?.id)
                    }
                }
                else -> androidx.compose.runtime.key(section) { VodBrowser(sync, api, section) }
            }
        }
    }
    if (showMembers) MembersPanel(members, state?.media?.title)

    // Påminnelse: kanalen byts snart för alla
    val reminder = state?.reminder
    if (reminder != null && !showMembers) {
        val tick = rememberTicker(reminder.key)
        val left = remember(tick, reminder.switchAt) { ((reminder.switchAt - sync.serverNow() + 999) / 1000).coerceAtLeast(0) }
        ReminderCard(
            reminder, secondsLeft = left,
            interactive = true, onNow = { sync.reminderNow() }, onCancel = { sync.reminderCancel() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(36.dp),
        )
    }
    }
}

/** Värde som ändras varje halvsekund medan [key] är aktiv (för nedräkningar). */
@Composable
fun rememberTicker(key: Any?): Long {
    var n by remember(key) { mutableStateOf(0L) }
    LaunchedEffect(key) { while (true) { delay(500); n++ } }
    return n
}

/** Lista över alla som är anslutna – öppnas från chippet uppe till höger. */
@Composable
private fun MembersPanel(members: List<se.synctv.Member>, playing: String?) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    val sorted = members.sortedWith(compareByDescending<se.synctv.Member> { it.watching }.thenBy { it.name.lowercase() })
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0x99000000)))
        Column(
            Modifier.width(460.dp).fillMaxHeight().background(Palette.Panel).padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 24.dp),
        ) {
            Text(t("${members.size} anslutna", "${members.size} connected"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                if (playing != null) t("Tittar på $playing", "Watching $playing") else t("Inget spelas just nu", "Nothing playing right now"),
                fontSize = 14.sp, color = Palette.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(sorted) { idx, m ->
                    FocusRow(onClick = {}, height = 60.dp, modifier = if (idx == 0) Modifier.focusRequester(first) else Modifier) {
                        Avatar(m.name, 34.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (m.device.isNotBlank()) Text(m.device, fontSize = 13.sp, color = Color(0x99FFFFFF), maxLines = 1)
                        }
                        Text(
                            if (m.watching) t("Tittar", "Watching") else t("I menyn", "In menu"),
                            fontSize = 13.sp,
                            color = if (m.watching) Color(0xFF3DD68C) else Color(0x99FFFFFF),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(t("Tillbaka  –  stäng", "Back  –  close"), fontSize = 13.sp, color = Palette.Faint)
        }
    }
}

// ============================================================== gemensamt

@Composable
private fun GroupList(
    categories: List<Category>,
    selectedId: String?,
    onSelect: (Category) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxHeight().background(Palette.Panel, RoundedCornerShape(12.dp)).padding(8.dp)) {
        SectionLabel(t("Grupper", "Groups"), Modifier.padding(start = 10.dp, top = 6.dp, bottom = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(categories) { c ->
                FocusRow(onClick = { onSelect(c) }, selected = c.id == selectedId, height = 46.dp) {
                    Text(c.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Faint, letterSpacing = 1.5.sp, modifier = modifier)
}

@Composable
private fun Message(text: String, error: Boolean = false) {
    Text(text, color = if (error) Color(0xFFFF8A80) else Palette.Dim, fontSize = 16.sp, modifier = Modifier.padding(20.dp))
}

// ============================================================== live

@Composable
private fun LiveBrowser(sync: SyncClient, api: Api, playingId: String?) {
    val favorites by sync.favorites.collectAsState()
    val favKeys = remember(favorites) { favorites.map { it.key }.toSet() }
    var cats by remember { mutableStateOf<List<Category>>(emptyList()) }
    var catId by rememberSaveable { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<Item>>(emptyList()) }
    var epg by remember { mutableStateOf<Map<String, List<Program>>>(emptyMap()) }
    var focused by remember { mutableStateOf<Item?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val now = rememberNowSec()

    LaunchedEffect(Unit) {
        try {
            cats = api.categories("live")
            if (catId == null || cats.none { it.id == catId }) catId = cats.firstOrNull()?.id
        } catch (e: Exception) { error = e.message }
    }
    LaunchedEffect(catId) {
        val c = catId ?: return@LaunchedEffect
        loading = true
        try {
            items = api.items("live", c)
            focused = items.firstOrNull { it.id == playingId } ?: items.firstOrNull()
            error = null
        } catch (e: Exception) { error = e.message }
        loading = false
        while (true) {
            epg = runCatching { api.epg(c) }.getOrDefault(epg)
            delay(3 * 60_000)
        }
    }

    val catName = cats.firstOrNull { it.id == catId }?.name ?: ""
    Row(Modifier.fillMaxSize()) {
        GroupList(cats, catId, { catId = it.id }, Modifier.width(250.dp))
        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1.3f).fillMaxHeight().background(Palette.Panel, RoundedCornerShape(12.dp)).padding(8.dp)) {
            Row(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 8.dp)) {
                SectionLabel(catName)
                if (items.isNotEmpty()) SectionLabel(t("  ·  ${items.size} kanaler", "  ·  ${items.size} channels"))
            }
            when {
                error != null -> Message(t("Fel: $error", "Error: $error"), error = true)
                loading && items.isEmpty() -> Message(t("Laddar kanaler…", "Loading channels…"))
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.focusRestorer()) {
                    items(items) { item ->
                        ChannelRow(
                            item = item,
                            epg = epg[item.id],
                            nowSec = now,
                            playing = item.id == playingId,
                            iconUrl = api.abs(item.icon),
                            onFocus = { focused = item },
                            onClick = { sync.load("live", item.id, item.ext, item.name, catName, item.icon, catId) },
                            favorite = favKeys.contains(favKey("live", item.id)),
                            onLongClick = { sync.toggleFavorite("live", item.copy(categoryId = catId), catName) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(14.dp))

        ChannelGuide(focused?.copy(categoryId = catId), focused?.let { epg[it.id] }, now, api, sync, catName, Modifier.weight(1f).fillMaxHeight())
    }
}

// ============================================================== filmer & serier

@Composable
private fun VodBrowser(sync: SyncClient, api: Api, section: Section) {
    val favorites by sync.favorites.collectAsState()
    val favMap = remember(favorites) { favorites.associateBy { it.key } }
    val favType = if (section == Section.SERIES) "series" else "movie"
    var cats by remember { mutableStateOf<List<Category>>(emptyList()) }
    var catId by rememberSaveable { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<Item>>(emptyList()) }
    var focused by remember { mutableStateOf<Item?>(null) }
    var series by remember { mutableStateOf<Item?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            cats = api.categories(section.kind)
            if (catId == null || cats.none { it.id == catId }) catId = cats.firstOrNull()?.id
        } catch (e: Exception) { error = e.message }
    }
    LaunchedEffect(catId) {
        val c = catId ?: return@LaunchedEffect
        loading = true
        series = null
        try {
            items = api.items(section.kind, c)
            focused = items.firstOrNull()
            error = null
        } catch (e: Exception) { error = e.message }
        loading = false
    }
    BackHandler(enabled = series != null) { series = null }
    val catName = cats.firstOrNull { it.id == catId }?.name

    Row(Modifier.fillMaxSize()) {
        GroupList(cats, catId, { catId = it.id }, Modifier.width(250.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val s = series
            when {
                error != null -> Message(t("Fel: $error", "Error: $error"), error = true)
                s != null -> SeriesDetail(api, sync, s.copy(categoryId = catId, categoryName = catName)) { ep, seasonNo ->
                    sync.load(
                        type = "episode", id = ep.id, ext = ep.ext,
                        title = cleanTitle(s.name),
                        subtitle = "S%02dE%02d · %s".format(seasonNo, ep.episode, ep.title),
                        icon = ep.icon ?: s.icon,
                        categoryId = catId,
                        seriesId = s.id,
                    )
                }
                loading && items.isEmpty() -> Message(t("Laddar…", "Loading…"))
                else -> {
                    focused?.let { Hero(it.name, it.year, it.genre, it.rating, it.plot, api.abs(it.icon), api.abs(it.backdrop ?: it.icon)) }
                    Text(t("Håll inne OK för att lägga till i favoriter", "Hold OK to add to favourites"), fontSize = 13.sp, color = Palette.Faint,
                        modifier = Modifier.padding(start = 6.dp, top = 10.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(92.dp),
                        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(items) { item ->
                            PosterCard(
                                title = cleanTitle(item.name),
                                iconUrl = api.abs(item.icon),
                                onFocus = { focused = item },
                                onClick = {
                                    if (section == Section.SERIES) series = item
                                    else sync.load("movie", item.id, item.ext, cleanTitle(item.name), yearFromTitle(item.name) ?: item.year, item.icon, catId)
                                },
                                favorite = favMap.containsKey(favKey(favType, item.id)),
                                newCount = favMap[favKey(favType, item.id)]?.newCount ?: 0,
                                onLongClick = { sync.toggleFavorite(favType, item.copy(categoryId = catId), catName) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Infopanel överst: bakgrundsbild, affisch, titel, metadata och handling. */
@Composable
private fun Hero(
    name: String,
    year: String?,
    genre: String?,
    rating: Double?,
    plot: String?,
    poster: String?,
    backdrop: String?,
    height: Int = 200,
) {
    Box(
        Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Panel),
    ) {
        if (backdrop != null) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxHeight().fillMaxWidth(0.65f).align(Alignment.CenterEnd).alpha(0.55f),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0.35f to Palette.Panel, 0.75f to Palette.Panel.copy(alpha = 0.55f), 1f to Color.Transparent)
            )
        )
        Row(Modifier.fillMaxSize().padding(20.dp)) {
            Thumb(poster, name, Modifier.fillMaxHeight().aspectRatio(2f / 3f), fit = false, corner = 8.dp)
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                Text(cleanTitle(name), fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = metaLine(year ?: yearFromTitle(name), genre, rating)
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(meta, fontSize = 15.sp, color = Palette.Gold)
                }
                if (!plot.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(plot, fontSize = 15.sp, color = Palette.Dim, maxLines = 4, overflow = TextOverflow.Ellipsis, lineHeight = 21.sp)
                }
            }
        }
    }
}

@Composable
internal fun SeriesDetail(api: Api, sync: SyncClient, series: Item, onPlay: (Episode, Int) -> Unit) {
    val favorites by sync.favorites.collectAsState()
    val fav = favorites.firstOrNull { it.key == favKey("series", series.id) }
    // Öppnar man serien räknas nya avsnitt som sedda.
    LaunchedEffect(series.id, fav?.newCount) {
        if (fav != null && fav.newCount > 0) { delay(1500); sync.favSeen(fav.key) }
    }
    var info by remember(series.id) { mutableStateOf<SeriesInfo?>(null) }
    var error by remember(series.id) { mutableStateOf<String?>(null) }
    var seasonIdx by remember(series.id) { mutableIntStateOf(0) }
    val firstEp = remember { FocusRequester() }
    LaunchedEffect(series.id) {
        try { info = api.seriesInfo(series.id) } catch (e: Exception) { error = e.message }
        delay(100)
        runCatching { firstEp.requestFocus() }
    }

    val i = info
    Column(Modifier.fillMaxSize()) {
        Hero(
            name = series.name,
            year = i?.year ?: series.year,
            genre = i?.genre ?: series.genre,
            rating = i?.rating ?: series.rating,
            plot = i?.plot ?: series.plot,
            poster = api.abs(i?.icon ?: series.icon),
            backdrop = api.abs(i?.backdrop ?: series.backdrop ?: series.icon),
        )
        Spacer(Modifier.height(14.dp))
        when {
            error != null -> Message(t("Fel: $error", "Error: $error"), error = true)
            i == null -> Message(t("Laddar avsnitt…", "Loading episodes…"))
            i.seasons.isEmpty() -> Message(t("Inga avsnitt hittades", "No episodes found"))
            else -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        Pill(
                            if (fav != null) t("★ Favorit", "★ Favourite") else t("☆ Lägg till favorit", "☆ Add to favourites"),
                            selected = fav != null,
                            onClick = { sync.toggleFavorite("series", series, series.categoryName) },
                        )
                    }
                    fav?.newLabel?.takeIf { fav.newCount > 0 }?.let { label ->
                        item {
                            Text(t("Nytt: $label", "New: $label"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                        }
                    }
                    itemsIndexed(i.seasons) { idx, s ->
                        Pill(if (s.number == 0) t("Extra", "Extras") else t("Säsong ${s.number}", "Season ${s.number}"), selected = idx == seasonIdx, onClick = { seasonIdx = idx }, onFocus = { seasonIdx = idx })
                    }
                }
                Spacer(Modifier.height(10.dp))
                val season = i.seasons.getOrNull(seasonIdx) ?: i.seasons.first()
                LazyColumn(
                    Modifier.background(Palette.Panel, RoundedCornerShape(12.dp)).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(season.episodes) { idx, ep ->
                        FocusRow(
                            onClick = { onPlay(ep, season.number) },
                            height = 70.dp,
                            modifier = if (idx == 0) Modifier.focusRequester(firstEp) else Modifier,
                        ) {
                            Text("E%02d".format(ep.episode), fontSize = 15.sp, color = Palette.Dim, modifier = Modifier.width(56.dp))
                            Thumb(api.abs(ep.icon ?: i.icon ?: series.icon), ep.title, Modifier.width(96.dp).height(54.dp), fit = false)
                            Spacer(Modifier.width(16.dp))
                            Text(ep.title, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
