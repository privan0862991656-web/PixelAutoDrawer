package com.example.pixelautodrawer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class PixelAccessibilityService : AccessibilityService() {

    companion object {
        var instance: PixelAccessibilityService? = null
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = stopDrawing()

    override fun onDestroy() {
        stopDrawing()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    /** เริ่มวาด คืนค่า false ถ้าข้อมูลไม่ครบ */
    fun startDrawing(): Boolean {
        val plan = AppState.plan ?: return false
        val tl = AppState.topLeft ?: return false
        val br = AppState.bottomRight ?: return false
        if (plan.total == 0 || AppState.swatchPoints.size < plan.groups.size) return false
        if (job?.isActive == true) return false

        val size = plan.size
        val cw = (br.x - tl.x) / size
        val ch = (br.y - tl.y) / size
        val delayMs = AppState.speed.delayMs

        AppState.progress.value = 0
        AppState.running.value = true

        job = scope.launch {
            var done = 0
            try {
                for ((gi, group) in plan.groups.withIndex()) {
                    // 1) แตะเลือกสีในถาดก่อน
                    val sw = AppState.swatchPoints[gi]
                    if (!tap(sw.x, sw.y)) return@launch
                    delay(delayMs)

                    // 2) วาดทุกช่องของสีนี้ให้เสร็จก่อนค่อยเปลี่ยนสี
                    for ((x, y) in group.cells) {
                        val px = tl.x + (x + 0.5f) * cw
                        val py = tl.y + (y + 0.5f) * ch
                        if (!tap(px, py)) return@launch
                        done++
                        AppState.progress.value = done * 100 / plan.total
                        delay(delayMs)
                    }
                }
            } finally {
                AppState.running.value = false
            }
        }
        return true
    }

    fun stopDrawing() {
        job?.cancel()
        job = null
        AppState.running.value = false
    }

    private suspend fun tap(x: Float, y: Float): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 1f, y + 1f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 30L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)

            if (!ok && cont.isActive) cont.resume(false)
        }
}
