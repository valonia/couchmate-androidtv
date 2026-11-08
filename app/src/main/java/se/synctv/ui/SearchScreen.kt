@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package se.synctv.ui

import se.synctv.t
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import se.synctv.Api
import se.synctv.Item
import se.synctv.SearchResult
import se.synctv.SyncClient
import se.synctv.favKey

/** Sök i kanaler, filmer och serier samtidigt. Resultat visas medan man skriver. */
@Composable
fun SearchScreen(sync: SyncClient, api: Api) {
    val favorites by sync.favorites.collectAsState()
    val favKeys = remember(favorites) { favorites.map { it.key }.toSet() }
    val favMap = remember(favorites) { favorites.associateBy { it.key } }
    var query by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<SearchResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var series by remember { mutableStateOf<Item?>(null) }
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { delay(150); runCatching { fieldFocus.requestFocus() } }

    // Sök medan man skriver (med en kort fördröjning).
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { result = null; error = null; return@LaunchedEffect }
        delay(350)
        loading = true
        try {
            result = api.search(q)
            error = null
        } catch (e: Exception) {
            error = e.message
        }
        loading = false
    }

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

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // ---- sökfält
        var focused by remember { mutableStateOf(false) }
        Box(
            Modifier
                .fillMaxWidth(0.6f)
                .background(Palette.Panel, RoundedCornerShape(50))
                .border(2.dp, if (focused) Color.White else Palette.Line, RoundedCornerShape(50))
                .padding(horizontal = 28.dp, vertical = 16.dp),
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Palette.Text, fontSize = 22.sp),
                cursorBrush = SolidColor(Palette.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    focusManager.moveFocus(FocusDirection.Down)
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                            else -> false
                        }
                    },
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.Filled.Search, contentDescription = null, tint = Palette.Dim, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(14.dp))
                        Box {
                            if (query.isEmpty()) Text(t("Sök kanaler, filmer och serier…", "Search channels, movies and series…"), fontSize = 22.sp, color = Palette.Faint)
                            inner()
                        }
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                error != null -> t("Fel: $error", "Error: $error")
                loading -> t("Söker…", "Searching…")
                query.trim().length < 2 -> t("Skriv minst två tecken. Tryck på mikrofonen på tangentbordet för att söka med rösten.", "Type at least two characters. Press the microphone on the keyboard to search by voice.")
                result?.isEmpty == true -> t("Inga träffar för \"${query.trim()}\"", "No results for \"${query.trim()}\"")
                else -> t("Håll inne OK för att lägga till i favoriter", "Hold OK to add to favourites")
            },
            fontSize = 14.sp,
            color = if (error != null) Color(0xFFFF8A80) else Palette.Faint,
            modifier = Modifier.padding(start = 28.dp, top = 4.dp, bottom = 8.dp),
        )

        // ---- resultat
        val r = result
        if (r != null && !r.isEmpty) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                if (r.live.isNotEmpty()) item {
                    ResultSection(t("Kanaler", "Channels"), r.live.size) {
                        items(r.live) { ch ->
                            ChannelCard(
                                ch, api.abs(ch.icon),
                                favorite = favKeys.contains(favKey("live", ch.id)),
                                onLongClick = { sync.toggleFavorite("live", ch, ch.categoryName) },
                            ) {
                                sync.load("live", ch.id, ch.ext, ch.name, ch.categoryName, ch.icon, ch.categoryId)
                            }
                        }
                    }
                }
                if (r.vod.isNotEmpty()) item {
                    ResultSection(t("Filmer", "Movies"), r.vod.size) {
                        items(r.vod) { m ->
                            PosterCard(cleanTitle(m.name), api.abs(m.icon), onFocus = {}, modifier = Modifier.width(128.dp), onClick = {
                                sync.load("movie", m.id, m.ext, cleanTitle(m.name), yearFromTitle(m.name) ?: m.year, m.icon, m.categoryId)
                            },
                                favorite = favKeys.contains(favKey("movie", m.id)),
                                onLongClick = { sync.toggleFavorite("movie", m, m.categoryName) },
                            )
                        }
                    }
                }
                // Träffar från plugin-källor (t.ex. Twitch-strömmar som sänder just nu)
                r.sources.filter { it.items.isNotEmpty() }.forEach { src ->
                    item(key = "src-${src.id}") { SourceRow(src.name, src.items, sync, playingId = null) }
                }
                if (r.series.isNotEmpty()) item {
                    ResultSection(t("Serier", "Series"), r.series.size) {
                        items(r.series) { sr ->
                            PosterCard(cleanTitle(sr.name), api.abs(sr.icon), onFocus = {}, modifier = Modifier.width(128.dp), onClick = { series = sr },
                                favorite = favMap.containsKey(favKey("series", sr.id)),
                                newCount = favMap[favKey("series", sr.id)]?.newCount ?: 0,
                                onLongClick = { sync.toggleFavorite("series", sr, sr.categoryName) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultSection(title: String, count: Int, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        Row(Modifier.padding(start = 12.dp, bottom = 10.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("  $count", fontSize = 20.sp, color = Palette.Faint)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun ChannelCard(item: Item, iconUrl: String?, favorite: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    FocusRow(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.width(300.dp).background(Palette.Panel, RoundedCornerShape(8.dp)), height = 76.dp) {
        Thumb(iconUrl, item.name, Modifier.width(80.dp).height(46.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.categoryName?.let { Text(it, fontSize = 13.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (favorite) Text("★", fontSize = 15.sp, color = Palette.Gold)
    }
}
