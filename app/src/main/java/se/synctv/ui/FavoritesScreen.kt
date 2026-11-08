@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package se.synctv.ui

import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Notifications
import se.synctv.next
import se.synctv.now
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import se.synctv.Api
import se.synctv.Favorite
import se.synctv.Item
import se.synctv.SyncClient
import se.synctv.t

private fun Favorite.toItem() = Item(id = id, name = name, icon = icon, ext = ext, categoryId = categoryId, categoryName = categoryName)

/** Rummets gemensamma favoriter: serier (med markering för nya avsnitt), filmer och kanaler. */
@Composable
fun FavoritesScreen(sync: SyncClient, api: Api) {
    val favorites by sync.favorites.collectAsState()
    val reminders by sync.reminders.collectAsState()
    var series by remember { mutableStateOf<Item?>(null) }
    var guideFor by remember { mutableStateOf<Favorite?>(null) }
    BackHandler(enabled = guideFor != null) { guideFor = null }
    var epgLoaded by remember { mutableStateOf(false) }
    // EPG (nu/nästa) för favoritkanalerna
    val liveIds = favorites.filter { it.type == "live" }.map { it.id }
    var epg by remember { mutableStateOf<Map<String, List<se.synctv.Program>>>(emptyMap()) }
    LaunchedEffect(liveIds) {
        if (liveIds.isEmpty()) return@LaunchedEffect
        while (true) {
            epg = runCatching { api.epgFor(liveIds) }.getOrDefault(epg)
            epgLoaded = true
            delay(3 * 60_000)
        }
    }
    val now = rememberNowSec()
    BackHandler(enabled = series != null) { series = null }

    val s = series
    if (s != null) {
        SeriesDetail(api, sync, s) { ep, seasonNo ->
            sync.load(
                type = "episode", id = ep.id, ext = ep.ext,
                title = cleanTitle(s.name),
                subtitle = "S%02dE%02d · %s".format(seasonNo, ep.episode, ep.title),
                icon = ep.icon ?: s.icon,
                categoryId = s.categoryId,
                seriesId = s.id,
            )
        }
        return
    }

    val g = guideFor
    if (g != null) {
        val first = remember { FocusRequester() }
        // När man släpper OK efter "håll inne" räknas det som ett tryck på knappen som just fått fokus.
        // Därför ignoreras tryck en kort stund efter att tablån öppnats.
        var released by remember(g.key) { mutableStateOf(false) }
        fun guarded(action: () -> Unit): () -> Unit = { if (released) action() }
        LaunchedEffect(g.key) { delay(80); runCatching { first.requestFocus() } }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp).onPreviewKeyEvent { ev ->
                val ok = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                when {
                    released || !ok -> { released = true; false }
                    ev.type == KeyEventType.KeyUp -> { released = true; true } // släppet efter "håll inne" – ignorera
                    else -> true // upprepningar medan knappen fortfarande hålls in
                }
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp)) {
                Pill(t("‹  Tillbaka", "‹  Back"), selected = false, onClick = guarded { guideFor = null }, modifier = Modifier.focusRequester(first))
                Spacer(Modifier.width(8.dp))
                Pill(t("Titta nu", "Watch now"), selected = true, onClick = guarded {
                    sync.load("live", g.id, g.ext.ifBlank { "ts" }, g.name, g.categoryName, g.icon, g.categoryId)
                })
                Spacer(Modifier.width(8.dp))
                Pill(t("Ta bort från favoriter", "Remove from favourites"), selected = false, onClick = guarded { sync.favRemove(g.key); guideFor = null })
            }
            ChannelGuide(g.toItem(), epg[g.id], now, api, sync, g.categoryName, Modifier.fillMaxSize(), showHints = false)
        }
        return
    }

    // Serier med nya avsnitt först
    val seriesFavs = favorites.filter { it.type == "series" }.sortedByDescending { it.newCount }
    val movieFavs = favorites.filter { it.type == "movie" }
    val liveFavs = favorites.filter { it.type == "live" }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, bottom = 14.dp)) {
            Text(t("Favoriter", "Favourites"), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                t("   Gemensamma för alla i rummet  ·  Håll inne OK: ta bort (kanaler: tablå)", "   Shared with everyone in the room  ·  Hold OK: remove (channels: guide)"),
                fontSize = 15.sp, color = Palette.Faint,
            )
        }

        if (favorites.isEmpty() && reminders.isEmpty()) {
            Text(
                t(
                    "Inga favoriter än. Håll inne OK på en serie, film eller kanal för att lägga till den. Kommer det nya avsnitt i en favoritserie markeras det här.",
                    "No favourites yet. Hold OK on a series, movie or channel to add it. New episodes of favourite series are marked here.",
                ),
                fontSize = 16.sp, color = Palette.Dim, modifier = Modifier.padding(12.dp),
            )
            return@Column
        }

        // Från menyn: tillbaka till raden man var på, annars översta – inte raden i samma höjd.
        val listState = rememberLazyListState()
        val entry = rememberFocusEntryByKey(listState, "favorites")
        LazyColumn(state = listState, modifier = Modifier.focusEntry(entry), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            if (reminders.isNotEmpty()) item(key = "fav-reminders") { Box(entry.item("fav-reminders")) {
                FavSection(t("Påminnelser", "Reminders"), reminders.size) {
                    items(reminders) { r ->
                        FocusRow(
                            onClick = { sync.load("live", r.channel.id, r.channel.ext.ifBlank { "ts" }, r.channel.name, r.channel.categoryName, r.channel.icon, r.channel.categoryId) },
                            onLongClick = { sync.reminderRemove(r.key) },
                            modifier = Modifier.width(380.dp).background(Palette.Panel, RoundedCornerShape(8.dp)),
                            height = 84.dp,
                        ) {
                            androidx.tv.material3.Icon(
                                androidx.compose.material.icons.Icons.Filled.Notifications, contentDescription = null,
                                tint = Palette.Gold, modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${dayLabel(r.start)} ${hhmm(r.start)}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Palette.Gold)
                                Text(r.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(r.channel.name + (r.addedBy?.let { t(" · av $it", " · by $it") } ?: ""), fontSize = 13.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            } }
            if (seriesFavs.isNotEmpty()) item(key = "fav-series") { Box(entry.item("fav-series")) {
                FavSection(t("Serier", "Series"), seriesFavs.size) {
                    items(seriesFavs) { f ->
                        PosterCard(
                            title = cleanTitle(f.name),
                            iconUrl = api.abs(f.icon),
                            onClick = { series = f.toItem() },
                            onFocus = {},
                            modifier = Modifier.width(128.dp),
                            favorite = true,
                            newCount = f.newCount,
                            caption = f.newLabel?.takeIf { f.newCount > 0 }?.let { t("Nytt: $it", "New: $it") },
                            onLongClick = { sync.favRemove(f.key) },
                        )
                    }
                }
            } }
            if (movieFavs.isNotEmpty()) item(key = "fav-movies") { Box(entry.item("fav-movies")) {
                FavSection(t("Filmer", "Movies"), movieFavs.size) {
                    items(movieFavs) { f ->
                        PosterCard(
                            title = cleanTitle(f.name),
                            iconUrl = api.abs(f.icon),
                            onClick = { sync.load("movie", f.id, f.ext, cleanTitle(f.name), yearFromTitle(f.name), f.icon, f.categoryId) },
                            onFocus = {},
                            modifier = Modifier.width(128.dp),
                            favorite = true,
                            onLongClick = { sync.favRemove(f.key) },
                        )
                    }
                }
            } }
            if (liveFavs.isNotEmpty()) item(key = "fav-live") { Box(entry.item("fav-live")) {
                FavSection(t("Kanaler", "Channels"), liveFavs.size) {
                    items(liveFavs) { f ->
                        FocusRow(
                            onClick = { sync.load("live", f.id, f.ext.ifBlank { "ts" }, f.name, f.categoryName, f.icon, f.categoryId) },
                            onLongClick = { guideFor = f },
                            modifier = Modifier.width(380.dp).background(Palette.Panel, RoundedCornerShape(8.dp)),
                            height = 96.dp,
                        ) {
                            Thumb(api.abs(f.icon), f.name, Modifier.width(80.dp).height(46.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val progs = epg[f.id]
                                val cur = progs.now(now)
                                val nxt = progs.next(now)
                                if (progs == null) {
                                    f.categoryName?.let { Text(it, fontSize = 13.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    if (epgLoaded) Text(t("Ingen programinformation", "No programme information"), fontSize = 12.sp, color = Palette.Faint, maxLines = 1)
                                } else {
                                    Text(cur?.title ?: t("Ingen programinformation", "No programme information"), fontSize = 13.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (cur != null) ProgressLine(cur.progress(now), Modifier.fillMaxWidth().padding(vertical = 4.dp))
                                    if (nxt != null) {
                                        val reminded = reminders.any { it.key == se.synctv.reminderKey(f.id, nxt.start) }
                                        Text(
                                            (if (reminded) "🔔 " else "") + "${hhmm(nxt.start)}  ${nxt.title}",
                                            fontSize = 12.sp, color = if (reminded) Palette.Gold else Palette.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            Text("★", fontSize = 15.sp, color = Palette.Gold)
                        }
                    }
                }
            } }
        }
    }
}

@Composable
private fun FavSection(title: String, count: Int, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        Row(Modifier.padding(start = 12.dp, bottom = 10.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("  $count", fontSize = 20.sp, color = Palette.Faint)
        }
        // focusRestorer: tillbaka till kortet man var på (även efter spelaren).
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}
