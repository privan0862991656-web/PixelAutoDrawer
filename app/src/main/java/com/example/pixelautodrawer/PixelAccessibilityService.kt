package com.example.pixelautodrawer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class PixelAccessibilityService : AccessibilityService() {

    companion object {
        var instance: PixelAccessibilityService? = null
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) pauseDrawing()
        }
    }

    override fun onServiceConnected() {
        instance = this
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this, screenReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = pauseDrawing()

    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        pauseDrawing()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    /** อ่านภาพหน้าจอทั้งจอ (ต้อง Android 11 ขึ้นไป) คืน null ถ้าอ่านไม่ได้ */
    fun captureScreen(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null)
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val hb = screenshot.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                hb.close()
                onResult(bmp)
            }

            override fun onFailure(errorCode: Int) {
                onResult(null)
            }
        })
    }

    /** เริ่มหรือทำต่อจากตำแหน่งที่ค้างไว้ คืน false ถ้าข้อมูลไม่พร้อม */
    fun startDrawing(): Boolean {
        val plan = AppState.plan ?: return false
        val tl = AppState.topLeft ?: return false
        val br = AppState.bottomRight ?: return false
        if (job?.isActive == true) return false
        if (AppState.isFinished()) AppState.resetProgress()

        val cw = (br.x - tl.x) / plan.size
        val ch = (br.y - tl.y) / plan.size

        job = scope.launch {
            AppState.paused.value = false
            AppState.running.value = true
            try {
                drawAll(plan, tl, cw, ch)
            } finally {
                AppState.running.value = false
                AppState.paused.value = AppState.hasProgress() && !AppState.isFinished()
            }
        }
        return true
    }

    /** พักการวาดทันที ตำแหน่งที่ค้างไว้ยังถูกเก็บไว้ */
    fun pauseDrawing() {
        job?.cancel()
        job = null
    }

    private suspend fun drawAll(plan: DrawPlan, tl: PointF, cw: Float, ch: Float) {
        while (AppState.resumeGroup < plan.groups.size) {
            val gi = AppState.resumeGroup
            val segs = buildSegments(plan.groups[gi].cells)
            val sw = AppState.swatchOf(gi)

            // กลุ่มที่ไม่มีถาดสีหรือถูกข้าม นับเป็นวาดแล้วและไปกลุ่มถัดไป
            if (sw == null || gi in AppState.skipped) {
                AppState.doneCells += remainingCells(segs)
                updateProgress(plan)
                nextGroup()
                continue
            }

            // เปลี่ยนสีเฉพาะตอนเริ่มกลุ่ม ตอนทำต่อกลางกลุ่มสีเดิมยังถูกเลือกอยู่
            if (AppState.resumeSegment == 0) {
                if (!gesture(tapPath(sw.x, sw.y), tapDuration())) return
                delay(AppState.colorDelayMs())
            }

            while (AppState.resumeSegment < segs.size) {
                if (gi in AppState.skipped) break
                val seg = segs[AppState.resumeSegment]
                val x0 = tl.x + (seg.x0 + 0.5f) * cw
                val x1 = tl.x + (seg.x1 + 0.5f) * cw
                val y = tl.y + (seg.y + 0.5f) * ch
                if (!gesture(linePath(x0, x1, y), dragDuration(seg.count))) return
                AppState.doneCells += seg.count
                AppState.resumeSegment++
                updateProgress(plan)
                delay(AppState.delayMs())
            }

            if (gi in AppState.skipped) {
                AppState.doneCells += remainingCells(segs)
            }
            nextGroup()
        }
    }

    private fun nextGroup() {
        AppState.resumeGroup++
        AppState.resumeSegment = 0
    }

    private fun remainingCells(segs: List<Segment>): Int =
        segs.drop(AppState.resumeSegment).sumOf { it.count }

    private fun updateProgress(plan: DrawPlan) {
        AppState.progress.value = AppState.doneCells * 100 / plan.total.coerceAtLeast(1)
    }

    private fun tapDuration(): Long = (30L / AppState.multiplier).coerceAtLeast(10L)

    private fun dragDuration(count: Int): Long =
        (count * 12L / AppState.multiplier).coerceAtLeast(16L)

    private fun tapPath(x: Float, y: Float): Path = Path().apply {
        moveTo(x, y)
        lineTo(x + 1f, y + 1f)
    }

    private fun linePath(x0: Float, x1: Float, y: Float): Path = Path().apply {
        moveTo(x0, y)
        lineTo(if (x0 == x1) x0 + 1f else x1, y)
    }

    /** ส่ง gesture หนึ่งครั้ง คืน true เมื่อทำสำเร็จ */
    private suspend fun gesture(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val g = GestureDescription.Builder().addStroke(stroke).build()
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }
}
