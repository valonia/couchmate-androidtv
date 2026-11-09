@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package app.couchmate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.couchmate.Api
import app.couchmate.Member
import app.couchmate.MenuEntry
import app.couchmate.SyncClient
import app.couchmate.tr
import kotlinx.coroutines.delay

@Composable
fun BrowseScreen(sync: SyncClient, api: Api, prefs: app.couchmate.Prefs, onOpenPlayer: () -> Unit, onReconnect: () -> Unit) {
    val state by sync.state.collectAsState()
    val members by sync.members.collectAsState()
    val connected by sync.connected.collectAsState()
    val syncError by sync.error.collectAsState()
    val favorites by sync.favorites.collectAsState()
    val newEpisodes = favorites.sumOf { it.newCount }

    // The menu comes from the server: fetched straight away when a plugin or the order changes, otherwise every minute.
    var menu by remember { mutableStateOf<List<MenuEntry>?>(null) }
    var menuError by remember { mutableStateOf<String?>(null) }
    val menuVersion by sync.menuVersion.collectAsState()
    LaunchedEffect(menuVersion, connected) {
        while (true) {
            runCatching { api.menu() }.onSuccess { menu = it; menuError = null }.onFailure { if (menu == null) menuError = it.message }
            delay(60_000)
        }
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // Start on the first entry; if the chosen one disappears (plugin switched off, nothing to show), go to the first.
    LaunchedEffect(menu) {
        val m = menu ?: return@LaunchedEffect
        if (selected == null || (selected != SEARCH_KEY && m.none { it.key == selected })) selected = m.firstOrNull()?.key ?: SEARCH_KEY
    }

    val firstFocus = remember { FocusRequester() }
    // Which list had focus (so you land there again after the player). null = the menu.
    val focusMemory = LocalFocusMemory.current ?: remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { delay(100); if (focusMemory.value == null) runCatching { firstFocus.requestFocus() } }

    var showMembers by remember { mutableStateOf(false) }
    BackHandler(enabled = showMembers) { showMembers = false }
    // Settings open as a popup on top of the menu (the app stays connected).
    var showSettings by remember { mutableStateOf(false) }

    // Back in the content: one step closer to the menu (e.g. guide → channels → groups → menu).
    // In the menu itself the app closes (the system's normal Back).
    var railFocused by remember { mutableStateOf(false) }
    val rootView = LocalView.current
    BackHandler(enabled = !railFocused && !showMembers && !showSettings) {
        // The same as pressing left on the remote; if that does nothing, jump straight to the menu.
        val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        val handled = rootView.dispatchKeyEvent(down)
        rootView.dispatchKeyEvent(up)
        if (!handled) runCatching { firstFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().background(Palette.Bg)) {
            SideNav(
                entries = menu.orEmpty(),
                selectedKey = selected ?: "",
                onSelect = { selected = it },
                newEpisodes = newEpisodes,
                media = state?.media,
                onOpenPlayer = onOpenPlayer,
                onStop = { sync.stopPlayback() },
                onSettings = { showSettings = true },
                connected = connected,
                members = members,
                error = syncError,
                onMembers = { showMembers = true },
                selectedFocus = firstFocus,
                onRailFocus = { railFocused = it; if (it) focusMemory.value = null },
            )

            Box(Modifier.weight(1f).fillMaxHeight().padding(start = 20.dp, end = 24.dp, top = 22.dp, bottom = 20.dp)) {
                val sel = selected
                val entry = menu?.firstOrNull { it.key == sel }
                when {
                    sel == SEARCH_KEY -> SearchScreen(sync, api)
                    entry?.core == "favorites" -> FavoritesScreen(sync, api)
                    entry?.core == "history" -> HistoryScreen(sync, api)
                    // A plugin's menu entry: its own view, built by the plugin (open → more views, Back → back).
                    entry?.plugin != null -> key(entry.key) { ViewHost(sync, api, ViewRef(entry.plugin, entry.id)) }
                    menu == null && menuError != null -> Message(tr("Error: {message}", "message" to menuError), error = true)
                    menu == null -> Message(tr("Loading…"))
                    menu?.none { it.plugin != null } == true -> Message(tr("Nothing to watch yet. Switch on a plugin in the admin panel on the server."))
                }
            }
        }
        if (showMembers) MembersPanel(members, state?.media?.title)

        // A reminder: the channel switches for everyone soon
        val reminder = state?.reminder
        if (reminder != null && !showMembers) {
            val tick = rememberTicker(reminder.key)
            val left = remember(tick, reminder.switchAt) { ((reminder.switchAt - sync.serverNow() + 999) / 1000).coerceAtLeast(0) }
            ReminderCard(
                reminder, secondsLeft = left,
                interactive = true, onNow = { sync.reminderNow() }, onCancel = { sync.reminderCancel() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(36.dp),
            )
        }
        NoticeToast(sync, Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
        if (showSettings) {
            SettingsDialog(
                prefs,
                onClose = { showSettings = false; runCatching { firstFocus.requestFocus() } },
                onReconnect = { showSettings = false; onReconnect() },
                // The language changed: tell the server and reload the menu, both come translated.
                onLanguage = { sync.languageChanged(); sync.menuVersion.value++ },
            )
        }
    }
}

/** A short message from the server or a plugin, shown for a few seconds. */
@Composable
fun NoticeToast(sync: SyncClient, modifier: Modifier = Modifier) {
    val notice by sync.notice.collectAsState()
    var visible by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        if (System.currentTimeMillis() - n.first > 10_000) return@LaunchedEffect
        visible = n.second
        delay(5000)
        visible = null
    }
    visible?.let {
        Text(
            it, fontSize = 16.sp, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = modifier.background(Color(0xF21A1F29), RoundedCornerShape(50)).padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }
}

/** A value that changes every half second while [key] is active (for countdowns). */
@Composable
fun rememberTicker(key: Any?): Long {
    var n by remember(key) { mutableStateOf(0L) }
    LaunchedEffect(key) { while (true) { delay(500); n++ } }
    return n
}

/** Everyone who is connected – opened from the chip at the bottom of the menu. */
@Composable
private fun MembersPanel(members: List<Member>, playing: String?) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    val sorted = members.sortedWith(compareByDescending<Member> { it.watching }.thenBy { it.name.lowercase() })
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0x99000000)))
        Column(Modifier.width(460.dp).fillMaxHeight().background(Palette.Panel).padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 24.dp)) {
            Text(tr("{n} connected", "n" to members.size), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                if (playing != null) tr("Watching {title}", "title" to playing) else tr("Nothing is playing right now"),
                fontSize = 14.sp, color = Palette.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(sorted) { idx, m ->
                    FocusRow(onClick = {}, height = 60.dp, modifier = if (idx == 0) Modifier.focusRequester(first) else Modifier) {
                        Avatar(m.name, 34.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (m.device.isNotBlank()) Text(m.device, fontSize = 13.sp, color = Color(0x99FFFFFF), maxLines = 1)
                        }
                        Text(
                            if (m.watching) tr("Watching") else tr("In the menu"),
                            fontSize = 13.sp,
                            color = if (m.watching) Color(0xFF3DD68C) else Color(0x99FFFFFF),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(tr("Back  –  close"), fontSize = 13.sp, color = Palette.Faint)
        }
    }
}
