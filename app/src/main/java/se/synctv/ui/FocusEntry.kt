@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package se.synctv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Bestämmer var fokus hamnar när man går in i en lista från sidan (t.ex. från menyn).
 * Utan detta väljer Compose raden som ligger närmast i höjd – ofta flera rader ner.
 * Ordning: önskat objekt om det syns, annars översta helt synliga raden.
 * Objekten identifieras med index eller med listans nycklar (key), beroende på hur den skapades.
 */
class FocusEntry(private val visible: () -> List<Any>) {
    private val requesters = HashMap<Any, FocusRequester>()

    /** Senast fokuserade objekt i listan (för att komma tillbaka dit). */
    var last: Any? = null

    fun requester(id: Any): FocusRequester = requesters.getOrPut(id) { FocusRequester() }

    /** Sätts på varje objekt i listan (id = index eller nyckel). */
    fun item(id: Any): Modifier = Modifier
        .focusRequester(requester(id))
        .onFocusChanged { if (it.hasFocus) last = id }

    fun target(vararg wanted: Any?): FocusRequester {
        val vis = visible()
        val pick = wanted.firstOrNull { it != null && it in vis } ?: vis.firstOrNull { it in requesters } ?: return FocusRequester.Default
        return requester(pick)
    }
}

/** Objekten identifieras med sitt index. */
@Composable
fun rememberFocusEntry(state: LazyListState): FocusEntry = remember(state) {
    FocusEntry { state.layoutInfo.visibleItemsInfo.filter { it.offset >= 0 }.map { it.index } }
}

/** Objekten identifieras med sin nyckel (key) – för listor med blandade rader. */
@Composable
fun rememberFocusEntryByKey(state: LazyListState): FocusEntry = remember(state) {
    FocusEntry { state.layoutInfo.visibleItemsInfo.filter { it.offset >= 0 }.map { it.key } }
}

@Composable
fun rememberFocusEntry(state: LazyGridState): FocusEntry = remember(state) {
    FocusEntry { state.layoutInfo.visibleItemsInfo.filter { it.offset.y >= 0 }.map { it.index } }
}

/** Sätts på listan: när fokus kommer utifrån går det till [wanted] (första som syns), annars översta raden. */
fun Modifier.focusEntry(entry: FocusEntry, wanted: () -> Array<Any?> = { arrayOf(entry.last) }): Modifier =
    this.focusProperties { enter = { entry.target(*wanted()) } }.focusGroup()
