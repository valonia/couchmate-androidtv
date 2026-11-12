package app.couchmate.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Skeletons: grey shapes where the content will be, pulsing gently while it loads – calmer than a "Loading…"
// text, even when it only shows for a moment. Each one takes the shape of what is coming (rows, cards, posters).

private val Bone = Color(0x1FFFFFFF)

/** A gentle pulse shared by everything drawn inside it. */
@Composable
private fun pulse(): Float {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    return a
}

@Composable
private fun Bone(modifier: Modifier, radius: Dp = 8.dp) {
    Box(modifier.background(Bone, RoundedCornerShape(radius)))
}

/** Rows like a list of channels, episodes or history: a picture and two lines of text. */
@Composable
fun SkeletonRows(modifier: Modifier = Modifier, count: Int = 6, height: Dp = 64.dp, picture: Boolean = true) {
    Column(modifier.alpha(pulse()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Row(Modifier.fillMaxWidth().height(height).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (picture) {
                    Bone(Modifier.width(height * 1.5f).height(height - 18.dp), 6.dp)
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Slightly different lengths, so it looks like text.
                    Bone(Modifier.fillMaxWidth(listOf(0.55f, 0.4f, 0.65f, 0.48f)[i % 4]).height(14.dp), 4.dp)
                    Bone(Modifier.fillMaxWidth(listOf(0.3f, 0.36f, 0.25f, 0.33f)[i % 4]).height(10.dp), 4.dp)
                }
            }
        }
    }
}

/** A row of cards (16:9) or posters (2:3) – what a horizontal list will hold. */
@Composable
fun SkeletonCards(modifier: Modifier = Modifier, count: Int = 6, width: Dp = 240.dp, aspect: Float = 16f / 9f, gap: Dp = 16.dp) {
    Row(modifier.alpha(pulse()), horizontalArrangement = Arrangement.spacedBy(gap)) {
        repeat(count) { Bone(Modifier.width(width).aspectRatio(aspect), 12.dp) }
    }
}

/** A wall of posters, like Movies or Series. */
@Composable
fun SkeletonGrid(modifier: Modifier = Modifier, rows: Int = 2, columns: Int = 6, aspect: Float = 2f / 3f) {
    Column(modifier.alpha(pulse()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(columns) { Bone(Modifier.weight(1f).aspectRatio(aspect), 8.dp) }
            }
        }
    }
}

/** A whole page that is on its way: a heading, a row of cards and a few rows. */
@Composable
fun SkeletonPage(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(start = 8.dp, top = 12.dp).alpha(pulse()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Bone(Modifier.width(260.dp).height(30.dp), 6.dp)
        Bone(Modifier.width(180.dp).height(16.dp), 4.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(5) { Bone(Modifier.width(260.dp).aspectRatio(16f / 9f), 12.dp) }
        }
        Bone(Modifier.width(180.dp).height(16.dp), 4.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(5) { Bone(Modifier.width(260.dp).aspectRatio(16f / 9f), 12.dp) }
        }
    }
}

/** A couple of lines of text (a small block whose data is on its way). */
@Composable
fun SkeletonLines(modifier: Modifier = Modifier, lines: Int = 2) {
    Column(modifier.alpha(pulse()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(lines) { i -> Bone(Modifier.fillMaxWidth(if (i == 0) 0.6f else 0.4f).height(12.dp), 4.dp) }
    }
}
