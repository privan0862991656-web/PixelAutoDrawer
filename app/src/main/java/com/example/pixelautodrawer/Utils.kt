package com.example.pixelautodrawer

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.animation.OvershootInterpolator
import android.widget.Toast

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** เอฟเฟกต์กดแล้วเด้ง */
fun View.pulse() {
    animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).withEndAction {
        animate().scaleX(1f).scaleY(1f)
            .setDuration(260)
            .setInterpolator(OvershootInterpolator(2f))
            .start()
    }.start()
}

fun Context.isAccessibilityOn(): Boolean {
    val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.name == PixelAccessibilityService::class.java.name }
}
