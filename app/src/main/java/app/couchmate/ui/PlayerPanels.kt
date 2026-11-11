@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package app.couchmate.ui

import app.couchmate.I18n
import app.couchmate.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import app.couchmate.Prefs
import java.util.Locale

/** A choice in the settings panel (a subtitle, an audio track, a quality …). */
data class TrackOption(val label: String, val selected: Boolean, val action: Boolean = false, val apply: () -> Unit)


private fun languageName(code: String?): String? {
    if (code.isNullOrBlank() || code == "und") return null
    val name = Locale.forLanguageTag(code).getDisplayLanguage(I18n.locale)
    return if (name.isNullOrBlank() || name == code) code.uppercase() else name.replaceFirstChar { it.uppercase(I18n.locale) }
}

private fun codecName(mime: String?): String? = when (mime) {
    MimeTypes.AUDIO_AC3 -> "AC3"
    MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3"
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
    MimeTypes.AUDIO_TRUEHD -> "TrueHD"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.APPLICATION_SUBRIP -> "SRT"
    MimeTypes.TEXT_SSA -> "ASS"
    MimeTypes.APPLICATION_PGS -> "PGS"
    MimeTypes.APPLICATION_VOBSUB -> "VobSub"
    MimeTypes.TEXT_VTT -> "WebVTT"
    MimeTypes.APPLICATION_DVBSUBS -> "DVB"
    else -> null
}

private fun trackLabel(f: Format, index: Int, type: Int): String {
    val lang = languageName(f.language)
    val name = f.label?.takeIf { it.isNotBlank() }
    val base = listOfNotNull(name, lang?.takeIf { name == null || !name.contains(it, ignoreCase = true) })
        .joinToString(" · ")
        .ifEmpty { tr("Track {n}", "n" to index + 1) }
    val extra = mutableListOf<String>()
    if (type == C.TRACK_TYPE_AUDIO) {
        codecName(f.sampleMimeType)?.let(extra::add)
        when (f.channelCount) {
            1 -> extra += "mono"
            2 -> extra += "stereo"
            6 -> extra += "5.1"
            8 -> extra += "7.1"
        }
    } else {
        if (isForced(f)) extra += tr("forced")
        // Subtitles are parsed in advance into "media3-cues" – the original format is then in codecs.
        codecName(if (f.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) f.codecs else f.sampleMimeType)?.let(extra::add)
    }
    return if (extra.isEmpty()) base else "$base   ${extra.joinToString(" · ")}"
}

/** Forced subtitles only show text when someone speaks another language. */
private fun isForced(f: Format) =
    f.selectionFlags and C.SELECTION_FLAG_FORCED != 0 ||
        f.label?.contains("forced", ignoreCase = true) == true

/** Saved choice: "sv" = full Swedish, "sv:forced" = Swedish forced, "off" = off, "" = the file's default. */
private fun subPref(f: Format) = (f.language ?: "") + if (isForced(f)) ":forced" else ""

/**
 * Picks the subtitle from the saved choice when a new video opens.
 * Prefers the same language AND the same type (full/forced) as last time.
 */
fun autoSelectSubtitle(player: ExoPlayer, tracks: Tracks, prefs: Prefs) {
    val pref = prefs.subLang
    if (pref.isBlank() || pref == "off") return
    val lang = pref.substringBefore(":")
    val wantForced = pref.endsWith(":forced")
    val candidates = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g ->
        (0 until g.length).filter { g.isTrackSupported(it) && g.getTrackFormat(it).language == lang }.map { g to it }
    }
    val pick = candidates.firstOrNull { (g, i) -> isForced(g.getTrackFormat(i)) == wantForced } ?: candidates.firstOrNull() ?: return
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .setOverrideForType(TrackSelectionOverride(pick.first.mediaTrackGroup, pick.second))
        .build()
}

/** CC is on and the video has the plugin's captions: show them (whatever the saved subtitle choice is). */
fun selectCaptions(player: ExoPlayer, tracks: Tracks) {
    val group = tracks.groups.firstOrNull { g ->
        g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.getTrackFormat(it).label == "CC" }
    }
    val b = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
    if (group != null) b.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
    player.trackSelectionParameters = b.build()
}

/** Subtitle choices: Off + every subtitle the player can show. */
fun subtitleOptions(player: ExoPlayer, tracks: Tracks, prefs: Prefs): List<TrackOption> {
    val disabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    val list = mutableListOf<TrackOption>()
    var anySelected = false
    for (g in tracks.groups) {
        if (g.type != C.TRACK_TYPE_TEXT) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)
            val selected = !disabled && g.isTrackSelected(i)
            anySelected = anySelected || selected
            // Number tracks with the same name, e.g. two Swedish: "Swedish", "Swedish (2)".
            var label = trackLabel(f, list.size, C.TRACK_TYPE_TEXT)
            val same = list.count { it.label == label || it.label.startsWith("$label (") }
            if (same > 0) label = "$label (${same + 1})"
            list += TrackOption(label, selected) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                    .setPreferredTextLanguage(f.language)
                    .build()
                prefs.subLang = subPref(f)
            }
        }
    }
    list.add(0, TrackOption(tr("Off"), !anySelected) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        prefs.subLang = "off"
    })
    return list
}

