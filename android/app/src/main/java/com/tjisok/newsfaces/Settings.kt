package com.tjisok.newsfaces

import android.content.Context
import org.json.JSONObject

data class FaceSettings(
    val cols: Int = 40, val pad: Float = 1.9f, val gap: Float = 0.06f,
    val outline: String = "head", val multi: Boolean = false, val mirror: Boolean = true,
    val hue: Float = 0f, val sat: Float = 1f, val bright: Float = 0f, val contrast: Float = 1f,
    val tint: Int = 0xFFFF7A3D.toInt(), val tintAmt: Float = 0f, val match: String = "avg",
    val variety: Float = 0.25f, val stability: Float = 0.5f, val fps: Int = 10, val freeze: Boolean = false
)

data class Settings(
    val cols: Int = 4, val gap: Float = 4f, val radius: Float = 8f, val aspect: Float = 1f,
    val sort: String = "newest", val reverse: Boolean = false, val hueOff: Float = 0f, val gray: Float = 0.12f,
    val strip: Float = 0f, val ageH: Float = 48f, val minSat: Float = 0f, val lightMin: Float = 0f, val lightMax: Float = 1f,
    val max: Int = 800, val refreshMin: Int = 10,
    val disabledSources: Set<String> = emptySet(),
    val face: FaceSettings = FaceSettings()
) {
    fun sourceOn(id: String) = id !in disabledSources

    fun toJson(): String {
        val f = face
        val o = JSONObject()
            .put("cols", cols).put("gap", gap).put("radius", radius).put("aspect", aspect).put("sort", sort).put("reverse", reverse)
            .put("hueOff", hueOff).put("gray", gray).put("strip", strip).put("ageH", ageH).put("minSat", minSat)
            .put("lightMin", lightMin).put("lightMax", lightMax).put("max", max).put("refreshMin", refreshMin)
            .put("disabled", disabledSources.joinToString(","))
            .put("face", JSONObject().put("cols", f.cols).put("pad", f.pad).put("gap", f.gap).put("outline", f.outline).put("multi", f.multi)
                .put("mirror", f.mirror).put("hue", f.hue).put("sat", f.sat).put("bright", f.bright).put("contrast", f.contrast)
                .put("tint", f.tint).put("tintAmt", f.tintAmt).put("match", f.match).put("variety", f.variety).put("stability", f.stability).put("fps", f.fps))
        return o.toString()
    }

    companion object {
        fun fromJson(s: String?): Settings {
            if (s.isNullOrBlank()) return Settings()
            return try {
                val o = JSONObject(s); val d = Settings(); val fo = o.optJSONObject("face"); val fd = FaceSettings()
                Settings(
                    cols = o.optInt("cols", d.cols), gap = o.optDouble("gap", d.gap.toDouble()).toFloat(), radius = o.optDouble("radius", d.radius.toDouble()).toFloat(),
                    aspect = o.optDouble("aspect", d.aspect.toDouble()).toFloat(), sort = o.optString("sort", d.sort), reverse = o.optBoolean("reverse", d.reverse),
                    hueOff = o.optDouble("hueOff", d.hueOff.toDouble()).toFloat(), gray = o.optDouble("gray", d.gray.toDouble()).toFloat(),
                    strip = o.optDouble("strip", d.strip.toDouble()).toFloat(), ageH = o.optDouble("ageH", d.ageH.toDouble()).toFloat(),
                    minSat = o.optDouble("minSat", d.minSat.toDouble()).toFloat(), lightMin = o.optDouble("lightMin", d.lightMin.toDouble()).toFloat(),
                    lightMax = o.optDouble("lightMax", d.lightMax.toDouble()).toFloat(), max = o.optInt("max", d.max), refreshMin = o.optInt("refreshMin", d.refreshMin),
                    disabledSources = o.optString("disabled", "").split(',').filter { it.isNotBlank() }.toSet(),
                    face = if (fo == null) fd else FaceSettings(
                        cols = fo.optInt("cols", fd.cols), pad = fo.optDouble("pad", fd.pad.toDouble()).toFloat(), gap = fo.optDouble("gap", fd.gap.toDouble()).toFloat(),
                        outline = fo.optString("outline", fd.outline), multi = fo.optBoolean("multi", fd.multi), mirror = fo.optBoolean("mirror", fd.mirror),
                        hue = fo.optDouble("hue", fd.hue.toDouble()).toFloat(), sat = fo.optDouble("sat", fd.sat.toDouble()).toFloat(),
                        bright = fo.optDouble("bright", fd.bright.toDouble()).toFloat(), contrast = fo.optDouble("contrast", fd.contrast.toDouble()).toFloat(),
                        tint = fo.optInt("tint", fd.tint), tintAmt = fo.optDouble("tintAmt", fd.tintAmt.toDouble()).toFloat(), match = fo.optString("match", fd.match),
                        variety = fo.optDouble("variety", fd.variety.toDouble()).toFloat(), stability = fo.optDouble("stability", fd.stability.toDouble()).toFloat(),
                        fps = fo.optInt("fps", fd.fps))
                )
            } catch (e: Exception) { Settings() }
        }

        fun load(ctx: Context): Settings = fromJson(ctx.getSharedPreferences("newsfaces", Context.MODE_PRIVATE).getString("settings", null))
        fun save(ctx: Context, s: Settings) { ctx.getSharedPreferences("newsfaces", Context.MODE_PRIVATE).edit().putString("settings", s.toJson()).apply() }
    }
}
