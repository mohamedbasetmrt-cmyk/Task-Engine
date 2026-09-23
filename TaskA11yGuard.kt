// TaskA11yGuard.kt — Axon Task Engine V1: تذكّر منح الـ Accessibility وكشف فصله بعد الـ build
// ملاحظة صريحة: الأندرويد نفسه هو اللي بيفصل الـ Accessibility service عند full-uninstall
// أو اختلاف التوقيع — مفيش كود يقدر "يجبر" النظام يحتفظ بيه. اللي نقدر عليه:
// 1) نتذكر إنك منحته قبل كده (flag persistent حتى بعد الـ update)
// 2) نكشف لحظة الفصل ونطلع بانر "اتقفل بعد الـ build — دوس للتفعيل" بضغطة واحدة.
package com.example.app_abdelbaset

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Toast

object TaskA11yGuard {

    private const val TAG = "TaskEngine"
    private const val PREFS = "axon_task_prefs"
    private const val KEY_WAS_GRANTED = "a11y_was_granted"

    /** تُنادى كل مرة نشوف الخدمة مربوطة — تثبت إن المنح حصل */
    fun markGranted(context: Context) {
        try {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!p.getBoolean(KEY_WAS_GRANTED, false)) {
                p.edit().putBoolean(KEY_WAS_GRANTED, true).apply()
                Log.d(TAG, "A11yGuard: grant recorded (will survive updates)")
            }
        } catch (_: Exception) {}
    }

    fun wasGrantedBefore(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WAS_GRANTED, false)
    } catch (_: Exception) { false }

    /** الحالة الحالية الفعلية (read-only — نفس كشف شاشة الـ Screen Reader Test) */
    fun isEnabledNow(context: Context): Boolean = try {
        A11yScreenReader.isEnabledInSystem(context) || A11yScreenReader.isServiceConnected()
    } catch (_: Exception) { A11yScreenReader.isServiceConnected() }

    /** true = كنت مانحه قبل كده بس حاليًا مفصول (حالة ما بعد الـ build غالبًا) */
    fun needsReEnable(context: Context): Boolean =
        wasGrantedBefore(context) && !isEnabledNow(context)

    /** يفتح شاشة الـ Accessibility بتاعة النظام بضغطة واحدة */
    fun openSystemSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.w(TAG, "A11yGuard open settings failed: ${e.message}")
            try { Toast.makeText(context, "افتح Accessibility وفعّل Axon Assistant", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
    }
}