/** Audio tracks the player can play. */
fun audioOptions(player: ExoPlayer, tracks: Tracks, prefs: Prefs): List<TrackOption> {
    val list = mutableListOf<TrackOption>()
    for (g in tracks.groups) {
        if (g.type != C.TRACK_TYPE_AUDIO) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)
            list += TrackOption(trackLabel(f, list.size, C.TRACK_TYPE_AUDIO), g.isTrackSelected(i)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                    .setPreferredAudioLanguage(f.language)
                    .build()
                prefs.audioLang = f.language ?: ""
            }
        }
    }
    return list
}

/** Applies the saved choices (languages) when something new starts. */
fun applyTrackPrefs(player: ExoPlayer, prefs: Prefs) {
    val b = player.trackSelectionParameters.buildUpon()
    when (val s = prefs.subLang) {
        "off" -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        "" -> Unit
        else -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(s.substringBefore(":"))
    }
    prefs.audioLang.takeIf { it.isNotBlank() }?.let { b.setPreferredAudioLanguage(it) }
    player.trackSelectionParameters = b.build()
}

/** The stream's qualities (video variants), largest first, plus "Automatic". Kept for the next stream. */
fun qualityOptions(player: ExoPlayer, tracks: Tracks, prefs: Prefs): List<TrackOption> {
    val formats = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g.getTrackFormat(it) } }
        .filter { it.height > 0 }
        .distinctBy { it.height }
        .sortedByDescending { it.height }
    val chosen = prefs.streamHeight
    val list = mutableListOf(TrackOption(tr("Automatic"), chosen == 0 || formats.none { it.height == chosen }) {
        prefs.streamHeight = 0
        applyStreamQuality(player, prefs, true)
    })
    formats.forEachIndexed { i, f ->
        val fps = if (f.frameRate >= 49f) "${Math.round(f.frameRate)}" else ""
        val label = "${f.height}p$fps" + if (i == 0) "  (" + tr("source") + ")" else ""
        list += TrackOption(label, f.height == chosen) {
            prefs.streamHeight = f.height
            applyStreamQuality(player, prefs, true)
        }
    }
    return list
}

/** Locks a stream's quality to the chosen height (only where the plugin offers a quality choice). */
fun applyStreamQuality(player: ExoPlayer, prefs: Prefs, isStream: Boolean) {
    val h = if (isStream) prefs.streamHeight else 0
    val b = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
    if (h > 0) b.setMaxVideoSize(Int.MAX_VALUE, h).setMinVideoSize(0, h)
    else b.clearVideoSizeConstraints().setMinVideoSize(0, 0)
    player.trackSelectionParameters = b.build()
}

/** The player's settings panel from the right: the boxes the plugin chose (this TV only). */
@Composable
fun SettingsPanel(sections: List<Pair<String, List<TrackOption>>>, onClose: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    // Focus on the chosen option in the first box (or its first row).
    val focusIdx = sections.firstOrNull()?.second?.indexOfFirst { it.selected }?.coerceAtLeast(0) ?: 0
    LaunchedEffect(Unit) { delay(80); runCatching { firstFocus.requestFocus() } }

    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0x33000000)))
        Column(
            Modifier.width(460.dp).fillMaxHeight().closeOnBack(onClose)
                .background(Color(0xFF0A0C10))
                .padding(start = 24.dp, end = 32.dp, top = 32.dp, bottom = 24.dp),
        ) {
            Text(tr("Settings"), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp))
            Text(tr("Only affects this TV"), fontSize = 14.sp, color = Palette.Faint, modifier = Modifier.padding(start = 14.dp, top = 2.dp, bottom = 14.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                sections.forEachIndexed { si, (title, options) ->
                    if (si > 0) item { Spacer(Modifier.height(18.dp)) }
                    item { Header(title) }
                    itemsIndexed(options) { idx, opt ->
                        OptionRow(opt, if (si == 0 && idx == focusIdx) Modifier.focusRequester(firstFocus) else Modifier)
                    }
                }
                if (sections.isEmpty()) item {
                    Text(tr("No settings for this video"), fontSize = 14.sp, color = Palette.Faint, modifier = Modifier.padding(start = 14.dp))
                }
            }
        }
    }
}

/** Back closes the panel straight away (otherwise the first press only removes focus). */
private fun Modifier.closeOnBack(onClose: () -> Unit) = onPreviewKeyEvent {
    if (it.key != Key.Back) return@onPreviewKeyEvent false
    if (it.type == KeyEventType.KeyUp) onClose()
    true
}

@Composable
private fun Header(text: String) {
    Text(
        text.uppercase(I18n.locale), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Faint, letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun OptionRow(opt: TrackOption, modifier: Modifier) {
    FocusRow(onClick = opt.apply, selected = opt.selected, height = 52.dp, modifier = modifier) {
        Text(if (opt.selected) "✓" else if (opt.action) "›" else "", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
        Text(opt.label, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
