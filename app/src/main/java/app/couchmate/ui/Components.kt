@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package app.couchmate.ui

import app.couchmate.tr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.delay
import app.couchmate.I18n
import app.couchmate.Item
import app.couchmate.Member
import app.couchmate.Program
import app.couchmate.now
import app.couchmate.nowSec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Colours – dark and compact. */
object Palette {
    val Bg = Color(0xFF0A0C10)
    val Panel = Color(0xFF11141B)
    val PanelHi = Color(0xFF1A1F29)
    val Line = Color(0x14FFFFFF)
    val Accent = Color(0xFF3D7BFF)
    val AccentSoft = Color(0x403D7BFF)
    val Text = Color(0xFFF2F4F8)
    val Dim = Color(0xFFA3ABB9)
    val Faint = Color(0xFF616A7A)
    val Live = Color(0xFFE5484D)
    val Gold = Color(0xFFF5C451)
}

fun hhmm(sec: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(sec * 1000))

/** A focusable row: transparent at rest, accent coloured when focused. */
@Composable
fun FocusRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    height: Dp = 56.dp,
    onFocus: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.AccentSoft else Color.Transparent,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Accent,
            focusedContentColor = Color.White,
            pressedContainerColor = Palette.Accent,
        ),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** A small "pill" button (tabs, seasons). */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onFocus: () -> Unit = {}) {
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.PanelHi else Color.Transparent,
            contentColor = if (selected) Palette.Text else Palette.Dim,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
    ) {
        Text(
            text,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** A round icon button. Same focus style as [Pill]. */
@Composable
fun IconPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(40.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.PanelHi else Color.Transparent,
            contentColor = if (selected) Palette.Text else Palette.Dim,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.tv.material3.Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp))
        }
    }
}

