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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class FaceTrack(val id: Int, var box: RectF, var seen: Long, val fallback: Boolean = false) {
    var assign: IntArray? = null
    var maskEma: FloatArray? = null
    var pos: PointF? = null // in head widths: x=1 is one head to the right
}

class Pool(val list: List<Photo>, val lab: FloatArray, val key: String)
class FaceMosaic(val id: Int, val cells: IntArray, val alpha: ByteArray?, val pos: PointF)
class MosaicFrame(val cols: Int, val rows: Int, val faces: List<FaceMosaic>, val pool: Pool, val time: Long)

class MosaicEngine(private val app: AppState) {
    private val detector = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setMinFaceSize(0.08f).build())
    private val segmenter = Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build())
    val frame = MutableStateFlow<MosaicFrame?>(null)
    val message = MutableStateFlow("")
    val thumbs = ConcurrentHashMap<String, Bitmap>()
    private val thumbPending = ConcurrentHashMap.newKeySet<String>()
    private val failedThumbs = ConcurrentHashMap.newKeySet<String>()
    var tracks: List<FaceTrack> = emptyList(); private set
    private var nextId = 1; private var lastSeen = 0L
    private var pool: Pool? = null
    private var lastProcess = 0L
    @Volatile var busy = false

    fun rowsFor(cols: Int) = max(1, (cols * 1.25f).roundToInt())

    fun arrange() { val n = tracks.size; tracks.forEachIndexed { i, t -> t.pos = slotPos(i, n) } }
    fun setPos(id: Int, x: Float, y: Float) { tracks.firstOrNull { it.id == id }?.pos = PointF(x, y) }
    fun reset() { tracks = emptyList() }

    private fun slotPos(i: Int, n: Int): PointF { val k = max(1, ceil(sqrt(n.toDouble())).toInt()); val gu = if (n > 1) 0.06f else 0f; return PointF((i % k) * (1f + gu), (i / k) * (1.25f + gu)) }

    private fun cropRect(b: RectF, pad: Float): RectF { val w = max(b.width(), b.height()) * pad; val h = w * 1.25f; val cx = b.centerX(); val cy = b.centerY() - b.height() * 0.15f; return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2) }

    /** Called on the analyser thread with an upright RGBA bitmap. */
    fun process(bmp: Bitmap, still: Boolean) {
        val fs = app.settings.value.face
        val now = System.currentTimeMillis()
        if (!still && now - lastProcess < 1000L / fs.fps.coerceIn(1, 30)) return
        if (busy) return
        busy = true; lastProcess = now
        try {
            val image = InputImage.fromBitmap(bmp, 0)
            // ---- faces
            var dets: List<RectF> = try { Tasks.await(detector.process(image)).map { RectF(it.boundingBox) } } catch (e: Exception) { emptyList() }
            if (dets.isNotEmpty()) {
                lastSeen = now
                if (!fs.multi) dets = listOf(dets.maxByOrNull { it.width() * it.height() }!!)
                val a = if (still) 1f else 0.35f
                val used = HashSet<FaceTrack>(); val out = ArrayList<FaceTrack>()
                for (d in dets) {
                    var best: FaceTrack? = null; var bd = 1e9f
                    for (t in tracks) { if (t in used || t.fallback) continue; val dist = hypot(t.box.centerX() - d.centerX(), t.box.centerY() - d.centerY()); if (dist < max(d.width(), t.box.width()) * 0.8f && dist < bd) { bd = dist; best = t } }
                    if (best != null) { used.add(best); val b = best.box; best.box = RectF(b.left + (d.left - b.left) * a, b.top + (d.top - b.top) * a, b.right + (d.right - b.right) * a, b.bottom + (d.bottom - b.bottom) * a); best.seen = now; out.add(best) }
                    else out.add(FaceTrack(nextId++, d, now))
                }
                if (fs.multi && !still) for (t in tracks) if (t !in used && !t.fallback && now - t.seen < 600) out.add(t)
                tracks = out; message.value = ""
            } else if (tracks.isEmpty() || now - lastSeen > 1500) {
                val s = min(bmp.width, bmp.height) * 0.55f
                if (tracks.isEmpty() || !tracks[0].fallback) tracks = listOf(FaceTrack(0, RectF((bmp.width - s) / 2, (bmp.height - s) / 2, (bmp.width + s) / 2, (bmp.height + s) / 2), now, fallback = true))
                message.value = "No face found — centre your face in the frame."
            }
            tracks = tracks.sortedBy { if (fs.mirror) -it.box.centerX() else it.box.centerX() }
            val n = tracks.size; tracks.forEachIndexed { i, t -> if (t.pos == null) t.pos = slotPos(i, n) }

            // ---- person mask (for the head outline)
            var maskBmp: Bitmap? = null
            if (fs.outline == "head") {
                try {
                    val m = Tasks.await(segmenter.process(image))
                    val w = m.width; val h = m.height; val buf = m.buffer; buf.rewind()
                    val bytes = ByteArray(w * h); for (i in bytes.indices) bytes[i] = (buf.float * 255f).toInt().coerceIn(0, 255).toByte()
                    maskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also { it.copyPixelsFromBuffer(ByteBuffer.wrap(bytes)) }
                } catch (_: Exception) {}
            }

            // ---- pool
            val P = buildPool(fs)
            if (P.list.isEmpty()) { message.value = "Waiting for colour analysis…"; return }

            // ---- sample + match each head
            val cols = fs.cols; val rows = rowsFor(cols)
            val faces = ArrayList<FaceMosaic>()
            for (t in tracks) {
                val crop = cropRect(t.box, fs.pad)
                val px = sample(bmp, crop, cols, rows, fs.mirror, null)
                val alpha = if (maskBmp != null) refineMask(sample(maskBmp, crop, cols, rows, fs.mirror, Paint().apply { color = Color.WHITE }), t, cols, rows, !still) else null
                val cells = match(px, alpha, t, cols, rows, fs, P)
                faces.add(FaceMosaic(t.id, cells.copyOf(), alpha, PointF(t.pos!!.x, t.pos!!.y))) // snapshot: the track keeps mutating its array
                requestThumbs(cells, P)
            }
            frame.value = MosaicFrame(cols, rows, faces, P, now)
        } finally { busy = false }
    }

    private fun sample(src: Bitmap, crop: RectF, cols: Int, rows: Int, mirror: Boolean, paint: Paint?): IntArray {
        // draw the crop at 4x then average down, for smoother cell colours than a straight bilinear sample
        val k = 4; val mid = Bitmap.createBitmap(cols * k, rows * k, Bitmap.Config.ARGB_8888)
        val c = Canvas(mid); c.drawColor(if (paint == null) Color.BLACK else Color.TRANSPARENT)
        val m = Matrix(); m.setRectToRect(crop, RectF(0f, 0f, (cols * k).toFloat(), (rows * k).toFloat()), Matrix.ScaleToFit.FILL)
        if (mirror) m.postScale(-1f, 1f, cols * k / 2f, 0f)
        val p = paint ?: Paint(Paint.FILTER_BITMAP_FLAG); p.isFilterBitmap = true
        c.drawBitmap(src, m, p)
        val small = Bitmap.createScaledBitmap(mid, cols, rows, true)
        val out = IntArray(cols * rows); small.getPixels(out, 0, cols, 0, 0, cols, rows)
        return out
    }

    /** person-mask coverage → head alpha: trims shoulder corners with an ellipse, fills holes, smooths over time, soft edges */
    private fun refineMask(px: IntArray, t: FaceTrack, cols: Int, rows: Int, live: Boolean): ByteArray {
        val n = cols * rows; val cov = FloatArray(n) { (px[it] ushr 24).toFloat() }
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
        val list = app.visibleList().filter { it.color != null && !it.color.fail }
        val key = fs.match + ":" + list.size + ":" + (list.firstOrNull()?.id ?: "") + ":" + (list.lastOrNull()?.id ?: "")
        pool?.let { if (it.key == key) return it }
        val lab = FloatArray(list.size * 3); val tmp = FloatArray(3)
        list.forEachIndexed { i, p -> val c = if (fs.match == "vivid") p.color!!.vivid else p.color!!.avg; ColorMath.rgb2lab(((c shr 16) and 255).toFloat(), ((c shr 8) and 255).toFloat(), (c and 255).toFloat(), tmp); lab[i * 3] = tmp[0]; lab[i * 3 + 1] = tmp[1]; lab[i * 3 + 2] = tmp[2] }
        return Pool(list, lab, key).also { pool = it }
    }

    private fun match(px: IntArray, alpha: ByteArray?, t: FaceTrack, cols: Int, rows: Int, fs: FaceSettings, P: Pool): IntArray {
        val n = cols * rows; var assign = t.assign; if (assign == null || assign.size != n) { assign = IntArray(n) { -1 }; t.assign = assign }
        val use = IntArray(P.list.size); val lab = P.lab; val np = P.list.size; val varK = fs.variety * 6f; val stab = fs.stability
        val useMask = fs.outline == "head" && alpha != null; val oval = fs.outline == "oval" || (fs.outline == "head" && alpha == null)
        val cx = (cols - 1) / 2f; val cy = (rows - 1) / 2f; val rx = cols / 2f; val ry = rows / 2f
        val hsl = FloatArray(3); val rgb = FloatArray(3); val labc = FloatArray(3)
        val tr = ((fs.tint shr 16) and 255).toFloat(); val tg = ((fs.tint shr 8) and 255).toFloat(); val tb = (fs.tint and 255).toFloat()
        for (i in 0 until n) {
            if (useMask) { if ((alpha!![i].toInt() and 255) < 40) { assign[i] = -1; continue } }
            else if (oval) { val x = i % cols; val y = i / cols; val dx = (x - cx) / rx; val dy = (y - cy) / ry; if (dx * dx + dy * dy > 1f) { assign[i] = -1; continue } }
            val c = px[i]; var r = ((c shr 16) and 255).toFloat(); var g = ((c shr 8) and 255).toFloat(); var b = (c and 255).toFloat()
            if (fs.hue != 0f || fs.sat != 1f || fs.bright != 0f || fs.contrast != 1f) {
                ColorMath.rgb2hsl(r.toInt(), g.toInt(), b.toInt(), hsl)
                val h = hsl[0] + fs.hue; val s = (hsl[1] * fs.sat).coerceIn(0f, 1f); val l = ((hsl[2] - .5f) * fs.contrast + .5f + fs.bright).coerceIn(0f, 1f)
                ColorMath.hsl2rgb(h, s, l, rgb); r = rgb[0]; g = rgb[1]; b = rgb[2]
            }
            if (fs.tintAmt > 0f) { r += (tr - r) * fs.tintAmt; g += (tg - g) * fs.tintAmt; b += (tb - b) * fs.tintAmt }
            ColorMath.rgb2lab(r, g, b, labc); val L = labc[0]; val A = labc[1]; val B = labc[2]
            var best = -1; var bd = Float.MAX_VALUE
            for (j in 0 until np) { val dl = L - lab[j * 3]; val da = A - lab[j * 3 + 1]; val db = B - lab[j * 3 + 2]; var d = dl * dl + da * da + db * db; if (varK > 0f) { val pen = varK * use[j]; d += pen * pen }; if (d < bd) { bd = d; best = j } }
            val prev = assign[i]
            if (stab > 0f && prev in 0 until np && prev != best) { val dl = L - lab[prev * 3]; val da = A - lab[prev * 3 + 1]; val db = B - lab[prev * 3 + 2]; var d = dl * dl + da * da + db * db; if (varK > 0f) { val pen = varK * use[prev]; d += pen * pen }; if (d <= bd * (1 + stab) + stab * stab * 300f) best = prev }
            assign[i] = best; if (best >= 0) use[best]++
        }
        return assign
    }

    private fun requestThumbs(cells: IntArray, P: Pool) {
        var budget = 24
        for (j in cells) {
            if (j < 0) continue; val p = P.list[j]
            if (thumbs.containsKey(p.id) || failedThumbs.contains(p.id) || !thumbPending.add(p.id)) continue
            if (--budget < 0) { thumbPending.remove(p.id); break }
            app.scope.launch {
                val b = try { app.loadBitmap(p.img, 64) } catch (_: Exception) { null }
                if (b != null) { val s = min(b.width, b.height); thumbs[p.id] = Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s) } else failedThumbs.add(p.id)
                thumbPending.remove(p.id)
            }
        }
    }

    // ---------------- drawing (shared by the screen and the recorder)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    /** extents of all heads in cell units: minX, minY, maxX, maxY */
    fun extents(f: MosaicFrame): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (m in f.faces) { val x = m.pos.x * f.cols; val y = m.pos.y * f.cols; minX = min(minX, x); minY = min(minY, y); maxX = max(maxX, x + f.cols); maxY = max(maxY, y + f.rows) }
        if (f.faces.isEmpty()) return floatArrayOf(0f, 0f, f.cols.toFloat(), f.rows.toFloat())
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /** Draw the frame so that cell (minX,minY) lands at (ox,oy) with `cell` px per cell. */
    fun draw(canvas: Canvas, f: MosaicFrame, cell: Float, gapFrac: Float, ox: Float, oy: Float, minX: Float, minY: Float) {
        val g = cell * gapFrac; val inner = cell - g
        val src = Rect(); val dst = RectF()
        for (m in f.faces) {
            val fx = ox + (m.pos.x * f.cols - minX) * cell; val fy = oy + (m.pos.y * f.cols - minY) * cell
            for (i in m.cells.indices) {
                val j = m.cells[i]; if (j < 0 || j >= f.pool.list.size) continue
                val p = f.pool.list[j]
                val al = if (m.alpha != null) (m.alpha[i].toInt() and 255) / 255f else 1f
                val sz = if (al < 1f) inner * (0.55f + 0.45f * al) else inner
                val x = fx + (i % f.cols) * cell + g / 2 + (inner - sz) / 2; val y = fy + (i / f.cols) * cell + g / 2 + (inner - sz) / 2
                dst.set(x, y, x + sz, y + sz)
                val t = thumbs[p.id]
                if (t != null) { paint.alpha = (al * 255).roundToInt(); src.set(0, 0, t.width, t.height); canvas.drawBitmap(t, src, dst, paint) }
                else { fill.color = p.color?.avg ?: Color.DKGRAY; fill.alpha = (al * 255).roundToInt(); canvas.drawRect(dst, fill) }
            }
        }
    }

    fun close() { detector.close(); segmenter.close() }
}
