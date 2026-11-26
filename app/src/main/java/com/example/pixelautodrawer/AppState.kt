package com.example.pixelautodrawer

import android.graphics.Bitmap
import android.graphics.PointF
import kotlinx.coroutines.flow.MutableStateFlow

enum class Speed(val label: String, val delayMs: Long) {
    SLOW("ช้า", 200L),
    MEDIUM("ปานกลาง", 80L),
    FAST("เร็ว", 30L)
}

/** เส้นแนวนอนต่อเนื่องในแถวเดียวกัน วาดด้วยการลากนิ้วครั้งเดียว */
data class Segment(val y: Int, val x0: Int, val x1: Int) {
    val count: Int get() = x1 - x0 + 1
}

/** รวมจุดที่อยู่ติดกันในแถวเดียวกันเป็นเส้น (cells = (x, y)) */
fun buildSegments(cells: List<Pair<Int, Int>>): List<Segment> {
    val sorted = cells.sortedWith(compareBy<Pair<Int, Int>>({ it.second }, { it.first }))
    val out = ArrayList<Segment>()
    for ((x, y) in sorted) {
        val last = out.lastOrNull()
        if (last != null && last.y == y && last.x1 + 1 == x) {
            out[out.size - 1] = last.copy(x1 = x)
        } else {
            out.add(Segment(y, x, x))
        }
    }
    return out
}

object AppState {
    const val MAX_COLORS = 32
    private val MULTIPLIERS = intArrayOf(1, 2, 3, 5)

    var sourceBitmap: Bitmap? = null
    var gridSize: Int = 32
    var scaledBitmap: Bitmap? = null
    var plan: DrawPlan? = null

    var topLeft: PointF? = null
    var bottomRight: PointF? = null

    /** ถาดสีของแต่ละกลุ่มสี (index ตรงกับ plan.groups) null = ยังไม่ตั้ง */
    val swatchPoints = mutableListOf<PointF?>()
    /** กลุ่มสีที่ผู้ใช้สั่งข้าม */
    val skipped = mutableSetOf<Int>()

    var speed: Speed = Speed.MEDIUM
    private var multiplierIndex = 0
    val multiplier: Int get() = MULTIPLIERS[multiplierIndex]

    // ตำแหน่งที่วาดค้างไว้ สำหรับทำต่อ
    var resumeGroup = 0
    var resumeSegment = 0
    var doneCells = 0

    val progress = MutableStateFlow(0)
    val running = MutableStateFlow(false)
    val paused = MutableStateFlow(false)

    fun cycleMultiplier() {
        multiplierIndex = (multiplierIndex + 1) % MULTIPLIERS.size
    }

    fun delayMs(): Long = (speed.delayMs / multiplier).coerceAtLeast(5L)

    fun colorDelayMs(): Long = (120L / multiplier).coerceAtLeast(40L)

    /** คำนวณแผนการวาดใหม่เมื่อเปลี่ยนภาพหรือขนาด */
    fun rebuild() {
        val src = sourceBitmap ?: return
        val result = PixelPlanner.build(src, gridSize, MAX_COLORS)
        scaledBitmap = result.scaled
        plan = result.plan
        swatchPoints.clear()
        skipped.clear()
        resetProgress()
    }

    fun swatchOf(i: Int): PointF? = swatchPoints.getOrNull(i)

    fun setSwatch(i: Int, p: PointF) {
        while (swatchPoints.size <= i) swatchPoints.add(null)
        swatchPoints[i] = p
    }

    fun swatchCount(): Int = swatchPoints.count { it != null }

    fun missingSwatches(): Int {
        val p = plan ?: return 0
        return p.groups.indices.count { it !in skipped && swatchOf(it) == null }
    }

    fun hasProgress(): Boolean = resumeGroup > 0 || resumeSegment > 0

    fun isFinished(): Boolean {
        val p = plan ?: return false
        return resumeGroup >= p.groups.size
    }

    fun resetProgress() {
        resumeGroup = 0
        resumeSegment = 0
        doneCells = 0
        progress.value = 0
    }

    /** ข้ามกลุ่มปัจจุบันทันที ใช้ตอนที่ไม่ได้กำลังวาด */
    fun skipCurrentIdle() {
        val p = plan ?: return
        if (isFinished()) return
        val segs = buildSegments(p.groups[resumeGroup].cells)
        doneCells += segs.drop(resumeSegment).sumOf { it.count }
        skipped.add(resumeGroup)
        resumeGroup++
        resumeSegment = 0
        progress.value = doneCells * 100 / p.total.coerceAtLeast(1)
    }
}
