package com.tjisok.newsfaces

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private var engineHolder: MosaicEngine? = null
private fun engine(): MosaicEngine = engineHolder ?: MosaicEngine(app).also { engineHolder = it }

@Composable
fun FaceScreen(zoomRequest: Int) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val settings by app.settings.collectAsStateWithLifecycle()
    val fs = settings.face
    val eng = remember { engine() }
    val frame by eng.frame.collectAsStateWithLifecycle()
    val message by eng.message.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var stillImage by remember { mutableStateOf<Bitmap?>(null) }
    var recorder by remember { mutableStateOf<MosaicRecorder?>(null) }
    var recText by remember { mutableStateOf("● Record") }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bmp = withContext(Dispatchers.IO) { app.loadBitmap(uri.toString(), 1280) }
            if (bmp != null) { eng.reset(); stillImage = bmp; withContext(Dispatchers.Default) { eng.process(bmp, still = true) } }
        }
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    // re-process a still whenever face settings change
    LaunchedEffect(fs, stillImage) { stillImage?.let { b -> withContext(Dispatchers.Default) { eng.process(b, still = true) } } }

    // zoom / pan state (unbounded)
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var lastReq by remember { mutableIntStateOf(zoomRequest) }
    LaunchedEffect(zoomRequest) { val d = zoomRequest - lastReq; lastReq = zoomRequest; if (d > 0) zoom *= 1.3f else if (d < 0) zoom /= 1.3f }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var prevCentre by remember { mutableStateOf<Offset?>(null) }

    // recording: draw every new frame into the recorder surface
    LaunchedEffect(frame, recorder) {
        val r = recorder ?: return@LaunchedEffect; val f = frame ?: return@LaunchedEffect
        r.frame { c ->
            c.drawColor(0xFF0B0B0E.toInt())
            val ex = eng.extents(f); val tc = ex[2] - ex[0]; val tr = ex[3] - ex[1]
            val cell = min(r.width / tc, r.height / tr); val ox = (r.width - tc * cell) / 2; val oy = (r.height - tr * cell) / 2
            eng.draw(c, f, cell, fs.gap, ox, oy, ex[0], ex[1])
        }
        val s = (System.currentTimeMillis() - r.startedAt) / 1000; recText = "■ %02d:%02d".format(s / 60, s % 60)
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        if (granted && stillImage == null) CameraFeed(eng, fs.mirror, lifecycle)
        // ---- mosaic canvas with gestures
        val f = frame
        Canvas(Modifier.fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var prevDist = -1f; var prevCentroid = Offset.Zero; var prevSingle: Offset? = down.position; var moved = false
                    val fr = eng.frame.value; val hit = fr?.let { hitTest(it, eng, down.position, canvasSize, zoom, pan, fs.gap) }
                    val dragHead = hit?.first; val startPos = dragHead?.let { id -> fr.faces.first { it.id == id }.pos.let { Offset(it.x, it.y) } }
                    while (true) {
                        val ev = awaitPointerEvent(); val pressed = ev.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val a = pressed[0].position; val b = pressed[1].position; val d = (a - b).getDistance(); val c = (a + b) / 2f
                            if (prevDist > 0f) { val k = (d / prevDist).coerceIn(0.2f, 5f); val nz = (zoom * k).coerceAtLeast(0.02f); val centre = Offset(size.width / 2f, size.height / 2f); val rel = c - centre; pan = (pan - rel) * (nz / zoom) + rel + (c - prevCentroid); zoom = nz }
                            prevDist = d; prevCentroid = c; prevSingle = null; moved = true; ev.changes.forEach { it.consume() }
                        } else if (pressed.size == 1) {
                            prevDist = -1f; val pos = pressed[0].position
                            prevSingle?.let { if ((pos - it).getDistance() > 4f) moved = true
                                if (dragHead != null && fr != null && startPos != null) { val total = pos - down.position; val u = cellPx(fr, eng, canvasSize) * zoom * fr.cols; eng.setPos(dragHead, startPos.x + total.x / u, startPos.y + total.y / u); if (stillImage != null) eng.frame.value = fr.copy(faces = fr.faces.map { m -> if (m.id == dragHead) FaceMosaic(m.id, m.cells, m.alpha, android.graphics.PointF(startPos.x + total.x / u, startPos.y + total.y / u)) else m }) }
                                else pan += pos - it }
                            prevSingle = pos; pressed[0].consume()
                        }
                        if (ev.changes.none { it.pressed }) break
                    }
                    if (!moved && hit != null && fr != null) { val idx = hit.second; if (idx >= 0) app.viewer.value = fr.pool.list[idx] }
                }
            }
            .onSizeChangedCompat { canvasSize = it }) {
            if (f == null) return@Canvas
            val ex = eng.extents(f); val tc = ex[2] - ex[0]; val tr = ex[3] - ex[1]
            val base = cellPx(f, eng, canvasSize)
            // keep the composition still when extents change (canvas content is centred)
            val centre = Offset((ex[0] + ex[2]) / 2f, (ex[1] + ex[3]) / 2f)
            prevCentre?.let { pc -> if (pc != centre) pan += (centre - pc) * base * zoom }
            prevCentre = centre
            val cell = base * zoom
            val ox = size.width / 2f + pan.x - tc * cell / 2f; val oy = size.height / 2f + pan.y - tr * cell / 2f
            drawIntoCanvas { c -> eng.draw(c.nativeCanvas, f, cell, fs.gap, ox, oy, ex[0], ex[1]) }
        }

        // ---- messages
        val msg = when { !granted && stillImage == null -> "Camera permission is needed. Or pick a photo below."; message.isNotEmpty() && frame == null -> message; else -> message }
        if (msg.isNotEmpty() || status.isNotEmpty()) Text(if (status.isNotEmpty()) status else msg, color = Fg, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).background(Panel.copy(alpha = .9f), RoundedCornerShape(12.dp)).padding(14.dp, 10.dp))

        // ---- bottom controls
        Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val r = recorder
                if (r == null) {
                    val fr = frame; if (fr == null) { status = "Nothing to record yet"; scope.launch { delay(2000); status = "" }; return@Button }
                    val ex = eng.extents(fr); val ar = (ex[2] - ex[0]) / (ex[3] - ex[1])
                    var w = if (ar >= 1f) 1920 else (1920 * ar).roundToInt(); var h = if (ar >= 1f) (1920 / ar).roundToInt() else 1920; w = w and 1.inv(); h = h and 1.inv()
                    try { recorder = MosaicRecorder(ctx, w, h, fs.fps) } catch (e: Exception) { status = "Cannot record: ${e.message}"; scope.launch { delay(3000); status = "" } }
                } else {
                    recorder = null; recText = "● Record"
                    scope.launch { val uri = withContext(Dispatchers.IO) { r.stop() }; status = "Saved to Movies/NewsFaces · ${r.frames} frames"; delay(3500); status = "" }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = if (recorder != null) Color(0xFFE0322F) else Panel2)) { Text(recText, color = if (recorder != null) Color.White else Color(0xFFFF6B6B)) }
            OutlinedButton(onClick = { eng.arrange(); zoom = 1f; pan = Offset.Zero; prevCentre = null; stillImage?.let { b -> scope.launch(Dispatchers.Default) { eng.process(b, still = true) } } }) { Text("Arrange", color = Fg) }
            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Photo", color = Fg) }
            if (stillImage != null) OutlinedButton(onClick = { stillImage = null; eng.reset() }) { Text("Camera", color = Fg) }
            else if (!granted) OutlinedButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera", color = Fg) }
        }
    }
}

