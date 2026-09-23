// TaskRateLimit.kt — Axon Task Engine V2: التعامل مع 429/quota بدل الفشل الفوري
// المبدأ: نفاد الحصة ≠ فشل منطقي — backoff مسجل + (اختياريًا) مزود بديل، ثم فشل بسبب صريح.
package com.example.app_abdelbaset

import android.util.Log

object TaskRateLimit {

    private const val TAG = "TaskEngine"
    const val MAX_BACKOFF_SEC = 60L
    const val MAX_ROUNDS = 2

    /** true لو رسالة الخطأ شكلها rate-limit/quota/overload — تُستحق backoff (بالكود لا بالكلمات فقط) */
    fun isRateLimit(msg: String?): Boolean {
        if (msg.isNullOrBlank()) return false
        val m = msg.lowercase()
        if (Regex("\\b(408|429|50[0234])\\b").containsMatchIn(m)) return true
        return m.contains("quota") || m.contains("rate-limit") || m.contains("rate limit") ||
                m.contains("retry-after") || m.contains("retry in") || m.contains("too many requests") ||
                m.contains("overload") || m.contains("high demand") || m.contains("try again") ||
                m.contains("service unavailable") || m.contains("bad gateway") ||
                m.contains("gateway timeout") || m.contains("temporarily")
    }

    /** أخطاء تستحق تدوير المفتاح فورًا (تشمل مفاتيح ميتة 401/403 بلا انتظار) */
    fun isRotatable(msg: String?): Boolean {
        if (isRateLimit(msg)) return true
        if (msg.isNullOrBlank()) return false
        val m = msg.lowercase()
        return m.contains("401") || m.contains("403") || m.contains("unauthorized") ||
                m.contains("invalid") && m.contains("key") || m.contains("forbidden") ||
                m.contains("permission denied")
    }

    /** يستخرج ثواني الانتظار من الرسالة (Retry-After / retry in Ns) — default 30، سقف 60 */
    fun parseWaitSec(msg: String?): Long {
        if (msg.isNullOrBlank()) return 30L
        val patterns = listOf(
            Regex("retry in ([\\d.]+)s", RegexOption.IGNORE_CASE),
            Regex("retry-after[:\\s]+(\\d+)", RegexOption.IGNORE_CASE),
            Regex("retry after (\\d+)", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val v = p.find(msg)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            if (v != null && v > 0) return v.toLong().coerceIn(1, MAX_BACKOFF_SEC)
        }
        return 30L
    }

    /**
     * انتظار backoff مقسم لثواني مع احترام الإلغاء والـ deadline.
     * @return true لو اكتمل الانتظار (يُعاد المحاولة)، false لو (ملغي | خلصت الجولات | خلص الوقت)
     */
    fun backoffBlocking(
        taskId: String,
        where: String,
        msg: String?,
        round: Int,
        remainingMs: Long,
        shouldAbort: () -> Boolean
    ): Boolean {
        if (round > MAX_ROUNDS) {
            TaskExecutionLogger.logFailure(taskId, "ratelimit:$where",
                "quota backoff exhausted ($MAX_ROUNDS rounds)",
                extra = (msg ?: "").take(150))
            return false
        }
        val waitSec = parseWaitSec(msg).coerceAtMost(remainingMs / 1000L)
        if (waitSec <= 0) {
            TaskExecutionLogger.logFailure(taskId, "ratelimit:$where",
                "no time left for backoff (deadline reached)")
            return false
        }
        TaskExecutionLogger.log(taskId,
            "RATE-LIMITED @$where — waiting ${waitSec}s (round $round/$MAX_ROUNDS). " +
                    "Tip: switch VISION provider (COHERE+/GEMINI) to continue.")
        Log.w(TAG, "[$taskId] RATE-LIMITED @$where — waiting ${waitSec}s")
        var left = waitSec
        while (left > 0) {
            if (shouldAbort()) return false
            try { Thread.sleep(1000) } catch (_: Exception) { return false }
            left--
        }
        return !shouldAbort()
    }
}
