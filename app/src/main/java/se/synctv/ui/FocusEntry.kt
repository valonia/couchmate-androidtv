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

/**
 * Bestämmer var fokus hamnar när man går in i en lista från sidan (t.ex. från menyn).
 * Utan detta väljer Compose raden som ligger närmast i höjd – ofta flera rader ner.
 * Ordning: önskat objekt om det syns, annars översta helt synliga raden.
 */
class FocusEntry(private val visible: () -> List<Int>) {
    private val requesters = HashMap<Int, FocusRequester>()

    /** Senast fokuserade objekt i listan (för att komma tillbaka dit). */
    var last: Int = -1

    fun requester(index: Int): FocusRequester = requesters.getOrPut(index) { FocusRequester() }

    /** Sätts på varje objekt i listan. */
    fun item(index: Int): Modifier = Modifier.focusRequester(requester(index))

    fun target(vararg wanted: Int): FocusRequester {
        val vis = visible()
        val pick = wanted.firstOrNull { it >= 0 && it in vis } ?: vis.firstOrNull() ?: return FocusRequester.Default
        return requester(pick)
    }
}

@Composable
fun rememberFocusEntry(state: LazyListState): FocusEntry = remember(state) {
    FocusEntry { state.layoutInfo.visibleItemsInfo.filter { it.offset >= 0 }.map { it.index } }
}

@Composable
fun rememberFocusEntry(state: LazyGridState): FocusEntry = remember(state) {
    FocusEntry { state.layoutInfo.visibleItemsInfo.filter { it.offset.y >= 0 }.map { it.index } }
}

/** Sätts på listan: när fokus kommer utifrån går det till [wanted] (första som syns), annars översta raden. */
fun Modifier.focusEntry(entry: FocusEntry, wanted: () -> IntArray): Modifier =
    this.focusProperties { enter = { entry.target(*wanted()) } }.focusGroup()
