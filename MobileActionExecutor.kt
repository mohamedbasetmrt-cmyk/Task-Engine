package com.example.app_abdelbaset

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.SearchManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.provider.*
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import java.util.concurrent.TimeUnit
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.DownloadManager
import android.graphics.Path
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import android.speech.SpeechRecognizer
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

/**
 * MobileActionExecutor — v4  (MASSIVE EXPANSION)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * NEW functions added (40+ total):
 *   set_alarm, set_timer, stopwatch_start/stop/reset
 *   flashlight_toggle, play_music, pause_music, next_track, previous_track
 *   take_photo, record_video
 *   navigate_to, share_location
 *   send_whatsapp, email_send
 *   airplane_mode, do_not_disturb, hotspot_toggle
 *   open_url, copy_to_clipboard
 *   calendar_add_event
 *   reminder_set, contact_add, contact_search
 *   notes_add
 *   weather_check, search_web, translate
 *   battery_status, memory_status
 *   read_last_notification, dismiss_notification
 *
 * ═══════════════════════════════════════════════════════════════════════
 */
class MobileActionExecutor(
    private val context: Context,
    /**
     * false في مسار الخلفية (widget): إطلاق Activity مباشر ممنوع من السيرفس (BAL)
     * فيُحفظ كزر إشعار عبر [drainPendingLaunches] بدل المنع الصامت. true = سلوك الشات.
     */
    private val interactive: Boolean = true
) {

    companion object {
        private const val TAG = "MobileActionExecutor"
    }

    /** نوايا معلقة لمسار الخلفية — تُفرَّغ بعد اكتمال الأكشنات */
    private val pendingLaunches =
        java.util.Collections.synchronizedList(mutableListOf<Pair<Intent, String>>())

    /**
     * true لو الأكشن الحالي سيسلّم نتيجته لاحقًا (async مثل الموقع) —
     * تُضبط على نفس خيط الـ main قبل finally فيضبط التخطي بأمان.
     * تُصفَّر مع كل execute() جديد.
     */
    @Volatile private var asyncResultArmed = false

    /** تُستدعى بعد انتهاء التنفيذ (خيط واحد بعد الـ latch) — ترجع نسخة وتفرغ القائمة */
    fun drainPendingLaunches(): List<Pair<Intent, String>> {
        synchronized(pendingLaunches) {
            val copy = pendingLaunches.toList()
            pendingLaunches.clear()
            return copy
        }
    }

    /**
     * بديل startActivity المباشر: تفاعلي → إطلاق فوري (ويرمي الخطأ كالسابق
     * للحفاظ على الـ fallbacks الموجودة)، خلفية → حفظ كزر إشعار.
     */
    private fun launchActivity(intent: Intent, label: String = "Open"): Boolean {
        try { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } catch (_: Exception) {}
        if (!interactive) {
            pendingLaunches.add(intent to label)
            Log.d(TAG, "queued for notification tap (background): $label")
            return false
        }
        context.startActivity(intent)
        return true
    }

    private val mainHandler  = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val vibrator     = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
    else null

    // ─────────────────────────────────────────────────────────────────────
    // MAIN ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────

    fun execute(actionFrame: JSONObject, onResult: (String) -> Unit = {}) {
        val action = actionFrame.optString("action", "unknown")
        val params = actionFrame.optJSONObject("params") ?: JSONObject()

        Log.d(TAG, "Executing action='$action'  params=$params")

        // ── NEW: Record tool usage in ServiceStatsTracker ──
        ServiceStatsTracker.recordToolUsage(
            toolName = action,
            parameters = params.toString()
        )

        var resultSent = false
        val safeOnResult: (String) -> Unit = { text ->
            if (!resultSent) { resultSent = true; onResult(text) }
        }

        asyncResultArmed = false
        mainHandler.post {
            try {
                when (action) {
                    // ── Volume ──────────────────────────────────────────
                    "volume_up"        -> volumeUp()
                    "volume_down"      -> volumeDown()
                    "volume_set"       -> volumeSet(params.optInt("level", 8))
                    "volume_mute"      -> volumeMute()
                    "volume_unmute"    -> volumeUnmute()

                    // ── Screen ──────────────────────────────────────────
                    "screen_lock"      -> screenLock()
                    "screenshot"       -> takeScreenshot()
                    "brightness_set"   -> setBrightness(params.optInt("level", 128))

                    // ── Apps ────────────────────────────────────────────
                    "open_app"         -> openApp(params.optString("app_name", ""))
                    "open_url"         -> openUrl(params.optString("url", ""))

                    // ── Calls ───────────────────────────────────────────
                    "call"             -> makeCall(
                        params.optString("number", ""),
                        params.optString("contact_name", "")
                    )
                    "answer_call"      -> answerCall()
                    "end_call"         -> endCall()

                    // ── Messaging ───────────────────────────────────────
                    "send_sms"         -> sendSms(
                        params.optString("number", ""),
                        params.optString("message", "")
                    )
                    "send_whatsapp"    -> sendWhatsApp(
                        params.optString("number", ""),
                        params.optString("message", "")
                    )
                    "email_send"       -> sendEmail(
                        params.optString("to", ""),
                        params.optString("subject", ""),
                        params.optString("body", "")
                    )

                    "play_audio_file"        -> playAudioFile(params.optString("path", ""))
                    "set_screen_timeout"     -> setScreenTimeout(params.optInt("seconds", 60))
                    "get_foreground_app"     -> getForegroundApp()
                    "open_whatsapp_chat"     -> openWhatsAppChat(params.optString("number", ""))
                    "get_network_status"     -> getNetworkStatus()
                    "bluetooth_scan"         -> bluetoothScan()
                    "play_video"             -> playVideo(params.optString("path", ""))
                    "show_image"             -> showImage(params.optString("path", ""))
                    "open_document"          -> openDocument(params.optString("path", ""))
                    "start_tracking"         -> startLocationTracking()
                    "geofence_trigger"       -> setGeofence(
                        params.optDouble("lat", 0.0),
                        params.optDouble("lng", 0.0),
                        params.optDouble("radius", 100.0),
                        params.optString("label", "")
                    )
                    "get_calendar_events"    -> getCalendarEvents(params.optString("date", ""))
                    "reply_notification"     -> replyToNotification(
                        params.optString("app_package", ""),
                        params.optString("reply_text", "")
                    )
                    "start_voice_recognition"-> startVoiceRecognition(params.optString("language", "en-US"))
                    "speak_text"             -> speakText(params.optString("text", ""))
                    "click_ui_element"       -> clickUiElement(
                        params.optString("text", ""),
                        params.optString("content_desc", ""),
                        params.optString("resource_id", "")
                    )
                    "scroll_screen"          -> scrollScreen(params.optString("direction", "down"), params.optInt("amount", 500))
                    "find_text_on_screen"    -> findTextOnScreen(params.optString("text", ""))
                    "input_text"             -> inputTextToFocused(params.optString("text", ""))
                    "list_files"             -> listFiles(params.optString("path", ""))
                    "search_files"           -> searchFiles(params.optString("name", ""), params.optString("path", ""))
                    "open_file"              -> openFile(params.optString("path", ""))
                    "delete_file"            -> deleteFile(params.optString("path", ""))

                    // ── Connectivity ────────────────────────────────────
                    "wifi_toggle"      -> wifiToggle(params.optBoolean("enable", true))
                    "bluetooth_toggle" -> bluetoothToggle(params.optBoolean("enable", true))
                    "airplane_mode"    -> airplaneMode(params.optBoolean("enable", true))
                    "hotspot_toggle"   -> hotspotToggle(params.optBoolean("enable", true))

                    // ── Media ───────────────────────────────────────────
                    "play_music"       -> mediaControl("play")
                    "pause_music"      -> mediaControl("pause")
                    "next_track"       -> mediaControl("next")
                    "previous_track"   -> mediaControl("previous")

                    // ── Flashlight ──────────────────────────────────────
                    "flashlight_toggle"-> flashlightToggle(params.optBoolean("enable", true))

                    // ── Location ────────────────────────────────────────
                    // GPS لا يُبدَّل برمجيًا لتطبيقات الطرف الثالث — صفحة النظام مباشرة
                    "location_settings"  -> openLocationSettings()
                    // محاولة صامتة أولًا (تنجح فقط مع منحة ADB لمرة واحدة)، ثم الصفحة
                    "location_toggle"    -> toggleLocation(params.optBoolean("enable", true))

                    // ── Camera ──────────────────────────────────────────
                    "take_photo"       -> takePhoto(params.optString("camera", "back"))
                    "record_video"     -> recordVideo(
                        params.optInt("duration", 30),
                        params.optString("camera", "back")
                    )

                    // ── Navigation ────────────────────────────────────
                    "navigate_to"      -> navigateTo(
                        params.optString("destination", ""),
                        params.optString("mode", "driving")
                    )
                    "share_location"   -> shareLocation(params.optString("contact", ""))
                    // موقع GPS حقيقي (Fused — async، النتيجة لاحقًا عبر onResult)
                    "get_current_location" -> getCurrentLocationFast(safeOnResult)

                    // ── Time & Alarms ───────────────────────────────────
                    "set_alarm"        -> setAlarm(
                        params.optInt("hour", 7),
                        params.optInt("minute", 0),
                        params.optString("label", "Alarm"),
                        parseRepeatDays(params.optJSONArray("repeat"))
                    )
                    "set_timer"        -> setTimer(
                        params.optInt("seconds", 60),
                        params.optString("label", "Timer")
                    )
                    "stopwatch_start"  -> stopwatchControl("start")
                    "stopwatch_stop"   -> stopwatchControl("stop")
                    "stopwatch_reset"  -> stopwatchControl("reset")

                    // ── Calendar ────────────────────────────────────────
                    "calendar_add_event" -> addCalendarEvent(
                        params.optString("title", "Event"),
                        params.optString("day", "today"),
                        params.optString("time", "")
                    )

                    // ── Reminders & Notes ───────────────────────────────
                    "reminder_set"     -> setReminder(
                        params.optString("text", ""),
                        params.optString("time", "")
                    )
                    "notes_add"        -> addNote(
                        params.optString("title", ""),
                        params.optString("content", "")
                    )
                    "download_file" -> downloadFile(params.optString("url", ""), params.optString("filename", ""))
                    // ── Contacts ────────────────────────────────────────
                    "contact_add"      -> addContact(
                        params.optString("name", ""),
                        params.optString("number", "")
                    )
                    "contact_search"   -> searchContact(params.optString("name", ""), safeOnResult)

                    // ── System Info ─────────────────────────────────────
                    "battery_status"   -> batteryStatus()
                    "memory_status"    -> memoryStatus()
                    "do_not_disturb"   -> doNotDisturb(params.optBoolean("enable", true))

                    // ── Utilities ───────────────────────────────────────
                    "copy_to_clipboard"-> copyToClipboard(params.optString("text", ""))
                    "weather_check"    -> checkWeather(params.optString("city", ""))
                    "search_web"       -> searchWeb(params.optString("query", ""))
                    "translate"        -> translateText(
                        params.optString("text", ""),
                        params.optString("target_language", "ar")
                    )

                    // ── Notifications ───────────────────────────────────
                    "read_last_notification" -> readLastNotification(
                        params.optString("app_package", ""), safeOnResult
                    )
                    "dismiss_notification"   -> dismissNotification(
                        params.optString("app_package", "")
                    )

                    // ── Desktop / Remote ──────────────────────────────────
                    "desktop_task"     -> forwardToDesktopAgent(
                        params.optString("text", ""),
                        onResult = { result -> speakText(result) },
                        onError = { err -> Log.w(TAG, "Desktop task error: $err") }
                    )

                    // ── Legacy / Unknown ────────────────────────────────
                    "unknown"          -> Log.w(TAG, "Server returned 'unknown' action")
                    else               -> Log.w(TAG, "Unrecognized action: '$action'")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Action '$action' threw: ${e.message}", e)
                showToast("Error: ${e.message}")
            } finally {
                // الأكشنات الـ async (الموقع) سلّمت نتيجتها بنفسها لاحقًا — لا ترسل فراغًا
                if (!asyncResultArmed) safeOnResult("")   // ضمان استدعاء واحد بالظبط حتى لو الأكشن مبعتش نتيجة
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  VOLUME
    // ═════════════════════════════════════════════════════════════════════
    private fun playAudioFile(path: String) {
        if (path.isBlank()) { showToast("No audio path provided"); return }
        val file = File(path)
        if (!file.exists()) { showToast("File not found: $path"); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.provider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { launchActivity(intent) } catch (e: Exception) { showToast("No audio player found") }
    }

    private fun setScreenTimeout(seconds: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
            openSettings(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}")
            return
        }
        val ms = (seconds * 1000).coerceAtLeast(15_000)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms)
        showToast("Screen timeout: ${seconds}s")
    }

    private fun getForegroundApp() {
        val usageStatsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        } else { showToast("UsageStats requires API 22+"); return }

        val end = System.currentTimeMillis()
        val begin = end - 1000 * 60 * 60
        val stats = usageStatsManager.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, begin, end)
        val foreground = stats?.filter { it.lastTimeUsed > 0 }?.maxByOrNull { it.lastTimeUsed }
        val msg = if (foreground != null) "Foreground: ${foreground.packageName}"
        else "Cannot get foreground app — grant Usage Stats in Settings"
        showToast(msg); speakText(msg)
    }

    private fun openWhatsAppChat(number: String) {
        val formatted = number.replace("+", "").replace(" ", "")
        try {
            launchActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$formatted"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { showToast("WhatsApp not installed") }
    }

    private fun getNetworkStatus() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val msg: String
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            msg = when {
                caps == null -> "No network connection"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Connected via WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Connected via Mobile Data"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Connected via Ethernet"
                else -> "Connected (unknown type)"
            }
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            msg = if (info != null && info.isConnected) "Connected: ${info.typeName}" else "No network connection"
        }
        showToast(msg); speakText(msg)
    }

    private fun bluetoothScan() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return showToast("Bluetooth not available")
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
        if (!hasPermission) { showToast("Bluetooth permissions not granted"); openSettings(Settings.ACTION_BLUETOOTH_SETTINGS); return }
        openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
        try {
            @Suppress("MissingPermission")
            val bonded = adapter.bondedDevices
            if (!bonded.isNullOrEmpty()) {
                val names = bonded.joinToString(", ") { it.name ?: it.address }
                showToast("Paired devices: $names"); speakText("Paired devices: $names")
            } else showToast("No paired Bluetooth devices found")
        } catch (e: Exception) { Log.e(TAG, "bluetoothScan error: ${e.message}") }
    }

    private fun playVideo(path: String) {
        if (path.isBlank()) { showToast("No video path provided"); return }
        val uri = if (path.startsWith("http")) Uri.parse(path) else {
            val file = File(path)
            if (!file.exists()) { showToast("File not found: $path"); return }
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { launchActivity(intent) } catch (e: Exception) { showToast("No video player found") }
    }

    private fun showImage(path: String) {
        if (path.isBlank()) { showToast("No image path provided"); return }
        val file = File(path)
        if (!file.exists()) { showToast("File not found: $path"); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { launchActivity(intent) } catch (e: Exception) { showToast("No image viewer found") }
    }

    private fun openDocument(path: String) {
        if (path.isBlank()) { showToast("No file path provided"); return }
        val file = File(path)
        if (!file.exists()) { showToast("File not found: $path"); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, getMimeType(path))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { launchActivity(intent) } catch (e: Exception) { showToast("No app to open: $path") }
    }

    private fun getMimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "txt" -> "text/plain"; "mp3" -> "audio/mpeg"; "mp4" -> "video/mp4"
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"
        else -> "*/*"
    }

    private fun startLocationTracking() {
        showToast("Opening Maps for live location tracking")
        openApp("maps")
    }

    private fun setGeofence(lat: Double, lng: Double, radius: Double, label: String) {
        if (lat == 0.0 && lng == 0.0) { showToast("Invalid coordinates"); return }
        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label.ifBlank { "Geofence" })})")
        try {
            launchActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            showToast("Geofence area shown on map (r=${radius.toInt()}m)")
        } catch (e: Exception) { openApp("maps") }
    }

    private fun getCalendarEvents(date: String) {
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) { showToast("READ_CALENDAR permission not granted"); return }

        val cal = Calendar.getInstance()
        if (date.isNotBlank()) {
            try {
                val sdf = android.icu.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                sdf.parse(date)?.let { cal.time = it }
            } catch (e: Exception) { Log.w(TAG, "Could not parse date: $date") }
        }
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
        val start = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
        val end = cal.timeInMillis

        val projection = arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND)
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val args = arrayOf(start.toString(), end.toString())
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(CalendarContract.Events.CONTENT_URI, projection, selection, args, CalendarContract.Events.DTSTART)
            val events = mutableListOf<String>()
            while (cursor != null && cursor.moveToNext()) {
                val title = cursor.getString(0) ?: "Untitled"
                val dtStart = cursor.getLong(1)
                val timeFmt = android.icu.text.SimpleDateFormat("HH:mm", Locale.getDefault())
                events.add("$title at ${timeFmt.format(Date(dtStart))}")
            }
            val msg = if (events.isEmpty()) "No events found" else "Events: ${events.joinToString(". ")}"
            showToast(msg); speakText(msg)
        } catch (e: Exception) {
            Log.e(TAG, "getCalendarEvents error: ${e.message}")
            showToast("Could not read calendar")
        } finally { cursor?.close() }
    }

    private fun downloadFile(url: String, filename: String) {
        if (url.isBlank()) { showToast("No URL provided"); return }
        val name = filename.ifBlank { url.substringAfterLast('/').take(60).ifBlank { "download" } }
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(name)
                setDescription("Downloading via Axon")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                setAllowedOverMetered(true); setAllowedOverRoaming(true)
            }
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            showToast("Download started: $name")
        } catch (e: Exception) { showToast("Download failed: ${e.message}") }
    }

    private fun replyToNotification(appPackage: String, replyText: String) {
        val listener = AxonNotificationListener.instance
        if (listener == null) { showToast("Enable Notification Access first"); openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS); return }
        if (replyText.isBlank()) { showToast("No reply text provided"); return }
        val sent = listener.replyToLast(appPackage, replyText)
        showToast(if (sent) "Reply sent" else "Could not reply — no reply action found")
    }

    private fun startVoiceRecognition(language: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { showToast("Speech recognition not available"); return }
        context.sendBroadcast(Intent("com.example.app_abdelbaset.START_VOICE_RECOGNITION").apply {
            putExtra("language", language); setPackage(context.packageName)
        })
    }

    private fun clickUiElement(text: String, contentDesc: String, resourceId: String) {
        val service = AxonAccessibilityService.instance
        if (service == null) { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS); showToast("Enable Accessibility Service first"); return }
        val root = service.rootInActiveWindow ?: return showToast("Cannot read screen")
        val node = when {
            text.isNotBlank() -> root.findAccessibilityNodeInfosByText(text)?.firstOrNull()
            contentDesc.isNotBlank() -> findNodeByDesc(root, contentDesc)
            resourceId.isNotBlank() -> root.findAccessibilityNodeInfosByViewId(resourceId)?.firstOrNull()
            else -> null
        }
        if (node != null) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); showToast("Tapped element") }
        else showToast("Element not found on screen")
        root.recycle()
    }

    private fun findNodeByDesc(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.contentDescription?.toString()?.contains(desc, ignoreCase = true) == true) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                search(child)?.let { return it }
                child.recycle()
            }
            return null
        }
        return search(root)
    }

    private fun scrollScreen(direction: String, amount: Int) {
        val service = AxonAccessibilityService.instance ?: return openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val display = context.resources.displayMetrics
            val cx = display.widthPixels / 2f
            val cy = display.heightPixels / 2f
            val delta = amount.coerceIn(100, 1500).toFloat()
            val (startY, endY) = if (direction.lowercase() == "up") Pair(cy - delta / 2, cy + delta / 2) else Pair(cy + delta / 2, cy - delta / 2)
            val path = Path().apply { moveTo(cx, startY); lineTo(cx, endY) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build()
            service.dispatchGesture(gesture, null, null)
        } else {
            val root = service.rootInActiveWindow ?: return
            val action = if (direction.lowercase() == "up") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            root.performAction(action); root.recycle()
        }
    }

    private fun findTextOnScreen(text: String) {
        if (text.isBlank()) return
        val service = AxonAccessibilityService.instance ?: return openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val root = service.rootInActiveWindow ?: return showToast("Cannot read screen")
        val results = root.findAccessibilityNodeInfosByText(text)
        val msg = if (!results.isNullOrEmpty()) "Found \"$text\" on screen (${results.size} match${if (results.size > 1) "es" else ""})" else "\"$text\" not found on screen"
        showToast(msg); speakText(msg)
        root.recycle()
    }

    private fun inputTextToFocused(text: String) {
        if (text.isBlank()) return
        val service = AxonAccessibilityService.instance ?: return openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val root = service.rootInActiveWindow
        val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            showToast("Text entered")
        } else showToast("No focused input field found")
        root?.recycle()
    }

    private fun listFiles(path: String) {
        val dir = if (path.isBlank()) Environment.getExternalStorageDirectory() else File(path)
        if (!dir.exists() || !dir.isDirectory) { showToast("Directory not found: ${dir.absolutePath}"); return }
        val files = dir.listFiles()
        if (files.isNullOrEmpty()) { showToast("Empty directory"); speakText("Directory is empty"); return }
        val names = files.take(10).joinToString(", ") { it.name }
        val msg = "Files in ${dir.name}: $names${if (files.size > 10) " …(${files.size} total)" else ""}"
        showToast(msg); speakText(msg)
    }

    private fun searchFiles(name: String, path: String) {
        if (name.isBlank()) { showToast("No search name provided"); return }
        val root = if (path.isBlank()) Environment.getExternalStorageDirectory() else File(path)
        fun findRecursive(dir: File): List<File> {
            val results = mutableListOf<File>()
            dir.listFiles()?.forEach { f ->
                if (f.name.contains(name, ignoreCase = true)) results.add(f)
                if (f.isDirectory) results.addAll(findRecursive(f))
            }
            return results
        }
        try {
            val found = findRecursive(root).take(10)
            val msg = if (found.isEmpty()) "No files found matching \"$name\"" else "Found: ${found.joinToString(", ") { it.absolutePath }}"
            showToast(if (msg.length > 200) msg.take(200) + "…" else msg)
            speakText("Found ${found.size} file${if (found.size != 1) "s" else ""} matching $name")
        } catch (e: Exception) { showToast("Search error: ${e.message}") }
    }

    private fun openFile(path: String) = openDocument(path)

    private fun deleteFile(path: String) {
        if (path.isBlank()) { showToast("No path provided"); return }
        val file = File(path)
        if (!file.exists()) { showToast("File not found: $path"); return }
        if (file.delete()) showToast("Deleted: ${file.name}") else showToast("Could not delete: ${file.name}")
    }

    private fun volumeUp() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        Log.d(TAG, "Volume raised")
    }

    private fun volumeDown() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        Log.d(TAG, "Volume lowered")
    }

    private fun volumeSet(level: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val clamped = level.coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, AudioManager.FLAG_SHOW_UI)
        Log.d(TAG, "Volume set to $clamped/$max")
    }

    private fun volumeMute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
        } else {
            @Suppress("DEPRECATION")
            audioManager.setStreamMute(AudioManager.STREAM_MUSIC, true)
        }
        Log.d(TAG, "Muted")
    }

    private fun volumeUnmute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
        } else {
            @Suppress("DEPRECATION")
            audioManager.setStreamMute(AudioManager.STREAM_MUSIC, false)
        }
        Log.d(TAG, "Unmuted")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  SCREEN
    // ═════════════════════════════════════════════════════════════════════

    private fun takeScreenshot() {
        val service = AxonAccessibilityService.instance
        if (service != null) {
            service.requestScreenshot()
            Log.d(TAG, "Screenshot requested")
        } else {
            openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
    }

    private fun screenLock() {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, AxonDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
            Log.d(TAG, "Screen locked")
        } else {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Axon needs Device Admin to lock screen")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchActivity(intent)
        }
    }

    private fun setBrightness(level: Int) {
        val clamped = level.coerceIn(0, 255)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
            openSettings(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}")
            return
        }
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, clamped)
        Log.d(TAG, "Brightness set to $clamped")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  APPS
    // ═════════════════════════════════════════════════════════════════════

    private fun normalizeAppName(text: String): String =
        text.lowercase().trim().replace(Regex("[^a-z0-9\\u0600-\\u06FF]"), "")

    private fun openApp(appName: String) {
        if (appName.isBlank()) { Log.w(TAG, "openApp: empty name"); return }

        val pkg = resolvePackageDynamic(appName)
        if (pkg != null && launchPackage(pkg, appName)) return

        // Play Store fallback
        try {
            launchActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(appName)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            launchActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=${Uri.encode(appName)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        val fixedUrl = if (url.startsWith("http")) url else "https://$url"
        launchActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fixedUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Log.d(TAG, "Opened URL: $fixedUrl")
    }

    private fun launchPackage(pkg: String, label: String): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchActivity(launch)
        Log.d(TAG, "Opened app: '$label' → $pkg")
        return true
    }

    private fun resolvePackageDynamic(appName: String): String? {
        val query = normalizeAppName(appName)
        val pm = context.packageManager

        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val candidates = apps.map { it.activityInfo.packageName to normalizeAppName(it.loadLabel(pm).toString()) }

        // مطابقة تامة
        candidates.firstOrNull { it.second == query }?.let { return it.first }

        // أقرب مطابقة جزئية (الأقل فرقًا في الطول، مش أول واحدة بالترتيب)
        return candidates
            .filter { it.second.contains(query) || query.contains(it.second) }
            .minByOrNull { kotlin.math.abs(it.second.length - query.length) }
            ?.first
    }

    // ═════════════════════════════════════════════════════════════════════
    //  CALLS
    // ═════════════════════════════════════════════════════════════════════

    private fun makeCall(number: String, contactName: String) {
        val hasCallPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val resolvedNumber = when {
            number.isNotBlank() -> number.trim()
            contactName.isNotBlank() && hasContactsPermission ->
                lookupContactNumber(contactName) ?: ""
            else -> ""
        }

        if (resolvedNumber.isBlank() && contactName.isBlank()) {
            Log.w(TAG, "makeCall: no number or contact")
            showToast("No number or contact name provided")
            return
        }

        if (resolvedNumber.isBlank()) {
            showToast("There no number for $contactName")
            return
        }

        if (hasCallPermission) {
            // ✅ يرن مباشرة
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$resolvedNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchActivity(intent)
            Log.d(TAG, "Calling: $resolvedNumber")
        } else {
            // ❌ مش عنده permission → اطلبها من الـ Activity
            Log.w(TAG, "CALL_PHONE permission missing — requesting")
            showToast("Reqest permission")

            // اطلب الـ permission عن طريق الـ Activity
            requestCallPermissionAndCall(resolvedNumber)
        }
    }

    private fun requestCallPermissionAndCall(number: String) {
        // أرسل broadcast للـ Activity عشان تطلب الـ permission
        val intent = Intent("com.example.app_abdelbaset.REQUEST_CALL_PERMISSION").apply {
            putExtra("number", number)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    private fun lookupContactNumber(name: String): String? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$name%")
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(uri, projection, selection, args, null)
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "lookupContactNumber error: ${e.message}")
            null
        } finally {
            cursor?.close()
        }
    }

    private fun answerCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
                telecom.acceptRingingCall()
                Log.d(TAG, "Call answered")
            }
        } else {
            val down = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_HEADSETHOOK))
            }
            context.sendBroadcast(down)
        }
    }

    private fun endCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
                telecom.endCall()
                Log.d(TAG, "Call ended")
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  MESSAGING
    // ═════════════════════════════════════════════════════════════════════

    private fun sendSms(number: String, message: String) {
        if (number.isBlank() || message.isBlank()) { Log.w(TAG, "sendSms: empty"); return }
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            try {
                val mgr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    context.getSystemService(android.telephony.SmsManager::class.java)
                else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()

                val parts = mgr?.divideMessage(message)
                if (parts != null) {
                    mgr.sendMultipartTextMessage(number, null, parts, null, null)
                    Log.d(TAG, "SMS sent to $number")
                    showToast("SMS sent")
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendSms failed: ${e.message}")
            }
        }
        // Fallback
        launchActivity(Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$number")
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun sendWhatsApp(number: String, message: String) {
        val formattedNumber = number.replace("+", "").replace(" ", "")
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$formattedNumber&text=${Uri.encode(message)}")
        try {
            launchActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.d(TAG, "WhatsApp opened for $number")
        } catch (e: Exception) {
            // WhatsApp not installed
            launchActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.whatsapp"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun sendEmail(to: String, subject: String, body: String) {
        if (to.isBlank()) return
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launchActivity(intent)
        Log.d(TAG, "Email intent to $to")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  CONNECTIVITY
    // ═════════════════════════════════════════════════════════════════════

    private fun wifiToggle(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openSettings(Settings.Panel.ACTION_WIFI)
        } else {
            @Suppress("DEPRECATION")
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled = enable
            Log.d(TAG, "WiFi ${if (enable) "on" else "off"}")
        }
    }

    private fun bluetoothToggle(enable: Boolean) {
        // المحاولة المباشرة أولًا (تنجح مع BLUETOOTH_CONNECT على معظم الأجهزة)
        if (tryDirectBluetooth(enable)) {
            Log.d(TAG, "Bluetooth toggled directly enable=$enable")
            return
        }
        if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // foreground: dialog النظام مباشرة — خلفية: عبر activity شفافة مسجلة
            // (intent النظام الأعمى لا يُرصد ولا يعتمد عليه من الخلفية)
            try {
                if (interactive) {
                    launchActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), "Enable Bluetooth")
                } else {
                    launchActivity(BluetoothEnableActivity.intent(context, true), "Enable Bluetooth")
                }
                return
            } catch (_: Exception) {}
        }
        openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
    }

    /** تبديل مباشر: true تعني تم فعليًا — false تعني fallback للواجهة */
    private fun tryDirectBluetooth(enable: Boolean): Boolean {
        return try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(TAG, "BLUETOOTH_CONNECT missing — cannot toggle directly")
                    return false
                }
            }
            @Suppress("DEPRECATION")
            if (enable) adapter.enable() else adapter.disable()
        } catch (e: Exception) {
            Log.w(TAG, "direct bluetooth failed: ${e.message}")
            false
        }
    }

    private fun airplaneMode(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            // API 17+ requires Settings.Global
            try {
                Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, if (enable) 1 else 0)
                val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                    putExtra("state", enable)
                }
                context.sendBroadcast(intent)
                Log.d(TAG, "Airplane mode ${if (enable) "on" else "off"}")
            } catch (e: Exception) {
                openSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            }
        } else {
            @Suppress("DEPRECATION")
            Settings.System.putInt(context.contentResolver, Settings.System.AIRPLANE_MODE_ON, if (enable) 1 else 0)
        }
    }

    private fun hotspotToggle(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // التبديل المباشر system-only — بانل الإنترنت one-tap هو أقصى الممكن
            try {
                showToast(if (enable) "Turn Hotspot ON in the panel" else "Turn Hotspot OFF in the panel")
                launchActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), "Hotspot")
                Log.d(TAG, "Hotspot internet panel opened (enable=$enable)")
                return
            } catch (_: Exception) {}
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            openSettings(Settings.ACTION_WIRELESS_SETTINGS)
            Log.d(TAG, "Hotspot settings opened")
        } else {
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            try {
                val method = wifiManager.javaClass.getMethod("setWifiApEnabled", android.net.wifi.WifiConfiguration::class.java, Boolean::class.java)
                method.invoke(wifiManager, null, enable)
                Log.d(TAG, "Hotspot ${if (enable) "on" else "off"}")
            } catch (e: Exception) {
                openSettings(Settings.ACTION_WIRELESS_SETTINGS)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  MEDIA CONTROL
    // ═════════════════════════════════════════════════════════════════════

    private fun mediaControl(action: String) {
        val keyCode = when (action) {
            "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return
        }
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "Media control: $action")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  FLASHLIGHT
    // ═════════════════════════════════════════════════════════════════════

    /** صفحة اللوكيشن في النظام مباشرة (التوجل الصامت مستحيل منذ Android 4.4) */
    private fun openLocationSettings() {
        showToast("Turn Location on/off with one tap")
        openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    }

    /**
     * توجل اللوكيشن الصامت: يعمل فقط مع منحة WRITE_SECURE_SETTINGS لمرة واحدة عبر ADB.
     * كتابة → انتظار قصير → تحقق فعلي من الحالة → false تعني fallback لصفحة النظام.
     * بدون المنحة يسقط في الـ catch مباشرة — لا كذب ولا كراش.
     */
    private fun toggleLocation(enable: Boolean): Boolean {
        if (trySecureLocation(enable)) {
            showToast(if (enable) "Location ON" else "Location OFF")
            Log.d(TAG, "Location toggled directly enable=$enable")
            return true
        }
        openLocationSettings()
        return false
    }

    private fun trySecureLocation(enable: Boolean): Boolean {
        return try {
            // فحص سريع: بدون المنحة لا داعي لمحاولة الكتابة أصلًا
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && lm.isLocationEnabled == enable) {
                return true // الحالة المطلوبة أصلًا — لا حاجة للكتابة
            }
            @Suppress("DEPRECATION")
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.LOCATION_MODE,
                if (enable) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                else Settings.Secure.LOCATION_MODE_OFF
            )
            // الكتابة تنجح شكليًا حتى لمفتاح ميت — التحقق الفعلي هو الفيصل
            try { Thread.sleep(500) } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled == enable
            } else {
                true
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "secure location needs ADB grant: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "secure location failed: ${e.message}")
            false
        }
    }

    private fun flashlightToggle(enable: Boolean) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraManager.setTorchMode(cameraId, enable)
                Log.d(TAG, "Flashlight ${if (enable) "on" else "off"}")
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Flashlight error: ${e.message}")
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  CAMERA
    // ═════════════════════════════════════════════════════════════════════

    private fun takePhoto(camera: String) {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            launchActivity(intent)
            Log.d(TAG, "Camera opened for photo ($camera)")
        } catch (e: Exception) {
            Log.e(TAG, "Camera error: ${e.message}")
            openApp("camera")
        }
    }

    private fun recordVideo(duration: Int, camera: String) {
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_DURATION_LIMIT, duration)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            launchActivity(intent)
            Log.d(TAG, "Camera opened for video ($duration sec, $camera)")
        } catch (e: Exception) {
            Log.e(TAG, "Video error: ${e.message}")
            openApp("camera")
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  NAVIGATION & LOCATION
    // ═════════════════════════════════════════════════════════════════════

    private fun navigateTo(destination: String, mode: String) {
        if (destination.isBlank()) { Log.w(TAG, "navigateTo: empty destination"); return }
        val modeParam = when (mode) {
            "driving" -> "d"
            "walking" -> "w"
            "transit" -> "r"
            "bicycling" -> "b"
            else -> "d"
        }
        val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$modeParam")
        try {
            launchActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.d(TAG, "Navigating to $destination via $mode")
        } catch (e: Exception) {
            // Google Maps not installed, open browser
            val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}&travelmode=$modeParam")
            launchActivity(Intent(Intent.ACTION_VIEW, webUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun shareLocation(contact: String) {
        // Open share dialog with location
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Sharing my location...")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launchActivity(Intent.createChooser(intent, "Share Location").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Log.d(TAG, "Location share dialog opened")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  REAL LOCATION (Fused Location Provider — async)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * موقع GPS حقيقي: lastLocation الحديثة فورًا، وإلا getCurrentLocation() بدقة عالية.
     * async بالضرورة (Fused) — تُضبط asyncResultArmed أولًا فـ finally يتخطى،
     * والنتيجة تصل لاحقًا عبر نفس onResult (محمية settle + timeout 20 ثانية).
     */
    private fun getCurrentLocationFast(onResult: (String) -> Unit) {
        asyncResultArmed = true
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            onResult("Location permission not granted — allow it in Settings > Apps > Axon > Permissions, then ask again")
            return
        }
        try {
            val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
            var settled = false
            fun settle(text: String) {
                if (!settled) {
                    settled = true
                    Log.d(TAG, "location settle: ${text.take(90)}")
                    onResult(text)
                }
            }
            // شبكة أمان: رد خلال 20 ثانية مهما حصل
            mainHandler.postDelayed({ settle("Couldn't get a location fix — is GPS on?") }, 20_000)
            // 1) آخر موقع معروف إن كان حديثًا (< دقيقتين) — فوري بلا انتظار GPS
            client.lastLocation
                .addOnSuccessListener { loc ->
                    if (loc != null && System.currentTimeMillis() - loc.time < 120_000) {
                        settle(formatFix(loc, fresh = false))
                    } else {
                        requestFreshFix(client, ::settle)
                    }
                }
                .addOnFailureListener { requestFreshFix(client, ::settle) }
        } catch (e: SecurityException) {
            try { onResult("Location permission missing") } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.w(TAG, "location failed: ${e.message}")
            try { onResult("Location failed: ${e.message}") } catch (_: Exception) {}
        }
    }

    /** طلب fix حي بدقة عالية مع إلغاء مؤقت — يُستدعى عند غياب/قدم lastLocation */
    private fun requestFreshFix(
        client: com.google.android.gms.location.FusedLocationProviderClient,
        settle: (String) -> Unit
    ) {
        try {
            val cts = CancellationTokenSource()
            mainHandler.postDelayed({ try { cts.cancel() } catch (_: Exception) {} }, 20_000)
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { loc ->
                    if (loc != null) settle(formatFix(loc, fresh = true))
                    else settle("No location fix — turn GPS on (or step outside) and ask again")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "fresh fix failed: ${e?.message}")
                    settle("Couldn't get a fresh fix (${e?.message ?: "unknown"}) — is GPS on?")
                }
        } catch (e: Exception) {
            settle("Location failed: ${e.message}")
        }
    }

    private fun formatFix(loc: android.location.Location, fresh: Boolean): String {
        val lat = loc.latitude
        val lon = loc.longitude
        val tag = if (fresh) "live GPS fix" else "recent fix"
        return "Location ($tag): $lat, $lon (±${loc.accuracy.toInt()}m) https://maps.google.com/?q=$lat,$lon"
    }

    // ═════════════════════════════════════════════════════════════════════
    //  ALARMS & TIMERS
    // ═════════════════════════════════════════════════════════════════════

    private fun setAlarm(hour: Int, minute: Int, label: String, repeatDays: List<String>) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            if (repeatDays.isNotEmpty()) {
                val daysMap = mapOf("Sun" to Calendar.SUNDAY, "Mon" to Calendar.MONDAY, "Tue" to Calendar.TUESDAY,
                    "Wed" to Calendar.WEDNESDAY, "Thu" to Calendar.THURSDAY, "Fri" to Calendar.FRIDAY, "Sat" to Calendar.SATURDAY)
                val days = repeatDays.mapNotNull { daysMap[it] }.toIntArray()
                if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, days)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            launchActivity(intent)
            Log.d(TAG, "Alarm set: $hour:$minute - $label")
            showToast("Alarm set for $hour:$minute")
        } catch (e: Exception) {
            Log.e(TAG, "Alarm error: ${e.message}")
            // Fallback to clock app
            openApp("clock")
        }
    }

    private fun setTimer(seconds: Int, label: String) {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            launchActivity(intent)
            Log.d(TAG, "Timer set: $seconds seconds - $label")
            showToast("Timer set for ${seconds/60} minutes")
        } catch (e: Exception) {
            Log.e(TAG, "Timer error: ${e.message}")
            openApp("clock")
        }
    }

    private fun stopwatchControl(action: String) {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launchActivity(intent)
        Log.d(TAG, "Stopwatch/alarms opened for: $action")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  CALENDAR
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Adds a calendar event the same way the adb command does:
     *   adb shell am start -a android.intent.action.EDIT -d content://com.android.calendar/events
     *       --es title "..." --el beginTime <millis> --el endTime <millis>
     *
     * Rules:
     *   - [day] only accepts: today / tomorrow / day-after-tomorrow (anything else defaults to today).
     *   - [time] ("HH:mm") comes from the user if provided; otherwise the current time is used.
     *   - The event always lasts exactly 30 minutes (endTime = beginTime + 30min), regardless of
     *     whether the time was given by the user or defaulted to "now".
     */
    private fun addCalendarEvent(title: String, day: String, time: String) {
        val cal = Calendar.getInstance() // starts as "now"

        // 1) Resolve the day: today (0) / tomorrow (+1) / day after tomorrow (+2)
        val daysToAdd = when (day.trim().lowercase(Locale.getDefault())) {
            "tomorrow", "bokra", "بكرة", "بكره", "غدا", "غداً" -> 1
            "day_after_tomorrow", "after_tomorrow", "بعد بكرة", "بعد بكره", "بعد غد" -> 2
            "today", "النهاردة", "النهارده", "اليوم" -> 0
            else -> 0 // unknown value -> safest default is today
        }
        if (daysToAdd > 0) cal.add(Calendar.DAY_OF_YEAR, daysToAdd)

        // 2) Resolve the start time: from the user if given, otherwise keep the current time
        if (time.isNotBlank()) {
            try {
                val parsed = android.icu.text.SimpleDateFormat("HH:mm", Locale.getDefault()).parse(time)
                if (parsed != null) {
                    val timeCal = Calendar.getInstance().apply { setTime(parsed) }
                    cal.set(Calendar.HOUR_OF_DAY, timeCal.get(Calendar.HOUR_OF_DAY))
                    cal.set(Calendar.MINUTE, timeCal.get(Calendar.MINUTE))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not parse time: $time — falling back to current time")
            }
        }
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val startMillis = cal.timeInMillis
        val endMillis = startMillis + (30 * 60 * 1000L) // fixed 30-minute duration, always

        val intent = Intent(Intent.ACTION_EDIT).apply {
            data = Uri.parse("content://com.android.calendar/events")
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launchActivity(intent)
        Log.d(TAG, "Calendar event: $title  begin=$startMillis  end=$endMillis")
        showToast("Event added: $title")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  REMINDERS & NOTES
    // ═════════════════════════════════════════════════════════════════════

    private fun setReminder(text: String, time: String) {
        if (text.isBlank()) return
        // Use Google Keep or system reminder
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_TITLE, "Reminder: $text")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            launchActivity(Intent.createChooser(intent, "Set Reminder").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.d(TAG, "Reminder: $text at $time")
        } catch (e: Exception) {
            addNote("Reminder", "$text\nTime: $time")
        }
    }

    private fun addNote(title: String, content: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, content)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            // Try Google Keep first
            intent.setPackage("com.google.android.keep")
            launchActivity(intent)
            Log.d(TAG, "Note added to Keep: $title")
            showToast("Note saved")
        } catch (e: Exception) {
            intent.setPackage(null)
            launchActivity(Intent.createChooser(intent, "Save Note").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  CONTACTS
    // ═════════════════════════════════════════════════════════════════════

    private fun addContact(name: String, number: String) {
        if (name.isBlank() || number.isBlank()) return
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, name)
            putExtra(ContactsContract.Intents.Insert.PHONE, number)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launchActivity(intent)
        Log.d(TAG, "Contact add: $name - $number")
        showToast("Contact added: $name")
    }

    private fun searchContact(name: String, onResult: (String) -> Unit = {}) {
        if (name.isBlank()) return

        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasContactsPermission) {
            val number = lookupContactNumber(name)
            if (!number.isNullOrBlank()) {
                val msg = "$name's number is $number"
                Log.d(TAG, "Contact search: $name → $number")
                showToast(msg)
                speakText(msg)
                onResult("Found: $name - $number")
                return
            }
            val notFound = "No phone number found for $name"
            Log.d(TAG, "Contact search: $name → not found")
            showToast(notFound)
            speakText(notFound)
            onResult(notFound)
        } else {
            Log.w(TAG, "READ_CONTACTS permission missing — requesting")
            showToast("Requesting contacts permission")
            val intent = Intent("com.example.app_abdelbaset.REQUEST_CONTACTS_PERMISSION").apply {
                putExtra("name", name)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
            onResult("Contacts permission not granted; please grant it and ask again for $name")
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  SYSTEM INFO
    // ═════════════════════════════════════════════════════════════════════

    private var tts: TextToSpeech? = null

    private fun batteryStatus() {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val battery = context.registerReceiver(null, intentFilter)
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val msg = "Battery: $pct%, ${if (isCharging) "charging" else "discharging"}"
        Log.d(TAG, msg)
        showToast(msg)
        speakText(msg)
    }

    fun speakText(text: String) {
        // ← USAGE_MEDIA: خروج من الـ speaker زي الفيديو — بدون رفع تلقائي لحجم المستخدم
        val utteranceId = "mae_tts_${System.nanoTime()}"
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    tts?.language = Locale.getDefault()
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                }
            }
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun memoryStatus() {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalMB = memoryInfo.totalMem / (1024 * 1024)
        val availMB = memoryInfo.availMem / (1024 * 1024)
        val usedMB = totalMB - availMB
        val pct = (usedMB * 100 / totalMB).toInt()

        val msg = "Memory: ${usedMB}MB used of ${totalMB}MB ($pct%)"
        Log.d(TAG, msg)
        showToast(msg)
    }

    private fun doNotDisturb(enable: Boolean) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (notificationManager.isNotificationPolicyAccessGranted) {
                val mode = if (enable) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                notificationManager.setInterruptionFilter(mode)
                Log.d(TAG, "Do Not Disturb: $enable")
                showToast(if (enable) "Do Not Disturb ON" else "Do Not Disturb OFF")
            } else {
                openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  UTILITIES
    // ═════════════════════════════════════════════════════════════════════

    private fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Axon", text)
        clipboard.setPrimaryClip(clip)
        Log.d(TAG, "Copied to clipboard: $text")
        showToast("Copied to clipboard")
    }

    private fun checkWeather(city: String) {
        // Open weather app or search
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://www.google.com/search?q=weather+${Uri.encode(city.ifBlank { "current location" })}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launchActivity(intent)
        Log.d(TAG, "Weather check: $city")
    }

    private fun searchWeb(query: String) {
        if (query.isBlank()) return
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            launchActivity(intent)
            Log.d(TAG, "Web search: $query")
        } catch (e: Exception) {
            // Fallback to browser
            openUrl("https://www.google.com/search?q=${Uri.encode(query)}")
        }
    }

    private fun translateText(text: String, targetLanguage: String) {
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://translate.google.com/?sl=auto&tl=$targetLanguage&text=${Uri.encode(text)}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launchActivity(intent)
        Log.d(TAG, "Translate: $text → $targetLanguage")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  NOTIFICATIONS
    // ═════════════════════════════════════════════════════════════════════

    private fun readLastNotification(appPackage: String, onResult: (String) -> Unit = {}) {
        val listener = AxonNotificationListener.instance

        val msg = if (listener != null) {
            if (appPackage.isBlank()) {
                listener.getAllNotifications()
            } else {
                listener.getLastFor(appPackage)
            }
        } else {
            AxonNotificationListener.lastNotification
                .takeIf { it.isNotBlank() }
                ?: "No notifications — enable Notification Access in Settings"
        }

        showToast(msg)
        onResult(msg)
        Log.d(TAG, "readLastNotification: $msg")

        if (listener == null) {
            openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
    }

    private fun dismissNotification(appPackage: String) {
        val listener = AxonNotificationListener.instance
        if (listener == null) {
            showToast("Enable Notification Access first")
            openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            return
        }

        if (appPackage.isBlank()) {
            listener.dismissAll()
            showToast("All notifications dismissed")
        } else {
            listener.dismissFrom(appPackage)
            showToast("Notifications from $appPackage dismissed")
        }

        Log.d(TAG, "dismissNotification: pkg=$appPackage")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  DESKTOP TASK FORWARDING
    // ═════════════════════════════════════════════════════════════════════

    private fun forwardToDesktopAgent(text: String, onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (text.isBlank()) return

        val prefs = context.getSharedPreferences("axon_prefs", Context.MODE_PRIVATE)
        val rawEndpoint = EndpointConfig.resolveHost(
            context,
            prefs.getString("endpoint", MainActivity.PRESET_ENDPOINTS[0])
        )
        val wsUrl = "wss://$rawEndpoint/mobile/ws/remote/remote_${System.currentTimeMillis()}"

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val fullResponse = StringBuilder()

        client.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Remote WS opened for: ${text.take(60)}")
                ws.send(JSONObject().apply { put("text", text) }.toString())
            }

            override fun onMessage(ws: WebSocket, message: String) {
                try {
                    val json = JSONObject(message)
                    when (json.optString("type")) {
                        "sentence" -> {
                            fullResponse.append(json.optString("text", "")).append(" ")
                        }
                        "done" -> {
                            val result = if (fullResponse.isNotEmpty()) fullResponse.toString().trim()
                            else json.optString("text", "")
                            ws.close(1000, "Done")
                            client.dispatcher.executorService.shutdown()
                            mainHandler.post { onResult(result) }
                        }
                        "error" -> {
                            mainHandler.post { onError(json.optString("text", "")) }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Remote WS parse: ${e.message}")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Remote WS failure: ${t.message}")
                mainHandler.post { onError(t.message ?: "Remote connection failed") }
            }
        })
    }

    // ═════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═════════════════════════════════════════════════════════════════════

    private fun openSettings(action: String, data: String? = null) {
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data?.let { this.data = Uri.parse(it) }
        }
        launchActivity(intent, "Settings")
    }

    private fun showToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    private fun parseRepeatDays(jsonArray: JSONArray?): List<String> {
        if (jsonArray == null) return emptyList()
        return (0 until jsonArray.length()).map { jsonArray.getString(it) }
    }
}

// ═════════════════════════════════════════════════════════════════════
//  ACCESSIBILITY SERVICE
// ═════════════════════════════════════════════════════════════════════

class AxonAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AxonA11y"
        @Volatile var instance: AxonAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
        // NOTE: do NOT overwrite serviceInfo here — the XML config
        // (axon_accessibility_config.xml, canRetrieveWindowContent=true)
        // is the source of truth. Overwriting it wiped the
        // retrieve-window-content capability and made rootInActiveWindow
        // always null (seen on Android 16).
        try {
            val si = serviceInfo
            Log.i(TAG, "connected canRetrieve=${si?.canRetrieveWindowContent} flags=0x${(si?.flags ?: 0).toString(16)} events=${si?.eventTypes}")
        } catch (e: Exception) {
            Log.i(TAG, "connected (dump failed: ${e.message})")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.d(TAG, "AccessibilityService disconnected")
    }

    fun requestScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val ok = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            Log.d(TAG, "Screenshot result=$ok")
        } else {
            Log.w(TAG, "Screenshots require API 28+")
        }
    }

    // TEMP-A11Y-TEST: read-only accessor for the isolated screen-reader test.
    // Safe to remove together with A11yScreenTest.kt. Runs on caller thread.
    fun activeRootForTest(): AccessibilityNodeInfo? {
        return try { rootInActiveWindow } catch (e: Exception) {
            Log.w(TAG, "activeRootForTest failed: ${e.message}")
            null
        }
    }
}

class AxonDeviceAdminReceiver : android.app.admin.DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.d("AxonAdmin", "Device admin enabled")
    }
    override fun onDisabled(context: Context, intent: Intent) {
        Log.d("AxonAdmin", "Device admin disabled")
    }
}