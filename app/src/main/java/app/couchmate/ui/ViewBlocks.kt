@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package app.couchmate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.couchmate.Api
import app.couchmate.I18n
import app.couchmate.SyncClient
import app.couchmate.tr
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

// The second half of the view renderer: children of rows/columns/boxes, text, lists and the screens.

internal fun JSONObject.weight(): Float? = (opt("weight") as? Number)?.toFloat()?.takeIf { it > 0 }

internal fun JSONObject.children(): List<JSONObject> =
    optJSONArray("children")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

@Composable
internal fun RowScope.RowChildren(ctx: ViewContext, node: JSONObject) {
    for (c in node.children()) {
        val w = c.weight()
        with(ctx) { Block(c, if (w != null) Modifier.weight(w) else Modifier) }
    }
}

@Composable
internal fun ColumnScope.ColumnChildren(ctx: ViewContext, node: JSONObject) {
    for (c in node.children()) {
        val w = c.weight()
        with(ctx) { Block(c, if (w != null) Modifier.weight(w) else Modifier) }
    }
}

@Composable
internal fun BoxScope.BoxChildren(ctx: ViewContext, node: JSONObject) {
    for (c in node.children()) {
        with(ctx) { Block(c, if (c.has("align")) Modifier.align(alignOf(c.optString("align"))) else Modifier) }
    }
}

/** Text: "text", or "join" (parts with an empty binding are left out) with a "separator". */
@Composable
internal fun ViewContext.TextBlock(node: JSONObject, modifier: Modifier) {
    val content = node.optJSONArray("join")?.let { parts ->
        (0 until parts.length()).mapNotNull { i ->
            val part = parts.opt(i) as? String ?: return@mapNotNull null
            val bindings = BIND.findAll(part).map { expr(it.groupValues[1]) }.toList()
            if (bindings.isNotEmpty() && bindings.any { text(it).isEmpty() }) null else string(part).takeIf { it.isNotBlank() }
        }.joinToString(node.optString("separator").ifEmpty { "  ·  " })
    } ?: string(node.opt("text"))
    StyledText(node, content.ifEmpty { string(node.opt("fallback")) }, modifier)
}

/** A time: "time" (21:30, default), "day" (Today, Tomorrow, Tuesday 10 November) or "datetime". */
@Composable
internal fun ViewContext.TimeBlock(node: JSONObject, modifier: Modifier) {
    val sec = text(value(node.opt("value"))).toDoubleOrNull()?.toLong() ?: return
    val t = when (node.optString("format")) {
        "day" -> dayLabel(sec)
        "datetime" -> "${dayLabel(sec)} ${hhmm(sec)}"
        else -> hhmm(sec)
    }
    StyledText(node, t, modifier)
}

@Composable
internal fun ViewContext.StyledText(node: JSONObject, t: String, modifier: Modifier) {
    if (t.isEmpty()) return
    val style = node.optString("style")
    val st = styleOf(style)
    val explicit = string(node.opt("color")).ifEmpty { null }
    val color = explicit?.let { colorOf(it) } ?: st.color ?: if (style == "soft") LocalContentColor.current.copy(alpha = 0.7f) else LocalContentColor.current
    Text(
        if (st.upper) t.uppercase(I18n.locale) else t,
        fontSize = (node.opt("size") as? Number)?.toFloat()?.sp ?: st.size,
        fontWeight = if (node.optBoolean("bold")) FontWeight.Bold else st.weight,
        color = color,
        letterSpacing = st.spacing,
        maxLines = node.optInt("maxLines", Int.MAX_VALUE),
        overflow = TextOverflow.Ellipsis,
        modifier = base(node, modifier),
    )
}

internal fun toList(v: Any?): List<Any?> = when (v) {
    is JSONArray -> (0 until v.length()).map { v.opt(it) }
    is List<*> -> v
    else -> emptyList()
}

/** Extra data for a list's items ({ name, as, refresh }), fetched with ?ids=… and merged into each item as [as]. */
@Composable
internal fun ViewContext.rememberEnrich(spec: JSONObject, items: List<Any?>): Map<String, Any?> {
    val ids = items.mapNotNull { (it as? JSONObject)?.optString("id")?.takeIf { s -> s.isNotEmpty() } }.take(200)
    var extra by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    LaunchedEffect(ids) {
        if (ids.isEmpty()) return@LaunchedEffect
        while (true) {
            runCatching { api.data(plugin, spec.optString("name"), params(spec.optJSONObject("params")) + ("ids" to ids.joinToString(","))) }
                .getOrNull()?.let { r -> if (r is JSONObject) extra = r.keys().asSequence().associateWith { r.opt(it) } }
            val refresh = spec.optLong("refresh", 0)
            if (refresh <= 0) break
            delay(refresh * 1000)
        }
    }
    return extra
}

