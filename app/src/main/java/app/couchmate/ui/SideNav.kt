@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package app.couchmate.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.couchmate.Media
import app.couchmate.Member
import app.couchmate.MenuEntry
import app.couchmate.R
import app.couchmate.tr

/** Icons missing from Compose's standard icons (Material drawings, 24×24). */
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

/** Icon for a built-in menu entry. */
fun coreIcon(core: String?): ImageVector = when (core) {
    "favorites" -> Icons.Filled.Star
    "history" -> AppIcons.History
    else -> AppIcons.LiveTv
}

private val COLLAPSED = 80.dp
private val EXPANDED = 250.dp

/** The search entry's key in the menu (always at the top). */
const val SEARCH_KEY = "core/search"

/**
 * The menu on the left. Expanded (icon + text) while focus is in it; when you move into the content the
 * text slides away and only the icons remain, so the content gets more room.
 * Search is always first and Settings always last; everything in between comes from the server, in the
 * order chosen in the admin panel.
 */
@Composable
fun SideNav(
    entries: List<MenuEntry>,
    selectedKey: String,
    onSelect: (String) -> Unit,
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
            // Coming back to the menu puts focus on the chosen entry.
            .focusProperties { enter = { selectedFocus } }
            .focusGroup()
            .padding(horizontal = 12.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Logo
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp).padding(start = 10.dp)) {
            Image(painterResource(R.drawable.ic_launcher), contentDescription = null, modifier = Modifier.size(36.dp))
            Label(expanded) {
                Row(Modifier.padding(start = 12.dp)) {
                    Text("Couch", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
                    Text("mate", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // The entries can be many (several plugins): they shrink a little to fit, and scroll if they still do not
        // (a fade at the bottom shows there is more). The bottom part stays put.
        val density = androidx.compose.ui.platform.LocalDensity.current
        var areaHeight by remember { mutableStateOf(0) }
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { areaHeight = it.height }) {
        val count = entries.size + 2
        val rowHeight = if (areaHeight == 0) 52.dp
            else ((with(density) { areaHeight.toDp() } - 4.dp * (count - 1)) / count).coerceIn(40.dp, 52.dp)
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Focus coming into the menu lands on the chosen entry – or on Search while the menu is still loading.
            val searchHoldsFocus = selectedKey == SEARCH_KEY || entries.none { it.key == selectedKey }
            NavItem(
                icon = Icons.Filled.Search, label = tr("Search"), expanded = expanded, selected = selectedKey == SEARCH_KEY,
                onClick = { onSelect(SEARCH_KEY) },
                modifier = if (searchHoldsFocus) Modifier.focusRequester(selectedFocus) else Modifier,
                height = rowHeight,
            )
            entries.forEach { e ->
                val sel = e.key == selectedKey
                NavItem(
                    icon = if (e.core != null) coreIcon(e.core) else pluginIcon(e.icon),
                    label = e.title,
                    expanded = expanded,
                    selected = sel,
                    badge = if (e.core == "favorites" && newEpisodes > 0) "$newEpisodes" else null,
                    onClick = { onSelect(e.key) },
                    modifier = if (sel) Modifier.focusRequester(selectedFocus) else Modifier,
                    height = rowHeight,
                )
            }
            NavItem(Icons.Filled.Settings, tr("Settings"), expanded, selected = false, onClick = onSettings, height = rowHeight)
        }
        if (scroll.canScrollForward) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp)
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Palette.Panel))),
            )
        }
        }

        if (media != null) {
            NavItem(Icons.Filled.PlayArrow, media.title, expanded, selected = false, onClick = onOpenPlayer, sublabel = tr("Now playing"), accent = true)
            NavItem(AppIcons.Stop, tr("Stop for everyone"), expanded, selected = false, onClick = onStop)
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
    height: androidx.compose.ui.unit.Dp = 52.dp,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(if (sublabel != null && expanded) 60.dp else height),
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

/** Who is online: avatars (and names when the menu is expanded). */
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
                        !connected -> error ?: tr("Connecting…")
                        members.isEmpty() -> tr("Connected")
                        else -> viewersText(members)
                    },
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp),
                )
            }
        }
    }
}

