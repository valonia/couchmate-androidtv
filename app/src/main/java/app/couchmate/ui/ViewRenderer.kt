@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package app.couchmate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.couchmate.Api
import app.couchmate.Favorite
import app.couchmate.I18n
import app.couchmate.Item
import app.couchmate.Program
import app.couchmate.Reminder
import app.couchmate.SyncClient
import app.couchmate.parseItem
import app.couchmate.reminderKey
import app.couchmate.tr
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

// =====================================================================================================
// Plugin views. A plugin describes a whole screen with building blocks (rows, columns, text, images,
// lists …), bindings ("{{item.title}}", "{{state.channel}}") and actions (play, open, set …).
// This file is the only place that knows how to draw them – see server/plugins/PLUGINS.md → Views.
// =====================================================================================================

/** A plugin view to show: which plugin, which view and its parameters. */
data class ViewRef(val plugin: String, val id: String, val params: Map<String, String> = emptyMap()) {
    val key get() = "$plugin/$id?" + params.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
    fun encode(): String = JSONObject().put("plugin", plugin).put("id", id).put("params", JSONObject(params)).toString()

    companion object {
        fun decode(s: String): ViewRef = JSONObject(s).let { o ->
            val p = o.optJSONObject("params") ?: JSONObject()
            ViewRef(o.getString("plugin"), o.getString("id"), p.keys().asSequence().associateWith { p.getString(it) })
        }

        /** The view an item opens (action "open"): its plugin's [Item.view] with { id }. */
        fun forItem(item: Item) = ViewRef(item.plugin, item.view ?: "item", mapOf("id" to item.id))
    }
}

/**
 * A view's state ("state" in the view, changed by "set" actions). Every key is its own Compose state, so a
 * change only redraws the blocks that read that key – e.g. moving focus in the channel list (which sets
 * "channel") redraws the guide, not the whole view.
 */
class ViewState {
    private val values = HashMap<String, androidx.compose.runtime.MutableState<Any?>>()
    private fun slot(key: String) = values.getOrPut(key) { androidx.compose.runtime.mutableStateOf(null) }
    operator fun get(key: String): Any? = slot(key).value
    operator fun set(key: String, value: Any?) { slot(key).value = value }
    fun isEmpty() = values.isEmpty()
    fun isNotEmpty() = values.isNotEmpty()
    /** Every key and value (reading them all – for saving). */
    fun toMap(): Map<String, Any?> = values.mapValues { it.value.value }
}

/** What the app knows that views can ask about ({{item.$favorite}}, {{player.position}} …). */
class ViewEnv(
    val favorites: Map<String, Favorite>,
    val playingKey: String?,
    val reminders: Map<String, Reminder>,
    val now: Long,
    /** The app's own top strip (who did what, who watches, the clock) for player overlays. */
    val topbar: (@Composable () -> Unit)? = null,
)

/** Everything a building block needs while it is drawn. */
class ViewContext(
    val plugin: String,
    val api: Api,
    val sync: SyncClient,
    val state: ViewState,
    val roots: Map<String, Any?>,
    val env: ViewEnv,
    val open: (ViewRef) -> Unit,
    val back: () -> Unit,
    val item: Any? = null,
    /** The channel a guide list belongs to (for {{item.$reminder}}). */
    val channel: JSONObject? = null,
) {
    fun with(item: Any?, channel: JSONObject? = this.channel) = ViewContext(plugin, api, sync, state, roots, env, open, back, item, channel)
}

// ---------------------------------------------------------------------------------------------- bindings

internal val BIND = Regex("\\{\\{\\s*(.+?)\\s*\\}\\}")

/** A string with bindings, parsed once: literal text and expressions (each a list of "a || b" alternatives). */
private class Template(val whole: List<String>?, val parts: List<Any>)

private val templates = object : LinkedHashMap<String, Template>(512, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Template>?) = size > 4000
}

private fun template(s: String): Template = synchronized(templates) {
    templates.getOrPut(s) {
        val alts = { e: String -> e.split("||").map { it.trim() } }
        val whole = BIND.matchEntire(s)?.let { alts(it.groupValues[1]) } // only "{{path}}" keeps the value's type
        val parts = ArrayList<Any>()
        var last = 0
        for (m in BIND.findAll(s)) {
            if (m.range.first > last) parts.add(s.substring(last, m.range.first))
            parts.add(alts(m.groupValues[1]))
            last = m.range.last + 1
        }
        if (last < s.length) parts.add(s.substring(last))
        Template(whole, parts)
    }
}

