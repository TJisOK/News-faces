package com.tjisok.newsfaces

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class FaceTrack(val id: Int, var box: RectF, var seen: Long, val fallback: Boolean = false) {
    var assign: IntArray? = null
    var maskEma: FloatArray? = null
    var pos: PointF? = null // in head widths
}

/** pool of candidate photos + Lab coords + a quantised nearest-neighbour lookup table (3 candidates per bin) */
class Pool(val list: List<Photo>, val lab: FloatArray, val key: String) {
    val bins = 24
    val lut = IntArray(bins * bins * bins * 3) { -1 }
    fun binOf(L: Float, A: Float, B: Float): Int {
        val bi = ((L / 100f) * bins).toInt().coerceIn(0, bins - 1)
        val ai = (((A + 110f) / 220f) * bins).toInt().coerceIn(0, bins - 1)
        val bb = (((B + 110f) / 220f) * bins).toInt().coerceIn(0, bins - 1)
        return (bi * bins + ai) * bins + bb
    }
    init {
        val n = list.size
        if (n > 0) {
            val d = FloatArray(3); val idx = IntArray(3)
            for (bi in 0 until bins) for (ai in 0 until bins) for (bb in 0 until bins) {
                val L = (bi + .5f) / bins * 100f; val A = (ai + .5f) / bins * 220f - 110f; val B = (bb + .5f) / bins * 220f - 110f
                d[0] = Float.MAX_VALUE; d[1] = Float.MAX_VALUE; d[2] = Float.MAX_VALUE; idx[0] = -1; idx[1] = -1; idx[2] = -1
                for (j in 0 until n) {
                    val dl = L - lab[j * 3]; val da = A - lab[j * 3 + 1]; val db = B - lab[j * 3 + 2]; val dist = dl * dl + da * da + db * db
                    if (dist < d[2]) { if (dist < d[1]) { d[2] = d[1]; idx[2] = idx[1]; if (dist < d[0]) { d[1] = d[0]; idx[1] = idx[0]; d[0] = dist; idx[0] = j } else { d[1] = dist; idx[1] = j } } else { d[2] = dist; idx[2] = j } }
                }
                val o = ((bi * bins + ai) * bins + bb) * 3; lut[o] = idx[0]; lut[o + 1] = idx[1]; lut[o + 2] = idx[2]
            }
        }
    }
}

class HeadLayout(val id: Int, val cells: IntArray, val x: Float, val y: Float) // x,y: top-left in bitmap px
class RenderedFrame(val bitmap: Bitmap, val cols: Int, val rows: Int, val cell: Float, val heads: List<HeadLayout>, val pool: Pool, val time: Long)

