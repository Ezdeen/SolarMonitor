package com.futureroots.solarmonitor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
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

<<<<<<< HEAD
/**
 * الشاشة الرئيسية: المستخدم يُدخل رقم/أرقام الإنفرتر ويضغط «حفظ وبدء الربط».
 * إعدادات n8n والمنصة موجودة مسبقاً داخل الحزمة (assets/default-activation.txt)، واسم الشبكة يُتعلَّم تلقائياً عند أول نجاح.
 */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private var serialsEt: EditText? = null
=======
/** شاشة واحدة: تفعيل (QR أو لصق الرمز) ← صلاحيات ← استثناء البطارية ← تعمل الخدمة وحدها بعدها. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
    private var statusTv: TextView? = null
    private var batteryTv: TextView? = null
    private var logTv: TextView? = null
    private val ticker = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 2000) } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { fitsSystemWindows = true }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        scroll.addView(root)
        setContentView(scroll)
<<<<<<< HEAD
        autoActivate()
=======
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
        render()
    }

    override fun onResume() {
        super.onResume()
<<<<<<< HEAD
        if (Prefs.isActivated(this) && Prefs.enabled(this) && Prefs.serials(this).isNotEmpty()) MonitorService.start(this)   // يضمن بقاء الخدمة حيّة
=======
        if (Prefs.isActivated(this) && Prefs.enabled(this)) MonitorService.start(this)      // يضمن بقاء الخدمة حيّة
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
        handler.post(ticker)
    }

    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }

<<<<<<< HEAD
    /** يقرأ إعدادات الحزمة الجاهزة (n8n، المنصة…) عند أول تشغيل، فلا يحتاج المستخدم لأي رمز. */
    private fun autoActivate() {
        if (Prefs.isActivated(this)) return
        try {
            val t = assets.open("default-activation.txt").bufferedReader().use { it.readText() }.trim()
            if (t.isNotEmpty()) Prefs.activate(this, t, Activation.parse(t).intervalMinutes)
        } catch (_: Exception) { }
    }

=======
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
    // ---------- بناء الواجهة ----------
    private fun text(s: String, size: Float = 15f, bold: Boolean = false, top: Int = 8): TextView = TextView(this).apply {
        this.text = s; textSize = size; if (bold) setTypeface(null, Typeface.BOLD); setPadding(0, dp(top), 0, 0)
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply { text = label; setOnClickListener { onClick() } }

    private fun render() {
<<<<<<< HEAD
        root.removeAllViews(); serialsEt = null; statusTv = null; batteryTv = null; logTv = null
        root.addView(text("مراقب الطاقة الشمسية", 22f, true))
        if (!Prefs.isActivated(this)) renderFallbackActivation() else renderMain()
=======
        root.removeAllViews(); statusTv = null; batteryTv = null; logTv = null
        root.addView(text("مراقب الطاقة الشمسية", 22f, true))
        if (!Prefs.isActivated(this)) renderActivation() else renderActive()
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
        root.addView(text("السجل", 16f, true, 20))
        logTv = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true); layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.START }
        root.addView(logTv)
        refresh()
    }

