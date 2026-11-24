package com.futureroots.solarmonitor

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** شاشة الترحيب (حسب التصميم): شعار + اسم التطبيق + شريط تقدم، ثم انتقال تلقائي خلال ثانية ونصف أو بزر «دخول». */
class SplashActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var went = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val frame = FrameLayout(this).apply {
            setBackgroundColor(UiColors.splash); fitsSystemWindows = true; layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }

        val logoCard = FrameLayout(this).apply {
            background = roundedBg(UiColors.white, 28, UiColors.border)
            elevation = dp(6).toFloat()
        }
        logoCard.addView(ImageView(this).apply { setImageResource(R.drawable.logo); scaleType = ImageView.ScaleType.FIT_CENTER },
            FrameLayout.LayoutParams(dp(112), dp(112)).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) })
        col.addView(logoCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        col.addView(label("الرقابة الشمسية", 30f, 0xFF101E18.toInt(), true, 28))
        col.addView(label("SOLAR MONITORING", 13f, UiColors.deep, true, 6).apply { letterSpacing = 0.2f })
        col.addView(label("منظومة رصد وإدارة الطاقة المتجددة في الزمن الفعلي", 15f, UiColors.muted, false, 20).apply { gravity = Gravity.CENTER })

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 0
            progressTintList = ColorStateList.valueOf(UiColors.deep)
            progressBackgroundTintList = ColorStateList.valueOf(UiColors.lightGreen)
        }
        col.addView(bar, LinearLayout.LayoutParams(dp(180), dp(6)).apply { topMargin = dp(32) })
        col.addView(label("الانتقال التلقائي خلال لحظات…", 12f, UiColors.muted, false, 10))

        frame.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            .apply { setMargins(dp(32), 0, dp(32), 0) })

        val enter = Button(this).apply {
            text = "دخول  ←"; isAllCaps = false; textSize = 18f; setTypeface(null, Typeface.BOLD)
            setTextColor(UiColors.white); stateListAnimator = null
            background = rippleOf(roundedBg(0xFF006A45.toInt(), 12))
            setOnClickListener { enterApp() }
        }
        frame.addView(enter, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(56), Gravity.BOTTOM)
            .apply { setMargins(dp(24), 0, dp(24), dp(40)) })

        setContentView(frame)
        ObjectAnimator.ofInt(bar, "progress", 0, 1000).apply { duration = 1400; interpolator = LinearInterpolator(); start() }
        handler.postDelayed({ enterApp() }, 1500)
    }

    private fun label(s: String, size: Float, color: Int, bold: Boolean, top: Int) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(top), 0, 0)
    }

    private fun enterApp() {
        if (went) return
        went = true
        handler.removeCallbacksAndMessages(null)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
