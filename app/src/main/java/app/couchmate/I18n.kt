package app.couchmate

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale

/** The app's name – shown in the menu, on the setup screen and under the icon. */
const val APP_NAME = "Couchmate"

/**
 * Translation. Everything in the app is written in English and passed through [tr]. The Couchmate server
 * translates it into the TV's language (or the one chosen in Settings) and every translation is kept on
 * the TV, so nothing is translated twice. Until a translation is there, the English text is shown.
 * Text from plugins arrives already translated by the server.
 */
object I18n {
    /** null = English (nothing to translate). Compose state, so the whole UI switches straight away. */
    var lang by mutableStateOf<String?>(null)
        private set

    private val dict = mutableStateMapOf<String, String>()
    private val pending = LinkedHashSet<String>()
    private val tries = HashMap<String, Int>()
    private var prefs: android.content.SharedPreferences? = null

    /** The language to use: the TV's own language, unless the user chose English. */
    fun resolve(pref: String): String? {
        if (pref == "en") return null
        val sys = Locale.getDefault().language.lowercase()
        return if (sys.isBlank() || sys == "en") null else sys
    }

    fun configure(context: Context, pref: String) {
        val l = resolve(pref)
        prefs = context.getSharedPreferences("couchmate_i18n", Context.MODE_PRIVATE)
        dict.clear()
        synchronized(pending) { pending.clear(); tries.clear() }
        if (l != null) {
            runCatching {
                val o = JSONObject(prefs?.getString(l, null) ?: "{}")
                for (k in o.keys()) dict[k] = o.getString(k)
            }
        }
        lang = l
    }

    /** Locale for dates and language names. */
    val locale: Locale get() = lang?.let { Locale(it) } ?: Locale.ENGLISH

    fun translate(text: String): String {
        if (lang == null || text.isBlank()) return text
        dict[text]?.let { return it }
        synchronized(pending) { pending.add(text) }
        return text
    }

    /**
     * Sends texts without a translation to the server in batches. Texts the server could not translate yet
     * (it may still be downloading a language) are asked for again later, a few times.
     */
    suspend fun pump(api: Api) {
        while (true) {
            delay(400)
            val l = lang ?: continue
            val batch = synchronized(pending) { pending.toList().also { pending.clear() } }
            if (batch.isEmpty()) continue
            val res = runCatching { api.translate(batch, l) }.getOrNull()
            if (res == null) {
                synchronized(pending) { pending.addAll(batch) }
                delay(15_000)
                continue
            }
            if (lang != l) continue
            val (translations, complete) = res
            val retry = mutableListOf<String>()
            for (t in batch) {
                val v = translations[t]
                if (v != null && (complete || v != t)) dict[t] = v
                else synchronized(pending) { if ((tries.merge(t, 1, Int::plus) ?: 0) < 6) retry += t }
            }
            save(l)
            if (retry.isNotEmpty()) {
                delay(20_000)
                synchronized(pending) { pending.addAll(retry) }
            }
        }
    }

    private fun save(l: String) {
        val o = JSONObject()
        for ((k, v) in dict) o.put(k, v)
        prefs?.edit()?.putString(l, o.toString())?.apply()
    }
}

/**
 * English text, translated into the TV's language. Placeholders: tr("{n} connected", "n" to 3).
 * Never pass names or content (channel names, titles) through here – only the app's own words.
 */
fun tr(text: String, vararg vars: Pair<String, Any?>): String {
    var s = I18n.translate(text)
    for ((k, v) in vars) s = s.replace("{$k}", v?.toString() ?: "")
    return s
}
