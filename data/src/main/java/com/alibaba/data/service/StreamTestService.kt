package com.alibaba.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class StreamTestService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val _progress = MutableStateFlow<TestProgress?>(null)
    val progress: StateFlow<TestProgress?> = _progress.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        
        // Acquire wake lock to prevent CPU sleep during testing
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Alibaba::StreamTestWakeLock"
        ).apply {
            acquire(60 * 60 * 1000L) // 1 hour max
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TEST -> {
                val notification = createNotification("Test başlatılıyor...", 0, null)
                startForeground(NOTIFICATION_ID, notification)
            }
            ACTION_STOP_TEST -> {
                stopSelf()
            }
            else -> {
                // Progress update from ViewModel
                val step = intent?.getStringExtra("progress_step") ?: ""
                val percent = intent?.getIntExtra("progress_percent", 0) ?: 0
                val eta = intent?.getIntExtra("progress_eta", -1)?.takeIf { it >= 0 }
                
                if (step.isNotEmpty()) {
                    val notification = createNotification(step, percent, eta)
                    notificationManager.notify(NOTIFICATION_ID, notification)
                }
            }
        }
        return START_STICKY // Restart service if killed by system
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
        serviceScope.cancel()
        super.onDestroy()
    }

    fun updateProgress(step: String, percent: Int, eta: Int?) {
        _progress.value = TestProgress(step, percent, eta)
        val notification = createNotification(step, percent, eta)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun completeTest(success: Boolean, message: String) {
        val notification = createCompletionNotification(success, message)
        notificationManager.notify(NOTIFICATION_ID, notification)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Stream Test",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "IPTV stream test progress"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String, progress: Int, eta: Int?): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, StreamTestService::class.java).apply {
            action = ACTION_STOP_TEST
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = if (eta != null && eta > 0) {
            "$text - Kalan: ${eta}s"
        } else {
            text
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IPTV Test - %$progress")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_delete, "Durdur", stopPendingIntent)
            .setColor(0x00FF41)
            .build()
    }

    private fun createCompletionNotification(success: Boolean, message: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (success) "Test Tamamlandı" else "Test Başarısız")
            .setContentText(message)
            .setSmallIcon(if (success) android.R.drawable.ic_menu_info_details else android.R.drawable.ic_dialog_alert)
            .setColor(if (success) 0x00FF41 else 0xFF0055)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    data class TestProgress(
        val step: String,
        val percent: Int,
        val etaSeconds: Int?
    )

    companion object {
        private const val CHANNEL_ID = "stream_test_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_TEST = "com.alibaba.START_TEST"
        const val ACTION_STOP_TEST = "com.alibaba.STOP_TEST"

        fun start(context: Context) {
            val intent = Intent(context, StreamTestService::class.java).apply {
                action = ACTION_START_TEST
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, StreamTestService::class.java).apply {
                action = ACTION_STOP_TEST
            }
            context.startService(intent)
        }
    }
}