/** css-like base cell size: fit the standard grid layout for n heads into the canvas */
private fun cellPx(f: MosaicFrame, eng: MosaicEngine, size: IntSize): Float {
    if (size.width == 0) return 4f
    val n = f.faces.size.coerceAtLeast(1); val k = ceil(sqrt(n.toDouble())).toInt().coerceAtLeast(1); val r = ceil(n / k.toDouble()).toInt(); val gu = if (n > 1) 0.06f else 0f
    val tc = (k + (k - 1) * gu) * f.cols; val tr = r * f.rows + (r - 1) * gu * f.cols
    return min(size.width * 0.94f / tc, size.height * 0.94f / tr)
}

/** returns (faceId, poolIndex) under a screen point, or null */
private fun hitTest(f: MosaicFrame, eng: MosaicEngine, p: Offset, size: IntSize, zoom: Float, pan: Offset, gap: Float): Pair<Int, Int>? {
    val ex = eng.extents(f); val tc = ex[2] - ex[0]; val tr = ex[3] - ex[1]; val cell = cellPx(f, eng, size) * zoom
    val ox = size.width / 2f + pan.x - tc * cell / 2f; val oy = size.height / 2f + pan.y - tr * cell / 2f
    val cx = (p.x - ox) / cell + ex[0]; val cy = (p.y - oy) / cell + ex[1]
    for (m in f.faces) { val x = m.pos.x * f.cols; val y = m.pos.y * f.cols
        if (cx >= x && cx < x + f.cols && cy >= y && cy < y + f.rows) { val i = (cy - y).toInt() * f.cols + (cx - x).toInt(); return m.id to (m.cells.getOrNull(i) ?: -1) } }
    return null
}

private fun Modifier.onSizeChangedCompat(f: (IntSize) -> Unit): Modifier = this.then(Modifier.onSizeChanged(f))

private fun MosaicFrame.copy(faces: List<FaceMosaic>) = MosaicFrame(cols, rows, faces, pool, time)

@Composable
private fun CameraFeed(eng: MosaicEngine, mirror: Boolean, lifecycle: androidx.lifecycle.LifecycleOwner) {
    val ctx = LocalContext.current
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        var provider: ProcessCameraProvider? = null
        val job = app.scope.launch(Dispatchers.Main) {
            try {
                provider = ProcessCameraProvider.getInstance(ctx).await()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    try {
                        if (!app.settings.value.face.freeze) {
                            val bmp = proxy.toBitmap(); val rot = proxy.imageInfo.rotationDegrees
                            val upright = if (rot != 0) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else bmp
                            eng.process(upright, still = false)
                        }
                    } catch (_: Exception) {} finally { proxy.close() }
                }
                provider!!.unbindAll()
                provider!!.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (e: Exception) { eng.message.value = "Camera unavailable: ${e.message}" }
        }
        onDispose { job.cancel(); provider?.unbindAll(); executor.shutdown() }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 72.dp).size(120.dp, 90.dp).clip(RoundedCornerShape(10.dp)))
    }
}
