// TaskActions.kt — Axon Task Engine V1: أفعال حتمية (استدعاء للموجود فقط — لا تعديل فيه)
// نجاح الإطلاق ≠ تحقق الهدف — الـ verification المستقلة هي الحكم.
package com.example.app_abdelbaset

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

interface TaskAction {
    val name: String
    fun execute(context: Context): ActionResult
}

/** يفتح Settings عبر نفس قدرة MobileActionExecutor الموجودة (instantiate + استدعاء فقط) */
class OpenSettingsAction : TaskAction {
    override val name = "OPEN_SETTINGS"
    override fun execute(context: Context): ActionResult {
        return try {
            val frame = JSONObject().apply {
                put("action", "open_app")
                put("params", JSONObject().apply { put("app_name", "Settings") })
            }
            val latch = CountDownLatch(1)
            var threw: Exception? = null
            try {
                MobileActionExecutor(context.applicationContext).execute(frame) { }
            } catch (e: Exception) {
                threw = e
            }
            // الـ execute ينشر على الـ main handler — نمهل الإطلاق لحظة ثم نرجع
            try { latch.await(800, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            if (threw != null) ActionResult(false, "launch threw: ${threw.message}")
            else ActionResult(true, "launch dispatched via MobileActionExecutor")
        } catch (e: Exception) {
            Log.e("TaskEngine", "FAIL @action:OPEN_SETTINGS — ${e.message}", e)
            ActionResult(false, e.message ?: "open failed")
        }
    }
}

/** رجوع للشاشة السابقة عبر نفس AccessibilityService الموجود (read-only instance) */
class BackAction : TaskAction {
    override val name = "BACK"
    override fun execute(context: Context): ActionResult {
        return try {
            val svc = AxonAccessibilityService.instance
                ?: return ActionResult(false, "Accessibility service not enabled (enable Axon Assistant first)")
            val ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            // مهلة قصيرة لوقوع الأثر قبل الـ observation
            try { Thread.sleep(600) } catch (_: Exception) {}
            if (!ok) Log.w("TaskEngine", "WARN @action:BACK — back rejected by system")
            ActionResult(ok, if (ok) "back dispatched" else "back rejected by system")
        } catch (e: Exception) {
            Log.e("TaskEngine", "FAIL @action:BACK — ${e.message}", e)
            ActionResult(false, e.message ?: "back failed")
        }
    }
}

/** فعل فشل مقصود لاختبار V1-T3 — المطلوب أن يفشل ويُسجَّل كفشل */
class FailTestAction : TaskAction {
    override val name = "FAIL_TEST"
    override fun execute(context: Context): ActionResult {
        return ActionResult(false, "nonexistent system destination (expected failure for V1-T3)")
    }
}

object TaskActionFactory {
    fun forStep(kind: TaskStepKind): TaskAction? = when (kind) {
        TaskStepKind.OPEN_SETTINGS -> OpenSettingsAction()
        TaskStepKind.BACK -> BackAction()
        TaskStepKind.FAIL_TEST -> FailTestAction()
        // خطوات الـ VERIFY لا تنفذ فعلًا — تُعالج في الـ Verifier
        TaskStepKind.VERIFY_SETTINGS, TaskStepKind.VERIFY_RETURN -> null
        // خطوات V2 البصرية تُنفَّذ في الـ Engine (تحتاج snapshot حي) — ليست هنا
        else -> null
    }
}

// ═════════════════════════════════════════════════════════════════════
//  V2: أفعال بصرية برقم عنصر (تُبنى من قرار الـ Reasoner + bounds القايمة)
// ═════════════════════════════════════════════════════════════════════

/** أدوات مشتركة: إيجاد node بحدود مطابقة + tap إيمائي كبديل */
object TaskUiActuator {

    /**
     * يعيد إيجاد عنصر بعد أن تتحرك الـ layout/الكيبورد. الـ bounds مجرد signal أخير؛
     * الأولوية للـ id والنص والوصف والـ class، لذلك لا يرتبط action برقم box عابر.
     */
    fun findNodeByFingerprint(root: AccessibilityNodeInfo?, want: A11yNodeItem): AccessibilityNodeInfo? {
        if (root == null) return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 64) return
            val id = try { node.viewIdResourceName ?: "-" } catch (_: Exception) { "-" }
            val text = try { node.text?.toString() ?: "-" } catch (_: Exception) { "-" }
            val desc = try { node.contentDescription?.toString() ?: "-" } catch (_: Exception) { "-" }
            val cls = try { node.className?.toString() ?: "-" } catch (_: Exception) { "-" }
            var score = 0
            if (want.viewId != "-" && want.viewId == id) score += 55
            if (want.text != "-" && want.text == text) score += 42
            if (want.contentDesc != "-" && want.contentDesc == desc) score += 42
            if (want.className == cls) score += 8
            val r = Rect()
            try { node.getBoundsInScreen(r) } catch (_: Exception) {}
            val old = TaskUiSnapshot.parseBounds(want.rawBounds.ifBlank { want.bounds })
            if (old != null && r.width() > 0 && r.height() > 0) {
                val overlap = Rect(r)
                if (overlap.intersect(old)) {
                    val inter = overlap.width().toLong() * overlap.height()
                    val union = r.width().toLong() * r.height() + old.width().toLong() * old.height() - inter
                    if (union > 0 && inter * 100 / union >= 45) score += 14
                }
            }
            if (score > bestScore && (node.isClickable || node.isEditable || want.editable)) {
                best?.let { try { it.recycle() } catch (_: Exception) {} }
                best = AccessibilityNodeInfo.obtain(node)
                bestScore = score
            }
            val count = try { node.childCount } catch (_: Exception) { 0 }
            for (i in 0 until count) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                visit(child, depth + 1)
                try { child.recycle() } catch (_: Exception) {}
            }
        }
        visit(root, 0)
        if (bestScore < 22) {
            best?.let { try { it.recycle() } catch (_: Exception) {} }
            return null
        }
        return best
    }

    fun findNodeByBounds(root: AccessibilityNodeInfo?, want: Rect): AccessibilityNodeInfo? {
        if (root == null) return null
        val r = Rect()
        try { root.getBoundsInScreen(r) } catch (_: Exception) { return null }
        if (r == want) return root
        val count = try { root.childCount } catch (_: Exception) { 0 }
        for (i in 0 until count) {
            val child = try { root.getChild(i) } catch (_: Exception) { null }
            if (child != null) {
                val found = findNodeByBounds(child, want)
                if (found != null) {
                    if (found != child) try { child.recycle() } catch (_: Exception) {}
                    return found
                }
                try { child.recycle() } catch (_: Exception) {}
            }
        }
        return null
    }

    fun gestureTap(svc: AccessibilityService, x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                .build()
            svc.dispatchGesture(gesture, null, null)
        } catch (_: Exception) { false }
    }

    /** النصوص والصور غالبًا children غير clickable داخل row clickable؛ جرّب الأب أولًا. */
    fun clickNodeOrClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth++ < 6) {
            val clicked = try {
                if (current.isClickable) current.performAction(AccessibilityNodeInfo.ACTION_CLICK) else false
            } catch (_: Exception) { false }
            if (clicked) return true
            val parent = try { current.parent } catch (_: Exception) { null }
            if (current != node) try { current.recycle() } catch (_: Exception) {}
            current = parent
        }
        return false
    }

    fun gestureSwipe(svc: AccessibilityService, x0: Float, y0: Float, x1: Float, y1: Float,
                      durationMs: Long = 300): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val path = Path().apply { moveTo(x0, y0); lineTo(x1, y1) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            svc.dispatchGesture(gesture, null, null)
        } catch (_: Exception) { false }
    }
}

