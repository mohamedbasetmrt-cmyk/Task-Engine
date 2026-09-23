// TaskUiReasoner.kt — Axon Task Engine V2: قرار فعل واحد من لقطة بصرية
// الـ prompt المعتمد: الصورة أساسي + قايمة النص احتياطي صريح + JSON صارم + خيار wait.
// + تقوية: ممنوع الـ full-screen container + تفضيل اللي له نص/وصف.
// + مرونة: fallback تلقائي لمزود بديل عند 429/quota + lastError للـ backoff في الـ Engine.
package com.example.app_abdelbaset

import android.content.Context
import android.util.Log

object TaskUiReasoner {

    private const val TAG = "TaskEngine"

    data class Decision(
        val action: String,   // click | type | swipe | back | wait
        val element: Int,     // رقم من القايمة — -1 لو لا ينطبق
        val text: String = "",
        val raw: String = "",
        val latencyMs: Long = 0L
    )

    data class Presence(
        val visible: Boolean,
        val element: Int,     // -1 لو غير ظاهر
        val raw: String = "",
        val latencyMs: Long = 0L
    )

    /** آخر خطأ vision (يُقرأ من الـ Engine لتمييز 429 عن الفشل المنطقي) */
    @Volatile var lastError: String = ""
        private set

    fun lastErrorRateLimited(): Boolean = TaskRateLimit.isRateLimit(lastError)

    /** ناتج خام + خطأ + اسم المزود المستخدم فعلًا */
    private data class VisionCall(val raw: String?, val error: String?, val providerTag: String)

    /**
     * استدعاء vision مع دوران كامل: لكل مزود (الأساسي ثم البدائل) × لكل مفتاح غير مبرد.
     * 429/quota/مفتاح ميت → المفتاح التالي فورًا (cooldown مسجل). غيره → يرجع الخطأ.
     * نفاد الكل → يرجع آخر خطأ (الـ caller يعمل backoff، لا يُحتسب recovery).
     */
    private fun callVision(
        context: Context,
        taskId: String,
        stepId: String,
        snap: TaskUiSnapshot.UiSnapshot,
        prompt: String,
        timeoutSec: Long,
        includeImage: Boolean = true
    ): VisionCall {
        val primary = TaskVisionProvider.getChoice(context)
        val order = listOf(primary) + TaskVisionProvider.fallbackOrder(primary)
        var lastErr: String? = "no provider attempted"
        var lastTag = primary.name
        for (choice in order) {
            // LOCAL بلا مفاتيح أصلًا (on-device) — محاولة واحدة مباشرة
            val slots = if (choice == TaskVisionProvider.VisionChoice.LOCAL) {
                listOf(TaskVisionKeys.KeySlot("", 0))
            } else {
                TaskVisionKeys.orderedSlots(context, choice)
            }
            if (slots.isEmpty()) {
                Log.w(TAG, "WARN @reasoner:$stepId — $choice has no API keys, trying next choice")
                lastErr = "$choice: no API keys configured"
                continue
            }
            for (slot in slots) {
                val provider = TaskVisionProvider.createFor(context, choice)
                // مفتاح هذه المحاولة فقط (override runtime — لا يمس prefs الشات)
                try {
                    when (provider) {
                        is CohereLlmProvider -> provider.sessionApiKeyOverride = slot.key
                        is GeminiLlmProvider -> provider.sessionApiKeyOverride = slot.key
                    }
                } catch (_: Exception) {}
                val tag = "$choice[K${slot.index + 1}]:${TaskVisionProvider.modelName(provider)}"
                lastTag = tag
                try {
                    if (!TaskVisionProvider.ensureReady(provider)) {
                        lastErr = "$tag not ready"
                        Log.w(TAG, "WARN @reasoner:$stepId — $tag not ready, trying next key")
                        continue
                    }
                    if (choice != primary || slot.index != TaskVisionKeys.activeIndex(context, choice)) {
                        TaskExecutionLogger.log(taskId,
                            "VISION ROTATE $stepId → $tag (previous key/choice limited)")
                    }
                    val res = TaskVisionProvider.generateVisionBlocking(
                        provider, prompt, if (includeImage) snap.boxedB64 else null, timeoutSec)
                    if (res.isSuccess) {
                        TaskVisionKeys.markSuccess(context, choice, slot.index)
                        return VisionCall(res.getOrNull(), null, tag)
                    }
                    lastErr = res.exceptionOrNull()?.message ?: "unknown vision error"
                    if (TaskRateLimit.isRotatable(lastErr)) {
                        TaskVisionKeys.markQuotaHit(choice, slot.index,
                            TaskRateLimit.parseWaitSec(lastErr))
                        TaskExecutionLogger.logWarn(taskId, "reasoner:$stepId",
                            "key exhausted on $tag (${lastErr.take(100)}) — rotating")
                        continue
                    }
                    return VisionCall(null, lastErr, tag)
                } finally {
                    TaskVisionProvider.release(provider)
                }
            }
        }
        return VisionCall(null, lastErr, lastTag)
    }