/** An image with fallback text (initials) if it is missing or does not load. */
@Composable
fun Thumb(
    url: String?,
    fallback: String,
    modifier: Modifier = Modifier,
    fit: Boolean = true,
    corner: Dp = 6.dp,
) {
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(Palette.PanelHi),
        contentAlignment = Alignment.Center,
    ) {
        // Logos (fit) get initials, posters get the whole title – always centred.
        val initials: @Composable () -> Unit = {
            Box(
                Modifier.fillMaxSize().then(
                    if (fit) Modifier
                    else Modifier.background(Brush.verticalGradient(listOf(Palette.PanelHi, Color(0xFF232A38))))
                ).padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (fit) {
                    Text(
                        fallback.filter { it.isLetterOrDigit() }.take(2).uppercase(),
                        color = Palette.Faint,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                } else {
                    Text(
                        cleanTitle(fallback),
                        color = Palette.Dim,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (url == null) initials()
        else SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.fillMaxSize().padding(if (fit) 4.dp else 0.dp),
            loading = { initials() },
            error = { initials() },
        )
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = Palette.Accent, track: Color = Color(0x33FFFFFF)) {
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

@Composable
fun LiveBadge() {
    Text(
        "LIVE",
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.background(Palette.Live, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** A clock that updates itself. */
@Composable
fun Clock(fontSize: Int = 22) {
    var t by remember { mutableLongStateOf(nowSec()) }
    LaunchedEffect(Unit) { while (true) { t = nowSec(); delay(5_000) } }
    Text(hhmm(t), fontSize = fontSize.sp, color = Palette.Text, fontWeight = FontWeight.Light, maxLines = 1, softWrap = false)
}

/** Current time in seconds, for programme progress in lists. */
@Composable
fun rememberNowSec(periodMs: Long = 30_000): Long {
    var t by remember { mutableLongStateOf(nowSec()) }
    LaunchedEffect(periodMs) { while (true) { delay(periodMs); t = nowSec() } }
    return t
}

@Composable
fun ViewersChip(connected: Boolean, members: List<Member>, error: String? = null, onClick: (() -> Unit)? = null) {
    // Compact whatever the number: up to three avatars + "Bob, Karin +13".
    val content: @Composable (Color) -> Unit = { textColor ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp)) {
            if (!connected || members.isEmpty()) {
                Box(Modifier.padding(start = 8.dp).size(8.dp).background(if (connected) Color(0xFF3DD68C) else Palette.Live, CircleShape))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                    members.take(3).forEach { Avatar(it.name, 26.dp) }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    !connected -> error ?: tr("Connecting…")
                    members.isEmpty() -> tr("Connected")
                    else -> viewersText(members)
                },
                fontSize = 14.sp,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
        }
    }
    if (onClick == null) {
        Box(Modifier.background(Palette.Panel, RoundedCornerShape(50))) { content(Palette.Dim) }
    } else {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Palette.Panel, contentColor = Palette.Dim,
                focusedContainerColor = Color.White, focusedContentColor = Color.Black,
            ),
        ) { content(androidx.tv.material3.LocalContentColor.current) }
    }
}

/** "Bob" · "Bob, Karin" · "Bob, Karin +13" */
fun viewersText(members: List<Member>): String = when (members.size) {
    0 -> ""
    1, 2 -> members.joinToString(", ") { it.name }
    else -> "${members[0].name}, ${members[1].name} +${members.size - 2}"
}

private val avatarColors = listOf(
    Color(0xFF3D7BFF), Color(0xFFE5484D), Color(0xFF3DD68C), Color(0xFFF5C451), Color(0xFFB57BFF), Color(0xFF2EC4D6), Color(0xFFFF8A4C),
)

/** A round avatar with an initial, coloured by the name (the same colour for the same person everywhere). */
@Composable
fun Avatar(name: String, size: Dp) {
    // The same calculation everywhere (unsigned 32-bit hash), so a name gets the same colour on every device.
    val color = avatarColors[((name.lowercase().hashCode().toLong() and 0xffffffffL) % avatarColors.size).toInt()]
    Box(
        Modifier.size(size).background(Palette.Bg, CircleShape).padding(2.dp).background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().take(1).uppercase(), fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}


/** A row in a channel list: number, logo, name, the programme on now and its progress. */
@Composable
fun ChannelRow(
    item: Item,
    epg: List<Program>?,
    nowSec: Long,
    playing: Boolean,
    iconUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    favorite: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val prog = epg.now(nowSec)
    FocusRow(onClick = onClick, modifier = modifier, height = 64.dp, onFocus = onFocus, selected = playing, onLongClick = onLongClick) {
        Text(
            item.number?.toString() ?: "",
            fontSize = 15.sp,
            color = Palette.Dim,
            modifier = Modifier.width(48.dp),
        )
        Thumb(iconUrl, item.title, Modifier.width(64.dp).height(38.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (favorite) {
                    Spacer(Modifier.width(8.dp))
                    Text("★", fontSize = 14.sp, color = Palette.Gold)
                }
                if (playing) {
                    Spacer(Modifier.width(8.dp))
                    Text("▶", fontSize = 13.sp)
                }
            }
            if (prog != null) {
                Text(
                    "${hhmm(prog.start)}  ${prog.title}",
                    fontSize = 14.sp,
                    color = Color(0xB3FFFFFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                ProgressLine(prog.progress(nowSec), Modifier.fillMaxWidth(), color = Color(0xCCFFFFFF), track = Color(0x22FFFFFF))
            }
        }
    }
}

/** A poster in a grid (movies, series). */
@Composable
fun PosterCard(
    title: String,
    iconUrl: String?,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    favorite: Boolean = false,
    newCount: Int = 0,
    caption: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Column(modifier) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .onFocusChanged { if (it.isFocused) onFocus() },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(8.dp)),
            ),
            colors = ClickableSurfaceDefaults.colors(containerColor = Palette.PanelHi, focusedContainerColor = Palette.PanelHi),
        ) {
            Box(Modifier.fillMaxSize()) {
                Thumb(iconUrl, title, Modifier.fillMaxSize(), fit = false, corner = 8.dp)
                if (favorite) {
                    Text(
                        "★", fontSize = 15.sp, color = Palette.Gold,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                            .background(Color(0xB30A0C10), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                if (newCount > 0) {
                    Text(
                        if (newCount == 1) tr("NEW") else tr("{n} NEW", "n" to newCount),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .background(Palette.Accent, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        if (caption != null) {
            Text(caption, fontSize = 12.sp, color = Palette.Accent, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp))
        }
        Text(
            title,
            fontSize = 13.sp,
            color = Palette.Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}


/** "Year · Genre · ★ 7.1" */
fun metaLine(year: String?, genre: String?, rating: Double?): String =
    listOfNotNull(
        year?.takeIf { it.isNotBlank() },
        genre?.takeIf { it.isNotBlank() },
        rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
    ).joinToString("  ·  ")

/** Removes typical IPTV tags from titles: "Film [PRE] [2018]" -> "Film". */
fun cleanTitle(name: String): String =
    name.replace(Regex("\\s*[\\[(][^\\])]*[\\])]"), "").replace(Regex("\\s{2,}"), " ").trim().ifEmpty { name }

/** The year from the title if there is no metadata: "Film [2018]" -> "2018". */
fun yearFromTitle(name: String): String? = Regex("[\\[(]((?:19|20)\\d{2})[\\])]").find(name)?.groupValues?.get(1)
