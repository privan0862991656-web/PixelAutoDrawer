package com.example.pixelautodrawer

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingService : Service() {

    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val observers = mutableListOf<Job>()

    private var panel: View? = null
    private var bubble: View? = null
    private var ghost: View? = null
    private var capture: View? = null
    private var confirm: View? = null
    private var uiWasMinimized = false

    private val panelLp by lazy { newParams(dp(220), WindowManager.LayoutParams.WRAP_CONTENT, 12, 120) }
    private val bubbleLp by lazy { newParams(dp(52), dp(52), 12, 120) }

    private lateinit var tvStatus: TextView
    private lateinit var tvProgress: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnGhost: Button
    private lateinit var btnSpeed: Button
    private lateinit var btnMult: Button
    private lateinit var btnColors: Button
    private lateinit var btnPalArea: Button
    private lateinit var btnPalAuto: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            if (AppState.running.value) {
                PixelAccessibilityService.instance?.pauseDrawing()
                toast("หน้าจอดับ: พักการวาดแล้ว")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (panel == null && bubble == null) showPanel()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        stopObserving()
        scope.cancel()
        removeGhost()
        removeCapture()
        removeConfirm()
        removePanel()
        removeBubble()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

    private fun floatingParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun newParams(w: Int, h: Int, xDp: Int, yDp: Int): WindowManager.LayoutParams =
        floatingParams(w, h).apply {
            x = dp(xDp)
            y = dp(yDp)
        }

    // ---------- แสดง / ซ่อนแผง และวงกลมย่อ ----------

    private fun showPanel() {
        if (!Settings.canDrawOverlays(this)) {
            toast("ยังไม่ได้อนุญาตแสดงทับแอป")
            stopSelf()
            return
        }
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        bindPanel(v)
        wm.addView(v, panelLp)
        panel = v
        observeState()
        refreshStatus()
    }

    private fun minimizeToBubble() {
        if (panel == null) return
        bubbleLp.x = panelLp.x
        bubbleLp.y = panelLp.y
        removePanel()
        showBubble()
    }

    private fun showBubble() {
        val b = TextView(this).apply {
            text = "✦"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(0xFF1A1200.toInt())
            setBackgroundResource(R.drawable.btn_gold)
        }
        attachDrag(b, b, bubbleLp) { expandPanel() }
        wm.addView(b, bubbleLp)
        bubble = b
    }

    private fun expandPanel() {
        removeBubble()
        panelLp.x = bubbleLp.x
        panelLp.y = bubbleLp.y
        showPanel()
    }

    private fun hideUi() {
        if (panel == null && bubble == null) return
        uiWasMinimized = bubble != null
        removePanel()
        removeBubble()
    }

    private fun restoreUi() {
        if (panel != null || bubble != null) return
        if (uiWasMinimized) showBubble() else showPanel()
    }

    private fun bindPanel(v: View) {
        tvStatus = v.findViewById(R.id.tvStatus)
        tvProgress = v.findViewById(R.id.tvProgress)
        progressBar = v.findViewById(R.id.progressBar)
        btnGhost = v.findViewById(R.id.btnGhost)
        btnSpeed = v.findViewById(R.id.btnSpeed)
        btnMult = v.findViewById(R.id.btnMult)
        btnColors = v.findViewById(R.id.btnColors)
        btnPalArea = v.findViewById(R.id.btnPalArea)
        btnPalAuto = v.findViewById(R.id.btnPalAuto)
        btnStart = v.findViewById(R.id.btnStart)
        btnStop = v.findViewById(R.id.btnStop)
        val btnSkip: Button = v.findViewById(R.id.btnSkip)
        val btnReselect: Button = v.findViewById(R.id.btnReselect)
        val btnReset: Button = v.findViewById(R.id.btnReset)

        attachDrag(v.findViewById(R.id.dragHandle), v, panelLp, null)

        v.findViewById<Button>(R.id.btnMin).setOnClickListener {
            it.pulse()
            minimizeToBubble()
        }

        v.findViewById<Button>(R.id.btnClose).setOnClickListener {
            it.pulse()
            stopSelf()
        }

        v.findViewById<Button>(R.id.btnSetTopLeft).setOnClickListener {
            it.pulse()
            beginCapture("แตะ 'มุมซ้ายบน' ของผืนผ้าใบในแอปวาดรูป") { x, y ->
                AppState.topLeft = PointF(x, y)
                onGeometryChanged()
            }
        }

        v.findViewById<Button>(R.id.btnSetBottomRight).setOnClickListener {
            it.pulse()
            beginCapture("แตะ 'มุมขวาล่าง' ของผืนผ้าใบในแอปวาดรูป") { x, y ->
                AppState.bottomRight = PointF(x, y)
                onGeometryChanged()
            }
        }

        v.findViewById<Button>(R.id.btnSetPalette).setOnClickListener {
            it.pulse()
            startPaletteCalibration()
        }

        btnPalArea.setOnClickListener {
            it.pulse()
            calibratePaletteArea()
        }

        btnPalAuto.setOnClickListener {
            it.pulse()
            autoMatchPalette()
        }

        btnReselect.setOnClickListener {
            it.pulse()
            reselectCurrent()
        }

        btnGhost.setOnClickListener {
            it.pulse()
            toggleGhost()
        }

        btnSpeed.setOnClickListener {
            it.pulse()
            val all = Speed.values()
            AppState.speed = all[(AppState.speed.ordinal + 1) % all.size]
            refreshStatus()
        }

        btnMult.setOnClickListener {
            it.pulse()
            AppState.cycleMultiplier()
            refreshStatus()
        }

        btnColors.setOnClickListener {
            it.pulse()
            if (AppState.running.value) {
                toast("หยุดการวาดก่อนเปลี่ยนจำนวนสี")
                return@setOnClickListener
            }
            AppState.cycleColorLimit()
            AppState.rebuild()
            removeGhost()
            refreshStatus()
            toast("ปรับเป็น ${AppState.colorLimit} สี ต้องตั้งถาดสีใหม่")
        }

        btnStart.setOnClickListener {
            it.pulse()
            val svc = PixelAccessibilityService.instance
            when {
                svc == null -> toast("เปิดสิทธิ์ Accessibility ของแอปนี้ก่อน")
                AppState.plan == null -> toast("เลือกภาพต้นแบบในหน้าหลักก่อน")
                AppState.topLeft == null || AppState.bottomRight == null ->
                    toast("ตั้งมุมผืนผ้าใบให้ครบก่อน")
                AppState.swatchCount() == 0 -> toast("ตั้งถาดสีอย่างน้อย 1 สีก่อน")
                svc.startDrawing() -> minimizeToBubble()
                else -> toast("เริ่มวาดไม่ได้")
            }
        }

        btnStop.setOnClickListener {
            it.pulse()
            PixelAccessibilityService.instance?.pauseDrawing()
        }

        btnSkip.setOnClickListener {
            it.pulse()
            if (AppState.isFinished()) {
                toast("วาดครบแล้ว")
                return@setOnClickListener
            }
            val gi = AppState.resumeGroup
            if (AppState.running.value) {
                AppState.skipped.add(gi)
            } else {
                AppState.skipCurrentIdle()
            }
            toast("ข้ามสีที่ ${gi + 1} แล้ว")
            refreshStatus()
        }

        btnReset.setOnClickListener {
            it.pulse()
            PixelAccessibilityService.instance?.pauseDrawing()
            AppState.resetProgress()
            AppState.skipped.clear()
            refreshStatus()
            toast("รีเซ็ตความคืบหน้าแล้ว")
        }
    }

    private fun observeState() {
        stopObserving()
        observers += scope.launch {
            AppState.progress.collect { p ->
                progressBar.progress = p
                tvProgress.text = "วาดแล้ว $p%"
            }
        }
        observers += scope.launch {
            combine(AppState.running, AppState.paused) { r, p -> r to p }.collect { (r, p) ->
                btnStart.isEnabled = !r
                btnStart.text = if (p) "▶ ทำต่อ" else "▶ เริ่ม"
                btnStart.alpha = if (r) 0.5f else 1f
                btnStop.isEnabled = r
                btnStop.alpha = if (r) 1f else 0.5f
            }
        }
    }

    private fun stopObserving() {
        observers.forEach { it.cancel() }
        observers.clear()
    }

    private fun refreshStatus() {
        if (!::tvStatus.isInitialized) return
        val tl = if (AppState.topLeft != null) "✓" else "✗"
        val br = if (AppState.bottomRight != null) "✓" else "✗"
        val groups = AppState.plan?.groups?.size ?: 0
        tvStatus.text = "มุมซ้าย $tl · มุมขวา $br · ถาดสี ${AppState.swatchCount()}/$groups\n" +
                "ขนาด ${AppState.gridSize}×${AppState.gridSize} · ข้าม ${AppState.skipped.size} สี"
        btnSpeed.text = "⚡ ${AppState.speed.label}"
        btnMult.text = "⏩ ×${AppState.multiplier}"
        btnColors.text = "🎚 จำนวนสี: ${AppState.colorLimit}"
        btnGhost.text = if (ghost != null) "🙈 ซ่อนภาพ" else "👻 ภาพซ้อน"
    }

    private fun onGeometryChanged() {
        removeGhost()
        refreshStatus()
    }

    // ---------- ถาดสี (มีปุ่มยืนยัน ✓ / ✕) ----------

    private fun startPaletteCalibration() {
        val plan = AppState.plan
        if (plan == null || plan.groups.isEmpty()) {
            toast("ยังไม่มีภาพต้นแบบ")
            return
        }
        PixelAccessibilityService.instance?.pauseDrawing()
        calibrateFrom(0)
    }

    private fun calibrateFrom(i: Int) {
        val plan = AppState.plan ?: return
        if (i >= plan.groups.size) {
            finishCalibration()
            return
        }
        pickSwatch(i) { calibrateFrom(i + 1) }
    }

    /** รับการแตะ 1 ครั้งเพื่อตั้งถาดสีของกลุ่ม i แล้วถามยืนยันก่อน */
    private fun pickSwatch(i: Int, onDone: () -> Unit) {
        val plan = AppState.plan ?: return
        val hex = String.format("#%06X", plan.groups[i].rgb and 0xFFFFFF)
        beginCapture(
            "ตั้งถาดสีที่ ${i + 1}/${plan.groups.size}\nแตะสี $hex ในถาดของแอปวาดรูป",
            keepHidden = true
        ) { x, y ->
            showConfirm(
                x, y,
                onOk = {
                    AppState.setSwatch(i, PointF(x, y))
                    AppState.skipped.remove(i)
                    onDone()
                },
                onRetry = { pickSwatch(i, onDone) }
            )
        }
    }

    private fun finishCalibration() {
        restoreUi()
        refreshStatus()
        toast("บันทึกถาดสีครบแล้ว")
    }

    /** เลือกสีใหม่ให้กลุ่มที่กำลังวาดอยู่ */
    private fun reselectCurrent() {
        val plan = AppState.plan
        if (plan == null) {
            toast("ยังไม่มีภาพต้นแบบ")
            return
        }
        val gi = AppState.resumeGroup
        if (gi >= plan.groups.size) {
            toast("วาดครบแล้ว")
            return
        }
        PixelAccessibilityService.instance?.pauseDrawing()
        val segs = buildSegments(plan.groups[gi].cells)
        AppState.doneCells -= segs.take(AppState.resumeSegment).sumOf { it.count }
        AppState.resumeSegment = 0
        AppState.progress.value = AppState.doneCells * 100 / plan.total.coerceAtLeast(1)
        pickSwatch(gi) {
            AppState.skipped.remove(gi)
            restoreUi()
            refreshStatus()
        }
    }

    /** แถบยืนยัน ✓ / ✕ ใกล้จุดที่แตะ */
    private fun showConfirm(px: Float, py: Float, onOk: () -> Unit, onRetry: () -> Unit) {
        removeConfirm()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_hint)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val ok = TextView(this).apply {
            text = "✓"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(0xFF1A1200.toInt())
            setBackgroundResource(R.drawable.btn_gold)
            setOnClickListener {
                removeConfirm()
                onOk()
            }
        }
        val no = TextView(this).apply {
            text = "✕"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(0xFFF5F0FF.toInt())
            setBackgroundResource(R.drawable.btn_red)
            setOnClickListener {
                removeConfirm()
                onRetry()
            }
        }
        row.addView(ok, LinearLayout.LayoutParams(dp(56), dp(48)))
        row.addView(no, LinearLayout.LayoutParams(dp(56), dp(48)).apply { marginStart = dp(10) })

        val dm = resources.displayMetrics
        val lp = floatingParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            this.x = (px - dp(70)).toInt().coerceIn(dp(4), dm.widthPixels - dp(150))
            this.y = (py + dp(28)).toInt().coerceIn(dp(24), dm.heightPixels - dp(90))
        }
        wm.addView(row, lp)
        confirm = row
    }

    private fun removeConfirm() {
        removeView(confirm)
        confirm = null
    }

    // ---------- ขอบถาดสีและจับสีอัตโนมัติ ----------

    private fun calibratePaletteArea() {
        PixelAccessibilityService.instance?.pauseDrawing()
        beginCapture("แตะ 'มุมซ้ายบน' ของแถบสีในแอปวาดรูป") { x, y ->
            AppState.paletteTL = PointF(x, y)
            beginCapture("แตะ 'มุมขวาล่าง' ของแถบสีในแอปวาดรูป") { x2, y2 ->
                AppState.paletteBR = PointF(x2, y2)
                refreshStatus()
                toast("บันทึกขอบถาดสีแล้ว")
            }
        }
    }

    private fun autoMatchPalette() {
        val svc = PixelAccessibilityService.instance
        if (svc == null) {
            toast("เปิดสิทธิ์ Accessibility ของแอปนี้ก่อน")
            return
        }
        val plan = AppState.plan
        if (plan == null) {
            toast("เลือกภาพต้นแบบก่อน")
            return
        }
        val tl = AppState.paletteTL
        val br = AppState.paletteBR
        if (tl == null || br == null) {
            toast("กด 'ขอบถาดสี' แล้วแตะมุมถาดก่อน")
            return
        }

        hideUi()
        handler.postDelayed({
            svc.captureScreen { bmp ->
                restoreUi()
                if (bmp == null) {
                    toast("อ่านหน้าจอไม่ได้ (ต้อง Android 11 ขึ้นไป และเปิดสิทธิ์ Accessibility ใหม่)")
                    return@captureScreen
                }
                val found = PaletteDetector.detect(
                    bmp, tl.x.toInt(), tl.y.toInt(), br.x.toInt(), br.y.toInt()
                )
                if (found.isEmpty()) {
                    toast("หาสีในถาดไม่เจอ ลองตั้งขอบถาดใหม่ให้ครอบเฉพาะช่องสี")
                    return@captureScreen
                }
                val matched = PaletteDetector.match(plan.groups.map { it.rgb }, found)
                matched.forEachIndexed { i, s -> AppState.setSwatch(i, PointF(s.x, s.y)) }
                refreshStatus()
                toast("จับคู่ ${matched.size} สีจากถาด ${found.size} ช่อง")
            }
        }, 400L)
    }

    // ---------- จับจุดแตะ (ซ่อนแผงชั่วคราว) ----------

    /**
     * ซ่อนแผงชั่วคราว รอรับการแตะ 1 ครั้ง แล้วเรียก onTap
     * ถ้า keepHidden = false จะแสดงแผงกลับอัตโนมัติหลัง onTap
     */
    private fun beginCapture(prompt: String, keepHidden: Boolean = false, onTap: (Float, Float) -> Unit) {
        removeCapture()
        hideUi()

        val root = FrameLayout(this).apply { setBackgroundColor(0x77000000) }

        val hint = TextView(this).apply {
            text = prompt
            setTextColor(0xFFFFD700.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_hint)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(
            hint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(80) }
        )

        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val x = e.rawX
                val y = e.rawY
                removeCapture()
                handler.post {
                    onTap(x, y)
                    if (!keepHidden && capture == null && confirm == null) restoreUi()
                }
            }
            true
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(root, lp)
        capture = root
    }

    // ---------- การลาก + ดีดชิดขอบ ----------

    /** handle = ส่วนที่ใช้จับลาก, root = วิวที่ถูกเพิ่มเข้า WindowManager */
    private fun attachDrag(
        handle: View,
        root: View,
        lp: WindowManager.LayoutParams,
        onClick: (() -> Unit)? = null
    ) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        var isDragging = false

        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = e.rawX
                    startRawY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startRawX
                    val dy = e.rawY - startRawY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        lp.x = (startX + dx).toInt()
                        lp.y = (startY + dy).toInt()
                        runCatching { wm.updateViewLayout(root, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onClick?.invoke()
                    }
                    true
                }
                else -> false
            }
        }
    }
}
