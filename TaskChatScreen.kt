// TaskChatScreen.kt — Axon Task Engine V1: شات كتابة منفصل + timeline التنفيذ
package com.example.app_abdelbaset

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.app_abdelbaset.ui.theme.AccentAmber
import com.example.app_abdelbaset.ui.theme.App_abdelbasetTheme
import com.example.app_abdelbaset.ui.theme.BgPrimary
import com.example.app_abdelbaset.ui.theme.CardBg
import com.example.app_abdelbaset.ui.theme.CardBorder
import com.example.app_abdelbaset.ui.theme.NeonCyan
import com.example.app_abdelbaset.ui.theme.NeonGreen
import com.example.app_abdelbaset.ui.theme.TextMuted
import com.example.app_abdelbaset.ui.theme.TextPrimary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class TaskChatMsg(val text: String, val isUser: Boolean, val isSystem: Boolean = false)

@Composable
fun TaskChatScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<TaskChatMsg>() }
    val logLines = remember { mutableStateListOf<TaskExecutionLogger.LogLine>() }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(TaskEngine.isRunning()) }
    var activeTaskId by remember { mutableStateOf<String?>(null) }
    // حالة الـ Accessibility — تُفحص عند فتح الشاشة وبعد كل task
    var a11yOn by remember { mutableStateOf(true) }
    var a11yWasGranted by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val logState = rememberLazyListState()

    fun refreshA11y() {
        try {
            val on = TaskA11yGuard.isEnabledNow(appContext)
            a11yOn = on
            if (on) TaskA11yGuard.markGranted(appContext)
            a11yWasGranted = TaskA11yGuard.wasGrantedBefore(appContext)
        } catch (_: Exception) {}
    }

    fun refreshLog() {
        val id = activeTaskId
        val lines = if (id != null) TaskExecutionLogger.linesFor(id)
        else TaskExecutionLogger.recentLines(80)
        logLines.clear()
        logLines.addAll(lines.takeLast(80))
        scope.launch {
            try { if (logLines.isNotEmpty()) logState.animateScrollToItem(logLines.size - 1) } catch (_: Exception) {}
            try { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) } catch (_: Exception) {}
        }
    }

    fun addMsg(t: String, isUser: Boolean, isSystem: Boolean = false) {
        messages.add(TaskChatMsg(t, isUser, isSystem))
        scope.launch { try { listState.animateScrollToItem(messages.size - 1) } catch (_: Exception) {} }
    }

    // نسخ نص للـ clipboard + toast تأكيد — عشان تبعت اللوج بسهولة
    fun copyText(label: String, text: String) {
        try {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(appContext, "Copied ($label)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(appContext, "Copy failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyLog() {
        if (logLines.isEmpty()) {
            Toast.makeText(appContext, "No log to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val text = buildString {
            appendLine("Axon Task Engine V1 log (${logLines.size} lines, task=${activeTaskId ?: "recent"}):")
            logLines.forEach { l ->
                appendLine("[${fmt.format(Date(l.atMillis))}] [${l.level}] [${l.taskId}] ${l.text}")
            }
        }
        copyText("task-log", text)
    }

    // الـ edge glow يظهر بره صفحة Task فقط — جوه الصفحة الـ timeline كفاية (قرار المستخدم)
    DisposableEffect(Unit) {
        try { TaskEdgeGlowController.setInTaskScreen(true) } catch (_: Exception) {}
        onDispose { try { TaskEdgeGlowController.setInTaskScreen(false) } catch (_: Exception) {} }
    }

    // كشف task متعلقة من process مات (تسجيل فقط — متفق عليه)
    LaunchedEffect(Unit) {
        if (messages.isEmpty()) {
            addMsg("Task Engine V1 — اكتب: افتح الاعدادات", isUser = false, isSystem = true)
        }
        refreshA11y()
        try {
            TaskRepository.detectStaleRunning(appContext)?.let { note ->
                addMsg(note, isUser = false, isSystem = true)
            }
        } catch (_: Exception) {}
        // لو كان ممنوح قبل الـ build ومفصول دلوقتي → نبّه مرة واحدة
        try {
            if (TaskA11yGuard.needsReEnable(appContext)) {
                addMsg("الـ Accessibility اتقفلت بعد الـ build — دوس على بانر التفعيل فوق عشان ترجعها بضغطة.",
                    isUser = false, isSystem = true)
            }
        } catch (_: Exception) {}
        refreshLog()
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank() || running) return
        refreshA11y()
        if (!a11yOn) {
            Toast.makeText(appContext, "تنبيه: الـ Accessibility مطفية — الـ verify قد يفشل", Toast.LENGTH_SHORT).show()
        }
        input = ""
        addMsg(text, isUser = true)
        running = true
        TaskEngine.submit(appContext, text, TaskEngine.TaskCallbacks(
            onEvent = { e ->
                activeTaskId = e.taskId
                when (e.type) {
                    TaskEventType.TASK_STARTED -> addMsg(TaskVoiceReporter.started(), isUser = false)
                    TaskEventType.TASK_COMPLETED ->
                        if (e.detail == TaskVoiceReporter.completed()) addMsg(e.detail, isUser = false)
                    TaskEventType.TASK_FAILED ->
                        if (e.detail.isNotBlank() && !e.detail.startsWith("goal=") && !e.detail.startsWith("unsupported")) {
                            addMsg(e.detail, isUser = false)
                        }
                    else -> {}
                }
                refreshLog()
            },
            onLogRefresh = { refreshLog() },
            onFinished = { rec ->
                running = false
                refreshA11y()
                when (rec.state) {
                    TaskState.COMPLETED -> { /* completed msg جاءت عبر event */ }
                    TaskState.FAILED -> {
                        if (rec.error?.startsWith("unsupported") == true) addMsg(TaskVoiceReporter.unsupported(), isUser = false)
                        else addMsg(TaskVoiceReporter.failed(rec.error ?: ""), isUser = false)
                    }
                    TaskState.CANCELLED -> addMsg(TaskVoiceReporter.cancelled(), isUser = false)
                    else -> {}
                }
                refreshLog()
            }
        ))
    }

    App_abdelbasetTheme {
        Box(modifier = Modifier.fillMaxSize().background(BgPrimary)) {
            Column(modifier = Modifier.fillMaxSize().padding(bottom = 12.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(36.dp)
                            .clip(RoundedCornerShape(6.dp)).background(CardBg)
                            .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("TASK ENGINE · V1", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, letterSpacing = 2.sp)
                        Text("INDEPENDENT · TEXT ONLY", fontSize = 8.sp, color = TextMuted, letterSpacing = 2.sp)
                    }
                    val dot = if (running) NeonGreen else TextMuted
                    val label = if (running) "● RUNNING" else "○ IDLE"
                    Text(label, fontSize = 9.sp, color = dot, letterSpacing = 1.5.sp)
                    if (running) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(CardBg)
                                .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                                .clickable { TaskEngine.cancel(); addMsg("إلغاء…", isUser = false, isSystem = true) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = AccentAmber, modifier = Modifier.size(14.dp))
                        }
                    }
                }

                // ── V2: اختيار مزود الـ UI reasoning (Cohere/Gemini) — pref منفصل بإيدك ──
                var visionChoice by remember { mutableStateOf(TaskVisionProvider.getChoice(appContext)) }
                var keyCount by remember { mutableStateOf(TaskVisionKeys.count(appContext, visionChoice)) }
                var showKeysDialog by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("VISION:", fontSize = 8.sp, color = TextMuted, letterSpacing = 2.sp)
                    Spacer(Modifier.width(8.dp))
                    TaskVisionProvider.VisionChoice.values().forEach { c ->
                        val sel = visionChoice == c
                        Box(
                            modifier = Modifier.padding(end = 6.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (sel) NeonCyan.copy(0.15f) else CardBg)
                                .border(0.5.dp, if (sel) NeonCyan.copy(0.6f) else CardBorder, RoundedCornerShape(6.dp))
                                .clickable {
                                    TaskVisionProvider.setChoice(appContext, c)
                                    visionChoice = c
                                    keyCount = TaskVisionKeys.count(appContext, c)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(TaskVisionProvider.displayLabel(c), fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                color = if (sel) NeonCyan else TextMuted, letterSpacing = 1.sp)
                        }
                    }
                    // مفاتيح المزود المختار: K نشط/العدد + تعديل (rotation تلقائي عند 429)
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(CardBg)
                            .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                            .clickable { showKeysDialog = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("KEYS ($keyCount)", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            color = TextMuted, letterSpacing = 1.sp)
                    }
                }
                // حوار المفاتيح: للمزودات السحابية لصق مفاتيح؛ LOCAL يعمل على الجهاز بلا مفاتيح
                if (showKeysDialog) {
                    if (visionChoice == TaskVisionProvider.VisionChoice.LOCAL) {
                        androidx.compose.ui.window.Dialog(onDismissRequest = { showKeysDialog = false }) {
                            Box(
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp)).background(CardBg)
                                    .border(0.5.dp, CardBorder, RoundedCornerShape(8.dp))
                                    .padding(16.dp)
                            ) {
                                Column {
                                    Text("ON-DEVICE — GEMMA",
                                        fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Spacer(Modifier.height(4.dp))
                                    Text("يعمل على الجهاز مباشرة — لا مفاتيح ولا حصة سحابية. يحتاج ملف موديل محمل (شاشة تحميل الموديلات في الإعدادات).",
                                        fontSize = 10.sp, color = TextMuted, lineHeight = 14.sp)
                                    Spacer(Modifier.height(10.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        Box(
                                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                                .background(NeonGreen.copy(0.15f))
                                                .border(0.5.dp, NeonGreen.copy(0.6f), RoundedCornerShape(6.dp))
                                                .clickable { showKeysDialog = false }
                                                .padding(horizontal = 14.dp, vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("تمام", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = NeonGreen)
                                        }
                                    }
                                }
                            }
                        }
                    } else
                    {
                    var draft by remember(visionChoice) {
                        mutableStateOf(
                            try {
                                // نعرض المخزن لو موجود، وإلا المفتاح المفرد الحالي
                                val prefs = appContext.getSharedPreferences("axon_prefs", android.content.Context.MODE_PRIVATE)
                                val stored = prefs.getString(
                                    if (visionChoice == TaskVisionProvider.VisionChoice.GEMINI) "vision_keys_GEMINI" else "vision_keys_COHERE", "")
                                stored?.takeIf { it.isNotBlank() } ?: when (visionChoice) {
                                    TaskVisionProvider.VisionChoice.GEMINI ->
                                        prefs.getString("gemini_api_key", "") ?: ""
                                    else -> prefs.getString("cohere_api_key", "") ?: ""
                                }
                            } catch (_: Exception) { "" }
                        )
                    }
                    androidx.compose.ui.window.Dialog(onDismissRequest = { showKeysDialog = false }) {
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp)).background(CardBg)
                                .border(0.5.dp, CardBorder, RoundedCornerShape(8.dp))
                                .padding(16.dp)
                        ) {
                            Column {
                                Text("API KEYS — ${TaskVisionProvider.displayLabel(visionChoice)}",
                                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Spacer(Modifier.height(4.dp))
                                Text("الصق مفاتيح مفصولة بفاصلة — الأول أساسي، والباقي احتياط عند نفاد الحصة (rotation تلقائي).",
                                    fontSize = 9.sp, color = TextMuted, lineHeight = 13.sp)
                                Spacer(Modifier.height(8.dp))
                                TextField(
                                    value = draft,
                                    onValueChange = { draft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("key1,key2,...", fontSize = 10.sp, color = TextMuted) },
                                    singleLine = false,
                                    maxLines = 4,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = BgPrimary, unfocusedContainerColor = BgPrimary,
                                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                                    )
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    Box(
                                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                            .clickable { showKeysDialog = false }
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("إلغاء", fontSize = 10.sp, color = TextMuted)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                            .background(NeonGreen.copy(0.15f))
                                            .border(0.5.dp, NeonGreen.copy(0.6f), RoundedCornerShape(6.dp))
                                            .clickable {
                                                val n = TaskVisionKeys.setKeysCsv(appContext, visionChoice, draft)
                                                keyCount = n
                                                Toast.makeText(appContext,
                                                    if (n > 0) "Saved $n key(s)" else "Cleared — سيُستخدم مفتاح الإعدادات",
                                                    Toast.LENGTH_SHORT).show()
                                                showKeysDialog = false
                                            }
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("حفظ", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = NeonGreen)
                                    }
                                }
                            }
                        }
                    }
                    } // else (cloud providers keys dialog)
                }
                Spacer(Modifier.height(6.dp))

                // Chat messages
                // بانر حالة الـ Accessibility — يظهر فقط لو مفصولة (خصوصًا بعد rebuild)
                if (!a11yOn) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(AccentAmber.copy(0.12f))
                            .border(0.5.dp, AccentAmber.copy(0.6f), RoundedCornerShape(6.dp))
                            .clickable {
                                TaskA11yGuard.openSystemSettings(appContext)
                                Toast.makeText(appContext, "فعّل Axon Assistant ثم ارجع", Toast.LENGTH_LONG).show()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Column {
                            Text(
                                if (a11yWasGranted) "⚠ الـ Accessibility اتقفلت بعد الـ build — دوس للتفعيل"
                                else "⚠ الـ Accessibility مطفية — دوس للتفعيل (مطلوبة للـ verification)",
                                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentAmber)
                            Spacer(Modifier.height(2.dp))
                            Text("دوس هنا لفتح الإعدادات • بدونها أي verify هيرجع unknown",
                                fontSize = 9.sp, color = TextMuted)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(0.45f).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(messages) { index, m ->
                        val bg = when {
                            m.isUser -> NeonCyan.copy(0.12f)
                            m.isSystem -> CardBg
                            else -> NeonGreen.copy(0.08f)
                        }
                        val border = when {
                            m.isUser -> NeonCyan.copy(0.4f)
                            else -> CardBorder
                        }
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp)).background(bg)
                                .border(0.5.dp, border, RoundedCornerShape(6.dp))
                                .clickable { copyText("task-msg", m.text) }
                                .padding(10.dp)
                        ) {
                            Text(m.text, fontSize = 12.sp, color = TextPrimary, lineHeight = 17.sp)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Execution timeline (logging) — دوس على أي سطر ينسخه، أو انسخ الكل بالزرار
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("EXECUTION LOG (TAP LINE = COPY)", fontSize = 8.sp, color = TextMuted, letterSpacing = 2.sp,
                        modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(CardBg)
                            .border(0.5.dp, NeonCyan.copy(0.5f), RoundedCornerShape(6.dp))
                            .clickable { copyLog() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("COPY LOG", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = NeonCyan, letterSpacing = 1.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                LazyColumn(
                    state = logState,
                    modifier = Modifier.fillMaxWidth().weight(0.45f).padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(6.dp)).background(CardBg)
                        .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp)).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (logLines.isEmpty()) {
                        item { Text("No execution yet — ابعت أول task.", fontSize = 10.sp, color = TextMuted) }
                    } else {
                        itemsIndexed(logLines) { index, l ->
                            val fmt = remember(l.atMillis) {
                                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(l.atMillis))
                            }
                            val color = when (l.level) {
                                TaskExecutionLogger.Level.ERROR -> AccentAmber
                                TaskExecutionLogger.Level.WARN -> NeonCyan
                                else -> TextMuted
                            }
                            Text("[$fmt] ${l.text.take(220)}", fontSize = 9.sp, color = color, lineHeight = 13.sp,
                                modifier = Modifier.clickable {
                                    copyText("log-line", "[${fmt}] [${l.level}] [${l.taskId}] ${l.text}")
                                })
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Input
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("اكتب task… (افتح الاعدادات)", fontSize = 11.sp, color = TextMuted) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = CardBg, unfocusedContainerColor = CardBg,
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.size(48.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (running || input.isBlank()) CardBg else NeonGreen.copy(0.15f))
                            .border(0.5.dp, if (running || input.isBlank()) CardBorder else NeonGreen.copy(0.6f), RoundedCornerShape(6.dp))
                            .clickable(enabled = !running && input.isNotBlank()) { send() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("▶", fontSize = 16.sp, color = if (running || input.isBlank()) TextMuted else NeonGreen)
                    }
                }
            }
        }
    }
}
