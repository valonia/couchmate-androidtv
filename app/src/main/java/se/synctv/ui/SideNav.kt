@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package se.synctv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import se.synctv.Media
import se.synctv.Member
import se.synctv.R
import se.synctv.t

/** Ikoner som saknas i Compose standardikoner (Material-ritningar, 24×24). */
object AppIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
            .build()

    val LiveTv = icon("LiveTv", "M21 6h-7.59l3.29-3.29L16 2l-4 4-4-4-.71.71L10.59 6H3c-1.1 0-2 .89-2 2v12c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V8c0-1.11-.9-2-2-2zm0 14H3V8h18v12zM9 10v8l7-4z")
    val Movie = icon("Movie", "M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z")
    val Series = icon("Series", "M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8 12.5v-9l6 4.5-6 4.5z")
    val History = icon("History", "M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.954 8.954 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z")
    val Stop = icon("Stop", "M6 6h12v12H6z")
}

fun Section.icon(): ImageVector = when (this) {
    Section.LIVE -> AppIcons.LiveTv
    Section.MOVIES -> AppIcons.Movie
    Section.SERIES -> AppIcons.Series
    Section.FAVORITES -> Icons.Filled.Star
    Section.HISTORY -> AppIcons.History
    Section.SEARCH -> Icons.Filled.Search
    Section.SOURCE -> AppIcons.LiveTv
}

private val COLLAPSED = 80.dp
private val EXPANDED = 250.dp

/**
 * Menyn till vänster. Utfälld (ikon + text) när fokus är i menyn; när man går in i innehållet
 * glider texten bort och bara ikonerna syns, så att innehållet får mer plats.
 */
@Composable
fun SideNav(
    section: Section,
    onSelect: (Section) -> Unit,
    sources: List<se.synctv.Source> = emptyList(),
    selectedSource: String? = null,
    onSelectSource: (se.synctv.Source) -> Unit = {},
    newEpisodes: Int,
    media: Media?,
    onOpenPlayer: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    connected: Boolean,
    members: List<Member>,
    error: String?,
    onMembers: () -> Unit,
    selectedFocus: FocusRequester,
    onRailFocus: (Boolean) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) EXPANDED else COLLAPSED, tween(220), label = "nav")
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(Palette.Panel)
            .onFocusChanged { expanded = it.hasFocus; onRailFocus(it.hasFocus) }
            // Kommer man tillbaka till menyn hamnar fokus på den valda fliken.
            .focusProperties { enter = { selectedFocus } }
            .focusGroup()
            .padding(horizontal = 12.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Logga
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp).padding(start = 10.dp)) {
            Image(painterResource(R.drawable.ic_launcher), contentDescription = null, modifier = Modifier.size(36.dp))
            Label(expanded) {
                Row(Modifier.padding(start = 12.dp)) {
                    Text("Couch", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
                    Text("mates", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        listOf(Section.SEARCH, Section.LIVE, Section.MOVIES, Section.SERIES, Section.FAVORITES, Section.HISTORY).forEach { s ->
            // Källor från plugins (t.ex. Twitch) ligger mellan Favoriter och Historik.
            if (s == Section.HISTORY) sources.forEach { src ->
                val sel = selectedSource == src.id
                NavItem(
                    icon = sourceIcon(src.iconPath),
                    label = src.name,
                    expanded = expanded,
                    selected = sel,
                    onClick = { onSelectSource(src) },
                    modifier = if (sel) Modifier.focusRequester(selectedFocus) else Modifier,
                )
            }
            NavItem(
                icon = s.icon(),
                label = s.label,
                expanded = expanded,
                selected = s == section,
                badge = if (s == Section.FAVORITES && newEpisodes > 0) "$newEpisodes" else null,
                onClick = { onSelect(s) },
                modifier = if (s == section) Modifier.focusRequester(selectedFocus) else Modifier,
            )
        }
        NavItem(Icons.Filled.Settings, t("Inställningar", "Settings"), expanded, selected = false, onClick = onSettings)

        Spacer(Modifier.weight(1f))

        if (media != null) {
            NavItem(Icons.Filled.PlayArrow, media.title, expanded, selected = false, onClick = onOpenPlayer, sublabel = t("Nu spelas", "Now playing"), accent = true)
            NavItem(AppIcons.Stop, t("Stoppa för alla", "Stop for everyone"), expanded, selected = false, onClick = onStop)
            Spacer(Modifier.height(6.dp))
        }
        Members(connected, members, error, expanded, onMembers)
        Box(Modifier.fillMaxWidth().padding(start = 10.dp, top = 8.dp)) { Clock(fontSize = if (expanded) 22 else 16) }
    }
}

@Composable
private fun Label(expanded: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = expanded, enter = fadeIn(tween(180, delayMillis = 60)), exit = fadeOut(tween(100))) { content() }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    sublabel: String? = null,
    accent: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(if (sublabel != null && expanded) 60.dp else 52.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when { selected -> Palette.AccentSoft; accent -> Color(0x223D7BFF); else -> Color.Transparent },
            contentColor = if (selected || accent) Palette.Text else Palette.Dim,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
    ) {
        Row(Modifier.fillMaxHeight().padding(start = 16.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
                if (badge != null && !expanded) {
                    Box(Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-4).dp).size(10.dp).background(Palette.Live, CircleShape))
                }
            }
            Label(expanded) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                    Column(Modifier.weight(1f, fill = false)) {
                        if (sublabel != null) Text(sublabel.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Palette.Accent)
                        Text(label, fontSize = 17.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            badge, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.background(Palette.Live, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Vilka som är online: avatarer (och namn när menyn är utfälld). */
@Composable
private fun Members(connected: Boolean, members: List<Member>, error: String?, expanded: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = Palette.Dim,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black,
        ),
    ) {
        Row(Modifier.fillMaxHeight().padding(start = 12.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                when {
                    !connected || members.isEmpty() ->
                        Box(Modifier.padding(start = 10.dp).size(10.dp).background(if (connected) Color(0xFF3DD68C) else Palette.Live, CircleShape))
                    else -> Box {
                        Avatar(members.first().name, 30.dp)
                        if (members.size > 1) {
                            Text(
                                "${members.size}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
                                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 4.dp)
                                    .background(Palette.Accent, RoundedCornerShape(50)).padding(horizontal = 5.dp),
                            )
                        }
                    }
                }
            }
            Label(expanded) {
                Text(
                    when {
                        !connected -> error ?: t("Ansluter…", "Connecting…")
                        members.isEmpty() -> t("Ansluten", "Connected")
                        else -> viewersText(members)
                    },
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp),
                )
            }
        }
    }
}

