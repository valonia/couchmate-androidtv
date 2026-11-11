package app.couchmate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** An entry in the menu: built in (favorites, history) or from a plugin. */
data class MenuEntry(val core: String?, val plugin: String?, val id: String, val title: String, val icon: String?) {
    val key get() = if (core != null) "core/$core" else "$plugin/$id"
}

/** Anything a plugin shows: a channel, movie, series, episode, stream … */
data class Item(
    val plugin: String,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    val backdrop: String? = null,
    val description: String? = null,
    val kind: String? = null,
    /** "play" (default) or "open" (the plugin's view [view] with params { id }). */
    val action: String = "play",
    val view: String? = null,
    val live: Boolean = false,
    val number: Int? = null,
    val viewers: Int? = null,
    val year: String? = null,
    val genre: String? = null,
    val rating: Double? = null,
    val badge: String? = null,
    val duration: Double? = null,
    val favorite: Boolean = true,
    /** Shown but cannot be played (e.g. a song that was not found): greyed out and skipped. */
    val unavailable: Boolean = false,
) {
    val key get() = "$plugin:$id"
    val opens get() = action == "open"
}

data class ItemRow(val id: String, val title: String?, val style: String, val items: List<Item>)

data class PluginResult(val plugin: String, val name: String, val sections: List<ItemRow>)

