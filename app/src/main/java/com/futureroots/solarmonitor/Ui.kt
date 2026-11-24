package com.futureroots.solarmonitor

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/** ألوان التصميم (الرقابة الشمسية) وأدوات رسم مشتركة بين الشاشات. */
object UiColors {
    val primary = 0xFF159B69.toInt()      // أخضر زمردي: الأزرار الرئيسية وحالة النجاح
    val deep = 0xFF104B38.toInt()         // أخضر غابات: العناوين والإطارات
    val charcoal = 0xFF24332C.toInt()     // النصوص
    val muted = 0xFF6D7A71.toInt()
    val lightGreen = 0xFFE8F3EC.toInt()
    val yellow = 0xFFF4C542.toInt()
    val yellowBg = 0xFFFEF8E7.toInt()
    val canvas = 0xFFF8FAF8.toInt()
    val white = 0xFFFFFFFF.toInt()
    val red = 0xFFD83232.toInt()
    val redBg = 0xFFFFF1F0.toInt()
    val grayBg = 0xFFF0F3F1.toInt()
    val border = 0x1424332C               // حدّ خفيف (≈8% من لون النص)
    val splash = 0xFFECFEF3.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.roundedBg(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Float = 1f): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        if (strokeColor != 0) setStroke((strokeDp * resources.displayMetrics.density).toInt().coerceAtLeast(1), strokeColor)
    }

fun rippleOf(d: Drawable): RippleDrawable = RippleDrawable(ColorStateList.valueOf(0x33104B38), d, null)
