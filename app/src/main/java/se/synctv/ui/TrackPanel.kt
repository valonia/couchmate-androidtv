@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package se.synctv.ui

import se.synctv.t
import se.synctv.I18n
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
import se.synctv.Prefs
import java.util.Locale

/** Ett val i panelen (undertext eller ljudspår). */
data class TrackOption(val label: String, val selected: Boolean, val apply: () -> Unit)


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
        .ifEmpty { t("Spår ${index + 1}", "Track ${index + 1}") }
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
        if (isForced(f)) extra += t("tvingad", "forced")
        // Undertexter tolkas i förväg till "media3-cues" – originalformatet ligger då i codecs.
        codecName(if (f.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) f.codecs else f.sampleMimeType)?.let(extra::add)
    }
    return if (extra.isEmpty()) base else "$base   ${extra.joinToString(" · ")}"
}

/** Forced-undertexter visar bara text när någon pratar ett annat språk. */
private fun isForced(f: Format) =
    f.selectionFlags and C.SELECTION_FLAG_FORCED != 0 ||
        f.label?.contains("forced", ignoreCase = true) == true ||
        f.label?.contains("tvingad", ignoreCase = true) == true

/** Sparat val: "sv" = fullständiga svenska, "sv:forced" = svenska forced, "off" = av, "" = filens standard. */
private fun subPref(f: Format) = (f.language ?: "") + if (isForced(f)) ":forced" else ""

/**
 * Väljer undertext enligt sparat val när en ny video öppnas.
 * Föredrar samma språk OCH samma typ (fullständig/forced) som man valde senast.
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

/** Undertextval: t("Av", "Off") + alla undertexter som spelaren klarar. */
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
            // Numrera spår med samma namn, t.ex. två svenska: "Svenska", "Svenska (2)".
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
    list.add(0, TrackOption(t("Av", "Off"), !anySelected) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        prefs.subLang = "off"
    })
    return list
}

/** Ljudspår som spelaren klarar. */
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

/** Använd sparade val (språk) när något nytt startar. */
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

/** Panel från höger: välj undertext och ljudspår (gäller bara den här TV:n). */
@Composable
fun TrackPanel(subs: List<TrackOption>, audio: List<TrackOption>) {
    val firstFocus = remember { FocusRequester() }
    val focusIdx = subs.indexOfFirst { it.selected }.coerceAtLeast(0)
    LaunchedEffect(Unit) { delay(80); runCatching { firstFocus.requestFocus() } }

    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0x33000000)))
        Column(
            Modifier.width(560.dp).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Color(0xE60A0C10), Color(0xF50A0C10))))
                .padding(start = 24.dp, end = 32.dp, top = 32.dp, bottom = 24.dp),
        ) {
            Text(t("Ljud & undertext", "Audio & subtitles"), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp))
            Text(t("Gäller bara den här TV:n", "Only affects this TV"), fontSize = 14.sp, color = Palette.Faint, modifier = Modifier.padding(start = 14.dp, top = 2.dp, bottom = 14.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item { Header(t("Undertext", "Subtitles")) }
                itemsIndexed(subs) { idx, opt ->
                    OptionRow(opt, if (idx == focusIdx) Modifier.focusRequester(firstFocus) else Modifier)
                }
                if (subs.size == 1) item {
                    Text(t("Den här videon har inga undertexter", "This video has no subtitles"), fontSize = 14.sp, color = Palette.Faint, modifier = Modifier.padding(start = 14.dp, top = 4.dp))
                }
                item { Spacer(Modifier.height(18.dp)); Header(t("Ljud", "Audio")) }
                if (audio.isEmpty()) item {
                    Text(t("Inga ljudspår att välja", "No audio tracks to choose from"), fontSize = 14.sp, color = Palette.Faint, modifier = Modifier.padding(start = 14.dp))
                }
                itemsIndexed(audio) { _, opt -> OptionRow(opt, Modifier) }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Faint, letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun OptionRow(opt: TrackOption, modifier: Modifier) {
    FocusRow(onClick = opt.apply, selected = opt.selected, height = 52.dp, modifier = modifier) {
        Text(if (opt.selected) "✓" else "", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
        Text(opt.label, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
