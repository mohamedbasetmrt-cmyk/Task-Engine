// TaskEvents.kt — Axon Task Engine V1: أحداث أساسية فقط
package com.example.app_abdelbaset

enum class TaskEventType {
    TASK_CREATED,
    TASK_STARTED,
    STEP_STARTED,
    STEP_COMPLETED,
    STEP_FAILED,
    TASK_COMPLETED,
    TASK_FAILED,
    TASK_CANCELLED
}

data class TaskEvent(
    val taskId: String,
    val type: TaskEventType,
    val detail: String = "",
    val atMillis: Long = System.currentTimeMillis()
)
