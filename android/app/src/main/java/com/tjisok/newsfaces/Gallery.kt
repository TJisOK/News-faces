package com.tjisok.newsfaces

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.roundToInt

const val MAX_COLS = 40

/** Two-finger pinch anywhere over the grid changes the column count; one finger still scrolls. */
fun Modifier.pinchColumns(cols: State<Int>, onCols: (Int) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var startDist = -1f; var startCols = cols.value; var lastCols = cols.value
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = ev.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val d = (pressed[0].position - pressed[1].position).getDistance()
                if (startDist < 0f) { startDist = d; startCols = lastCols }
                else if (d > 0f) {
                    val target = (startCols / (d / startDist)).roundToInt().coerceIn(1, MAX_COLS)
                    if (target != lastCols) { lastCols = target; onCols(target) }
                }
                ev.changes.forEach { it.consume() }
            } else if (startDist >= 0f) startDist = -1f
            if (ev.changes.none { it.pressed }) break
        }
    }
}

@Composable
fun GalleryScreen(zoomRequest: Int) {
    val settings by app.settings.collectAsStateWithLifecycle()
    val photos by app.photos.collectAsStateWithLifecycle()
    val list = remember(photos, settings) { app.visibleList() }
    val colsState = rememberUpdatedState(settings.cols)
    var lastReq by remember { mutableIntStateOf(zoomRequest) }
    LaunchedEffect(zoomRequest) {
        val d = zoomRequest - lastReq; lastReq = zoomRequest
        if (d != 0) app.update { it.copy(cols = (it.cols - d).coerceIn(1, MAX_COLS)) }
    }
    val gap = settings.gap.dp
    Box(Modifier.fillMaxSize().pinchColumns(colsState) { c -> app.update { it.copy(cols = c) } }) {
        if (list.isEmpty()) Text("No photos match the current filters.", color = Muted, modifier = Modifier.align(Alignment.Center))
        LazyVerticalGrid(columns = GridCells.Fixed(settings.cols), contentPadding = PaddingValues(gap),
            horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { p -> Tile(p, settings, Modifier.animateItem()) }
        }
    }
}

@Composable
fun Tile(p: Photo, s: Settings, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Box(modifier.aspectRatio(1f / s.aspect).clip(RoundedCornerShape(s.radius.dp)).background(Panel2).clickableNoRipple { app.viewer.value = p }) {
        AsyncImage(model = ImageRequest.Builder(ctx).data(p.img).crossfade(true).build(), imageLoader = app.loader, contentDescription = p.title,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (s.strip > 0f && p.color != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(s.strip.dp).background(Color(p.color.vivid)))
    }
}

@Composable
fun Spectrum() {
    val settings by app.settings.collectAsStateWithLifecycle()
    val photos by app.photos.collectAsStateWithLifecycle()
    val list = remember(photos, settings) { app.visibleList() }
    Canvas(Modifier.fillMaxWidth().height(5.dp)) {
        if (list.isEmpty()) return@Canvas
        val w = size.width / list.size
        list.forEachIndexed { i, p -> drawRect(Color(p.color?.vivid ?: 0xFF333333.toInt()), Offset(i * w, 0f), Size(w + 0.6f, size.height)) }
    }
}
