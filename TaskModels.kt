// TaskModels.kt — Axon Task Engine V1: domain models (independent, لا يمس الموجود)
package com.example.app_abdelbaset

/** النية المستخرجة من كلام اليوزر (V1: ترجمة LLM لنية + fallback rule-based) */
enum class TaskGoal {
    // ── V1 ──
    OPEN_SETTINGS,
    OPEN_SETTINGS_AND_BACK,
    FAIL_TEST,
    // ── V2: عام — أي تسلسل UI actions ──
    OPEN_APP,         // فتح تطبيق فقط بلا تفاعل (standalone — يسد ثغرة "open X")
    UI_WAIT,          // انتظار مستقل لعنصر/نص يظهر (بلا فعل قبله)
    UI_FLOW,          // فتح تطبيق + تسلسل click/type مهما كان طوله (بديل UI_TAP/UI_TYPE/WHATSAPP_SEARCH/UI_MULTISTATE)
    UI_RECOVER,       // هدف مستحيل عمدًا لاختبار الـ recovery
    UNKNOWN
}

/** خطوة واحدة داخل UI_FLOW — كما يستخرجها الـ LLM من نية المستخدم (نية فقط، بلا تخطيط) */
data class UiFlowStep(
    val op: String,       // "click" | "type" | "scroll" | "back" | "wait"
    val target: String,   // وصف العنصر (يُمرر كما هو لـ TaskUiReasoner كـ goalDesc)
    val text: String = "", // لـ type: النص — لـ scroll: الاتجاه (up/down، default down)
    /** دليل نجاح قابل للملاحظة بعد الفعل. لا نستخدم "الشاشة اتغيرت" كبديل له. */
    val expected: String = ""
)

/** حالة الـ Task */
enum class TaskState {
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isFinal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

/** خطوة واحدة داخل الخطة — param/param2 حسب النوع (desc/app/text/expected) */
data class TaskStep(
    val id: String,
    val kind: TaskStepKind,
    val label: String,
    val param: String = "",
    val param2: String = "",
    val timeoutSec: Long = 15,
    /** post-condition اختياري خاص بالفعل، يثبته نفس attempt الذي نفذ الفعل. */
    val expected: String = "",
    /** locator تعلّمه workflow موثوق؛ يستخدم فقط كـ fast-path ثم evidence هو الحكم. */
    val learnedLocator: LearnedLocator? = null
)

data class LearnedLocator(
    val viewId: String = "",
    val role: String = "",
    val className: String = "",
    val confidence: Int = 0
)

enum class TaskStepKind {
    // V1
    OPEN_SETTINGS,
    VERIFY_SETTINGS,
    BACK,
    VERIFY_RETURN,
    FAIL_TEST,
    // V2
    OPEN_APP,        // param=app name
    VERIFY_APP,      // param=app name — fail fast if the app is not foreground
    UI_CLICK,        // param=target desc (snapshot+reason+tap)
    UI_TYPE,         // param=target desc, param2=text
    UI_SCROLL,       // param=target to find, param2=direction (up/down) — light swipes + presence
    UI_WAIT,         // param=target desc, timeoutSec
    UI_BACK,         // رجوع داخل flow مع تحقق من انتقال فعلي
    VERIFY_TEXT,     // param=expected text present
    VERIFY_CHANGED   // الشاشة اتغيرت مقارنة بما قبل الفعل
}

/** نتيجة تنفيذ action واحد — نجاح الإطلاق ≠ تحقق الهدف */
data class ActionResult(
    val success: Boolean,
    val detail: String = ""
)

/** الحالة المرصودة الحالية */
data class ObservedState(
    val foregroundPackage: String,
    val atMillis: Long = System.currentTimeMillis()
)

/** نتيجة الـ verification المستقلة */
data class VerifyResult(
    val pass: Boolean,
    val expected: String,
    val actual: String
)

/** دليل غير قابل للتباس لنتيجة action؛ يُسجّل من نفس pre/post observation ولا يعاد تخمينه لاحقًا. */
data class ActionProof(
    val stepId: String,
    val action: String,
    val target: String,
    val expected: String,
    val preSignature: String,
    val postSignature: String,
    val changed: Boolean,
    val semanticPass: Boolean?,
    val selectedElement: Int = -1
)

/** سجل Task واحد */
data class TaskRecord(
    val id: String,
    val goal: TaskGoal,
    val userText: String,
    val state: TaskState,
    val currentStep: String? = null,
    val result: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null
)
