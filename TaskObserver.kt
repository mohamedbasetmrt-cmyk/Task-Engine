// TaskObserver.kt — Axon Task Engine V1: ملاحظة الحالة (قراءة فقط)
// Fallback chain: نفس AccessibilityService أولًا → UsageStatsManager ثانيًا → unknown أخيرًا.
// لا نكذب أبدًا: المصدر يُسجَّل مع كل قراءة.
package com.example.app_abdelbaset

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

object TaskObserver {

    data class Observation(
        val state: ObservedState,
        /** "a11y" | "usage-stats" | "unknown" */
        val source: String
    )

    /** يرجع package الواجهة الحالية — "unknown" لو غير متاح (شاشة آمنة / خدمة مطفية) */
    fun observe(context: Context): ObservedState = observeWithSource(context).state

    fun observeWithSource(context: Context): Observation {
        // 1) نفس AccessibilityService الموجود — قراءة فقط (الأدق)
        try {
            val svc = AxonAccessibilityService.instance
            if (svc == null) {
                Log.w("TaskEngine", "WARN @observe — AxonAccessibilityService.instance is NULL (service not bound — toggle Axon Assistant OFF/ON)")
            } else {
                val root = try { svc.rootInActiveWindow } catch (e: Exception) {
                    Log.w("TaskEngine", "WARN @observe — rootInActiveWindow threw: ${e.message}")
                    null
                }
                val pkg = try { root?.packageName?.toString() } catch (_: Exception) { null }
                if (!pkg.isNullOrBlank()) return Observation(ObservedState(pkg), "a11y")
                Log.w("TaskEngine", "WARN @observe — a11y root empty (secure screen?) — trying usage-stats fallback")
            }
        } catch (e: Exception) {
            Log.e("TaskEngine", "FAIL @observe — a11y path threw: ${e.message}", e)
        }
        // 2) fallback: UsageStatsManager (نفس طريقة getForegroundApp الموجودة — قراءة فقط)
        try {
            val usm = context.applicationContext
                .getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                val end = System.currentTimeMillis()
                val begin = end - 60_000L
                val stats = try {
                    usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
                } catch (se: SecurityException) {
                    Log.w("TaskEngine", "WARN @observe — Usage Access not granted (Settings → Usage Access) — cannot use usage-stats fallback")
                    null
                }
                val top = stats?.filter { it.lastTimeUsed > 0 }?.maxByOrNull { it.lastTimeUsed }
                if (top != null && (end - top.lastTimeUsed) < 60_000L && top.packageName.isNotBlank()) {
                    Log.d("TaskEngine", "OBSERVE via usage-stats: ${top.packageName}")
                    return Observation(ObservedState(top.packageName), "usage-stats")
                }
                if (top != null) {
                    Log.w("TaskEngine", "WARN @observe — usage-stats stale (last=${top.packageName} ${(end - top.lastTimeUsed) / 1000}s ago) → unknown")
                } else {
                    Log.w("TaskEngine", "WARN @observe — usage-stats empty (permission missing?) → unknown")
                }
            }
        } catch (e: Exception) {
            Log.w("TaskEngine", "WARN @observe — usage-stats path threw: ${e.message}")
        }
        // 3) لا نكذب — نرجع unknown والـ verifier يتعامل معه كفشل تحقق برسالة واضحة
        return Observation(ObservedState("unknown"), "unknown")
    }
}
