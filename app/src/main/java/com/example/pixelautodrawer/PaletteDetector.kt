package com.example.pixelautodrawer

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.sqrt

data class PaletteSwatch(val x: Float, val y: Float, val rgb: Int)

object PaletteDetector {

    private const val BG_TOLERANCE = 28.0     // ต่างจากสีพื้นหลังมากกว่านี้ = สีของถาด
    private const val SPLIT_TOLERANCE = 40.0  // สีต่างกันมาก = คนละช่อง แม้ไม่มีช่องว่างคั่น
    private const val MIN_RUN = 12            // ช่องสีต้องกว้างอย่างน้อยเท่านี้ px
    private const val SAME_X_PX = 10          // x ใกล้กัน + สีใกล้กัน = ช่องเดียวกัน
    private const val ROWS = 7                // จำนวนแถวที่สแกนในพื้นที่ถาด

    /** อ่านช่องสีทั้งหมดในสี่เหลี่ยม (left, top)-(right, bottom) ของภาพหน้าจอ */
    fun detect(screen: Bitmap, left: Int, top: Int, right: Int, bottom: Int): List<PaletteSwatch> {
        val l = left.coerceIn(0, screen.width - 1)
        val t = top.coerceIn(0, screen.height - 1)
        val r = right.coerceIn(0, screen.width - 1)
        val b = bottom.coerceIn(0, screen.height - 1)
        if (r - l < MIN_RUN || b - t < ROWS) return emptyList()

        val bg = backgroundOf(screen, l, t, r, b)
        val found = mutableListOf<PaletteSwatch>()
        for (i in 1..ROWS) {
            val y = t + (b - t) * i / (ROWS + 1)
            for (run in runsAt(screen, l, r, y, bg)) {
                val cx = (run.first + run.last) / 2
                val p = PaletteSwatch(cx.toFloat(), y.toFloat(), screen.getPixel(cx, y))
                if (found.none { isSameSwatch(it, p) }) found.add(p)
            }
        }
        return found
    }

    /** จับคู่สีเป้าหมายแต่ละสี กับช่องสีที่ใกล้ที่สุดในพื้นที่ Lab */
    fun match(targets: List<Int>, swatches: List<PaletteSwatch>): List<PaletteSwatch> {
        if (swatches.isEmpty()) return emptyList()
        val swLab = swatches.map { labOf(it.rgb) }
        return targets.map { t ->
            val tl = labOf(t)
            var best = 0
            var bestD = Double.MAX_VALUE
            for (i in swatches.indices) {
                val d = sqDist(tl, swLab[i])
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            swatches[best]
        }
    }

    private fun isSameSwatch(a: PaletteSwatch, b: PaletteSwatch): Boolean =
        abs(a.x - b.x) < SAME_X_PX && dist(a.rgb, b.rgb) < SPLIT_TOLERANCE / 2

    private fun backgroundOf(screen: Bitmap, l: Int, t: Int, r: Int, b: Int): Int {
        val mx = (l + r) / 2
        val my = (t + b) / 2
        val colors = listOf(
            screen.getPixel(l, t), screen.getPixel(r, t),
            screen.getPixel(l, b), screen.getPixel(r, b),
            screen.getPixel(mx, t), screen.getPixel(mx, b),
            screen.getPixel(l, my), screen.getPixel(r, my)
        )
        return colors.maxByOrNull { c -> colors.count { dist(it, c) < BG_TOLERANCE } } ?: 0
    }

    private fun runsAt(screen: Bitmap, l: Int, r: Int, y: Int, bg: Int): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = -1
        var prev = 0
        for (x in l..r) {
            val c = screen.getPixel(x, y)
            val isColor = Color.alpha(c) > 128 && dist(c, bg) > BG_TOLERANCE
            if (start >= 0 && (!isColor || dist(c, prev) > SPLIT_TOLERANCE)) {
                addRun(out, start, x - 1)
                start = -1
            }
            if (isColor && start < 0) start = x
            prev = c
        }
        if (start >= 0) addRun(out, start, r)
        return out
    }

    private fun addRun(out: MutableList<IntRange>, a: Int, b: Int) {
        if (b - a + 1 >= MIN_RUN) out.add(a..b)
    }

    private fun labOf(rgb: Int): DoubleArray {
        val lab = DoubleArray(3)
        ColorUtils.colorToLAB(rgb, lab)
        return lab
    }

    private fun sqDist(a: DoubleArray, b: DoubleArray): Double {
        val dl = a[0] - b[0]
        val da = a[1] - b[1]
        val db = a[2] - b[2]
        return dl * dl + da * da + db * db
    }

    private fun dist(a: Int, b: Int): Double {
        val dr = Color.red(a) - Color.red(b)
        val dg = Color.green(a) - Color.green(b)
        val db = Color.blue(a) - Color.blue(b)
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }
}
