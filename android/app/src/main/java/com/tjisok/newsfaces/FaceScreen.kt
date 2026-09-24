package com.tjisok.newsfaces

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.util.Size
import android.view.WindowManager
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
import androidx.compose.ui.graphics.Paint as CPaint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private class Hover(val photo: Photo, val rect: RectF) // rect in canvas px

@Composable
fun FaceScreen(zoomRequest: Int) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val settings by app.settings.collectAsStateWithLifecycle()
    val fs = settings.face
    val eng = app.engine
    val frame by eng.frame.collectAsStateWithLifecycle()
    val message by eng.message.collectAsStateWithLifecycle()
    val recStatus by eng.recordStatus.collectAsStateWithLifecycle()
    val hud by app.hud.collectAsStateWithLifecycle()
    val presetLabel by app.presetLabel.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val stillImage by app.still.collectAsStateWithLifecycle()
    var showUrl by remember { mutableStateOf(true) }
    var hover by remember { mutableStateOf<Hover?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) app.useStill(uri.toString())
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA); delay(7000); showUrl = false }
    LaunchedEffect(fs, stillImage) { stillImage?.let { b -> withContext(Dispatchers.Default) { eng.process(b, still = true) } } }
    var lastReq by remember { mutableIntStateOf(zoomRequest) }
    LaunchedEffect(zoomRequest) { val d = zoomRequest - lastReq; lastReq = zoomRequest; if (d != 0) app.updateFace { it.copy(cols = (it.cols / if (d > 0) 1.25f else 0.8f).roundToInt().coerceIn(2, 240)) } }
    // IMU: left/right tilt (roll) → saturation. Gravity vector in portrait: x lateral, y vertical.
    DisposableEffect(fs.imu) {
        val sm = ctx.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) { val r = Math.toDegrees(kotlin.math.atan2(-e.values[0].toDouble(), e.values[1].toDouble())).toFloat(); eng.roll += (r - eng.roll) * 0.25f }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        if (fs.imu && sensor != null) sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }
    // keep the screen on while the mosaic runs
    DisposableEffect(Unit) { val w = (ctx as? Activity)?.window; w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }

    Box(Modifier.fillMaxSize().background(Bg)) {
        if (granted && stillImage == null) CameraFeed(eng, lifecycle, showPip = hud && fs.track)

        Canvas(Modifier.fillMaxSize()
            .onSizeChanged { canvasSize = it; eng.targetW = max(1, it.width); eng.targetH = max(1, it.height) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false); down.consume()
                    val t0 = System.currentTimeMillis(); val startCols = app.settings.value.face.cols; val startScale = app.settings.value.face.headScale; val trackMode = app.settings.value.face.track
                    var d0 = -1f; var moved = false; var longPressed = false; var lastCols = startCols; var prevSingle: Offset? = down.position
                    var swipeStartX = Float.NaN; var swiped = false
                    hover = hoverAt(eng.frame.value, down.position, size, zoom, pan, false)
                    while (true) {
                        val remaining = 600 - (System.currentTimeMillis() - t0)
                        val ev = if (!moved && !longPressed && remaining > 0) withTimeoutOrNull(remaining) { awaitPointerEvent() } else awaitPointerEvent()
                        if (ev == null) { longPressed = true; hover = null; app.hud.value = !app.hud.value; continue }
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.size >= 3) { // three-finger horizontal swipe: switch preset (left = next, right = previous)
                            val cx = pressed.map { it.position.x }.average().toFloat()
                            if (swipeStartX.isNaN()) swipeStartX = cx
                            else if (!swiped && abs(cx - swipeStartX) > 140f) { swiped = true; app.applyPreset(app.presetIndex() + if (cx < swipeStartX) 1 else -1); zoom = 1f; pan = Offset.Zero }
                            moved = true; hover = null; d0 = -1f; ev.changes.forEach { it.consume() }
                        } else if (swiped) { ev.changes.forEach { it.consume() } }
                        else if (pressed.size >= 2) {
                            val d = (pressed[0].position - pressed[1].position).getDistance()
                            if (d0 < 0f) d0 = d
                            else if (d > 0f && trackMode) { val sc = (startScale * d / d0).coerceIn(0.2f, 5f); if (abs(sc - app.settings.value.face.headScale) > 0.01f) app.updateFace { it.copy(headScale = sc) } }
                            else if (d > 0f) { val target = (startCols / (d / d0)).roundToInt().coerceIn(2, 240); if (target != lastCols) { lastCols = target; app.updateFace { it.copy(cols = target) } } }
                            moved = true; hover = null; prevSingle = null; ev.changes.forEach { it.consume() }
                        } else if (pressed.size == 1) {
                            d0 = -1f; val pos = pressed[0].position
                            if ((pos - down.position).getDistance() > 10f) moved = true
                            if (!longPressed) hover = hoverAt(eng.frame.value, pos, size, zoom, pan, false)
                            prevSingle = pos; pressed[0].consume()
                        }
                        if (ev.changes.none { it.pressed }) break
                    }
                    val h = hover; hover = null
                    if (!moved && !longPressed && System.currentTimeMillis() - t0 < 350 && h != null) app.viewer.value = h.photo
                }
            }) {
            val f = frame ?: return@Canvas
            val bmp = f.bitmap; val s = size.width / bmp.width
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas; nc.save()
                nc.drawBitmap(bmp, null, RectF(0f, 0f, bmp.width * s, bmp.height * s), bmpPaint)
                nc.restore()
                hover?.let { h ->
                    val th = eng.thumbs[h.photo.id]; val r = h.rect; val grow = r.width() * 1.1f
                    val big = RectF(r.left - grow, r.top - grow, r.right + grow, r.bottom + grow)
                    shadow.setShadowLayer(18f, 0f, 8f, 0x99000000.toInt()); nc.drawRoundRect(big, 10f, 10f, shadow)
                    if (th != null) { nc.save(); nc.clipPath(android.graphics.Path().apply { addRoundRect(big, 10f, 10f, android.graphics.Path.Direction.CW) }); nc.drawBitmap(th, null, big, bmpPaint); nc.restore() }
                    else { fill.color = h.photo.color?.avg ?: 0xFF444444.toInt(); nc.drawRoundRect(big, 10f, 10f, fill) }
                    nc.drawRoundRect(big, 10f, 10f, ring)
                }
            }
        }

        // ---- messages
        val msg = when { !granted && stillImage == null -> "Camera permission is needed. Long-press for controls, or pick a photo."; recStatus.isNotEmpty() -> recStatus; else -> message }
        if (msg.isNotEmpty()) Text(msg, color = Fg, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).background(Panel.copy(alpha = .9f), RoundedCornerShape(12.dp)).padding(14.dp, 10.dp))
        if (showUrl && app.controlUrl.isNotEmpty()) Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 14.dp).background(Panel.copy(alpha = .92f), RoundedCornerShape(12.dp)).padding(14.dp, 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Control from your computer", color = Muted, fontSize = 11.sp); Text(app.controlUrl, color = Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("Long-press for controls · swipe with three fingers to switch Frame / Head / Heads", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (presetLabel.isNotEmpty()) Text(presetLabel, color = Fg, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center).background(Panel.copy(alpha = .85f), RoundedCornerShape(16.dp)).padding(26.dp, 14.dp))
        hover?.let { h -> Text("${h.photo.source} · ${h.photo.title}", color = Fg, fontSize = 12.sp, maxLines = 2, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).background(Panel.copy(alpha = .9f), RoundedCornerShape(10.dp)).padding(10.dp, 6.dp)) }

        // ---- HUD (long-press to toggle)
        if (hud) Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val recording = eng.isRecording
            Button(onClick = { if (recording) eng.stopRecording() else eng.startRecording() }, colors = ButtonDefaults.buttonColors(containerColor = if (recording) Color(0xFFE0322F) else Panel2)) { Text(if (recording) "■ Stop" else "● Record", color = if (recording) Color.White else Color(0xFFFF6B6B)) }
            OutlinedButton(onClick = { app.updateFace { it.copy(track = !it.track) }; eng.reset(); zoom = 1f; pan = Offset.Zero }) { Text(if (fs.track) "Heads" else "Frame", color = Fg) }
            if (fs.track) OutlinedButton(onClick = { eng.arrange(); zoom = 1f; pan = Offset.Zero }) { Text("Arrange", color = Fg) }
            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Photo", color = Fg) }
            if (stillImage != null) OutlinedButton(onClick = { app.still.value = null; eng.reset() }) { Text("Camera", color = Fg) }
            else if (!granted) OutlinedButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera", color = Fg) }
        }
    }
}

