package com.tjisok.newsfaces

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.abs

@Composable
fun PhotoViewer(start: Photo, onClose: () -> Unit) {
    val list = remember { app.visibleList().ifEmpty { listOf(start) } }
    val startIndex = list.indexOfFirst { it.id == start.id }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = startIndex) { list.size }
    var zoomed by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .97f))) {
        HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
            ZoomableImage(list[page]) { zoomed = it }
        }
        val p = list[pager.currentPage]
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .75f)))).padding(20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(Color(p.color?.vivid ?: 0xFF444444.toInt()), RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(6.dp))
                Text("${p.source} · ${p.sec} · ${fmtAgo(p.time)}".uppercase(), color = Color.White.copy(alpha = .7f), fontSize = 11.sp)
            }
            Text(p.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            if (p.link.isNotEmpty()) Text("Open article ↗", color = Color(0xFF7FBAFF), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp).clickableNoRipple {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.link))) } catch (_: Exception) {}
            })
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).size(40.dp).background(Color.White.copy(alpha = .12f), CircleShape)) {
            Icon(Icons.Default.Close, "Close", tint = Color.White)
        }
    }
}

/** Unbounded pinch zoom + pan; single-finger drags pass through to the pager while at 1x. */
@Composable
fun ZoomableImage(p: Photo, onZoomed: (Boolean) -> Unit) {
    var scale by remember(p.id) { mutableFloatStateOf(1f) }
    var off by remember(p.id) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(scale) { onZoomed(abs(scale - 1f) > 0.02f) }
    Box(Modifier.fillMaxSize()
        .pointerInput(p.id) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var prevDist = -1f; var prevCentroid = Offset.Zero; var prevSingle: Offset? = null
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        val a = pressed[0].position; val b = pressed[1].position
                        val d = (a - b).getDistance(); val c = (a + b) / 2f
                        if (prevDist > 0f) {
                            val k = (d / prevDist).coerceIn(0.2f, 5f); val ns = (scale * k).coerceAtLeast(0.05f)
                            val centre = Offset(size.width / 2f, size.height / 2f); val rel = c - centre
                            off = (off - rel) * (ns / scale) + rel + (c - prevCentroid); scale = ns
                        }
                        prevDist = d; prevCentroid = c; prevSingle = null
                        ev.changes.forEach { it.consume() }
                    } else if (pressed.size == 1) {
                        prevDist = -1f
                        if (scale > 1.02f) {
                            val pos = pressed[0].position
                            prevSingle?.let { off += pos - it }
                            prevSingle = pos; pressed[0].consume()
                        }
                    }
                    if (ev.changes.none { it.pressed }) break
                }
            }
        }
        .pointerInput(p.id) { detectTapGestures(onDoubleTap = { t ->
            val centre = Offset(size.width / 2f, size.height / 2f); val rel = t - centre
            if (abs(scale - 1f) > 0.05f) { scale = 1f; off = Offset.Zero } else { off = (off - rel) * 2.5f + rel; scale = 2.5f }
        }) }) {
        AsyncImage(model = p.img, imageLoader = app.loader, contentDescription = p.title, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y })
    }
}
