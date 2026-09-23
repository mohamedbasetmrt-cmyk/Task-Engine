// TaskVisionProvider.kt — Axon Task Engine V2: مزود الـ UI reasoning (Cohere أو Gemini)
// pref منفصل task_vision_provider يتحكم فيه المستخدم من شاشة Task نفسها.
// session جديدة كل مرة (تاريخ نظيف) — نفس نمط AwarenessLlmFactory لكن بمزود محدد.
package com.example.app_abdelbaset

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object TaskVisionProvider {

    private const val TAG = "TaskEngine"
    private const val PREFS = "axon_prefs"
    const val KEY_PROVIDER = "task_vision_provider"

    enum class VisionChoice { COHERE_VISION, COHERE_PLUS, GEMINI, LOCAL }

    fun getChoice(context: Context): VisionChoice = try {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROVIDER, "GEMINI") ?: "GEMINI"
        VisionChoice.values().firstOrNull { it.name == v } ?: VisionChoice.GEMINI
    } catch (_: Exception) { VisionChoice.GEMINI }

    fun setChoice(context: Context, choice: VisionChoice) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PROVIDER, choice.name).apply()
        } catch (_: Exception) {}
    }

    /** يبني المزود المختار — Cohere يُجبر على موديل vision عبر session override (لا يمس prefs المستخدم) */
    fun create(context: Context): LlmProvider = createFor(context, getChoice(context))

    fun createFor(context: Context, choice: VisionChoice): LlmProvider {
        return when (choice) {
            VisionChoice.COHERE_VISION -> CohereLlmProvider(context.applicationContext).apply {
                sessionModelOverride = "command-a-vision-07-2025"
                forceNonToolMode = true
            }
            VisionChoice.COHERE_PLUS -> CohereLlmProvider(context.applicationContext).apply {
                // vision-capable وأقوى، لكنه tool-capable برضو — لازم نقفل الـ tools/personality
                // prompt عشان الرد يفضل JSON نضيف يطابق بروتوكول TaskUiReasoner.parse
                sessionModelOverride = "command-a-plus-05-2026"
                forceNonToolMode = true
            }
            VisionChoice.GEMINI -> GeminiLlmProvider(context.applicationContext)
            // on-device: lean profile (نص+صورة فقط) + session جديدة بلا history
            VisionChoice.LOCAL -> LocalLiteRTLMProvider(context.applicationContext).apply {
                leanTaskMode = true
                clearHistory()
            }
        }
    }

    /** ترتيب البدائل عند نفاد حصة الأساسي: الأقوى/المختلف أولًا (LOCAL يُحاول أخيرًا) */
    fun fallbackOrder(current: VisionChoice): List<VisionChoice> = when (current) {
        VisionChoice.GEMINI -> listOf(VisionChoice.COHERE_PLUS, VisionChoice.COHERE_VISION, VisionChoice.LOCAL)
        VisionChoice.COHERE_PLUS -> listOf(VisionChoice.GEMINI, VisionChoice.COHERE_VISION, VisionChoice.LOCAL)
        VisionChoice.COHERE_VISION -> listOf(VisionChoice.COHERE_PLUS, VisionChoice.GEMINI, VisionChoice.LOCAL)
        VisionChoice.LOCAL -> listOf(VisionChoice.GEMINI, VisionChoice.COHERE_PLUS, VisionChoice.COHERE_VISION)
    }

    fun modelName(provider: LlmProvider): String = try {
        when (provider) {
            is CohereLlmProvider -> provider.currentModel
            is GeminiLlmProvider -> provider.currentModel
            is LocalLiteRTLMProvider -> "gemma-local"
            else -> "?"
        }
    } catch (_: Exception) { "?" }

    fun choiceName(context: Context): String = getChoice(context).name

    /** اسم مختصر للعرض في أزرار الاختيار (بدل الـ enum name الطويل) */
    fun displayLabel(choice: VisionChoice): String = when (choice) {
        VisionChoice.COHERE_VISION -> "COHERE-V"
        VisionChoice.COHERE_PLUS -> "COHERE+"
        VisionChoice.GEMINI -> "GEMINI"
        VisionChoice.LOCAL -> "GEMMA"
    }

    /** جاهزية: API = المفتاح موجود؛ LOCAL = تحميل الموديل (blocking حتى 60s) */
    fun ensureReady(provider: LlmProvider): Boolean = try {
        when (provider) {
            is CohereLlmProvider -> {
                provider.connect {}
                if (!provider.hasApiKey()) {
                    Log.w(TAG, "WARN @vision — Cohere API key not set (Settings → Cohere)")
                    false
                } else true
            }
            is GeminiLlmProvider -> {
                provider.connect {}
                if (!provider.hasApiKey()) {
                    Log.w(TAG, "WARN @vision — Gemini API key not set (Settings → Gemini)")
                    false
                } else true
            }
            is LocalLiteRTLMProvider -> {
                // on-device: لازم الموديل محمل — تحميل blocking (يُنادى من خيط خلفية)
                if (provider.isReady) return true
                try {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    val ok = java.util.concurrent.atomic.AtomicBoolean(false)
                    provider.loadModel(
                        backend = provider.backendPref(),
                        onSuccess = { ok.set(true); latch.countDown() },
                        onError = { err ->
                            Log.w(TAG, "WARN @vision — local model load: $err")
                            latch.countDown()
                        }
                    )
                    latch.await(60, java.util.concurrent.TimeUnit.SECONDS) && ok.get() && provider.isReady
                } catch (e: Exception) {
                    Log.w(TAG, "WARN @vision — local ensureReady threw: ${e.message}")
                    false
                }
            }
            else -> false
        }
    } catch (e: Exception) {
        Log.w(TAG, "WARN @vision — ensureReady threw: ${e.message}")
        false
    }

    /**
     * استدعاء vision متزامن (نص + صورة base64) — نفس صيغة ChatScreen:
     * {"type":"image_text","text":...,"image":b64,"media_type":"image/jpeg"}.
     * يُستدعى من خيط خلفية فقط.
     */
    fun generateVisionBlocking(
        provider: LlmProvider,
        promptText: String,
        imageB64: String?,
        timeoutSec: Long = 60
    ): Result<String> {
        val payload = try {
            JSONObject().apply {
                put("type", if (promptText.isNotBlank() && imageB64 != null) "image_text" else "text")
                put("text", promptText)
                if (imageB64 != null) {
                    put("image", imageB64)
                    put("media_type", "image/jpeg")
                }
            }.toString()
        } catch (_: Exception) { promptText }
        val out = StringBuilder()
        val err = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        try {
            provider.sendMessage(
                json = payload,
                onChunk = { c -> synchronized(out) { out.append(c) } },
                onDone = { latch.countDown() },
                onError = { e -> err.set(e); latch.countDown() },
                onAction = {}
            )
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val done = try { latch.await(timeoutSec, TimeUnit.SECONDS) } catch (_: Exception) { false }
        if (!done) {
            try { provider.cancel() } catch (_: Exception) {}
            return Result.failure(IllegalStateException("Vision LLM timeout after ${timeoutSec}s"))
        }
        err.get()?.let { return Result.failure(IllegalStateException(it)) }
        val full = synchronized(out) { out.toString() }.trim()
        return if (full.isBlank()) Result.failure(IllegalStateException("Empty vision response"))
        else Result.success(full)
    }

    fun release(provider: LlmProvider) {
        try { provider.cancel() } catch (_: Exception) {}
        try { provider.disconnect() } catch (_: Exception) {}
    }
}