private val bmpPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 4f; color = android.graphics.Color.WHITE }
private val shadow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt() }
private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

/** which photo cell is under a canvas point; returns the cell's canvas rect */
private fun hoverAt(f: RenderedFrame?, p: Offset, size: IntSize, zoom: Float, pan: Offset, track: Boolean): Hover? {
    if (f == null || size.width == 0) return null
    val s = size.width.toFloat() / f.bitmap.width
    var px = p.x; var py = p.y
    if (track) { val cx = size.width / 2f; val cy = size.height / 2f; px = (px - cx - pan.x) / zoom + cx; py = (py - cy - pan.y) / zoom + cy }
    val bx = px / s; val by = py / s
    for (h in f.heads) {
        val w = f.cols * f.cell; val hh = f.rows * f.cell
        if (bx >= h.x && bx < h.x + w && by >= h.y && by < h.y + hh) {
            val ci = ((bx - h.x) / f.cell).toInt(); val cj = ((by - h.y) / f.cell).toInt(); val j = h.cells.getOrNull(cj * f.cols + ci) ?: -1
            if (j < 0 || j >= f.pool.list.size) return null
            val left = h.x + ci * f.cell; val top = h.y + cj * f.cell
            val r = RectF(left * s, top * s, (left + f.cell) * s, (top + f.cell) * s)
            if (track) { val cx = size.width / 2f; val cy = size.height / 2f; r.set((r.left - cx) * zoom + cx + pan.x, (r.top - cy) * zoom + cy + pan.y, (r.right - cx) * zoom + cx + pan.x, (r.bottom - cy) * zoom + cy + pan.y) }
            return Hover(f.pool.list[j], r)
        }
    }
    return null
}

@Composable
private fun CameraFeed(eng: MosaicEngine, lifecycle: androidx.lifecycle.LifecycleOwner, showPip: Boolean) {
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
                            val t0 = System.nanoTime()
                            val bmp = proxy.toBitmap(); val rot = proxy.imageInfo.rotationDegrees
                            eng.tPrep += (System.nanoTime() - t0) / 1e6
                            eng.process(bmp, still = false, rot = rot)
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
        AndroidView(factory = { previewView }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 12.dp, top = 12.dp).size(if (showPip) 120.dp else 1.dp, if (showPip) 90.dp else 1.dp).clip(RoundedCornerShape(10.dp)))
    }
}
