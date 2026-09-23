// TaskUiTargeting.kt — local, deterministic candidate ranking before any model call.
package com.example.app_abdelbaset

/**
 * طبقة سريعة بين Accessibility والـ vision. لا تتخذ action بنفسها؛ فقط تقلص فضاء
 * البحث وتشرح لماذا عنصر ما مرشح. بهذا يظل الـ model مسؤولًا عن الفهم البصري عند الغموض.
 */
object TaskUiTargeting {
    data class Candidate(val index: Int, val score: Int, val reason: String)

    fun rankedIds(
        snap: TaskUiSnapshot.UiSnapshot,
        goal: String,
        allowed: Set<String>,
        limit: Int = 36
    ): Set<Int> = rank(snap, goal, allowed).take(limit).map { it.index }.toSet()

    fun summary(snap: TaskUiSnapshot.UiSnapshot, goal: String, allowed: Set<String>): String =
        rank(snap, goal, allowed).take(12).joinToString("; ") { c ->
            val n = snap.nodes[c.index]
            "#${c.index}(${c.score},${c.reason}) '${displayLabel(n).take(42)}'"
        }

    fun findLearnedCandidate(snap: TaskUiSnapshot.UiSnapshot, locator: LearnedLocator, allowed: Set<String>): Int? {
        if (locator.viewId.isBlank()) return null
        val wantsType = "type" in allowed
        val hits = snap.nodes.withIndex().filter { (_, n) ->
            n.enabled && n.viewId == locator.viewId &&
                    (locator.role.isBlank() || n.role == locator.role) &&
                    (if (wantsType) n.editable else n.clickable || n.editable)
        }
        return hits.singleOrNull()?.index
    }

    /** مفتاح مناسب لمنع الدوران داخل خطوة واحدة حتى لو تغيّر ترتيب boxes. */
    fun stableKey(n: A11yNodeItem): String {
        val id = listOf(normalize(n.viewId), normalize(n.label), normalize(n.text), normalize(n.contentDesc))
            .filter { it.isNotBlank() }.joinToString("|")
        // العناصر الفارغة المتشابهة تحتاج منطقة تقريبية كي لا يُستبعد كل الـ rows مرة واحدة.
        val geo = TaskUiSnapshot.parseBounds(n.bounds)?.let { r ->
            "${r.centerX() / 160},${r.centerY() / 160},${r.width() / 120},${r.height() / 120}"
        } ?: "?"
        return "$id|${n.role}|${n.className.substringAfterLast('.')}|$geo"
    }

    private fun rank(
        snap: TaskUiSnapshot.UiSnapshot,
        goal: String,
        allowed: Set<String>
    ): List<Candidate> {
        val wantsType = "type" in allowed
        val goalNorm = normalize(goal)
        val terms = tokens(goalNorm)
        val wantsAi = goalNorm.contains("meta ai") || goalNorm.contains("assistant")
        return snap.nodes.withIndex().mapNotNull { (index, n) ->
            if (wantsType && !n.editable) return@mapNotNull null
            if (!wantsType && !n.clickable && !n.editable) return@mapNotNull null
            if (!n.enabled) return@mapNotNull null
            val hay = normalize(listOf(n.label, n.text, n.contentDesc, n.viewId, n.role).joinToString(" "))
            var score = 20
            val reasons = ArrayList<String>(3)
            if (n.editable && wantsType) { score += 65; reasons += "input" }
            if (n.role == "BUTTON" || n.role == "TAB" || n.role == "TOGGLE") { score += 18; reasons += n.role.lowercase() }
            if (n.label.isNotBlank()) { score += 12; reasons += "label" }
            val hits = terms.count { it.length >= 2 && hay.contains(it) }
            if (hits > 0) { score += hits * 28; reasons += "text:$hits" }
            if (goalNorm.isNotBlank() && hay.contains(goalNorm)) { score += 45; reasons += "exact" }
            if (!wantsAi && isSystemSuggestion(n)) { score -= 65; reasons += "system-suggestion" }
            if (n.className.contains("Layout", ignoreCase = true) && n.label.isBlank()) score -= 14
            Candidate(index, score, reasons.joinToString(",").ifBlank { "actionable" })
        }.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.index })
    }

    private fun isSystemSuggestion(n: A11yNodeItem): Boolean {
        val id = n.viewId.lowercase()
        return id.contains("meta_ai") || id.contains("ai_assistant") || id.contains("search_suggestion")
    }

    private fun displayLabel(n: A11yNodeItem): String =
        n.label.ifBlank { n.text.ifBlank { n.contentDesc.ifBlank { n.viewId } } }

    private fun normalize(value: String): String = value.lowercase()
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ى', 'ي').replace('ة', 'ه')
        .replace(Regex("[^a-z0-9\\u0600-\\u06ff]+"), " ").trim()

    private fun tokens(value: String): Set<String> = value.split(' ').filter { it.length >= 2 }.toSet()
}
