@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package app.couchmate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.couchmate.Api
import app.couchmate.Item
import app.couchmate.PluginResult
import app.couchmate.SyncClient
import app.couchmate.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Searches every plugin at once. Results appear while you type. */
@Composable
fun SearchScreen(sync: SyncClient, api: Api) {
    val state by sync.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<List<PluginResult>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<Item?>(null) }
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { delay(150); runCatching { fieldFocus.requestFocus() } }

    // Search while typing (after a short pause – every search asks every plugin, YouTube's take a few seconds).
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { result = null; error = null; return@LaunchedEffect }
        delay(700)
        loading = true
        try {
            result = api.search(q)
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // typed on: a newer search replaces this one – not an error
        } catch (e: Exception) {
            if (!isActive) return@LaunchedEffect // the request broke off because a newer search started
            error = e.message
        }
        loading = false
    }

    val o = opened
    if (o != null) {
        key(o.key) { ViewHost(sync, api, ViewRef.forItem(o), onExit = { opened = null }) }
        return
    }
    val opener = ItemOpener { item -> if (item.opens) opened = item else sync.load(item) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // ---- search field
        var focused by remember { mutableStateOf(false) }
        Box(
            Modifier
                .fillMaxWidth(0.6f)
                .background(Palette.Panel, RoundedCornerShape(50))
                .border(2.dp, if (focused) Color.White else Palette.Line, RoundedCornerShape(50))
                .padding(horizontal = 28.dp, vertical = 16.dp),
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Palette.Text, fontSize = 22.sp),
                cursorBrush = SolidColor(Palette.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    focusManager.moveFocus(FocusDirection.Down)
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                            else -> false
                        }
                    },
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.Filled.Search, contentDescription = null, tint = Palette.Dim, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(14.dp))
                        Box {
                            if (query.isEmpty()) Text(tr("Search everything…"), fontSize = 22.sp, color = Palette.Faint)
                            inner()
                        }
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        val r = result
        val sections = r.orEmpty().flatMap { p -> p.sections.filter { it.items.isNotEmpty() }.map { p to it } }
        Text(
            when {
                error != null -> tr("Error: {message}", "message" to error)
                loading -> tr("Searching…")
                query.trim().length < 2 -> tr("Type at least two characters. Press the microphone on the keyboard to search by voice.")
                r != null && sections.isEmpty() -> tr("No results for \"{query}\"", "query" to query.trim())
                else -> tr("Hold OK to add to favorites")
            },
            fontSize = 14.sp,
            color = if (error != null) Color(0xFFFF8A80) else Palette.Faint,
            modifier = Modifier.padding(start = 28.dp, top = 4.dp, bottom = 8.dp),
        )

        // ---- results: one row per section, in the order the plugins answered
        // Down from the search field or in from the menu: back to the row you were on, otherwise the top one.
        val listState = rememberLazyListState()
        val entry = rememberFocusEntryByKey(listState, "search")
        if (sections.isNotEmpty()) {
            val several = r.orEmpty().size > 1
            LazyColumn(
                state = listState,
                modifier = Modifier.focusEntry(entry),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                sections.forEach { (p, s) ->
                    val k = "${p.plugin}/${s.id}"
                    item(key = k) {
                        Box(entry.item(k)) {
                            // With several plugins the plugin's name goes in front, e.g. "IPTV · Movies".
                            val title = s.title?.let { if (several) "${p.name} · $it" else it } ?: p.name
                            CardRow(title, s, api, sync, opener, state?.media?.key)
                        }
                    }
                }
            }
        }
    }
}
