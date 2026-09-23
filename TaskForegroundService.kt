// TaskForegroundService.kt — Axon Task Engine V1: يبقي الـ Task حيًا لو خرجت من الصفحة/App
// scoped فقط لمدة التنفيذ — ليس دائمًا مثل GoalEngineService. يُوقف بعد كل task.
package com.example.app_abdelbaset

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class TaskForegroundService : Service() {

    companion object {
        private const val TAG = "TaskEngine"
        private const val NOTIF_ID = 3001
        private const val CHANNEL_ID = "axon_task_channel"

        @Volatile var runningTaskId: String? = null
            private set

        @JvmStatic
        fun start(context: Context, taskId: String) {
            runningTaskId = taskId
            val intent = Intent(context, TaskForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "TaskFGS start failed: ${e.message}")
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            runningTaskId = null
            try { context.stopService(Intent(context, TaskForegroundService::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        // الـ edge glow مملوك لهذه الـ service: تنظيفه مضمون في onDestroy/onTaskRemoved
        try { TaskEdgeGlowController.attach(applicationContext) } catch (_: Exception) {}
        val notif = buildNotif("Task Engine يعمل…")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { TaskEdgeGlowController.forceHide() } catch (_: Exception) {}
        runningTaskId = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try { TaskEdgeGlowController.forceHide() } catch (_: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

    private fun createChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Axon Task Engine", NotificationManager.IMPORTANCE_LOW)
                )
            }
        } catch (_: Exception) {}
    }

    private fun buildNotif(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Axon Task Engine")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
}
