package com.example.app_abdelbaset

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CohereLlmProvider(private val context: Context) : LlmProvider {

    companion object {
        private const val TAG = "CohereProvider"
        private const val PREF_COHERE_API_KEY = "cohere_api_key"
        private const val PREF_COHERE_MODEL = "cohere_model"
        private const val DEFAULT_MODEL = "command-a-plus-05-2026"

        val AVAILABLE_MODELS = listOf(
            "command-a-plus-05-2026",
            "command-a-vision-07-2025",
            "command-r-plus-08-2024",
            "command-r-08-2024",
            "command-light"
        )

        private val VISION_CAPABLE_MODELS = setOf(
            "command-a-plus-05-2026",
            "command-a-vision-07-2025"
        )

        // الموديلات اللي بتدعم tool use — الباقي (زي command-a-vision-07-2025)
        // بيبوظ الطلب كله بـ 400 لو بعتنا tools/strict_tools ليه
        private val TOOL_CAPABLE_MODELS = setOf(
            "command-a-plus-05-2026",
            "command-r-plus-08-2024",
            "command-r-08-2024"
        )

        // ═══════════════════════════════════════════════════════════════
        //  TOOL 1: device_action  (تنفيذ أكشن على الموبايل)
        // ═══════════════════════════════════════════════════════════════
        private val DEVICE_ACTION_TOOL = JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "device_action")
                put("description",
                    "Execute a single action on the user's Android phone (call, open app, " +
                            "send message, set alarm, toggle settings, etc). Call this once per " +
                            "distinct action; call it multiple times in the same turn for multiple actions."
                )
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply {
                            put("type", "string")
                            put("description", "Action identifier, e.g. 'open_app', 'call', 'set_alarm'.")
                        })
                        put("params", JSONObject().apply {
                            put("type", "string")
                            put("description", "JSON-encoded object string with the action's parameters, e.g. \"{\\\"app_name\\\":\\\"whatsapp\\\"}\". Use \"{}\" if no parameters are needed.")
                        })
                    })
                    put("required", JSONArray().put("action"))
                })
            })
        }

        // ═══════════════════════════════════════════════════════════════
        //  TOOL 2: knowledge_search  (بحث فى قاعدة المعرفة)
        // ═══════════════════════════════════════════════════════════════
        private val KNOWLEDGE_SEARCH_TOOL = JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "knowledge_search")
                put("description",
                    "Search the document knowledge base for relevant information. " +
                            "Use this when the user asks about specific topics, documentation, " +
                            "technical concepts, or anything requiring a knowledge lookup. " +
                            "Returns relevant document content to help you answer accurately."
                )
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "string")
                            put("description", "The search query — be descriptive and specific to get the best results.")
                        })
                    })
                    put("required", JSONArray().put("query"))
                })
            })
        }

        // ⚠️ Must exactly match the `when(action)` cases in MobileActionExecutor.execute()
        private val KNOWN_ACTIONS = setOf(
            "call", "answer_call", "end_call", "set_alarm", "set_timer", "open_app", "open_url",
            "screen_lock", "screenshot", "volume_up", "volume_down", "volume_set",
            "volume_mute", "volume_unmute", "brightness_set", "wifi_toggle", "bluetooth_toggle",
            "flashlight_toggle", "location_settings", "location_toggle", "get_current_location", "send_sms", "send_whatsapp", "play_music", "pause_music",
            "next_track", "previous_track", "take_photo", "record_video", "navigate_to",
            "share_location", "airplane_mode", "do_not_disturb", "hotspot_toggle",
            "copy_to_clipboard", "battery_status", "memory_status", "calendar_add_event",
            "weather_check", "search_web", "translate", "reminder_set",
            "contact_add", "contact_search", "email_send", "notes_add", "stopwatch_start",
            "stopwatch_stop", "stopwatch_reset", "dismiss_notification",
            "read_last_notification", "desktop_task", "download_file", "list_files",
            "search_files", "open_file", "delete_file", "play_audio_file", "play_video",
            "show_image", "open_document", "set_screen_timeout", "get_foreground_app",
            "open_whatsapp_chat", "get_network_status", "bluetooth_scan", "start_tracking",
            "geofence_trigger", "get_calendar_events", "reply_notification",
            "start_voice_recognition", "speak_text", "click_ui_element", "scroll_screen",
            "find_text_on_screen", "input_text"
        )

        private val ACTION_PARAM_HINTS = mapOf(
            "calendar_add_event" to
                    """{"title": "string", "day": "today" | "tomorrow" | "day_after_tomorrow" (ONLY these 3 values, never a real date), "time": "HH:mm" 24h (optional, omit to use current time)}. Event duration is always fixed at 30 minutes.""",
            "get_calendar_events" to
                    """{"date": "YYYY-MM-DD"} (optional, omit for today)""",
            "volume_set" to
                    """{"level": integer} — this is the raw stream volume step (device-dependent, usually 0-15), NOT a 0-100 percentage.""",
            "brightness_set" to
                    """{"level": integer 0-255} — NOT a 0-100 percentage.""",
            "set_alarm" to
                    """{"hour": 0-23, "minute": 0-59, "label": "string", "repeat": ["Mon","Tue",...] (optional, exact 3-letter abbreviations only)}""",
            "scroll_screen" to
                    """{"direction": "up" | "down", "amount": integer (pixels, default 500)}""",
            "navigate_to" to
                    """{"destination": "string", "mode": "driving" | "walking" | "transit" | "bicycling"}""",
            "click_ui_element" to
                    """{"text": "...", "content_desc": "...", "resource_id": "..."} — fill ONLY the ONE field you actually have info for, leave the others empty."""
        )