/** A value with its bindings filled in. A string that is only "{{path}}" keeps the value's type (object, list …). */
fun ViewContext.value(v: Any?): Any? {
    if (v !is String || !v.contains("{{")) return if (v == JSONObject.NULL) null else v
    val t = template(v)
    t.whole?.let { return first(it) }
    val sb = StringBuilder()
    @Suppress("UNCHECKED_CAST")
    for (p in t.parts) if (p is String) sb.append(p) else sb.append(text(first(p as List<String>)))
    return sb.toString()
}

/** The first alternative that is set. */
private fun ViewContext.first(alts: List<String>): Any? {
    for (a in alts) { val v = path(a); if (truthy(v)) return v }
    return null
}

fun ViewContext.string(v: Any?): String = text(value(v))

/** Text of a resolved value ("" for nothing; whole numbers without decimals). */
fun text(v: Any?): String = when (v) {
    null, JSONObject.NULL -> ""
    is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
    is Float -> if (v % 1f == 0f) v.toLong().toString() else v.toString()
    is JSONObject, is JSONArray, is Map<*, *>, is List<*> -> ""
    else -> v.toString()
}

fun truthy(v: Any?): Boolean = when (v) {
    null, JSONObject.NULL, false -> false
    is String -> v.isNotEmpty() && v != "false" && v != "0" && v != "null"
    is Number -> v.toDouble() != 0.0
    is JSONArray -> v.length() > 0
    is Collection<*> -> v.isNotEmpty()
    else -> true
}

/** "a.b || c.d" – the first one that is set. */
internal fun ViewContext.expr(e: String): Any? = first(e.split("||").map { it.trim() })

internal fun ViewContext.path(p: String): Any? {
    if (p.startsWith("'") && p.endsWith("'") && p.length >= 2) return p.substring(1, p.length - 1)
    val parts = p.split('.')
    // state.x reads only the key x (so only blocks that use x are redrawn when it changes).
    var start = 1
    var cur: Any? = when (parts[0]) {
        "item" -> item
        "state" -> { start = 2; parts.getOrNull(1)?.let { state[it] } }
        else -> roots[parts[0]]
    }
    for (i in start until parts.size) cur = child(cur, parts[i]) ?: return null
    return cur
}

internal fun ViewContext.child(v: Any?, key: String): Any? = when (v) {
    is JSONObject -> if (key.startsWith("$")) computed(v, key) else v.opt(key).takeIf { it != JSONObject.NULL }
    is Map<*, *> -> v[key]
    is JSONArray -> if (key == "length") v.length() else key.toIntOrNull()?.let { v.opt(it) }
    is List<*> -> if (key == "length") v.size else key.toIntOrNull()?.let { v.getOrNull(it) }
    is String -> if (key == "length") v.length else null
    else -> null
}

/** Values the app works out for an item: $favorite, $newCount, $newLabel, $playing, $live, $now, $next, $reminder. */
internal fun ViewContext.computed(o: JSONObject, key: String): Any? {
    val itemKey = { "${o.optString("plugin").ifEmpty { plugin }}:${o.optString("id")}" }
    return when (key) {
        "\$favorite" -> env.favorites.containsKey(itemKey())
        "\$newCount" -> env.favorites[itemKey()]?.newCount?.takeIf { it > 0 }
        "\$newLabel" -> env.favorites[itemKey()]?.newLabel
        "\$playing" -> env.playingKey == itemKey()
        "\$live" -> o.optLong("start", 0) > 0 && env.now >= o.optLong("start") && env.now < o.optLong("end")
        "\$now", "\$next" -> {
            val list = o.optJSONArray("programs") ?: return null
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }.let { ps ->
                if (key == "\$now") ps.firstOrNull { env.now >= it.optLong("start") && env.now < it.optLong("end") }
                else ps.firstOrNull { it.optLong("start") > env.now }
            }
        }
        "\$reminder" -> {
            val ch = channel ?: return null
            val r = env.reminders[reminderKey(ch.optString("plugin").ifEmpty { plugin }, ch.optString("id"), o.optLong("start"))] ?: return null
            r.addedBy?.let { tr("Reminder · by {name}", "name" to it) } ?: tr("Reminder")
        }
        else -> null
    }
}

/** An object from a view as an item the app can play or favourite. */
internal fun ViewContext.toItem(v: Any?): Item? = when (v) {
    is JSONObject -> parseItem(v, plugin)
    else -> null
}

