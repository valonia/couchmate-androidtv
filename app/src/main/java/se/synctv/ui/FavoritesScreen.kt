@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package se.synctv.ui

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
    var series by remember { mutableStateOf<Item?>(null) }
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

    // Serier med nya avsnitt först
    val seriesFavs = favorites.filter { it.type == "series" }.sortedByDescending { it.newCount }
    val movieFavs = favorites.filter { it.type == "movie" }
    val liveFavs = favorites.filter { it.type == "live" }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, bottom = 14.dp)) {
            Text(t("Favoriter", "Favourites"), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                t("   Gemensamma för alla i rummet  ·  Håll inne OK för att ta bort", "   Shared with everyone in the room  ·  Hold OK to remove"),
                fontSize = 15.sp, color = Palette.Faint,
            )
        }

        if (favorites.isEmpty()) {
            Text(
                t(
                    "Inga favoriter än. Håll inne OK på en serie, film eller kanal för att lägga till den. Kommer det nya avsnitt i en favoritserie markeras det här.",
                    "No favourites yet. Hold OK on a series, movie or channel to add it. New episodes of favourite series are marked here.",
                ),
                fontSize = 16.sp, color = Palette.Dim, modifier = Modifier.padding(12.dp),
            )
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            if (seriesFavs.isNotEmpty()) item {
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
            }
            if (movieFavs.isNotEmpty()) item {
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
            }
            if (liveFavs.isNotEmpty()) item {
                FavSection(t("Kanaler", "Channels"), liveFavs.size) {
                    items(liveFavs) { f ->
                        FocusRow(
                            onClick = { sync.load("live", f.id, f.ext.ifBlank { "ts" }, f.name, f.categoryName, f.icon, f.categoryId) },
                            onLongClick = { sync.favRemove(f.key) },
                            modifier = Modifier.width(300.dp).background(Palette.Panel, RoundedCornerShape(8.dp)),
                            height = 76.dp,
                        ) {
                            Thumb(api.abs(f.icon), f.name, Modifier.width(80.dp).height(46.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                f.categoryName?.let { Text(it, fontSize = 13.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            Text("★", fontSize = 15.sp, color = Palette.Gold)
                        }
                    }
                }
            }
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
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}
