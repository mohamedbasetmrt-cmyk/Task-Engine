// TaskEdgeGlowView.kt — Axon Task Engine: مؤشر "computer use نشط" (off-white edge glow)
// طبقة شفافة تمامًا في الوسط — ترسم توهجًا ناعمًا (blur حقيقي) على الحواف الأربع فقط،
// بدون أي عناصر متحركة على المحيط (لا دوائر، لا "traveler") — خط حافة رفيع خافت بس.
// خفيف: بلا bitmaps/screenshots/AI — Canvas + BlurMaskFilter + ValueAnimator واحد (~60fps).
// ملاحظة: BlurMaskFilter محتاج software layer (مش hardware) عشان يترسم فعليًا.
package com.example.app_abdelbaset

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

class TaskEdgeGlowView(context: Context) : View(context) {

    /** Off-white دافئ (قرار المستخدم — بدل الأزرق) */
    companion object {
        const val OFF_WHITE = 0xFFF2EDE3.toInt()
    }

    enum class GlowMode { THINKING, EXECUTING, SUCCESS, ERROR }

    @Volatile var mode: GlowMode = GlowMode.THINKING

    private var phase = 0f
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val density = resources.displayMetrics.density
    private val cornerPx = 34f * density

    // خط رفيع خافت في القلب + هالة blur واسعة حواليه — إحساس "توهج" حقيقي بدل حلقات صلبة
    private val coreStrokePx = 3.5f * density
    private val coreInset = 5f * density
    private val coreBlur = BlurMaskFilter(9f * density, BlurMaskFilter.Blur.NORMAL)

    private val haloStrokePx = 26f * density
    private val haloInset = 0f
    private val haloBlur = BlurMaskFilter(36f * density, BlurMaskFilter.Blur.NORMAL)

    init {
        // مهم: الخلفية شفافة تمامًا — لا تعتيم للتطبيق تحتها
        setBackgroundColor(Color.TRANSPARENT)
        // BlurMaskFilter بيتجاهله الـ hardware layer، فلازم software عشان الـ glow يبان فعلاً
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setGlowMode(m: GlowMode) {
        if (mode != m) {
            mode = m
            // ERROR أسرع (نبضتين)، THINKING أبطأ — الباقي وسط
            restartAnim()
        }
    }

    private fun animDuration(): Long = when (mode) {
        GlowMode.THINKING -> 4600L
        GlowMode.EXECUTING -> 3000L
        GlowMode.SUCCESS -> 1500L
        GlowMode.ERROR -> 750L
    }

    private fun alphaRange(): Pair<Float, Float> = when (mode) {
        GlowMode.THINKING -> 0.10f to 0.20f
        GlowMode.EXECUTING -> 0.25f to 0.45f
        GlowMode.SUCCESS -> 0.35f to 0.65f
        GlowMode.ERROR -> 0.30f to 0.55f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnim()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    private fun startAnim() {
        if (animator?.isStarted == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = animDuration()
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun restartAnim() {
        stopAnim()
        startAnim()
    }

    private fun stopAnim() {
        try { animator?.cancel() } catch (_: Exception) {}
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val (aMin, aMax) = alphaRange()
        // نبض ناعم — بلا flashing: جيب واحد مستمر
        val pulse = 0.5f + 0.5f * sin((2f * PI * phase).toFloat())
        val alpha = aMin + (aMax - aMin) * pulse
        drawSoftEdges(canvas, w, h, alpha)
    }

    /**
     * توهج حقيقي على الحواف: هالة blur واسعة خافتة + خط رفيع أوضح جواها،
     * كل واحد بـ BlurMaskFilter مستقل — بدون أي حلقات/دوائر متحركة على المحيط.
     */
    private fun drawSoftEdges(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        // الهالة الخارجية — واسعة وخافتة جدًا (الإحساس الرئيسي بالـ blur)
        paint.strokeWidth = haloStrokePx
        paint.maskFilter = haloBlur
        paint.color = OFF_WHITE
        paint.alpha = ((alpha * 0.8f * 255f).toInt()).coerceIn(0, 255)
        canvas.drawRoundRect(
            RectF(haloInset, haloInset, w - haloInset, h - haloInset), cornerPx, cornerPx, paint
        )

        // خط رفيع في القلب — يدي إحساس "حافة" واضحة بدون حدّة
        paint.strokeWidth = coreStrokePx
        paint.maskFilter = coreBlur
        paint.color = OFF_WHITE
        paint.alpha = ((alpha * 255f).toInt()).coerceIn(0, 255)
        canvas.drawRoundRect(
            RectF(coreInset, coreInset, w - coreInset, h - coreInset), cornerPx, cornerPx, paint
        )

        paint.maskFilter = null
    }
}