/** Parameters with their bindings filled in, as strings (for data and views). */
internal fun ViewContext.params(o: JSONObject?): Map<String, String> {
    if (o == null) return emptyMap()
    return o.keys().asSequence().associateWith { k ->
        when (val v = value(o.opt(k))) {
            is JSONObject -> v.optString("id")
            else -> text(v)
        }
    }
}

// ---------------------------------------------------------------------------------------------- actions

/** Runs an action: { play } { open, params } { set } { favorite } { remind } { if, then, else } { back }, or a list of them. */
fun ViewContext.run(action: Any?) {
    when (action) {
        is JSONArray -> for (i in 0 until action.length()) run(action.opt(i))
        is JSONObject -> {
            if (action.has("if")) {
                run(if (truthy(value(action.opt("if")))) action.opt("then") else action.opt("else"))
                return
            }
            if (action.has("play")) toItem(value(action.opt("play")))?.let { item ->
                val sub = action.optString("subtitle").takeIf { it.isNotEmpty() }?.let { string(it) }?.takeIf { it.isNotEmpty() }
                val pos = action.opt("position")?.let { text(value(it)).toDoubleOrNull() } ?: 0.0
                sync.load(if (item.subtitle == null && sub != null) item.copy(subtitle = sub) else item, pos)
            }
            if (action.has("open")) open(ViewRef(action.optString("plugin").ifEmpty { plugin }, string(action.opt("open")), params(action.optJSONObject("params"))))
            action.optJSONObject("set")?.let { set -> for (k in set.keys()) state[k] = value(set.opt(k)) }
            if (action.has("favorite")) toItem(value(action.opt("favorite")))?.let { sync.toggleFavorite(it) }
            action.optJSONObject("remind")?.let { r ->
                val ch = toItem(value(r.opt("channel")))
                val p = value(r.opt("program")) as? JSONObject
                if (ch != null && p != null) sync.toggleReminder(ch, Program(p.optString("title"), p.optString("description").ifEmpty { null }, p.optLong("start"), p.optLong("end")))
            }
            if (action.optBoolean("back")) back()
        }
    }
}

// ---------------------------------------------------------------------------------------------- tokens

internal fun colorOf(v: String?, fallback: Color = Palette.Text): Color = when (v) {
    null, "" -> fallback
    "text" -> Palette.Text
    "textSoft" -> Color(0xCCFFFFFF)
    "dim" -> Palette.Dim
    "faint" -> Palette.Faint
    "accent" -> Palette.Accent
    "gold" -> Palette.Gold
    "live" -> Palette.Live
    "green" -> Color(0xFF3DD68C)
    "status" -> Color(0xFF9FB4FF)
    "white" -> Color.White
    "black" -> Color.Black
    "dark" -> Color(0xB30A0C10)
    "track" -> Color(0x33FFFFFF)
    "trackSoft" -> Color(0x22FFFFFF)
    else -> runCatching { Color(android.graphics.Color.parseColor(v)) }.getOrDefault(fallback)
}

internal fun Modifier.backgroundOf(v: String?, shape: RoundedCornerShape): Modifier = when (v) {
    null, "", "none" -> this
    "panel" -> background(Palette.Panel, shape)
    "panelHi" -> background(Palette.PanelHi, shape)
    "bg" -> background(Palette.Bg, shape)
    "accentSoft" -> background(Palette.AccentSoft, shape)
    "heroFade" -> background(Brush.horizontalGradient(0.35f to Palette.Panel, 0.75f to Palette.Panel.copy(alpha = 0.55f), 1f to Color.Transparent), shape)
    "bottomFade" -> background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF00A0C10))), shape)
    "topFade" -> background(Brush.verticalGradient(listOf(Color(0xD9000000), Color.Transparent)), shape)
    "sideFade" -> background(Brush.horizontalGradient(listOf(Color(0xF50A0C10), Color(0xE60A0C10))), shape)
    else -> background(colorOf(v), shape)
}

internal fun alignOf(v: String?): Alignment = when (v) {
    "topStart" -> Alignment.TopStart
    "topCenter" -> Alignment.TopCenter
    "topEnd" -> Alignment.TopEnd
    "centerStart" -> Alignment.CenterStart
    "centerEnd" -> Alignment.CenterEnd
    "bottomStart" -> Alignment.BottomStart
    "bottomCenter" -> Alignment.BottomCenter
    "bottomEnd" -> Alignment.BottomEnd
    else -> Alignment.Center
}

