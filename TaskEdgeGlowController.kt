// TaskEdgeGlowController.kt — Axon Task Engine: مدير طبقة الـ edge glow (singleton)
// القواعد المتفق عليها:
// - الـ glow يظهر طول ما الـ agent شغال — جوه أو بره صفحة Task (مش بيتخفى في الصفحة تاني)
//   inTaskScreen/appForeground لسه متتبَّعين لأغراض اللوج فقط، مش للإخفاء
// - SUCCESS/ERROR: نبضة ~1.5s ثم اختفاء (ERROR = نبضتين سريعتين بنفس الـ off-white)
// - بلا overlay permission → سكوت تام (الشغل يكمل عادي)
// - نسخة واحدة فقط + تنظيف إجباري (بلا orphaned views لو الـ service مات)
package com.example.app_abdelbaset

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

object TaskEdgeGlowController {

    private const val TAG = "TaskEngine"
    const val PULSE_MS = 1500L

    private val main = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var view: TaskEdgeGlowView? = null
    private var attached = false

    /** true = المستخدم جوه شاشة Task (composition) */
    @Volatile var inTaskScreen = false
        private set

    /**
     * true = تطبيقنا في الواجهة. يُحدَّث من MainActivity.onResume/onPause.
     * ده الإصلاح الجوهري: لما الـ task يفتح Settings فوقنا، الـ Activity تتغطى
     * (onPause) لكن شاشة Task تفضل mounted — فالظهور الفعلي هو الحكم، مش التركيبة.
     */
    @Volatile var appForeground = true
        private set

    @Volatile private var taskRunning = false
    @Volatile private var lastMode = TaskEdgeGlowView.GlowMode.THINKING

    private var pulseHide: Runnable? = null

    /** تُنادى من TaskForegroundService (أو أول حدث) — تخزين context فقط، بلا إظهار */
    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun setInTaskScreen(inTask: Boolean) {
        inTaskScreen = inTask
        main.post { refresh() }
    }

    fun setAppForeground(fg: Boolean) {
        appForeground = fg
        main.post { refresh() }
    }

    /** نقطة الربط مع TaskEngine.emit — آمنة من أي thread */
    fun onTaskEvent(type: TaskEventType) {
        main.post { handleOnMain(type) }
    }

    private fun handleOnMain(type: TaskEventType) {
        Log.d(TAG, "EdgeGlow event=$type running=$taskRunning inTask=$inTaskScreen fg=$appForeground")
        when (type) {
            TaskEventType.TASK_CREATED -> {
                taskRunning = true
                lastMode = TaskEdgeGlowView.GlowMode.THINKING
                cancelPulse()
                refresh()
            }
            TaskEventType.TASK_STARTED,
            TaskEventType.STEP_STARTED,
            TaskEventType.STEP_COMPLETED -> {
                taskRunning = true
                lastMode = TaskEdgeGlowView.GlowMode.EXECUTING
                cancelPulse()
                refresh()
            }
            TaskEventType.STEP_FAILED -> {
                // فشل خطوة أثناء التنفيذ — يبقى EXECUTING (الـ task لسه شغال، الحكم النهائي عند TASK_FAILED)
                taskRunning = true
                refresh()
            }
            TaskEventType.TASK_COMPLETED -> {
                taskRunning = false
                pulse(TaskEdgeGlowView.GlowMode.SUCCESS)
            }
            TaskEventType.TASK_FAILED -> {
                taskRunning = false
                pulse(TaskEdgeGlowView.GlowMode.ERROR)
            }
            TaskEventType.TASK_CANCELLED -> {
                taskRunning = false
                cancelPulse()
                hideView()
            }
        }
    }

    private fun refresh() {
        val reason = suppressedReason()
        if (reason != null) {
            Log.d(TAG, "EdgeGlow hidden ($reason)")
            hideView()
            return
        }
        showView(lastMode)
    }

    /** نبضة 1.5s ثم اختفاء — تُعرض فقط لو المستخدم بره شاشة Task ومسموح بالـ overlay */
    private fun pulse(mode: TaskEdgeGlowView.GlowMode) {
        cancelPulse()
        val reason = suppressedReason()
        if (reason != null) {
            Log.d(TAG, "EdgeGlow pulse $mode skipped ($reason)")
            hideView()
            return
        }
        showView(mode)
        val r = Runnable {
            pulseHide = null
            hideView()
        }
        pulseHide = r
        main.postDelayed(r, PULSE_MS)
    }

    /**
     * القاعدة النهائية: glow يظهر طول ما الـ agent شغال + overlay مسموح.
     * inTaskScreen/appForeground متتبَّعين للوج فقط — مش بيخفوا الـ glow.
     */
    private fun suppressedReason(): String? = when {
        !taskRunning -> "idle"
        !overlayAllowed() -> "no-overlay-permission (امنح Display over other apps)"
        else -> null
    }

    private fun cancelPulse() {
        try { pulseHide?.let { main.removeCallbacks(it) } } catch (_: Exception) {}
        pulseHide = null
    }

    private fun overlayAllowed(): Boolean {
        val ctx = appContext ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true
        } catch (_: Exception) { false }
    }

    private fun windowManager(): WindowManager? = try {
        appContext?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    } catch (_: Exception) { null }

    private fun showView(mode: TaskEdgeGlowView.GlowMode) {
        val wm = windowManager() ?: return
        val existing = view
        if (attached && existing != null) {
            try { existing.setGlowMode(mode) } catch (_: Exception) {}
            return
        }
        if (attached) return // guard: لا نسختين أبدًا
        try {
            val ctx = appContext ?: return
            val v = TaskEdgeGlowView(ctx).apply {
                setGlowMode(mode)
                // بيخلي WindowManager يمد نافذة الـ overlay لحد حواف الموبايل الفعلية
                // (تحت الـ status/navigation bar) بدل ما توقف عند frame الـ app بس
                @Suppress("DEPRECATION")
                systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                // لمس يمر 100% للتطبيق تحتها: لا فوكس، لا لمس، لا مودال
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.FILL
                // edge-to-edge حتى مع الـ notch/cutout
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            wm.addView(v, params)
            view = v
            attached = true
            Log.d(TAG, "EdgeGlow shown mode=$mode (inTaskScreen=$inTaskScreen)")
        } catch (e: Exception) {
            Log.w(TAG, "EdgeGlow show failed: ${e.message}")
            attached = false
            view = null
        }
    }

    private fun hideView() {
        cancelPulse()
        if (!attached) return
        try {
            val wm = windowManager()
            val v = view
            if (wm != null && v != null) {
                try { wm.removeView(v) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        view = null
        attached = false
    }

    /** تنظيف إجباري — تُنادى من TaskForegroundService.onDestroy/onTaskRemoved */
    fun forceHide() {
        taskRunning = false
        if (Looper.myLooper() == Looper.getMainLooper()) hideView()
        else main.post { hideView() }
    }

    // ── للاختبار اليدوي/الآلي الخفيف ──
    fun isAttachedForTest(): Boolean = attached
}