class MosaicEngine(private val app: AppState) {
    private val detector = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setMinFaceSize(0.08f).build())
    private val segmenter = Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build())
    val frame = MutableStateFlow<RenderedFrame?>(null)
    val message = MutableStateFlow("")
    val thumbs = ConcurrentHashMap<String, Bitmap>()
    private val thumbPending = ConcurrentHashMap.newKeySet<String>()
    private val failedThumbs = ConcurrentHashMap.newKeySet<String>()
    var tracks: List<FaceTrack> = emptyList(); private set
    private var nextId = 1; private var lastSeen = 0L
    private var pool: Pool? = null
    private var poolPhotosRef: List<Photo>? = null; private var poolSettingsRef: Settings? = null
    val poolSize get() = pool?.list?.size ?: 0
    private var lastProcess = 0L
    @Volatile var busy = false
    // face detection + segmentation run on their own thread (~12 Hz); sampling/matching/rendering keep the camera rate
    private val mlExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var mlBusy = false; private var lastMl = 0L
    @Volatile private var latestMask: Bitmap? = null
    /** output size in px (the screen); set by the screen composable */
    @Volatile var targetW = 1080; @Volatile var targetH = 2400
    private var buffers = arrayOfNulls<Bitmap>(2); private var bufIdx = 0
    // fps measurement
    private var fpsCount = 0; private var fpsT0 = System.currentTimeMillis(); @Volatile var measuredFps = 0.0
    private var tSample = 0.0; private var tRender = 0.0; @Volatile var tPrep = 0.0
    // recording
    private var recorder: MosaicRecorder? = null
    val isRecording get() = recorder != null
    val recordingSeconds get() = recorder?.let { ((System.currentTimeMillis() - it.startedAt) / 1000).toInt() } ?: 0
    val recordStatus = MutableStateFlow("")

    fun rowsFor(cols: Int, track: Boolean) = if (track) max(1, (cols * 1.25f).roundToInt()) else max(1, (cols * targetH.toFloat() / targetW).roundToInt())
    fun latestBitmap(): Bitmap? = frame.value?.bitmap

    fun arrange() { val n = tracks.size; tracks.forEachIndexed { i, t -> t.pos = slotPos(i, n) } }
    fun setPos(id: Int, x: Float, y: Float) { tracks.firstOrNull { it.id == id }?.pos = PointF(x, y) }
    fun reset() { tracks = emptyList(); latestMask = null }
    private fun slotPos(i: Int, n: Int): PointF { val k = max(1, ceil(sqrt(n.toDouble())).toInt()); val gu = if (n > 1) 0.06f else 0f; return PointF((i % k) * (1f + gu), (i / k) * (1.25f + gu)) }
    private fun cropRect(b: RectF, pad: Float): RectF { val w = max(b.width(), b.height()) * pad; val h = w * 1.25f; val cx = b.centerX(); val cy = b.centerY() - b.height() * 0.15f; return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2) }

    fun startRecording() {
        if (recorder != null) return
        val bmp = frame.value?.bitmap ?: run { recordStatus.value = "Nothing to record yet"; return }
        var w = min(1920, bmp.width); var h = (w.toFloat() * bmp.height / bmp.width).roundToInt(); if (h > 1920) { h = 1920; w = (h.toFloat() * bmp.width / bmp.height).roundToInt() }
        w = w and 1.inv(); h = h and 1.inv()
        try { recorder = MosaicRecorder(app.ctx, w, h, app.settings.value.face.fps); recordStatus.value = "" } catch (e: Exception) { recordStatus.value = "Cannot record: ${e.message}" }
    }
    fun stopRecording() {
        val r = recorder ?: return; recorder = null
        app.scope.launch { try { r.stop(); recordStatus.value = "Saved to Movies/NewsFaces · ${r.frames} frames" } catch (e: Exception) { recordStatus.value = "Save failed: ${e.message}" }; kotlinx.coroutines.delay(3500); recordStatus.value = "" }
    }

    /** Called on the analyser thread with an upright RGBA bitmap. */
    fun process(bmp0: Bitmap, still: Boolean, rot: Int = 0) {
        val fs = app.settings.value.face
        val now = System.currentTimeMillis()
        val minGap = if (fs.fps >= 30) 0L else (800L / fs.fps.coerceIn(1, 30)) // at 30 fps take every camera frame; otherwise skip frames arriving too early
        if (!still && now - lastProcess < minGap) return
        if (busy) return
        busy = true; lastProcess = now
        try {
            val tA = System.nanoTime()
            // head tracking needs an upright bitmap for ML Kit; the whole-frame path folds the rotation into the sampling matrix instead
            val bmp = if (fs.track && rot != 0) Bitmap.createBitmap(bmp0, 0, 0, bmp0.width, bmp0.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else bmp0
            val swap = !fs.track && (rot == 90 || rot == 270)
            val upW = if (swap) bmp.height else bmp.width; val upH = if (swap) bmp.width else bmp.height
            val U = Matrix(); if (!fs.track && rot != 0) { U.setRotate(rot.toFloat()); when (rot) { 90 -> U.postTranslate(upW.toFloat(), 0f); 180 -> U.postTranslate(upW.toFloat(), upH.toFloat()); 270 -> U.postTranslate(0f, upH.toFloat()) } }
            val P = buildPool(fs)
            if (P.list.isEmpty()) { message.value = "Waiting for colour analysis…"; return }
            val cols = fs.cols.coerceIn(2, 240); val rows = rowsFor(cols, fs.track)
            val heads = ArrayList<Pair<FaceTrack, Triple<IntArray, ByteArray?, PointF>>>()
            if (fs.track) {
                if (still) mlUpdate(bmp, true, fs)
                else if (!mlBusy && now - lastMl >= 80) { mlBusy = true; lastMl = now; mlExecutor.execute { try { mlUpdate(bmp, false, fs) } catch (_: Exception) {} finally { mlBusy = false } } }
                val snapshot = tracks; val maskBmp = latestMask
                for (t in snapshot) {
                    val pos = t.pos ?: continue
                    val crop = cropRect(t.box, fs.pad)
                    val px = sample(bmp, crop, cols, rows, fs.mirror, null)
                    val alpha = if (maskBmp != null && fs.outline == "head") refineMask(sample(maskBmp, crop, cols, rows, fs.mirror, Paint().apply { color = Color.WHITE }), t, cols, rows, !still, crop) else null
                    heads.add(t to Triple(match(px, alpha, t, cols, rows, fs, P), alpha, PointF(pos.x, pos.y)))
                }
            } else {
                // whole frame → pixels: centre-crop the camera image to the screen aspect
                val ar = targetW.toFloat() / targetH; var cw = upW.toFloat(); var ch = cw / ar; if (ch > upH) { ch = upH.toFloat(); cw = ch * ar }
                val crop = RectF((upW - cw) / 2, (upH - ch) / 2, (upW + cw) / 2, (upH + ch) / 2)
                if (tracks.isEmpty() || tracks[0].id != -1) tracks = listOf(FaceTrack(-1, crop, now, fallback = true).also { it.pos = PointF(0f, 0f) })
                val t = tracks[0]; t.box = crop
                val px = sample(bmp, crop, cols, rows, fs.mirror, null, U)
                heads.add(t to Triple(match(px, null, t, cols, rows, fs, P), null, PointF(0f, 0f)))
                message.value = ""
            }
            val tB = System.nanoTime()
            for ((_, h) in heads) requestThumbs(h.first, P)
            frame.value = render(heads, cols, rows, fs, P, now)
            val tC = System.nanoTime(); tSample += (tB - tA) / 1e6; tRender += (tC - tB) / 1e6
            recorder?.let { r -> val fb = frame.value!!.bitmap; try { r.frame { c -> c.drawColor(0xFF0B0B0E.toInt()); val s = min(r.width.toFloat() / fb.width, r.height.toFloat() / fb.height); val dw = fb.width * s; val dh = fb.height * s; c.drawBitmap(fb, null, RectF((r.width - dw) / 2, (r.height - dh) / 2, (r.width + dw) / 2, (r.height + dh) / 2), paint) } } catch (_: Exception) {} }
            fpsCount++; val dt = now - fpsT0; if (dt >= 1000) { measuredFps = fpsCount * 1000.0 / dt; android.util.Log.d("NewsFaces", "fps %.1f  sample+match %.1f ms  render %.1f ms  prep %.1f ms (avg over %d)".format(measuredFps, tSample / fpsCount, tRender / fpsCount, tPrep / fpsCount, fpsCount)); tSample = 0.0; tRender = 0.0; tPrep = 0.0; fpsCount = 0; fpsT0 = now }
        } finally { busy = false }
    }

    private fun mlUpdate(bmp: Bitmap, still: Boolean, fs: FaceSettings) {
        val now = System.currentTimeMillis()
        if (tracks.isNotEmpty() && tracks[0].id == -1) tracks = emptyList()
        val image = InputImage.fromBitmap(bmp, 0)
        var dets: List<RectF> = try { Tasks.await(detector.process(image)).map { RectF(it.boundingBox) } } catch (e: Exception) { emptyList() }
        if (dets.isNotEmpty()) {
            lastSeen = now
            if (!fs.multi) dets = listOf(dets.maxByOrNull { it.width() * it.height() }!!)
            val a = if (still) 1f else 0.5f
            val used = HashSet<FaceTrack>(); val res = ArrayList<FaceTrack>()
            for (d in dets) {
                var best: FaceTrack? = null; var bd = 1e9f
                for (t in tracks) { if (t in used || t.fallback) continue; val dist = hypot(t.box.centerX() - d.centerX(), t.box.centerY() - d.centerY()); if (dist < max(d.width(), t.box.width()) * 0.8f && dist < bd) { bd = dist; best = t } }
                if (best != null) { used.add(best); val b = best.box; best.box = RectF(b.left + (d.left - b.left) * a, b.top + (d.top - b.top) * a, b.right + (d.right - b.right) * a, b.bottom + (d.bottom - b.bottom) * a); best.seen = now; res.add(best) }
                else res.add(FaceTrack(nextId++, d, now))
            }
            if (fs.multi && !still) for (t in tracks) if (t !in used && !t.fallback && now - t.seen < 600) res.add(t)
            val n = res.size; res.forEachIndexed { i, t -> if (t.pos == null) t.pos = slotPos(i, n) }
            tracks = res.sortedBy { if (fs.mirror) -it.box.centerX() else it.box.centerX() }; message.value = ""
        } else if (tracks.isEmpty() || now - lastSeen > 1500) {
            val s = min(bmp.width, bmp.height) * 0.55f
            if (tracks.isEmpty() || !tracks[0].fallback) tracks = listOf(FaceTrack(0, RectF((bmp.width - s) / 2, (bmp.height - s) / 2, (bmp.width + s) / 2, (bmp.height + s) / 2), now, fallback = true).also { it.pos = PointF(0f, 0f) })
            message.value = "No face found — centre your face in the frame."
        }
        if (fs.outline == "head") {
            try {
                val m = Tasks.await(segmenter.process(image)); val w = m.width; val h = m.height; val buf = m.buffer; buf.rewind()
                val bytes = ByteArray(w * h); for (i in bytes.indices) bytes[i] = (buf.float * 255f).toInt().coerceIn(0, 255).toByte()
                latestMask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also { it.copyPixelsFromBuffer(ByteBuffer.wrap(bytes)) }
            } catch (_: Exception) {}
        } else latestMask = null
    }

    private fun sample(src: Bitmap, crop: RectF, cols: Int, rows: Int, mirror: Boolean, paint: Paint?, upright: Matrix? = null): IntArray {
        val k = if (cols * rows > 12000) 2 else 3
        val mid = Bitmap.createBitmap(cols * k, rows * k, Bitmap.Config.ARGB_8888)
        val c = Canvas(mid); c.drawColor(if (paint == null) Color.BLACK else Color.TRANSPARENT)
        val m = Matrix(); if (upright != null) m.set(upright)
        val r = Matrix(); r.setRectToRect(crop, RectF(0f, 0f, (cols * k).toFloat(), (rows * k).toFloat()), Matrix.ScaleToFit.FILL); m.postConcat(r)
        if (mirror) m.postScale(-1f, 1f, cols * k / 2f, 0f)
        val p = paint ?: Paint(Paint.FILTER_BITMAP_FLAG); p.isFilterBitmap = true
        c.drawBitmap(src, m, p)
        val small = Bitmap.createScaledBitmap(mid, cols, rows, true)
        val out = IntArray(cols * rows); small.getPixels(out, 0, cols, 0, 0, cols, rows)
        return out
    }

    private fun refineMask(px: IntArray, t: FaceTrack, cols: Int, rows: Int, live: Boolean, crop: RectF? = null): ByteArray {
        val n = cols * rows; val cov = FloatArray(n) { (px[it] ushr 24).toFloat() }
        if (crop != null && !t.fallback) { // the person mask includes the shoulders: keep head and neck, fade out below the chin
            val chin = (t.box.bottom - crop.top) / crop.height() * rows; val neck = t.box.height() / crop.height() * rows * 0.55f
            for (y in 0 until rows) { val k = ((y - chin) / neck).coerceIn(0f, 1f); if (k > 0f) { val f = 1f - k * k; for (x in 0 until cols) cov[y * cols + x] *= f } }
        }
        val cx = (cols - 1) / 2f; val cy = (rows - 1) / 2f; val rx = cols * 0.6f; val ry = rows * 0.6f
        for (i in 0 until n) { val x = i % cols; val y = i / cols; val dx = (x - cx) / rx; val dy = (y - cy) / ry; if (dx * dx + dy * dy > 1f) cov[i] = 0f }
        val filled = cov.copyOf()
        for (y in 1 until rows - 1) for (x in 1 until cols - 1) { val i = y * cols + x; if (cov[i] < 115f) { var c = 0; if (cov[i - 1] >= 115f) c++; if (cov[i + 1] >= 115f) c++; if (cov[i - cols] >= 115f) c++; if (cov[i + cols] >= 115f) c++; if (c >= 3) filled[i] = 200f } }
        var ema = t.maskEma
        if (ema == null || ema.size != n) { ema = filled.copyOf(); t.maskEma = ema } else if (live) { for (i in 0 until n) ema[i] += (filled[i] - ema[i]) * 0.5f } else System.arraycopy(filled, 0, ema, 0, n)
        val out = ByteArray(n); for (i in 0 until n) { val tt = ((ema[i] - 60f) / 120f).coerceIn(0f, 1f); out[i] = (tt * tt * (3 - 2 * tt) * 255f).roundToInt().toByte() }
        return out
    }

    private fun buildPool(fs: FaceSettings): Pool {
        val photos = app.photos.value; val settings = app.settings.value
        val cur = pool
        if (cur != null && photos === poolPhotosRef && settings === poolSettingsRef) return cur
        val list = app.visibleList().filter { it.color != null && !it.color.fail }
        val key = fs.match + ":" + list.size + ":" + (list.firstOrNull()?.id ?: "") + ":" + (list.lastOrNull()?.id ?: "")
        poolPhotosRef = photos; poolSettingsRef = settings
        if (cur != null && cur.key == key) return cur
        val lab = FloatArray(list.size * 3); val tmp = FloatArray(3)
        list.forEachIndexed { i, p -> val c = if (fs.match == "vivid") p.color!!.vivid else p.color!!.avg; ColorMath.rgb2lab(((c shr 16) and 255).toFloat(), ((c shr 8) and 255).toFloat(), (c and 255).toFloat(), tmp); lab[i * 3] = tmp[0]; lab[i * 3 + 1] = tmp[1]; lab[i * 3 + 2] = tmp[2] }
        return Pool(list, lab, key).also { pool = it }
    }

    /** O(1) per cell via the Lab lookup table; variety and stability pick among the bin's 3 candidates and the previous choice */
    private fun match(px: IntArray, alpha: ByteArray?, t: FaceTrack, cols: Int, rows: Int, fs: FaceSettings, P: Pool): IntArray {
        val n = cols * rows; var assign = t.assign; if (assign == null || assign.size != n) { assign = IntArray(n) { -1 }; t.assign = assign }
        val np = P.list.size; val lab = P.lab; val lut = P.lut; val use = IntArray(np); val varK = fs.variety * 6f; val stab = fs.stability
        val useMask = fs.track && fs.outline == "head" && alpha != null; val oval = fs.track && (fs.outline == "oval" || (fs.outline == "head" && alpha == null))
        val cx = (cols - 1) / 2f; val cy = (rows - 1) / 2f; val rx = cols / 2f; val ry = rows / 2f
        val hsl = FloatArray(3); val rgb = FloatArray(3); val labc = FloatArray(3)
        val tr = ((fs.tint shr 16) and 255).toFloat(); val tg = ((fs.tint shr 8) and 255).toFloat(); val tb = (fs.tint and 255).toFloat()
        val adjust = fs.hue != 0f || fs.sat != 1f || fs.bright != 0f || fs.contrast != 1f
        fun dist(j: Int, L: Float, A: Float, B: Float): Float { val dl = L - lab[j * 3]; val da = A - lab[j * 3 + 1]; val db = B - lab[j * 3 + 2]; var d = dl * dl + da * da + db * db; if (varK > 0f) { val pen = varK * use[j]; d += pen * pen }; return d }
        for (i in 0 until n) {
            if (useMask) { if ((alpha!![i].toInt() and 255) < 40) { assign[i] = -1; continue } }
            else if (oval) { val x = i % cols; val y = i / cols; val dx = (x - cx) / rx; val dy = (y - cy) / ry; if (dx * dx + dy * dy > 1f) { assign[i] = -1; continue } }
            val c = px[i]; var r = ((c shr 16) and 255).toFloat(); var g = ((c shr 8) and 255).toFloat(); var b = (c and 255).toFloat()
            if (adjust) { ColorMath.rgb2hsl(r.toInt(), g.toInt(), b.toInt(), hsl); ColorMath.hsl2rgb(hsl[0] + fs.hue, (hsl[1] * fs.sat).coerceIn(0f, 1f), ((hsl[2] - .5f) * fs.contrast + .5f + fs.bright).coerceIn(0f, 1f), rgb); r = rgb[0]; g = rgb[1]; b = rgb[2] }
            if (fs.tintAmt > 0f) { r += (tr - r) * fs.tintAmt; g += (tg - g) * fs.tintAmt; b += (tb - b) * fs.tintAmt }
            ColorMath.rgb2lab(r, g, b, labc); val L = labc[0]; val A = labc[1]; val B = labc[2]
            val o = P.binOf(L, A, B) * 3
            var best = -1; var bd = Float.MAX_VALUE
            for (k in 0 until 3) { val j = lut[o + k]; if (j < 0) continue; val d = dist(j, L, A, B); if (d < bd) { bd = d; best = j } }
            val prev = assign[i]
            if (prev in 0 until np && prev != best && best >= 0) { val d = dist(prev, L, A, B); if (stab > 0f && d <= bd * (1 + stab) + stab * stab * 300f) best = prev else if (d < bd) best = prev }
            assign[i] = best; if (best >= 0) use[best]++
        }
        return assign
    }

    private fun requestThumbs(cells: IntArray, P: Pool) {
        var budget = 32
        for (j in cells) {
            if (j < 0 || j >= P.list.size) continue; val p = P.list[j]
            if (thumbs.containsKey(p.id) || failedThumbs.contains(p.id) || !thumbPending.add(p.id)) continue
            if (--budget < 0) { thumbPending.remove(p.id); break }
            app.scope.launch {
                val b = try { app.loadBitmap(p.img, 64) } catch (_: Exception) { null }
                if (b != null) { val s = min(b.width, b.height); thumbs[p.id] = Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s) } else failedThumbs.add(p.id)
                thumbPending.remove(p.id)
            }
        }
    }

    // ---------------- offscreen rendering (analyser thread) → one bitmap per frame
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    // Compositing 4000+ cells through Canvas.drawBitmap costs ~8 µs each in software. Instead the frame is assembled in an int
    // buffer with row copies of thumbnails pre-scaled to the exact cell size, then uploaded with one setPixels call.
    private val scaledPx = HashMap<String, IntArray>(); private var scaledSize = -1
    private var frameInts = IntArray(0)
    private fun scaledPixels(id: String, th: Bitmap, sz: Int): IntArray {
        if (sz != scaledSize) { scaledPx.clear(); scaledSize = sz }
        return scaledPx.getOrPut(id) { val b = Bitmap.createScaledBitmap(th, sz, sz, true); IntArray(sz * sz).also { b.getPixels(it, 0, sz, 0, 0, sz, sz) } }
    }

    private fun render(heads: List<Pair<FaceTrack, Triple<IntArray, ByteArray?, PointF>>>, cols: Int, rows: Int, fs: FaceSettings, P: Pool, now: Long): RenderedFrame {
        val W = min(targetW, 1080); val H = (W.toFloat() * targetH / targetW).roundToInt().coerceAtLeast(1)
        var bmp = buffers[bufIdx]; if (bmp == null || bmp.width != W || bmp.height != H) { bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); buffers[bufIdx] = bmp }
        bufIdx = 1 - bufIdx
        if (frameInts.size != W * H) frameInts = IntArray(W * H)
        val buf = frameInts; java.util.Arrays.fill(buf, 0xFF0B0B0E.toInt())
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for ((_, h) in heads) { val x = h.third.x * cols; val y = h.third.y * cols; minX = min(minX, x); minY = min(minY, y); maxX = max(maxX, x + cols); maxY = max(maxY, y + rows) }
        val tc = maxX - minX; val tr = maxY - minY
        val cell = if (!fs.track) W.toFloat() / cols else if (heads.size == 1) max(W * 0.94f / tc, H * 0.94f / tr) else min(W * 0.94f / tc, H * 0.94f / tr)
        val ox = (W - tc * cell) / 2; val oy = (H - tr * cell) / 2
        val g = cell * fs.gap; val inner = cell - g; val sz = inner.roundToInt().coerceAtLeast(1); val fastPath = sz <= 96
        val layouts = ArrayList<HeadLayout>(); val soft = ArrayList<Triple<Photo, RectF, Float>>()
        for ((t, h) in heads) {
            val cells = h.first; val alpha = h.second
            val fx = ox + (h.third.x * cols - minX) * cell; val fy = oy + (h.third.y * cols - minY) * cell
            for (i in cells.indices) {
                val j = cells[i]; if (j < 0 || j >= P.list.size) continue
                val p = P.list[j]; val al = if (alpha != null) (alpha[i].toInt() and 255) / 255f else 1f
                val x = fx + (i % cols) * cell + g / 2; val y = fy + (i / cols) * cell + g / 2
                val th = thumbs[p.id]
                if (al >= 1f && fastPath) {
                    val x0 = x.roundToInt(); val y0 = y.roundToInt()
                    val cx0 = max(0, x0); val cx1 = min(W, x0 + sz); val cy0 = max(0, y0); val cy1 = min(H, y0 + sz)
                    if (cx1 <= cx0 || cy1 <= cy0) continue
                    if (th != null) { val src = scaledPixels(p.id, th, sz); for (yy in cy0 until cy1) System.arraycopy(src, (yy - y0) * sz + (cx0 - x0), buf, yy * W + cx0, cx1 - cx0) }
                    else { val col = p.color?.avg ?: Color.DKGRAY; for (yy in cy0 until cy1) java.util.Arrays.fill(buf, yy * W + cx0, yy * W + cx1, col) }
                } else { val s2 = inner * (0.55f + 0.45f * al); soft.add(Triple(p, RectF(x + (inner - s2) / 2, y + (inner - s2) / 2, x + (inner + s2) / 2, y + (inner + s2) / 2), al)) }
            }
            layouts.add(HeadLayout(t.id, cells.copyOf(), fx, fy))
        }
        bmp.setPixels(buf, 0, W, 0, 0, W, H)
        if (soft.isNotEmpty()) { // soft-edged cells of the head outline, and very large cells: the ordinary canvas path
            val c = Canvas(bmp); val src = Rect()
            for ((p, dst, al) in soft) { val th = thumbs[p.id]; if (th != null) { paint.alpha = (al * 255).roundToInt(); src.set(0, 0, th.width, th.height); c.drawBitmap(th, src, dst, paint) } else { fill.color = p.color?.avg ?: Color.DKGRAY; fill.alpha = (al * 255).roundToInt(); c.drawRect(dst, fill) } }
        }
        return RenderedFrame(bmp, cols, rows, cell, layouts, P, now)
    }

    fun close() { detector.close(); segmenter.close(); stopRecording(); mlExecutor.shutdown() }
}