internal fun paddingOf(v: Any?): PaddingValues? = when (v) {
    is Number -> PaddingValues(v.toFloat().dp)
    is JSONArray -> when (v.length()) {
        2 -> PaddingValues(horizontal = v.optDouble(0).toFloat().dp, vertical = v.optDouble(1).toFloat().dp)
        4 -> PaddingValues(v.optDouble(0).toFloat().dp, v.optDouble(1).toFloat().dp, v.optDouble(2).toFloat().dp, v.optDouble(3).toFloat().dp)
        else -> null
    }
    else -> null
}

internal fun iconOf(node: JSONObject): ImageVector = when (node.optString("name")) {
    "bell" -> Icons.Filled.Notifications
    "star" -> Icons.Filled.Star
    "search" -> Icons.Filled.Search
    "play" -> Icons.Filled.PlayArrow
    "history" -> AppIcons.History
    "live" -> AppIcons.LiveTv
    "movie" -> AppIcons.Movie
    "series" -> AppIcons.Series
    else -> pluginIcon(node.optString("path"))
}

/** Text styles: size, weight and colour. */
internal data class TextStyleSpec(val size: TextUnit, val weight: FontWeight, val color: Color?, val upper: Boolean = false, val spacing: TextUnit = TextUnit.Unspecified)

internal fun styleOf(name: String?): TextStyleSpec = when (name) {
    "title" -> TextStyleSpec(28.sp, FontWeight.Bold, null)
    "headline" -> TextStyleSpec(21.sp, FontWeight.Bold, null)
    "bodyStrong" -> TextStyleSpec(17.sp, FontWeight.Medium, null)
    "caption" -> TextStyleSpec(14.sp, FontWeight.Normal, Color(0xB3FFFFFF))
    "dim" -> TextStyleSpec(15.sp, FontWeight.Normal, Palette.Dim)
    "faint" -> TextStyleSpec(14.sp, FontWeight.Normal, Palette.Faint)
    "soft" -> TextStyleSpec(15.sp, FontWeight.Normal, null) // follows the focused row's colour, dimmed
    "label" -> TextStyleSpec(12.sp, FontWeight.Bold, Palette.Faint, upper = true, spacing = 1.5.sp)
    else -> TextStyleSpec(16.sp, FontWeight.Normal, null)
}

internal fun dpOf(v: Any?): Dp? = (v as? Number)?.toFloat()?.dp

/** Size, background, corners and padding that any block can have. Weight and alignment come from the parent. */
internal fun ViewContext.base(node: JSONObject, outer: Modifier = Modifier, skipPadding: Boolean = false): Modifier {
    var m = outer
    when (val w = node.opt("width")) {
        is Number -> m = m.width(w.toFloat().dp)
        "fill" -> m = m.fillMaxWidth()
        is String -> if (w.endsWith("%")) w.dropLast(1).toFloatOrNull()?.let { m = m.fillMaxWidth(it / 100f) }
    }
    when (val h = node.opt("height")) {
        is Number -> m = m.height(h.toFloat().dp)
        "fill" -> m = m.fillMaxHeight()
        is String -> if (h.endsWith("%")) h.dropLast(1).toFloatOrNull()?.let { m = m.fillMaxHeight(it / 100f) }
    }
    // On lists and grids "aspect" is the shape of each item, not of the list.
    val listLike = node.optString("type").let { it == "list" || it == "grid" }
    if (!listLike) (node.opt("aspect") as? Number)?.toFloat()?.takeIf { it > 0 }?.let { m = m.aspectRatio(it) }
    val shape = RoundedCornerShape((dpOf(node.opt("radius")) ?: 0.dp))
    if (node.has("radius")) m = m.clip(shape)
    m = m.backgroundOf(string(node.opt("background")).ifEmpty { null }, shape)
    if (!skipPadding) paddingOf(node.opt("padding"))?.let { m = m.padding(it) }
    (node.opt("opacity") as? Number)?.toFloat()?.let { m = m.alpha(it) }
    return m
}

internal fun ViewContext.visible(node: JSONObject): Boolean {
    fun all(v: Any?) = if (v is JSONArray) (0 until v.length()).all { truthy(value(v.opt(it))) } else truthy(value(v))
    fun any(v: Any?) = if (v is JSONArray) (0 until v.length()).any { truthy(value(v.opt(it))) } else truthy(value(v))
    if (node.has("when") && !all(node.opt("when"))) return false
    if (node.has("unless") && any(node.opt("unless"))) return false
    return true
}

// ---------------------------------------------------------------------------------------------- data

internal class Loaded(val value: Any?, val loading: Boolean, val error: String?)

