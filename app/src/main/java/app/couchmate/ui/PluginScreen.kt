@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package app.couchmate.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.couchmate.Api
import app.couchmate.Item
import app.couchmate.ItemRow
import app.couchmate.Program
import app.couchmate.Screen
import app.couchmate.SyncClient
import app.couchmate.tr
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private val iconCache = HashMap<String, ImageVector>()

/** The icon a plugin sends (an SVG path in a 24×24 box), otherwise a TV icon. */
fun pluginIcon(path: String?): ImageVector {
    if (path.isNullOrBlank()) return AppIcons.LiveTv
    return iconCache.getOrPut(path) {
        runCatching {
            ImageVector.Builder(name = "plugin", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
                .addPath(pathData = PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
                .build()
        }.getOrDefault(AppIcons.LiveTv)
    }
}

private fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1f M".format(n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000} k"
    n >= 1_000 -> "%.1f k".format(n / 1000.0)
    else -> "$n"
}

/** What happens when an item is chosen: play it for everyone, or open its detail page. */
fun interface ItemOpener { fun open(item: Item) }

// ============================================================== cards

/** A 16:9 card: picture with LIVE and viewers, title and subtitle (streams, videos). */
@Composable
fun LandscapeCard(item: Item, api: Api, playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, favorite: Boolean = false, onLongClick: (() -> Unit)? = null) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.width(300.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (playing) Palette.AccentSoft else Palette.Panel,
            contentColor = Palette.Text,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                Thumb(api.abs(item.image), item.subtitle ?: item.title, Modifier.fillMaxSize(), fit = false, corner = 12.dp)
                Row(Modifier.align(Alignment.TopStart).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.live) LiveBadge()
                    item.viewers?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            tr("{n} viewers", "n" to compact(it)), fontSize = 11.sp, color = Color.White,
                            modifier = Modifier.background(Color(0xB3000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                if (favorite) {
                    Text(
                        "★", fontSize = 15.sp, color = Palette.Gold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .background(Color(0xB30A0C10), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                // Streams: the channel above the stream's title; anything else: the title above its subtitle.
                val (top, bottom) = if (item.live && item.subtitle != null) item.subtitle to item.title else item.title to item.subtitle
                Text(top, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(bottom ?: "", fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** A compact channel card for rows (search, favourites). */
@Composable
fun ChannelCard(item: Item, api: Api, favorite: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    FocusRow(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.width(300.dp).background(Palette.Panel, RoundedCornerShape(8.dp)), height = 76.dp) {
        Thumb(api.abs(item.image), item.title, Modifier.width(80.dp).height(46.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let { Text(it, fontSize = 13.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (favorite) Text("★", fontSize = 15.sp, color = Palette.Gold)
    }
}

/** The right card for a row's style: landscape | poster | square | channels. */
@Composable
fun ItemCard(item: Item, style: String, api: Api, sync: SyncClient, opener: ItemOpener, playingKey: String?, modifier: Modifier = Modifier) {
    val favorites by sync.favorites.collectAsState()
    val fav = favorites.firstOrNull { it.key == item.key }
    val longClick: (() -> Unit)? = if (item.favorite) ({ sync.toggleFavorite(item) }) else null
    when (style) {
        "poster", "square" -> PosterCard(
            title = cleanTitle(item.title), iconUrl = api.abs(item.image), onClick = { opener.open(item) }, onFocus = {},
            modifier = modifier.width(128.dp), favorite = fav != null, newCount = fav?.newCount ?: 0,
            caption = fav?.newLabel?.takeIf { fav.newCount > 0 }?.let { tr("New: {episode}", "episode" to it) }, onLongClick = longClick,
        )
        "channels" -> Box(modifier) { ChannelCard(item, api, favorite = fav != null, onLongClick = longClick) { opener.open(item) } }
        else -> LandscapeCard(item, api, playing = item.key == playingKey, onClick = { opener.open(item) }, modifier = modifier, favorite = fav != null, onLongClick = longClick)
    }
}

/** A titled row of cards (rows screens, search results, favourites). */
@Composable
fun CardRow(title: String, row: ItemRow, api: Api, sync: SyncClient, opener: ItemOpener, playingKey: String?, playingFocus: FocusRequester? = null, onCardFocus: (String) -> Unit = {}) {
    Column {
        Row(Modifier.padding(start = 12.dp, bottom = 8.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("  ${row.items.size}", fontSize = 20.sp, color = Palette.Faint)
        }
        // focusRestorer: back to the card you were on (also after the player).
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(row.items, key = { it.key }) { item ->
                ItemCard(
                    item, row.style, api, sync, opener, playingKey,
                    modifier = (if (playingFocus != null && item.key == playingKey) Modifier.focusRequester(playingFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) onCardFocus(item.key) },
                )
            }
        }
    }
}

// ============================================================== shared bits

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(app.couchmate.I18n.locale), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Faint, letterSpacing = 1.5.sp, modifier = modifier)
}

@Composable
fun Message(text: String, error: Boolean = false) {
    Text(text, color = if (error) Color(0xFFFF8A80) else Palette.Dim, fontSize = 16.sp, modifier = Modifier.padding(20.dp))
}

@Composable
private fun GroupList(groups: List<app.couchmate.Group>, selectedId: String?, onSelect: (app.couchmate.Group) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxHeight().background(Palette.Panel, RoundedCornerShape(12.dp)).padding(8.dp)) {
        SectionLabel(tr("Groups"), Modifier.padding(start = 10.dp, top = 6.dp, bottom = 8.dp))
        // From the menu you land on the chosen group (the first time: the top), not the row at the same height.
        val listState = rememberLazyListState()
        val entry = rememberFocusEntry(listState, "groups")
        val selIdx = groups.indexOfFirst { it.id == selectedId }
        LaunchedEffect(groups.size) {
            if (selIdx > 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == selIdx && it.offset >= 0 }) listState.scrollToItem(selIdx)
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.focusEntry(entry) { arrayOf<Any?>(selIdx) }) {
            itemsIndexed(groups) { i, g ->
                FocusRow(onClick = { onSelect(g) }, selected = g.id == selectedId, height = 46.dp, modifier = entry.item(i)) {
                    Text(g.title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The info panel at the top: backdrop, poster, title, facts and description. */
@Composable
fun Hero(item: Item, api: Api, facts: List<String>? = null, height: Int = 200) {
    val backdrop = api.abs(item.backdrop ?: item.image)
    Box(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Panel)) {
        if (backdrop != null) {
            AsyncImage(
                model = backdrop, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxHeight().fillMaxWidth(0.65f).align(Alignment.CenterEnd).alpha(0.55f),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.35f to Palette.Panel, 0.75f to Palette.Panel.copy(alpha = 0.55f), 1f to Color.Transparent)))
        Row(Modifier.fillMaxSize().padding(20.dp)) {
            Thumb(api.abs(item.image), item.title, Modifier.fillMaxHeight().aspectRatio(2f / 3f), fit = false, corner = 8.dp)
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                Text(cleanTitle(item.title), fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = facts?.joinToString("  ·  ") ?: metaLine(item.year ?: yearFromTitle(item.title), item.genre, item.rating)
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(meta, fontSize = 15.sp, color = Palette.Gold)
                }
                val text = item.description ?: item.subtitle
                if (!text.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(text, fontSize = 15.sp, color = Palette.Dim, maxLines = 4, overflow = TextOverflow.Ellipsis, lineHeight = 21.sp)
                }
            }
        }
    }
}

// ============================================================== the screen behind a menu entry

/**
 * What a plugin shows behind a menu entry. Items with action "open" show their detail page in place
 * (Back closes it again); the opened item is remembered, so you come back to it after the player.
 */
@Composable
fun PluginScreen(sync: SyncClient, api: Api, plugin: String, menuId: String, title: String, icon: String?) {
    val state by sync.state.collectAsState()
    val playingKey = state?.media?.key
    var screen by remember { mutableStateOf<Screen?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    var openedItem by remember { mutableStateOf<Item?>(null) }
    BackHandler(enabled = openedId != null) { openedId = null }
    val opener = ItemOpener { item -> if (item.opens) { openedItem = item; openedId = item.id } else sync.load(item) }

    LaunchedEffect(plugin, menuId) {
        while (true) {
            try { screen = api.screen(plugin, menuId); error = null } catch (e: Exception) { error = e.message }
            // Rows (e.g. live streams) change over time – refresh them every minute.
            if (screen !is Screen.Rows) break
            delay(60_000)
        }
    }

    val opened = openedId
    if (opened != null) {
        key(opened) { DetailView(sync, api, plugin, opened, openedItem?.takeIf { it.id == opened }) }
        return
    }
    val s = screen
    when {
        s == null && error != null -> Message(tr("Error: {message}", "message" to error), error = true)
        s == null -> Message(tr("Loading…"))
        s is Screen.Rows -> RowsView(sync, api, s, title, icon, opener, playingKey)
        s is Screen.Groups -> GroupsView(sync, api, plugin, menuId, s, opener, playingKey)
        s is Screen.Detail -> DetailContent(sync, api, s, s.item)
        s is Screen.Message -> Message(s.text ?: s.message ?: tr("Nothing to show right now."))
    }
}

// ============================================================== rows (e.g. live streams)

@Composable
private fun RowsView(sync: SyncClient, api: Api, screen: Screen.Rows, title: String, icon: String?, opener: ItemOpener, playingKey: String?) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, bottom = 10.dp)) {
            androidx.tv.material3.Icon(pluginIcon(icon), contentDescription = null, tint = Palette.Accent, modifier = Modifier.padding(end = 12.dp).width(28.dp).height(28.dp))
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        val rows = screen.rows.filter { it.items.isNotEmpty() }
        // From the menu: back to the row you were on, otherwise the top one – not the row at the same height.
        val listState = rememberLazyListState()
        val entry = rememberFocusEntryByKey(listState, "rows", autoRestore = false)
        var focusedCard by remember { mutableStateOf<String?>(null) }
        // Back from the player: to what plays (the rows may have changed order), otherwise the row you were on.
        val playingFocus = remember { FocusRequester() }
        val playingRow = rows.firstOrNull { r -> r.items.any { it.key == playingKey } }?.id
        LaunchedEffect(Unit) {
            if (!entry.shouldRestore) return@LaunchedEffect
            if (playingRow == null) { entry.restore(); return@LaunchedEffect }
            // The row settles a moment after the list arrives – try until the right card has focus.
            focusedCard = null
            repeat(25) {
                delay(120)
                if (focusedCard == playingKey) return@LaunchedEffect
                runCatching { playingFocus.requestFocus() }
            }
        }
        if (rows.isEmpty()) {
            Message(screen.message ?: tr("Nothing to show right now."))
            return@Column
        }
        LazyColumn(state = listState, modifier = Modifier.focusEntry(entry), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            screen.message?.let { m -> item(key = "msg") { Text(m, color = Palette.Dim, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp)) } }
            items(rows, key = { it.id }) { r ->
                Box(entry.item(r.id)) {
                    CardRow(r.title ?: title, r, api, sync, opener, playingKey, if (r.id == playingRow) playingFocus else null) { focusedCard = it }
                }
            }
        }
    }
}

// ============================================================== groups (channels with a guide, or posters)

@Composable
private fun GroupsView(sync: SyncClient, api: Api, plugin: String, menu: String, screen: Screen.Groups, opener: ItemOpener, playingKey: String?) {
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(screen.groups) {
        if (groupId == null || screen.groups.none { it.id == groupId }) groupId = screen.initial ?: screen.groups.firstOrNull()?.id
    }
    var items by remember { mutableStateOf<List<Item>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(groupId) {
        val g = groupId ?: return@LaunchedEffect
        loading = true
        try { items = api.group(plugin, menu, g); error = null } catch (e: Exception) { error = e.message }
        loading = false
    }
    val groupName = screen.groups.firstOrNull { it.id == groupId }?.title ?: ""
    Row(Modifier.fillMaxSize()) {
        if (screen.groups.size > 1) {
            GroupList(screen.groups, groupId, { groupId = it.id }, Modifier.width(250.dp))
            Spacer(Modifier.width(14.dp))
        }
        when {
            error != null -> Message(tr("Error: {message}", "message" to error), error = true)
            loading && items.isEmpty() -> Message(tr("Loading…"))
            items.isEmpty() -> Message(screen.message ?: tr("Nothing here."))
            screen.style == "channels" -> ChannelsPane(sync, api, plugin, groupId, groupName, items, screen.guide, playingKey)
            else -> GridPane(sync, api, groupId, items, screen.style, opener)
        }
    }
}

/** A channel list (number, logo, now playing) and, if the plugin has a guide, the focused channel's schedule. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ChannelsPane(sync: SyncClient, api: Api, plugin: String, groupId: String?, groupName: String, items: List<Item>, guide: Boolean, playingKey: String?) {
    val favorites by sync.favorites.collectAsState()
    val favKeys = remember(favorites) { favorites.map { it.key }.toSet() }
    var epg by remember { mutableStateOf<Map<String, List<Program>>>(emptyMap()) }
    var focused by remember(items) { mutableStateOf(items.firstOrNull { it.key == playingKey } ?: items.firstOrNull()) }
    val now = rememberNowSec()
    if (guide) LaunchedEffect(items) {
        while (true) {
            epg = runCatching { api.epg(plugin, items.map { it.id }) }.getOrDefault(epg)
            delay(3 * 60_000)
        }
    }
    // From the groups: back to the channel you were on, otherwise the one that plays, otherwise the top one.
    val chState = rememberLazyListState()
    val chEntry = rememberFocusEntry(chState, "channels")
    // A new group: start at the top. (Not when just coming back from the player.)
    var shownGroup by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(groupId) {
        if (shownGroup != null && shownGroup != groupId) { chEntry.last = null; chState.scrollToItem(0) }
        shownGroup = groupId
    }
    Column(Modifier.weight(1.3f).fillMaxHeight().background(Palette.Panel, RoundedCornerShape(12.dp)).padding(8.dp)) {
        Row(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 8.dp)) {
            SectionLabel(groupName)
            SectionLabel("  ·  " + tr("{n} channels", "n" to items.size))
        }
        LazyColumn(
            state = chState, verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.focusEntry(chEntry) { arrayOf<Any?>(chEntry.last, items.indexOfFirst { it.key == playingKey }) },
        ) {
            itemsIndexed(items, key = { _, it -> it.key }) { idx, item ->
                ChannelRow(
                    modifier = chEntry.item(idx),
                    item = item,
                    epg = epg[item.id],
                    nowSec = now,
                    playing = item.key == playingKey,
                    iconUrl = api.abs(item.image),
                    onFocus = { focused = item; chEntry.last = idx },
                    onClick = { sync.load(item.copy(subtitle = item.subtitle ?: groupName)) },
                    favorite = favKeys.contains(item.key),
                    onLongClick = if (item.favorite) ({ sync.toggleFavorite(item) }) else null,
                )
            }
        }
    }
    if (guide) {
        Spacer(Modifier.width(14.dp))
        ChannelGuide(focused, focused?.let { epg[it.id] }, now, api, sync, Modifier.weight(1f).fillMaxHeight())
    }
}

/** A grid of posters (or 16:9 cards) with an info panel for the focused one at the top. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.GridPane(sync: SyncClient, api: Api, groupId: String?, items: List<Item>, style: String, opener: ItemOpener) {
    val favorites by sync.favorites.collectAsState()
    val favMap = remember(favorites) { favorites.associateBy { it.key } }
    var focused by remember(items) { mutableStateOf(items.firstOrNull()) }
    // From the groups: back to the item you were on, otherwise the top one.
    val gridState = rememberLazyGridState()
    val gridEntry = rememberFocusEntry(gridState, "grid")
    var shownGroup by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(groupId) {
        if (shownGroup != null && shownGroup != groupId) { gridEntry.last = null; gridState.scrollToItem(0) }
        shownGroup = groupId
    }
    val posters = style != "landscape"
    Column(Modifier.weight(1f).fillMaxHeight()) {
        if (posters) focused?.let { Hero(it, api) }
        Text(tr("Hold OK to add to favorites"), fontSize = 13.sp, color = Palette.Faint, modifier = Modifier.padding(start = 6.dp, top = 10.dp))
        LazyVerticalGrid(
            state = gridState,
            modifier = Modifier.focusEntry(gridEntry) { arrayOf<Any?>(gridEntry.last) },
            columns = if (posters) GridCells.Adaptive(92.dp) else GridCells.Adaptive(300.dp),
            contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.key }) { idx, item ->
                val fav = favMap[item.key]
                val longClick: (() -> Unit)? = if (item.favorite) ({ sync.toggleFavorite(item) }) else null
                val mod = gridEntry.item(idx).onFocusChanged { if (it.isFocused) { focused = item; gridEntry.last = idx } }
                if (posters) PosterCard(
                    modifier = mod, title = cleanTitle(item.title), iconUrl = api.abs(item.image),
                    onFocus = {}, onClick = { opener.open(item) },
                    favorite = fav != null, newCount = fav?.newCount ?: 0, onLongClick = longClick,
                ) else LandscapeCard(item, api, playing = false, onClick = { opener.open(item) }, modifier = mod, favorite = fav != null, onLongClick = longClick)
            }
        }
    }
}

// ============================================================== detail page (e.g. a series)

/** Loads and shows an item's detail page. [fallback] is shown at the top until it has loaded. */
@Composable
fun DetailView(sync: SyncClient, api: Api, plugin: String, itemId: String, fallback: Item?) {
    var screen by remember(itemId) { mutableStateOf<Screen?>(null) }
    var error by remember(itemId) { mutableStateOf<String?>(null) }
    LaunchedEffect(itemId) {
        try { screen = api.open(plugin, itemId) } catch (e: Exception) { error = e.message }
    }
    val s = screen
    when {
        s is Screen.Detail -> DetailContent(sync, api, s, s.item ?: fallback)
        s is Screen.Message -> Message(s.text ?: tr("Nothing here."))
        error != null -> Column { fallback?.let { Hero(it, api) }; Message(tr("Error: {message}", "message" to error), error = true) }
        else -> Column { fallback?.let { Hero(it, api) }; Message(tr("Loading…")) }
    }
}

@Composable
fun DetailContent(sync: SyncClient, api: Api, screen: Screen.Detail, item: Item?) {
    val favorites by sync.favorites.collectAsState()
    val fav = item?.let { i -> favorites.firstOrNull { it.key == i.key } }
    // Opening it counts its new episodes as seen.
    LaunchedEffect(item?.key, fav?.newCount) {
        if (fav != null && fav.newCount > 0) { delay(1500); sync.favSeen(fav.key) }
    }
    var seasonIdx by rememberSaveable(item?.key) { mutableIntStateOf(0) }
    val firstEp = remember { FocusRequester() }
    val epState = rememberLazyListState()
    val epEntry = rememberFocusEntry(epState, "episodes")
    LaunchedEffect(item?.key) {
        delay(100)
        // Back from the player: the episode you played (handled by epEntry), otherwise the first one.
        if (!epEntry.shouldRestore) runCatching { firstEp.requestFocus() }
    }
    val seasons = screen.seasons.filter { it.items.isNotEmpty() }
    Column(Modifier.fillMaxSize()) {
        if (item != null) Hero(item, api, screen.facts.takeIf { it.isNotEmpty() })
        Spacer(Modifier.height(14.dp))
        screen.message?.let { Text(it, fontSize = 15.sp, color = Palette.Dim, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Something playable without episodes (e.g. a movie): a play button.
            if (item != null && !item.opens && seasons.isEmpty()) item {
                Pill(tr("▶  Play"), selected = true, onClick = { sync.load(item) }, modifier = Modifier.focusRequester(firstEp))
            }
            if (item != null && item.favorite) item {
                Pill(
                    if (fav != null) tr("★ Favorite") else tr("☆ Add to favorites"),
                    selected = fav != null,
                    onClick = { sync.toggleFavorite(item) },
                )
            }
            fav?.newLabel?.takeIf { fav.newCount > 0 }?.let { label ->
                item {
                    Text(tr("New: {episode}", "episode" to label), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                }
            }
            if (seasons.size > 1) itemsIndexed(seasons) { idx, s ->
                Pill(s.title ?: tr("Season {n}", "n" to idx + 1), selected = idx == seasonIdx, onClick = { seasonIdx = idx }, onFocus = { seasonIdx = idx })
            }
        }
        Spacer(Modifier.height(10.dp))
        if (seasons.isEmpty()) {
            if (item?.opens == true) Message(tr("No episodes found"))
            return@Column
        }
        val season = seasons.getOrNull(seasonIdx) ?: seasons.first()
        LazyColumn(
            Modifier.background(Palette.Panel, RoundedCornerShape(12.dp)).padding(8.dp).focusEntry(epEntry),
            state = epState,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            episodeRows(season.items, item, api, firstEp, epEntry) { ep -> sync.load(ep) }
        }
    }
}

private fun LazyListScope.episodeRows(list: List<Item>, parent: Item?, api: Api, firstEp: FocusRequester, entry: FocusEntry, onPlay: (Item) -> Unit) {
    itemsIndexed(list, key = { _, it -> it.key }) { idx, ep ->
        FocusRow(
            onClick = { onPlay(ep) },
            height = 70.dp,
            modifier = (if (idx == 0) Modifier.focusRequester(firstEp) else Modifier).then(entry.item(idx)),
        ) {
            // Dimmed text follows the row's colour, so it stays readable on the focused (blue) row.
            val dim = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.7f)
            Text(ep.subtitle ?: "", fontSize = 15.sp, color = dim, maxLines = 1, modifier = Modifier.width(96.dp))
            Thumb(api.abs(ep.image ?: parent?.image), ep.title, Modifier.width(96.dp).height(54.dp), fit = false)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(ep.title, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ep.description?.let { Text(it, fontSize = 13.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            ep.duration?.let { Text(formatMinutes(it), fontSize = 13.sp, color = dim) }
        }
    }
}

fun formatMinutes(sec: Double): String {
    val min = (sec / 60).toInt().coerceAtLeast(1)
    return if (min >= 60) tr("{h} h {m} min", "h" to min / 60, "m" to min % 60) else tr("{m} min", "m" to min)
}