internal data class Heading(val text: String)

/**
 * A list or grid of items. Items come from "items" (a binding) or "data" (an array, or an object with "items").
 * focus: row | card | poster | pill | none – how an item looks and is focused. Actions: onFocus, onClick, onLongClick.
 */
@Composable
internal fun ViewContext.ListBlock(node: JSONObject, modifier: Modifier, grid: Boolean) {
    val spec = node.optJSONObject("data")
    val loaded = spec?.let { rememberData(it) }
    val raw: Any? = if (spec != null) loaded?.value?.let { if (it is JSONObject) it.opt("items") else it } else value(node.opt("items"))
    val items = toList(raw)
    val enrich = node.optJSONObject("enrich")
    val extra = if (enrich != null) rememberEnrich(enrich, items) else emptyMap()
    val shown = remember(items, extra) {
        if (extra.isEmpty()) items else items.map { o ->
            val obj = o as? JSONObject ?: return@map o
            val add = extra[obj.optString("id")] ?: return@map o
            JSONObject(obj.toString()).put(enrich!!.optString("as").ifEmpty { "extra" }, add)
        }
    }
    val template = node.optJSONObject("item") ?: return
    val style = node.optString("focus").ifEmpty { "row" }
    val channel = value(node.opt("channel")) as? JSONObject
    val listId = string(node.opt("id")).ifEmpty { "list" }
    val selected = string(node.opt("selected")).ifEmpty { null }
    val initial = string(node.opt("initial")).ifEmpty { null }
    val gap = dpOf(node.opt("gap")) ?: 2.dp
    val content = paddingOf(node.opt("padding")) ?: PaddingValues(0.dp)
    // Lists and grids fill the width unless the view says otherwise.
    val m = base(node, if (node.has("width")) modifier else modifier.fillMaxWidth(), skipPadding = true)

    when {
        spec != null && loaded?.value == null && loaded?.error != null -> { Message(tr("Error: {message}", "message" to loaded.error), error = true); return }
        spec != null && loaded?.value == null -> { Box(m) { Message(node.optString("loading").ifEmpty { tr("Loading…") }) }; return }
        shown.isEmpty() -> { Box(m) { node.optString("empty").takeIf { it.isNotEmpty() }?.let { Message(string(it)) } }; return }
    }

    // Stable keys: the item's id (the index if ids repeat or are missing).
    val keys = remember(shown) {
        val seen = HashSet<String>()
        shown.mapIndexed { i, o -> val id = (o as? JSONObject)?.optString("id").orEmpty(); if (id.isNotEmpty() && seen.add(id)) id else "#$i" }
    }
    val ctxFor = { o: Any? -> with(o, channel) }
    val isSelected = { o: Any? -> selected != null && (o as? JSONObject)?.optString("id") == selected }
    val longClick = node.has("onLongClick")

    if (grid) {
        val state = rememberLazyGridState()
        val entry = rememberFocusEntry(state, listId)
        val wanted = keys.indexOfFirst { it == initial || it == selected }.takeIf { it >= 0 }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(dpOf(node.opt("minWidth")) ?: 120.dp),
            state = state,
            modifier = m.focusEntry(entry) { arrayOf<Any?>(entry.last, wanted) },
            contentPadding = content,
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(dpOf(node.opt("rowGap")) ?: 16.dp),
        ) {
            itemsIndexed(shown, key = { i, _ -> keys[i] }) { i, o ->
                val c = ctxFor(o)
                ItemBox(
                    node, template, c, style, entry.item(i), isSelected(o),
                    onFocus = { entry.last = i; c.run(node.opt("onFocus")) },
                    onClick = { c.run(node.opt("onClick")) },
                    onLongClick = if (longClick) ({ c.run(node.opt("onLongClick")) }) else null,
                )
            }
        }
        Autofocus(node, entry, if (keys.isNotEmpty()) 0 else null)
        return
    }

    // Day headings between items ("sectionBy": "day", by their "start").
    val entries: List<Any?> = remember(shown, I18n.lang) {
        if (node.optString("sectionBy") != "day") shown else buildList {
            var last: String? = null
            for (o in shown) {
                val start = (o as? JSONObject)?.optLong("start") ?: 0
                val d = if (start > 0) dayLabel(start) else null
                if (d != null && d != last) { add(Heading(d)); last = d }
                add(o)
            }
        }
    }
    val indexOf = remember(shown) { shown.withIndex().associate { (i, o) -> System.identityHashCode(o) to i } }
    val state = rememberLazyListState()
    val entry = rememberFocusEntryByKey(state, listId)
    val wanted: Any? = keys.firstOrNull { it == initial } ?: keys.firstOrNull { it == selected }
    val body: LazyListScope.() -> Unit = {
        itemsIndexed(entries, key = { n, o -> if (o is Heading) "h:${o.text}:$n" else keys[indexOf[System.identityHashCode(o)] ?: 0] }) { _, o ->
            if (o is Heading) {
                Text(o.text.uppercase(I18n.locale), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Faint, letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 4.dp))
            } else {
                val k = keys[indexOf[System.identityHashCode(o)] ?: 0]
                val c = ctxFor(o)
                ItemBox(
                    node, template, c, style, entry.item(k), isSelected(o),
                    onFocus = { entry.last = k; c.run(node.opt("onFocus")) },
                    onClick = { c.run(node.opt("onClick")) },
                    onLongClick = if (longClick) ({ c.run(node.opt("onLongClick")) }) else null,
                )
            }
        }
    }
    val listMod = m.focusEntry(entry) { arrayOf<Any?>(entry.last, wanted) }
    if (node.optString("direction") == "horizontal") {
        LazyRow(state = state, modifier = listMod, contentPadding = content, horizontalArrangement = Arrangement.spacedBy(gap), content = body)
    } else {
        LazyColumn(state = state, modifier = listMod, contentPadding = content, verticalArrangement = Arrangement.spacedBy(gap), content = body)
    }
    Autofocus(node, entry, keys.firstOrNull())
}

