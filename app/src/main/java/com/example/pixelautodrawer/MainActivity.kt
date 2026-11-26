package com.example.pixelautodrawer

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvAccessStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvPlanInfo: TextView
    private lateinit var imgPreview: ImageView

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        if (bmp == null) {
            toast("เปิดรูปไม่สำเร็จ")
            return@registerForActivityResult
        }
        AppState.sourceBitmap = bmp
        AppState.rebuild()
        refreshPreview()
        toast("โหลดภาพต้นแบบแล้ว")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvAccessStatus = findViewById(R.id.tvAccessStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvPlanInfo = findViewById(R.id.tvPlanInfo)
        imgPreview = findViewById(R.id.imgPreview)

        // ชื่อแอปกระพริบนุ่ม ๆ
        ObjectAnimator.ofFloat(findViewById(R.id.tvTitle), "alpha", 0.55f, 1f).apply {
            duration = 1400
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }

        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            it.pulse()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            it.pulse()
            openOverlaySettings()
        }

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            it.pulse()
            pickImage.launch("image/*")
        }

        findViewById<RadioGroup>(R.id.rgSize).setOnCheckedChangeListener { _, id ->
            AppState.gridSize = when (id) {
                R.id.rb64 -> 64
                R.id.rb128 -> 128
                else -> 32
            }
            AppState.rebuild()
            refreshPreview()
        }

        findViewById<Button>(R.id.btnOpenPanel).setOnClickListener {
            it.pulse()
            if (!Settings.canDrawOverlays(this)) {
                toast("ต้องอนุญาตแสดงทับแอปก่อน")
                openOverlaySettings()
                return@setOnClickListener
            }
            startService(Intent(this, FloatingService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        tvAccessStatus.text = if (isAccessibilityOn())
            "✅ Accessibility: เปิดอยู่"
        else
            "⛔ Accessibility: ยังไม่เปิด"

        tvOverlayStatus.text = if (Settings.canDrawOverlays(this))
            "✅ แสดงทับแอป: อนุญาตแล้ว"
        else
            "⛔ แสดงทับแอป: ยังไม่อนุญาต"
    }

    private fun refreshPreview() {
        imgPreview.setImageBitmap(AppState.scaledBitmap)
        val plan = AppState.plan
        tvPlanInfo.text = if (plan == null) {
            "ยังไม่ได้เลือกภาพ"
        } else {
            "${AppState.gridSize}×${AppState.gridSize} · ${plan.groups.size} สี · ${plan.total} พิกเซล"
        }
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }
}
