package com.futureroots.solarmonitor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.futureroots.solarmonitor.core.Activation
import com.futureroots.solarmonitor.core.ActivationException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * الشاشة الرئيسية (بتصميم «الرقابة الشمسية»): المستخدم يُدخل رقم/أرقام الإنفرتر ويضغط «حفظ وبدء الربط».
 * التشغيل الدوري صامت في الخلفية (كل 3 أو 4 ساعات) ويُستأنف تلقائياً عند الاتصال بالشبكة أو بعد إعادة التشغيل.
 */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private var serialsEt: EditText? = null
    private var statusTv: TextView? = null
    private var chipTv: TextView? = null
    private var lastRunTv: TextView? = null
    private var batteryChip: TextView? = null
    private var logTv: TextView? = null
    private var advancedOpen = false
    private var logOpen = false
    private val ticker = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 2000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(UiColors.canvas); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        scroll.addView(root)
        setContentView(scroll)
        autoActivate()
        render()
        firstRunSetup()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.isActivated(this) && Prefs.enabled(this) && Prefs.serials(this).isNotEmpty()) Scheduler.ensure(this)   // الجدولة الصامتة
        handler.post(ticker)
    }

    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }

    /** يقرأ إعدادات الحزمة الجاهزة (n8n، المنصة…) عند أول تشغيل، فلا يحتاج المستخدم لأي رمز. */
    private fun autoActivate() {
        if (Prefs.isActivated(this)) return
        try {
            val t = assets.open("default-activation.txt").bufferedReader().use { it.readText() }.trim()
            if (t.isNotEmpty()) Prefs.activate(this, t, Activation.parse(t).intervalMinutes)
        } catch (_: Exception) { }
    }

    // ---------- أدوات بناء الواجهة ----------
    private fun tv(s: String, size: Float = 14f, color: Int = UiColors.charcoal, bold: Boolean = false): TextView = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); setLineSpacing(0f, 1.2f)
    }

    private fun lp(top: Int = 0, bottom: Int = 0, w: Int = LinearLayout.LayoutParams.MATCH_PARENT) =
        LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(top), 0, dp(bottom)) }

    private fun badge(icon: String): TextView = TextView(this).apply {
        text = icon; textSize = 18f; gravity = Gravity.CENTER
        background = roundedBg(UiColors.lightGreen, 12)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
    }

    private fun titleRow(icon: String, title: String, subtitle: String? = null): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(badge(icon))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(12), 0, 0, 0) }
        col.addView(tv(title, 17f, UiColors.charcoal, true))
        if (subtitle != null) col.addView(tv(subtitle, 12f, UiColors.muted))
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun card(icon: String, title: String, subtitle: String? = null, body: LinearLayout.() -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBg(UiColors.white, 16, UiColors.border)
        }
        c.addView(titleRow(icon, title, subtitle))
        c.body()
        root.addView(c, lp(bottom = 12))
    }

    private fun primaryBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(UiColors.white)
        stateListAnimator = null; minHeight = dp(52); minimumHeight = dp(52)
        background = rippleOf(roundedBg(UiColors.primary, 8))
        setOnClickListener { onClick() }
    }

    private fun outlineBtn(label: String, color: Int = UiColors.deep, fill: Int = 0, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(color)
        stateListAnimator = null; minHeight = dp(48); minimumHeight = dp(48)
        background = rippleOf(roundedBg(fill, 8, color, 1.5f))
        setOnClickListener { onClick() }
    }

    private fun chip(t: TextView, text: String, fg: Int, bg: Int) {
        t.text = text; t.textSize = 12f; t.setTypeface(null, Typeface.BOLD); t.setTextColor(fg)
        t.setPadding(dp(12), dp(5), dp(12), dp(5)); t.background = roundedBg(bg, 50)
    }

    private fun collapsible(icon: String, title: String, open: Boolean, onToggle: (Boolean) -> Unit, content: View) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBg(UiColors.white, 16, UiColors.border)
        }
        val arrow = tv(if (open) "▴" else "▾", 18f, UiColors.deep, true)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(badge(icon))
        head.addView(tv(title, 16f, UiColors.charcoal, true).apply { setPaddingRelative(dp(12), 0, 0, 0) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(arrow)
        content.visibility = if (open) View.VISIBLE else View.GONE
        head.setOnClickListener {
            val o = content.visibility != View.VISIBLE
            content.visibility = if (o) View.VISIBLE else View.GONE
            arrow.text = if (o) "▴" else "▾"
            onToggle(o)
        }
        box.addView(head)
        box.addView(content, lp(top = 12))
        root.addView(box, lp(bottom = 12))
    }

    // ---------- بناء الشاشة ----------
    private fun render() {
        root.removeAllViews(); serialsEt = null; statusTv = null; chipTv = null; lastRunTv = null; batteryChip = null; logTv = null
        root.addView(headerCard(), lp(bottom = 12))
        if (!Prefs.isActivated(this)) renderFallbackActivation() else renderMain()
        refresh()
    }

    private fun headerCard(): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBg(UiColors.white, 16, UiColors.border)
        }
        c.addView(ImageView(this).apply { setImageResource(R.drawable.logo); scaleType = ImageView.ScaleType.FIT_CENTER },
            LinearLayout.LayoutParams(dp(60), dp(60)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(14), 0, 0, 0) }
        col.addView(tv("مراقب الطاقة الشمسية", 20f, UiColors.deep, true))
        col.addView(tv("المنصة المركزية لإدارة العواكس اللحظية", 12f, UiColors.muted))
        c.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return c
    }

    private fun renderMain() {
        // 1) حالة الاتصال والخدمة
        card("📡", "حالة الاتصال والخدمة") {
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val chipView = TextView(this@MainActivity)
            chipTv = chipView
            row.addView(chipView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val lastView = tv("", 12f, UiColors.muted).apply { setPaddingRelative(dp(12), 0, 0, 0) }
            lastRunTv = lastView
            row.addView(lastView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(row, lp(top = 14))
            val statusView = tv("", 14f).apply { setPadding(dp(12), dp(12), dp(12), dp(12)); background = roundedBg(UiColors.canvas, 12, UiColors.border) }
            statusTv = statusView
            addView(statusView, lp(top = 12))
            addView(tv("●  أخضر: يعمل      ●  أصفر: بانتظار      ●  أحمر: خطأ", 11f, UiColors.muted), lp(top = 10))
        }

        // 2) أجهزة الإنفرتر
        card("🔌", "إعدادات أجهزة الإنفرتر", "سجّل المعرّفات المعتمدة للمسح") {
            addView(tv("الأرقام التسلسلية المعتمدة", 13f, UiColors.charcoal, true), lp(top = 14))
            val et = EditText(this@MainActivity).apply {
                hint = "مثال: A2250012345"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                minLines = 3; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.TOP or Gravity.START
                setTextColor(UiColors.charcoal); textSize = 15f
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = roundedBg(UiColors.white, 8, 0x4024332C, 1f)
                setText(Prefs.serials(this@MainActivity).joinToString("\n"))
            }
            serialsEt = et
            addView(et, lp(top = 6))
            addView(tv("ضع كل رقم تسلسلي في سطر مستقل عند وجود أكثر من جهاز.", 12f, UiColors.muted), lp(top = 6))
            addView(primaryBtn("حفظ وبدء الربط والمسح") { onLink() }, lp(top = 14))
        }

        // 3) تكرار السحب + الإجراءات اليدوية
        card("⏱", "تكرار سحب البيانات", "يعمل تلقائياً بصمت في الخلفية") {
            val rg = RadioGroup(this@MainActivity).apply { orientation = RadioGroup.VERTICAL }
            fun opt(minutes: Int, label: String): RadioButton = RadioButton(this@MainActivity).apply {
                id = minutes; text = label; textSize = 15f; setTextColor(UiColors.charcoal)
                buttonTintList = ColorStateList.valueOf(UiColors.primary)
                setPadding(dp(8), dp(12), dp(8), dp(12))
            }
            rg.addView(opt(180, "كل 3 ساعات  (موصى به)"), RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
            rg.addView(opt(240, "كل 4 ساعات  (يوفّر الطاقة)"), RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT))
            fun styleRadios() {
                for (i in 0 until rg.childCount) {
                    val r = rg.getChildAt(i) as RadioButton
                    r.background = if (r.isChecked) roundedBg(UiColors.lightGreen, 12, UiColors.primary, 1.5f) else roundedBg(UiColors.white, 12, 0x3324332C, 1f)
                }
            }
            rg.check(Prefs.interval(this@MainActivity))
            styleRadios()
            rg.setOnCheckedChangeListener { _, id ->
                Prefs.setInterval(this@MainActivity, id); styleRadios()
                if (Prefs.serials(this@MainActivity).isNotEmpty()) Scheduler.scheduleNext(this@MainActivity, Outcome.NOT_DUE, -1)
            }
            addView(rg, lp(top = 14))
            addView(tv("إجراءات يدوية", 13f, UiColors.charcoal, true), lp(top = 16))
            val actions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(outlineBtn("إعادة المحاولة الآن", UiColors.deep, UiColors.lightGreen) {
                if (saveSerials()) { MonitorService.start(this@MainActivity, MonitorService.ACTION_RUN_NOW); toast("جارٍ التنفيذ… تابع الحالة") }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(6), 0) })
            actions.addView(outlineBtn("فحص فقط (بدون إرسال)") {
                if (saveSerials()) { MonitorService.start(this@MainActivity, MonitorService.ACTION_TEST); toast("جارٍ الفحص… تابع الحالة") }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(6), 0, 0, 0) })
            addView(actions, lp(top = 8))
        }

        // 4) البطارية
        card("🔋", "العمل في الخلفية") {
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(tv("استثناء تحسين البطارية", 14f, UiColors.charcoal, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val batView = TextView(this@MainActivity)
            batteryChip = batView
            row.addView(batView)
            addView(row, lp(top = 14))
            addView(tv("يطلب التطبيق هذا الاستثناء تلقائياً عند أول فتح ليعمل بصمت دون إشعار ظاهر. على أجهزة Xiaomi / Huawei / Oppo / Samsung فعّل أيضاً «التشغيل التلقائي» وامنع إيقاف التطبيق من إعدادات البطارية.", 12f, UiColors.muted), lp(top = 10))
            addView(outlineBtn("إعداد استثناء البطارية") { requestBatteryExemption() }, lp(top = 12))
        }

        // 5) متقدم (مطوي)
        val adv = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        adv.addView(outlineBtn("إعدادات التطبيق") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, lp())
        adv.addView(outlineBtn("إيقاف ومسح الإعدادات", UiColors.red, UiColors.redBg) {
            AlertDialog.Builder(this).setMessage("سيتوقف الإرسال وتُمسح الأرقام وحالة العدادات من هذا الهاتف. متابعة؟")
                .setPositiveButton("إيقاف") { _, _ -> resetAll() }.setNegativeButton("رجوع", null).show()
        }, lp(top = 10))
        collapsible("⚙", "الإعدادات المتقدمة", advancedOpen, { advancedOpen = it }, adv)

        // 6) السجل التقني (مطوي ولا يظهر إلا بالضغط)
        val log = TextView(this).apply {
            textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setTextColor(UiColors.charcoal)
            layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.START
            setPadding(dp(10), dp(10), dp(10), dp(10)); background = roundedBg(UiColors.grayBg, 8)
        }
        logTv = log
        collapsible("🖥", "السجل التقني", logOpen, { logOpen = it; if (it) refresh() }, log)
    }

    /** احتياط: إن لم تكن إعدادات الحزمة مدمجة، يمكن لصق رمز تفعيل يدوياً. */
    private fun renderFallbackActivation() {
        card("🔑", "تفعيل التطبيق") {
            addView(tv("إعدادات الحزمة غير موجودة. الصق رمز التفعيل (يبدأ بـ ${Activation.PREFIX}) أو امسح QR.", 14f), lp(top = 12))
            val et = EditText(this@MainActivity).apply {
                hint = "رمز التفعيل"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                minLines = 3; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.TOP or Gravity.START
                setPadding(dp(12), dp(12), dp(12), dp(12)); background = roundedBg(UiColors.white, 8, 0x4024332C, 1f)
            }
            addView(et, lp(top = 10))
            addView(primaryBtn("تفعيل بالرمز") { activate(et.text.toString()) }, lp(top = 12))
            addView(outlineBtn("مسح رمز QR") { scanQr() }, lp(top = 8))
        }
    }

    /** 0 = يعمل، 1 = بانتظار، 2 = خطأ (استدلال من نص الحالة). */
    private fun statusKind(s: String): Int = when {
        s.contains("خطأ") || s.contains("تعذّر") || s.contains("فشل") -> 2
        s.contains("بانتظار") || s.contains("لم يُعثر") || s.contains("تخطي") || s.contains("غير متصل") -> 1
        else -> 0
    }

    private fun refresh() {
        val s = Prefs.status(this)
        statusTv?.text = s
        chipTv?.let {
            when (statusKind(s)) {
                0 -> chip(it, "●  يعمل بنجاح", UiColors.primary, UiColors.lightGreen)
                1 -> chip(it, "●  بانتظار", UiColors.charcoal, UiColors.yellowBg)
                else -> chip(it, "●  يوجد خطأ", UiColors.red, UiColors.redBg)
            }
        }
        lastRunTv?.text = Prefs.lastRunAt(this).takeIf { it > 0 }?.let {
            "آخر فحص: " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
        } ?: "لم يُنفَّذ أي تشغيل بعد"
        batteryChip?.let {
            if (isIgnoringBattery()) chip(it, "✓  مُستثنى", UiColors.primary, UiColors.lightGreen)
            else chip(it, "✗  غير مُستثنى", UiColors.charcoal, UiColors.yellowBg)
        }
        if (logOpen) logTv?.text = LogStore.tail(this).ifEmpty { "(لا يوجد سجل بعد)" }
    }

    private fun isIgnoringBattery() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    // ---------- الربط ----------
    /** يقرأ الأرقام من الحقل ويحفظها (يفصل بالسطر أو الفاصلة أو المسافة). */
    private fun saveSerials(): Boolean {
        val list = (serialsEt?.text?.toString() ?: "").split(Regex("[\\s,;،]+")).map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
        if (list.isEmpty()) { toast("أدخل رقم إنفرتر واحداً على الأقل"); return false }
        if (list.any { it.length < 6 }) { toast("رقم غير صحيح: الرقم التسلسلي 6 خانات على الأقل"); return false }
        Prefs.setSerials(this, list)
        serialsEt?.setText(list.joinToString("\n"))
        return true
    }

    private fun onLink() {
        if (!saveSerials()) return
        Prefs.setStatus(this, "جارٍ مسح الشبكة والبحث عن الأجهزة…", false)
        val need = missingPermissions()
        if (need.isEmpty()) { afterPermissions(); return }
        askPermissions(need, 7)
    }

    private fun askPermissions(need: List<String>, code: Int) {
        AlertDialog.Builder(this)
            .setTitle("صلاحيات مطلوبة")
            .setMessage("• الموقع: فقط لقراءة اسم شبكة Wi-Fi ومعرفة هل أنت داخل شبكة المشروع (لا نتتبّع موقعك).\n• الشبكة المحلية (على الإصدارات الأحدث): للاتصال بالإنفرتر.\n\nالتطبيق يعمل بصمت في الخلفية دون إشعار دائم.")
            .setPositiveButton("متابعة") { _, _ -> requestPermissions(need.toTypedArray(), code) }
            .setCancelable(false).show()
    }

    private fun missingPermissions(): List<String> {
        val need = ArrayList<String>()
        fun add(p: String) { if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p) }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 36) add("android.permission.ACCESS_LOCAL_NETWORK")
        return need
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) afterPermissions()
        if (requestCode == 8) requestBatteryExemptionQuietly()   // أول تشغيل: بعد الصلاحيات اطلب استثناء البطارية
    }

    /** أول فتح للتطبيق بعد التثبيت: اطلب الصلاحيات ثم استثناء البطارية تلقائياً (دون انتظار ضغط أي زر). */
    private fun firstRunSetup() {
        if (Prefs.setupDone(this)) return
        Prefs.setSetupDone(this)
        val need = missingPermissions()
        if (need.isEmpty()) requestBatteryExemptionQuietly() else askPermissions(need, 8)
    }

    private fun requestBatteryExemptionQuietly() { if (!isIgnoringBattery()) requestBatteryExemption() }

    /** بعد الصلاحيات: ابدأ تشغيلاً فورياً (مسح ← مطابقة الأرقام ← إرسال)، وفعّل الجدولة الصامتة، ثم اطلب استثناء البطارية إن لزم. */
    private fun afterPermissions() {
        Scheduler.scheduleNext(this, Outcome.NOT_DUE, -1)
        MonitorService.start(this, MonitorService.ACTION_RUN_NOW)
        toast("بدأ المسح… تابع الحالة")
        if (!isIgnoringBattery()) requestBatteryExemption()
    }

    private fun requestBatteryExemption() {
        if (isIgnoringBattery()) { toast("التطبيق مُستثنى بالفعل ✓"); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { }
        }
    }

    private fun resetAll() {
        Scheduler.cancelAll(this)
        CycleRunner.cancel()
        Prefs.deactivate(this)
        File(filesDir, "state.json").delete(); LogStore.clear(this)
        autoActivate(); render()
    }

    // ---------- احتياط: تفعيل يدوي ----------
    private fun activate(raw: String) {
        try {
            val cfg = Activation.parse(raw)
            Prefs.activate(this, raw.trim(), cfg.intervalMinutes)
            render()
        } catch (e: ActivationException) { toast(e.message ?: "رمز غير صالح") }
    }

    private fun scanQr() {
        try {
            val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(this, opts).startScan()
                .addOnSuccessListener { b -> b.rawValue?.let { activate(it) } ?: toast("لم يُقرأ رمز") }
                .addOnFailureListener { toast("تعذّر المسح (${it.message}). الصق الرمز يدوياً.") }
        } catch (e: Exception) { toast("الماسح غير متاح على هذا الجهاز. الصق الرمز يدوياً.") }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
