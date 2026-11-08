package se.synctv

import android.content.Context
import org.json.JSONObject

data class Category(val id: String, val name: String)

data class Item(
    val id: String,
    val name: String,
    val icon: String?,
    val ext: String,
    val num: Int? = null,
    val plot: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val rating: Double? = null,
    val backdrop: String? = null,
    val categoryId: String? = null,
    val categoryName: String? = null,
    // Från plugin-källor (t.ex. Twitch)
    val subtitle: String? = null,
    val live: Boolean = false,
    val viewers: Int? = null,
)

/** En källa från ett plugin på servern (t.ex. Twitch) – visas i menyn mellan Favoriter och Historik. */
data class Source(val id: String, val name: String, val iconPath: String?)

data class SourceSection(val id: String, val title: String, val items: List<Item>)
data class SourceBrowse(val message: String?, val sections: List<SourceSection>)
data class SourceResult(val id: String, val name: String, val items: List<Item>)

data class SearchResult(val live: List<Item>, val vod: List<Item>, val series: List<Item>, val sources: List<SourceResult> = emptyList()) {
    val isEmpty get() = live.isEmpty() && vod.isEmpty() && series.isEmpty() && sources.all { it.items.isEmpty() }
}

data class HistoryEntry(
    val key: String,
    val type: String,
    val id: String,
    val ext: String,
    val title: String,
    val subtitle: String?,
    val icon: String?,
    val categoryId: String?,
    val seriesId: String? = null,
    val startedBy: String?,
    val startedAt: Long,
    val lastAt: Long,
    val position: Double,
    val duration: Double?,
    val live: Boolean = false,
) {
    val isLive get() = type == "live" || live
    val fraction get() = if (duration != null && duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
    val finished get() = duration != null && duration > 0 && position >= duration * 0.95
    /** Värt att fortsätta från: mer än 1 min in och inte nästan slut. */
    val resumable get() = !isLive && position > 60 && !finished
}

data class Program(val title: String, val desc: String?, val start: Long, val end: Long) {
    fun isNow(nowSec: Long) = nowSec in start until end
    fun progress(nowSec: Long) = if (end > start) ((nowSec - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f
}

fun List<Program>?.now(nowSec: Long) = this?.firstOrNull { it.isNow(nowSec) }
fun List<Program>?.next(nowSec: Long) = this?.firstOrNull { it.start > nowSec }

fun nowSec() = System.currentTimeMillis() / 1000

data class Episode(val id: String, val title: String, val episode: Int, val ext: String, val icon: String?)

data class Season(val number: Int, val episodes: List<Episode>)

data class SeriesInfo(
    val name: String,
    val icon: String?,
    val seasons: List<Season>,
    val plot: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val rating: Double? = null,
    val backdrop: String? = null,
)

data class Media(
    val type: String,
    val id: String,
    val ext: String,
    val title: String,
    val subtitle: String?,
    val icon: String?,
    val categoryId: String?,
    val url: String,
    val seriesId: String? = null,
    val live: Boolean = false,
    /** Källan har livechatt (t.ex. Twitch). */
    val chat: Boolean = false,
    /** Plugin-källor: pluginet bestämmer spelarens knappar och inställningar. */
    val player: PlayerSpec? = null,
) {
    /** Källa och objekt för plugin-media ("twitch:lofigirl" → "twitch", "lofigirl"). */
    val sourceId get() = if (type == "plugin") id.substringBefore(":") else null
    val key get() = "$type:$id"
    /** TV-kanaler och liveströmmar från plugin-källor. */
    val isLive get() = type == "live" || live
}

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
)

/** En påminnelse som snart byter kanal för alla (vid [switchAt], serverns klocka i ms). */
data class PendingReminder(val key: String, val title: String, val start: Long, val channelName: String, val channelIcon: String?, val switchAt: Long)

/** "Byt till den här kanalen när programmet börjar" – gemensam för rummet. */
data class Reminder(
    val key: String,
    val channel: Item,
    val title: String,
    val desc: String?,
    val start: Long,
    val end: Long,
    val addedBy: String?,
)

fun reminderKey(channelId: String, start: Long) = "$channelId:$start"

/** "Nästa avsnitt" som startar automatiskt vid [startsAt] (serverns klocka, ms). */
data class UpNext(val title: String, val subtitle: String?, val icon: String?, val startsAt: Long)

data class Member(val id: String, val name: String, val device: String, val watching: Boolean)

/** En favorit i rummet. För serier: [newCount] nya avsnitt sedan någon tittade senast, t.ex. [newLabel] = "S03E05". */
data class Favorite(
    val key: String,
    val type: String, // series | movie | live
    val id: String,
    val name: String,
    val icon: String?,
    val ext: String,
    val categoryId: String?,
    val categoryName: String?,
    val addedBy: String?,
    val newCount: Int,
    val newLabel: String?,
)

fun favKey(type: String, id: String) = "$type:$id"

fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

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

/**
 * Spelarens utseende för en plugin-källa, bestämt av pluginet:
 * keys = vad OK/▲/▼/◀/▶ gör ("pause", "settings", "chat", "info", "none"),
 * settings = rutorna i inställningspanelen (quality, chat, audio, subtitles, choice, actions).
 */
data class PlayerSpec(val keys: Map<String, String>, val settings: List<SpecSection>)
data class SpecSection(val id: String, val type: String, val title: String?, val options: List<SpecOption> = emptyList(), val default: String? = null)
data class SpecOption(val id: String, val label: String)

/** Ett chattmeddelande från en livestream (t.ex. Twitch). */
data class ChatMsg(val user: String, val color: String?, val text: String)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("synctv", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit().putString("server", v).apply()
    var pin: String
        get() = sp.getString("pin", "") ?: ""
        set(v) = sp.edit().putString("pin", v).apply()
    var name: String
        get() = sp.getString("name", "") ?: ""
        set(v) = sp.edit().putString("name", v).apply()

    /** Valt undertextspråk ("off" = av, "" = standard) och ljudspråk – gäller bara den här TV:n. */
    var subLang: String
        get() = sp.getString("subLang", "") ?: ""
        set(v) = sp.edit().putString("subLang", v).apply()
    var audioLang: String
        get() = sp.getString("audioLang", "") ?: ""
        set(v) = sp.edit().putString("audioLang", v).apply()

    /** Livestreams (t.ex. Twitch): visa chatten, och vald kvalitet (höjd i pixlar, 0 = automatisk). */
    var chatOn: Boolean
        get() = sp.getBoolean("chatOn", false)
        set(v) = sp.edit().putBoolean("chatOn", v).apply()
    var streamHeight: Int
        get() = sp.getInt("streamHeight", 0)
        set(v) = sp.edit().putInt("streamHeight", v).apply()

    /** Egna val i en källas inställningar (type "choice"), per källa och val. */
    fun sourceOpt(source: String, id: String): String? = sp.getString("opt.$source.$id", null)
    fun setSourceOpt(source: String, id: String, value: String) = sp.edit().putString("opt.$source.$id", value).apply()

    /** Språk: "auto" (följer TV:n), "sv" eller "en". */
    var language: String
        get() = sp.getString("language", "auto") ?: "auto"
        set(v) = sp.edit().putString("language", v).apply()

    val isConfigured get() = serverUrl.isNotBlank() && name.isNotBlank()
}
