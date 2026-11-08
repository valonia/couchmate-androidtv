@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package se.synctv.ui

import se.synctv.t
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import se.synctv.Prefs
import androidx.tv.material3.Text
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import se.synctv.Api
import se.synctv.Item
import se.synctv.Media
import se.synctv.Member
import se.synctv.PlaybackSync
import se.synctv.Program
import se.synctv.RoomState
import se.synctv.SyncClient
import se.synctv.formatTime
import se.synctv.next
import se.synctv.now

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(sync: SyncClient, api: Api, onExit: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .build()
    }
    val controller = remember { PlaybackSync(player, sync) }
    val prefs = remember { Prefs(context) }

    // Ljud/undertext: spåren följs så att panelen uppdateras direkt när man väljer.
    var tracks by remember { mutableStateOf(player.currentTracks) }
    val autoPicked = remember { mutableSetOf<String>() }
    DisposableEffect(player) {
        applyTrackPrefs(player, prefs)
        val listener = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) {
                tracks = t
                // En gång per video: välj undertext enligt det man valde senast.
                val uri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                if (uri != null && uri !in autoPicked && t.groups.any { it.type == C.TRACK_TYPE_TEXT }) {
                    autoPicked += uri
                    autoSelectSubtitle(player, t, prefs)
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val state by sync.state.collectAsState()
    val chatMsgs by sync.chat.collectAsState()
    val members by sync.members.collectAsState()
    val connected by sync.connected.collectAsState()
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        sync.setWatching(true)
        onDispose {
            sync.setWatching(false)
            sync.setChat(false)
            player.release()
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> { controller.active = false; player.pause() }
                Lifecycle.Event.ON_START -> controller.active = true
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Synk-loop (+ positionsrapport varannan sekund)
    LaunchedEffect(Unit) {
        var n = 0
        while (isActive) {
            controller.tick(sync.state.value)
            if (++n % 8 == 0) sync.report(controller.positionSec(), controller.status)
            delay(250)
        }
    }

    // Klocka för UI + när overlayn ska döljas
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var overlayUntil by remember { mutableLongStateOf(SystemClock.elapsedRealtime() + 6000) }
    LaunchedEffect(Unit) {
        while (isActive) { now = SystemClock.elapsedRealtime(); delay(500) }
    }
    fun poke() { overlayUntil = SystemClock.elapsedRealtime() + 6000; now = SystemClock.elapsedRealtime() }
    LaunchedEffect(state?.version) {
        if (state?.action in setOf("load", "play", "pause", "seek")) poke()
    }

    // ---- kanaldata (för live): kanaler i samma grupp + EPG
    val media = state?.media
    var channels by remember { mutableStateOf<List<Item>>(emptyList()) }
    var epg by remember { mutableStateOf<Map<String, List<Program>>>(emptyMap()) }
    // Livestream från en källa (t.ex. Twitch): egen overlay, kvalitet och chatt i stället för kanallista/undertext.
    val stream = media?.type == "plugin"
    var showChat by remember { mutableStateOf(prefs.chatOn) }
    val chatVisible = stream && showChat && media?.chat == true
    LaunchedEffect(chatVisible) { sync.setChat(chatVisible) }
    LaunchedEffect(media?.key) { applyStreamQuality(player, prefs, stream) }
    fun toggleChat() { showChat = !showChat; prefs.chatOn = showChat }

    LaunchedEffect(media?.categoryId, media?.type) {
        val cat = media?.categoryId
        if (media?.type != "live" || cat == null) return@LaunchedEffect
        channels = runCatching { api.items("live", cat) }.getOrDefault(emptyList())
        while (isActive) {
            epg = runCatching { api.epg(cat) }.getOrDefault(epg)
            delay(3 * 60_000)
        }
    }

    // Spolning: samla ihop knapptryck och skicka EN seek efter en kort paus.
    var pendingSeek by remember { mutableStateOf<Double?>(null) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    fun nudge(delta: Double) {
        val m = sync.state.value?.media ?: return
        if (m.isLive) return
        val dur = controller.durationSec()
        val start = pendingSeek ?: controller.positionSec()
        pendingSeek = (start + delta).coerceIn(0.0, if (dur > 0) dur - 1 else Double.MAX_VALUE)
        poke()
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(800)
            pendingSeek?.let { sync.seek(it) }
            delay(600)
            pendingSeek = null
        }
    }

    fun loadChannel(item: Item) {
        val m = sync.state.value?.media ?: return
        sync.load("live", item.id, item.ext, item.name, m.subtitle, item.icon, m.categoryId)
    }

    fun zap(dir: Int) {
        val m = sync.state.value?.media ?: return
        if (m.type != "live" || channels.size < 2) return
        val i = channels.indexOfFirst { it.id == m.id }
        if (i < 0) return
        loadChannel(channels[(i + dir + channels.size) % channels.size])
    }

    // ---- kanallista ovanpå videon
    var panelOpen by remember { mutableStateOf(false) }
    var trackPanel by remember { mutableStateOf(false) }
    var streamPanel by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(panelOpen, trackPanel, streamPanel) {
        if (!panelOpen && !trackPanel && !streamPanel) { delay(50); runCatching { rootFocus.requestFocus() } }
    }

    BackHandler {
        when {
            streamPanel -> streamPanel = false
            trackPanel -> trackPanel = false
            panelOpen -> panelOpen = false
            state?.reminder != null -> sync.reminderCancel() // Tillbaka avbryter påminnelsen för alla
            state?.upNext != null -> sync.nextCancel() // Tillbaka avbryter "nästa avsnitt" för alla
            else -> onExit()
        }
    }

    val st = state
    val live = media?.type == "live" // TV-kanal: kanallista, tablå och kanalbyte
    val anyPanel = panelOpen || trackPanel || streamPanel
    val showOverlay = !anyPanel && (now < overlayUntil || st?.paused == true || pendingSeek != null)
    val chatWidth = if (chatVisible) 400.dp else 0.dp

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { ev ->
                if (anyPanel) return@onKeyEvent false // panelerna sköter sig själva
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                val firstPress = ev.nativeKeyEvent.repeatCount == 0
                // Livestream: OK/▶/▼ = kvalitet & chatt, ◀ = visa/dölj chatten.
                if (stream && sync.state.value?.reminder == null) {
                    when (ev.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.DirectionRight, Key.DirectionDown -> { if (firstPress) streamPanel = true; return@onKeyEvent true }
                        Key.DirectionLeft -> { if (firstPress) { if (media?.chat == true) toggleChat() else streamPanel = true }; return@onKeyEvent true }
                        Key.DirectionUp -> { poke(); return@onKeyEvent true }
                        else -> Unit
                    }
                }
                when (ev.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (firstPress) {
                            when {
                                sync.state.value?.reminder != null -> sync.reminderNow() // OK = byt kanal nu
                                sync.state.value?.upNext != null -> sync.nextNow() // OK = spela nästa avsnitt nu
                                live -> panelOpen = true
                                else -> { sync.toggle(); poke() }
                            }
                        }
                        true
                    }
                    Key.MediaPlayPause -> { if (firstPress) { sync.toggle(); poke() }; true }
                    Key.MediaPlay -> { sync.play(); poke(); true }
                    Key.MediaPause -> { sync.pause(); poke(); true }
                    Key.DirectionLeft -> { if (live) panelOpen = true else nudge(-10.0); true }
                    Key.DirectionRight -> { if (live) trackPanel = true else nudge(10.0); true }
                    Key.MediaRewind -> { nudge(-30.0); true }
                    Key.MediaFastForward -> { nudge(30.0); true }
                    Key.DirectionUp, Key.ChannelUp -> { if (live && firstPress) zap(-1) else poke(); true }
                    Key.DirectionDown -> { if (!firstPress) Unit else if (live) zap(1) else trackPanel = true; true }
                    Key.ChannelDown -> { if (live && firstPress) zap(1); true }
                    Key.Captions -> { trackPanel = true; true }
                    else -> ev.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_INFO && run { poke(); true }
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    keepScreenOn = true
                    isFocusable = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    // Tydliga undertexter: vit text med svart kant.
                    subtitleView?.apply {
                        setApplyEmbeddedStyles(true)
                        setStyle(CaptionStyleCompat(android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null))
                        setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.1f)
                        setBottomPaddingFraction(0.06f)
                    }
                }
            },
            modifier = Modifier.fillMaxSize().padding(end = chatWidth),
        )

        if (chatVisible) {
            ChatPanel(chatMsgs, Modifier.align(Alignment.CenterEnd).width(chatWidth).fillMaxHeight())
        }

        // Status (buffrar/synkar) när overlayn är dold
        if (!showOverlay && !panelOpen && controller.status.isNotEmpty()) {
            StatusChip(controller.status, Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 28.dp + chatWidth))
        }

        AnimatedVisibility(
            visible = showOverlay && st != null && media != null, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.padding(end = chatWidth),
        ) {
            if (st != null && media != null) {
                if (media.type == "plugin") {
                    StreamOverlay(st, media, members, connected, controller.status, api, hasChat = media.chat, chatOn = chatVisible)
                } else if (media.isLive) {
                    LiveOverlay(st, media, epg[media.id], channels.firstOrNull { it.id == media.id }, members, connected, controller.status, api)
                } else {
                    VodOverlay(
                        st, media, members, connected, controller.status,
                        position = pendingSeek ?: controller.positionSec(),
                        duration = controller.durationSec(),
                        seeking = pendingSeek != null,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = panelOpen,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
        ) {
            ChannelPanel(
                title = media?.subtitle ?: t("Kanaler", "Channels"),
                channels = channels,
                epg = epg,
                currentId = media?.id,
                api = api,
                onPick = { loadChannel(it); panelOpen = false },
            )
        }

        AnimatedVisibility(
            visible = trackPanel,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            TrackPanel(
                subs = subtitleOptions(player, tracks, prefs),
                audio = audioOptions(player, tracks, prefs),
                onClose = { trackPanel = false },
            )
        }

        AnimatedVisibility(
            visible = streamPanel,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            StreamPanel(
                quality = qualityOptions(player, tracks, prefs),
                chat = if (media?.chat == true) listOf(
                    TrackOption(t("Visa chatten", "Show chat"), showChat) { if (!showChat) toggleChat() },
                    TrackOption(t("Dölj chatten", "Hide chat"), !showChat) { if (showChat) toggleChat() },
                ) else null,
                onClose = { streamPanel = false },
            )
        }

        // Påminnelse: kanalen byts snart för alla
        val reminder = st?.reminder
        if (reminder != null && !anyPanel) {
            ReminderCard(
                reminder, secondsLeft = ((reminder.switchAt - sync.serverNow() + 999) / 1000).coerceAtLeast(0),
                interactive = false, onNow = { sync.reminderNow() }, onCancel = { sync.reminderCancel() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(48.dp),
            )
        }

        // "Nästa avsnitt" när ett avsnitt tagit slut
        val upNext = st?.upNext
        if (upNext != null && !anyPanel) {
            UpNextCard(upNext, secondsLeft = ((upNext.startsAt - sync.serverNow()) / 1000.0).coerceAtLeast(0.0), api = api,
                modifier = Modifier.align(Alignment.BottomEnd).padding(48.dp))
        }
    }
}

@Composable
private fun UpNextCard(upNext: se.synctv.UpNext, secondsLeft: Double, api: Api, modifier: Modifier = Modifier) {
    Row(
        modifier
            .width(560.dp)
            .background(Color(0xF00A0C10), RoundedCornerShape(14.dp))
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(api.abs(upNext.icon), upNext.title, Modifier.width(150.dp).height(84.dp), fit = false, corner = 8.dp)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(t("NÄSTA AVSNITT", "NEXT EPISODE"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Accent, letterSpacing = 1.5.sp)
            Text(upNext.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            upNext.subtitle?.let { Text(it, fontSize = 15.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(8.dp))
            Text(
                t("Startar om ${secondsLeft.toInt() + 1} s   ·   OK spela nu   ·   Tillbaka avbryt",
                    "Starts in ${secondsLeft.toInt() + 1} s   ·   OK play now   ·   Back cancel"),
                fontSize = 13.sp, color = Palette.Faint,
            )
        }
    }
}

@Composable
private fun StatusChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 15.sp,
        color = Palette.Text,
        modifier = modifier.background(Color(0xCC0A0C10), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun actionText(a: String?) = when (a) {
    "load" -> t("startade", "started")
    "play", "start" -> t("tryckte play", "pressed play")
    "pause" -> t("pausade", "paused")
    "seek" -> t("spolade", "skipped")
    else -> null
}

/** Övre remsan: vem gjorde vad + vilka som tittar + klocka. */
@Composable
private fun TopStrip(st: RoomState, members: List<Member>, connected: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xD9000000), Color.Transparent)))
            .padding(start = 56.dp, end = 56.dp, top = 32.dp, bottom = 60.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val who = st.changedBy
        val what = actionText(st.action)
        if (who != null && what != null) {
            Text(who, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Text(" $what", fontSize = 17.sp, color = Palette.Dim)
        }
        Spacer(Modifier.weight(1f))
        ViewersChip(connected, members.filter { it.watching })
        Spacer(Modifier.width(20.dp))
        Clock(26)
    }
}

/** Overlay för livestreams från källor (t.ex. Twitch): bild, titel, kanal och LIVE. */
@Composable
private fun StreamOverlay(
    st: RoomState,
    media: Media,
    members: List<Member>,
    connected: Boolean,
    status: String,
    api: Api,
    hasChat: Boolean,
    chatOn: Boolean,
) {
    Box(Modifier.fillMaxSize()) {
        TopStrip(st, members, connected)
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF00A0C10))))
                .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = 40.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(api.abs(media.icon), media.subtitle ?: media.title, Modifier.width(150.dp).height(84.dp), corner = 10.dp)
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(media.subtitle ?: media.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(12.dp))
                        LiveBadge()
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(media.title, fontSize = 18.sp, color = Palette.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row {
                if (status.isNotEmpty()) Text(status, fontSize = 14.sp, color = Color(0xFF9FB4FF))
                Spacer(Modifier.weight(1f))
                val chatHint = if (!hasChat) "" else if (chatOn) t("◀  dölj chatt   ·   ", "◀  hide chat   ·   ") else t("◀  visa chatt   ·   ", "◀  show chat   ·   ")
                Text(chatHint + t("OK  kvalitet & chatt   ·   ⏯  paus   ·   Tillbaka  meny", "OK  quality & chat   ·   ⏯  pause   ·   Back  menu"), fontSize = 13.sp, color = Palette.Faint)
            }
        }
    }
}

