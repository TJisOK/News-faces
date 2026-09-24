package com.tjisok.newsfaces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AppState(private val ctx: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val loader: ImageLoader = ImageLoader.Builder(ctx).crossfade(true).build()

    private val _settings = MutableStateFlow(Settings.load(ctx))
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    fun update(f: (Settings) -> Settings) { _settings.update(f); Settings.save(ctx, _settings.value) }
    fun updateFace(f: (FaceSettings) -> FaceSettings) = update { it.copy(face = f(it.face)) }

    private val byId = ConcurrentHashMap<String, Photo>()
    private val _photos = MutableStateFlow<List<Photo>>(emptyList())
    val photos: StateFlow<List<Photo>> = _photos.asStateFlow()
    val feedState = MutableStateFlow<Map<String, FeedState>>(emptyMap())
    val fetching = MutableStateFlow(false)
    val analysing = MutableStateFlow(0 to 0) // done, total
    val mode = MutableStateFlow("gallery")
    val viewer = MutableStateFlow<Photo?>(null)

    private val cacheFile get() = File(ctx.filesDir, "photos.json")
    private var refreshJob: Job? = null

    init {
        loadCache()
        scope.launch { if (photos.value.isEmpty() || System.currentTimeMillis() - cacheTs > 3 * 60_000L) refresh() else analyzeAll() }
        scheduleRefresh()
    }

    private var cacheTs = 0L
    private fun loadCache() {
        try {
            if (!cacheFile.exists()) return
            val o = JSONObject(cacheFile.readText()); cacheTs = o.optLong("ts")
            val arr = o.getJSONArray("photos")
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i); val c = p.optJSONObject("color")
                val photo = Photo(p.getString("id"), p.getString("img"), p.optString("link"), p.optString("title"), p.optLong("time"), p.optString("source"), p.optString("sec"), p.optString("sid"),
                    if (c == null || c.optBoolean("fail")) null else PhotoColor(c.getDouble("hue").toFloat(), c.getDouble("sat").toFloat(), c.getDouble("light").toFloat(), c.getInt("avg"), c.getInt("vivid")),
                    p.optBoolean("lowres"))
                byId[photo.id] = photo
            }
            publish()
        } catch (_: Exception) {}
    }

    private fun saveCache() {
        try {
            val arr = JSONArray()
            for (p in byId.values.sortedByDescending { it.time }.take(4000)) {
                val o = JSONObject().put("id", p.id).put("img", p.img).put("link", p.link).put("title", p.title).put("time", p.time).put("source", p.source).put("sec", p.sec).put("sid", p.sid).put("lowres", p.lowres)
                p.color?.let { c -> o.put("color", JSONObject().put("hue", c.hue).put("sat", c.sat).put("light", c.light).put("avg", c.avg).put("vivid", c.vivid).put("fail", c.fail)) }
                arr.put(o)
            }
            cacheFile.writeText(JSONObject().put("ts", System.currentTimeMillis()).put("photos", arr).toString())
        } catch (_: Exception) {}
    }

    private fun publish() { _photos.value = byId.values.toList() }

    fun scheduleRefresh() {
        refreshJob?.cancel()
        val min = settings.value.refreshMin; if (min <= 0) return
        refreshJob = scope.launch { while (true) { delay(min * 60_000L); refresh() } }
    }

    fun refresh() {
        if (fetching.value) return
        scope.launch {
            fetching.value = true
            val feeds = Feeds.ALL.filter { settings.value.sourceOn(it.id) }
            val sem = Semaphore(6)
            val jobs = feeds.map { f -> launch { sem.withPermit { fetchFeed(f) } } }
            jobs.forEach { it.join() }
            fetching.value = false; saveCache(); publish(); analyzeAll()
        }
    }

    suspend fun fetchFeed(f: Feed) {
        feedState.update { it + (f.id to FeedState.LOADING) }
        try {
            val items = withContext(Dispatchers.IO) { FeedFetcher.fetch(f) }
            for (it in items) {
                var img = FeedFetcher.upgradeImg(it.img)
                var lowres = false
                if (img.contains("i.guim.co.uk")) { val master = FeedFetcher.guardianMaster(img); if (master != null) img = master else lowres = it.imgWidth in 1..200 }
                val id = FeedFetcher.keyOf(img, it.link)
                if (byId.containsKey(id)) continue
                byId[id] = Photo(id, img, it.link, it.title.replace(Regex("<[^>]+>"), "").trim(), FeedFetcher.parseDate(it.date), f.name, f.sec, f.id, null, lowres)
            }
            feedState.update { it + (f.id to FeedState.OK) }
            publish()
        } catch (e: Exception) {
            android.util.Log.w("NewsFaces", "feed ${f.id} failed: ${e.javaClass.simpleName} ${e.message}")
            feedState.update { it + (f.id to FeedState.FAIL) }
        }
    }

    fun fetchFeedAsync(f: Feed) = scope.launch { fetchFeed(f) }

    // ---------------- colour analysis
    private val analysisSem = Semaphore(5)
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private var analysisDone = 0; private var analysisTotal = 0

    fun analyzeAll() {
        val pending = byId.values.filter { it.color == null && queued.add(it.id) }
        if (pending.isEmpty()) return
        analysisTotal += pending.size; analysing.value = analysisDone to analysisTotal
        var sinceSave = 0
        for (p in pending) scope.launch {
            analysisSem.withPermit { analyze(p) }
            analysisDone++; analysing.value = analysisDone to analysisTotal
            if (++sinceSave % 25 == 0 || analysisDone == analysisTotal) { publish(); if (analysisDone == analysisTotal) saveCache() }
        }
    }

    /** Small thumbnail URL that the image proxy renders CORS-free; on Android we can load originals directly, so use them, sized down by Coil. */
    private suspend fun analyze(p: Photo) {
        val bmp = loadBitmap(p.img, 24)
        val color = if (bmp == null) PhotoColor(0f, 0f, .5f, 0xFF555555.toInt(), 0xFF555555.toInt(), fail = true) else computeColor(bmp)
        byId[p.id] = (byId[p.id] ?: p).copy(color = color)
    }

    suspend fun loadBitmap(url: String, size: Int): Bitmap? {
        val req = ImageRequest.Builder(ctx).data(url).size(size).allowHardware(false).build()
        val res = loader.execute(req)
        return (res as? SuccessResult)?.drawable?.let { (it as? BitmapDrawable)?.bitmap }
    }

    private fun computeColor(src: Bitmap): PhotoColor {
        val n = 24
        val bmp = Bitmap.createScaledBitmap(src, n, n, true)
        val px = IntArray(n * n); bmp.getPixels(px, 0, n, 0, 0, n, n)
        val bins = FloatArray(36); var R = 0L; var G = 0L; var B = 0L; var SS = 0f; var LL = 0f; var bestV = -1f; var vivid = 0xFF808080.toInt()
        val hsl = FloatArray(3)
        for (c in px) {
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            ColorMath.rgb2hsl(r, g, b, hsl)
            R += r; G += g; B += b; SS += hsl[1]; LL += hsl[2]
            val w = hsl[1] * (1f - Math.abs(2f * hsl[2] - 1f))
            bins[(hsl[0] / 10f).toInt().coerceIn(0, 35)] += w
            if (w > bestV) { bestV = w; vivid = c or (0xFF shl 24) }
        }
        var bi = 0; for (i in 1 until 36) if (bins[i] > bins[bi]) bi = i
        var sx = 0.0; var sy = 0.0
        for (k in -2..2) { val j = (bi + k + 36) % 36; val a = Math.toRadians((j * 10 + 5).toDouble()); sx += bins[j] * Math.cos(a); sy += bins[j] * Math.sin(a) }
        var hue = Math.toDegrees(Math.atan2(sy, sx)).toFloat(); if (hue < 0) hue += 360f
        val cnt = px.size; val sat = SS / cnt; val light = LL / cnt
        val avg = (0xFF shl 24) or ((R / cnt).toInt() shl 16) or ((G / cnt).toInt() shl 8) or (B / cnt).toInt()
        return PhotoColor(hue, sat, light, avg, if (sat < 0.05f) avg else vivid)
    }

    // ---------------- sorting & filtering (mirrors the web app)
    fun visibleList(): List<Photo> {
        val s = settings.value
        val cutoff = System.currentTimeMillis() - (s.ageH * 3600_000f).toLong()
        var list = photos.value.filter { p ->
            s.sourceOn(p.sid) && p.time >= cutoff && (p.color == null || p.color.fail || (p.color.sat >= s.minSat && p.color.light >= s.lightMin && p.color.light <= s.lightMax))
        }
        val isGray = { c: PhotoColor -> c.fail || c.sat < s.gray }
        val rank = { c: PhotoColor? -> if (c == null) 2 else if (isGray(c)) 1 else 0 }
        val hueKey = { c: PhotoColor -> ((c.hue - s.hueOff) % 360f + 360f) % 360f }
        val cmp: Comparator<Photo> = when (s.sort) {
            "oldest" -> compareBy { it.time }
            "hue" -> Comparator { a, b ->
                val ra = rank(a.color); val rb = rank(b.color)
                if (ra != rb) ra - rb else when (ra) {
                    0 -> { val d = hueKey(a.color!!) - hueKey(b.color!!); if (d != 0f) d.compareTo(0f) else a.color.light.compareTo(b.color.light) }
                    1 -> a.color!!.light.compareTo(b.color!!.light)
                    else -> b.time.compareTo(a.time)
                }
            }
            "light" -> compareBy { it.color?.light ?: 2f }
            "sat" -> compareByDescending { it.color?.sat ?: -1f }
            "warm" -> compareByDescending { p -> p.color?.takeIf { !it.fail }?.let { it.sat * (1f - ColorMath.circDist(it.hue, 30f) / 180f) * 2f - it.sat } ?: -9f }
            "source" -> compareBy<Photo> { it.source }.thenBy { it.sec }.thenByDescending { it.time }
            else -> compareByDescending { it.time }
        }
        list = list.sortedWith(cmp)
        if (s.reverse) list = list.reversed()
        return list.take(s.max)
    }

    fun clearCache() { byId.clear(); publish(); cacheFile.delete(); queued.clear(); analysisDone = 0; analysisTotal = 0; refresh() }
}