/** Loads a block's data ({ name, params, delay, refresh }); loads again when the filled-in params change. */
@Composable
internal fun ViewContext.rememberData(spec: JSONObject): Loaded {
    val name = spec.optString("name")
    val params = params(spec.optJSONObject("params"))
    val key = name + params.toSortedMap().toString()
    var value by remember { mutableStateOf<Any?>(null) }
    var loadedKey by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        val wait = spec.optLong("delay", 0)
        if (wait > 0) delay(wait)
        while (true) {
            try { value = api.data(plugin, name, params); error = null } catch (e: Exception) { error = e.message }
            loadedKey = key
            val refresh = spec.optLong("refresh", 0)
            if (refresh <= 0) break
            delay(refresh * 1000)
        }
    }
    return Loaded(if (loadedKey != null) value else null, loadedKey != key, error)
}

// ---------------------------------------------------------------------------------------------- drawing

/** Draws one building block. */
@Composable
fun ViewContext.Block(node: JSONObject, modifier: Modifier = Modifier) {
    if (!visible(node)) return
    val type = node.optString("type")
    val spec = node.optJSONObject("data")
    if (spec != null && type != "list" && type != "grid") {
        val d = rememberData(spec)
        when {
            d.value == null && d.error != null -> Message(tr("Error: {message}", "message" to d.error), error = true)
            d.value == null -> SkeletonLines(modifier)
            else -> with(d.value).Draw(type, node, modifier)
        }
        return
    }
    Draw(type, node, modifier)
}

@Composable
internal fun ViewContext.Draw(type: String, node: JSONObject, modifier: Modifier) {
    when (type) {
        "row" -> Row(
            base(node, modifier),
            horizontalArrangement = Arrangement.spacedBy((dpOf(node.opt("gap")) ?: 0.dp)),
            verticalAlignment = when (node.optString("align")) { "center" -> Alignment.CenterVertically; "bottom" -> Alignment.Bottom; else -> Alignment.Top },
        ) { RowChildren(this@Draw, node) }
        "column" -> Column(
            base(node, modifier),
            verticalArrangement = Arrangement.spacedBy((dpOf(node.opt("gap")) ?: 0.dp)),
            horizontalAlignment = when (node.optString("align")) { "center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start },
        ) { ColumnChildren(this@Draw, node) }
        "box" -> Box(base(node, modifier)) { BoxChildren(this@Draw, node) }
        "spacer" -> Spacer(base(node, modifier))
        "text" -> TextBlock(node, modifier)
        "time" -> TimeBlock(node, modifier)
        "image" -> {
            val url = api.abs(string(node.opt("src")).ifEmpty { null })
            Thumb(url, string(node.opt("fallback")), base(node, modifier), fit = node.optString("fit") == "contain", corner = dpOf(node.opt("radius")) ?: 6.dp)
        }
        "icon" -> Icon(iconOf(node), contentDescription = null, tint = colorOf(string(node.opt("color")), LocalContentColor.current),
            modifier = base(node, modifier).size(dpOf(node.opt("size")) ?: 24.dp))
        "badge" -> {
            val t = string(node.opt("text"))
            if (t.isNotEmpty()) Text(
                t, fontSize = (node.optDouble("size", 11.0)).sp, fontWeight = FontWeight.Bold, color = colorOf(string(node.opt("textColor")), Color.White),
                modifier = modifier.padding(paddingOf(node.opt("padding")) ?: PaddingValues(0.dp))
                    .background(colorOf(string(node.opt("color")), Palette.Live), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        "progress" -> {
            val start = text(value(node.opt("start"))).toLongOrNull()
            val end = text(value(node.opt("end"))).toLongOrNull()
            val fraction = if (start != null && end != null && end > start) ((env.now - start).toFloat() / (end - start)) else text(value(node.opt("value"))).toFloatOrNull() ?: 0f
            val m = base(node, if (node.has("width") || node.has("weight")) modifier else modifier.fillMaxWidth())
            ProgressLine(fraction, m, color = colorOf(string(node.opt("color")), Palette.Accent), track = colorOf(string(node.opt("track")), Color(0x33FFFFFF)))
        }
        "clock" -> Box(base(node, modifier)) { Clock(node.optInt("size", 22)) }
        "topbar" -> Box(modifier) { env.topbar?.invoke() }
        "button" -> {
            val label = string(node.opt("text"))
            Pill(label, selected = truthy(value(node.opt("selected"))), onClick = { run(node.opt("onClick")) }, modifier = base(node, modifier))
        }
        "list" -> ListBlock(node, modifier, grid = false)
        "grid" -> ListBlock(node, modifier, grid = true)
        else -> Unit
    }
}