/** Livechatten till höger om videon. Nyaste längst ner. */
@Composable
private fun ChatPanel(messages: List<se.synctv.ChatMsg>, modifier: Modifier = Modifier) {
    Column(modifier.background(Color(0xFF0E1015)).padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 16.dp)) {
        Text(t("Chatt", "Chat"), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Dim)
        Spacer(Modifier.height(10.dp))
        if (messages.isEmpty()) {
            Text(t("Väntar på meddelanden…", "Waiting for messages…"), fontSize = 14.sp, color = Palette.Faint)
        }
        // Nyaste meddelandet längst ner och alltid synligt.
        val listState = rememberLazyListState()
        LaunchedEffect(messages.lastOrNull()) { listState.scrollToItem(0) }
        LazyColumn(Modifier.weight(1f), state = listState, reverseLayout = true, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val list = messages.asReversed()
            items(list.size) { i ->
                val m = list[i]
                val nameColor = m.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: Palette.Accent
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Bold)) { append(m.user) }
                        append("  ")
                        append(m.text)
                    },
                    fontSize = 15.sp, color = Palette.Text, lineHeight = 20.sp,
                )
            }
        }
    }
}

@Composable
private fun LiveOverlay(
    st: RoomState,
    media: Media,
    epg: List<Program>?,
    channel: Item?,
    members: List<Member>,
    connected: Boolean,
    status: String,
    api: Api,
) {
    val nowS = rememberNowSec(15_000)
    val cur = epg.now(nowS)
    val nxt = epg.next(nowS)
    Box(Modifier.fillMaxSize()) {
        TopStrip(st, members, connected)
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF00A0C10))))
                .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = 40.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(api.abs(media.icon), media.title, Modifier.width(150.dp).height(84.dp), corner = 10.dp)
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        channel?.num?.let { Text("$it", fontSize = 20.sp, color = Palette.Dim); Spacer(Modifier.width(12.dp)) }
                        Text(media.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(12.dp))
                        LiveBadge()
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(cur?.title ?: t("Ingen programinformation", "No programme information"), fontSize = 19.sp, color = if (cur != null) Palette.Text else Palette.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (cur != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(hhmm(cur.start), fontSize = 14.sp, color = Palette.Dim)
                            ProgressLine(cur.progress(nowS), Modifier.weight(1f).padding(horizontal = 12.dp))
                            Text(hhmm(cur.end), fontSize = 14.sp, color = Palette.Dim)
                        }
                    }
                    if (nxt != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(t("Härnäst  ${hhmm(nxt.start)}  ${nxt.title}", "Up next  ${hhmm(nxt.start)}  ${nxt.title}"), fontSize = 15.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row {
                if (status.isNotEmpty()) Text(status, fontSize = 14.sp, color = Color(0xFF9FB4FF))
                Spacer(Modifier.weight(1f))
                Text(t("OK  kanallista   ·   ▲▼  byt kanal   ·   ▶  ljud & undertext   ·   ⏯  paus   ·   Tillbaka  meny", "OK  channel list   ·   ▲▼  change channel   ·   ▶  audio & subtitles   ·   ⏯  pause   ·   Back  menu"), fontSize = 13.sp, color = Palette.Faint)
            }
        }
    }
}

