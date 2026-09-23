// TaskUiSnapshot.kt — Axon Task Engine V2: لقطة بصرية عند الطلب
// screenshot (عبر takeScreenshot API على نفس accessibility instance — بلا تعديل فيه)
// + boxes مرقمة من الـ bounds + قايمة A11yNodeItem كنص + توقيتات كل مرحلة.
// أي فشل في الصورة → tree-only + WARN مسجل (الـ prompt المعتمد يدعم الـ fallback).
package com.example.app_abdelbaset

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object TaskUiSnapshot {

    private const val TAG = "TaskEngine"
    private const val MAX_NODES = 300
    private const val MAX_LIST_ENTRIES = 150
    private const val MAX_BOXES = 80
    private const val MAX_WIDTH = 720
    private const val JPEG_Q = 80

    // اقتراحات مساعد النظام ليست عادةً نتيجة البحث المطلوبة؛ نعلّمها للـ reasoner
    // بدل حذفها، حتى تظل قابلة للاختيار حين يطلبها المستخدم صراحةً.
    private val SYSTEM_SUGGESTION_ID_HINTS = listOf("meta_ai", "ai_assistant", "search_suggestion")

    data class UiSnapshot(
        val nodes: List<A11yNodeItem>,
        val elementText: String,
        /** JPEG مضغوط (720w) عليه boxes مرقمة — null لو الصورة فشلت */
        val boxedB64: String?,
        val shotBytes: Int,
        val treeMs: Long,
        val shotMs: Long,
        val boxesDrawn: Int,
        val shotOk: Boolean,
        val shotNote: String,
        /** بصمة الشجرة لمقارنة "الشاشة اتغيرت ولا لأ" */
        val signature: String,
        val pkg: String
    )

    /**
     * لقطة كاملة — blocking، تُنادى من خيط خلفية (TaskEngine executor).
     * تسجل سطر SNAPSHOT بالصيغة المتفق عليها.
     */
    fun captureBlocking(context: Context, taskId: String, stepId: String, wantShot: Boolean = true): UiSnapshot {
        // ── 1) الشجرة (نفس طريقة A11yScreenTest — قراءة فقط) ──
        val t0 = System.currentTimeMillis()
        val cap = try { A11yScreenReader.captureNow(MAX_NODES) } catch (e: Exception) {
            Log.w(TAG, "WARN @snapshot:$stepId — tree capture threw: ${e.message}")
            null
        }
        val treeMs = System.currentTimeMillis() - t0
        val nodes = cap?.nodes.orEmpty()
        val pkg = cap?.packageName ?: "unknown"
        val dm = try { context.resources.displayMetrics } catch (_: Exception) { null }
        val elementText = buildElementText(nodes, dm?.widthPixels ?: 0, dm?.heightPixels ?: 0)
        val sig = signatureOf(nodes)

        // ── 2) الصورة ──
        var b64: String? = null
        var bytes = 0
        var boxes = 0
        var ok = false
        var note = "skipped"
        val s0 = System.currentTimeMillis()
        if (wantShot) {
            try {
                val bmp = takeScreenshotBitmap(context)
                if (bmp != null) {
                    val drawn = drawBoxes(bmp, nodes)
                    boxes = drawn.first
                    val jpg = compress(bmp)
                    bytes = jpg.size
                    b64 = Base64.encodeToString(jpg, Base64.NO_WRAP)
                    ok = true
                    note = "ok"
                    try { bmp.recycle() } catch (_: Exception) {}
                } else {
                    note = lastShotError ?: "null-bitmap"
                }
            } catch (e: Exception) {
                note = "exception:${e.message?.take(60)}"
                Log.w(TAG, "WARN @snapshot:$stepId — screenshot path threw: ${e.message}")
            }
        }
        val shotMs = System.currentTimeMillis() - s0
        TaskExecutionLogger.log(taskId,
            "SNAPSHOT $stepId — nodes=${nodes.size} boxes=$boxes shot=${bytes / 1024}KB " +
                    "shotMs=$shotMs treeMs=$treeMs shotOk=$ok ($note) pkg=$pkg")
        if (!ok && wantShot) {
            TaskExecutionLogger.logWarn(taskId, "snapshot:$stepId",
                "screenshot unavailable ($note) — proceeding tree-only (prompt fallback active)")
        }
        return UiSnapshot(nodes, elementText, b64, bytes, treeMs, shotMs, boxes, ok, note, sig, pkg)
    }

    // ── شجرة كنص (نفس حقول A11yNodeItem) + تعليم الحاويات المليانة الشاشة (full) ──
    private fun buildElementText(nodes: List<A11yNodeItem>, sw: Int, sh: Int): String {
        val sb = StringBuilder()
        nodes.take(MAX_LIST_ENTRIES).forEachIndexed { i, n ->
            sb.append('#').append(i).append(' ').append(n.className.substringAfterLast('.'))
            // label يأتي من الـ reader بعد طي children داخل الـ row/button؛ هو أوثق من text الخام.
            if (n.label.isNotBlank()) sb.append(" label=\"").append(n.label.take(90)).append('"')
            if (n.text != "-") sb.append(" text=\"").append(n.text.take(60)).append('"')
            if (n.contentDesc != "-") sb.append(" desc=\"").append(n.contentDesc.take(60)).append('"')
            if (n.viewId != "-") sb.append(" id=").append(n.viewId.substringAfterLast('/').take(40))
            sb.append(" role=").append(n.role)
            sb.append(" click=").append(n.clickable).append(" edit=").append(n.editable)
            if (!n.enabled) sb.append(" disabled")
            if (sw > 0 && sh > 0) {
                val r = parseBounds(n.bounds)
                if (r != null && r.width() > sw * 0.85f && r.height() > sh * 0.85f) sb.append(" full")
            }
            val idLower = n.viewId.lowercase()
            if (SYSTEM_SUGGESTION_ID_HINTS.any { idLower.contains(it) }) {
                sb.append(" system-suggestion=prefer-real-result-unless-explicitly-requested")
            }
            sb.append('\n')
        }
        if (nodes.size > MAX_LIST_ENTRIES) sb.append("…(${nodes.size - MAX_LIST_ENTRIES} more)\n")
        return sb.toString()
    }

    private fun signatureOf(nodes: List<A11yNodeItem>): String {
        var h = 17
        for (n in nodes) {
            h = 31 * h + n.className.hashCode()
            // label هو النص المنطقي بعد دمج أطفال الـ row؛ بدونه تغيّر نتيجة بحث/عنوان
            // قد يضيع لأن text الخام للـ parent فارغ.
            h = 31 * h + n.label.hashCode()
            h = 31 * h + n.text.hashCode()
            h = 31 * h + n.contentDesc.hashCode()
            h = 31 * h + n.viewId.hashCode()
            h = 31 * h + n.role.hashCode()
            h = 31 * h + if (n.enabled) 1 else 0
            h = 31 * h + n.bounds.hashCode()
        }
        return "${nodes.size}:${Integer.toHexString(h)}"
    }

    // ── Screenshot عبر takeScreenshot API (عام — بلا تعديل ملف الـ service) ──
    @Volatile private var lastShotError: String? = null

    /** فاصل أدنى بين اللقطات — الـ API نفسه rate-limited (code=3 عند التتابع السريع) */
    @Volatile private var lastShotAt = 0L
    private const val MIN_SHOT_GAP_MS = 800L

    private fun takeScreenshotBitmap(context: Context): Bitmap? {
        lastShotError = null
        if (Build.VERSION.SDK_INT < 30) {
            lastShotError = "api<30"
            return null
        }
        // throttle: لا لقطة قبل 800ms من السابقة (يمنع code=3)
        val gap = System.currentTimeMillis() - lastShotAt
        if (gap < MIN_SHOT_GAP_MS) {
            try { Thread.sleep(MIN_SHOT_GAP_MS - gap) } catch (_: Exception) {}
        }
        val svc = AxonAccessibilityService.instance ?: run {
            lastShotError = "a11y-null"
            return null
        }
        val displayId = try {
            val wm = context.applicationContext.getSystemService(WindowManager::class.java)
            wm?.defaultDisplay?.displayId ?: Display.DEFAULT_DISPLAY
        } catch (_: Exception) { Display.DEFAULT_DISPLAY }
        val latch = CountDownLatch(1)
        val out = AtomicReference<Bitmap?>(null)
        try {
            svc.takeScreenshot(displayId, Executor { it.run() },
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val hb = screenshot.hardwareBuffer
                            val cs = try { screenshot.colorSpace }
                                catch (_: Exception) { null }
                                ?: android.graphics.ColorSpace.get(
                                    android.graphics.ColorSpace.Named.SRGB)
                            val bmp = Bitmap.wrapHardwareBuffer(hb, cs)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            try { hb.close() } catch (_: Exception) {}
                            if (bmp != null) out.set(bmp)
                            else lastShotError = "wrap-null"
                        } catch (e: Exception) {
                            lastShotError = "decode:${e.message?.take(50)}"
                        }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        lastShotError = "code=$errorCode"
                        latch.countDown()
                    }
                })
        } catch (e: Exception) {
            lastShotError = "call:${e.message?.take(50)}"
            return null
        }
        try { latch.await(8, TimeUnit.SECONDS) } catch (_: Exception) {
            lastShotError = "timeout"
            return null
        }
        lastShotAt = System.currentTimeMillis()
        return out.get()
    }

    /** يصغر لـ 720w ثم يرسم boxes مرقمة — يرجع (عدد الـ boxes) */
    private fun drawBoxes(src: Bitmap, nodes: List<A11yNodeItem>): Pair<Int, Bitmap> {
        val scale = if (src.width > MAX_WIDTH) MAX_WIDTH.toFloat() / src.width else 1f
        val bmp = if (scale < 1f) {
            val w = (src.width * scale).toInt()
            val h = (src.height * scale).toInt()
            try { Bitmap.createScaledBitmap(src, w, h, true) } catch (_: Exception) { src }
        } else src
        val canvas = Canvas(bmp)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (3f * bmp.width / 1080f).coerceAtLeast(2f)
            color = Color.WHITE
        }
        val outline = Paint(stroke).apply { color = Color.BLACK; strokeWidth = stroke.strokeWidth + 3f }
        val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (30f * bmp.width / 1080f).coerceAtLeast(20f)
        }
        val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; alpha = 200 }
        var drawn = 0
        for ((i, n) in nodes.withIndex()) {
            if (drawn >= MAX_BOXES) break
            if (!n.clickable && !n.editable) continue
            val r = parseBounds(n.bounds) ?: continue
            if (r.width() <= 2 || r.height() <= 2) continue
            val rs = Rect(
                (r.left * scale).toInt(), (r.top * scale).toInt(),
                (r.right * scale).toInt(), (r.bottom * scale).toInt()
            )
            canvas.drawRect(rs, outline)
            canvas.drawRect(rs, stroke)
            val label = i.toString()
            val tw = textP.measureText(label)
            val th = textP.textSize
            val lx = rs.left.toFloat()
            val ly = (rs.top - th - 8f).coerceAtLeast(0f)
            canvas.drawRect(lx, ly, lx + tw + 16f, ly + th + 12f, labelBg)
            canvas.drawText(label, lx + 8f, ly + th + 2f, textP)
            drawn++
        }
        return drawn to bmp
    }

    fun parseBounds(b: String): Rect? {
        // صيغة A11yScreenTest: "[l,t][r,b]"
        return try {
            val nums = b.replace("[", "").replace("]", ",").replace(" ", "")
                .split(",").filter { it.isNotEmpty() }.map { it.toInt() }
            if (nums.size != 4) return null
            Rect(nums[0], nums[1], nums[2], nums[3])
        } catch (_: Exception) { null }
    }

    private fun compress(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        try { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_Q, out) } catch (_: Exception) {}
        return out.toByteArray()
    }
}
