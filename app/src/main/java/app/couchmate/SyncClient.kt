package app.couchmate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for the room. Keeps the room's state and an estimate of the difference between this
 * device's clock and the server's (NTP-like, the lowest round trip wins).
 */
class SyncClient(
    private val api: Api,
    private val base: String,
    private val pin: String,
    private val name: String,
    private val device: String,
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var offsetMs = 0L
    @Volatile var watching = false
        private set
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private val samples = ArrayDeque<Pair<Long, Long>>() // rtt, offset

    val state = MutableStateFlow<RoomState?>(null)
    val members = MutableStateFlow<List<Member>>(emptyList())
    val connected = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val favorites = MutableStateFlow<List<Favorite>>(emptyList())
    val reminders = MutableStateFlow<List<Reminder>>(emptyList())
    /** Counts up when the server says the menu changed (plugins, order). */
    val menuVersion = MutableStateFlow(0)
    /** Counts up when the history changed (something started, removed, cleared). */
    val historyVersion = MutableStateFlow(0)
    /** Live chat for the stream that plays (only while it is shown). */
    val chat = MutableStateFlow<List<ChatMsg>>(emptyList())
    /** A short message from the server or a plugin (shown as a toast). */
    val notice = MutableStateFlow<Pair<Long, String>?>(null)
    @Volatile private var chatOn = false

    fun serverNow(): Long = System.currentTimeMillis() + offsetMs

    @Synchronized
    fun start() {
        if (running) return
        running = true
        open()
    }

    @Synchronized
    fun stop() {
        running = false
        reconnectJob?.cancel()
        pingJob?.cancel()
        ws?.close(1000, null)
        ws = null
        connected.value = false
    }

    private fun open() {
        val wsUrl = base.replaceFirst(Regex("^http"), "ws") + "/ws?pin=" + URLEncoder.encode(pin, "UTF-8")
        ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!running) return
                connected.value = true
                error.value = null
                synchronized(samples) { samples.clear() }
                webSocket.send(JSONObject().put("type", "hello").put("name", name).put("device", device).put("lang", I18n.lang ?: "en").toString())
                if (watching) webSocket.send(JSONObject().put("type", "watching").put("value", true).toString())
                if (chatOn) webSocket.send(JSONObject().put("type", "chat_sub").put("on", true).toString())
                pingJob?.cancel()
                pingJob = scope.launch {
                    repeat(5) { ping(); delay(300) }
                    while (isActive) { delay(10_000); ping() }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (running) handle(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                lost(webSocket, if (code == 4001) tr("The PIN code has changed – enter the new one in Settings") else null)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(
                webSocket,
                when (response?.code) {
                    401 -> tr("Wrong PIN code")
                    503 -> tr("The server is not set up yet")
                    else -> t.message ?: tr("Connection lost")
                },
            )
        })
    }

    private fun lost(socket: WebSocket, msg: String?) {
        if (socket !== ws) return
        connected.value = false
        pingJob?.cancel()
        if (msg != null) error.value = msg
        if (!running) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(2000)
            synchronized(this@SyncClient) { if (running && ws === socket) open() }
        }
    }

    /** The language changed in Settings: the server sends text in the new language from now on. */
    fun languageChanged() = send(JSONObject().put("type", "lang").put("lang", I18n.lang ?: "en"))

    private fun handle(text: String) {
        val o = try { JSONObject(text) } catch (e: JSONException) { return }
        when (o.optString("type")) {
            "pong" -> {
                val t = o.getLong("t")
                val s = o.getLong("s")
                val now = System.currentTimeMillis()
                val rtt = now - t
                synchronized(samples) {
                    samples.addLast(rtt to (s - (t + rtt / 2)))
                    while (samples.size > 12) samples.removeFirst()
                    offsetMs = samples.minBy { it.first }.second
                }
            }
            "state" -> state.value = parseState(o.getJSONObject("state"))
            "chat" -> {
                val msgs = o.optJSONArray("messages").mapObjects { m -> ChatMsg(m.optString("user"), m.optStringOrNull("color"), m.optString("text")) }
                chat.value = (if (o.optBoolean("reset")) msgs else chat.value + msgs).takeLast(150)
            }
            "favorites" -> favorites.value = o.optJSONArray("favorites").mapObjects { f ->
                Favorite(
                    key = f.getString("key"), plugin = f.getString("plugin"), id = f.getString("id"), kind = f.optStringOrNull("kind"),
                    title = f.optString("title"), subtitle = f.optStringOrNull("subtitle"), image = f.optStringOrNull("image"),
                    live = f.optBoolean("live"), action = f.optStringOrNull("action") ?: "play", addedBy = f.optStringOrNull("addedBy"),
                    newCount = f.optInt("newCount"), newLabel = f.optStringOrNull("newLabel"),
                )
            }
            "reminders" -> reminders.value = o.optJSONArray("reminders").mapObjects { r ->
                val plugin = r.getString("plugin")
                val c = r.getJSONObject("channel")
                Reminder(
                    key = r.getString("key"), plugin = plugin,
                    channel = Item(plugin, c.getString("id"), c.optString("title"), image = c.optStringOrNull("image"), kind = "channel", live = true),
                    title = r.optString("title"), desc = r.optStringOrNull("desc"),
                    start = r.optLong("start"), end = r.optLong("end"), addedBy = r.optStringOrNull("addedBy"),
                )
            }
            "members" -> members.value = o.optJSONArray("members").mapObjects { m ->
                Member(m.optString("id"), m.optString("name"), m.optString("device"), m.optBoolean("watching"))
            }
            "menu_changed" -> menuVersion.value++
            "history_changed" -> historyVersion.value++
            "notice" -> o.optStringOrNull("text")?.let { notice.value = System.currentTimeMillis() to it }
            "error" -> o.optStringOrNull("text")?.let { notice.value = System.currentTimeMillis() to it }
        }
    }

    private fun parsePlayerSpec(o: JSONObject): PlayerSpec {
        val k = o.optJSONObject("keys")
        val keys = listOf("ok", "up", "down", "left", "right").associateWith { k?.optString(it).orEmpty().ifEmpty { "none" } }
        val settings = o.optJSONArray("settings").mapObjects { s ->
            SpecSection(
                id = s.optString("id"), type = s.optString("type"), title = s.optStringOrNull("title"),
                options = s.optJSONArray("options").mapObjects { SpecOption(it.optString("id"), it.optString("label")) },
                default = s.optStringOrNull("default"),
            )
        }
        return PlayerSpec(keys, settings, o.optBoolean("channels"), o.optBoolean("guide"))
    }

    private fun parseState(o: JSONObject): RoomState {
        val media = o.optJSONObject("media")?.let { j ->
            Media(
                plugin = j.getString("plugin"),
                id = j.getString("id"),
                kind = j.optStringOrNull("kind"),
                title = j.optString("title"),
                subtitle = j.optStringOrNull("subtitle"),
                image = j.optStringOrNull("image"),
                live = j.optBoolean("live"),
                url = api.abs(j.getString("url")) ?: j.getString("url"),
                type = j.optString("type").ifEmpty { "file" },
                chat = j.optBoolean("chat"),
                player = j.optJSONObject("player")?.let(::parsePlayerSpec),
            )
        }
        val upNext = o.optJSONObject("upNext")?.let { u -> u.optJSONObject("item")?.let(::parseItem)?.let { UpNext(it, u.optLong("startsAt")) } }
        val reminder = o.optJSONObject("reminder")?.let { r ->
            val c = r.optJSONObject("channel")
            PendingReminder(r.optString("key"), r.optString("title"), r.optLong("start"), c?.optString("title") ?: "", c?.optStringOrNull("image"), r.optLong("switchAt"))
        }
        return RoomState(
            media = media,
            paused = o.getBoolean("paused"),
            waiting = o.optBoolean("waiting"),
            position = o.getDouble("position"),
            refTime = o.getLong("refTime"),
            version = o.getInt("version"),
            changedBy = o.optStringOrNull("changedBy"),
            action = o.optStringOrNull("action"),
            upNext = upNext,
            reminder = reminder,
        )
    }

    private fun send(o: JSONObject) {
        ws?.send(o.toString())
    }

    private fun ping() = send(JSONObject().put("type", "ping").put("t", System.currentTimeMillis()))

    // ------------------------------------------------------------ commands

    /** Plays an item for everyone. [position] = where to start (seconds), e.g. when resuming. */
    fun load(item: Item, position: Double = 0.0) {
        val meta = JSONObject().put("title", item.title).put("subtitle", item.subtitle).put("image", item.image)
            .put("kind", item.kind).put("live", item.live)
        send(JSONObject().put("type", "load").put("plugin", item.plugin).put("id", item.id).put("position", position).put("meta", meta))
    }

    fun play() = send(JSONObject().put("type", "play"))
    fun pause() = send(JSONObject().put("type", "pause"))
    fun toggle() = send(JSONObject().put("type", "toggle"))
    fun stopPlayback() = send(JSONObject().put("type", "stop"))
    fun seek(position: Double) = send(JSONObject().put("type", "seek").put("position", position))
    fun ready(version: Int, duration: Double = 0.0) =
        send(JSONObject().put("type", "ready").put("version", version).put("duration", duration))
    /** Reports the position to the server (for the sync check in the admin panel). */
    fun report(position: Double, status: String) =
        send(JSONObject().put("type", "report").put("position", position).put("status", status).put("at", serverNow()))

    /** A button in the player's settings (the plugin decides what happens). */
    fun playerAction(action: String) = send(JSONObject().put("type", "player_action").put("action", action))

    /** Show the chat (the server only sends chat to those who show it). */
    fun setChat(on: Boolean) {
        if (chatOn == on) return
        chatOn = on
        if (!on) chat.value = emptyList()
        send(JSONObject().put("type", "chat_sub").put("on", on))
    }

    fun setWatching(value: Boolean) {
        watching = value
        send(JSONObject().put("type", "watching").put("value", value))
    }

    // ---- next episode
    fun nextNow() = send(JSONObject().put("type", "next_now"))
    fun nextCancel() = send(JSONObject().put("type", "next_cancel"))

    // ---- history
    fun historyRemove(key: String) = send(JSONObject().put("type", "history_remove").put("key", key))
    fun historyClear() = send(JSONObject().put("type", "history_clear"))

    // ---- favourites (shared by the room)
    fun isFavorite(item: Item) = favorites.value.any { it.key == item.key }
    fun favRemove(key: String) = send(JSONObject().put("type", "fav_remove").put("key", key))
    fun favSeen(key: String) = send(JSONObject().put("type", "fav_seen").put("key", key))

    /** Adds or removes. Returns true if it is a favourite now. */
    fun toggleFavorite(item: Item): Boolean {
        if (!item.favorite) return false
        if (isFavorite(item)) { favRemove(item.key); return false }
        send(JSONObject().put("type", "fav_add").put("item", JSONObject()
            .put("plugin", item.plugin).put("id", item.id).put("title", item.title).put("subtitle", item.subtitle)
            .put("image", item.image).put("kind", item.kind).put("live", item.live).put("action", item.action)))
        return true
    }

    // ---- reminders (shared by the room)
    fun reminderFor(channel: Item, start: Long) = reminders.value.firstOrNull { it.key == reminderKey(channel.plugin, channel.id, start) }

    /** Adds or removes a reminder for a programme. Returns true if it exists now. */
    fun toggleReminder(channel: Item, p: Program): Boolean {
        val key = reminderKey(channel.plugin, channel.id, p.start)
        if (reminders.value.any { it.key == key }) { reminderRemove(key); return false }
        send(JSONObject().put("type", "reminder_add").put("plugin", channel.plugin)
            .put("channel", JSONObject().put("id", channel.id).put("title", channel.title).put("image", channel.image))
            .put("program", JSONObject().put("title", p.title).put("description", p.description).put("start", p.start).put("end", p.end)))
        return true
    }
    fun reminderRemove(key: String) = send(JSONObject().put("type", "reminder_remove").put("key", key))
    fun reminderNow() = send(JSONObject().put("type", "reminder_now"))
    fun reminderCancel() = send(JSONObject().put("type", "reminder_cancel"))
}
