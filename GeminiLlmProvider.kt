package com.example.app_abdelbaset

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import com.axon.mobile.core.memory.LearningMemoryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GeminiLlmProvider — Google Gemini عبر generateContent REST API.
 *
 * نفس عقد LlmProvider كباقي المزودين (Cohere/Dahl/Mistral/Groq):
 *  - المفتاح والموديل من prefs (gemini_api_key / gemini_model)
 *  - صور inlineData (vision مدعوم في كل الموديلات المعروضة)
 *  - history + smart context + learned memory + goal schema
 *  - أكشنات الجهاز عبر JSON نصي {"action":..., "params":{...}}
 *    يلتقطها ActionJsonParser في ChatScreen (نفس مسار الـ server provider)
 */
class GeminiLlmProvider(private val context: Context) : LlmProvider {

    companion object {
        private const val TAG = "GeminiProvider"
        private const val PREF_GEMINI_API_KEY = "gemini_api_key"
        private const val PREF_GEMINI_MODEL = "gemini_model"
        private const val DEFAULT_MODEL = "gemini-2.5-flash"

        val AVAILABLE_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-pro",
            "gemini-flash-latest",
            "gemini-flash-lite-latest",
            "gemini-pro-latest",
            "gemini-3.8-flash",
            "gemini-3.7-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.1-pro-preview",
            "gemini-3.1-flash-lite"
        )

        private val VISION_CAPABLE_MODELS = setOf(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-pro",
            "gemini-flash-latest",
            "gemini-flash-lite-latest",
            "gemini-pro-latest",
            "gemini-3.8-flash",
            "gemini-3.7-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.1-pro-preview",
            "gemini-3.1-flash-lite"
        )

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