    /**
     * Tier-1 لا يحتاج reasoning ثقيل: يقرأ شجرة Accessibility فقط. نبدأ بمزود خفيف
     * متاح، ثم نحتفظ بالمزود الذي اختاره المستخدم للـ screenshot/القرار الصعب في Tier-2.
     * لا تُرسل هذه الدالة شيئًا إلا لمزوّد يملك المستخدم مفتاحه مهيأ بالفعل.
     */
    private fun callVisionFast(
        context: Context,
        taskId: String,
        stepId: String,
        prompt: String,
        timeoutSec: Long
    ): VisionCall {
        val preferred = TaskVisionProvider.getChoice(context)
        val base = listOf(
            TaskVisionProvider.VisionChoice.COHERE_VISION,
            TaskVisionProvider.VisionChoice.GEMINI,
            TaskVisionProvider.VisionChoice.LOCAL
        )
        val order = (if (preferred in base) listOf(preferred) + (base - preferred) else base).distinct()
        var lastErr: String? = "no fast provider attempted"
        var lastTag = "fast"
        for (choice in order) {
            val slots = if (choice == TaskVisionProvider.VisionChoice.LOCAL) {
                listOf(TaskVisionKeys.KeySlot("", 0))
            } else TaskVisionKeys.orderedSlots(context, choice)
            if (slots.isEmpty()) {
                lastErr = "$choice: no API keys configured"
                continue
            }
            for (slot in slots) {
                val provider = TaskVisionProvider.createFor(context, choice)
                val tag = "$choice[K${slot.index + 1}]:${TaskVisionProvider.modelName(provider)}(fast)"
                lastTag = tag
                try {
                    when (provider) {
                        is CohereLlmProvider -> provider.sessionApiKeyOverride = slot.key
                        is GeminiLlmProvider -> provider.sessionApiKeyOverride = slot.key
                    }
                    if (!TaskVisionProvider.ensureReady(provider)) {
                        lastErr = "$tag not ready"
                        continue
                    }
                    val res = TaskVisionProvider.generateVisionBlocking(provider, prompt, null, timeoutSec)
                    if (res.isSuccess) {
                        TaskVisionKeys.markSuccess(context, choice, slot.index)
                        return VisionCall(res.getOrNull(), null, tag)
                    }
                    lastErr = res.exceptionOrNull()?.message ?: "unknown vision error"
                    if (TaskRateLimit.isRotatable(lastErr)) {
                        TaskVisionKeys.markQuotaHit(choice, slot.index, TaskRateLimit.parseWaitSec(lastErr))
                        continue
                    }
                    return VisionCall(null, lastErr, tag)
                } finally {
                    TaskVisionProvider.release(provider)
                }
            }
        }
        return VisionCall(null, lastErr, lastTag)
    }

