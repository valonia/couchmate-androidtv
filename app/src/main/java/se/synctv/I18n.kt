package se.synctv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Appens namn – ändra här för att byta namn överallt i appen. */
const val APP_NAME = "Couchmates"

enum class Lang { SV, EN }

/**
 * Språk i appen. "auto" följer TV:ns systemspråk (svenska om TV:n är på svenska, annars engelska).
 * [lang] är Compose-state, så hela gränssnittet byter språk direkt när det ändras.
 */
object I18n {
    var lang by mutableStateOf(resolve("auto"))
        private set

    fun resolve(pref: String): Lang = when (pref) {
        "sv" -> Lang.SV
        "en" -> Lang.EN
        else -> if (Locale.getDefault().language == "sv") Lang.SV else Lang.EN
    }

    fun set(pref: String) {
        lang = resolve(pref)
    }

    val locale: Locale get() = if (lang == Lang.SV) Locale("sv", "SE") else Locale.ENGLISH
}

/** Text på aktuellt språk. */
fun t(sv: String, en: String): String = if (I18n.lang == Lang.SV) sv else en