/** ضغط عنصر: node.click أولًا، ثم tap إيمائي على المركز كبديل */
class TapElementAction(
    private val bounds: Rect,
    private val label: String = "",
    private val fingerprint: A11yNodeItem? = null
) : TaskAction {
    override val name = "TAP_ELEMENT"
    override fun execute(context: Context): ActionResult {
        // guard: رفض منطقة شريط النظام السفلي (دوسات خطيرة زي @(x,2277))
        try {
            val h = context.resources.displayMetrics.heightPixels
            if (bounds.exactCenterY() > h - 120) {
                Log.w("TaskEngine", "WARN @action:TAP_ELEMENT — refused nav-region tap ${bounds.toShortString()}")
                return ActionResult(false, "refused: bounds in system nav region ${bounds.toShortString()}")
            }
        } catch (_: Exception) {}
        return try {
            val svc = AxonAccessibilityService.instance
                ?: return ActionResult(false, "Accessibility service not enabled")
            val root = try { svc.rootInActiveWindow } catch (e: Exception) {
                return ActionResult(false, "no active window: ${e.message}")
            } ?: return ActionResult(false, "no active window")
            val node = fingerprint?.let { TaskUiActuator.findNodeByFingerprint(root, it) }
                ?: TaskUiActuator.findNodeByBounds(root, bounds)
            if (node != null) {
                val ok = TaskUiActuator.clickNodeOrClickableAncestor(node)
                try { Thread.sleep(500) } catch (_: Exception) {}
                if (ok) return ActionResult(true, "node click ${label.ifBlank { bounds.toShortString() }}")
            }
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val ok = TaskUiActuator.gestureTap(svc, cx, cy)
            try { Thread.sleep(500) } catch (_: Exception) {}
            if (ok) ActionResult(true, "gesture tap @(${"%.0f".format(cx)},${"%.0f".format(cy)})")
            else ActionResult(false, "tap failed (node + gesture)")
        } catch (e: Exception) {
            Log.e("TaskEngine", "FAIL @action:TAP_ELEMENT — ${e.message}", e)
            ActionResult(false, e.message ?: "tap failed")
        }
    }
}

