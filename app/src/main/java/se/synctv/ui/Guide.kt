@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package se.synctv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import se.synctv.Api
import se.synctv.I18n
import se.synctv.Item
import se.synctv.PendingReminder
import se.synctv.Program
import se.synctv.Reminder
import se.synctv.SyncClient
import se.synctv.t
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/** "Idag", "Imorgon" eller t.ex. "tisdag 10 november". */
fun dayLabel(start: Long): String {
    val d = Calendar.getInstance().apply { timeInMillis = start * 1000 }
    val today = Calendar.getInstance()
    val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        same(d, today) -> t("Idag", "Today")
        same(d, tomorrow) -> t("Imorgon", "Tomorrow")
        else -> SimpleDateFormat("EEEE d MMMM", I18n.locale).format(Date(start * 1000))
    }
}

/**
 * Höger panel i TV-fliken: kanalens tablå (pågående och kommande program).
 * → på fjärren flyttar in i tablån; OK på ett kommande program = påminnelse för hela rummet.
 */
@Composable
fun ChannelGuide(item: Item?, shortEpg: List<Program>?, now: Long, api: Api, sync: SyncClient, categoryName: String?, modifier: Modifier = Modifier, showHints: Boolean = true) {
    val reminders by sync.reminders.collectAsState()
    var guide by remember(item?.id) { mutableStateOf<List<Program>?>(null) }
    LaunchedEffect(item?.id) {
        val id = item?.id ?: return@LaunchedEffect
        delay(350) // vänta tills man slutat bläddra
        guide = runCatching { api.guide(id) }.getOrNull()
    }
    Column(modifier.background(Palette.Panel, RoundedCornerShape(12.dp)).padding(start = 22.dp, end = 14.dp, top = 22.dp, bottom = 16.dp)) {
        if (item == null) return@Column
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(api.abs(item.icon), item.name, Modifier.width(120.dp).height(68.dp), corner = 8.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    item.num?.let { Text("$it", color = Palette.Dim, fontSize = 16.sp); Spacer(Modifier.width(8.dp)) }
                    LiveBadge()
                }
                Text(item.name, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(14.dp))
        val programs = (guide ?: shortEpg ?: emptyList()).filter { it.end > now }
        if (programs.isEmpty()) {
            Text(t("Ingen programinformation", "No programme information"), color = Palette.Faint, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
        } else {
            // Dagrubriker mellan programmen
            val entries = remember(programs) {
                buildList<Any> {
                    var last: String? = null
                    for (p in programs) {
                        val d = dayLabel(p.start)
                        if (d != last) { add(d); last = d }
                        add(p)
                    }
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(entries) { e ->
                    when (e) {
                        is String -> Text(
                            e.uppercase(I18n.locale), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Faint, letterSpacing = 1.sp,
                            modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 4.dp),
                        )
                        is Program -> GuideRow(e, now, reminders.firstOrNull { it.key == se.synctv.reminderKey(item.id, e.start) }) {
                            if (e.isNow(now)) sync.load("live", item.id, item.ext, item.name, categoryName, item.icon, item.categoryId)
                            else sync.toggleReminder(item, e, categoryName)
                        }
                    }
                }
            }
        }
        if (showHints) {
        Spacer(Modifier.height(8.dp))
        Text(t("→  tablå   ·   OK på ett program  –  påminnelse", "→  guide   ·   OK on a programme  –  reminder"), fontSize = 13.sp, color = Palette.Faint)
        Text(t("OK på kanal  –  titta   ·   Håll inne OK  –  favorit", "OK on channel  –  watch   ·   Hold OK  –  favourite"), fontSize = 13.sp, color = Palette.Faint)
        }
    }
}

@Composable
private fun GuideRow(p: Program, now: Long, reminder: Reminder?, onClick: () -> Unit) {
    val live = p.isNow(now)
    FocusRow(
        onClick = onClick,
        selected = live || reminder != null,
        height = 64.dp,
        modifier = if (reminder != null && !live) Modifier.background(Color(0x1FF5C451), RoundedCornerShape(8.dp)) else Modifier,
    ) {
        Column(Modifier.width(70.dp)) {
            Text(hhmm(p.start), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("– ${hhmm(p.end)}", fontSize = 12.sp, color = Palette.Faint)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) {
                    Text(
                        t("PÅGÅR", "ON NOW"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        modifier = Modifier.background(Palette.Live, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(p.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                live -> ProgressLine(p.progress(now), Modifier.fillMaxWidth().padding(top = 6.dp))
                reminder != null -> Text(
                    t("Påminnelse${reminder.addedBy?.let { " · av $it" } ?: ""}", "Reminder${reminder.addedBy?.let { " · by $it" } ?: ""}"),
                    fontSize = 12.sp, color = Palette.Gold, maxLines = 1,
                )
                p.desc != null -> Text(p.desc, fontSize = 12.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!live) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.Notifications, contentDescription = null,
                tint = if (reminder != null) Palette.Gold else Color(0x55FFFFFF), modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * "Påminnelse: X börjar 21:00 – byter kanal om 23 s". I spelaren styrs det med OK/Tillbaka;
 * i menyn får kortet egna knappar ([interactive]).
 */
@Composable
fun ReminderCard(r: PendingReminder, secondsLeft: Long, interactive: Boolean, onNow: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val first = remember { FocusRequester() }
    if (interactive) LaunchedEffect(r.key) { delay(80); runCatching { first.requestFocus() } }
    Row(
        modifier.width(560.dp).background(Color(0xF21A1F29), RoundedCornerShape(14.dp)).padding(18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(48.dp).background(Color(0x33F5C451), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Notifications, contentDescription = null, tint = Palette.Gold, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t("PÅMINNELSE  ·  BYTER KANAL OM $secondsLeft S", "REMINDER  ·  SWITCHING CHANNEL IN $secondsLeft S"),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Gold, letterSpacing = 1.sp,
            )
            Text(r.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Text(t("Börjar ${hhmm(r.start)} på ${r.channelName}", "Starts ${hhmm(r.start)} on ${r.channelName}"), fontSize = 15.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            if (interactive) {
                Row {
                    Pill(t("Byt nu", "Switch now"), selected = true, onClick = onNow, modifier = Modifier.focusRequester(first))
                    Spacer(Modifier.width(8.dp))
                    Pill(t("Avbryt", "Cancel"), selected = false, onClick = onCancel)
                }
            } else {
                Text(t("OK byt nu   ·   Tillbaka avbryt", "OK switch now   ·   Back cancel"), fontSize = 13.sp, color = Palette.Faint)
            }
        }
    }
}
