package se.synctv

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
 * WebSocket-klient mot sync-servern. Håller rummets tillstånd och en uppskattning av
 * skillnaden mellan enhetens klocka och serverns (NTP-liknande, lägsta RTT vinner).
 */
class SyncClient(
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
                webSocket.send(JSONObject().put("type", "hello").put("name", name).put("device", device).toString())
                if (watching) webSocket.send(JSONObject().put("type", "watching").put("value", true).toString())
                pingJob?.cancel()
                pingJob = scope.launch {
                    repeat(5) { ping(); delay(300) }
                    while (isActive) { delay(10_000); ping() }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (running) handle(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, null)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                lost(webSocket, if (response?.code == 401) t("Fel PIN-kod", "Wrong PIN") else (t.message ?: t("Anslutningen bröts", "Connection lost")))
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
            "favorites" -> {
                val a = o.getJSONArray("favorites")
                favorites.value = List(a.length()) { i ->
                    val f = a.getJSONObject(i)
                    Favorite(
                        key = f.getString("key"), type = f.getString("type"), id = f.getString("id"),
                        name = f.optString("name"), icon = f.optStringOrNull("icon"), ext = f.optString("ext"),
                        categoryId = f.optStringOrNull("categoryId"), categoryName = f.optStringOrNull("categoryName"),
                        addedBy = f.optStringOrNull("addedBy"), newCount = f.optInt("newCount"), newLabel = f.optStringOrNull("newLabel"),
                    )
                }
            }
            "reminders" -> {
                val a = o.getJSONArray("reminders")
                reminders.value = List(a.length()) { i ->
                    val r = a.getJSONObject(i)
                    val c = r.getJSONObject("channel")
                    Reminder(
                        key = r.getString("key"),
                        channel = Item(
                            id = c.getString("id"), name = c.optString("name"), icon = c.optStringOrNull("icon"), ext = c.optString("ext"),
                            categoryId = c.optStringOrNull("categoryId"), categoryName = c.optStringOrNull("categoryName"),
                        ),
                        title = r.optString("title"), desc = r.optStringOrNull("desc"),
                        start = r.optLong("start"), end = r.optLong("end"), addedBy = r.optStringOrNull("addedBy"),
                    )
                }
            }
            "members" -> {
                val a = o.getJSONArray("members")
                members.value = List(a.length()) { i ->
                    val m = a.getJSONObject(i)
                    Member(m.optString("id"), m.optString("name"), m.optString("device"), m.optBoolean("watching"))
                }
            }
        }
    }

    private fun parseState(o: JSONObject): RoomState {
        val media = o.optJSONObject("media")?.let { j ->
            val url = j.getString("url").let { if (it.startsWith("/")) base + it else it }
            Media(
                type = j.getString("type"),
                id = j.getString("id"),
                ext = j.optString("ext"),
                title = j.optString("title"),
                subtitle = j.optStringOrNull("subtitle"),
                icon = j.optStringOrNull("icon"),
                categoryId = j.optStringOrNull("categoryId"),
                url = url,
                seriesId = j.optStringOrNull("seriesId"),
            )
        }
        val upNext = o.optJSONObject("upNext")?.let { u ->
            val m = u.optJSONObject("media")
            UpNext(m?.optString("title") ?: "", m?.optStringOrNull("subtitle"), m?.optStringOrNull("icon"), u.optLong("startsAt"))
        }
        val reminder = o.optJSONObject("reminder")?.let { r ->
            val c = r.optJSONObject("channel")
            PendingReminder(r.optString("key"), r.optString("title"), r.optLong("start"), c?.optString("name") ?: "", c?.optStringOrNull("icon"), r.optLong("switchAt"))
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

    // ------------------------------------------------------------ kommandon

    fun setWatching(value: Boolean) {
        watching = value
        send(JSONObject().put("type", "watching").put("value", value))
    }

    fun load(
        type: String, id: String, ext: String, title: String, subtitle: String?, icon: String?, categoryId: String?,
        position: Double = 0.0,
        seriesId: String? = null,
    ) {
        val media = JSONObject()
            .put("type", type).put("id", id).put("ext", ext).put("title", title)
            .put("subtitle", subtitle).put("icon", icon).put("categoryId", categoryId).put("seriesId", seriesId)
        send(JSONObject().put("type", "load").put("media", media).put("position", position))
    }

    fun play() = send(JSONObject().put("type", "play"))
    fun pause() = send(JSONObject().put("type", "pause"))
    fun toggle() = send(JSONObject().put("type", "toggle"))
    fun stopPlayback() = send(JSONObject().put("type", "stop"))
    fun seek(position: Double) = send(JSONObject().put("type", "seek").put("position", position))
    fun ready(version: Int, duration: Double = 0.0) =
        send(JSONObject().put("type", "ready").put("version", version).put("duration", duration))
    fun historyRemove(key: String) = send(JSONObject().put("type", "history_remove").put("key", key))
    /** Rapporterar position till servern (för synk-kontroll/felsökning). */
    fun report(position: Double, status: String) =
        send(JSONObject().put("type", "report").put("position", position).put("status", status).put("at", serverNow()))
    /** Nästa avsnitt: spela direkt / avbryt. */
    // ---- favoriter (gemensamma för rummet)
    fun favAdd(type: String, id: String, name: String, icon: String?, ext: String, categoryId: String?, categoryName: String?) =
        send(JSONObject().put("type", "fav_add").put("item", JSONObject()
            .put("type", type).put("id", id).put("name", name).put("icon", icon).put("ext", ext)
            .put("categoryId", categoryId).put("categoryName", categoryName)))
    fun favRemove(key: String) = send(JSONObject().put("type", "fav_remove").put("key", key))
    fun favSeen(key: String) = send(JSONObject().put("type", "fav_seen").put("key", key))

    /** Lägg till eller ta bort. Returnerar true om den nu är favorit. */
    fun toggleFavorite(type: String, item: Item, categoryName: String?): Boolean {
        val key = favKey(type, item.id)
        return if (favorites.value.any { it.key == key }) {
            favRemove(key); false
        } else {
            favAdd(type, item.id, item.name, item.icon, item.ext, item.categoryId, categoryName ?: item.categoryName); true
        }
    }

    // ---- påminnelser (gemensamma för rummet)
    fun reminderFor(channelId: String, start: Long) = reminders.value.firstOrNull { it.key == reminderKey(channelId, start) }

    /** Lägg till eller ta bort en påminnelse för ett program. Returnerar true om den nu finns. */
    fun toggleReminder(channel: Item, p: Program, categoryName: String?): Boolean {
        val key = reminderKey(channel.id, p.start)
        if (reminders.value.any { it.key == key }) {
            reminderRemove(key)
            return false
        }
        send(JSONObject().put("type", "reminder_add")
            .put("channel", JSONObject()
                .put("id", channel.id).put("name", channel.name).put("icon", channel.icon).put("ext", channel.ext.ifBlank { "ts" })
                .put("categoryId", channel.categoryId).put("categoryName", categoryName ?: channel.categoryName))
            .put("program", JSONObject().put("title", p.title).put("desc", p.desc).put("start", p.start).put("end", p.end)))
        return true
    }
    fun reminderRemove(key: String) = send(JSONObject().put("type", "reminder_remove").put("key", key))
    fun reminderNow() = send(JSONObject().put("type", "reminder_now"))
    fun reminderCancel() = send(JSONObject().put("type", "reminder_cancel"))

    fun nextNow() = send(JSONObject().put("type", "next_now"))
    fun nextCancel() = send(JSONObject().put("type", "next_cancel"))
    fun historyClear() = send(JSONObject().put("type", "history_clear"))
}
