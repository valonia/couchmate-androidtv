@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package se.synctv.ui

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
 * Vilken lista som hade fokus senast (namnet), sparat för hela menyskärmen.
 * När man kommer tillbaka från spelaren får den listan fokus igen, på samma rad.
 * null = fokus var i menyn till vänster.
 */
val LocalFocusMemory = staticCompositionLocalOf<MutableState<String?>?> { null }

/**
 * Bestämmer var fokus hamnar när man går in i en lista från sidan (t.ex. från menyn), och
 * återställer fokus när man kommer tillbaka från spelaren.
 * Utan detta väljer Compose raden som ligger närmast i höjd – ofta flera rader ner.
 * Objekten identifieras med index eller med listans nycklar (key), beroende på hur den skapades.
 */
class FocusEntry(
    private val name: String,
    private val visible: () -> List<Any>,
    private val store: MutableState<String?>,
    private val memory: MutableState<String?>?,
) {
    private val requesters = HashMap<Any, FocusRequester>()

    /** Senast fokuserade objekt i listan (sparas även när skärmen lämnas). */
    var last: Any?
        get() = store.value?.let { if (it.startsWith("i:")) it.drop(2).toIntOrNull() else it.drop(2) }
        set(v) { store.value = when (v) { is Int -> "i:$v"; is String -> "s:$v"; else -> null } }

    fun requester(id: Any): FocusRequester = requesters.getOrPut(id) { FocusRequester() }

    /** Sätts på varje objekt i listan (id = index eller nyckel). */
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

    /** Hade den här listan fokus när skärmen lämnades (t.ex. för spelaren)? */
    val shouldRestore get() = memory?.value == name && last != null

    /** Ge fokus till raden man var på (väntar tills raden har laddats och ritats). */
    suspend fun restore() {
        val id = last ?: return
        repeat(30) {
            delay(100)
            if (memory?.value != name) return // man har hunnit gå någon annanstans
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

/** Objekten identifieras med sitt index. */
@Composable
fun rememberFocusEntry(state: LazyListState, name: String): FocusEntry =
    rememberEntry(name) { state.layoutInfo.visibleItemsInfo.filter { it.offset >= 0 }.map { it.index } }

/** Objekten identifieras med sin nyckel (key) – för listor med blandade rader. */
@Composable
fun rememberFocusEntryByKey(state: LazyListState, name: String, autoRestore: Boolean = true): FocusEntry =
    rememberEntry(name, autoRestore) { state.layoutInfo.visibleItemsInfo.filter { it.offset >= 0 }.map { it.key } }

@Composable
fun rememberFocusEntry(state: LazyGridState, name: String): FocusEntry =
    rememberEntry(name) { state.layoutInfo.visibleItemsInfo.filter { it.offset.y >= 0 }.map { it.index } }

/** Sätts på listan: när fokus kommer utifrån går det till [wanted] (första som syns), annars översta raden. */
fun Modifier.focusEntry(entry: FocusEntry, wanted: () -> Array<Any?> = { arrayOf(entry.last) }): Modifier =
    this.focusProperties { enter = { entry.target(*wanted()) } }.focusGroup()
