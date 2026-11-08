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

fun formatMinutes(sec: Double): String {
    val min = (sec / 60).toInt().coerceAtLeast(1)
    return if (min >= 60) tr("{h} h {m} min", "h" to min / 60, "m" to min % 60) else tr("{m} min", "m" to min)
}