    /**
     * قرار واحد — blocking على خيط خلفية.
     * Tiered: المحاولة الأولى نص فقط (رخيصة) — لو قرار واثق (≠ wait) خلاص،
     * وإلا إعادة بنفس الـ snapshot مع الصورة. يوفر ~70% من توكنز الـ vision.
     * @param allowed الأفعال المسموحة لهذه الخطوة (مثلًا click فقط)
     * @param extraText للـ type: النص المطلوب كتابته (يُمرر للـ prompt وللناتج)
     */
    fun decideBlocking(
        context: Context,
        taskId: String,
        stepId: String,
        snap: TaskUiSnapshot.UiSnapshot,
        goalDesc: String,
        allowed: Set<String>,
        extraText: String = "",
        timeoutSec: Long = 60
    ): Decision? {
        lastError = ""
        val t0 = System.currentTimeMillis()
        // لا نسمح للـ model أن يختار containers/labels غير قابلة للفعل. هذه القائمة
        // تحافظ على أرقام الـ snapshot الأصلية، لذا لا يوجد أي mapping هش بعد إعادة الرسم.
        val candidates = TaskUiTargeting.rankedIds(snap, goalDesc, allowed)
        if (candidates.isEmpty() && "wait" !in allowed) {
            lastError = "no actionable candidates"
            TaskExecutionLogger.logWarn(taskId, "reasoner:$stepId", lastError)
            return null
        }
        // ── Tier 1: نص فقط ──
        val textPrompt = buildPrompt(goalDesc, snap.elementText, allowed, extraText, false, candidates,
            TaskUiTargeting.summary(snap, goalDesc, allowed))
        val t1 = callVisionFast(context, taskId, "$stepId#t", textPrompt, 15)
        if (t1.raw != null) {
            var decisionSource = t1
            var d = parse(t1.raw.trim(), snap.nodes.size, allowed, extraText, candidates,
                System.currentTimeMillis() - t0)
            // رد JSON صحيح لكن action غير مسموح لا يستحق صورة مكلفة؛ أعطِ الموديل
            // السريع فرصة تصحيح واحدة واضحة قبل الانتقال لـ Tier-2.
            if (d == null) {
                val repairPrompt = textPrompt + "\n\nREMINDER: action MUST be exactly one of: " +
                        allowed.joinToString(", ") + ". Reply ONLY with the JSON object."
                val repair = callVisionFast(context, taskId, "$stepId#repair", repairPrompt, 12)
                if (repair.raw != null) {
                    val repaired = parse(repair.raw.trim(), snap.nodes.size, allowed, extraText, candidates,
                        System.currentTimeMillis() - t0)
                    if (repaired != null) {
                        d = repaired
                        decisionSource = repair
                        TaskExecutionLogger.log(taskId, "TEXT-FIRST $stepId — repaired invalid decision cheaply")
                    }
                }
            }
            if (d != null && d.action != "wait") {
                logDecision(taskId, stepId, decisionSource.providerTag, d, snap,
                    System.currentTimeMillis() - t0, tier = "TEXT-FIRST HIT")
                return d
            }
        } else if (TaskRateLimit.isRateLimit(t1.error)) {
            // الحصص ميتة — لا تحرق call صورة، ارجع فورًا (الـ Engine يعمل backoff)
            lastError = t1.error ?: "unknown"
            TaskExecutionLogger.logFailure(taskId, "reasoner:$stepId",
                "vision call failed: ${lastError.take(150)}",
                extra = "tier=text, goal=$goalDesc")
            return null
        }
        // ── Tier 2: صورة + نص ──
        TaskExecutionLogger.log(taskId, "TEXT-FIRST $stepId miss → IMAGE retry")
        val imgPrompt = buildPrompt(goalDesc, snap.elementText, allowed, extraText, snap.shotOk, candidates,
            TaskUiTargeting.summary(snap, goalDesc, allowed))
        val t2 = callVision(context, taskId, "$stepId#img", snap, imgPrompt, timeoutSec, includeImage = true)
        val ms = System.currentTimeMillis() - t0
        if (t2.raw == null) {
            lastError = t2.error ?: "unknown"
            TaskExecutionLogger.logFailure(taskId, "reasoner:$stepId",
                "vision call failed: ${lastError.take(150)}",
                extra = "tiers=text+image, goal=$goalDesc")
            return null
        }
        val raw = t2.raw.trim()
        val d = parse(raw, snap.nodes.size, allowed, extraText, candidates, ms)
        if (d == null) {
            lastError = "unparseable/invalid decision"
            TaskExecutionLogger.logFailure(taskId, "reasoner:$stepId",
                "unparseable/invalid decision",
                extra = "via=${t2.providerTag} raw=${raw.take(200)} nodes=${snap.nodes.size}")
            return null
        }
        logDecision(taskId, stepId, t2.providerTag, d, snap, ms, tier = "IMAGE")
        return d
    }

    private fun logDecision(
        taskId: String, stepId: String, providerTag: String, d: Decision,
        snap: TaskUiSnapshot.UiSnapshot, ms: Long, tier: String
    ) {
        val el = snap.nodes.getOrNull(d.element)
        TaskExecutionLogger.log(taskId,
            "REASONER $stepId via=$providerTag [$tier] ${ms}ms — " +
                    "raw=${d.raw.take(120)} → chose #${d.element}" +
                    (if (el != null) " ${el.className.substringAfterLast('.')} " +
                            "\"${el.text.ifBlank { el.contentDesc }.take(40)}\"" else " (no-element)"))
    }

