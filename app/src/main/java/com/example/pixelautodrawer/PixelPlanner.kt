package com.example.pixelautodrawer

import android.graphics.Bitmap
import android.graphics.Color

data class ColorGroup(val rgb: Int, val cells: List<Pair<Int, Int>>)

class DrawPlan(val size: Int, val groups: List<ColorGroup>) {
    val total: Int = groups.sumOf { it.cells.size }
}

class PlanResult(val scaled: Bitmap, val plan: DrawPlan)

object PixelPlanner {

    fun build(src: Bitmap, size: Int, maxColors: Int): PlanResult {
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        val px = IntArray(size * size)
        scaled.getPixels(px, 0, size, 0, 0, size, size)

        // 1) หาสีหลัก: จัดกลุ่มสีแบบหยาบ แล้วใช้ค่าเฉลี่ยของกลุ่มที่พบบ่อยที่สุด
        val buckets = HashMap<Int, IntArray>()
        for (c in px) {
            if (Color.alpha(c) < 128) continue
            val key = ((Color.red(c) shr 4) shl 8) or
                    ((Color.green(c) shr 4) shl 4) or
                    (Color.blue(c) shr 4)
            val b = buckets.getOrPut(key) { IntArray(4) }
            b[0]++
            b[1] += Color.red(c)
            b[2] += Color.green(c)
            b[3] += Color.blue(c)
        }
        val palette = buckets.values
            .sortedByDescending { it[0] }
            .take(maxColors)
            .map { Color.rgb(it[1] / it[0], it[2] / it[0], it[3] / it[0]) }

        if (palette.isEmpty()) return PlanResult(scaled, DrawPlan(size, emptyList()))

        // 2) ทุกพิกเซลหาสีในถาดที่ใกล้ที่สุด (ระยะทาง RGB ยกกำลังสอง)
        val cells = Array(palette.size) { ArrayList<Pair<Int, Int>>() }
        for (y in 0 until size) {
            for (x in 0 until size) {
                val c = px[y * size + x]
                if (Color.alpha(c) < 128) continue
                var best = 0
                var bestD = Int.MAX_VALUE
                for (i in palette.indices) {
                    val p = palette[i]
                    val dr = Color.red(c) - Color.red(p)
                    val dg = Color.green(c) - Color.green(p)
                    val db = Color.blue(c) - Color.blue(p)
                    val d = dr * dr + dg * dg + db * db
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
            .map { ColorGroup(palette[it], cells[it]) }

        return PlanResult(scaled, DrawPlan(size, groups))
    }
}
