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
        onClick: (() -> Unit)?
    ) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(root, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        snapToEdge(root, lp)
                        runCatching { wm.updateViewLayout(root, lp) }
                    } else {
                        onClick?.invoke()
                    }
                }
            }
            true
        }
    }

    /** ดีดให้ชิดขอบซ้ายหรือขวาที่ใกล้ที่สุด และไม่ให้หลุดจอ */
    private fun snapToEdge(view: View, lp: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val w = if (view.width > 0) view.width else dp(220)
        val h = if (view.height > 0) view.height else dp(52)
        val margin = dp(4)
        lp.x = if (lp.x + w / 2 < dm.widthPixels / 2) margin else dm.widthPixels - w - margin
        val maxY = maxOf(dp(24), dm.heightPixels - h - dp(24))
        lp.y = lp.y.coerceIn(dp(24), maxY)
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
        removeView(ghost)
        ghost = null
    }

    // ---------- ตัวช่วยลบ view ----------

    private fun removeView(v: View?) {
        if (v != null) runCatching { wm.removeView(v) }
    }

    private fun removePanel() {
        stopObserving()
        removeView(panel)
        panel = null
    }

    private fun removeBubble() {
        removeView(bubble)
        bubble = null
    }

    private fun removeCapture() {
        removeView(capture)
        capture = null
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
