@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package app.couchmate.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
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

@Composable
private fun rememberEntry(name: String, autoRestore: Boolean = true, visible: () -> List<Any>): FocusEntry {
    val store = rememberSaveable(name) { mutableStateOf<String?>(null) }
    val memory = LocalFocusMemory.current
    val entry = remember(name) { FocusEntry(name, visible, store, memory) }
    LaunchedEffect(entry) { if (autoRestore && entry.shouldRestore) entry.restore() }
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
    this.focusProperties { enter = { entry.target(*wanted()) } }.focusGroup()
