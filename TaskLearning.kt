// TaskLearning.kt — privacy-preserving learned workflow templates.
package com.example.app_abdelbaset

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * يتعلم الهيكل فقط من UI_FLOW ناجح. القيم التي كتبها المستخدم لا تُحفظ: تتحول إلى
 * placeholders أثناء بناء signature، والـ locator يُستخدم فقط إن كان له viewId ثابت.
 */
object TaskLearning {
    private const val PREFS = "axon_task_learning"
    private const val KEY_TEMPLATES = "templates_v1"
    private const val MAX_TEMPLATES = 30

    data class Match(val id: String, val locators: List<LearnedLocator?>, val successes: Int, val score: Double)
    private data class Template(val id: String, val app: String, val signature: String,
                                val locators: List<LearnedLocator?>, val successes: Int,
                                val utterancePattern: String = "", val flowTemplate: List<UiFlowStep> = emptyList())

    /**
     * أسرع مسار آمن: نفس صياغة طلب سبق نجاحه مع قيم type مختلفة فقط.
     * لا توجد مطابقة تقريبية هنا؛ الغموض يعود للـ LLM بدل تنفيذ intent خاطئ.
     */
    fun recallIntent(context: Context, userText: String): TaskIntentTranslator.IntentResult? {
        for (t in load(context)) {
            if (t.successes < 2 || t.utterancePattern.isBlank() || t.flowTemplate.isEmpty()) continue
            val flow = renderIfMatches(t, userText) ?: continue
            return TaskIntentTranslator.IntentResult(TaskGoal.UI_FLOW, viaLlm = false, usedFallback = false,
                app = t.app, flow = flow, translator = "learned-template")
        }
        return null
    }

    fun match(context: Context, intent: TaskIntentTranslator.IntentResult): Match? {
        if (intent.goal != TaskGoal.UI_FLOW || intent.app.isBlank() || intent.flow.isEmpty()) return null
        val normalizedFlow = TaskFlowNormalizer.normalize(intent.flow)
        val app = normalize(intent.app)
        // الـ intent قد يجي من عشر صيغ كلام مختلفة. نقارن الخطة المفهومة نفسها
        // (التطبيق + ترتيب الأفعال + وصف العناصر الثابتة) لا نص المستخدم.
        val found = load(context).filter { it.app == app && it.flowTemplate.isNotEmpty() }
            .map { it to semanticScore(normalizedFlow, it.flowTemplate) }
            .filter { (_, score) -> score >= 0.62 }
            .maxWithOrNull(compareBy<Pair<Template, Double>> { it.second }.thenBy { it.first.successes })
            ?: return null
        return Match(found.first.id, found.first.locators, found.first.successes, found.second)
    }

    fun learnSuccess(context: Context, intent: TaskIntentTranslator.IntentResult, userText: String,
                     locators: List<LearnedLocator?>) {
        if (intent.goal != TaskGoal.UI_FLOW || intent.app.isBlank() || intent.flow.isEmpty()) return
        val normalizedFlow = TaskFlowNormalizer.normalize(intent.flow)
        val app = normalize(intent.app)
        val sig = signature(normalizedFlow)
        val all = load(context).toMutableList()
        val old = all.indexOfFirst { it.app == app && it.signature == sig }
        val utterancePattern = utterancePattern(userText, normalizedFlow)
        val flowTemplate = templateFlow(normalizedFlow)
        val next = if (old >= 0) {
            val previous = all.removeAt(old)
            previous.copy(locators = merge(previous.locators, locators), successes = previous.successes + 1,
                utterancePattern = utterancePattern.ifBlank { previous.utterancePattern },
                flowTemplate = flowTemplate.ifEmpty { previous.flowTemplate })
        } else Template("wf_${System.currentTimeMillis()}", app, sig, locators, 1, utterancePattern, flowTemplate)
        all.add(next)
        save(context, all.sortedByDescending { it.successes }.take(MAX_TEMPLATES))
    }

    /** لا نفعل shortcut قبل نجاحين؛ أول نجاح مجرد observation لا قاعدة موثوقة. */
    fun usable(locator: LearnedLocator?, successes: Int): Boolean =
        locator != null && locator.confidence >= 2 && successes >= 2 && locator.viewId.isNotBlank()

    private fun signature(flow: List<UiFlowStep>): String {
        val values = flow.filter { it.op == "type" }.map { it.text }.filter { it.isNotBlank() }
        return flow.joinToString("→") { f ->
            val normalized = normalize(listOf(f.target, f.expected).joinToString(" "))
            val templated = values.fold(normalized) { acc, value -> acc.replace(normalize(value), "{value}") }
            "${f.op}:$templated"
        }
    }

    /** 0..1: نفس العمليات إلزامي، ثم تشابه الأوصاف بعد إزالة القيم المكتوبة. */
    private fun semanticScore(current: List<UiFlowStep>, learned: List<UiFlowStep>): Double {
        if (current.size != learned.size || current.map { it.op } != learned.map { it.op }) return 0.0
        val dynamic = current.filter { it.op == "type" }.map { normalize(it.text) }.filter { it.isNotBlank() }
        fun terms(f: UiFlowStep): Set<String> = normalize("${f.target} ${f.expected}")
            .let { raw -> dynamic.fold(raw) { acc, v -> acc.replace(v, " ") } }
            .split(' ').filter { it.length >= 3 && it !in GENERIC_TERMS }.toSet()
        val scores = current.indices.map { i ->
            val a = terms(current[i]); val b = terms(learned[i])
            when {
                a.isEmpty() || b.isEmpty() -> 0.20 // ترتيب وحده لا يكفي لتفعيل shortcut
                else -> a.intersect(b).size.toDouble() / a.union(b).size.toDouble()
            }
        }
        // ترتيب الأفعال gate فقط؛ الكلمات الثابتة تمنع خلط flows متشابهة جدًا.
        return 0.40 + scores.average() * 0.60
    }

