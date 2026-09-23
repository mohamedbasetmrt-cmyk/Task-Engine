// TaskEngine.kt — Axon Task Engine V1: حلقة التنفيذ (Goal → Task → Plan → Execute → Observe → Verify → Complete)
// مستقل تمامًا عن one-shot الحالي. LLM يُستخدم لترجمة النية فقط (call واحدة) ثم كل شيء حتمي.
package com.example.app_abdelbaset

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object TaskEngine {

    private const val TAG = "TaskEngine"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** الـ task الجاري حاليًا (واحد فقط في V1) */
    private val currentTaskId = AtomicReference<String?>(null)
    private val cancelFlag = AtomicBoolean(false)

    data class TaskCallbacks(
        val onEvent: (TaskEvent) -> Unit = {},
        val onLogRefresh: () -> Unit = {},
        val onFinished: (TaskRecord) -> Unit = {}
    )

    @Volatile var lastRecord: TaskRecord? = null
        private set

    fun isRunning(): Boolean = currentTaskId.get() != null

    fun cancel() {
        cancelFlag.set(true)
    }

    /**
     * يبدأ task جديد من نص اليوزر. يُنادى من الـ UI (يقفز لخيط خلفية فورًا).
     * التسلسل: translate(intent) → plan → foreground service → steps loop → finish.
     */
    fun submit(appContext: Context, userText: String, cb: TaskCallbacks = TaskCallbacks()) {
        if (isRunning()) {
            Log.w(TAG, "submit rejected — another task running")
            return
        }
        val ctx = appContext.applicationContext
        cancelFlag.set(false)
        executor.execute {
            val taskId = UUID.randomUUID().toString().take(8)
            currentTaskId.set(taskId)
            var intentGoalName = "?"
            var currentStepName = ""
            fun emit(e: TaskEvent) {
                TaskExecutionLogger.logEvent(e)
                try { TaskEdgeGlowController.onTaskEvent(e.type) } catch (_: Exception) {}
                mainHandler.post { try { cb.onEvent(e) } catch (_: Exception) {} ; try { cb.onLogRefresh() } catch (_: Exception) {} }
            }
            fun finish(rec: TaskRecord) {
                lastRecord = rec
                TaskRepository.pushHistory(rec)
                TaskRepository.clearRunning(ctx)
                TaskExecutionLogger.clearTrack(rec.id)
                currentTaskId.set(null)
                // COMPLETED/FAILED يبدأوا pulse (SUCCESS/ERROR) في الـ edge glow مدته PULSE_MS.
                // لو وقّفنا الـ foreground service فورًا، onDestroy() بينادي forceHide() ويقطع
                // النبضة قبل ما تخلص. فبنأجل الـ stop لحد ما النبضة تخلص، وبنتأكد إن مفيش
                // task جديد بدأ في الأثناء (guard بـ runningTaskId) قبل ما نوقف الـ service فعليًا.
                val needsPulseDelay = rec.state == TaskState.COMPLETED || rec.state == TaskState.FAILED
                if (needsPulseDelay) {
                    mainHandler.postDelayed({
                        try {
                            if (TaskForegroundService.runningTaskId == taskId) {
                                TaskForegroundService.stop(ctx)
                            }
                        } catch (_: Exception) {}
                    }, TaskEdgeGlowController.PULSE_MS + 200L)
                } else {
                    try { TaskForegroundService.stop(ctx) } catch (_: Exception) {}
                }
                mainHandler.post { try { cb.onFinished(rec) } catch (_: Exception) {}; try { cb.onLogRefresh() } catch (_: Exception) {} }
            }
            try {

            emit(TaskEvent(taskId, TaskEventType.TASK_CREATED, userText.take(120)))
            TaskForegroundService.start(ctx, taskId)
            try { TaskEdgeGlowController.attach(ctx) } catch (_: Exception) {}

            // ── 1) ترجمة النية: LLM call واحدة + fallback حتمي ──
            TaskExecutionLogger.log(taskId, "Resolving intent (learned template → LLM → rules)…")
            val resolvedIntent = TaskLearning.recallIntent(ctx, userText) ?: try {
                TaskIntentTranslator.translateBlocking(ctx, userText)
            } catch (e: Exception) {
                TaskExecutionLogger.logFailure(taskId, "translate", "translator threw: ${e.message}",
                    extra = "input=${userText.take(120)}", throwable = e)
                TaskIntentTranslator.ruleIntent(userText)
            }
            val normalizedFlow = if (resolvedIntent.goal == TaskGoal.UI_FLOW)
                TaskFlowNormalizer.normalize(resolvedIntent.flow) else resolvedIntent.flow
            val intent = if (normalizedFlow.size != resolvedIntent.flow.size) {
                TaskExecutionLogger.log(taskId, "PLAN normalized: removed ${resolvedIntent.flow.size - normalizedFlow.size} redundant input click(s)")
                resolvedIntent.copy(flow = normalizedFlow)
            } else resolvedIntent
            intentGoalName = intent.goal.name
            if (intent.usedFallback) {
                TaskExecutionLogger.logWarn(taskId, "translate",
                    "LLM unavailable/failed → rule fallback used (goal=${intent.goal}, ${intent.durationMs}ms)")
            }
            TaskExecutionLogger.log(taskId,
                "Intent → ${intent.goal} (viaLlm=${intent.viaLlm}, fallback=${intent.usedFallback}, " +
                        "${intent.durationMs}ms, translator=${intent.translator}, " +
                        "raw=${intent.rawLlm.take(200)})")

            if (cancelFlag.get()) {
                emit(TaskEvent(taskId, TaskEventType.TASK_CANCELLED, "before plan"))
                finish(TaskRecord(taskId, intent.goal, userText, TaskState.CANCELLED, finishedAt = System.currentTimeMillis()))
                return@execute
            }

            // ── 2) خطة حتمية (V1+V2: النية الكاملة بالـ params) ──
            val learnedMatch = TaskLearning.match(ctx, intent)
            if (learnedMatch != null) TaskExecutionLogger.log(taskId,
                "LEARNING template=${learnedMatch.id} matched score=${"%.2f".format(learnedMatch.score)} " +
                        "successes=${learnedMatch.successes} — evidence remains required")
            val plan = TaskPlanner.buildPlan(intent, learnedMatch)
            if (plan.isEmpty()) {
                TaskExecutionLogger.logFailure(taskId, "plan", "no plan for goal=${intent.goal} — unsupported",
                    goal = intent.goal.name, extra = "input=${userText.take(120)} app=${intent.app} target=${intent.target}")
                emit(TaskEvent(taskId, TaskEventType.TASK_FAILED, "unsupported goal=${intent.goal}"))
                emit(TaskEvent(taskId, TaskEventType.TASK_FAILED, TaskVoiceReporter.unsupported()))
                finish(TaskRecord(taskId, intent.goal, userText, TaskState.FAILED,
                    error = "unsupported", finishedAt = System.currentTimeMillis()))
                return@execute
            }

            emit(TaskEvent(taskId, TaskEventType.TASK_STARTED, "goal=${intent.goal} steps=${plan.size}"))
            TaskExecutionLogger.log(taskId, "Plan: ${plan.joinToString(" → ") { it.kind.name }}")
            mainHandler.post { try { cb.onLogRefresh() } catch (_: Exception) {} }

            // ── 3) حلقة الخطوات ──
            var failedReason: String? = null
            // دليل آخر فعل بصري. لا نعيد تصوير الشاشة في verify ونلغي نجاحًا موثقًا.
            var lastPreSig = ""
            var lastActionProof: ActionProof? = null
            val learnedObserved = mutableMapOf<String, LearnedLocator>()

            // ── V2 helpers (snapshot → reason → act) ──
            fun runOpenApp(step: TaskStep): String? {
                TaskExecutionLogger.log(taskId, "ACTION OPEN_APP \"${step.param}\" — via MobileActionExecutor")
                return try {
                    val frame = org.json.JSONObject().apply {
                        put("action", "open_app")
                        put("params", org.json.JSONObject().apply { put("app_name", step.param) })
                    }
                    MobileActionExecutor(ctx).execute(frame) { }
                    try { Thread.sleep(1500) } catch (_: Exception) {}
                    val observed = TaskObserver.observe(ctx)
                    TaskExecutionLogger.log(taskId,
                        "ACTION OPEN_APP: dispatched — observe pkg=${observed.foregroundPackage}")
                    null
                } catch (e: Exception) {
                    TaskExecutionLogger.logFailure(taskId, "action:OPEN_APP",
                        "threw: ${e.message}", goal = intentGoalName, step = step.id, throwable = e)
                    "open ${step.param} failed: ${e.message}"
                }
            }

            /** click بصري مع recovery: حتى 3 ملاحظات (re-observe) قبل الفشل المسجل */
            fun runUiClick(step: TaskStep): String? {
                // فلتر أمان: هدف يطابق التطبيق المفتوح نفسه (أيقونة/اسم الـ app) يُرفض قبل أي فعل
                val appNorm = intent.app.lowercase().filter { it.isLetterOrDigit() }
                if (appNorm.length >= 3 &&
                    step.param.lowercase().filter { it.isLetterOrDigit() }.contains(appNorm)
                ) {
                    TaskExecutionLogger.logFailure(taskId, "safety:${step.id}",
                        "target references the opened app itself — refusing",
                        goal = intentGoalName, step = step.id,
                        extra = "app=${intent.app} target=${step.param}")
                    return "safety: target \"${step.param}\" references the opened app itself (${intent.app})"
                }
                var attempt = 0
                var rateRounds = 0
                var lastErr = ""
                // عناصر مرفوضة بهوية ثابتة، لا index عابر يتغير بعد كل re-observe.
                val excludedKeys = mutableSetOf<String>()
                fun isFullScreen(n: A11yNodeItem, sw: Int, sh: Int): Boolean {
                    val r = TaskUiSnapshot.parseBounds(n.bounds) ?: return false
                    return r.width() > sw * 0.85f && r.height() > sh * 0.85f
                }
                fun hasTapAlternative(snap: TaskUiSnapshot.UiSnapshot, skipIdx: Int, excluded: Set<Int>, sw: Int, sh: Int): Boolean =
                    snap.nodes.withIndex().any { (i, n) ->
                        i != skipIdx && i !in excluded && (n.clickable || n.editable) &&
                                !isFullScreen(n, sw, sh) &&
                                TaskUiSnapshot.parseBounds(n.bounds)?.let { it.width() > 2 && it.height() > 2 } == true
                    }
                while (attempt < 3) {
                    attempt++
                    if (attempt > 1) TaskExecutionLogger.log(taskId,
                        "RECOVERY ${step.id} — re-observing (attempt $attempt/3)")
                    val snap = TaskUiSnapshot.captureBlocking(ctx, taskId, "${step.id}#obs$attempt")
                    if (snap.nodes.isEmpty()) {
                        lastErr = "empty tree (attempt $attempt)"
                        try { Thread.sleep(1000) } catch (_: Exception) {}
                        continue
                    }
                    val excludedHere = snap.nodes.withIndex()
                        .filter { (_, n) -> TaskUiTargeting.stableKey(n) in excludedKeys }
                        .map { it.index }.toSet()
                    val exclNote = if (excludedHere.isEmpty()) "" else
                        " Do NOT pick ${excludedHere.sorted().joinToString(", ") { "#$it" }} (already tried/rejected)."
                    val learnedElement = step.learnedLocator?.takeIf { TaskLearning.usable(it, learnedMatch?.successes ?: 0) }
                        ?.let { TaskUiTargeting.findLearnedCandidate(snap, it, setOf("click", "wait")) }
                    val d = if (learnedElement != null) {
                        TaskExecutionLogger.log(taskId, "LEARNING ${step.id} — trusted locator hit #$learnedElement")
                        TaskUiReasoner.Decision("click", learnedElement, raw = "learned-locator")
                    } else TaskUiReasoner.decideBlocking(ctx, taskId, "${step.id}#r$attempt",
                        snap, "Find the UI element for: ${step.param}.$exclNote", setOf("click", "wait"))
                    if (d == null) {
                        // 429/quota → backoff ولا تُحتسب المحاولة؛ غيره → recovery عادي
                        if (TaskUiReasoner.lastErrorRateLimited()) {
                            rateRounds++
                            val ok = TaskRateLimit.backoffBlocking(taskId, step.id,
                                TaskUiReasoner.lastError, rateRounds, Long.MAX_VALUE) { cancelFlag.get() }
                            if (ok) { attempt--; continue }
                            lastErr = "rate-limit backoff exhausted: ${TaskUiReasoner.lastError.take(120)}"
                            break
                        }
                        lastErr = "reasoner failed (attempt $attempt)"
                        continue
                    }
                    if (d.action == "wait") {
                        lastErr = "target not visible (attempt $attempt)"
                        try { Thread.sleep(1200) } catch (_: Exception) {}
                        continue
                    }
                    val node = snap.nodes.getOrNull(d.element)
                        ?: run { lastErr = "bad element ${d.element}"; continue }
                    val targetKey = TaskUiTargeting.stableKey(node)
                    if (node.viewId != "-") learnedObserved[step.id] = LearnedLocator(node.viewId, node.role, node.className, 1)
                    if (d.element in excludedHere) {
                        lastErr = "reasoner repeated rejected target"
                        continue
                    }
                    // فلتر full-screen كودي (قرار + prompt): استبعاد الحاوية المليانة لو فيه بدائل
                    val dm = ctx.resources.displayMetrics
                    if (isFullScreen(node, dm.widthPixels, dm.heightPixels) &&
                        hasTapAlternative(snap, d.element, excludedHere, dm.widthPixels, dm.heightPixels)
                    ) {
                        TaskExecutionLogger.logWarn(taskId, "safety:${step.id}",
                            "skipped full-screen #${d.element} — re-asking without it")
                        excludedKeys.add(targetKey)
                        lastErr = "full-screen pick rejected"
                        continue
                    }
                    val rect = TaskUiSnapshot.parseBounds(node.bounds)
                        ?: run { lastErr = "bad bounds"; continue }
                    lastPreSig = snap.signature
                    val tappedEditable = node.editable
                    val res = TapElementAction(rect, "#${d.element}", node).execute(ctx)
                    TaskExecutionLogger.logAction(taskId, "TAP_ELEMENT #${d.element}", res.success, res.detail)
                    if (!res.success) {
                        lastErr = "tap failed: ${res.detail}"
                        try { Thread.sleep(800) } catch (_: Exception) {}
                        continue
                    }
                    try { Thread.sleep(1200) } catch (_: Exception) {}
                    // تحقق بالسلسلة: دوسة حقل قابل للكتابة تُثبتها الكتابة اللاحقة — بلا فحص معزول
                    if (tappedEditable) {
                        TaskExecutionLogger.log(taskId,
                            "CHAIN ${step.id} — tap on editable #${d.element}, proof deferred to typing")
                        emit(TaskEvent(taskId, TaskEventType.STEP_COMPLETED,
                            "${step.id}:tap#${d.element}:chained"))
                        return null
                    }
                    // تحقق دلالي للأزرار: البصمة أولًا (رخيص)، ثم سؤال vision واحد
                    val postSnap = TaskUiSnapshot.captureBlocking(ctx, taskId, "${step.id}#post", wantShot = true)
                    if (postSnap.nodes.isEmpty()) {
                        TaskExecutionLogger.log(taskId,
                            "VERIFY ${step.id} — empty post-action tree")
                        excludedKeys.add(targetKey)
                        lastErr = "empty post-action tree"
                        continue
                    }
                    val changed = postSnap.signature != lastPreSig
                    val expected = step.expected.ifBlank {
                        "clear sign that tapping '${step.param}' worked (the expected new screen, panel, dialog, or control is open or active)"
                    }
                    // عند وجود post-condition صريح نتحقق منه حتى لو لم تتغير بصمة الشجرة؛
                    // بعض التطبيقات تغيّر highlight/focus فقط ولا يظهر ذلك في Accessibility tree.
                    if (!changed && step.expected.isBlank()) {
                        TaskExecutionLogger.log(taskId,
                            "VERIFY ${step.id} — no screen change after tap (sig=${postSnap.signature} pre=$lastPreSig)")
                        excludedKeys.add(targetKey)
                        lastErr = "no screen change after tap"
                        continue
                    }
                    val sem = TaskUiReasoner.decidePresenceBlocking(ctx, taskId, "${step.id}#sem", postSnap, expected)
                    if (sem == null) {
                        if (TaskUiReasoner.lastErrorRateLimited()) {
                            rateRounds++
                            val ok = TaskRateLimit.backoffBlocking(taskId, step.id,
                                TaskUiReasoner.lastError, rateRounds, Long.MAX_VALUE) { cancelFlag.get() }
                            if (ok) { attempt--; continue }
                            lastErr = "rate-limit backoff exhausted: ${TaskUiReasoner.lastError.take(120)}"
                            break
                        }
                        TaskExecutionLogger.logWarn(taskId, step.id,
                            "semantic check inconclusive — accepting on signature change")
                    } else if (!sem.visible) {
                        excludedKeys.add(targetKey)
                        lastErr = "tap had no visible effect"
                        continue
                    }
                    lastActionProof = ActionProof(step.id, "click", step.param, expected,
                        lastPreSig, postSnap.signature, changed, sem?.visible, d.element)
                    TaskExecutionLogger.log(taskId,
                        "PROOF ${step.id} action=click element=#${d.element} changed=$changed " +
                                "semantic=${sem?.visible ?: "inconclusive"} expected=\"${expected.take(100)}\"")
                    emit(TaskEvent(taskId, TaskEventType.STEP_COMPLETED,
                        "${step.id}:tap#${d.element}"))
                    return null
                }
                TaskExecutionLogger.logFailure(taskId, "recovery:${step.id}",
                    "exhausted 3 observations for \"${step.param}\"",
                    goal = intentGoalName, step = step.id, extra = lastErr)
                return "tap \"${step.param}\" failed after recovery: $lastErr"
            }

            fun runUiType(step: TaskStep): String? {
                var attempt = 0
                var rateRounds = 0
                var lastErr = ""
                while (attempt < 3) {
                    attempt++
                    if (attempt > 1) TaskExecutionLogger.log(taskId,
                        "RECOVERY ${step.id} — re-observing (attempt $attempt/3)")
                    val snap = TaskUiSnapshot.captureBlocking(ctx, taskId, "${step.id}#obs$attempt")
                    if (snap.nodes.isEmpty()) {
                        lastErr = "empty tree (attempt $attempt)"
                        try { Thread.sleep(1000) } catch (_: Exception) {}
                        continue
                    }
                    val learnedElement = step.learnedLocator?.takeIf { TaskLearning.usable(it, learnedMatch?.successes ?: 0) }
                        ?.let { TaskUiTargeting.findLearnedCandidate(snap, it, setOf("type", "wait")) }
                    val d = if (learnedElement != null) {
                        TaskExecutionLogger.log(taskId, "LEARNING ${step.id} — trusted input locator hit #$learnedElement")
                        TaskUiReasoner.Decision("type", learnedElement, step.param2, "learned-locator")
                    } else TaskUiReasoner.decideBlocking(ctx, taskId, "${step.id}#r$attempt",
                        snap, "Find the text input field for: ${step.param}",
                        setOf("type", "wait"), extraText = step.param2)
                    if (d == null) {
                        if (TaskUiReasoner.lastErrorRateLimited()) {
                            rateRounds++
                            val ok = TaskRateLimit.backoffBlocking(taskId, step.id,
                                TaskUiReasoner.lastError, rateRounds, Long.MAX_VALUE) { cancelFlag.get() }
                            if (ok) { attempt--; continue }
                            lastErr = "rate-limit backoff exhausted: ${TaskUiReasoner.lastError.take(120)}"
                            break
                        }
                        lastErr = "reasoner failed (attempt $attempt)"
                        continue
                    }
                    if (d.action == "wait") {
                        lastErr = "input not visible (attempt $attempt)"
                        try { Thread.sleep(1200) } catch (_: Exception) {}
                        continue
                    }
                    val node = snap.nodes.getOrNull(d.element)
                        ?: run { lastErr = "bad element ${d.element}"; continue }
                    if (node.viewId != "-") learnedObserved[step.id] = LearnedLocator(node.viewId, node.role, node.className, 1)
                    val rect = TaskUiSnapshot.parseBounds(node.bounds)
                        ?: run { lastErr = "bad bounds"; continue }
                    lastPreSig = snap.signature
                    val res = TypeElementAction(rect, step.param2, "#${d.element}", node).execute(ctx)
                    TaskExecutionLogger.logAction(taskId, "TYPE_ELEMENT #${d.element}", res.success, res.detail)
                    if (!res.success) {
                        lastErr = "type failed: ${res.detail}"
                        try { Thread.sleep(800) } catch (_: Exception) {}
                        continue
                    }
                    // إثبات الكتابة من نفس attempt؛ VERIFY_TEXT اللاحقة تبقى guard توافق
                    // فقط ولا تكون الدليل الوحيد إذا انتقلت الواجهة سريعًا.
                    try { Thread.sleep(450) } catch (_: Exception) {}
                    val postSnap = TaskUiSnapshot.captureBlocking(ctx, taskId, "${step.id}#post", wantShot = false)
                    val typed = TaskVerifier.verifyTextPresent(postSnap.nodes, step.param2)
                    if (!typed.pass) {
                        lastErr = "typed text not observable: ${typed.actual}"
                        continue
                    }
                    lastActionProof = ActionProof(step.id, "type", step.param, step.expected.ifBlank { step.param2 },
                        lastPreSig, postSnap.signature, postSnap.signature != lastPreSig, true, d.element)
                    TaskExecutionLogger.log(taskId,
                        "PROOF ${step.id} action=type element=#${d.element} text-present=true")
                    emit(TaskEvent(taskId, TaskEventType.STEP_COMPLETED,
                        "${step.id}:type#${d.element}"))
                    return null
                }
                TaskExecutionLogger.logFailure(taskId, "recovery:${step.id}",
                    "exhausted 3 observations for typing \"${step.param2}\"",
                    goal = intentGoalName, step = step.id, extra = lastErr)
                return "type \"${step.param2}\" failed after recovery: $lastErr"
            }

            fun runUiWait(step: TaskStep): String? {
                val r = TaskWaiter.waitForVision(ctx, taskId, step.id, step.param, step.timeoutSec) { cancelFlag.get() }
                if (r.cancelled) return "CANCELLED_SENTINEL"
                return if (r.found) {
                    emit(TaskEvent(taskId, TaskEventType.STEP_COMPLETED,
                        "${step.id}:found polls=${r.polls}${if (r.degraded) " degraded" else ""}"))
                    null
                } else {
                    "wait timeout for \"${step.param}\" (${r.polls} polls, ${r.elapsedMs}ms)"
                }
            }

            /** تحقق سريع: التطبيق المطلوب فعلًا في الواجهة؟ — fail fast بدل الدوس في الهوا */
            fun appKey(app: String): String {
                val n = app.lowercase()
                val known = listOf("whatsapp", "settings", "instagram", "telegram", "facebook",
                    "youtube", "chrome", "gmail", "maps", "contacts", "camera", "gallery",
                    "messages", "clock", "calculator", "spotify", "tiktok", "outlook")
                for (k in known) if (n.contains(k)) return k
                return n.split(Regex("[^a-z0-9]+")).firstOrNull { it.length >= 2 } ?: "app"
            }

            fun runVerifyApp(step: TaskStep): String? {
                val key = appKey(step.param)
                var attempt = 0
                var lastPkg = "unknown"
                var lastSource = "unknown"
                while (attempt < 3) {
                    attempt++
                    try {
                        val obs = TaskObserver.observeWithSource(ctx)
                        lastPkg = obs.state.foregroundPackage
                        lastSource = obs.source
                    } catch (e: Exception) {
                        TaskExecutionLogger.logFailure(taskId, "observe:${step.id}",
                            "observer threw: ${e.message}", goal = intentGoalName, step = step.id, throwable = e)
                    }
                    TaskExecutionLogger.log(taskId,
                        "VERIFY_APP ${step.id} #$attempt/3 via=$lastSource pkg=$lastPkg (expect ~$key)")
                    if (lastPkg.lowercase().contains(key)) {
                        TaskExecutionLogger.logVerify(taskId, step.id, true,
                            "${step.param} foreground", lastPkg)
                        emit(TaskEvent(taskId, TaskEventType.STEP_COMPLETED, "${step.id}:PASS via=$lastSource"))
                        return null
                    }
                    if (attempt < 3) try { Thread.sleep(1200) } catch (_: Exception) {}
                }
                TaskExecutionLogger.logVerify(taskId, step.id, false,
                    "${step.param} foreground (~$key)", lastPkg)
                emit(TaskEvent(taskId, TaskEventType.STEP_FAILED, "${step.id}:FAIL pkg=$lastPkg"))
                return if (lastPkg == "unknown") {
                    "verify app failed: ${step.param} not observed (saw unknown ×3) — " +
                            "شغّل Axon Assistant من Accessibility أو امنح Usage Access"
                } else {
                    "verify app failed: expected ${step.param} foreground but saw $lastPkg"
                }
            }

            /** سكرول خفيف طبيعي للاستكشاف: swipe قصير + presence — حد أقصى 3 مرات */
            fun runUiScroll(step: TaskStep): String? {
                val dir = if (step.param2.lowercase() == "up") "up" else "down"
                var swipes = 0
                var rateRounds = 0
                while (swipes < 3) {
                    if (cancelFlag.get()) return "CANCELLED_SENTINEL"
                    val snap = TaskUiSnapshot.captureBlocking(ctx, taskId, "${step.id}#chk$swipes")
                    if (snap.nodes.isNotEmpty()) {
                        val p = TaskUiReasoner.decidePresenceBlocking(ctx, taskId, "${step.id}#chk$swipes",
                            snap, step.param)
                        if (p != null) {
                            if (p.visible) {
                                emit(TaskEvent(taskId, TaskEventType.STEP_COMPLETED,
                                    "${step.id}:found#${p.element} swipes=$swipes"))
                                return null
                            }
                        } else if (TaskUiReasoner.lastErrorRateLimited()) {
                            rateRounds++
                            val ok = TaskRateLimit.backoffBlocking(taskId, step.id,
                                TaskUiReasoner.lastError, rateRounds, Long.MAX_VALUE) { cancelFlag.get() }
                            if (!ok) return "rate-limit backoff exhausted: ${TaskUiReasoner.lastError.take(120)}"
                            continue
                        }
                    }
                    swipes++
                    if (swipes >= 3) break
                    TaskExecutionLogger.log(taskId, "SCROLL ${step.id} swipe $swipes/3 $dir (light) for \"${step.param}\"")
                    val res = SwipeAction(dir, distancePx = 350f, durationMs = 250).execute(ctx)
                    TaskExecutionLogger.logAction(taskId, "SWIPE_$dir", res.success, res.detail)
                    if (!res.success) return "swipe failed: ${res.detail}"
                }
                TaskExecutionLogger.logFailure(taskId, "scroll:${step.id}",
                    "target not found after 3 light swipes",
                    goal = intentGoalName, step = step.id, extra = "target=${step.param} dir=$dir")
                return "scroll: \"${step.param}\" not found after 3 swipes $dir"
            }

            fun runVerifyText(step: TaskStep): String? {
                val snap = TaskUiSnapshot.captureBlocking(ctx, taskId, "${step.id}#verify", wantShot = false)
                val vr = TaskVerifier.verifyTextPresent(snap.nodes, step.param)
                TaskExecutionLogger.logVerify(taskId, step.id, vr.pass, vr.expected, vr.actual)
                return if (vr.pass) {
                    emit(TaskEvent(taskId, TaskEventType.STEP_COMPLETED, "${step.id}:PASS"))
                    null
                } else {
                    emit(TaskEvent(taskId, TaskEventType.STEP_FAILED, "${step.id}:FAIL"))
                    "verify text failed at ${step.id}: expected [${vr.expected}] actual [${vr.actual}]"
                }
            }

            fun runVerifyChanged(step: TaskStep): String? {
                val proof = lastActionProof
                // توافق مع الخطط القديمة: إن كان الـ action وثّق pre/post بالفعل، فهذا هو الحكم.
                if (proof != null) {
                    val pass = proof.changed || proof.semanticPass == true
                    TaskExecutionLogger.logVerify(taskId, step.id, pass,
                        "action proof for ${proof.stepId}",
                        "pre=${proof.preSignature} post=${proof.postSignature} semantic=${proof.semanticPass}")
                    return if (pass) {
                        emit(TaskEvent(taskId, TaskEventType.STEP_COMPLETED, "${step.id}:PASS proof=${proof.stepId}"))
                        null
                    } else "verify changed failed at ${step.id}: action proof has no observable effect"
                }
                val snap = TaskUiSnapshot.captureBlocking(ctx, taskId, "${step.id}#verify", wantShot = false)
                val changed = snap.nodes.isNotEmpty() && snap.signature != lastPreSig
                TaskExecutionLogger.logVerify(taskId, step.id, changed,
                    "screen changed since action", "sig=${snap.signature} pre=$lastPreSig")
                return if (changed) {
                    emit(TaskEvent(taskId, TaskEventType.STEP_COMPLETED, "${step.id}:PASS"))
                    null
                } else {
                    emit(TaskEvent(taskId, TaskEventType.STEP_FAILED, "${step.id}:FAIL"))
                    "verify changed failed at ${step.id}: screen looks identical (sig=${snap.signature})"
                }
            }

            for (step in plan) {
                if (cancelFlag.get()) {
                    emit(TaskEvent(taskId, TaskEventType.TASK_CANCELLED, "at ${step.id}"))
                    finish(TaskRecord(taskId, intent.goal, userText, TaskState.CANCELLED,
                        currentStep = step.id, finishedAt = System.currentTimeMillis()))
                    return@execute
                }
                TaskRepository.saveRunning(ctx, taskId, intent.goal, userText, step.id)
                currentStepName = step.id
                TaskExecutionLogger.trackStep(taskId, step.id)
                emit(TaskEvent(taskId, TaskEventType.STEP_STARTED, "${step.id}:${step.kind}"))
                TaskExecutionLogger.log(taskId, "STEP ${step.id} [${step.kind}] — ${step.label}")

                when (step.kind) {
                    TaskStepKind.VERIFY_SETTINGS, TaskStepKind.VERIFY_RETURN -> {
                        // مهلة استقرار الشاشة قبل التحقق + retry (الإطلاق بياخد ~2.5s)
                        try { Thread.sleep(1500) } catch (_: Exception) {}
                        var observed = ObservedState("unknown")
                        var vr = VerifyResult(false, "pending", "pending")
                        var lastSource = "unknown"
                        var attempt = 0
                        while (attempt < 3) {
                            attempt++
                            try {
                                val obs = TaskObserver.observeWithSource(ctx)
                                observed = obs.state
                                lastSource = obs.source
                            } catch (e: Exception) {
                                TaskExecutionLogger.logFailure(taskId, "observe:${step.id}",
                                    "observer threw (attempt $attempt/3): ${e.message}", goal = intentGoalName, step = step.id, throwable = e)
                                lastSource = "observer-crash"
                            }
                            vr = TaskVerifier.verify(ctx, step, observed)
                            TaskExecutionLogger.log(taskId,
                                "OBSERVE #$attempt/3 via=$lastSource pkg=${observed.foregroundPackage} → VERIFY ${step.kind}: ${if (vr.pass) "PASS" else "FAIL"} (expected=${vr.expected} actual=${vr.actual})")
                            if (vr.pass) break
                            if (attempt < 3) {
                                try { Thread.sleep(1200) } catch (_: Exception) {}
                            }
                        }
                        TaskExecutionLogger.logVerify(taskId, step.id,
                            vr.pass, vr.expected, vr.actual)
                        if (vr.pass) {
                            emit(TaskEvent(taskId, TaskEventType.STEP_COMPLETED, "${step.id}:PASS via=$lastSource"))
                        } else {
                            emit(TaskEvent(taskId, TaskEventType.STEP_FAILED, "${step.id}:FAIL actual=${vr.actual} via=$lastSource"))
                            failedReason = if (vr.actual == "unknown") {
                                "verification failed at ${step.id}: expected [${vr.expected}] but saw [unknown] (3 attempts, via=$lastSource) — شغّل Axon Assistant من Accessibility (اقفلها وافتحها OFF/ON) أو امنح Usage Access من الإعدادات"
                            } else {
                                "verification failed at ${step.id}: expected [${vr.expected}] but saw [${vr.actual}] (3 attempts, via=$lastSource)"
                            }
                            break
                        }
                    }
                    TaskStepKind.OPEN_SETTINGS, TaskStepKind.BACK, TaskStepKind.FAIL_TEST, TaskStepKind.UI_BACK -> {
                        val action = if (step.kind == TaskStepKind.UI_BACK) BackAction() else TaskActionFactory.forStep(step.kind)
                        if (action == null) {
                            TaskExecutionLogger.logFailure(taskId, "action:${step.kind}",
                                "no action mapped for step kind", goal = intentGoalName, step = step.id)
                            emit(TaskEvent(taskId, TaskEventType.STEP_FAILED, "${step.id}:no-action"))
                            failedReason = "no action for ${step.kind}"
                            break
                        }
                        val res = try { action.execute(ctx) } catch (e: Exception) {
                            TaskExecutionLogger.logFailure(taskId, "action:${action.name}",
                                "action threw: ${e.message}", goal = intentGoalName, step = step.id, throwable = e)
                            ActionResult(false, e.message ?: "exception")
                        }
                        TaskExecutionLogger.logAction(taskId, action.name, res.success, res.detail)
                        if (res.success) {
                            emit(TaskEvent(taskId, TaskEventType.STEP_COMPLETED, "${step.id}:dispatched"))
                            // مهلة انتقال الشاشة قبل الخطوة التالية
                            try { Thread.sleep(1200) } catch (_: Exception) {}
                        } else {
                            emit(TaskEvent(taskId, TaskEventType.STEP_FAILED, "${step.id}:${res.detail.take(120)}"))
                            failedReason = "action ${action.name} failed: ${res.detail}"
                            break
                        }
                    }
                    // ── V2 steps ──
                    TaskStepKind.OPEN_APP -> {
                        val r = runOpenApp(step)
                        if (r == null) {
                            emit(TaskEvent(taskId, TaskEventType.STEP_COMPLETED, "${step.id}:dispatched"))
                        } else {
                            emit(TaskEvent(taskId, TaskEventType.STEP_FAILED, "${step.id}:$r".take(200)))
                            failedReason = r
                            break
                        }
                    }
                    TaskStepKind.UI_CLICK -> {
                        val r = runUiClick(step)
                        if (cancelFlag.get()) {
                            emit(TaskEvent(taskId, TaskEventType.TASK_CANCELLED, "at ${step.id}"))
                            finish(TaskRecord(taskId, intent.goal, userText, TaskState.CANCELLED,
                                currentStep = step.id, finishedAt = System.currentTimeMillis()))
                            return@execute
                        }
                        if (r != null) {
                            emit(TaskEvent(taskId, TaskEventType.STEP_FAILED, "${step.id}:$r".take(200)))
                            failedReason = r
                            break
                        }
                    }
                    TaskStepKind.UI_TYPE -> {
                        val r = runUiType(step)
                        if (cancelFlag.get()) {
                            emit(TaskEvent(taskId, TaskEventType.TASK_CANCELLED, "at ${step.id}"))
                            finish(TaskRecord(taskId, intent.goal, userText, TaskState.CANCELLED,
                                currentStep = step.id, finishedAt = System.currentTimeMillis()))
                            return@execute
                        }
                        if (r != null) {
                            emit(TaskEvent(taskId, TaskEventType.STEP_FAILED, "${step.id}:$r".take(200)))
                            failedReason = r
                            break
                        }
                    }
                    TaskStepKind.UI_WAIT -> {
                        val r = runUiWait(step)
                        if (cancelFlag.get() || r == "CANCELLED_SENTINEL") {
                            emit(TaskEvent(taskId, TaskEventType.TASK_CANCELLED, "at ${step.id}"))
                            finish(TaskRecord(taskId, intent.goal, userText, TaskState.CANCELLED,
                                currentStep = step.id, finishedAt = System.currentTimeMillis()))
                            return@execute
                        }
                        if (r != null) {
                            emit(TaskEvent(taskId, TaskEventType.STEP_FAILED, "${step.id}:$r".take(200)))
                            failedReason = r
                            break
                        }
                    }
                    TaskStepKind.UI_SCROLL -> {
                        val r = runUiScroll(step)
                        if (cancelFlag.get() || r == "CANCELLED_SENTINEL") {
                            emit(TaskEvent(taskId, TaskEventType.TASK_CANCELLED, "at ${step.id}"))
                            finish(TaskRecord(taskId, intent.goal, userText, TaskState.CANCELLED,
                                currentStep = step.id, finishedAt = System.currentTimeMillis()))
                            return@execute
                        }
                        if (r != null) {
                            emit(TaskEvent(taskId, TaskEventType.STEP_FAILED, "${step.id}:$r".take(200)))
                            failedReason = r
                            break
                        }
                    }
                    TaskStepKind.VERIFY_APP -> {
                        val r = runVerifyApp(step)
                        if (r != null) {
                            failedReason = r
                            break
                        }
                    }
                    TaskStepKind.VERIFY_TEXT -> {
                        val r = runVerifyText(step)
                        if (r != null) {
                            failedReason = r
                            break
                        }
                    }
                    TaskStepKind.VERIFY_CHANGED -> {
                        val r = runVerifyChanged(step)
                        if (r != null) {
                            failedReason = r
                            break
                        }
                    }
                }
            }

            // ── 4) النهاية ──
            if (failedReason == null) {
                if (intent.goal == TaskGoal.UI_FLOW) {
                    val locators = intent.flow.mapIndexed { i, flow ->
                        when (flow.op) { "click" -> learnedObserved["click${i + 1}"]; "type" -> learnedObserved["type${i + 1}"]; else -> null }
                    }
                    TaskLearning.learnSuccess(ctx, intent, userText, locators)
                    TaskExecutionLogger.log(taskId, "LEARNING stored verified workflow structure for ${intent.app}")
                }
                emit(TaskEvent(taskId, TaskEventType.TASK_COMPLETED, "goal=${intent.goal}"))
                TaskExecutionLogger.log(taskId, "GOAL ACHIEVED — ${intent.goal}")
                emit(TaskEvent(taskId, TaskEventType.TASK_COMPLETED, TaskVoiceReporter.completed()))
                finish(TaskRecord(taskId, intent.goal, userText, TaskState.COMPLETED,
                    result = "goal achieved", finishedAt = System.currentTimeMillis()))
            } else {
                TaskExecutionLogger.logFailure(taskId, "goal", failedReason!!, goal = intentGoalName)
                emit(TaskEvent(taskId, TaskEventType.TASK_FAILED, failedReason!!))
                finish(TaskRecord(taskId, intent.goal, userText, TaskState.FAILED,
                    error = failedReason, finishedAt = System.currentTimeMillis()))
            }
            } catch (e: Exception) {
                // أي كراش غير متوقع في حلقة الـ engine نفسها — لازم يبان في logcat بمكانه
                TaskExecutionLogger.logFailure(taskId, "engine-loop",
                    "unexpected crash: ${e.message}", goal = intentGoalName, step = currentStepName, throwable = e)
                try {
                    TaskRepository.pushHistory(TaskRecord(taskId,
                        runCatching { TaskGoal.valueOf(intentGoalName) }.getOrDefault(TaskGoal.UNKNOWN),
                        userText, TaskState.FAILED, currentStep = currentStepName.ifBlank { null },
                        error = "engine crash at ${currentStepName.ifBlank { "unknown" }}: ${e.message}",
                        finishedAt = System.currentTimeMillis()))
                } catch (_: Exception) {}
                try { TaskRepository.clearRunning(ctx) } catch (_: Exception) {}
                try { TaskForegroundService.stop(ctx) } catch (_: Exception) {}
                TaskExecutionLogger.clearTrack(taskId)
                currentTaskId.set(null)
                val msg = "engine crash at ${currentStepName.ifBlank { "unknown" }}: ${e.message}"
                mainHandler.post {
                    try { cb.onFinished(TaskRecord(taskId, TaskGoal.UNKNOWN, userText, TaskState.FAILED,
                        error = msg, finishedAt = System.currentTimeMillis())) } catch (_: Exception) {}
                    try { cb.onLogRefresh() } catch (_: Exception) {}
                }
            }
        }
    }
}