@Composable
private fun VodOverlay(
    st: RoomState,
    media: Media,
    members: List<Member>,
    connected: Boolean,
    status: String,
    position: Double,
    duration: Double,
    seeking: Boolean,
) {
    Box(Modifier.fillMaxSize()) {
        TopStrip(st, members, connected)
        if (st.paused && !st.waiting) {
            Text(
                "❚❚",
                fontSize = 64.sp,
                color = Color(0xE6FFFFFF),
                modifier = Modifier.align(Alignment.Center).background(Color(0x66000000), RoundedCornerShape(50)).padding(horizontal = 34.dp, vertical = 18.dp),
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF00A0C10))))
                .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = 40.dp),
        ) {
            Text(media.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            media.subtitle?.let { Text(it, fontSize = 18.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(18.dp))
            val frac = if (duration > 0) (position / duration).toFloat() else 0f
            Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0x33FFFFFF), RoundedCornerShape(3.dp))) {
                Box(
                    Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(6.dp)
                        .background(if (seeking) Palette.Gold else Palette.Accent, RoundedCornerShape(3.dp))
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(position), fontSize = 16.sp, color = if (seeking) Palette.Gold else Palette.Text)
                if (status.isNotEmpty()) {
                    Spacer(Modifier.width(20.dp))
                    Text(status, fontSize = 14.sp, color = Color(0xFF9FB4FF))
                }
                Spacer(Modifier.weight(1f))
                Text(t("OK  paus   ·   ◀▶  spola 10 s   ·   ▼  ljud & undertext   ·   Tillbaka  meny", "OK  pause   ·   ◀▶  skip 10 s   ·   ▼  audio & subtitles   ·   Back  menu"), fontSize = 13.sp, color = Palette.Faint)
                Spacer(Modifier.width(24.dp))
                Text(if (duration > 0) "-" + formatTime(duration - position) else "", fontSize = 16.sp, color = Palette.Dim)
            }
        }
    }
}