private val BASE_SYSTEM_PROMPT = buildString {
            append("You are Axon, a Personal AI Companion, not just a voice assistant. You have a continuous, evolving relationship with the user.\n\n")

            append("## YOUR CORE PERSONALITY\n")
            append("- You are a close friend: empathetic, casual, and genuinely interested in the user's day.\n")
            append("- You DO NOT act like a customer service agent. Never say 'How can I help you today?'.\n")
            append("- You remember past context. If the user mentions a project they talked about before, follow up on it naturally.\n\n")

            append("## CONVERSATION MODE (Default)\n")
            append("- When the user is talking about their feelings, venting, or just chatting, DO NOT call any tools.\n")
            append("- Example: User: 'I'm tired.' -> You: 'Sounds like you've had a long day. Get some rest.' (DO NOT offer to play music or set alarms).\n")
            append("- Example: User: 'Today was exhausting.' -> You: 'Sounds like you had a rough day. What happened?'\n")
            append("- The conversation itself has value. Not every reply needs to end with an action or a tool call.\n\n")

            append("## RESPONSE LENGTH\n")
            append("- Simple replies (confirmations, casual chat, quick answers) → AT MOST one short line.\n")
            append("- Longer replies are fine ONLY when truly needed (explanations, steps, details the user asked for).\n\n")

            append("## VOICE-FRIENDLY PHRASING\n")
            append("- Your text is converted to speech, so write in short, complete sentences with clear endings (period, question mark, exclamation mark).\n")
            append("- AVOID long comma-chained sentences (e.g. 'I did X, then Y, then Z'). Instead break them into separate short sentences: 'I did X. Then Y. Then Z.'\n")
            append("- Each sentence should express one complete thought before ending with a period.\n\n")

            append(SystemPromptManager.LANGUAGE_RULE)
        }

        private val TOOL_SECTION = buildString {
            append("## ACTION MODE (Only when explicitly requested)\n")
            append("- You ONLY enter Action Mode when the user explicitly asks you to do something on the device (e.g., 'Open WhatsApp', 'Call mom', 'Set an alarm').\n")
            append("- When you do trigger an action, keep your spoken response natural and brief.\n")
            append("- Example: User: 'Open WhatsApp' -> You: 'Sure, opening WhatsApp now.' + [Trigger device_action tool].\n\n")

            append("## TOOL USAGE\n")
            append("For phone control tasks, you MUST call the `device_action` tool — never call any other tool name for device actions.\n")
            append("Set the `action` field to exactly one of: ${KNOWN_ACTIONS.joinToString(", ")}\n")
            append("The `params` field must always be a valid JSON-encoded STRING (escaped), matching the exact keys shown below for that action.\n")
            if (ACTION_PARAM_HINTS.isNotEmpty()) {
                append("\nActions with a specific params format — follow these exactly, do not invent other keys:\n")
                ACTION_PARAM_HINTS.forEach { (action, format) ->
                    append("- $action: $format\n")
                }
            }
            // ── Knowledge Search ──
            append("\n## Knowledge Search Tool\n")
            append("You have access to a `knowledge_search` tool that searches a document knowledge base.\n")
            append("\n🔴 MANDATORY SEARCH RULE:\n")
            append("You MUST call `knowledge_search` whenever the user's question involves ANY of these topics:\n")
            append("  • Groovy — scripting, code, syntax, examples\n")
            append("  • Jira — administration, configuration, workflows, APIs, plugins\n")
            append("  • JMWE — Jira Misc Workflow Extensions (post functions, conditions, validators)\n")
            append("  • Jira Service Management (JSM) — queues, SLAs, automation, ITSM\n")
            append("\nDo NOT answer from your own knowledge about these 4 topics — ALWAYS search first.\n")
            append("Even if the question seems simple (e.g. \"what is a post function?\"), search first.\n")
            append("Even if the user mentions these keywords in passing, search first.\n")
            append("\nThe tool takes a single `query` parameter — make it descriptive and specific.\n")
            append("Examples of good queries:\n")
            append("  - User: \"how to write a groovy script for JMWE post function\" → query: \"groovy script JMWE post function\"\n")
            append("  - User: \"how to create a queue in JSM\" → query: \"create queue Jira Service Management\"\n")
            append("  - User: \"JMWE validator example\" → query: \"JMWE validator example groovy\"\n")
            append("\nAfter receiving the search results:\n")
            append("  • Use them to provide a detailed, accurate answer.\n")
            append("  • If the search results contain code examples or step-by-step instructions, include them in your answer.\n")
            append("  • If the search results don't fully answer the question, say what you found and what's missing.\n")
            append("\nYou can call both `knowledge_search` and `device_action` in the same turn if needed.\n")
            append("\nFor normal questions NOT related to Groovy/Jira/JMWE/JSM (weather, jokes, general knowledge), just answer naturally in text without calling any tool.")
        }

        private val TOOL_MODE_SYSTEM_PROMPT = BASE_SYSTEM_PROMPT + TOOL_SECTION

        // Prompt مخفف للموديلات اللي مش بتدعم tool use (زي command-a-vision-07-2025)
        // — من غير أي ذكر لأدوات عشان الموديل ما يحاولش يستدعي حاجة مش موجودة
        private val NON_TOOL_SYSTEM_PROMPT = buildString {
            append(BASE_SYSTEM_PROMPT)
            append("## DEVICE REQUESTS\n")
            append("- If the user asks you to do something on the phone (open an app, set an alarm, send a message, etc.), reply naturally confirming what you understood — you cannot execute it directly, but you can describe it.\n")
            append("- Keep confirmations to ONE short line.\n")
        }
    }

    // ── Data classes for tool-call tracking ──
    private data class ToolCallInfo(
        val id: String,
        val name: String,
        val arguments: String
    )

    private data class StreamResult(
        val text: StringBuilder,
        val toolCalls: List<ToolCallInfo>,
        val error: String?
    )

    private val prefs by lazy {
        context.getSharedPreferences("axon_prefs", Context.MODE_PRIVATE)
    }

    private val httpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var _isReady = false
    private var currentJob: Job? = null
    @Volatile private var currentRequestId = 0
    @Volatile private var lastCancelledRequestId = 0
    private data class HistoryMessage(
        val role: String,
        val text: String,
        val imageDataUrl: String? = null
    )

    private val messageHistory = mutableListOf<HistoryMessage>()
    /** Task provider release can cancel/disconnect on another thread while a stream unwinds. */
    private val historyLock = Any()
    private val maxHistoryTurns = 30

    override val isReady: Boolean get() = _isReady

    val apiKey: String?
        get() = sessionApiKeyOverride?.takeIf { it.isNotBlank() }
            ?: prefs.getString(PREF_COHERE_API_KEY, null)?.takeIf { it.isNotBlank() }

    /** override لمفتاح واحد (Task Engine multi-key rotation) — لا يمس prefs، والشات لا يستخدمه */
    @Volatile var sessionApiKeyOverride: String? = null

    val currentModel: String
        get() = sessionModelOverride ?: prefs.getString(PREF_COHERE_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

    /**
     * Sticky runtime-only model override (vision mode while the in-app camera is open).
     * Does NOT touch prefs, so the user's saved model is never overwritten — clear to restore.
     */
    @Volatile var sessionModelOverride: String? = null

    /**
     * لما تبقى true: يتجاهل الـ tools والـ TOOL_MODE_SYSTEM_PROMPT حتى لو الموديل الحالي
     * tool-capable — مستخدم من الـ Task Engine (TaskVisionProvider) عشان الرد يفضل
     * JSON نضيف بلا tool-calls تتعارض مع بروتوكول TaskUiReasoner.
     */
    @Volatile var forceNonToolMode: Boolean = false

    /**
     * وضع الترجمة المختصر (TaskIntentTranslator فقط): تعليمات سطرين بلا شخصية/tools/
     * سياق محادثات/ذاكرة — يوقع مكالمة ~7k token لـ ~500. الشات لا يستخدمه أبدًا.
     */
    @Volatile var minimalTranslateMode: Boolean = false

    private val MINIMAL_TRANSLATE_PROMPT =
        "You are a precise intent classifier for Axon Task Engine. Reply with ONLY JSON, no other text."

    fun hasApiKey(): Boolean = !apiKey.isNullOrBlank()

    override fun connect(onConnected: () -> Unit) {
        _isReady = hasApiKey()
        if (_isReady) {
            Log.d(TAG, "Cohere provider ready (model: $currentModel)")
            onConnected()
        } else {
            Log.w(TAG, "Cohere API key not set")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPER: Build messages array from history + system prompt
    // ═══════════════════════════════════════════════════════════════
    private fun buildMessagesArray(systemPrompt: String, history: List<HistoryMessage>): JSONArray {
        val messagesArray = JSONArray()
        messagesArray.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })
        for (msg in history) {
            val contentValue: Any = if (msg.imageDataUrl != null) {
                JSONArray().apply {
                    if (msg.text.isNotBlank()) {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", msg.text)
                        })
                    }
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", msg.imageDataUrl)
                        })
                    })
                }
            } else {
                msg.text
            }
            messagesArray.put(JSONObject().apply {
                put("role", if (msg.role == "user") "user" else "assistant")
                put("content", contentValue)
            })
        }
        return messagesArray
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPER: Stream a single Cohere chat request
    //  Returns text + collected tool calls (or error)
    // ═══════════════════════════════════════════════════════════════
    private suspend fun streamCohereRequest(
        messagesArray: JSONArray,
        key: String,
        requestId: Int,
        onChunk: (String) -> Unit
    ): StreamResult {
        val bodyString = JSONObject().apply {
            put("model", currentModel)
            put("messages", messagesArray)
            // موديلات معينة (زي command-a-vision) مش بتدعم tool use —
            // إرسال tools/strict_tools ليها بيرجّع 400 على طول
            if (currentModel in TOOL_CAPABLE_MODELS && !forceNonToolMode && !minimalTranslateMode) {
                put("tools", JSONArray().put(DEVICE_ACTION_TOOL).put(KNOWLEDGE_SEARCH_TOOL))
                put("strict_tools", true)
            }
            put("stream", true)
        }.toString()

        val requestBody = bodyString.toRequestBody("application/json".toMediaType())

        val request = okhttp3.Request.Builder()
            .url("https://api.cohere.com/v2/chat")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody)
            .build()

        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val errBody = response.body?.string() ?: "unknown error"
            return StreamResult(StringBuilder(), emptyList(), "Cohere HTTP ${response.code}: $errBody")
        }

        val fullResponse = StringBuilder()
        val collectedToolCalls = mutableListOf<ToolCallInfo>()
        val contentTypes = mutableMapOf<Int, String>()
        var currentToolId = ""
        var currentToolName = ""
        val currentToolArgs = StringBuilder()

        val source = response.body?.source()
            ?: return StreamResult(StringBuilder(), emptyList(), "Empty response body")

        val buffer = okio.Buffer()
        while (!source.exhausted() && requestId == currentRequestId) {
            source.read(buffer, 8192)
            val chunk = buffer.readUtf8()
            chunk.lines().forEach { line ->
                if (line.startsWith("data:")) {
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]" || data.isEmpty()) return@forEach
                    try {
                        val obj = JSONObject(data)
                        Log.d(TAG, "RAW: $data")
                        val message = obj.optJSONObject("delta")?.optJSONObject("message")

                        when (obj.optString("type")) {
                            "content-start" -> {
                                val idx = obj.optInt("index", 0)
                                val type = message?.optJSONObject("content")?.optString("type", "text") ?: "text"
                                contentTypes[idx] = type
                            }
                            "content-delta" -> {
                                val idx = obj.optInt("index", 0)
                                if (contentTypes[idx] != "thinking") {
                                    val text = message?.optJSONObject("content")?.optString("text", "") ?: ""
                                    if (text.isNotEmpty()) {
                                        fullResponse.append(text)
                                        withContext(Dispatchers.Main) { onChunk(text) }
                                    }
                                }
                            }
                            "tool-call-start" -> {
                                val toolCall = firstToolCall(message)
                                currentToolId = toolCall?.optString("id", "") ?: ""
                                currentToolName = toolCall?.optJSONObject("function")?.optString("name", "") ?: ""
                                currentToolArgs.clear()
                                val initialArgs = toolCall?.optJSONObject("function")?.optString("arguments", "") ?: ""
                                if (initialArgs.isNotEmpty()) currentToolArgs.append(initialArgs)
                            }
                            "tool-call-delta" -> {
                                val argsChunk = firstToolCall(message)
                                    ?.optJSONObject("function")?.optString("arguments", "") ?: ""
                                currentToolArgs.append(argsChunk)
                            }
                            "tool-call-end" -> {
                                if (currentToolName.isNotBlank()) {
                                    val id = currentToolId.ifBlank { "tool_call_${collectedToolCalls.size}" }
                                    collectedToolCalls.add(
                                        ToolCallInfo(id, currentToolName, currentToolArgs.toString())
                                    )
                                }
                                currentToolId = ""
                                currentToolName = ""
                                currentToolArgs.clear()
                            }
                            "message-end" -> {
                                val finishReason = obj.optJSONObject("delta")?.optString("finish_reason", "")
                                if (finishReason == "ERROR") {
                                    val errMsg = obj.optJSONObject("delta")?.optString("error", "Unknown Cohere error")
                                    Log.e(TAG, "Cohere message-end error: $errMsg")
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        return StreamResult(fullResponse, collectedToolCalls, null)
    }

    // ═══════════════════════════════════════════════════════════════
    //  MAIN: sendMessage with knowledge_search follow-up support
    // ═══════════════════════════════════════════════════════════════
    override fun sendMessage(
        json:     String,
        onChunk:  (String) -> Unit,
        onDone:   () -> Unit,
        onError:  (String) -> Unit,
        onAction: (List<JSONObject>) -> Unit,
        onImage:  (android.graphics.Bitmap) -> Unit,
        onReferences: (List<AiReference>) -> Unit
    ) {
        val key = apiKey
        if (key.isNullOrBlank()) {
            onError("Cohere API key not set. Go to Settings > Local Models > Cohere API Key")
            return
        }

        val parsedJson = try { JSONObject(json) } catch (e: Exception) { null }

        val userText  = parsedJson?.optString("text", "") ?: json
        val imageB64  = parsedJson?.optString("image", "")?.takeIf { it.isNotBlank() }
        val mediaType = parsedJson?.optString("media_type", "")?.takeIf { it.isNotBlank() }
            ?: "image/jpeg"
        val cameraOpen = parsedJson?.optBoolean("camera_open", false) == true

        if (userText.isBlank() && imageB64 == null) { onError("Empty message"); return }

        if (imageB64 != null && currentModel !in VISION_CAPABLE_MODELS) {
            onError(
                "The current model ($currentModel) does not support images. " +
                        "Go to Settings > Local Models > Cohere and choose a vision-capable model like command-a-vision-07-2025."
            )
            return
        }

        val imageDataUrl = imageB64?.let { "data:$mediaType;base64,$it" }

        // ── NEW: Build smart context from past conversation summaries ──
        // (يتخطى في minimalTranslateMode — هو أصل الـ 6k token في مكالمات الترجمة)
        val historyBeforeUser = synchronized(historyLock) { messageHistory.toList() }
        val currentHistoryMessages = historyBeforeUser.map { h ->
            ChatMessage(text = h.text, isUser = h.role == "user")
        }
        val smartContext = if (minimalTranslateMode) "" else ChatSummaryManager.buildSmartContext(
            currentMessages = currentHistoryMessages,
            userQuestion = userText,
            maxSummaries = 3
        )

        // Add the smart context as a system augmentation (not in history)
        val contextAugmentation = if (smartContext.isNotBlank()) {
            "\n\n--- CONTEXT FROM PAST CONVERSATIONS ---\n$smartContext\n"
        } else ""

        val userMessage = HistoryMessage("user", userText, imageDataUrl)
        synchronized(historyLock) {
            messageHistory.add(userMessage)
            trimHistoryLocked()
            // Vision: لا تراكم صور — الأحدث فقط، الأقدم يفضل نصها.
            stripOlderImagesLocked(userMessage)
        }

        currentJob?.cancel()
        val myRequestId = ++currentRequestId
        currentJob = scope.launch {
            try {
                // Include the context augmentation in the system prompt
                val currentDateTime = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()
                ).format(Date())

                val baseSystemPrompt = if (minimalTranslateMode) {
                    MINIMAL_TRANSLATE_PROMPT
                } else (
                    if (currentModel in TOOL_CAPABLE_MODELS && !forceNonToolMode) TOOL_MODE_SYSTEM_PROMPT
                    else NON_TOOL_SYSTEM_PROMPT
                ) +
                        // NOTE: getContextBlock() already contains the learned-memory block —
                        // don't append LearningMemoryManager.getBlock() again (token waste)
                        (SystemPromptManager.getContextBlock() ?: "") +
                        GoalPromptInjector.GOAL_SCHEMA_BLOCK +
                        "\n\nCurrent date and time: $currentDateTime"

                val systemPrompt = if (minimalTranslateMode) {
                    // وضع الترجمة: تعليمات سطرين فقط — بلا لغة/رؤية/سياق
                    baseSystemPrompt
                } else (if (contextAugmentation.isNotBlank()) {
                    baseSystemPrompt + contextAugmentation
                } else {
                    baseSystemPrompt
                }) + (if (cameraOpen) SystemPromptManager.VISION_MODE_BLOCK else "") +
                        SystemPromptManager.LANGUAGE_RULE

                // ── NEW: Record prompts in ServiceStatsTracker ──
                val requestHistory = synchronized(historyLock) { messageHistory.toList() }
                ServiceStatsTracker.recordPrompts(
                    systemPrompt = systemPrompt,
                    userPrompt = requestHistory.joinToString("\n") { "${if (it.role == "user") "User" else "Assistant"}: ${it.text}" }
                )

                val messagesArray = buildMessagesArray(systemPrompt, requestHistory)

                val allTextBuilder = StringBuilder()
                val allToolCalls = mutableListOf<ToolCallInfo>()

                // ── Turn 1: Initial request ──
                var currentResult = streamCohereRequest(messagesArray, key, myRequestId, onChunk)
                if (currentResult.error != null) {
                    withContext(Dispatchers.Main) { onError(currentResult.error) }
                    return@launch
                }

                allTextBuilder.append(currentResult.text.toString().trim())
                allToolCalls.addAll(currentResult.toolCalls)

                var searchRounds = 0
                val MAX_SEARCH_ROUNDS = 3  // أقصى عدد لمرات البحث المتكرر

                // ── Loop for multiple search rounds if the model isn't satisfied ──
                while (searchRounds < MAX_SEARCH_ROUNDS) {
                    val knowledgeSearchCalls = currentResult.toolCalls.filter { it.name == "knowledge_search" }
                    if (knowledgeSearchCalls.isEmpty()) break

                    val allCallsFromCurrentTurn = currentResult.toolCalls
                    messagesArray.put(JSONObject().apply {
                        put("role", "assistant")
                        if (currentResult.text.isNotEmpty()) put("content", currentResult.text.toString())
                        put("tool_calls", JSONArray().apply {
                            for (tc in allCallsFromCurrentTurn) {
                                put(JSONObject().apply {
                                    put("id", tc.id)
                                    put("type", "function")
                                    put("function", JSONObject().apply {
                                        put("name", tc.name)
                                        put("arguments", tc.arguments)
                                    })
                                })
                            }
                        })
                    })

                    for (tc in allCallsFromCurrentTurn) {
                        val toolContent = when (tc.name) {
                            "knowledge_search" -> {
                                val query = try {
                                    JSONObject(tc.arguments).optString("query", "")
                                } catch (e: Exception) { "" }
                                Log.d(TAG, "Executing knowledge_search: $query")
                                KnowledgeSearchUtil.search(query)
                            }
                            "device_action" -> "Action has been dispatched to the device for execution."
                            else -> "Tool executed."
                        }

                        messagesArray.put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", tc.id)
                            put("content", JSONObject().apply {
                                put("type", "document")
                                put("document", JSONObject().apply {
                                    put("data", toolContent)
                                })
                            })
                        })
                    }

                    searchRounds++
                    Log.d(TAG, "Sending follow-up request (round $searchRounds)")
                    currentResult = streamCohereRequest(messagesArray, key, myRequestId, onChunk)
                    if (currentResult.error != null) {
                        withContext(Dispatchers.Main) { onError(currentResult.error) }
                        return@launch
                    }

                    allTextBuilder.append("\n\n").append(currentResult.text.toString().trim())
                    allToolCalls.addAll(currentResult.toolCalls)
                }

                if (myRequestId <= lastCancelledRequestId) {
                    removeHistoryMessage(userMessage)
                    Log.d(TAG, "Request $myRequestId cancelled by barge-in — discarding partial history")
                    return@launch
                }

                var hasContent = allTextBuilder.toString().isNotBlank()

                // ── Process ALL device_action calls (from all turns) ──
                val collectedActions = mutableListOf<JSONObject>()
                for (tc in allToolCalls.filter { it.name == "device_action" }) {
                    try {
                        val args = JSONObject(tc.arguments)
                        val action = args.optString("action", "")
                        if (action.isNotBlank() && action in KNOWN_ACTIONS) {
                            collectedActions.add(JSONObject().apply {
                                put("action", action)
                                put("params", try {
                                    JSONObject(args.optString("params", "{}"))
                                } catch (e: Exception) {
                                    JSONObject()
                                })
                            })
                        } else {
                            Log.w(TAG, "Ignoring unknown/hallucinated action: '$action'")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Bad tool-call arguments JSON: ${tc.arguments}")
                    }
                }

                val assistantText = allTextBuilder.toString().trim()

                if (assistantText.isNotEmpty()) {
                    synchronized(historyLock) {
                        messageHistory.add(HistoryMessage("assistant", assistantText))
                        trimHistoryLocked()
                    }
                }

                if (collectedActions.isNotEmpty()) {
                    val trace = collectedActions.joinToString(", ") { act ->
                        val a = act.optString("action")
                        val p = act.optJSONObject("params")
                        val hint = p?.optString("app_name")?.takeIf { it.isNotBlank() }
                            ?: p?.optString("contact_name")?.takeIf { it.isNotBlank() }
                            ?: p?.optString("destination")?.takeIf { it.isNotBlank() }
                            ?: ""
                        if (hint.isNotBlank()) "$a → $hint" else a
                    }
                    synchronized(historyLock) {
                        messageHistory.add(HistoryMessage("assistant", "[Action executed: $trace]"))
                        trimHistoryLocked()
                    }
                }

                withContext(Dispatchers.Main) {
                    if (collectedActions.isNotEmpty()) onAction(collectedActions)
                    if (hasContent || collectedActions.isNotEmpty()) {
                        onDone()
                        // ── NEW: Save the conversation session after completion ──
//                        scope.launch(Dispatchers.IO) {
//                            val sessionId = "cohere_${System.currentTimeMillis()}"
//                            val allMessages = messageHistory.map { h ->
//                                ChatMessage(text = h.text, isUser = h.role == "user")
//                            }
//                            ChatSummaryManager.saveSession(allMessages, sessionId)
//                            Log.d(TAG, "Saved conversation session: $sessionId")
//                        }
                    }
                    else if (searchRounds > 0) onError("I searched the knowledge base but couldn't find a complete answer. Please try rephrasing.")
                    else onError("No response from Cohere")
                }

            } catch (e: CancellationException) {
                Log.d(TAG, "Stream cancelled")
                removeHistoryMessage(userMessage)
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: Exception) {
                Log.e(TAG, "Cohere stream error", e)
                withContext(Dispatchers.Main) { onError("Cohere error: ${e.message}") }
            }
        }
    }

    override fun disconnect() {
        currentJob?.cancel()
        currentJob = null
        _isReady = false
        synchronized(historyLock) { messageHistory.clear() }
        Log.d(TAG, "Cohere provider disconnected")
    }

    override fun cancel() {
        lastCancelledRequestId = currentRequestId
        currentJob?.cancel()
        currentJob = null
        Log.d(TAG, "Cohere request cancelled (barge-in)")
    }

    fun clearHistory() {
        synchronized(historyLock) { messageHistory.clear() }
        Log.d(TAG, "Conversation history cleared")
    }

    fun setApiKey(key: String) {
        prefs.edit().putString(PREF_COHERE_API_KEY, key.trim()).apply()
        _isReady = key.isNotBlank()
    }

    fun setModel(model: String) {
        prefs.edit().putString(PREF_COHERE_MODEL, model).apply()
    }

    fun clearApiKey() {
        prefs.edit().remove(PREF_COHERE_API_KEY).apply()
        _isReady = false
    }

    private fun firstToolCall(message: JSONObject?): JSONObject? {
        return when (val tc = message?.opt("tool_calls")) {
            is JSONArray  -> tc.optJSONObject(0)
            is JSONObject -> tc
            else          -> null
        }
    }

    /** Caller holds [historyLock]. */
    private fun trimHistoryLocked() {
        while (messageHistory.size > maxHistoryTurns) {
            messageHistory.removeAt(0)
        }
    }

    // ── Vision: مفيش احتفاظ بالصور المتبعتة — أحدث صورة فقط في الـ history ──
    /** Caller holds [historyLock]. */
    private fun stripOlderImagesLocked(keep: HistoryMessage) {
        for (i in messageHistory.indices) {
            if (messageHistory[i] !== keep && messageHistory[i].imageDataUrl != null) {
                messageHistory[i] = messageHistory[i].copy(imageDataUrl = null)
            }
        }
    }

    // ── Vision: مسح كل الصور من الذاكرة (عند قفل شاشة الكاميرا) — النص يفضل ──
    fun clearSentImages() {
        synchronized(historyLock) {
            for (i in messageHistory.indices) {
                if (messageHistory[i].imageDataUrl != null) {
                    messageHistory[i] = messageHistory[i].copy(imageDataUrl = null)
                }
            }
        }
        Log.d(TAG, "cleared sent images from history")
    }

    /** Identity removal prevents an old cancelled stream from deleting a newer identical message. */
    private fun removeHistoryMessage(message: HistoryMessage) {
        synchronized(historyLock) {
            val index = messageHistory.indexOfLast { it === message }
            if (index >= 0) messageHistory.removeAt(index)
        }
    }
}
