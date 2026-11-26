package com.example.pixelautodrawer

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import kotlin.math.sqrt

data class ColorGroup(val rgb: Int, val cells: List<Pair<Int, Int>>)

class DrawPlan(val size: Int, val groups: List<ColorGroup>) {
    val total: Int = groups.sumOf { it.cells.size }
}

class PlanResult(val scaled: Bitmap, val plan: DrawPlan)

private class Candidate(val rgb: Int, val lab: DoubleArray, val count: Long)

object PixelPlanner {

    /** สีที่มีระยะ ΔE น้อยกว่านี้จะถือว่าเป็นสีเดียวกัน */
    private const val MERGE_DELTA_E = 6.0

    fun build(src: Bitmap, size: Int, maxColors: Int): PlanResult {
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        val px = IntArray(size * size)
        scaled.getPixels(px, 0, size, 0, 0, size, size)

        // 1) จัดกลุ่มสีแบบ 5 บิตต่อช่อง แล้วหาค่าเฉลี่ยของแต่ละกลุ่ม
        val sums = HashMap<Int, LongArray>()
        for (c in px) {
            if (Color.alpha(c) < 128) continue
            val key = ((Color.red(c) shr 3) shl 10) or
                    ((Color.green(c) shr 3) shl 5) or
                    (Color.blue(c) shr 3)
            val s = sums.getOrPut(key) { LongArray(4) }
            s[0]++
            s[1] += Color.red(c).toLong()
            s[2] += Color.green(c).toLong()
            s[3] += Color.blue(c).toLong()
        }
        val candidates = sums.values
            .sortedByDescending { it[0] }
            .map { s ->
                val rgb = Color.rgb(
                    (s[1] / s[0]).toInt(),
                    (s[2] / s[0]).toInt(),
                    (s[3] / s[0]).toInt()
                )
                Candidate(rgb, labOf(rgb), s[0])
            }

        // 2) เลือกสีหลัก โดยตัดสีที่ใกล้กันเกินไปออก (วัดในพื้นที่ Lab)
        val palette = mutableListOf<Candidate>()
        for (c in candidates) {
            if (palette.size >= maxColors) break
            if (palette.none { deltaE(it.lab, c.lab) < MERGE_DELTA_E }) palette.add(c)
        }
        if (palette.isEmpty()) return PlanResult(scaled, DrawPlan(size, emptyList()))

        // 3) ทุกพิกเซลหาสีในถาดที่ใกล้ที่สุดในพื้นที่ Lab
        val cells = Array(palette.size) { ArrayList<Pair<Int, Int>>() }
        val lab = DoubleArray(3)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val c = px[y * size + x]
                if (Color.alpha(c) < 128) continue
                ColorUtils.colorToLAB(c, lab)
                var best = 0
                var bestD = Double.MAX_VALUE
                for (i in palette.indices) {
                    val d = sqDist(lab, palette[i].lab)
                    if (d < bestD) {
                        bestD = d
                        best = i
                    }
                }
                cells[best].add(x to y)
            }
        }

        val groups = palette.indices
            .filter { cells[it].isNotEmpty() }
            .sortedByDescending { cells[it].size }
            .map { ColorGroup(palette[it].rgb, cells[it]) }

        return PlanResult(scaled, DrawPlan(size, groups))
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

    private fun deltaE(a: DoubleArray, b: DoubleArray): Double = sqrt(sqDist(a, b))
}
