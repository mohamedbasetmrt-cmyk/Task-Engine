// TaskRepository.kt — Axon Task Engine V1: حفظ خفيف (ذاكرة + SharedPrefs للكشف عن موت الـ process)
// المتفق عليه: لو الـ process مات والـ Task كانت RUNNING → تُسجَّل FAILED (process-killed) ولا تُستأنف في V1.
package com.example.app_abdelbaset

import android.content.Context
import android.util.Log

object TaskRepository {

    private const val TAG = "TaskEngine"
    private const val PREFS = "axon_task_prefs"
    private const val KEY_RUNNING_ID = "running_task_id"
    private const val KEY_RUNNING_GOAL = "running_task_goal"
    private const val KEY_RUNNING_TEXT = "running_task_text"
    private const val KEY_RUNNING_START = "running_task_start"
    private const val KEY_RUNNING_STEP = "running_task_step"

    private val history = java.util.Collections.synchronizedList(mutableListOf<TaskRecord>())
    private const val MAX_HISTORY = 50

    fun saveRunning(context: Context, taskId: String, goal: TaskGoal, userText: String, step: String) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_RUNNING_ID, taskId)
                .putString(KEY_RUNNING_GOAL, goal.name)
                .putString(KEY_RUNNING_TEXT, userText.take(200))
                .putLong(KEY_RUNNING_START, System.currentTimeMillis())
                .putString(KEY_RUNNING_STEP, step)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "saveRunning failed: ${e.message}")
        }
    }

    fun clearRunning(context: Context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_RUNNING_ID).remove(KEY_RUNNING_GOAL).remove(KEY_RUNNING_TEXT)
                .remove(KEY_RUNNING_START).remove(KEY_RUNNING_STEP).apply()
        } catch (_: Exception) {}
    }

    /** تُنادى عند فتح شاشة الـ Task: لو فيه task متعلقة من process مات → سجلها FAILED وارجع وصفها */
    fun detectStaleRunning(context: Context): String? {
        return try {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val id = p.getString(KEY_RUNNING_ID, null) ?: return null
            val goal = p.getString(KEY_RUNNING_GOAL, "?") ?: "?"
            val step = p.getString(KEY_RUNNING_STEP, "?") ?: "?"
            val text = p.getString(KEY_RUNNING_TEXT, "") ?: ""
            clearRunning(context)
            val rec = TaskRecord(id = id, goal = runCatching { TaskGoal.valueOf(goal) }.getOrDefault(TaskGoal.UNKNOWN),
                userText = text, state = TaskState.FAILED,
                error = "process-killed at step $step (recorded, not resumed in V1)",
                finishedAt = System.currentTimeMillis())
            pushHistory(rec)
            TaskExecutionLogger.log(id, "TASK_FAILED — process-killed at step $step (goal=$goal). Recorded for diagnosis.")
            Log.e(TAG, "[$id] FAIL @lifecycle — process-killed at step $step (goal=$goal). Recorded, not resumed in V1.")
            "آخر task ($goal) اتقطع لما الـ App اتقفل عند خطوة [$step] — اتسجل كفشل عشان نعرف إيه اللي حصل."
        } catch (e: Exception) {
            Log.w(TAG, "detectStale failed: ${e.message}")
            null
        }
    }

    fun pushHistory(rec: TaskRecord) {
        synchronized(history) {
            history.add(rec)
            while (history.size > MAX_HISTORY) history.removeAt(0)
        }
    }

    fun history(): List<TaskRecord> = synchronized(history) { history.toList().reversed() }
}
