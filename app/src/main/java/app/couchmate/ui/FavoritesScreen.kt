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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.couchmate.Api
import app.couchmate.FavGroup
import app.couchmate.Favorite
import app.couchmate.Program
import app.couchmate.SyncClient
import app.couchmate.next
import app.couchmate.now
import app.couchmate.reminderKey
import app.couchmate.tr
import kotlinx.coroutines.delay

/** The room's shared favourites, from every plugin, with new episodes marked. */
@Composable
fun FavoritesScreen(sync: SyncClient, api: Api) {
    val favorites by sync.favorites.collectAsState()
    val reminders by sync.reminders.collectAsState()
    var openedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var guideFor by remember { mutableStateOf<Favorite?>(null) }
    BackHandler(enabled = guideFor != null) { guideFor = null }

    // Now/next for the favourite channels, per plugin.
    val channels = favorites.filter { it.group.style == "guide" }
    var epg by remember { mutableStateOf<Map<String, List<Program>>>(emptyMap()) }
    var epgLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(channels.map { it.key }) {
        if (channels.isEmpty()) return@LaunchedEffect
        while (true) {
            val out = HashMap<String, List<Program>>()
            for ((plugin, list) in channels.groupBy { it.plugin }) {
                runCatching { api.epg(plugin, list.map { it.id }) }.getOrNull()?.forEach { (id, p) -> out["$plugin:$id"] = p }
            }
            epg = out
            epgLoaded = true
            delay(3 * 60_000)
        }
    }
    val now = rememberNowSec()

    val opened = favorites.firstOrNull { it.key == openedKey }
    if (opened != null) {
        key(opened.key) { ViewHost(sync, api, ViewRef.forItem(opened.toItem()), onExit = { openedKey = null }) }
        return
    }

    val g = guideFor
    if (g != null) {
        val first = remember { FocusRequester() }
        // Letting go of OK after "hold" counts as a press on the button that just got focus,
        // so presses are ignored for a moment after the guide opens.
        var released by remember(g.key) { mutableStateOf(false) }
        fun guarded(action: () -> Unit): () -> Unit = { if (released) action() }
        LaunchedEffect(g.key) { delay(80); runCatching { first.requestFocus() } }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp).onPreviewKeyEvent { ev ->
                val ok = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                when {
                    released || !ok -> { released = true; false }
                    ev.type == KeyEventType.KeyUp -> { released = true; true } // letting go after "hold" – ignore
                    else -> true // repeats while the button is still held
                }
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp)) {
                Pill(tr("‹  Back"), selected = false, onClick = guarded { guideFor = null }, modifier = Modifier.focusRequester(first))
                Spacer(Modifier.width(8.dp))
                Pill(tr("Watch now"), selected = true, onClick = guarded { sync.load(g.toItem()) })
                Spacer(Modifier.width(8.dp))
                Pill(tr("Remove from favorites"), selected = false, onClick = guarded { sync.favRemove(g.key); guideFor = null })
            }
            ChannelGuide(g.toItem(), epg[g.key], now, api, sync, Modifier.fillMaxSize(), showHints = false)
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, bottom = 14.dp)) {
            Text(tr("Favorites"), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("   " + tr("Shared with everyone in the room  ·  Hold OK: remove (channels: guide)"), fontSize = 15.sp, color = Palette.Faint)
        }

        if (favorites.isEmpty() && reminders.isEmpty()) {
            Text(
                tr("No favorites yet. Hold OK on anything to add it. New episodes of favorite series are marked here."),
                fontSize = 16.sp, color = Palette.Dim, modifier = Modifier.padding(12.dp),
            )
            return@Column
        }

        // In the groups the plugins name (ordered by them); within a group, new episodes and live ones first.
        val sorted = favorites.sortedWith(compareByDescending<Favorite> { it.newCount }.thenByDescending { it.live })
        val groups = sorted.map { it.group }.distinctBy { it.id }.sortedWith(compareBy<FavGroup> { it.order }.thenBy { it.title })
        val byGroup = sorted.groupBy { it.group.id }

        // From the menu: back to the row you were on, otherwise the top one – not the row at the same height.
        val listState = rememberLazyListState()
        val entry = rememberFocusEntryByKey(listState, "favorites")
        LazyColumn(state = listState, modifier = Modifier.focusEntry(entry), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            if (reminders.isNotEmpty()) item(key = "fav-reminders") { Box(entry.item("fav-reminders")) {
                FavSection(tr("Reminders"), reminders.size) {
                    items(reminders, key = { it.key }) { r ->
                        FocusRow(
                            onClick = { sync.load(r.channel) },
                            onLongClick = { sync.reminderRemove(r.key) },
                            modifier = Modifier.width(380.dp).background(Palette.Panel, RoundedCornerShape(8.dp)),
                            height = 84.dp,
                        ) {
                            androidx.tv.material3.Icon(Icons.Filled.Notifications, contentDescription = null, tint = Palette.Gold, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${dayLabel(r.start)} ${hhmm(r.start)}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Palette.Gold)
                                Text(r.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(r.channel.title + (r.addedBy?.let { " · " + tr("by {name}", "name" to it) } ?: ""), fontSize = 13.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            } }
            for (group in groups) {
                val list = byGroup[group.id].orEmpty()
                item(key = "fav-${group.id}") { Box(entry.item("fav-${group.id}")) {
                    FavSection(group.title, list.size) {
                        when (group.style) {
                            "guide" -> items(list, key = { it.key }) { f -> FavChannel(f, api, epg[f.key], epgLoaded, now, reminders.map { it.key }.toSet(), onClick = { sync.load(f.toItem()) }, onLongClick = { guideFor = f }) }
                            "poster" -> items(list, key = { it.key }) { f ->
                                PosterCard(
                                    title = cleanTitle(f.title), iconUrl = api.abs(f.image),
                                    onClick = { if (f.action == "open") openedKey = f.key else sync.load(f.toItem()) },
                                    onFocus = {}, modifier = Modifier.width(128.dp), favorite = true, newCount = f.newCount,
                                    caption = f.newLabel?.takeIf { f.newCount > 0 }?.let { tr("New: {episode}", "episode" to it) },
                                    onLongClick = { sync.favRemove(f.key) },
                                )
                            }
                            else -> items(list, key = { it.key }) { f ->
                                LandscapeCard(f.toItem(), api, playing = false, favorite = true,
                                    onClick = { if (f.action == "open") openedKey = f.key else sync.load(f.toItem()) },
                                    onLongClick = { sync.favRemove(f.key) })
                            }
                        }
                    }
                } }
            }
        }
    }
}

@Composable
private fun FavChannel(f: Favorite, api: Api, progs: List<Program>?, epgLoaded: Boolean, now: Long, reminderKeys: Set<String>, onClick: () -> Unit, onLongClick: () -> Unit) {
    FocusRow(
        onClick = onClick, onLongClick = onLongClick,
        modifier = Modifier.width(380.dp).background(Palette.Panel, RoundedCornerShape(8.dp)),
        height = 96.dp,
    ) {
        Thumb(api.abs(f.image), f.title, Modifier.width(80.dp).height(46.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(f.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val cur = progs.now(now)
            val nxt = progs.next(now)
            if (progs == null) {
                f.subtitle?.let { Text(it, fontSize = 13.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (epgLoaded) Text(tr("No programme information"), fontSize = 12.sp, color = Palette.Faint, maxLines = 1)
            } else {
                Text(cur?.title ?: tr("No programme information"), fontSize = 13.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (cur != null) ProgressLine(cur.progress(now), Modifier.fillMaxWidth().padding(vertical = 4.dp))
                if (nxt != null) {
                    val reminded = reminderKey(f.plugin, f.id, nxt.start) in reminderKeys
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

@Composable
private fun FavSection(title: String, count: Int, content: LazyListScope.() -> Unit) {
    Column {
        Row(Modifier.padding(start = 12.dp, bottom = 10.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("  $count", fontSize = 20.sp, color = Palette.Faint)
        }
        // focusRestorer: back to the card you were on (also after the player).
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}
