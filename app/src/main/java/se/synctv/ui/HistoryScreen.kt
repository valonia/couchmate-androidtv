@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package se.synctv.ui

import se.synctv.t
import se.synctv.I18n
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import se.synctv.Api
import se.synctv.HistoryEntry
import se.synctv.SyncClient
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale


private fun whenText(ms: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val time = hhmm(ms / 1000)
    return when {
        same(then, today) -> t("idag $time", "today $time")
        same(then, yesterday) -> t("igår $time", "yesterday $time")
        else -> "${SimpleDateFormat("d MMM", I18n.locale).format(Date(ms))} $time"
    }
}

private fun remaining(e: HistoryEntry): String? {
    val d = e.duration ?: return null
    if (e.finished) return t("Sedd", "Watched")
    val min = ((d - e.position) / 60).toInt().coerceAtLeast(1)
    return if (min >= 60) t("${min / 60} h ${min % 60} min kvar", "${min / 60} h ${min % 60} min left") else t("$min min kvar", "$min min left")
}

/** Rummets gemensamma historik – vad ni tittat på, vem som startade och var ni slutade. */
@Composable
fun HistoryScreen(sync: SyncClient, api: Api) {
    var entries by remember { mutableStateOf<List<HistoryEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val state by sync.state.collectAsState()

    // Hämta direkt och sedan regelbundet, så att det de andra tittar på dyker upp.
    LaunchedEffect(Unit) {
        while (true) {
            try {
                entries = api.history()
                error = null
            } catch (e: Exception) {
                error = e.message
            }
            delay(10_000)
        }
    }

    fun play(e: HistoryEntry, fromStart: Boolean = false) {
        val pos = if (!fromStart && e.resumable) e.position else 0.0
        sync.load(e.type, e.id, e.ext, e.title, e.subtitle, e.icon, e.categoryId, position = pos, seriesId = e.seriesId, live = e.live)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, bottom = 14.dp)) {
            Text(t("Historik", "History"), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(t("   Det ni har tittat på tillsammans", "   What you've watched together"), fontSize = 16.sp, color = Palette.Faint)
            Spacer(Modifier.weight(1f))
            if (!entries.isNullOrEmpty()) {
                Pill(t("Rensa historik", "Clear history"), selected = false, onClick = {
                    sync.historyClear()
                    entries = emptyList()
                })
            }
        }

        val list = entries
        when {
            error != null && list == null -> Text(t("Fel: $error", "Error: $error"), color = Color(0xFFFF8A80), modifier = Modifier.padding(12.dp))
            list == null -> Text(t("Laddar…", "Loading…"), color = Palette.Dim, modifier = Modifier.padding(12.dp))
            list.isEmpty() -> Text(
                t("Inget här än. Allt ni startar sparas här, och filmer och avsnitt kan fortsättas där ni slutade.", "Nothing here yet. Everything you start is saved here, and movies and episodes can be resumed where you left off."),
                color = Palette.Dim, fontSize = 16.sp, modifier = Modifier.padding(12.dp),
            )
            else -> LazyColumn(
                Modifier.background(Palette.Panel, RoundedCornerShape(12.dp)),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(list, key = { it.key }) { e ->
                    HistoryRow(
                        e = e,
                        playingNow = state?.media?.let { "${it.type}:${it.id}" } == e.key,
                        iconUrl = api.abs(e.icon),
                        onPlay = { play(e) },
                        onRestart = { play(e, fromStart = true) },
                        onRemove = {
                            sync.historyRemove(e.key)
                            entries = entries?.filter { it.key != e.key }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    e: HistoryEntry,
    playingNow: Boolean,
    iconUrl: String?,
    onPlay: () -> Unit,
    onRestart: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FocusRow(onClick = onPlay, modifier = Modifier.weight(1f), height = 92.dp, selected = playingNow) {
            if (e.isLive) {
                Thumb(iconUrl, e.title, Modifier.width(110.dp).height(62.dp))
            } else {
                Box(Modifier.width(110.dp), contentAlignment = Alignment.Center) {
                    Thumb(iconUrl, e.title, Modifier.width(52.dp).height(78.dp), fit = false)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (e.isLive) { Spacer(Modifier.width(10.dp)); LiveBadge() }
                    if (playingNow) { Spacer(Modifier.width(10.dp)); Text(t("▶ Spelas nu", "▶ Playing now"), fontSize = 13.sp) }
                }
                e.subtitle?.let { Text(it, fontSize = 14.sp, color = Color(0xB3FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(e.startedBy?.let { t("Startad av $it", "Started by $it") }, whenText(e.lastAt)).joinToString("  ·  "),
                        fontSize = 13.sp,
                        color = Color(0x99FFFFFF),
                    )
                    remaining(e)?.let {
                        Spacer(Modifier.width(14.dp))
                        Text(it, fontSize = 13.sp, color = if (e.finished) Color(0xFF3DD68C) else Color(0x99FFFFFF))
                    }
                }
                if (!e.isLive && e.duration != null) {
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(e.fraction, Modifier.fillMaxWidth(0.6f), color = if (e.finished) Color(0xFF3DD68C) else Color(0xE6FFFFFF))
                }
            }
            if (!e.isLive) {
                Text(if (e.resumable) t("Fortsätt", "Resume") else t("Spela", "Play"), fontSize = 14.sp, color = Color(0xCCFFFFFF))
            }
        }
        Spacer(Modifier.width(8.dp))
        if (e.resumable) {
            Pill(t("Från början", "Start over"), selected = false, onClick = onRestart)
            Spacer(Modifier.width(4.dp))
        }
        Pill(t("Ta bort", "Remove"), selected = false, onClick = onRemove)
    }
}
