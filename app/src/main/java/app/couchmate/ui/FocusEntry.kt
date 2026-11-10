@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package app.couchmate.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay

/**
 * Which list had focus last (its name), kept for the whole menu screen.
 * Coming back from the player gives that list focus again, on the same row.
 * null = focus was in the menu on the left.
 */
val LocalFocusMemory = staticCompositionLocalOf<MutableState<String?>?> { null }

/**
 * Decides where focus lands when entering a list from the side (e.g. from the menu), and restores focus
 * when coming back from the player. Without this Compose picks the row closest in height – often several rows down.
 * Items are identified by index or by the list's keys, depending on how it was built.
 */
class FocusEntry(
    private val name: String,
    private val visible: () -> List<Any>,
    private val store: MutableState<String?>,
    private val memory: MutableState<String?>?,
) {
    private val requesters = HashMap<Any, FocusRequester>()

    /** The last focused item in the list (kept even when the screen is left). */
    var last: Any?
        get() = store.value?.let { if (it.startsWith("i:")) it.drop(2).toIntOrNull() else it.drop(2) }
        set(v) { store.value = when (v) { is Int -> "i:$v"; is String -> "s:$v"; else -> null } }

    fun requester(id: Any): FocusRequester = requesters.getOrPut(id) { FocusRequester() }

    /** Set while [focus] moves focus here, so the list's enter rule lands on that item. */
    internal var pending: Any? = null

    /** Where the list is on the screen (for Back – see [FocusTrail]). */
    internal var bounds: Rect? = null

    /** The list's selected item (e.g. the group whose movies are shown), if it has one. */
    var selected: () -> Any? = { null }

    /** Gives focus to the selected item, else the one you were on (or the first visible one). */
    fun focus(): Boolean {
        val vis = visible()
        val id = listOf(selected(), last).firstOrNull { it != null && it in vis } ?: vis.firstOrNull { it in requesters } ?: return false
        // Entering the list runs its "enter" rule (the last row) – tell it to land here instead.
        pending = id
        val ok = runCatching { requester(id).requestFocus() }.isSuccess
        pending = null
        return ok
    }

    /** Put on every item in the list (id = index or key). */
    fun item(id: Any): Modifier = Modifier
        .focusRequester(requester(id))
        .onFocusChanged {
            if (it.hasFocus) {
                last = id
                memory?.value = name
            }
        }

    fun target(vararg wanted: Any?): FocusRequester {
        val vis = visible()
        val pick = wanted.firstOrNull { it != null && it in vis } ?: vis.firstOrNull { it in requesters } ?: return FocusRequester.Default
        return requester(pick)
    }

    /** Did this list have focus when the screen was left (e.g. for the player)? */
    val shouldRestore get() = memory?.value == name && last != null

    /** Gives focus to the row you were on (waits until it has loaded and been drawn). */
    suspend fun restore() {
        val id = last ?: return
        repeat(30) {
            delay(100)
            if (memory?.value != name) return // you have gone somewhere else meanwhile
            if (runCatching { requester(id).requestFocus() }.isSuccess && id in visible()) return
        }
    }
}

/**
 * Back in the content goes to the list on the left of the one you are in – e.g. posters → groups,
 * guide → channels → groups – instead of stepping one item at a time. With nothing on the left: the menu.
 */
object FocusTrail {
    private val live = LinkedHashSet<FocusEntry>()
    /** The list that has focus now (null = focus is somewhere else, e.g. in a channel's guide). */
    private var focused: FocusEntry? = null
    private var lastFocused: FocusEntry? = null

    internal fun add(e: FocusEntry) { live += e }
    internal fun remove(e: FocusEntry) {
        live -= e
        if (focused == e) focused = null
        if (lastFocused == e) lastFocused = null
    }
    internal fun focusChanged(e: FocusEntry, has: Boolean) {
        if (has) { focused = e; lastFocused = e } else if (focused == e) focused = null
    }

    /** One step back. False = nothing to go back to (the caller goes to the menu). */
    fun back(): Boolean {
        val cur = focused
        // Focus is outside the lists (e.g. in the guide next to the channels): back to the list you came from.
        if (cur == null) return lastFocused?.focus() ?: false
        val b = cur.bounds ?: return false
        val left = live.filter { e ->
            val o = e.bounds ?: return@filter false
            e !== cur && o.right <= b.left + 8f && o.bottom > b.top && o.top < b.bottom
        }
        return left.sortedByDescending { it.bounds!!.right }.any { it.focus() }
    }
}

@Composable
private fun rememberEntry(name: String, autoRestore: Boolean = true, visible: () -> List<Any>): FocusEntry {
    val store = rememberSaveable(name) { mutableStateOf<String?>(null) }
    val memory = LocalFocusMemory.current
    val entry = remember(name) { FocusEntry(name, visible, store, memory) }
    LaunchedEffect(entry) {
        if (autoRestore && entry.shouldRestore) entry.restore()
    }
    DisposableEffect(entry) { FocusTrail.add(entry); onDispose { FocusTrail.remove(entry) } }
    return entry
}

/** Items identified by index. */
@Composable
fun rememberFocusEntry(state: LazyListState, name: String): FocusEntry =
    rememberEntry(name) { state.layoutInfo.visibleItemsInfo.filter { it.offset >= 0 }.map { it.index } }

/** Items identified by key – for lists with mixed rows. */
@Composable
fun rememberFocusEntryByKey(state: LazyListState, name: String, autoRestore: Boolean = true): FocusEntry =
    rememberEntry(name, autoRestore) { state.layoutInfo.visibleItemsInfo.filter { it.offset >= 0 }.map { it.key } }

@Composable
fun rememberFocusEntry(state: LazyGridState, name: String): FocusEntry =
    rememberEntry(name) { state.layoutInfo.visibleItemsInfo.filter { it.offset.y >= 0 }.map { it.index } }

/** Put on the list: focus coming from outside goes to [wanted] (the first that is visible), otherwise the top row. */
fun Modifier.focusEntry(entry: FocusEntry, wanted: () -> Array<Any?> = { arrayOf(entry.last) }): Modifier =
    this.onGloballyPositioned { entry.bounds = it.boundsInRoot() }
        .onFocusChanged { FocusTrail.focusChanged(entry, it.hasFocus) }
        .focusProperties { enter = { entry.pending?.let { entry.requester(it) } ?: entry.target(*wanted()) } }
        .focusGroup()
