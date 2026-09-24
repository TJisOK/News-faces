package com.tjisok.newsfaces

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object ColorMath {
    /** returns [h 0..360, s 0..1, l 0..1] */
    fun rgb2hsl(r: Int, g: Int, b: Int, out: FloatArray) {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val mx = max(rf, max(gf, bf)); val mn = min(rf, min(gf, bf)); val l = (mx + mn) / 2f
        var h = 0f; var s = 0f
        if (mx != mn) {
            val d = mx - mn
            s = if (l > .5f) d / (2f - mx - mn) else d / (mx + mn)
            h = when (mx) {
                rf -> (gf - bf) / d + (if (gf < bf) 6f else 0f)
                gf -> (bf - rf) / d + 2f
                else -> (rf - gf) / d + 4f
            } * 60f
        }
        out[0] = h; out[1] = s; out[2] = l
    }

    fun hsl2rgb(h0: Float, s: Float, l: Float, out: FloatArray) {
        val h = (((h0 % 360f) + 360f) % 360f) / 360f
        if (s <= 0f) { out[0] = l * 255f; out[1] = l * 255f; out[2] = l * 255f; return }
        val q = if (l < .5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun f(t0: Float): Float {
            val t = (t0 + 1f) % 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < .5f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        out[0] = f(h + 1f / 3f) * 255f; out[1] = f(h) * 255f; out[2] = f(h - 1f / 3f) * 255f
    }

    private val LUT = FloatArray(256) { i ->
        val c = i / 255f
        if (c > 0.04045f) ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat() else c / 12.92f
    }

    private fun lin(v: Float): Float = LUT[v.toInt().coerceIn(0, 255)]

    /** CIE Lab (D65) from 0..255 floats */
    fun rgb2lab(r: Float, g: Float, b: Float, out: FloatArray) {
        val R = lin(r); val G = lin(g); val B = lin(b)
        var x = (R * 0.4124f + G * 0.3576f + B * 0.1805f) / 0.95047f
        var y = R * 0.2126f + G * 0.7152f + B * 0.0722f
        var z = (R * 0.0193f + G * 0.1192f + B * 0.9505f) / 1.08883f
        fun f(t: Float): Float = if (t > 0.008856f) cbrt(t) else 7.787f * t + 16f / 116f
        x = f(x); y = f(y); z = f(z)
        out[0] = 116f * y - 16f; out[1] = 500f * (x - y); out[2] = 200f * (y - z)
    }

    fun circDist(a: Float, b: Float): Float { val d = abs(a - b) % 360f; return if (d > 180f) 360f - d else d }
}
