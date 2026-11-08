@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package se.synctv.ui

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import se.synctv.Api
import se.synctv.Item
import se.synctv.Source
import se.synctv.SourceBrowse
import se.synctv.SyncClient
import se.synctv.t

private val iconCache = HashMap<String, ImageVector>()

/** Ikonen som pluginet skickar (SVG-sökväg i en 24×24-ruta), annars en vanlig TV-ikon. */
fun sourceIcon(path: String?): ImageVector {
    if (path.isNullOrBlank()) return AppIcons.LiveTv
    return iconCache.getOrPut(path) {
        runCatching {
            ImageVector.Builder(name = "source", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
                .addPath(pathData = PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
                .build()
        }.getOrDefault(AppIcons.LiveTv)
    }
}

private fun viewersText(n: Int): String = when {
    n >= 1_000_000 -> "%.1f mn".format(n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000} k"
    n >= 1_000 -> "%.1f k".format(n / 1000.0)
    else -> "$n"
}.replace('.', ',')

/** Kort för en ström/video från en källa: bild 16:9 med LIVE och tittare, titel och kanal. */
@Composable
fun SourceCard(item: Item, playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
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
                Thumb(item.icon, item.subtitle ?: item.name, Modifier.fillMaxSize(), fit = false, corner = 12.dp)
                Row(Modifier.align(Alignment.TopStart).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.live) {
                        Text(
                            "LIVE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.background(Palette.Live, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    item.viewers?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            t("${viewersText(it)} tittare", "${viewersText(it)} viewers"), fontSize = 11.sp, color = Color.White,
                            modifier = Modifier.background(Color(0xB3000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(item.subtitle ?: "", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** En rad med kort (används både i källans vy och i sökresultaten). */
@Composable
fun SourceRow(title: String, items: List<Item>, sync: SyncClient, playingId: String?, playingFocus: androidx.compose.ui.focus.FocusRequester? = null, onCardFocus: (String) -> Unit = {}) {
    Column {
        Row(Modifier.padding(start = 12.dp, bottom = 8.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("  ${items.size}", fontSize = 20.sp, color = Palette.Faint)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(items, key = { it.id }) { item ->
                SourceCard(
                    item, playing = item.id == playingId, onClick = { sync.loadSource(item) },
                    modifier = (if (playingFocus != null && item.id == playingId) Modifier.focusRequester(playingFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) onCardFocus(item.id) },
                )
            }
        }
    }
}

/** Vyn för en källa i menyn (t.ex. Twitch): raderna som pluginet levererar. Uppdateras varje minut. */
@Composable
fun SourceScreen(sync: SyncClient, api: Api, source: Source, playingId: String?) {
    var data by remember(source.id) { mutableStateOf<SourceBrowse?>(null) }
    var error by remember(source.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(source.id) {
        while (true) {
            try { data = api.sourceBrowse(source.id); error = null } catch (e: Exception) { error = e.message }
            delay(60_000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, bottom = 10.dp)) {
            androidx.tv.material3.Icon(sourceIcon(source.iconPath), contentDescription = null, tint = Palette.Accent, modifier = Modifier.padding(end = 12.dp).width(28.dp).height(28.dp))
            Text(source.name, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        val d = data
        // Från menyn: tillbaka till raden man var på, annars översta – inte raden i samma höjd.
        val listState = rememberLazyListState()
        val entry = rememberFocusEntryByKey(listState, "source", autoRestore = false)
        var focusedCard by remember { mutableStateOf<String?>(null) }
        // Tillbaka från spelaren: till strömmen som spelas (listan kan ha bytt ordning), annars raden man var på.
        val playingFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val playingRow = d?.sections?.firstOrNull { s -> s.items.any { it.id == playingId } }?.id
        LaunchedEffect(d != null) {
            if (d == null || !entry.shouldRestore) return@LaunchedEffect
            if (playingRow == null) { entry.restore(); return@LaunchedEffect }
            // Raden lägger sig till rätta en stund efter att listan kommit – försök tills rätt kort har fokus.
            focusedCard = null
            repeat(25) {
                delay(120)
                if (focusedCard == playingId) return@LaunchedEffect
                runCatching { playingFocus.requestFocus() }
            }
        }
        when {
            d == null && error != null -> Text(t("Fel: $error", "Error: $error"), color = Color(0xFFFF8A80), fontSize = 16.sp, modifier = Modifier.padding(12.dp))
            d == null -> Text(t("Laddar…", "Loading…"), color = Palette.Dim, fontSize = 16.sp, modifier = Modifier.padding(12.dp))
            d.sections.all { it.items.isEmpty() } -> Text(d.message ?: t("Inget att visa just nu.", "Nothing to show right now."), color = Palette.Dim, fontSize = 16.sp, modifier = Modifier.padding(12.dp))
            else -> LazyColumn(state = listState, modifier = Modifier.focusEntry(entry), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                d.message?.let { m -> item(key = "msg") { Text(m, color = Palette.Dim, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp)) } }
                items(d.sections.filter { it.items.isNotEmpty() }, key = { it.id }) { s -> Box(entry.item(s.id)) { SourceRow(s.title, s.items, sync, playingId, if (s.id == playingRow) playingFocus else null) { focusedCard = it } } }
            }
        }
    }
}
