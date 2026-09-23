// TaskExecutionLogger.kt — Axon Task Engine V1: سجل التنفيذ (logcat + timeline للشاشة)
// القاعدة: أي FAIL يطلع في الـ logcat مع مكانه بالظبط (goal/step/where/reason) عشان نعرف حصل فين.
package com.example.app_abdelbaset

import android.util.Log

object TaskExecutionLogger {

    const val TAG = "TaskEngine"

    enum class Level { DEBUG, WARN, ERROR }

    data class LogLine(
        val taskId: String,
        val text: String,
        val atMillis: Long = System.currentTimeMillis(),
        val level: Level = Level.DEBUG
    )

    private val lines = java.util.Collections.synchronizedList(mutableListOf<LogLine>())
    private const val MAX_LINES = 500

    /** آخر خطوة معروفة لكل task — تُطبع مع أي FAIL لاحق عشان نعرف كنا فين */
    private val lastStepOf = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())

    fun trackStep(taskId: String, step: String) {
        lastStepOf[taskId] = step
    }

    fun clearTrack(taskId: String) {
        lastStepOf.remove(taskId)
    }

    fun log(taskId: String, text: String) {
        Log.d(TAG, "[$taskId] $text")
        append(taskId, text, Level.DEBUG)
    }

    fun logWarn(taskId: String, where: String, text: String) {
        val step = lastStepOf[taskId]?.let { " step=$it" } ?: ""
        Log.w(TAG, "[$taskId]$step WARN @$where — $text")
        append(taskId, "WARN @$where — $text", Level.WARN)
    }

    /**
     * السجل الأساسي للفشل: يظهر في logcat كـ ERROR مع المكان والسبب.
     * @param where مثال: "translate" / "plan" / "action:OPEN_SETTINGS" / "verify:VERIFY_SETTINGS" / "engine-loop"
     */
    fun logFailure(
        taskId: String,
        where: String,
        reason: String,
        goal: String = "",
        step: String = "",
        extra: String = "",
        throwable: Throwable? = null
    ) {
        val knownStep = step.ifBlank { lastStepOf[taskId].orEmpty() }
        val sb = StringBuilder("[$taskId] FAIL @$where")
        if (goal.isNotBlank()) sb.append(" goal=").append(goal)
        if (knownStep.isNotBlank()) sb.append(" step=").append(knownStep)
        sb.append(" — ").append(reason)
        if (extra.isNotBlank()) sb.append(" | ").append(extra.take(300))
        val msg = sb.toString()
        if (throwable != null) Log.e(TAG, msg, throwable)
        else Log.e(TAG, msg)
        append(taskId, "FAIL @$where — $reason${if (extra.isNotBlank()) " | ${extra.take(200)}" else ""}", Level.ERROR)
    }

    fun logAction(taskId: String, action: String, success: Boolean, detail: String) {
        if (success) log(taskId, "ACTION $action: OK — ${detail.take(200)}")
        else logFailure(taskId, "action:$action", detail)
    }

    fun logVerify(
        taskId: String,
        step: String,
        pass: Boolean,
        expected: String,
        actual: String
    ) {
        if (pass) log(taskId, "VERIFY $step: PASS (expected=$expected actual=$actual)")
        else logFailure(taskId, "verify:$step", "verification failed",
            step = step, extra = "expected=[$expected] actual=[$actual]")
    }

    fun logEvent(e: TaskEvent) {
        // أحداث الفشل تطلع ERROR في logcat، والباقي DEBUG
        if (e.type == TaskEventType.TASK_FAILED || e.type == TaskEventType.STEP_FAILED) {
            logFailure(e.taskId, "event:${e.type}", e.detail.ifBlank { e.type.name })
        } else {
            log(e.taskId, "EVENT ${e.type}${if (e.detail.isNotBlank()) " — ${e.detail}" else ""}")
        }
    }

    /** تقرير الفشل المجمّع لـ task — يُنسخ من الـ timeline أو يُطلب عند التشخيص */
    fun failureReport(taskId: String): String {
        val errs = synchronized(lines) { lines.filter { it.taskId == taskId && it.level == Level.ERROR }.toList() }
        if (errs.isEmpty()) return "No failures recorded for $taskId."
        return buildString {
            appendLine("Failure report [$taskId] (${errs.size}):")
            errs.forEach { appendLine("- ${it.text}") }
        }
    }

    /** سطور task معين للعرض في الـ timeline */
    fun linesFor(taskId: String): List<LogLine> =
        synchronized(lines) { lines.filter { it.taskId == taskId }.toList() }

    fun recentLines(limit: Int = 200): List<LogLine> =
        synchronized(lines) { lines.takeLast(limit).toList() }

    fun clear(taskId: String) {
        synchronized(lines) { lines.removeAll { it.taskId == taskId } }
        clearTrack(taskId)
    }

    private fun append(taskId: String, text: String, level: Level) {
        synchronized(lines) {
            lines.add(LogLine(taskId, text, level = level))
            while (lines.size > MAX_LINES) lines.removeAt(0)
        }
    }
}