    private fun utterancePattern(userText: String, flow: List<UiFlowStep>): String {
        var out = userText.trim()
        val values = flow.filter { it.op == "type" }.map { it.text }.filter { it.isNotBlank() }
        values.forEachIndexed { i, value ->
            // لا نخزّن template إن كانت القيمة غير مذكورة في كلام المستخدم؛ لا نخمن مكانها.
            val at = out.indexOf(value, ignoreCase = true)
            if (at < 0) return ""
            out = out.substring(0, at) + "{v$i}" + out.substring(at + value.length)
        }
        return out
    }

    private fun templateFlow(flow: List<UiFlowStep>): List<UiFlowStep> {
        val values = flow.filter { it.op == "type" }.map { it.text }.filter { it.isNotBlank() }
        fun replace(value: String): String = values.foldIndexed(value) { i, acc, item ->
            if (item.isBlank()) acc else acc.replace(item, "{v$i}", ignoreCase = true)
        }
        return flow.map { f -> f.copy(target = replace(f.target), text = replace(f.text), expected = replace(f.expected)) }
    }

    private fun renderIfMatches(template: Template, userText: String): List<UiFlowStep>? {
        val slots = Regex("\\{v(\\d+)\\}").findAll(template.utterancePattern).toList()
        if (slots.isEmpty()) return null
        val regexText = buildString {
            var cursor = 0
            slots.forEach { m ->
                append(Regex.escape(template.utterancePattern.substring(cursor, m.range.first)))
                append("(.+?)")
                cursor = m.range.last + 1
            }
            append(Regex.escape(template.utterancePattern.substring(cursor)))
        }
        val match = Regex("^$regexText$", RegexOption.IGNORE_CASE).matchEntire(userText.trim()) ?: return null
        val values = slots.mapIndexed { i, m -> m.groupValues[1].toInt() to match.groupValues[i + 1].trim() }.toMap()
        if (values.values.any { it.isBlank() }) return null
        fun fill(s: String): String = values.entries.fold(s) { acc, (i, value) -> acc.replace("{v$i}", value) }
        return template.flowTemplate.map { f -> f.copy(target = fill(f.target), text = fill(f.text), expected = fill(f.expected)) }
    }

    private fun merge(old: List<LearnedLocator?>, fresh: List<LearnedLocator?>): List<LearnedLocator?> =
        fresh.indices.map { i ->
            val a = old.getOrNull(i); val b = fresh[i]
            if (a != null && b != null && a.viewId == b.viewId && a.role == b.role)
                b.copy(confidence = (a.confidence + 1).coerceAtMost(10))
            else b?.copy(confidence = 1) ?: a
        }

    private fun load(context: Context): List<Template> {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TEMPLATES, "[]") ?: "[]"
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val locs = o.optJSONArray("locators") ?: JSONArray()
                val parsedLocators = (0 until locs.length()).map { j ->
                    locs.optJSONObject(j)?.let { x ->
                        LearnedLocator(
                            x.optString("id"), x.optString("role"),
                            x.optString("class"), x.optInt("confidence")
                        )
                    }
                }
                val flowArray = o.optJSONArray("flow")
                val parsedFlow = if (flowArray == null) emptyList() else {
                    (0 until flowArray.length()).mapNotNull { j ->
                        flowArray.optJSONObject(j)?.let { x ->
                            UiFlowStep(
                                x.optString("op"), x.optString("target"),
                                x.optString("text"), x.optString("expected")
                            )
                        }
                    }
                }
                Template(
                    id = o.optString("id"), app = o.optString("app"),
                    signature = o.optString("signature"), locators = parsedLocators,
                    successes = o.optInt("successes", 0),
                    utterancePattern = o.optString("utterance", ""), flowTemplate = parsedFlow
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, templates: List<Template>) {
        val arr = JSONArray()
        templates.forEach { t -> arr.put(JSONObject().apply {
            put("id", t.id); put("app", t.app); put("signature", t.signature); put("successes", t.successes)
            put("utterance", t.utterancePattern)
            put("flow", JSONArray().apply { t.flowTemplate.forEach { f -> put(JSONObject().apply {
                put("op", f.op); put("target", f.target); put("text", f.text); put("expected", f.expected)
            }) } })
            put("locators", JSONArray().apply { t.locators.forEach { l -> put(l?.let { x -> JSONObject().apply {
                put("id", x.viewId); put("role", x.role); put("class", x.className); put("confidence", x.confidence)
            } }) } })
        }) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TEMPLATES, arr.toString()).apply()
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9\\u0600-\\u06ff]+"), " ").trim()

    private val GENERIC_TERMS = setOf("tap", "click", "type", "wait", "open", "the", "for", "and", "بعد", "على", "الى", "من", "في", "افتح", "اضغط", "اكتب", "انتظر", "زر", "حقل", "screen", "visible")
}