<<<<<<< HEAD
    private fun renderMain() {
        root.addView(text("أدخل رقم الإنفرتر (الرقم التسلسلي). عند وجود أكثر من جهاز اكتب كل رقم في سطر.", 15f))
        val et = EditText(this).apply {
            hint = "مثال: A2250012345"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            minLines = 3; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.START
            setText(Prefs.serials(this@MainActivity).joinToString("\n"))
        }
        serialsEt = et
        root.addView(et)
        root.addView(button("حفظ وبدء الربط والمسح") { onLink() })
        statusTv = text("", 15f, true, 16); root.addView(statusTv)

        root.addView(text("تكرار القراءة والإرسال:", 15f, true, 16))
        val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val r60 = RadioButton(this).apply { text = "كل ساعة"; id = 60 }
        val r15 = RadioButton(this).apply { text = "كل 15 دقيقة"; id = 15 }
        rg.addView(r60); rg.addView(r15)
        rg.check(Prefs.interval(this))
        rg.setOnCheckedChangeListener { _, id -> Prefs.setInterval(this, id); if (Prefs.serials(this).isNotEmpty()) MonitorService.start(this) }
        root.addView(rg)

        root.addView(button("إعادة المحاولة الآن") { if (saveSerials()) { MonitorService.start(this, MonitorService.ACTION_RUN_NOW); toast("جارٍ التنفيذ… تابع الحالة") } })
        root.addView(button("فحص فقط (بدون إرسال)") { if (saveSerials()) { MonitorService.start(this, MonitorService.ACTION_TEST); toast("جارٍ الفحص… تابع الحالة") } })
        batteryTv = text("", 14f, false, 16); root.addView(batteryTv)
        root.addView(button("استثناء التطبيق من تحسين البطارية") { requestBatteryExemption() })
        root.addView(text("أجهزة Xiaomi / Huawei / Oppo / Samsung: فعّل «التشغيل التلقائي» وامنع إيقاف التطبيق في إعدادات البطارية، وإلا قد يُغلقه النظام.", 13f))
        root.addView(button("إعدادات التطبيق") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) })
        root.addView(button("إيقاف ومسح الإعدادات") {
            AlertDialog.Builder(this).setMessage("سيتوقف الإرسال وتُمسح الأرقام وحالة العدادات من هذا الهاتف. متابعة؟")
                .setPositiveButton("إيقاف") { _, _ -> resetAll() }.setNegativeButton("رجوع", null).show()
        })
    }

    /** احتياط: إن لم تكن إعدادات الحزمة مدمجة، يمكن لصق رمز تفعيل يدوياً. */
    private fun renderFallbackActivation() {
        root.addView(text("إعدادات الحزمة غير موجودة. الصق رمز التفعيل (يبدأ بـ ${Activation.PREFIX}) أو امسح QR.", 15f))
        val et = EditText(this).apply {
            hint = "رمز التفعيل"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
=======
    private fun renderActivation() {
        root.addView(text("لربط هذا الهاتف بحسابك: امسح رمز QR من المنصة، أو الصق رمز التفعيل (يبدأ بـ ${Activation.PREFIX}).", 15f))
        val et = EditText(this).apply {
            hint = "الصق رمز التفعيل هنا"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
            minLines = 3; layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        root.addView(et)
        root.addView(button("تفعيل بالرمز") { activate(et.text.toString()) })
        root.addView(button("مسح رمز QR") { scanQr() })
    }

<<<<<<< HEAD
    private fun refresh() {
        statusTv?.text = Prefs.status(this) + Prefs.lastRunAt(this).takeIf { it > 0 }?.let {
=======
    private fun renderActive() {
        val cfg = Prefs.config(this)
        root.addView(text("مفعّل" + (cfg?.customer?.let { " لحساب: $it" } ?: ""), 15f, true))
        statusTv = text("", 15f); root.addView(statusTv)
        root.addView(text("تكرار القراءة والإرسال:", 15f, true, 16))
        val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val r60 = RadioButton(this).apply { text = "كل ساعة"; id = 60 }
        val r15 = RadioButton(this).apply { text = "كل 15 دقيقة"; id = 15 }
        rg.addView(r60); rg.addView(r15)
        rg.check(Prefs.interval(this))
        rg.setOnCheckedChangeListener { _, id -> Prefs.setInterval(this, id); MonitorService.start(this) }
        root.addView(rg)
        root.addView(button("قراءة وإرسال الآن") { MonitorService.start(this, MonitorService.ACTION_RUN_NOW); toast("جارٍ التنفيذ… تابع السجل") })
        root.addView(button("فحص الشبكة والإنفرتر (بدون إرسال)") { MonitorService.start(this, MonitorService.ACTION_TEST); toast("جارٍ الفحص… تابع السجل") })
        batteryTv = text("", 14f, false, 16); root.addView(batteryTv)
        root.addView(button("استثناء التطبيق من تحسين البطارية") { requestBatteryExemption() })
        root.addView(text("أجهزة Xiaomi / Huawei / Oppo / Samsung: فعّل «التشغيل التلقائي» وامنع إيقاف التطبيق في إعدادات البطارية، وإلا قد يُغلقه النظام.", 13f))
        root.addView(button("إعدادات التطبيق") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        root.addView(button("إلغاء التفعيل") {
            AlertDialog.Builder(this).setMessage("سيتوقف الإرسال ويُمسح الرمز وحالة العدادات من هذا الهاتف. متابعة؟")
                .setPositiveButton("إلغاء التفعيل") { _, _ -> deactivate() }.setNegativeButton("رجوع", null).show()
        })
    }

    private fun refresh() {
        statusTv?.text = "الحالة: ${Prefs.status(this)}" + Prefs.lastRunAt(this).takeIf { it > 0 }?.let {
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
            "\nآخر تشغيل: " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
        }.orEmpty()
        val ignoring = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryTv?.text = if (ignoring) "تحسين البطارية: مُستثنى ✓" else "تحسين البطارية: غير مُستثنى ✗ (مطلوب لاستمرار العمل في الخلفية)"
        logTv?.text = LogStore.tail(this).ifEmpty { "(لا يوجد سجل بعد)" }
    }

<<<<<<< HEAD
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
        AlertDialog.Builder(this)
            .setTitle("صلاحيات مطلوبة")
            .setMessage("• الإشعارات: لإظهار أيقونة «نشط».\n• الموقع: فقط لقراءة اسم شبكة Wi-Fi ومعرفة هل أنت داخل شبكة المشروع (لا نتتبّع موقعك).\n• الأجهزة القريبة/الشبكة المحلية (على الإصدارات الأحدث): للاتصال بالإنفرتر.")
            .setPositiveButton("متابعة") { _, _ -> requestPermissions(need.toTypedArray(), 7) }
            .setCancelable(false).show()
    }

    private fun missingPermissions(): List<String> {
        val need = ArrayList<String>()
        fun add(p: String) { if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p) }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 36) add("android.permission.ACCESS_LOCAL_NETWORK")
        return need
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) afterPermissions()
    }

    /** بعد الصلاحيات: ابدأ الخدمة فوراً (مسح ← مطابقة الأرقام ← إرسال)، ثم اطلب استثناء البطارية إن لزم. */
    private fun afterPermissions() {
        MonitorService.start(this, MonitorService.ACTION_RUN_NOW)
        toast("بدأ المسح… تابع الحالة")
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) requestBatteryExemption()
    }

    private fun requestBatteryExemption() {
        if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) { toast("التطبيق مُستثنى بالفعل ✓"); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun resetAll() {
        startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_STOP))
        Prefs.deactivate(this)
        File(filesDir, "state.json").delete(); LogStore.clear(this)
        autoActivate(); render()
    }

    // ---------- احتياط: تفعيل يدوي ----------
