package se.synctv

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Katalog-API mot sync-servern (som i sin tur pratar Xtream med Dispatcharr). */
class Api(private val base: String, private val pin: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val itemCache = ConcurrentHashMap<String, Pair<Long, List<Item>>>()
    private val epgCache = ConcurrentHashMap<String, Pair<Long, Map<String, List<Program>>>>()

    /** Gör serverns relativa adresser (t.ex. /img/...) absoluta. */
    fun abs(url: String?): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("/") -> base + url
        else -> url
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(base + path).header("x-pin", pin).build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(body).optString("error") }.getOrNull()
                throw IOException(
                    when {
                        r.code == 401 -> t("Fel PIN-kod", "Wrong PIN")
                        !msg.isNullOrBlank() -> msg
                        else -> t("Serverfel ${r.code}", "Server error ${r.code}")
                    }
                )
            }
            body
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** kind = live | vod | series */
    suspend fun categories(kind: String): List<Category> {
        val a = JSONArray(get("/api/$kind/categories"))
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Category(o.getString("id"), o.getString("name"))
        }
    }

    suspend fun items(kind: String, categoryId: String): List<Item> {
        val key = "$kind/$categoryId"
        itemCache[key]?.let { (t, list) -> if (SystemClock.elapsedRealtime() - t < 5 * 60_000) return list }
        val a = JSONArray(get("/api/$kind/items?category_id=" + enc(categoryId)))
        val list = parseItems(a)
        itemCache[key] = SystemClock.elapsedRealtime() to list
        return list
    }

    private fun parseItems(a: JSONArray): List<Item> = List(a.length()) { i ->
        val o = a.getJSONObject(i)
        Item(
            id = o.getString("id"),
            name = o.optString("name"),
            icon = o.optStringOrNull("icon"),
            ext = o.optString("ext"),
            num = if (o.isNull("num")) null else o.optInt("num"),
            plot = o.optStringOrNull("plot"),
            year = o.optStringOrNull("year"),
            genre = o.optStringOrNull("genre"),
            rating = if (o.isNull("rating")) null else o.optDouble("rating"),
            backdrop = o.optStringOrNull("backdrop"),
            categoryId = o.optStringOrNull("categoryId"),
            categoryName = o.optStringOrNull("categoryName"),
            subtitle = o.optStringOrNull("subtitle"),
            live = o.optBoolean("live"),
            viewers = if (o.isNull("viewers") || !o.has("viewers")) null else o.optInt("viewers"),
        )
    }

    /** Källor från plugins på servern (t.ex. Twitch). */
    suspend fun sources(): List<Source> {
        val a = JSONArray(get("/api/sources"))
        return List(a.length()) { i -> val o = a.getJSONObject(i); Source(o.getString("id"), o.optString("name"), o.optStringOrNull("iconPath")) }
    }

    suspend fun sourceBrowse(id: String): SourceBrowse {
        val o = JSONObject(get("/api/sources/" + enc(id) + "/browse"))
        val a = o.optJSONArray("sections") ?: JSONArray()
        return SourceBrowse(
            message = o.optStringOrNull("message"),
            sections = List(a.length()) { i ->
                val s = a.getJSONObject(i)
                SourceSection(s.optString("id"), s.optString("title"), parseItems(s.optJSONArray("items") ?: JSONArray()))
            },
        )
    }

    /** Rummets gemensamma historik, senaste först. */
    suspend fun history(): List<HistoryEntry> {
        val a = JSONArray(get("/api/history"))
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            HistoryEntry(
                key = o.getString("key"),
                type = o.getString("type"),
                id = o.getString("id"),
                ext = o.optString("ext"),
                title = o.optString("title"),
                subtitle = o.optStringOrNull("subtitle"),
                icon = o.optStringOrNull("icon"),
                categoryId = o.optStringOrNull("categoryId"),
                seriesId = o.optStringOrNull("seriesId"),
                startedBy = o.optStringOrNull("startedBy"),
                startedAt = o.optLong("startedAt"),
                lastAt = o.optLong("lastAt"),
                position = o.optDouble("position", 0.0),
                duration = if (o.isNull("duration")) null else o.optDouble("duration"),
                live = o.optBoolean("live"),
            )
        }
    }

    /** Söker i kanaler, filmer och serier på en gång. */
    suspend fun search(query: String): SearchResult {
        val o = JSONObject(get("/api/search?limit=40&q=" + enc(query)))
        return SearchResult(
            live = parseItems(o.optJSONArray("live") ?: JSONArray()),
            vod = parseItems(o.optJSONArray("vod") ?: JSONArray()),
            series = parseItems(o.optJSONArray("series") ?: JSONArray()),
            sources = (o.optJSONArray("sources") ?: JSONArray()).let { a ->
                List(a.length()) { i -> val s = a.getJSONObject(i); SourceResult(s.optString("id"), s.optString("name"), parseItems(s.optJSONArray("items") ?: JSONArray())) }
            },
        )
    }

    private fun parseEpg(o: JSONObject): Map<String, List<Program>> {
        val out = HashMap<String, List<Program>>()
        for (id in o.keys()) {
            val a = o.getJSONArray(id)
            out[id] = List(a.length()) { i ->
                val p = a.getJSONObject(i)
                Program(p.optString("title"), p.optStringOrNull("desc"), p.optLong("start"), p.optLong("end"))
            }
        }
        return out
    }

    /** EPG (nu/nästa) för alla kanaler i en kategori. */
    suspend fun epg(categoryId: String): Map<String, List<Program>> {
        epgCache[categoryId]?.let { (t, m) -> if (SystemClock.elapsedRealtime() - t < 3 * 60_000) return m }
        val m = parseEpg(JSONObject(get("/api/live/epg?category_id=" + enc(categoryId))))
        epgCache[categoryId] = SystemClock.elapsedRealtime() to m
        return m
    }

    /** Hela tablån för en kanal (pågående och kommande program). */
    suspend fun guide(channelId: String): List<Program> {
        val a = JSONArray(get("/api/live/guide?id=" + enc(channelId)))
        return List(a.length()) { i ->
            val p = a.getJSONObject(i)
            Program(p.optString("title"), p.optStringOrNull("desc"), p.optLong("start"), p.optLong("end"))
        }
    }

    suspend fun epgFor(ids: List<String>): Map<String, List<Program>> =
        parseEpg(JSONObject(get("/api/live/epg?ids=" + enc(ids.joinToString(",")))))

    suspend fun seriesInfo(id: String): SeriesInfo {
        val o = JSONObject(get("/api/series/info?id=" + enc(id)))
        val seasons = o.getJSONArray("seasons")
        return SeriesInfo(
            name = o.optString("name"),
            icon = o.optStringOrNull("icon"),
            plot = o.optStringOrNull("plot"),
            year = o.optStringOrNull("year"),
            genre = o.optStringOrNull("genre"),
            rating = if (o.isNull("rating")) null else o.optDouble("rating"),
            backdrop = o.optStringOrNull("backdrop"),
            seasons = List(seasons.length()) { i ->
                val s = seasons.getJSONObject(i)
                val eps = s.getJSONArray("episodes")
                Season(
                    number = s.getInt("season"),
                    episodes = List(eps.length()) { j ->
                        val e = eps.getJSONObject(j)
                        Episode(e.getString("id"), e.optString("title"), e.optInt("episode"), e.optString("ext"), e.optStringOrNull("icon"))
                    }
                )
            }
        )
    }
}
