// TaskWaiter.kt — Axon Task Engine V2: انتظار دلالي (vision) — polling + timeout مسجل
// المبدأ: المعنى من الـ Vision (صورة+عناصر)، بلا كلمات hardcoded.
// المطابق الحرفي موجود فقط كوضع منخفض (degraded) لو المزود وقع — مسجل بـ WARN.
package com.example.app_abdelbaset

object TaskWaiter {

    data class WaitResult(
        val found: Boolean,
        val polls: Int,
        val elapsedMs: Long,
        val matchedDesc: String = "",
        val degraded: Boolean = false,
        val cancelled: Boolean = false
    )

    /**
     * ينتظر ظهور هدف موصوف دلاليًا — blocking على خيط خلفية.
     * كل poll = snapshot طازج + سؤال vision واحد. 429 → backoff (لا يُحتسب poll ضائع).
     */
    fun waitForVision(
        context: android.content.Context,
        taskId: String,
        stepId: String,
        target: String,
        timeoutSec: Long = 15,
        shouldAbort: () -> Boolean = { false }
    ): WaitResult {
        val t0 = System.currentTimeMillis()
        val deadline = t0 + timeoutSec * 1000L
        var polls = 0
        var rateRounds = 0
        TaskExecutionLogger.log(taskId, "WAIT $stepId — waiting (vision) for \"$target\" (timeout=${timeoutSec}s)")
        while (System.currentTimeMillis() < deadline) {
            if (shouldAbort()) return WaitResult(false, polls, System.currentTimeMillis() - t0, cancelled = true)
            polls++
            val snap = TaskUiSnapshot.captureBlocking(context, taskId, "$stepId#poll$polls")
            if (snap.nodes.isEmpty()) {
                TaskExecutionLogger.log(taskId, "WAIT $stepId — poll $polls: empty tree, retrying")
                sleepBreakable(1500, shouldAbort)
                continue
            }
            val p = TaskUiReasoner.decidePresenceBlocking(
                context, taskId, "$stepId#poll$polls", snap, target)
            if (p != null) {
                if (p.visible) {
                    val ms = System.currentTimeMillis() - t0
                    val el = snap.nodes.getOrNull(p.element)
                    val desc = if (el != null) "#${p.element} ${el.className.substringAfterLast('.')} " +
                            "\"${el.text.ifBlank { el.contentDesc }.take(50)}\"" else "#${p.element}"
                    TaskExecutionLogger.log(taskId, "WAIT $stepId — DONE after $polls poll(s), ${ms}ms: $desc")
                    return WaitResult(true, polls, ms, desc)
                }
                TaskExecutionLogger.log(taskId, "WAIT $stepId — poll $polls: not visible yet (nodes=${snap.nodes.size})")
                sleepBreakable(3000, shouldAbort)
                continue
            }
            // presence فشل: 429 → backoff، غيره → مسح نصي منخفض
            if (TaskUiReasoner.lastErrorRateLimited()) {
                rateRounds++
                val remaining = deadline - System.currentTimeMillis()
                val ok = TaskRateLimit.backoffBlocking(taskId, "wait:$stepId",
                    TaskUiReasoner.lastError, rateRounds, remaining, shouldAbort)
                if (!ok) {
                    val ms = System.currentTimeMillis() - t0
                    return WaitResult(false, polls, ms)
                }
                continue
            }
            val deg = degradedTextScan(snap.nodes, target)
            if (deg != null) {
                val ms = System.currentTimeMillis() - t0
                TaskExecutionLogger.logWarn(taskId, "wait:$stepId",
                    "vision down — degraded text match accepted after $polls poll(s): $deg")
                return WaitResult(true, polls, ms, deg, degraded = true)
            }
            sleepBreakable(2500, shouldAbort)
        }
        val ms = System.currentTimeMillis() - t0
        TaskExecutionLogger.logFailure(taskId, "wait:$stepId",
            "timeout waiting for \"$target\"",
            extra = "polls=$polls elapsed=${ms}ms timeout=${timeoutSec}s")
        return WaitResult(false, polls, ms)
    }

    /** مسح نصي منخفض (degraded فقط): أي كلمة مميزة (4+ حروف) من الوصف */
    private fun degradedTextScan(nodes: List<A11yNodeItem>, target: String): String? {
        val words = target.lowercase().split(Regex("[^a-z0-9\\u0600-\\u06FF]+"))
            .filter { it.length >= 4 }.toSet()
        if (words.isEmpty()) return null
        for ((i, n) in nodes.withIndex()) {
            val hay = (n.text + " " + n.contentDesc).lowercase()
            val hit = words.firstOrNull { hay.contains(it) }
            if (hit != null) return "#$i '${hit}' in \"${n.text.ifBlank { n.contentDesc }.take(50)}\""
        }
        return null
    }

    private fun sleepBreakable(ms: Long, shouldAbort: () -> Boolean) {
        var left = ms
        while (left > 0) {
            if (shouldAbort()) return
            try { Thread.sleep(500L.coerceAtMost(left)) } catch (_: Exception) { return }
            left -= 500
        }
    }

    /**
     * انتظار حرفي قديم — أُبقي للتوافق فقط (الـ Engine الجديد يستخدم waitForVision).
     * @see waitForVision
     */
    fun waitForText(
        context: android.content.Context,
        taskId: String,
        stepId: String,
        target: String,
        timeoutSec: Long = 15,
        pollMs: Long = 1200
    ): WaitResult {
        val t0 = System.currentTimeMillis()
        val deadline = t0 + timeoutSec * 1000L
        var polls = 0
        TaskExecutionLogger.log(taskId, "WAIT $stepId — waiting for \"$target\" (timeout=${timeoutSec}s)")
        while (System.currentTimeMillis() < deadline) {
            polls++
            val cap = try { A11yScreenReader.captureNow(300) } catch (_: Exception) { null }
            val nodes = cap?.nodes.orEmpty()
            val hit = nodes.firstOrNull { n ->
                n.text.contains(target, ignoreCase = true) ||
                        n.contentDesc.contains(target, ignoreCase = true)
            }
            if (hit != null) {
                val ms = System.currentTimeMillis() - t0
                val desc = "#${nodes.indexOf(hit)} ${hit.className.substringAfterLast('.')} " +
                        "\"${hit.text.ifBlank { hit.contentDesc }.take(50)}\""
                TaskExecutionLogger.log(taskId, "WAIT $stepId — DONE after $polls poll(s), ${ms}ms: $desc")
                return WaitResult(true, polls, ms, desc)
            }
            if (polls == 1) {
                TaskExecutionLogger.log(taskId, "WAIT $stepId — poll 1/${timeoutSec * 1000 / pollMs}: not yet (nodes=${nodes.size})")
            }
            try { Thread.sleep(pollMs) } catch (_: Exception) { break }
        }
        val ms = System.currentTimeMillis() - t0
        TaskExecutionLogger.logFailure(taskId, "wait:$stepId",
            "timeout waiting for \"$target\"",
            extra = "polls=$polls elapsed=${ms}ms timeout=${timeoutSec}s")
        return WaitResult(false, polls, ms)
    }
}