=======
    // ---------- التفعيل ----------
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
    private fun activate(raw: String) {
        try {
            val cfg = Activation.parse(raw)
            Prefs.activate(this, raw.trim(), cfg.intervalMinutes)
<<<<<<< HEAD
            render()
        } catch (e: ActivationException) { toast(e.message ?: "رمز غير صالح") }
=======
            File(filesDir, "state.json").delete(); LogStore.clear(this)
            render()
            AlertDialog.Builder(this)
                .setTitle("صلاحيات مطلوبة")
                .setMessage("• الإشعارات: لإظهار أيقونة «نشط».\n• الموقع: فقط لقراءة اسم شبكة Wi-Fi ومعرفة هل أنت داخل شبكة المشروع (لا نتتبّع موقعك).\n• الأجهزة القريبة/الشبكة المحلية (على الإصدارات الأحدث): للاتصال بالإنفرتر.")
                .setPositiveButton("متابعة") { _, _ -> requestNeededPermissions() }
                .setCancelable(false).show()
        } catch (e: ActivationException) {
            toast(e.message ?: "رمز غير صالح")
        }
>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
    }

    private fun scanQr() {
        try {
            val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(this, opts).startScan()
                .addOnSuccessListener { b -> b.rawValue?.let { activate(it) } ?: toast("لم يُقرأ رمز") }
                .addOnFailureListener { toast("تعذّر المسح (${it.message}). الصق الرمز يدوياً.") }
        } catch (e: Exception) { toast("الماسح غير متاح على هذا الجهاز. الصق الرمز يدوياً.") }
    }

<<<<<<< HEAD
=======
    private fun deactivate() {
        startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_STOP))
        Prefs.deactivate(this)
        File(filesDir, "state.json").delete(); LogStore.clear(this)
        render()
    }

    // ---------- الصلاحيات ----------
    private fun requestNeededPermissions() {
        val need = ArrayList<String>()
        fun add(p: String) { if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p) }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 36) add("android.permission.ACCESS_LOCAL_NETWORK")
        if (need.isEmpty()) afterPermissions() else requestPermissions(need.toTypedArray(), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) afterPermissions()
    }

    /** بعد الصلاحيات: ابدأ الخدمة فوراً (مسح الشبكة وبقية الخطوات)، ثم اطلب استثناء البطارية. */
    private fun afterPermissions() {
        MonitorService.start(this, MonitorService.ACTION_RUN_NOW)
        requestBatteryExemption()
    }

    private fun requestBatteryExemption() {
        if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) { toast("التطبيق مُستثنى بالفعل ✓"); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

>>>>>>> 540a325b2a54ee83d0f851610ccb86cb6802011f
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
