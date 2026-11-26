package com.example.pixelautodrawer

import android.graphics.Bitmap
import android.graphics.PointF
import kotlinx.coroutines.flow.MutableStateFlow

enum class Speed(val label: String, val delayMs: Long) {
    SLOW("ช้า", 260L),
    MEDIUM("ปานกลาง", 120L),
    FAST("เร็ว", 50L)
}

object AppState {
    const val MAX_COLORS = 16

    var sourceBitmap: Bitmap? = null
    var gridSize: Int = 32
    var scaledBitmap: Bitmap? = null
    var plan: DrawPlan? = null

    var topLeft: PointF? = null
    var bottomRight: PointF? = null
    val swatchPoints = mutableListOf<PointF>()   // ตำแหน่งถาดสี เรียงตาม plan.groups

    var speed: Speed = Speed.MEDIUM

    val progress = MutableStateFlow(0)
    val running = MutableStateFlow(false)

    /** คำนวณแผนการวาดใหม่เมื่อเปลี่ยนภาพหรือขนาด */
    fun rebuild() {
        val src = sourceBitmap ?: return
        val result = PixelPlanner.build(src, gridSize, MAX_COLORS)
        scaledBitmap = result.scaled
        plan = result.plan
        swatchPoints.clear()   // ถาดสีต้องตั้งใหม่เพราะลำดับสีอาจเปลี่ยน
    }
}
