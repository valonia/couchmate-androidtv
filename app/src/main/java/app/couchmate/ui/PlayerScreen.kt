@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package app.couchmate.ui

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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.Text
import app.couchmate.Api
import app.couchmate.ChatMsg
import app.couchmate.I18n
import app.couchmate.Item
import app.couchmate.Media
import app.couchmate.Member
import app.couchmate.PlaybackSync
import app.couchmate.PlayerSpec
import app.couchmate.Queue
import app.couchmate.Prefs
import app.couchmate.Program
import app.couchmate.RoomState
import app.couchmate.SpecSection
import app.couchmate.SyncClient
import app.couchmate.UpNext
import app.couchmate.formatTime
import app.couchmate.next
import app.couchmate.now
import app.couchmate.tr
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The player. Everything plays in sync for the whole room (PlaybackSync). What the remote's buttons do,
 * what is in the settings panel and which overlay shows are decided by the plugin (media.player).
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(sync: SyncClient, api: Api, onExit: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .build()
    }
    val controller = remember { PlaybackSync(player, sync) }
    val prefs = remember { Prefs(context) }

    // Audio/subtitles: the tracks are followed so the panel updates straight away when you choose.
    var tracks by remember { mutableStateOf(player.currentTracks) }
    val autoPicked = remember { mutableSetOf<String>() }
    DisposableEffect(player) {
        applyTrackPrefs(player, prefs)
        val listener = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) {
                tracks = t
                // Once per video: CC when it is on and the video has captions, otherwise the subtitle you chose
                // last time.
                val uri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                if (uri != null && uri !in autoPicked && !t.isEmpty) {
                    autoPicked += uri
                    val m = sync.state.value?.media
                    if (m?.captions == true && prefs.cc) selectCaptions(player, t)
                    else {
                        applyTrackPrefs(player, prefs)
                        if (t.groups.any { it.type == C.TRACK_TYPE_TEXT }) autoSelectSubtitle(player, t, prefs)
                    }
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

    // The sync loop (+ a position report every other second)
    LaunchedEffect(Unit) {
        var n = 0
        while (isActive) {
            controller.tick(sync.state.value)
            if (++n % 8 == 0) sync.report(controller.positionSec(), controller.status)
            delay(250)
        }
    }

    // A clock for the UI + when the overlay hides
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var overlayUntil by remember { mutableLongStateOf(SystemClock.elapsedRealtime() + 6000) }
    LaunchedEffect(Unit) {
        while (isActive) { now = SystemClock.elapsedRealtime(); delay(500) }
    }
    fun poke() { overlayUntil = SystemClock.elapsedRealtime() + 6000; now = SystemClock.elapsedRealtime() }
    LaunchedEffect(state?.version) {
        if (state?.action in setOf("load", "play", "pause", "seek")) poke()
    }

    // ---- what the plugin decided about the player
    val media = state?.media
    val spec = media?.player ?: defaultSpec(media)
    val live = media?.live == true
    var showChat by remember { mutableStateOf(prefs.chatOn) }
    val chatVisible = media?.chat == true && showChat
    LaunchedEffect(chatVisible) { sync.setChat(chatVisible) }
    LaunchedEffect(media?.key) { applyStreamQuality(player, prefs, spec.settings.any { it.type == "quality" }) }
    fun toggleChat() { showChat = !showChat; prefs.chatOn = showChat }
    // The plugin's own choices (type "choice") go with the stream address: ?opt.<id>=<value>.
    var optVersion by remember { mutableStateOf(0) }
    fun optValue(m: Media, s: SpecSection) = prefs.pluginOpt(m.plugin, s.id)?.takeIf { v -> s.options.any { it.id == v } } ?: s.default
    LaunchedEffect(Unit) {
        controller.urlFor = { m ->
            val choices = m.player?.settings?.filter { it.type == "choice" }.orEmpty()
            if (choices.isEmpty()) m.url
            else m.url + (if ('?' in m.url) "&" else "?") + choices.joinToString("&") { s -> "opt.${s.id}=" + java.net.URLEncoder.encode(optValue(m, s) ?: "", "UTF-8") }
        }
        // CC on: the plugin's captions in the app's language (it picks the best it has).
        controller.captionsFor = { m, url ->
            if (m.captions && prefs.cc) url + (if ('?' in url) "&" else "?") + "part=captions&lang=" + (I18n.lang ?: "en") else null
        }
    }
    fun toggleCaptions() {
        prefs.cc = !prefs.cc
        autoPicked.clear() // pick the tracks again when it has loaded
        controller.reloadCaptions()
        optVersion++
    }

    // ---- channels to zap between, and what is on (when the plugin offers them)
    var channels by remember { mutableStateOf<List<Item>>(emptyList()) }
    var epg by remember { mutableStateOf<Map<String, List<Program>>>(emptyMap()) }
    LaunchedEffect(media?.key, spec.channels) {
        val m = media ?: return@LaunchedEffect
        if (!spec.channels) { channels = emptyList(); return@LaunchedEffect }
        // Zapping within the same list keeps it; a channel from elsewhere fetches its own list.
        if (channels.none { it.key == m.key }) channels = runCatching { api.channels(m.plugin, m.id) }.getOrDefault(emptyList())
    }
    LaunchedEffect(media?.key, spec.guide, channels) {
        val m = media ?: return@LaunchedEffect
        if (!spec.guide) return@LaunchedEffect
        val ids = (listOf(m.id) + channels.filter { it.plugin == m.plugin }.map { it.id }).distinct()
        while (isActive) {
            epg = runCatching { api.epg(m.plugin, ids) }.getOrDefault(epg)
            delay(3 * 60_000)
        }
    }

    // Seeking: gather key presses and send ONE seek after a short pause.
    var pendingSeek by remember { mutableStateOf<Double?>(null) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    fun nudge(delta: Double) {
        val m = sync.state.value?.media ?: return
        if (m.live) return
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
        val m = sync.state.value?.media
        sync.load(item.copy(subtitle = item.subtitle ?: m?.subtitle))
    }

    fun zap(dir: Int) {
        val m = sync.state.value?.media ?: return
        if (channels.size < 2) return
        val i = channels.indexOfFirst { it.key == m.key }
        if (i < 0) return
        loadChannel(channels[(i + dir + channels.size) % channels.size])
    }

    // ---- panels on top of the video
    var panelOpen by remember { mutableStateOf(false) }
    var settingsPanel by remember { mutableStateOf(false) }
    // The queue: what plays next (when the plugin has one), in a column to the right – like the chat.
    var queueOpen by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf<Queue?>(null) }
    // What was picked in the queue, until it plays.
    var starting by remember { mutableStateOf<Item?>(null) }
    var startingFrom by remember { mutableStateOf(0) } // the room's version when it was picked
    LaunchedEffect(starting) { if (starting != null) { delay(30_000); starting = null } }
    LaunchedEffect(state?.media?.key, state?.paused, state?.waiting) {
        val s = starting ?: return@LaunchedEffect
        val stNow = state ?: return@LaunchedEffect
        val m = stNow.media
        if (m != null && m.key != s.key && stNow.action == "load" && stNow.version > startingFrom) starting = null // something else was started
        if (m?.key == s.key && !stNow.paused && !stNow.waiting) starting = null
    }
    LaunchedEffect(media?.key, spec.queue) {
        val m = media
        queue = null
        if (m == null || !spec.queue) { queueOpen = false; return@LaunchedEffect }
        queue = runCatching { api.queue(m.plugin, m.id) }.getOrNull()
        if (queue?.items.isNullOrEmpty()) queueOpen = false
    }
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(panelOpen, settingsPanel, queueOpen) {
        if (!panelOpen && !settingsPanel && !queueOpen) { delay(50); runCatching { rootFocus.requestFocus() } }
    }

    BackHandler {
        when {
            settingsPanel -> settingsPanel = false
            panelOpen -> panelOpen = false
            queueOpen -> queueOpen = false
            state?.reminder != null -> sync.reminderCancel() // Back cancels the reminder for everyone
            state?.upNext != null -> sync.nextCancel() // Back cancels "next episode" for everyone
            else -> onExit()
        }
    }

    /** What a key does, as the plugin decided. */
    fun perform(action: String, firstPress: Boolean) {
        when (action) {
            "pause" -> if (firstPress) { sync.toggle(); poke() }
            "settings" -> if (firstPress) settingsPanel = true
            "chat" -> if (firstPress) { if (media?.chat == true) toggleChat() else poke() }
            "channels" -> if (firstPress) { if (channels.isNotEmpty()) panelOpen = true else poke() }
            "channel_up" -> if (firstPress) zap(-1)
            "channel_down" -> if (firstPress) zap(1)
            "seek_back" -> nudge(-10.0)
            "seek_forward" -> nudge(10.0)
            // Nothing queued (e.g. a single video): the overlay instead.
            "queue" -> if (firstPress) {
                // Fetched again on every press – the plugin may have something new (e.g. autoplay switched on).
                if (!queue?.items.isNullOrEmpty()) queueOpen = true
                val m = media
                if (m != null && spec.queue) scope.launch {
                    val fresh = runCatching { api.queue(m.plugin, m.id) }.getOrNull()
                    if (fresh != null && sync.state.value?.media?.key == m.key) queue = fresh
                    if (queue?.items.isNullOrEmpty()) { queueOpen = false; poke() } else queueOpen = true
                } else if (queue?.items.isNullOrEmpty()) poke()
            }
            else -> poke()
        }
    }

    val st = state
    val anyPanel = panelOpen || settingsPanel || queueOpen
    val showOverlay = !anyPanel && (now < overlayUntil || st?.paused == true || pendingSeek != null)
    // The column to the right of the video: the queue while it is open, otherwise the chat.
    val chatWidth = when { queueOpen -> 460.dp; chatVisible -> 400.dp; else -> 0.dp }
    // The video surface does not always redraw when it grows again (the chat hides) – ask it to lay out again.
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    LaunchedEffect(chatWidth) {
        repeat(3) {
            delay(60)
            playerView?.let { v ->
                v.videoSurfaceView?.requestLayout()
                v.requestLayout()
                v.invalidate()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Back in the queue closes it at once. Otherwise Compose uses the first Back to take the focus off the
            // list (the panel stays, nothing is marked) and only the second one closes it.
            .onPreviewKeyEvent { ev ->
                val back = ev.key == Key.Back || ev.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_BACK
                if (!back || !queueOpen || settingsPanel || panelOpen) return@onPreviewKeyEvent false
                if (ev.type == KeyEventType.KeyUp) queueOpen = false
                true
            }
            .onKeyEvent { ev ->
                if (anyPanel) return@onKeyEvent false // the panels handle themselves
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                val firstPress = ev.nativeKeyEvent.repeatCount == 0
                val cur = sync.state.value
                when (ev.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        when {
                            cur?.reminder != null -> if (firstPress) sync.reminderNow() // OK = switch channel now
                            cur?.upNext != null -> if (firstPress) sync.nextNow() // OK = play the next episode now
                            else -> perform(spec.keys["ok"] ?: "pause", firstPress)
                        }
                        true
                    }
                    Key.DirectionUp -> { perform(spec.keys["up"] ?: "info", firstPress); true }
                    Key.DirectionDown -> { perform(spec.keys["down"] ?: "settings", firstPress); true }
                    Key.DirectionLeft -> { perform(spec.keys["left"] ?: "none", firstPress); true }
                    Key.DirectionRight -> { perform(spec.keys["right"] ?: "settings", firstPress); true }
                    Key.MediaPlayPause -> { if (firstPress) { sync.toggle(); poke() }; true }
                    Key.MediaPlay -> { sync.play(); poke(); true }
                    Key.MediaPause -> { sync.pause(); poke(); true }
                    Key.MediaRewind -> { nudge(-30.0); true }
                    Key.MediaFastForward -> { nudge(30.0); true }
                    Key.ChannelUp -> { if (firstPress && spec.channels) zap(-1); true }
                    Key.ChannelDown -> { if (firstPress && spec.channels) zap(1); true }
                    // The remote's CC button: CC on/off where the plugin has captions, else the settings.
                    Key.Captions -> { if (firstPress) { if (cur?.media?.captions == true) { toggleCaptions(); poke() } else settingsPanel = true }; true }
                    else -> ev.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_INFO && run { poke(); true }
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    playerView = this
                    useController = false
                    this.player = player
                    keepScreenOn = true
                    isFocusable = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    // Clear subtitles: white text with a black edge.
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

        val q = queue
        if (queueOpen && q != null) {
            QueuePanel(q, api, Modifier.align(Alignment.CenterEnd).width(chatWidth).fillMaxHeight()) { item ->
                sync.load(item)
                startingFrom = sync.state.value?.version ?: 0
                starting = item
                queueOpen = false
            }
        } else if (chatVisible) {
            ChatPanel(chatMsgs, Modifier.align(Alignment.CenterEnd).width(chatWidth).fillMaxHeight())
        }

        // Picked in the queue: say so right away, until it plays (fetching it can take a few seconds).
        val pick = starting
        if (pick != null && !panelOpen) {
            StatusChip(tr("Starting “{title}”…", "title" to pick.title), Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 28.dp + chatWidth))
        }
        // Status (buffering/syncing) while the overlay is hidden
        if (!showOverlay && !panelOpen && controller.status.isNotEmpty()) {
            if (pick == null) StatusChip(controller.status, Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 28.dp + chatWidth))
        }

        AnimatedVisibility(
            visible = showOverlay && st != null && media != null, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.padding(end = chatWidth),
        ) {
            if (st != null && media != null) {
                val hints = keyHints(spec, media, chatVisible, channels.isNotEmpty(), !queue?.items.isNullOrEmpty())
                val overlay = spec.overlay
                if (overlay != null) {
                    // The plugin's own overlay, bound to {{media}} and {{player}}.
                    val position = pendingSeek ?: controller.positionSec()
                    val duration = controller.durationSec()
                    val playerJson = org.json.JSONObject()
                        .put("position", position).put("duration", duration)
                        .put("positionText", formatTime(position))
                        .put("remainingText", if (duration > 0) "-" + formatTime(duration - position) else "")
                        .put("progress", if (duration > 0) position / duration else 0.0)
                        .put("paused", st.paused && !st.waiting).put("waiting", st.waiting).put("seeking", pendingSeek != null)
                        .put("status", controller.status).put("hints", hints)
                    PluginOverlay(sync, api, media.plugin, overlay, media.toJson(), playerJson) { TopStrip(st, members, connected) }
                } else when {
                    live && spec.guide -> LiveOverlay(st, media, epg[media.id], channels.firstOrNull { it.key == media.key }, members, connected, controller.status, api, hints)
                    live -> StreamOverlay(st, media, members, connected, controller.status, api, hints)
                    else -> VodOverlay(
                        st, media, members, connected, controller.status,
                        position = pendingSeek ?: controller.positionSec(),
                        duration = controller.durationSec(),
                        seeking = pendingSeek != null,
                        hints = hints,
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
                title = media?.subtitle ?: tr("Channels"),
                channels = channels,
                epg = epg,
                currentKey = media?.key,
                api = api,
                onPick = { loadChannel(it); panelOpen = false },
            )
        }

        AnimatedVisibility(
            visible = settingsPanel,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            // The boxes the plugin chose, in its order. Reading optVersion redraws the panel when a choice changes.
            val m = media
            @Suppress("UNUSED_VARIABLE") val version = optVersion
            val sections = run {
                spec.settings.mapNotNull { s ->
                    when (s.type) {
                        "quality" -> qualityOptions(player, tracks, prefs).takeIf { it.size > 1 }?.let { (s.title ?: tr("Quality")) to it }
                        "chat" -> if (m?.chat == true) (s.title ?: tr("Chat")) to listOf(
                            TrackOption(tr("Show the chat"), showChat) { if (!showChat) toggleChat() },
                            TrackOption(tr("Hide the chat"), !showChat) { if (showChat) toggleChat() },
                        ) else null
                        "audio" -> audioOptions(player, tracks, prefs).takeIf { it.size > 1 }?.let { (s.title ?: tr("Audio")) to it }
                        "subtitles" -> subtitleOptions(player, tracks, prefs).takeIf { it.size > 1 }?.let { (s.title ?: tr("Subtitles")) to it }
                        "captions" -> if (m?.captions == true) (s.title ?: "CC") to listOf(
                            TrackOption(tr("Off"), !prefs.cc) { if (prefs.cc) toggleCaptions() },
                            TrackOption(tr("On"), prefs.cc) { if (!prefs.cc) toggleCaptions() },
                        ) else null
                        "choice" -> if (m != null) (s.title ?: s.id) to s.options.map { o ->
                            TrackOption(o.label, optValue(m, s) == o.id) {
                                prefs.setPluginOpt(m.plugin, s.id, o.id)
                                optVersion++
                            }
                        } else null
                        "actions" -> (s.title ?: tr("Actions")) to s.options.map { o ->
                            TrackOption(o.label, false, action = true) { sync.playerAction(o.id); settingsPanel = false }
                        }
                        else -> null
                    }
                }
            }
            SettingsPanel(sections = sections, onClose = { settingsPanel = false })
        }

        // A reminder: the channel switches for everyone soon
        val reminder = st?.reminder
        if (reminder != null && !anyPanel) {
            ReminderCard(
                reminder, secondsLeft = ((reminder.switchAt - sync.serverNow() + 999) / 1000).coerceAtLeast(0),
                interactive = false, onNow = { sync.reminderNow() }, onCancel = { sync.reminderCancel() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(48.dp),
            )
        }

        // "Next episode" when an episode has ended
        val upNext = st?.upNext
        if (upNext != null && !anyPanel) {
            UpNextCard(upNext, secondsLeft = ((upNext.startsAt - sync.serverNow()) / 1000.0).coerceAtLeast(0.0), api = api,
                modifier = Modifier.align(Alignment.BottomEnd).padding(48.dp))
        }
        NoticeToast(sync, Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
    }
}

/** When the plugin sent nothing: OK pause, ◀▶ seek (not live), ▼ settings, ▲ info. */
private fun defaultSpec(media: Media?): PlayerSpec = PlayerSpec(
    keys = mapOf(
        "ok" to "pause", "up" to "info", "down" to "settings",
        "left" to if (media?.live == true) (if (media.chat) "chat" else "none") else "seek_back",
        "right" to if (media?.live == true) "settings" else "seek_forward",
    ),
    settings = listOfNotNull(
        if (media?.chat == true) SpecSection("chat", "chat", null) else null,
        SpecSection("quality", "quality", null), SpecSection("audio", "audio", null), SpecSection("subtitles", "subtitles", null),
    ),
    channels = false, guide = false,
)

/** The help line at the bottom, built from what the plugin's keys do, e.g. "OK  pause/play   ·   ▲▼  change channel". */
private fun keyHints(spec: PlayerSpec, media: Media, chatOn: Boolean, hasChannels: Boolean, hasQueue: Boolean = false): String {
    val glyphs = listOf("ok" to "OK", "up" to "▲", "down" to "▼", "left" to "◀", "right" to "▶")
    val merged = LinkedHashMap<String, MutableList<String>>()
    for ((k, glyph) in glyphs) {
        val label = when (spec.keys[k]) {
            "pause" -> tr("play or pause")
            "settings" -> tr("settings")
            "chat" -> if (media.chat) (if (chatOn) tr("hide chat") else tr("show chat")) else null
            "channels" -> if (hasChannels) tr("channel list") else null
            "channel_up", "channel_down" -> if (hasChannels) tr("change channel") else null
            "seek_back", "seek_forward" -> if (!media.live) tr("skip 10 s") else null
            "queue" -> if (hasQueue) tr("up next") else null
            else -> null
        } ?: continue
        merged.getOrPut(label) { mutableListOf() } += glyph
    }
    return (merged.map { (label, keys) -> "${keys.joinToString("")}  $label" } + (tr("Back") + "  " + tr("menu"))).joinToString("   ·   ")
}

@Composable
private fun UpNextCard(upNext: UpNext, secondsLeft: Double, api: Api, modifier: Modifier = Modifier) {
    Row(
        modifier.width(560.dp).background(Color(0xF00A0C10), RoundedCornerShape(14.dp)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(api.abs(upNext.item.image), upNext.item.title, Modifier.width(150.dp).height(84.dp), fit = false, corner = 8.dp)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(tr("UP NEXT"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Accent, letterSpacing = 1.5.sp)
            Text(upNext.item.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            upNext.item.subtitle?.let { Text(it, fontSize = 15.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(8.dp))
            Text(tr("Starts in {n} s   ·   OK play now   ·   Back cancel", "n" to secondsLeft.toInt() + 1), fontSize = 13.sp, color = Palette.Faint)
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

/** "Bob paused" – who did what, for the top strip. */
private fun actionText(who: String, a: String?) = when (a) {
    "load" -> tr("{name} started the video", "name" to who)
    "play", "start" -> tr("{name} pressed play", "name" to who)
    "pause" -> tr("{name} paused the video", "name" to who)
    "seek" -> tr("{name} skipped forward in the video", "name" to who)
    else -> null
}

/** The top strip: who did what + who is watching + the clock. */
@Composable
private fun TopStrip(st: RoomState, members: List<Member>, connected: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xD9000000), Color.Transparent)))
            .padding(start = 56.dp, end = 56.dp, top = 32.dp, bottom = 60.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        st.changedBy?.let { actionText(it, st.action) }?.let { Text(it, fontSize = 17.sp, color = Palette.Dim) }
        Spacer(Modifier.weight(1f))
        ViewersChip(connected, members.filter { it.watching })
        Spacer(Modifier.width(20.dp))
        Clock(26)
    }
}

/** The bottom part of every overlay: gradient, content, then status + key hints. */
@Composable
private fun BottomPanel(status: String, hints: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF00A0C10))))
            .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = 40.dp),
    ) {
        content()
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (status.isNotEmpty()) Text(status, fontSize = 14.sp, color = Color(0xFF9FB4FF))
            Spacer(Modifier.weight(1f))
            Text(hints, fontSize = 13.sp, color = Palette.Faint)
            trailing()
        }
    }
}

/** The overlay for live streams without a guide: picture, title, channel and LIVE. */
@Composable
private fun StreamOverlay(st: RoomState, media: Media, members: List<Member>, connected: Boolean, status: String, api: Api, hints: String) {
    Box(Modifier.fillMaxSize()) {
        TopStrip(st, members, connected)
        BottomPanel(status, hints, Modifier.align(Alignment.BottomStart)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(api.abs(media.image), media.subtitle ?: media.title, Modifier.width(150.dp).height(84.dp), corner = 10.dp)
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(media.subtitle ?: media.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(12.dp))
                        LiveBadge()
                    }
                    if (media.subtitle != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(media.title, fontSize = 18.sp, color = Palette.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * What plays next, to the right of the video (where the chat goes). The one playing now is marked; focus starts on
 * the one after it. OK plays the chosen one for everyone, Back closes.
 */
@Composable
private fun QueuePanel(queue: Queue, api: Api, modifier: Modifier = Modifier, onPick: (Item) -> Unit) {
    val listState = rememberLazyListState()
    val first = remember { FocusRequester() }
    // The one standing still under the focus for a moment is fetched in advance – it starts quickly if picked.
    var focused by remember { mutableStateOf<Item?>(null) }
    LaunchedEffect(focused) { focused?.let { delay(400); api.prepare(it.plugin, it.id) } }
    val currentIdx = queue.items.indexOfFirst { it.id == queue.current }
    val startIdx = (currentIdx + 1).takeIf { it in queue.items.indices } ?: currentIdx.coerceAtLeast(0)
    LaunchedEffect(queue) {
        listState.scrollToItem((startIdx - 1).coerceAtLeast(0))
        delay(80)
        runCatching { first.requestFocus() }
    }
    Column(modifier.background(Color(0xFF0E1015)).padding(start = 14.dp, end = 14.dp, top = 22.dp, bottom = 16.dp)) {
        Text(tr("Up next"), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Dim, modifier = Modifier.padding(start = 8.dp))
        queue.title?.let {
            Text(it, fontSize = 13.sp, color = Palette.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val keys = uniqueKeys(queue.items)
            itemsIndexed(queue.items, key = { idx, _ -> keys[idx] }) { idx, item ->
                val playing = idx == currentIdx
                FocusRow(
                    // Greyed out (e.g. a song not found on YouTube): shown, never played.
                    onClick = { if (!item.unavailable) onPick(item) },
                    selected = playing,
                    height = 76.dp,
                    onFocus = { if (!playing && !item.unavailable) focused = item },
                    modifier = (if (idx == startIdx) Modifier.focusRequester(first) else Modifier).alpha(if (item.unavailable) 0.4f else 1f),
                ) {
                    Text("${idx + 1}", fontSize = 13.sp, color = Palette.Faint, modifier = Modifier.width(30.dp))
                    Thumb(api.abs(item.image), item.title, Modifier.width(96.dp).height(54.dp), fit = false, corner = 6.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        if (playing) Text(tr("Now playing").uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Palette.Accent)
                        Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = if (playing) 1 else 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                        val sub = listOfNotNull(item.subtitle?.takeIf { it.isNotEmpty() }, item.duration?.takeIf { it > 0 }?.let { formatTime(it) }).joinToString("  ·  ")
                        if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** The live chat to the right of the video. Newest at the bottom. */
@Composable
private fun ChatPanel(messages: List<ChatMsg>, modifier: Modifier = Modifier) {
    Column(modifier.background(Color(0xFF0E1015)).padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 16.dp)) {
        Text(tr("Chat"), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Dim)
        Spacer(Modifier.height(10.dp))
        if (messages.isEmpty()) Text(tr("Waiting for messages…"), fontSize = 14.sp, color = Palette.Faint)
        // The newest message at the bottom and always visible.
        val listState = rememberLazyListState()
        LaunchedEffect(messages.lastOrNull()) { listState.scrollToItem(0) }
        LazyColumn(Modifier.weight(1f), state = listState, reverseLayout = true, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
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

/** The overlay for channels with a programme guide: number, name, what is on now and next. */
@Composable
private fun LiveOverlay(st: RoomState, media: Media, epg: List<Program>?, channel: Item?, members: List<Member>, connected: Boolean, status: String, api: Api, hints: String) {
    val nowS = rememberNowSec(15_000)
    val cur = epg.now(nowS)
    val nxt = epg.next(nowS)
    Box(Modifier.fillMaxSize()) {
        TopStrip(st, members, connected)
        BottomPanel(status, hints, Modifier.align(Alignment.BottomStart)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(api.abs(media.image), media.title, Modifier.width(150.dp).height(84.dp), corner = 10.dp)
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        channel?.number?.let { Text("$it", fontSize = 20.sp, color = Palette.Dim); Spacer(Modifier.width(12.dp)) }
                        Text(media.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(12.dp))
                        LiveBadge()
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(cur?.title ?: tr("No programme information"), fontSize = 19.sp, color = if (cur != null) Palette.Text else Palette.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        Text(tr("Up next") + "  ${hhmm(nxt.start)}  ${nxt.title}", fontSize = 15.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** The overlay for movies and episodes: title, progress, time left. */
@Composable
private fun VodOverlay(st: RoomState, media: Media, members: List<Member>, connected: Boolean, status: String, position: Double, duration: Double, seeking: Boolean, hints: String) {
    Box(Modifier.fillMaxSize()) {
        TopStrip(st, members, connected)
        if (st.paused && !st.waiting) {
            Text(
                "❚❚", fontSize = 64.sp, color = Color(0xE6FFFFFF),
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
                Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(6.dp).background(if (seeking) Palette.Gold else Palette.Accent, RoundedCornerShape(3.dp)))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(position), fontSize = 16.sp, color = if (seeking) Palette.Gold else Palette.Text)
                if (status.isNotEmpty()) {
                    Spacer(Modifier.width(20.dp))
                    Text(status, fontSize = 14.sp, color = Color(0xFF9FB4FF))
                }
                Spacer(Modifier.weight(1f))
                Text(hints, fontSize = 13.sp, color = Palette.Faint)
                Spacer(Modifier.width(24.dp))
                Text(if (duration > 0) "-" + formatTime(duration - position) else "", fontSize = 16.sp, color = Palette.Dim)
            }
        }
    }
}

/** A channel list that slides in from the left on top of the video. */
@Composable
private fun ChannelPanel(title: String, channels: List<Item>, epg: Map<String, List<Program>>, currentKey: String?, api: Api, onPick: (Item) -> Unit) {
    val nowS = rememberNowSec()
    val listState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    val startIdx = channels.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
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
                Text(tr("Loading channels…"), color = Palette.Dim, modifier = Modifier.padding(14.dp))
            } else {
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val keys = uniqueKeys(channels)
                    itemsIndexed(channels, key = { idx, _ -> keys[idx] }) { idx, item ->
                        ChannelRow(
                            item = item,
                            epg = epg[item.id],
                            nowSec = nowS,
                            playing = item.key == currentKey,
                            iconUrl = api.abs(item.image),
                            onClick = { onPick(item) },
                            modifier = if (idx == startIdx) Modifier.focusRequester(currentFocus) else Modifier,
                        )
                    }
                }
            }
        }
        // The rest of the screen: slightly darkened so the video shows
        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0x33000000)))
    }
}