/** Kanallista som glider in från vänster ovanpå videon. */
@Composable
private fun ChannelPanel(
    title: String,
    channels: List<Item>,
    epg: Map<String, List<Program>>,
    currentId: String?,
    api: Api,
    onPick: (Item) -> Unit,
) {
    val nowS = rememberNowSec()
    val listState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    val startIdx = channels.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    LaunchedEffect(channels.size) {
        if (channels.isEmpty()) return@LaunchedEffect
        listState.scrollToItem((startIdx - 3).coerceAtLeast(0))
        delay(80)
        runCatching { currentFocus.requestFocus() }
    }
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(600.dp).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Color(0xF50A0C10), Color(0xE60A0C10))))
                .padding(start = 28.dp, end = 20.dp, top = 28.dp, bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, bottom = 12.dp)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Clock(20)
            }
            if (channels.isEmpty()) {
                Text(t("Laddar kanaler…", "Loading channels…"), color = Palette.Dim, modifier = Modifier.padding(14.dp))
            } else {
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(channels) { idx, item ->
                        ChannelRow(
                            item = item,
                            epg = epg[item.id],
                            nowSec = nowS,
                            playing = item.id == currentId,
                            iconUrl = api.abs(item.icon),
                            onClick = { onPick(item) },
                            modifier = if (idx == startIdx) Modifier.focusRequester(currentFocus) else Modifier,
                        )
                    }
                }
            }
        }
        // Resten av skärmen: svagt mörkad så videon syns
        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0x33000000)))
    }
}
