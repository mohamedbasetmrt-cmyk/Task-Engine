// TaskIntentTranslator.kt — Axon Task Engine V1: LLM يترجم الكلام لنية فقط (call واحدة)
// نفس LLM الموجود بالظبط (نفس prefs/mode/keys) لكن session/instance منفصلة خاصة بالـ Task.
// أي فشل → fallback فوري للـ rule-based. يُستدعى من خيط خلفية فقط.
package com.example.app_abdelbaset

import android.content.Context
import android.util.Log

object TaskIntentTranslator {

    private const val TAG = "TaskEngine"

    data class IntentResult(
        val goal: TaskGoal,
        val viaLlm: Boolean,
        val usedFallback: Boolean,
        val rawLlm: String = "",
        val durationMs: Long = 0L,
        val app: String = "",
        val target: String = "",
        val timeoutSec: Long = 15,
        val flow: List<UiFlowStep> = emptyList(),   // لـ UI_FLOW فقط
        /** المزود الفعلي الذي ترجم (للتشخيص الدائم) */
        val translator: String = ""
    )

    /**
     * يترجم نص اليوزر لنية. blocking — لازم يتنادى من خيط خلفية (TaskEngine executor).
     * V1: OPEN_SETTINGS / OPEN_SETTINGS_AND_BACK / FAIL_TEST / UNKNOWN.
     * V2: UI_WAIT / UI_FLOW / UI_RECOVER (+params).
     */
    fun translateBlocking(context: Context, userText: String, timeoutSec: Long = 25): IntentResult {
        val t0 = System.currentTimeMillis()
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) {
            return IntentResult(TaskGoal.UNKNOWN, viaLlm = false, usedFallback = true)
        }
        // 1) محاولة LLM (نفس الموجود — session جديدة منفصلة + minimal mode للترجمة)
        try {
            val provider = AwarenessLlmFactory.create(context.applicationContext)
            // وضع الترجمة المختصر: يوقع مكالمة ~7k token لـ ~500 (بلا شخصية/tools/سياق)
            try {
                when (provider) {
                    is CohereLlmProvider -> provider.minimalTranslateMode = true
                    is GeminiLlmProvider -> provider.minimalTranslateMode = true
                }
            } catch (_: Exception) {}
            val translatorTag = describeProvider(provider)
            try {
                if (!AwarenessLlmFactory.ensureReady(context.applicationContext, provider, 30)) {
                    Log.w(TAG, "WARN @translate — LLM not ready (mode/provider not prepared) — rule fallback. input=${trimmed.take(80)}")
                    return ruleIntent(trimmed).copy(durationMs = System.currentTimeMillis() - t0)
                }
                val prompt = buildPrompt(trimmed)
                val res = AwarenessLlmFactory.generateBlocking(provider, prompt, timeoutSec)
                if (res.isFailure) {
                    Log.w(TAG, "WARN @translate — LLM generate failed: ${res.exceptionOrNull()?.message} — rule fallback. input=${trimmed.take(80)}")
                    return ruleIntent(trimmed).copy(durationMs = System.currentTimeMillis() - t0)
                }
                val raw = res.getOrNull()?.trim().orEmpty()
                val parsed = parseIntent(raw)
                if (parsed != null) {
                    val guarded = guardIntent(trimmed, parsed)
                    return guarded.copy(viaLlm = true, usedFallback = false,
                        rawLlm = raw.take(300), durationMs = System.currentTimeMillis() - t0,
                        translator = translatorTag)
                }
                // LLM رد بحاجة غير مفهومة → fallback للقواعد
                Log.w(TAG, "WARN @translate — unparseable LLM reply — rule fallback. raw=${raw.take(120)} input=${trimmed.take(80)}")
            } finally {
                AwarenessLlmFactory.release(provider)
            }
        } catch (e: Exception) {
            Log.e(TAG, "FAIL @translate — translator exception, rule fallback. input=${trimmed.take(80)}: ${e.message}", e)
        }
        // 2) fallback الحتمي
        return ruleIntent(trimmed).copy(durationMs = System.currentTimeMillis() - t0)
    }

    /** وصف المزود الفعلي للتشخيص الدائم (class + model) */
    fun describeProvider(provider: LlmProvider): String = try {
        when (provider) {
            is CohereLlmProvider -> "Cohere:${provider.currentModel}"
            is GeminiLlmProvider -> "Gemini:${provider.currentModel}"
            is GroqLlmProvider -> "Groq:${provider.currentModel}"
            is DahlLlmProvider -> "Dahl:${provider.currentModel}"
            is MistralLlmProvider -> "Mistral:${provider.currentModel}"
            is LocalLiteRTLMProvider -> "LocalLiteRT"
            is ServerLlmProvider -> "Server"
            else -> provider.javaClass.simpleName
        }
    } catch (_: Exception) { "?" }

    /**
     * حارس الاتساق: لو الـ LLM قال settings والنص مفيهوش كلمة settings
     * وفيه اسم تطبيق آخر → انزل لـ OPEN_APP (أو UNKNOWN) مع WARN مسجل.
     * شبكة أمان ضد هلوسة أي موديل أيًا كان سببه.
     */
    fun guardIntent(userText: String, r: IntentResult): IntentResult {
        if (r.goal != TaskGoal.OPEN_SETTINGS && r.goal != TaskGoal.OPEN_SETTINGS_AND_BACK) return r
        val t = userText.lowercase()
        val mentionsSettings = t.contains("setting") || t.contains("اعداد") || t.contains("إعداد")
        if (mentionsSettings) return r
        val app = extractApp(t)?.takeIf { it.isNotBlank() }
        Log.w(TAG, "WARN @translate-guard — LLM said ${r.goal} with no settings word; " +
                "input=${userText.take(80)} app=$app — downgrading")
        return if (!app.isNullOrBlank()) {
            r.copy(goal = TaskGoal.OPEN_APP, app = app, flow = emptyList())
        } else {
            r.copy(goal = TaskGoal.UNKNOWN)
        }
    }

    /** توافق قديم: مطابقة الهدف فقط (تُستخدم من TaskEngine catch) */
    fun ruleMatch(text: String): TaskGoal = ruleIntent(text).goal

    /**
     * مطابقة حتمية كاملة (هدف + params) — fallback وكـ baseline.
     * V1 أولًا (settings)، ثم V2 (tap/type/wait/whatsapp/multi/recover).
     */
    fun ruleIntent(text: String): IntentResult {
        val t = text.lowercase()
        // ── V1 ──
        val wantsSettings = t.contains("setting") || t.contains("اعداد") || t.contains("إعداد")
        val wantsBack = t.contains("back") || t.contains("ارجع") || t.contains("إرجع") ||
                t.contains("return") || t.contains("رجوع") || t.contains("وارجع")
        if (wantsSettings && wantsBack)
            return IntentResult(TaskGoal.OPEN_SETTINGS_AND_BACK, false, true)
        if (wantsSettings)
            return IntentResult(TaskGoal.OPEN_SETTINGS, false, true)
        val wantsImpossibleV1 = (t.contains("nonexistent") || t.contains("مش موجود") ||
                t.contains("غير موجود") || t.contains("مستحيل")) &&
                !t.contains("دوس") && !t.contains("اضغط") && !t.contains("click") && !t.contains("tap")
        if (wantsImpossibleV1)
            return IntentResult(TaskGoal.FAIL_TEST, false, true)
        // ── V2: عام ──
        val app = extractApp(t) ?: ""
        val quoted = extractQuoted(text) ?: ""
        val wantsWait = t.contains("استنى") || t.contains("إستنى") || t.contains("انتظر") ||
                t.contains("wait") || t.contains("إنتظر")
        if (wantsWait) {
            return IntentResult(TaskGoal.UI_WAIT, false, true,
                app = app, target = quoted.ifBlank { "element" })
        }
        val wantsFakeTarget = t.contains("مش موجود") || t.contains("غير موجود") ||
                t.contains("وهمي") || t.contains("nonexistent")
        val wantsTap = t.contains("دوس") || t.contains("اضغط") || t.contains("click") ||
                t.contains("tap") || t.contains("افتح")
        if (wantsTap && wantsFakeTarget) {
            return IntentResult(TaskGoal.UI_RECOVER, false, true,
                app = app.ifBlank { "Settings" }, target = quoted.ifBlank { "nonexistent control xyz" })
        }
        // فتح-بس بلا تفاعل (open X ولا شيء بعده) → OPEN_APP مستقل
        val openOnly = (t.contains("افتح ") || t.contains("افتحي ") || t.contains("open ")) &&
                !t.contains("دوس") && !t.contains("اضغط") && !t.contains("click") &&
                !t.contains("tap") && !t.contains("اكتب") && !t.contains("إكتب") &&
                !t.contains("type") && !t.contains("wait") && !t.contains("استنى") &&
                !t.contains("انتظر")
        if (openOnly && app.isNotBlank()) {
            return IntentResult(TaskGoal.OPEN_APP, false, true, app = app)
        }
        // fallback سكرول خفيف: دور بالتمرير بدل البحث (قوائم قصيرة)
        val wantsScroll = t.contains("scroll") || t.contains("اسكرول") || t.contains("مرر") ||
                t.contains("اسحب") || t.contains("swipe")
        if (wantsScroll && app.isNotBlank()) {
            val dir = if (t.contains("فوق") || t.contains("لفوق") || t.contains(" up")) "up" else "down"
            return IntentResult(TaskGoal.UI_FLOW, false, true,
                app = app, flow = listOf(UiFlowStep("scroll", quoted.ifBlank { "item" }, dir)))
        }
        // fallback حتمي بسيط: خطوة واحدة (click أو type) — تسلسلات معقدة محتاجة LLM فعليًا
        val wantsType = t.contains("اكتب") || t.contains("إكتب") || t.contains("type") ||
                t.contains("ادخل") || t.contains("دخل")
        if (wantsType && app.isNotBlank()) {
            val txt = quoted.ifBlank { "Ahmed" }
            return IntentResult(TaskGoal.UI_FLOW, false, true,
                app = app, flow = listOf(UiFlowStep("type", "text field", txt)))
        }
        if (wantsTap && app.isNotBlank()) {
            return IntentResult(TaskGoal.UI_FLOW, false, true,
                app = app, flow = listOf(UiFlowStep("click", quoted.ifBlank { "visible button" })))
        }
        return IntentResult(TaskGoal.UNKNOWN, false, true)
    }

    /** يستخرج اسم تطبيق بعد افتح/open — null لو مفيش */
    private fun extractApp(t: String): String? {
        val markers = listOf("افتح ", "افتحي ", "open ")
        for (m in markers) {
            val i = t.indexOf(m)
            if (i >= 0) {
                val rest = t.substring(i + m.length).trim()
                val stop = listOf(" و", " ودوس", " واضغط", " واكتب", " واستنى", " then ", " and ")
                var end = rest.length
                for (s in stop) {
                    val j = rest.indexOf(s)
                    if (j > 0 && j < end) end = j
                }
                val name = rest.take(end).trim().trim('"', '\'', '،', '.', '؟', '?')
                if (name.length in 2..30) return name
            }
        }
        return null
    }

    private fun extractQuoted(text: String): String? {
        val m = Regex("[\"'“”]([^\"'“”]+)[\"'“”]").find(text)
        return m?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun afterWord(t: String, words: List<String>): String {
        for (w in words) {
            val i = t.indexOf(w)
            if (i >= 0) {
                val rest = t.substring(i + w.length).trim()
                    .trim('"', '\'', '،', '.', '؟', '?').take(30).trim()
                if (rest.isNotBlank()) return rest.split(" ")[0]
            }
        }
        return ""
    }

    private fun buildPrompt(userText: String): String {
        return "You are an intent translator for Axon Task Engine (V1+V2). " +
                "Allowed goals ONLY: OPEN_SETTINGS, OPEN_SETTINGS_AND_BACK, FAIL_TEST, " +
                "OPEN_APP, UI_WAIT, UI_FLOW, UI_RECOVER, UNKNOWN. " +
                "Rules V1: user wants to open the SYSTEM SETTINGS app itself (literally Settings, " +
                "not any other app) -> OPEN_SETTINGS. " +
                "Open system settings then go back/return -> OPEN_SETTINGS_AND_BACK. " +
                "Impossible/nonexistent system destination (no UI tap verbs) -> FAIL_TEST. " +
                "Rules V2: user wants to ONLY open an app with no interaction specified " +
                "(e.g. \"open teams\") -> OPEN_APP with app=name. " +
                "Rules V2 (general — use for ANY app task): user wants to open an app and perform " +
                "one or more UI interactions (tap a button, type text, scroll a list, go back, wait for a result, search, send a message, " +
                "navigate screens, etc.) -> UI_FLOW. Break the goal into an ORDERED list of atomic " +
                "steps in 'flow': each item is {\"op\":\"click\"|\"type\"|\"scroll\"|\"back\"|\"wait\", \"target\":\"visual/textual " +
                "description of the element to find\", \"text\":\"for type=text to type; for scroll=direction " +
                "up|down (default down)\", \"expected\":\"a concrete visible sign this step succeeded\"}. " +
                "Do NOT skip intermediate steps — e.g. tapping search before typing in it, opening a " +
                "result before typing into it, tapping send after typing a message. Set 'app' to the " +
                "app name to open first. NEVER include opening/launching the app itself as a flow step " +
                "(no tapping app icons, no opening the app) — 'app' already opens it. The flow starts " +
                "with the first interaction INSIDE the opened app. " +
                "For short scrollable lists where the target may become visible by scrolling, prefer " +
                "a scroll step over opening search, unless the user explicitly asked to search. " +
                "User wants to wait until an element/text appears with NO action before/after it -> " +
                "UI_WAIT (app, target, timeout=seconds default 15). " +
                "User wants to tap something impossible/nonexistent on screen (recovery test) -> " +
                "UI_RECOVER (app, target). " +
                "Anything else -> UNKNOWN. " +
                "User text may be Arabic or English. " +
                "Reply with ONLY JSON, no other text, e.g.: " +
                "{\"goal\":\"OPEN_APP\",\"app\":\"Teams\"}. " +
                "Or: {\"goal\":\"UI_FLOW\",\"app\":\"WhatsApp\",\"flow\":[" +
                "{\"op\":\"click\",\"target\":\"search icon\"}," +
                "{\"op\":\"type\",\"target\":\"search input field\",\"text\":\"Momo\"}," +
                "{\"op\":\"click\",\"target\":\"chat result named Momo\"}," +
                "{\"op\":\"type\",\"target\":\"message text field\",\"text\":\"okay done\"}," +
                "{\"op\":\"click\",\"target\":\"send button\"}]}. " +
                "Include app/target/flow/timeout only when relevant to the chosen goal. " +
                "User text: $userText"
    }

    /** يرجع null لو غير مفهومة → المتصل يعمل fallback */
    private fun parseIntent(raw: String): IntentResult? {
        if (raw.isBlank()) return null
        // 1) محاولة JSON مباشر
        try {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start >= 0 && end > start) {
                val obj = org.json.JSONObject(raw.substring(start, end + 1))
                val g = obj.optString("goal", "").trim().uppercase()
                val goal = when (g) {
                    "OPEN_SETTINGS" -> TaskGoal.OPEN_SETTINGS
                    "OPEN_SETTINGS_AND_BACK" -> TaskGoal.OPEN_SETTINGS_AND_BACK
                    "FAIL_TEST" -> TaskGoal.FAIL_TEST
                    "OPEN_APP" -> TaskGoal.OPEN_APP
                    "UI_WAIT" -> TaskGoal.UI_WAIT
                    "UI_FLOW" -> TaskGoal.UI_FLOW
                    "UI_RECOVER" -> TaskGoal.UI_RECOVER
                    "UNKNOWN" -> TaskGoal.UNKNOWN
                    else -> null
                } ?: return null
                val flowArr = obj.optJSONArray("flow")
                val flow = if (flowArr != null) {
                    (0 until flowArr.length()).mapNotNull { i ->
                        val f = flowArr.optJSONObject(i) ?: return@mapNotNull null
                        val op = f.optString("op", "").trim().lowercase()
                        if (op !in setOf("click", "type", "scroll", "back", "wait")) return@mapNotNull null
                        UiFlowStep(op, f.optString("target", "").take(120),
                            f.optString("text", "").take(500),
                            f.optString("expected", "").take(180))
                    }
                } else emptyList()
                return IntentResult(
                    goal = goal, viaLlm = false, usedFallback = false,
                    app = obj.optString("app", "").take(40),
                    target = obj.optString("target", "").take(120),
                    timeoutSec = obj.optLong("timeout", 15).coerceIn(5, 120),
                    flow = flow
                )
            }
        } catch (_: Exception) {}
        // 2) بحث متساهل عن اسم الهدف في النص — تخمين صريح، فيُسجَّل fallback لا LLM
        // (الـ echo بلا JSON حقيقي كان يتلبس لبس الـ LLM — إصلاح التشخيص الصادق)
        val u = raw.uppercase()
        fun partial(g: TaskGoal, vararg keys: String): IntentResult? {
            if (keys.any { u.contains(it) })
                return IntentResult(g, false, true, rawLlm = raw.take(300))
            return null
        }
        partial(TaskGoal.UI_RECOVER, "UI_RECOVER")?.let { return it }
        partial(TaskGoal.UI_FLOW, "UI_FLOW")?.let { return it }
        partial(TaskGoal.UI_WAIT, "UI_WAIT")?.let { return it }
        partial(TaskGoal.OPEN_APP, "OPEN_APP")?.let { return it }
        partial(TaskGoal.OPEN_SETTINGS_AND_BACK, "OPEN_SETTINGS_AND_BACK")?.let { return it }
        partial(TaskGoal.OPEN_SETTINGS, "OPEN_SETTINGS")?.let { return it }
        partial(TaskGoal.FAIL_TEST, "FAIL_TEST")?.let { return it }
        partial(TaskGoal.UNKNOWN, "UNKNOWN")?.let { return it }
        return null
    }

    /** توافق قديم */
    private fun parseGoal(raw: String): TaskGoal? = parseIntent(raw)?.goal
}
