package com.example.pixelautodrawer

import android.app.Service
import android.content.Intent
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
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*

class FloatingService : Service() {

    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var panel: View? = null
    private var ghost: View? = null
    private var capture: View? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvProgress: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnGhost: Button
    private lateinit var btnSpeed: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (panel == null) showPanel()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        removeGhost()
        removeCapture()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

    // ---------- แผงควบคุม ----------

    private fun showPanel() {
        if (!Settings.canDrawOverlays(this)) {
            toast("ยังไม่ได้อนุญาตแสดงทับแอป")
            stopSelf()
            return
        }
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        val lp = WindowManager.LayoutParams(
            dp(280),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(120)
        }
        bindPanel(v, lp)
        wm.addView(v, lp)
        panel = v
        observeState()
        refreshStatus()
    }

    private fun bindPanel(v: View, lp: WindowManager.LayoutParams) {
        tvStatus = v.findViewById(R.id.tvStatus)
        tvProgress = v.findViewById(R.id.tvProgress)
        progressBar = v.findViewById(R.id.progressBar)
        btnGhost = v.findViewById(R.id.btnGhost)
        btnSpeed = v.findViewById(R.id.btnSpeed)
        btnStart = v.findViewById(R.id.btnStart)
        btnStop = v.findViewById(R.id.btnStop)

        // ลากย้ายแผงจากแถบหัว
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        v.findViewById<View>(R.id.dragHandle).setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - touchX).toInt()
                    lp.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(v, lp)
                }
            }
            true
        }

        v.findViewById<Button>(R.id.btnClose).setOnClickListener {
            it.pulse()
            stopSelf()
        }

        v.findViewById<Button>(R.id.btnSetTopLeft).setOnClickListener {
            it.pulse()
            captureTap("แตะ 'มุมซ้ายบน' ของผืนผ้าใบในแอปวาดรูป") { x, y ->
                AppState.topLeft = PointF(x, y)
                onGeometryChanged()
            }
        }

        v.findViewById<Button>(R.id.btnSetBottomRight).setOnClickListener {
            it.pulse()
            captureTap("แตะ 'มุมขวาล่าง' ของผืนผ้าใบในแอปวาดรูป") { x, y ->
                AppState.bottomRight = PointF(x, y)
                onGeometryChanged()
            }
        }

        v.findViewById<Button>(R.id.btnSetPalette).setOnClickListener {
            it.pulse()
            startPaletteCalibration()
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

        btnStart.setOnClickListener {
            it.pulse()
            val svc = PixelAccessibilityService.instance
            if (svc == null) {
                toast("เปิดสิทธิ์ Accessibility ของแอปนี้ก่อน")
                return@setOnClickListener
            }
            if (AppState.plan == null) {
                toast("เลือกภาพต้นแบบในหน้าหลักก่อน")
                return@setOnClickListener
            }
            if (AppState.topLeft == null || AppState.bottomRight == null) {
                toast("ตั้งมุมผืนผ้าใบให้ครบก่อน")
                return@setOnClickListener
            }
            if (AppState.swatchPoints.size < (AppState.plan?.groups?.size ?: 0)) {
                toast("ตั้งถาดสีให้ครบก่อน")
                return@setOnClickListener
            }
            if (!svc.startDrawing()) toast("เริ่มวาดไม่ได้")
        }

        btnStop.setOnClickListener {
            it.pulse()
            PixelAccessibilityService.instance?.stopDrawing()
        }
    }

    private fun observeState() {
        scope.launch {
            AppState.progress.collect { p ->
                progressBar.progress = p
                tvProgress.text = "วาดแล้ว $p%"
            }
        }
        scope.launch {
            AppState.running.collect { running ->
                btnStart.isEnabled = !running
                btnStop.isEnabled = running
                btnStart.alpha = if (running) 0.5f else 1f
                btnStop.alpha = if (running) 1f else 0.5f
            }
        }
    }

    private fun refreshStatus() {
        if (!::tvStatus.isInitialized) return
        val tl = if (AppState.topLeft != null) "✓" else "✗"
        val br = if (AppState.bottomRight != null) "✓" else "✗"
        val plan = AppState.plan
        val sw = if (plan == null) "-" else "${AppState.swatchPoints.size}/${plan.groups.size}"
        tvStatus.text = "มุมซ้ายบน $tl · มุมขวาล่าง $br\nถาดสี $sw · ขนาด ${AppState.gridSize}×${AppState.gridSize}"
        btnSpeed.text = "⚡ ความเร็ว: ${AppState.speed.label}"
        btnGhost.text = if (ghost != null) "🙈 ซ่อนภาพซ้อนทับ" else "👻 แสดงภาพซ้อนทับ"
    }

    private fun onGeometryChanged() {
        removeGhost()
        refreshStatus()
    }

    // ---------- ถาดสี ----------

    private fun startPaletteCalibration() {
        val plan = AppState.plan
        if (plan == null || plan.groups.isEmpty()) {
            toast("ยังไม่มีภาพต้นแบบ")
            return
        }
        AppState.swatchPoints.clear()
        calibrateSwatch(0)
    }

    private fun calibrateSwatch(i: Int) {
        val plan = AppState.plan ?: return
        if (i >= plan.groups.size) {
            refreshStatus()
            toast("บันทึกถาดสีครบแล้ว")
            return
        }
        val hex = String.format("#%06X", plan.groups[i].rgb and 0xFFFFFF)
        captureTap("ตั้งถาดสีที่ ${i + 1}/${plan.groups.size}\nแตะสี $hex ในแอปวาดรูป") { x, y ->
            AppState.swatchPoints.add(PointF(x, y))
            calibrateSwatch(i + 1)
        }
    }

    // ---------- การจับจุดแตะ (overlay เต็มจอชั่วคราว) ----------

    private fun captureTap(prompt: String, onTap: (Float, Float) -> Unit) {
        removeCapture()

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
                handler.post { onTap(x, y) }
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

    private fun removeCapture() {
        capture?.let { runCatching { wm.removeView(it) } }
        capture = null
    }

    // ---------- ภาพซ้อนทับ (ghost) ----------

    private fun toggleGhost() {
        if (ghost != null) {
            removeGhost()
            refreshStatus()
            return
        }
        val tl = AppState.topLeft
        val br = AppState.bottomRight
        val bmp = AppState.scaledBitmap
        if (tl == null || br == null || bmp == null) {
            toast("ตั้งมุมและเลือกภาพก่อน")
            return
        }

        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = 0.45f
        }
        val lp = WindowManager.LayoutParams(
            (br.x - tl.x).toInt(),
            (br.y - tl.y).toInt(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = tl.x.toInt()
            y = tl.y.toInt()
        }
        wm.addView(iv, lp)
        ghost = iv
        refreshStatus()
    }

    private fun removeGhost() {
        ghost?.let { runCatching { wm.removeView(it) } }
        ghost = null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
