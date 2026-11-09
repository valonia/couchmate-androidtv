@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package app.couchmate.ui

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
import app.couchmate.Api
import app.couchmate.I18n
import app.couchmate.Item
import app.couchmate.PendingReminder
import app.couchmate.Program
import app.couchmate.Reminder
import app.couchmate.SyncClient
import app.couchmate.reminderKey
import app.couchmate.tr
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/** "Today", "Tomorrow" or e.g. "Tuesday 10 November" (in the TV's language). */
fun dayLabel(start: Long): String {
    val d = Calendar.getInstance().apply { timeInMillis = start * 1000 }
    val today = Calendar.getInstance()
    val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        same(d, today) -> tr("Today")
        same(d, tomorrow) -> tr("Tomorrow")
        else -> SimpleDateFormat("EEEE d MMMM", I18n.locale).format(Date(start * 1000))
    }
}

/**
 * The right-hand panel next to a channel list: the channel's schedule (on now and coming up).
 * → on the remote moves into the schedule; OK on a coming programme = a reminder for the whole room.
 */
@Composable
fun ChannelGuide(item: Item?, shortEpg: List<Program>?, now: Long, api: Api, sync: SyncClient, modifier: Modifier = Modifier, showHints: Boolean = true) {
    val reminders by sync.reminders.collectAsState()
    var guide by remember(item?.key) { mutableStateOf<List<Program>?>(null) }
    LaunchedEffect(item?.key) {
        val it = item ?: return@LaunchedEffect
        delay(350) // wait until browsing stops
        guide = runCatching { api.guide(it.plugin, it.id) }.getOrNull()
    }
    Column(modifier.background(Palette.Panel, RoundedCornerShape(12.dp)).padding(start = 22.dp, end = 14.dp, top = 22.dp, bottom = 16.dp)) {
        if (item == null) return@Column
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(api.abs(item.image), item.title, Modifier.width(120.dp).height(68.dp), corner = 8.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    item.number?.let { Text("$it", color = Palette.Dim, fontSize = 16.sp); Spacer(Modifier.width(8.dp)) }
                    LiveBadge()
                }
                Text(item.title, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(14.dp))
        val programs = (guide ?: shortEpg ?: emptyList()).filter { it.end > now }
        if (programs.isEmpty()) {
            Text(tr("No programme information"), color = Palette.Faint, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
        } else {
            // Day headings between the programmes
            val entries = remember(programs, I18n.lang) {
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
                        is Program -> GuideRow(e, now, reminders.firstOrNull { it.key == reminderKey(item.plugin, item.id, e.start) }) {
                            if (e.isNow(now)) sync.load(item) else sync.toggleReminder(item, e)
                        }
                    }
                }
            }
        }
        if (showHints) {
            Spacer(Modifier.height(8.dp))
            Text(tr("→  guide   ·   OK on a programme  –  reminder"), fontSize = 13.sp, color = Palette.Faint)
            Text(tr("OK on a channel  –  play   ·   Hold OK  –  favorite"), fontSize = 13.sp, color = Palette.Faint)
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
                        tr("ON NOW"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        modifier = Modifier.background(Palette.Live, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(p.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                live -> ProgressLine(p.progress(now), Modifier.fillMaxWidth().padding(top = 6.dp))
                reminder != null -> Text(
                    reminder.addedBy?.let { tr("Reminder · by {name}", "name" to it) } ?: tr("Reminder"),
                    fontSize = 12.sp, color = Palette.Gold, maxLines = 1,
                )
                p.description != null -> Text(p.description, fontSize = 12.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
 * "Reminder: X starts 21:00 – switching channel in 23 s". In the player it is controlled with OK/Back;
 * in the menu the card has its own buttons ([interactive]).
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
                tr("REMINDER  ·  SWITCHING CHANNEL IN {n} S", "n" to secondsLeft),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Gold, letterSpacing = 1.sp,
            )
            Text(r.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Text(tr("Starts {time} on {channel}", "time" to hhmm(r.start), "channel" to r.channelTitle), fontSize = 15.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            if (interactive) {
                Row {
                    Pill(tr("Switch now"), selected = true, onClick = onNow, modifier = Modifier.focusRequester(first))
                    Spacer(Modifier.width(8.dp))
                    Pill(tr("Cancel"), selected = false, onClick = onCancel)
                }
            } else {
                Text(tr("OK switch now   ·   Back cancel"), fontSize = 13.sp, color = Palette.Faint)
            }
        }
    }
}