        private val GEMINI_SYSTEM_PROMPT = buildString {
            append("You are Axon, a Personal AI Companion, not just a voice assistant. You have a continuous, evolving relationship with the user.\n\n")

            append("## YOUR CORE PERSONALITY\n")
            append("- You are a close friend: empathetic, casual, and genuinely interested in the user's day.\n")
            append("- You DO NOT act like a customer service agent. Never say 'How can I help you today?'.\n")
            append("- You remember past context. If the user mentions a project they talked about before, follow up on it naturally.\n\n")

            append("## CONVERSATION MODE (Default)\n")
            append("- When the user is talking about their feelings, venting, or just chatting, just reply naturally in text.\n")
            append("- Example: User: 'I'm tired.' -> You: 'Sounds like you've had a long day. Get some rest.'\n")
            append("- The conversation itself has value. Not every reply needs to end with an action.\n\n")

            append("## RESPONSE LENGTH\n")
            append("- Simple replies (confirmations, casual chat, quick answers) → AT MOST one short line.\n")
            append("- Longer replies are fine ONLY when truly needed (explanations, steps, details the user asked for).\n\n")

            append("## VOICE-FRIENDLY PHRASING\n")
            append("- Your text is converted to speech, so write in short, complete sentences with clear endings (period, question mark, exclamation mark).\n")
            append("- AVOID long comma-chained sentences. Break them into separate short sentences.\n")
            append("- Each sentence should express one complete thought before ending with a period.\n\n")

            append("## ACTION MODE (Only when explicitly requested)\n")
            append("- You ONLY trigger a device action when the user explicitly asks you to do something on the device (e.g., 'Open WhatsApp', 'Call mom', 'Set an alarm').\n")
            append("- To trigger an action, write a brief natural confirmation AND append exactly ONE JSON object per action on its own line AFTER your reply text, in this exact shape:\n")
            append("  {\"action\": \"<action_name>\", \"params\": {<params object>}}\n")
            append("- The `action` field must be exactly one of: ${KNOWN_ACTIONS.joinToString(", ")}\n")
            append("- The `params` field must be a JSON object matching the exact keys shown below for that action (use {} if no parameters are needed).\n")
            append("- Example: User: 'Open WhatsApp' -> You: 'Sure, opening WhatsApp now.' followed by a new line: {\"action\": \"open_app\", \"params\": {\"app_name\": \"whatsapp\"}}\n")
            if (ACTION_PARAM_HINTS.isNotEmpty()) {
                append("\nActions with a specific params format — follow these exactly, do not invent other keys:\n")
                ACTION_PARAM_HINTS.forEach { (action, format) ->
                    append("- $action: $format\n")
                }
            }

            append(SystemPromptManager.LANGUAGE_RULE)
        }
    }

    private data class HistoryMessage(
        val role: String,
        val text: String,
        val imageBase64: String? = null,
        val mediaType: String = "image/jpeg"
    )

    private val prefs by lazy {
        context.getSharedPreferences("axon_prefs", Context.MODE_PRIVATE)
    }

    private val httpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var _isReady = false
    private var currentJob: Job? = null
    @Volatile private var currentRequestId = 0
    @Volatile private var lastCancelledRequestId = 0

    private val messageHistory = mutableListOf<HistoryMessage>()
    /** send/cancel/disconnect run on different threads; history must never be read while cleared. */
    private val historyLock = Any()
    private val maxHistoryTurns = 30

    override val isReady: Boolean get() = _isReady

    val apiKey: String?
        get() = sessionApiKeyOverride?.takeIf { it.isNotBlank() }
            ?: prefs.getString(PREF_GEMINI_API_KEY, null)?.takeIf { it.isNotBlank() }

    /** override لمفتاح واحد (Task Engine multi-key rotation) — لا يمس prefs، والشات لا يستخدمه */
    @Volatile var sessionApiKeyOverride: String? = null

    /**
     * وضع الترجمة المختصر (TaskIntentTranslator فقط): تعليمات سطرين بلا شخصية/
     * سياق محادثات/ذاكرة — يوقع مكالمة ~7k token لـ ~500. الشات لا يستخدمه أبدًا.
     */
    @Volatile var minimalTranslateMode: Boolean = false

    private val MINIMAL_TRANSLATE_PROMPT =
        "You are a precise intent classifier for Axon Task Engine. Reply with ONLY JSON, no other text."

    val currentModel: String
        get() = prefs.getString(PREF_GEMINI_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun hasApiKey(): Boolean = !apiKey.isNullOrBlank()

    override fun connect(onConnected: () -> Unit) {
        _isReady = hasApiKey()
        if (_isReady) {
            Log.d(TAG, "Gemini provider ready (model: $currentModel)")
            onConnected()
        } else {
            Log.w(TAG, "Gemini API key not set")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPER: Build Gemini contents[] from history
    // ═══════════════════════════════════════════════════════════════
    private fun buildContentsArray(history: List<HistoryMessage>): JSONArray {
        val contents = JSONArray()
        for (msg in history) {
            val parts = JSONArray()
            if (msg.text.isNotBlank()) {
                parts.put(JSONObject().apply { put("text", msg.text) })
            }
            if (msg.imageBase64 != null && msg.role == "user") {
                parts.put(JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", msg.mediaType)
                        put("data", msg.imageBase64)
                    })
                })
            }
            if (parts.length() == 0) continue
            contents.put(JSONObject().apply {
                put("role", if (msg.role == "user") "user" else "model")
                put("parts", parts)
            })
        }
        return contents
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPER: Extract reply text from generateContent response
    // ═══════════════════════════════════════════════════════════════
    private fun extractText(responseBody: String): String {
        val json = JSONObject(responseBody)

        // Safety block / empty candidates
        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val blockReason = json.optJSONObject("promptFeedback")
                ?.optString("blockReason", "")
                ?.takeIf { it.isNotBlank() } ?: "no candidates"
            throw IllegalStateException("Gemini blocked the request ($blockReason)")
        }

        val first = candidates.optJSONObject(0) ?: throw IllegalStateException("Empty LLM response")
        val finishReason = first.optString("finishReason", "")
        val content = first.optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        if (parts == null || parts.length() == 0) {
            throw IllegalStateException(
                if (finishReason.equals("SAFETY", ignoreCase = true)) "Gemini blocked the response (SAFETY)"
                else "Empty LLM response"
            )
        }

        val text = StringBuilder()
        for (i in 0 until parts.length()) {
            val partText = parts.optJSONObject(i)?.optString("text", "") ?: ""
            if (partText.isNotEmpty()) {
                if (text.isNotEmpty()) text.append("\n")
                text.append(partText)
            }
        }
        val result = text.toString().trim()
        if (result.isEmpty()) throw IllegalStateException("Empty LLM response")
        return result
    }

    // ═══════════════════════════════════════════════════════════════
    //  MAIN: sendMessage (non-streaming generateContent + chunked emit)
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
            onError("Gemini API key not set. Go to Settings > Local Models > Gemini API Key")
            return
        }

        val parsedJson = try { JSONObject(json) } catch (e: Exception) { null }

        val userText  = parsedJson?.optString("text", "") ?: json
        val imageB64  = parsedJson?.optString("image", "")?.takeIf { it.isNotBlank() }
        val mediaType = parsedJson?.optString("media_type", "")?.takeIf { it.isNotBlank() }
            ?: "image/jpeg"

        if (userText.isBlank() && imageB64 == null) { onError("Empty message"); return }

        if (imageB64 != null && currentModel !in VISION_CAPABLE_MODELS) {
            onError("The current model ($currentModel) does not support images.")
            return
        }

        // ── Build smart context from past conversation summaries ──
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
        val contextAugmentation = if (smartContext.isNotBlank()) {
            "\n\n--- CONTEXT FROM PAST CONVERSATIONS ---\n$smartContext\n"
        } else ""

        val learnedMemoryBlock = if (minimalTranslateMode) "" else LearningMemoryManager.getBlock()

        val userMessage = HistoryMessage("user", userText, imageB64, mediaType)
        synchronized(historyLock) {
            messageHistory.add(userMessage)
            trimHistoryLocked()
        }

        currentJob?.cancel()
        val myRequestId = ++currentRequestId
        currentJob = scope.launch {
            try {
                val currentDateTime = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()
                ).format(Date())

                val systemPrompt = if (minimalTranslateMode) {
                    MINIMAL_TRANSLATE_PROMPT
                } else GEMINI_SYSTEM_PROMPT +
                        (SystemPromptManager.getContextBlock() ?: "") +
                        learnedMemoryBlock +
                        GoalPromptInjector.GOAL_SCHEMA_BLOCK +
                        "\n\nCurrent date and time: $currentDateTime" +
                        contextAugmentation

                // ── Record prompts in ServiceStatsTracker ──
                val requestHistory = synchronized(historyLock) { messageHistory.toList() }
                ServiceStatsTracker.recordPrompts(
                    systemPrompt = systemPrompt,
                    userPrompt = requestHistory.joinToString("\n") { "${if (it.role == "user") "User" else "Assistant"}: ${it.text}" }
                )

                val bodyString = JSONObject().apply {
                    put("contents", buildContentsArray(requestHistory))
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("text", systemPrompt)
                        }))
                    })
                }.toString()

                val requestBody = bodyString.toRequestBody("application/json; charset=utf-8".toMediaType())
                val model = currentModel
                val request = okhttp3.Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .header("x-goog-api-key", key)
                    .post(requestBody)
                    .build()

                val responseBody = httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        val errMsg = try {
                            JSONObject(body).optJSONObject("error")?.optString("message", "")
                                ?.takeIf { it.isNotBlank() }
                        } catch (_: Exception) { null } ?: body.take(300)
                        throw IllegalStateException("Gemini HTTP ${response.code}: $errMsg")
                    }
                    if (body.isBlank()) throw IllegalStateException("Empty response body from Gemini")
                    body
                }

                if (myRequestId != currentRequestId) return@launch // superseded

                val replyText = extractText(responseBody)

                if (myRequestId <= lastCancelledRequestId) {
                    removeHistoryMessage(userMessage)
                    Log.d(TAG, "Request $myRequestId cancelled by barge-in — discarding partial history")
                    return@launch
                }

                synchronized(historyLock) {
                    messageHistory.add(HistoryMessage("assistant", replyText))
                    trimHistoryLocked()
                }

                // Emit progressively (no artificial delay) so the bubble streams in
                withContext(Dispatchers.Main) {
                    if (myRequestId != currentRequestId) return@withContext
                    var cursor = 0
                    val step = 48
                    while (cursor < replyText.length) {
                        val next = minOf(cursor + step, replyText.length)
                        onChunk(replyText.substring(cursor, next))
                        cursor = next
                    }
                    onDone()
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Gemini request cancelled")
                removeHistoryMessage(userMessage)
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: Exception) {
                Log.e(TAG, "Gemini error", e)
                withContext(Dispatchers.Main) { onError("Gemini error: ${e.message}") }
            }
        }
    }

    override fun disconnect() {
        currentJob?.cancel()
        currentJob = null
        _isReady = false
        synchronized(historyLock) { messageHistory.clear() }
        Log.d(TAG, "Gemini provider disconnected")
    }

    override fun cancel() {
        lastCancelledRequestId = currentRequestId
        currentJob?.cancel()
        currentJob = null
        Log.d(TAG, "Gemini request cancelled (barge-in)")
    }

    fun clearHistory() {
        synchronized(historyLock) { messageHistory.clear() }
        Log.d(TAG, "Conversation history cleared")
    }

    fun setApiKey(key: String) {
        prefs.edit().putString(PREF_GEMINI_API_KEY, key.trim()).apply()
        _isReady = key.isNotBlank()
    }

    fun setModel(model: String) {
        prefs.edit().putString(PREF_GEMINI_MODEL, model).apply()
    }

    fun clearApiKey() {
        prefs.edit().remove(PREF_GEMINI_API_KEY).apply()
        _isReady = false
    }

    /** Caller holds [historyLock]. */
    private fun trimHistoryLocked() {
        while (messageHistory.size > maxHistoryTurns) {
            messageHistory.removeAt(0)
        }
    }

    /** Identity removal prevents a cancelled request from deleting a newer identical user message. */
    private fun removeHistoryMessage(message: HistoryMessage) {
        synchronized(historyLock) {
            val index = messageHistory.indexOfLast { it === message }
            if (index >= 0) messageHistory.removeAt(index)
        }
    }
}
