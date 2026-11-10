package app.couchmate

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** What the server says before logging in. */
data class ServerInfo(val name: String, val ready: Boolean)

/**
 * HTTP API of the Couchmate server. Everything plugins show comes through here, already translated
 * into the TV's language by the server.
 */
class Api(private val base: String, private val pin: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val groupCache = ConcurrentHashMap<String, Pair<Long, List<Item>>>()
    private val epgCache = ConcurrentHashMap<String, Pair<Long, Map<String, List<Program>>>>()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Relative server addresses (/plugins/…, /stream/…) become absolute and carry the PIN. */
    fun abs(url: String?): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("/") -> base + url + (if ('?' in url) "&" else "?") + "pin=" + enc(pin)
        else -> url
    }

    private fun langParam(): String = I18n.lang?.let { "lang=" + enc(it) } ?: ""

    private suspend fun call(path: String, body: String? = null, withPin: Boolean = true): String = withContext(Dispatchers.IO) {
        val sep = if ('?' in path) "&" else "?"
        val url = base + path + langParam().let { if (it.isEmpty()) "" else sep + it }
        val b = Request.Builder().url(url)
        if (withPin) b.header("x-pin", pin)
        if (body != null) b.post(body.toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                val o = runCatching { JSONObject(text) }.getOrNull()
                throw IOException(
                    when {
                        o?.optString("code") == "setup" -> tr("The server is not set up yet. Set a PIN code in the admin panel.")
                        r.code == 401 -> tr("Wrong PIN code")
                        !o?.optString("error").isNullOrBlank() -> o!!.optString("error")
                        else -> tr("Server error {code}", "code" to r.code)
                    }
                )
            }
            text
        }
    }

    suspend fun info(): ServerInfo {
        val o = JSONObject(call("/api/info", withPin = false))
        if (o.optString("app") != "Couchmate") throw IOException(tr("This is not a Couchmate server"))
        return ServerInfo(o.optString("name").ifEmpty { APP_NAME }, o.optBoolean("ready"))
    }

    suspend fun auth() { call("/api/auth") }

    suspend fun menu(): List<MenuEntry> = JSONArray(call("/api/menu")).mapObjects { o ->
        MenuEntry(o.optStringOrNull("core"), o.optStringOrNull("plugin"), o.optString("id"), o.optString("title"), o.optStringOrNull("icon"))
    }

    /** A view a plugin built from building blocks. */
    suspend fun view(plugin: String, id: String, params: Map<String, String> = emptyMap()): JSONObject {
        val qs = params.entries.joinToString("") { "&" + enc(it.key) + "=" + enc(it.value) }
        return JSONObject(call("/api/view/${enc(plugin)}/${enc(id)}?_=1$qs"))
    }

    /** Data a view loads (any JSON: an array, an object …). */
    suspend fun data(plugin: String, name: String, params: Map<String, String>): Any? {
        val qs = params.entries.joinToString("") { "&" + enc(it.key) + "=" + enc(it.value) }
        val text = call("/api/data/${enc(plugin)}/${enc(name)}?_=1$qs")
        return org.json.JSONTokener(text).nextValue().takeIf { it != JSONObject.NULL }
    }

    /** The items of a group on a "groups" screen (kept for 5 minutes). */
    suspend fun group(plugin: String, menu: String, group: String): List<Item> {
        val key = "$plugin/$menu/$group/${I18n.lang}"
        groupCache[key]?.let { (t, list) -> if (SystemClock.elapsedRealtime() - t < 5 * 60_000) return list }
        val o = JSONObject(call("/api/data/${enc(plugin)}/group?menu=" + enc(menu) + "&group=" + enc(group)))
        val list = o.optJSONArray("items").mapObjects { parseItem(it, plugin) }
        groupCache[key] = SystemClock.elapsedRealtime() to list
        return list
    }

    /** Now and next for channels (kept for 3 minutes). */
    suspend fun epg(plugin: String, ids: List<String>): Map<String, List<Program>> {
        if (ids.isEmpty()) return emptyMap()
        val key = "$plugin/" + ids.joinToString(",")
        epgCache[key]?.let { (t, m) -> if (SystemClock.elapsedRealtime() - t < 3 * 60_000) return m }
        val o = JSONObject(call("/api/data/${enc(plugin)}/epg?ids=" + enc(ids.take(200).joinToString(","))))
        val out = HashMap<String, List<Program>>()
        for (k in o.keys()) out[k] = parsePrograms(o.optJSONArray(k))
        epgCache[key] = SystemClock.elapsedRealtime() to out
        return out
    }

    /** The whole schedule for one channel. */
    suspend fun guide(plugin: String, id: String): List<Program> =
        parsePrograms(JSONArray(call("/api/data/${enc(plugin)}/guide?id=" + enc(id))))

    /** The channels to zap between while [id] plays. */
    /** Gets an item's stream ready on the server in the background (e.g. the one focused in the queue). */
    suspend fun prepare(plugin: String, id: String) {
        runCatching { call("/api/prepare/${enc(plugin)}?id=" + enc(id)) }
    }

    /** What plays next while [id] plays (the plugin's data('queue')). */
    suspend fun queue(plugin: String, id: String): Queue {
        val o = JSONObject(call("/api/data/${enc(plugin)}/queue?id=" + enc(id)))
        val items = o.optJSONArray("items")?.mapObjects { parseItem(it, plugin) } ?: emptyList()
        return Queue(o.optString("title").ifEmpty { null }, o.optString("current").ifEmpty { null }, items)
    }

    suspend fun channels(plugin: String, id: String): List<Item> =
        JSONArray(call("/api/data/${enc(plugin)}/channels?id=" + enc(id))).mapObjects { parseItem(it, plugin) }

    /** Searches every plugin at once. */
    suspend fun search(query: String): List<PluginResult> = JSONArray(call("/api/search?q=" + enc(query))).mapObjects { o ->
        val plugin = o.optString("plugin")
        PluginResult(plugin, o.optString("name"), parseRows(o.optJSONArray("sections"), plugin))
    }

    /** The room's shared history, newest first. */
    suspend fun history(): List<HistoryEntry> = JSONArray(call("/api/history")).mapObjects { o ->
        HistoryEntry(
            key = o.getString("key"), plugin = o.getString("plugin"), id = o.getString("id"), kind = o.optStringOrNull("kind"),
            title = o.optString("title"), subtitle = o.optStringOrNull("subtitle"), image = o.optStringOrNull("image"),
            live = o.optBoolean("live"), startedBy = o.optStringOrNull("startedBy"), lastAt = o.optLong("lastAt"),
            position = o.optDouble("position", 0.0), duration = o.optDoubleOrNull("duration"),
        )
    }

    /** The app's own texts: { english: translation } and whether everything was translated. */
    suspend fun translate(texts: List<String>, lang: String): Pair<Map<String, String>, Boolean> {
        val body = JSONObject().put("texts", JSONArray(texts)).toString()
        val o = JSONObject(call("/api/i18n?lang=" + enc(lang), body))
        val t = o.optJSONObject("translations") ?: JSONObject()
        return t.keys().asSequence().associateWith { t.getString(it) } to o.optBoolean("complete")
    }
}