/** "autofocus": true – the list takes focus when the view opens (unless coming back from the player). */
@Composable
internal fun Autofocus(node: JSONObject, entry: FocusEntry, first: Any?) {
    if (!node.optBoolean("autofocus") || first == null) return
    LaunchedEffect(Unit) {
        delay(120)
        if (!entry.shouldRestore) runCatching { entry.requester(entry.last ?: first).requestFocus() }
    }
}

/** One item, drawn and focused the way the list's "focus" says. */
@Composable
internal fun ItemBox(
    node: JSONObject, template: JSONObject, ctx: ViewContext, style: String, focusMod: Modifier, selected: Boolean,
    onFocus: () -> Unit, onClick: () -> Unit, onLongClick: (() -> Unit)?,
) {
    val width = dpOf(node.opt("itemWidth"))
    val height = dpOf(node.opt("itemHeight"))
    when (style) {
        "row" -> FocusRow(onClick = onClick, onLongClick = onLongClick, selected = selected, height = height ?: 56.dp, onFocus = onFocus, modifier = focusMod) {
            with(ctx) { Block(template, Modifier.weight(1f)) }
        }
        "card" -> Surface(
            onClick = onClick, onLongClick = onLongClick,
            modifier = focusMod.then(if (width != null) Modifier.width(width) else Modifier).then(if (height != null) Modifier.height(height) else Modifier)
                .onFocusChanged { if (it.isFocused) onFocus() },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (selected) Palette.AccentSoft else Palette.Panel, contentColor = Palette.Text,
                focusedContainerColor = Color.White, focusedContentColor = Color.Black,
            ),
        ) { with(ctx) { Block(template) } }
        "poster" -> Column(if (width != null) Modifier.width(width) else Modifier) {
            Surface(
                onClick = onClick, onLongClick = onLongClick,
                modifier = focusMod.fillMaxWidth().aspectRatio(node.optDouble("aspect", 2.0 / 3.0).toFloat()).onFocusChanged { if (it.isFocused) onFocus() },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(8.dp))),
                colors = ClickableSurfaceDefaults.colors(containerColor = Palette.PanelHi, focusedContainerColor = Palette.PanelHi),
            ) { with(ctx) { Block(template, Modifier.fillMaxSize()) } }
            with(ctx) {
                string(node.opt("sublabel")).takeIf { it.isNotEmpty() }?.let {
                    Text(it, fontSize = 12.sp, color = Palette.Accent, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
                }
                string(node.opt("label")).takeIf { it.isNotEmpty() }?.let {
                    Text(cleanTitle(it), fontSize = 13.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp))
                }
            }
        }
        "pill" -> Surface(
            onClick = onClick, onLongClick = onLongClick,
            modifier = focusMod.onFocusChanged { if (it.isFocused) onFocus() },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (selected) Palette.PanelHi else Color.Transparent, contentColor = if (selected) Palette.Text else Palette.Dim,
                focusedContainerColor = Color.White, focusedContentColor = Color.Black,
            ),
        ) { Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) { with(ctx) { Block(template) } } }
        else -> Box(focusMod.then(if (width != null) Modifier.width(width) else Modifier)) { with(ctx) { Block(template) } }
    }
}