    /**
     * سؤال حضور دلالي (لـ WAIT/VERIFY الجديد): هل الهدف ظاهر؟ — blocking.
     * Tiered مثل القرار: نص أولًا ثم صورة. يحل محل المطابق الحرفي بلا كلمات hardcoded.
     */
    fun decidePresenceBlocking(
        context: Context,
        taskId: String,
        stepId: String,
        snap: TaskUiSnapshot.UiSnapshot,
        targetDesc: String,
        timeoutSec: Long = 45
    ): Presence? {
        lastError = ""
        val t0 = System.currentTimeMillis()
        // Tier 1: نص فقط
        val p1 = callVisionFast(context, taskId, "$stepId#t",
            presencePrompt(targetDesc, snap.elementText, false), 12)
        if (p1.raw != null) {
            val pres = parsePresence(p1.raw, snap, taskId, stepId, targetDesc, p1.providerTag,
                System.currentTimeMillis() - t0, tier = "TEXT-FIRST HIT")
            if (pres != null && pres.visible) return pres
            // غير ظاهر بالنص → جرّب بالصورة قبل الحكم (قد تكون الشجرة ناقصة)
        } else if (TaskRateLimit.isRateLimit(p1.error)) {
            lastError = p1.error ?: "unknown"
            TaskExecutionLogger.logFailure(taskId, "presence:$stepId",
                "vision call failed: ${lastError.take(150)}",
                extra = "tier=text, target=$targetDesc")
            return null
        }
        // Tier 2: صورة + نص
        if (p1.raw != null) {
            TaskExecutionLogger.log(taskId, "TEXT-FIRST $stepId miss/hidden → IMAGE retry")
        }
        val p2 = callVision(context, taskId, "$stepId#img", snap,
            presencePrompt(targetDesc, snap.elementText, snap.shotOk), timeoutSec, includeImage = true)
        val ms = System.currentTimeMillis() - t0
        if (p2.raw == null) {
            lastError = p2.error ?: "unknown"
            TaskExecutionLogger.logFailure(taskId, "presence:$stepId",
                "vision call failed: ${lastError.take(150)}",
                extra = "tiers=text+image, target=$targetDesc")
            return null
        }
        return parsePresence(p2.raw, snap, taskId, stepId, targetDesc, p2.providerTag, ms, tier = "IMAGE")
    }

    private fun presencePrompt(targetDesc: String, elements: String, hasShot: Boolean): String {
        val imgLine = if (hasShot)
            "IMAGE: screenshot with numbered boxes. Each box number matches the ELEMENTS list."
        else
            "IMAGE: unavailable — decide from the ELEMENTS text list only."
        return "You are a mobile UI observer. Is the following UI target currently VISIBLE on screen?\n" +
                "\nTARGET: $targetDesc\n" +
                "\n$imgLine\n" +
                "ELEMENTS (use if the image is unclear):\n" + elements + "\n" +
                "RULES:\n" +
                "- Understand the target semantically (synonyms, translations like search/بحث all match).\n" +
                "- Also accept an ACTIVE/FOCUSED input field or open keyboard as a sign that its input UI is present.\n" +
                "- Reply ONLY with JSON, no other text: {\"visible\":true,\"element\":3} or {\"visible\":false}.\n" +
                "- \"element\" MUST be a number from the list above when visible (use -1 when false). Never invent numbers."
    }

