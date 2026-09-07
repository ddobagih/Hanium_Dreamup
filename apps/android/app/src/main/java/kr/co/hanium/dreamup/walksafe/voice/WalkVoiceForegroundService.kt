package kr.co.hanium.dreamup.walksafe.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

internal interface WalkVoiceSessionController {
    fun start(): Boolean
    fun stop()
}

internal interface WalkVoiceSessionControllerProvider {
    val walkVoiceSessionController: WalkVoiceSessionController?
}

class WalkVoiceForegroundService : Service() {
    private var foregroundStarted = false
    private var sessionStarted = false
    private var activeController: WalkVoiceSessionController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVoiceSession()
            ACTION_STOP -> {
                if (stopSelfResult(startId)) stopVoiceSession()
            }
            else -> {
                // A null intent can be delivered only for a system recreation. Never resume the mic.
                if (!sessionStarted) stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopVoiceSession()
        super.onDestroy()
    }

    private fun ensureForegroundStarted() {
        if (foregroundStarted) return

        createNotificationChannel()
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun startVoiceSession() {
        ensureForegroundStarted()
        if (sessionStarted) return

        sessionStarted = true
        try {
            val controller =
                (application as? WalkVoiceSessionControllerProvider)?.walkVoiceSessionController
                    ?: run {
                        stopVoiceSession(stopController = false)
                        stopSelf()
                        return
                    }
            activeController = controller
            if (!controller.start()) {
                stopVoiceSession(stopController = false)
                stopSelf()
            }
        } catch (error: Throwable) {
            stopVoiceSession()
            stopSelf()
            throw error
        }
    }

    private fun stopVoiceSession(stopController: Boolean = true) {
        val controller = activeController
        activeController = null
        if (sessionStarted) {
            sessionStarted = false
            if (stopController) runCatching { controller?.stop() }
        }
        if (foregroundStarted) {
            foregroundStarted = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "길라잡이 음성 호출",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "보행 중 길라잡이 호출어를 기다립니다."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, WalkVoiceForegroundService::class.java)
            .setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("길라잡이 음성 입력 사용 중")
            .setContentText("호출어를 기다리고 있습니다.")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    "음성 입력 중지",
                    stopPendingIntent,
                ).build(),
            )
            .build()
    }

    companion object {
        const val ACTION_START =
            "kr.co.hanium.dreamup.walksafe.voice.action.START"
        const val ACTION_STOP =
            "kr.co.hanium.dreamup.walksafe.voice.action.STOP"

        private const val NOTIFICATION_CHANNEL_ID = "walk_voice_session"
        private const val NOTIFICATION_ID = 2_401
        private const val STOP_REQUEST_CODE = 2_402
    }
}
