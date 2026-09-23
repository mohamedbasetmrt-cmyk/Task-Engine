// TaskFlowNormalizer.kt — removes only provably redundant planning noise.
package com.example.app_abdelbaset

object TaskFlowNormalizer {
    /**
     * بعض النماذج تضيف click على نفس input بعد type. لا نحذف click إلا إذا كان الوصف
     * نفسه يثبت أنه input، وفيه كلمات مشتركة مع input السابق. أزرار send/submit تبقى.
     */
    fun normalize(flow: List<UiFlowStep>): List<UiFlowStep> {
        val out = ArrayList<UiFlowStep>(flow.size)
        flow.forEach { step ->
            val previous = out.lastOrNull()
            val redundantInputClick = step.op == "click" && previous?.op == "type" &&
                    isInputDescription(step.target) && sharesMeaning(previous.target, step.target)
            if (!redundantInputClick) out += step
        }
        return out
    }

    private fun isInputDescription(value: String): Boolean {
        val text = normalize(value)
        if (listOf("button", "icon", "send", "submit", "زر", "ايقونه", "ارسال").any { text.contains(it) }) return false
        return listOf("input", "field", "edittext", "text box", "query", "composer", "entry", "حقل", "ادخال").any {
            text.contains(it)
        }
    }

    private fun sharesMeaning(a: String, b: String): Boolean {
        val left = words(a); val right = words(b)
        return left.intersect(right).isNotEmpty()
    }

    private fun words(value: String): Set<String> = normalize(value).split(' ').filter { it.length >= 3 }.toSet()

    private fun normalize(value: String): String = value.lowercase()
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي')
        .replace(Regex("[^a-z0-9\\u0600-\\u06ff]+"), " ").trim()
}
