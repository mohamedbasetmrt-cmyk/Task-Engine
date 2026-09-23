// TaskPlanner.kt — Axon Task Engine V1+V2: planner حتمي (نية → خطوات ثابتة)
// الـ LLM لا يخطط هنا — هو يترجم لنية فقط (TaskIntentTranslator). التخطيط deterministic.
package com.example.app_abdelbaset

object TaskPlanner {

    /** توافق V1: خطة من الهدف فقط (بلا params) */
    fun buildPlan(goal: TaskGoal): List<TaskStep> =
        buildPlan(TaskIntentTranslator.IntentResult(goal, false, true))

    /** يحول النية (+params) لخطة ثابتة. UNKNOWN → خطة فارغة (تترفض بشياكة). */
    fun buildPlan(intent: TaskIntentTranslator.IntentResult, learned: TaskLearning.Match? = null): List<TaskStep> {
        val goal = intent.goal
        // ── V1 ──
        if (goal == TaskGoal.OPEN_SETTINGS) return listOf(
            TaskStep("open", TaskStepKind.OPEN_SETTINGS, "Open Settings"),
            TaskStep("verify", TaskStepKind.VERIFY_SETTINGS, "Verify Settings open")
        )
        if (goal == TaskGoal.OPEN_SETTINGS_AND_BACK) return listOf(
            TaskStep("open", TaskStepKind.OPEN_SETTINGS, "Open Settings"),
            TaskStep("verify", TaskStepKind.VERIFY_SETTINGS, "Verify Settings open"),
            TaskStep("back", TaskStepKind.BACK, "Go back"),
            TaskStep("verify_return", TaskStepKind.VERIFY_RETURN, "Verify return")
        )
        if (goal == TaskGoal.FAIL_TEST) return listOf(
            TaskStep("fail", TaskStepKind.FAIL_TEST, "Impossible destination")
        )
        // ── V2: عام ──
        if (goal == TaskGoal.OPEN_APP) {
            val app = intent.app.ifBlank { return emptyList() }
            return listOf(
                TaskStep("open", TaskStepKind.OPEN_APP, "Open $app", param = app),
                TaskStep("verify_open", TaskStepKind.VERIFY_APP, "Verify $app foreground", param = app)
            )
        }
        if (goal == TaskGoal.UI_WAIT) {
            val app = intent.app.ifBlank { return emptyList() }
            val target = intent.target.ifBlank { return emptyList() }
            return listOf(
                TaskStep("open", TaskStepKind.OPEN_APP, "Open $app", param = app),
                TaskStep("verify_open", TaskStepKind.VERIFY_APP, "Verify $app foreground", param = app),
                TaskStep("wait", TaskStepKind.UI_WAIT, "Wait for: $target",
                    param = target, timeoutSec = intent.timeoutSec)
            )
        }
        if (goal == TaskGoal.UI_RECOVER) {
            val app = intent.app.ifBlank { "Settings" }
            val target = intent.target.ifBlank { "nonexistent control xyz" }
            return listOf(
                TaskStep("open", TaskStepKind.OPEN_APP, "Open $app", param = app),
                TaskStep("verify_open", TaskStepKind.VERIFY_APP, "Verify $app foreground", param = app),
                // الهدف مستحيل عمدًا → الـ Engine يحاول recovery (re-observe) ثم يفشل مسجلًا
                TaskStep("tap", TaskStepKind.UI_CLICK, "Tap impossible: $target", param = target)
            )
        }
        if (goal == TaskGoal.UI_FLOW) {
            val app = intent.app.ifBlank { return emptyList() }
            if (intent.flow.isEmpty()) return emptyList()
            val steps = mutableListOf<TaskStep>()
            steps += TaskStep("open", TaskStepKind.OPEN_APP, "Open $app", param = app)
            steps += TaskStep("verify_open", TaskStepKind.VERIFY_APP, "Verify $app foreground", param = app)
            intent.flow.forEachIndexed { i, f ->
                val n = i + 1
                val locator = learned?.locators?.getOrNull(i)
                when (f.op) {
                    "scroll" -> {
                        if (f.target.isBlank()) return@forEachIndexed
                        val dir = if (f.text.lowercase() == "up") "up" else "down"
                        // السكرول يتحقق بنفسه (presence) — بلا verify لاحقة
                        steps += TaskStep("scroll$n", TaskStepKind.UI_SCROLL,
                            "Scroll $dir for: ${f.target}", param = f.target, param2 = dir,
                            expected = f.expected)
                    }
                    "click" -> {
                        if (f.target.isBlank()) return@forEachIndexed
                        // UI_CLICK يثبت post-condition من لقطة نفس المحاولة؛ لا تضف VERIFY_CHANGED
                        // متأخرًا لأنه قد يرى شاشة مستقرة مختلفة ويحوّل نجاحًا حقيقيًا إلى فشل.
                        val expected = f.expected.ifBlank {
                            "the UI opened, selected, activated, or navigated as a result of tapping ${f.target}"
                        }
                        steps += TaskStep("click$n", TaskStepKind.UI_CLICK, "Tap: ${f.target}",
                            param = f.target, expected = expected, learnedLocator = locator)
                    }
                    "type" -> {
                        if (f.text.isBlank()) return@forEachIndexed
                        steps += TaskStep("type$n", TaskStepKind.UI_TYPE, "Type \"${f.text}\"",
                            param = f.target, param2 = f.text, expected = f.expected, learnedLocator = locator)
                        steps += TaskStep("verify$n", TaskStepKind.VERIFY_TEXT, "Verify text present", param = f.text)
                    }
                    "back" -> steps += TaskStep("back$n", TaskStepKind.UI_BACK, "Go back")
                    "wait" -> if (f.target.isNotBlank()) steps += TaskStep("wait$n", TaskStepKind.UI_WAIT,
                        "Wait for: ${f.target}", param = f.target, timeoutSec = 15, expected = f.expected)
                }
            }
            return if (steps.size <= 1) emptyList() else steps
        }
        return emptyList()
    }
}