data class Program(val title: String, val description: String?, val start: Long, val end: Long) {
    fun isNow(nowSec: Long) = nowSec in start until end
    fun progress(nowSec: Long) = if (end > start) ((nowSec - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f
}

fun List<Program>?.now(nowSec: Long) = this?.firstOrNull { it.isNow(nowSec) }
fun List<Program>?.next(nowSec: Long) = this?.firstOrNull { it.start > nowSec }
fun nowSec() = System.currentTimeMillis() / 1000

data class HistoryEntry(
    val key: String,
    val plugin: String,
    val id: String,
    val kind: String?,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val live: Boolean,
    val startedBy: String?,
    val lastAt: Long,
    val position: Double,
    val duration: Double?,
) {
    val fraction get() = if (duration != null && duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
    val finished get() = duration != null && duration > 0 && position >= duration * 0.95
    /** Worth resuming: more than a minute in and not nearly finished. */
    val resumable get() = !live && position > 60 && !finished
    fun toItem() = Item(plugin, id, title, subtitle, image, kind = kind, live = live)
}

/** A favourite in the room. [newCount] new episodes since someone last looked, e.g. [newLabel] = "S03E05". */
data class Favorite(
    val key: String,
    val plugin: String,
    val id: String,
    val kind: String?,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val live: Boolean,
    val action: String,
    val view: String?,
    val addedBy: String?,
    val newCount: Int,
    val newLabel: String?,
    /** How many watch right now (live channels, kept up to date by the plugin). */
    val viewers: Int? = null,
    /** The group it shows under on the Favorites screen – named by its plugin (or the usual one for its kind). */
    val group: FavGroup = FavGroup.OTHER,
) {
    fun toItem() = Item(plugin, id, title, subtitle, image, kind = kind, action = action, view = view, live = live, viewers = viewers)
}

/**
 * A group on the Favorites screen. style: "poster" (tall covers), "landscape" (16:9 cards) or "guide"
 * (TV channels: a row with what is on now). Lower [order] comes first.
 */
data class FavGroup(val id: String, val title: String, val order: Double, val style: String) {
    companion object { val OTHER = FavGroup("other", "Other", 99.0, "landscape") }
}

/** "Switch to this channel when the programme starts" – shared by the room. */
data class Reminder(
    val key: String,
    val plugin: String,
    val channel: Item,
    val title: String,
    val desc: String?,
    val start: Long,
    val end: Long,
    val addedBy: String?,
)

fun reminderKey(plugin: String, channelId: String, start: Long) = "$plugin:$channelId:$start"

/**
 * How the player looks and reacts, decided by the plugin.
 * keys: what OK/▲/▼/◀/▶ do – pause, settings, chat, info, channels, channel_up, channel_down, seek_back, seek_forward, none.
 * settings: the boxes in the settings panel (quality, chat, audio, subtitles, choice, actions).
 * channels: the plugin lists channels to zap between. guide: it has programme info for the overlay.
 */
data class PlayerSpec(val keys: Map<String, String>, val settings: List<SpecSection>, val channels: Boolean, val guide: Boolean, val overlay: JSONObject? = null, val queue: Boolean = false)

/** What plays next (the plugin's data('queue')): [current] is the item playing now. */
data class Queue(val title: String?, val current: String?, val items: List<Item>)
data class SpecSection(val id: String, val type: String, val title: String?, val options: List<SpecOption> = emptyList(), val default: String? = null)
data class SpecOption(val id: String, val label: String)

data class Media(
    val plugin: String,
    val id: String,
    val kind: String?,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val live: Boolean,
    val url: String,
    /** "hls" or "file" – the address has no file extension, so the player needs to be told. */
    val type: String,
    /** The video has a separate audio file (same address with part=audio) that plays together with it. */
    val audio: Boolean = false,
    /** The plugin has captions for it (same address with part=captions) – shown when CC is on on this TV. */
    val captions: Boolean = false,
    val chat: Boolean,
    val player: PlayerSpec?,
) {
    val key get() = "$plugin:$id"
    fun toItem() = Item(plugin, id, title, subtitle, image, kind = kind, live = live)
    /** For the plugin's overlay ({{media.title}} …). */
    fun toJson(): JSONObject = JSONObject().put("plugin", plugin).put("id", id).put("kind", kind).put("title", title)
        .put("subtitle", subtitle).put("image", image).put("live", live)
}

/** "Next episode", starting by itself at [startsAt] (server clock, ms). */
data class UpNext(
    val item: Item,
    val startsAt: Long,
    /** Shown before the end (music): the next one follows straight on when this one ends. */
    val early: Boolean = false,
    /** The next one's stream, ready in advance – the player buffers it so the switch is seamless. */
    val media: Media? = null,
)

/** A reminder that is about to switch everyone at [switchAt] (server clock, ms). */
data class PendingReminder(val key: String, val title: String, val start: Long, val channelTitle: String, val channelImage: String?, val switchAt: Long)

data class RoomState(
    val media: Media?,
    val paused: Boolean,
    val waiting: Boolean,
    val position: Double,
    val refTime: Long,
    val version: Int,
    val changedBy: String?,
    val action: String?,
    val upNext: UpNext? = null,
    val reminder: PendingReminder? = null,
    /** Music: what follows, told early (before the card) so the player can start buffering it. */
    val preparedNext: PreparedNext? = null,
)

data class PreparedNext(val item: Item, val media: Media)

data class Member(val id: String, val name: String, val device: String, val watching: Boolean)

/** A chat message from a live stream. */
data class ChatMsg(val user: String, val color: String?, val text: String)

// ---------------------------------------------------------------- JSON

fun JSONObject.optStringOrNull(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

fun JSONObject.optIntOrNull(name: String): Int? = if (!has(name) || isNull(name)) null else optInt(name)
fun JSONObject.optDoubleOrNull(name: String): Double? = if (!has(name) || isNull(name)) null else optDouble(name).takeIf { !it.isNaN() }

inline fun <T> JSONArray?.mapObjects(fn: (JSONObject) -> T?): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) optJSONObject(i)?.let(fn)?.let(out::add)
    return out
}

fun parseItem(o: JSONObject, pluginFallback: String? = null): Item? {
    val plugin = o.optStringOrNull("plugin") ?: pluginFallback ?: return null
    val id = o.optStringOrNull("id") ?: return null
    return Item(
        plugin = plugin,
        id = id,
        title = o.optString("title").ifEmpty { id },
        subtitle = o.optStringOrNull("subtitle"),
        image = o.optStringOrNull("image"),
        backdrop = o.optStringOrNull("backdrop"),
        description = o.optStringOrNull("description"),
        kind = o.optStringOrNull("kind"),
        action = o.optStringOrNull("action") ?: "play",
        view = o.optStringOrNull("view"),
        live = o.optBoolean("live"),
        number = o.optIntOrNull("number"),
        viewers = o.optIntOrNull("viewers"),
        year = o.optStringOrNull("year"),
        genre = o.optStringOrNull("genre"),
        rating = o.optDoubleOrNull("rating"),
        badge = o.optStringOrNull("badge"),
        unavailable = o.optBoolean("unavailable"),
        duration = o.optDoubleOrNull("duration"),
        favorite = o.optBoolean("favorite", true),
    )
}

fun parseRows(a: JSONArray?, plugin: String? = null) = a.mapObjects { r ->
    ItemRow(r.optString("id"), r.optStringOrNull("title"), r.optString("style").ifEmpty { "landscape" }, r.optJSONArray("items").mapObjects { parseItem(it, plugin) })
}

fun parsePrograms(a: JSONArray?) = a.mapObjects { p ->
    Program(p.optString("title"), p.optStringOrNull("description"), p.optLong("start"), p.optLong("end"))
}

// ---------------------------------------------------------------- formatting

fun normalizeUrl(input: String): String {
    var u = input.trim().trimEnd('/')
    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
    return u
}

fun formatTime(sec: Double): String {
    val s = sec.coerceAtLeast(0.0).toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}

// ---------------------------------------------------------------- settings on this TV

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("couchmate", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit().putString("server", v).apply()
    var pin: String
        get() = sp.getString("pin", "") ?: ""
        set(v) = sp.edit().putString("pin", v).apply()
    var name: String
        get() = sp.getString("name", "") ?: ""
        set(v) = sp.edit().putString("name", v).apply()

    /** Chosen subtitle language ("off" = off, "" = the file's default) and audio language – this TV only. */
    var subLang: String
        get() = sp.getString("subLang", "") ?: ""
        set(v) = sp.edit().putString("subLang", v).apply()
    var audioLang: String
        get() = sp.getString("audioLang", "") ?: ""
        set(v) = sp.edit().putString("audioLang", v).apply()
    /** CC: the captions a plugin gives (e.g. YouTube) – on or off for everything, this TV only. */
    var cc: Boolean
        get() = sp.getBoolean("cc", false)
        set(v) = sp.edit().putBoolean("cc", v).apply()

    /** Live streams: show the chat, and the chosen quality (height in pixels, 0 = automatic). */
    var chatOn: Boolean
        get() = sp.getBoolean("chatOn", false)
        set(v) = sp.edit().putBoolean("chatOn", v).apply()
    var streamHeight: Int
        get() = sp.getInt("streamHeight", 0)
        set(v) = sp.edit().putInt("streamHeight", v).apply()

    /** A plugin's own player choices (type "choice"), per plugin and choice. */
    fun pluginOpt(plugin: String, id: String): String? = sp.getString("opt.$plugin.$id", null)
    fun setPluginOpt(plugin: String, id: String, value: String) = sp.edit().putString("opt.$plugin.$id", value).apply()

    /** Language: "auto" (the TV's language) or "en". */
    var language: String
        get() = sp.getString("language", "auto") ?: "auto"
        set(v) = sp.edit().putString("language", v).apply()

    val isConfigured get() = serverUrl.isNotBlank() && name.isNotBlank()
}
