// TaskVerifier.kt — Axon Task Engine V1: تحقق مستقل (نجاح الفعل ≠ تحقق الهدف)
package com.example.app_abdelbaset

import android.content.Context

object TaskVerifier {

    fun verify(context: Context, step: TaskStep, observed: ObservedState): VerifyResult {
        return when (step.kind) {
            TaskStepKind.VERIFY_SETTINGS -> {
                val pkg = observed.foregroundPackage.lowercase()
                val pass = pkg.contains("settings") || pkg == "com.android.settings"
                VerifyResult(pass, expected = "Settings foreground", actual = observed.foregroundPackage)
            }
            TaskStepKind.VERIFY_RETURN -> {
                // بعد BACK المفروض نرجع لتطبيقنا (أي package غير settings يعتبر عودة مبدئية،
                // والأدق: package تطبيقنا نفسه)
                val own = context.packageName
                val pass = observed.foregroundPackage == own ||
                        (!observed.foregroundPackage.contains("settings", ignoreCase = true) &&
                                observed.foregroundPackage != "unknown")
                VerifyResult(pass, expected = "return to $own", actual = observed.foregroundPackage)
            }
            // خطوات الفعل نفسها: التحقق هنا يعني "الفعل أُطلق بنجاح" — والـ VERIFY اللاحقة هي الحكم الحقيقي
            TaskStepKind.OPEN_SETTINGS, TaskStepKind.BACK,
            TaskStepKind.OPEN_APP, TaskStepKind.UI_CLICK, TaskStepKind.UI_TYPE, TaskStepKind.UI_BACK ->
                VerifyResult(true, expected = "dispatched", actual = "dispatched")
            TaskStepKind.FAIL_TEST ->
                VerifyResult(false, expected = "impossible destination", actual = "launch rejected")
            // خطوات V2 الخاصة (WAIT/VERIFY_TEXT/VERIFY_CHANGED) تُعالج مباشرة في الـ Engine
            // لأنها تحتاج snapshot/tree — الوصول هنا يعني خطأ داخلي
            else ->
                VerifyResult(false, expected = "engine-handled", actual = "reached-generic-verifier:${step.kind}")
        }
    }

    /**
     * تحقق مستقل: هل النص المتوقع ظاهر حاليًا في شجرة الشاشة؟
     * يُستدعى من الـ Engine لخطوات VERIFY_TEXT (بعد snapshot طازج).
     */
    fun verifyTextPresent(nodes: List<A11yNodeItem>, expected: String): VerifyResult {
        if (expected.isBlank()) return VerifyResult(false, expected = "non-empty text", actual = "blank")
        val hit = nodes.firstOrNull { n ->
            n.text.contains(expected, ignoreCase = true) ||
                    n.contentDesc.contains(expected, ignoreCase = true)
        }
        return if (hit != null) {
            VerifyResult(true, expected = "text present: $expected",
                actual = "#${nodes.indexOf(hit)} ${hit.text.ifBlank { hit.contentDesc }.take(60)}")
        } else {
            VerifyResult(false, expected = "text present: $expected",
                actual = "not found in ${nodes.size} nodes")
        }
    }
}