/** كتابة نص في عنصر: setText على الـ node، ثم بديل tap+focused-input */
class TypeElementAction(
    private val bounds: Rect,
    private val text: String,
    private val label: String = "",
    private val fingerprint: A11yNodeItem? = null
) : TaskAction {
    override val name = "TYPE_ELEMENT"
    override fun execute(context: Context): ActionResult {
        if (text.isBlank()) return ActionResult(false, "blank text")
        return try {
            val svc = AxonAccessibilityService.instance
                ?: return ActionResult(false, "Accessibility service not enabled")
            val root = try { svc.rootInActiveWindow } catch (e: Exception) {
                return ActionResult(false, "no active window: ${e.message}")
            } ?: return ActionResult(false, "no active window")
            val node = fingerprint?.let { TaskUiActuator.findNodeByFingerprint(root, it) }
                ?: TaskUiActuator.findNodeByBounds(root, bounds)
            if (node != null) {
                try { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } catch (_: Exception) {}
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val ok = try { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) } catch (_: Exception) { false }
                try { Thread.sleep(500) } catch (_: Exception) {}
                if (ok) return ActionResult(true, "setText ${label.ifBlank { bounds.toShortString() }} (${text.length} chars)")
            }
            // بديل: tap ثم كتابة في الحقل المركّز
            TaskUiActuator.gestureTap(svc, bounds.exactCenterX(), bounds.exactCenterY())
            try { Thread.sleep(700) } catch (_: Exception) {}
            val root2 = try { svc.rootInActiveWindow } catch (_: Exception) { null }
            val focused = try { root2?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
            if (focused != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val ok = try { focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) } catch (_: Exception) { false }
                try { Thread.sleep(500) } catch (_: Exception) {}
                if (ok) return ActionResult(true, "setText via focused input (${text.length} chars)")
            }
            ActionResult(false, "type failed (node + focused fallback)")
        } catch (e: Exception) {
            Log.e("TaskEngine", "FAIL @action:TYPE_ELEMENT — ${e.message}", e)
            ActionResult(false, e.message ?: "type failed")
        }
    }
}

/** سحب — خفيف طبيعي افتراضيًا (~350px) للحالات الاستكشافية، وأطول عند الطلب */
class SwipeAction(
    private val direction: String = "up",
    private val distancePx: Float = 0f,
    private val durationMs: Long = 300
) : TaskAction {
    override val name = "SWIPE"
    override fun execute(context: Context): ActionResult {
        return try {
            val svc = AxonAccessibilityService.instance
                ?: return ActionResult(false, "Accessibility service not enabled")
            val dm = context.resources.displayMetrics
            val cx = dm.widthPixels / 2f
            val cy = dm.heightPixels / 2f
            val d = if (distancePx > 0) distancePx
            else (dm.heightPixels / 4f).coerceIn(300f, 1200f)
            val (x0, y0, x1, y1) = when (direction.lowercase()) {
                "up" -> Quad(cx, cy + d / 2, cx, cy - d / 2)
                "down" -> Quad(cx, cy - d / 2, cx, cy + d / 2)
                "left" -> Quad(cx + d / 2, cy, cx - d / 2, cy)
                else -> Quad(cx - d / 2, cy, cx + d / 2, cy)
            }
            val ok = TaskUiActuator.gestureSwipe(svc, x0, y0, x1, y1, durationMs)
            try { Thread.sleep(800) } catch (_: Exception) {}
            if (ok) ActionResult(true, "swipe $direction (${d.toInt()}px)")
            else ActionResult(false, "swipe rejected")
        } catch (e: Exception) {
            Log.e("TaskEngine", "FAIL @action:SWIPE — ${e.message}", e)
            ActionResult(false, e.message ?: "swipe failed")
        }
    }

    private data class Quad(val x0: Float, val y0: Float, val x1: Float, val y1: Float)
}