    /** null = غير صالح أو غير ظاهر بشكل مؤكد — الـ caller يقرر (presence.visible=false ≠ فشل) */
    private fun parsePresence(
        rawIn: String,
        snap: TaskUiSnapshot.UiSnapshot,
        taskId: String,
        stepId: String,
        targetDesc: String,
        providerTag: String,
        ms: Long,
        tier: String
    ): Presence? {
        return try {
            val raw = rawIn.trim()
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) {
                lastError = "unparseable presence"
                null
            } else {
                val obj = org.json.JSONObject(raw.substring(start, end + 1))
                val vis = obj.optBoolean("visible", false)
                val el = obj.optInt("element", -1)
                if (vis && (el < 0 || el >= snap.nodes.size)) {
                    lastError = "presence element out of range"
                    TaskExecutionLogger.logFailure(taskId, "presence:$stepId",
                        "element $el out of range", extra = "raw=${raw.take(150)}")
                    null
                } else {
                    TaskExecutionLogger.log(taskId,
                        "PRESENCE $stepId via=$providerTag [$tier] ${ms}ms — " +
                                "target=\"$targetDesc\" visible=$vis" +
                                (if (vis) " element=#$el" else ""))
                    Presence(vis, if (vis) el else -1, raw, ms)
                }
            }
        } catch (e: Exception) {
            lastError = "presence parse: ${e.message}"
            null
        }
    }

    private fun buildPrompt(
        goalDesc: String,
        elements: String,
        allowed: Set<String>,
        extraText: String,
        hasShot: Boolean,
        candidates: Set<Int>,
        candidateSummary: String
    ): String {
        val imgLine = if (hasShot)
            "IMAGE: screenshot with numbered boxes. Each box number matches the ELEMENTS list."
        else
            "IMAGE: unavailable for this step — decide from the ELEMENTS text list only."
        val textLine = if (extraText.isNotBlank()) "TEXT TO TYPE: \"$extraText\"\n" else ""
        return "You are a mobile UI operator. Decide ONE action.\n" +
                "\nGOAL: $goalDesc\n" +
                "\n$imgLine\n" +
                "ELEMENTS (use if the image is unclear or you can't find the target in it):\n" +
                elements + "\n" + textLine +
                "ACTIONABLE ELEMENT NUMBERS ONLY: " + candidates.sorted().joinToString(", ") + "\n" +
                "LOCAL RANKING (hint, not proof): " + candidateSummary + "\n" +
                "\nRULES:\n" +
                "- Prefer the IMAGE (boxes + layout). If you can't understand it or can't find " +
                "the target in it, use the ELEMENTS text list instead.\n" +
                "- Reply ONLY with JSON, no other text:\n" +
                "  {\"action\":\"click\",\"element\":1}\n" +
                "  {\"action\":\"type\",\"element\":7,\"text\":\"Ahmed\"}\n" +
                "- \"element\" MUST be one of ACTIONABLE ELEMENT NUMBERS above. Never invent numbers.\n" +
                "- Never pick a full-screen/root container (often #0, marked full in the list) — " +
                "prefer the smallest specific control that matches the goal.\n" +
                "- Prefer elements with meaningful text/desc; avoid empty (\"-\") containers " +
                "unless nothing else matches.\n" +
                "- \"action\" MUST be one of: ${allowed.joinToString(", ")}.\n" +
                "- If the target is not visible anywhere, reply: {\"action\":\"wait\"}"
    }

    /** null = غير صالح (الـ caller يسجل ويفشل/يعيد) */
    private fun parse(
        raw: String,
        nodeCount: Int,
        allowed: Set<String>,
        extraText: String,
        candidates: Set<Int>,
        ms: Long
    ): Decision? {
        if (raw.isBlank()) return null
        try {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj = org.json.JSONObject(raw.substring(start, end + 1))
            var action = obj.optString("action", "").trim().lowercase()
            // بعض النماذج تصف الضغط اللازم لتركيز حقل الكتابة كـ click. طالما الاختيار
            // ضمن editable-only candidates، فهو نفس قصد type ولا نهدر round كامل.
            if (action == "click" && "type" in allowed && candidates.contains(obj.optInt("element", -1))) {
                action = "type"
            }
            if (action.isBlank() || action !in allowed) {
                Log.w(TAG, "UiReasoner: action '$action' not in $allowed")
                return null
            }
            if (action == "wait" || action == "back") {
                return Decision(action, -1, raw = raw, latencyMs = ms)
            }
            val el = obj.optInt("element", -1)
            if (el < 0 || el >= nodeCount) {
                Log.w(TAG, "UiReasoner: element $el out of range (0..${nodeCount - 1})")
                return null
            }
            if (el !in candidates) {
                Log.w(TAG, "UiReasoner: element $el is not actionable; candidates=$candidates")
                return null
            }
            val text = if (action == "type") {
                obj.optString("text", extraText).ifBlank { extraText }
            } else ""
            if (action == "type" && text.isBlank()) {
                Log.w(TAG, "UiReasoner: type with blank text")
                return null
            }
            return Decision(action, el, text, raw, ms)
        } catch (e: Exception) {
            Log.w(TAG, "UiReasoner parse failed: ${e.message}")
            return null
        }
    }
}