// ---------------------------------------------------------------------------------------------- screens

@Composable
internal fun rememberEnv(sync: SyncClient, nowPeriodMs: Long = 30_000, topbar: (@Composable () -> Unit)? = null): ViewEnv {
    val favorites by sync.favorites.collectAsState()
    val reminders by sync.reminders.collectAsState()
    val state by sync.state.collectAsState()
    val now = rememberNowSec(nowPeriodMs)
    val favMap = remember(favorites) { favorites.associateBy { it.key } }
    val remMap = remember(reminders) { reminders.associateBy { it.key } }
    return ViewEnv(favMap, state?.media?.key, remMap, now, topbar)
}

private fun unwrap(v: Any?): Any? = if (v == JSONObject.NULL) null else v

/**
 * One plugin view: loaded from the server, with its own state ("state" in the view, changed by "set" actions).
 * The state is kept while the player shows, so you come back to the same channel, group or season.
 */
@Composable
fun ViewScreen(sync: SyncClient, api: Api, ref: ViewRef, open: (ViewRef) -> Unit, back: () -> Unit, modifier: Modifier = Modifier) {
    var root by remember(ref.key) { mutableStateOf<JSONObject?>(null) }
    var error by remember(ref.key) { mutableStateOf<String?>(null) }
    val saved = rememberSaveable(ref.key) { mutableStateOf<String?>(null) }
    val state = remember(ref.key) {
        mutableStateMapOf<String, Any?>().also { m ->
            saved.value?.let { s -> runCatching { JSONObject(s).let { o -> for (k in o.keys()) m[k] = unwrap(o.opt(k)) } } }
        }
    }
    val restored = remember(ref.key) { state.isNotEmpty() }
    LaunchedEffect(ref.key) {
        var first = true
        while (true) {
            try {
                val v = api.view(ref.plugin, ref.id, ref.params)
                if (first && !restored) v.optJSONObject("state")?.let { s -> for (k in s.keys()) state[k] = unwrap(s.opt(k)) }
                root = v
                error = null
            } catch (e: Exception) { error = e.message }
            first = false
            val refresh = root?.optLong("refresh", 0) ?: 0
            if (refresh <= 0) break
            delay(refresh * 1000)
        }
    }
    LaunchedEffect(ref.key) {
        snapshotFlow { state.toMap() }.collect { snap ->
            saved.value = JSONObject().apply { for ((k, v) in snap) put(k, v ?: JSONObject.NULL) }.toString()
        }
    }
    val env = rememberEnv(sync)
    val r = root
    when {
        r == null && error != null -> Message(tr("Error: {message}", "message" to error), error = true)
        r == null -> Message(tr("Loading…"))
        else -> {
            val viewRoot = JSONObject((r.optJSONObject("view") ?: JSONObject()).toString()).apply { for ((k, v) in ref.params) if (!has(k)) put(k, v) }
            val ctx = ViewContext(ref.plugin, api, sync, state, mapOf("view" to viewRoot), env, open, back)
            ctx.Block(r, modifier.fillMaxSize())
        }
    }
}

/**
 * A stack of plugin views starting at [root]: "open" pushes a view, Back pops it. At the root, Back calls
 * [onExit] (if given) – otherwise the screen around it handles Back.
 */
@Composable
fun ViewHost(sync: SyncClient, api: Api, root: ViewRef, onExit: (() -> Unit)? = null) {
    var stack by rememberSaveable(root.key) { mutableStateOf(listOf(root.encode())) }
    val current = ViewRef.decode(stack.last())
    BackHandler(enabled = stack.size > 1 || onExit != null) {
        if (stack.size > 1) stack = stack.dropLast(1) else onExit?.invoke()
    }
    key(current.key) {
        ViewScreen(
            sync, api, current,
            open = { stack = stack + it.encode() },
            back = { if (stack.size > 1) stack = stack.dropLast(1) else onExit?.invoke() },
        )
    }
}

/** A plugin's overlay on top of the video, bound to {{media}}, {{player}} and the app's top strip ("topbar"). */
@Composable
fun PluginOverlay(sync: SyncClient, api: Api, plugin: String, node: JSONObject, media: JSONObject, player: JSONObject, topbar: @Composable () -> Unit) {
    val state = remember { mutableStateMapOf<String, Any?>() }
    val env = rememberEnv(sync, 15_000, topbar)
    val ctx = ViewContext(plugin, api, sync, state, mapOf("media" to media, "player" to player), env, open = {}, back = {})
    ctx.Block(node, Modifier.fillMaxSize())
}

