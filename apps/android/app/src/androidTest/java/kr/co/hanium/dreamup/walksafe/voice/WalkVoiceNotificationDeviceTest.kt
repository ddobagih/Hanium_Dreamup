package kr.co.hanium.dreamup.walksafe.voice

import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.feedback.ForegroundAacRecorder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real Android service/notification/recorder, synthetic owner only. No walking gate is changed. */
class WalkVoiceNotificationDeviceTest {
    @Test fun notificationStopClosesRealMicrophoneAndSurvivesHostRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowVoiceNotificationDeviceTest") == "true")
        val context = instrumentation.targetContext
        val app = context.applicationContext as WalkSafeApplication
        assertNull("Cold instrumentation process required", app.walkVoiceSessionController)
        val directory = File(context.cacheDir, "notification-mic-${UUID.randomUUID()}")
        check(directory.mkdir())
        val audio = context.getSystemService(AudioManager::class.java)
        val manager = context.getSystemService(NotificationManager::class.java)
        val previousSessions = audio.activeRecordingConfigurations.map { it.clientAudioSessionId }.toSet()
        val events = mutableListOf<String>()
        val recorder = ForegroundAacRecorder(directory, onSpeechEnded = {})
        val controller = object : WalkVoiceSessionController {
            override fun start(): Boolean { events += "start"; return recorder.start() }
            override fun stop() { events += "stop"; recorder.cancel() }
        }
        val service = Intent(context, WalkVoiceForegroundService::class.java)
        var requestId = 0L
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun await(label: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            do {
                var ready = false
                main { ready = predicate() }
                if (ready) return
                SystemClock.sleep(50)
            } while (SystemClock.elapsedRealtime() < deadline)
            fail(label)
        }
        ActivityScenario.launch<WalkVoiceForegroundServiceTestHostActivity>(
            Intent(context, WalkVoiceForegroundServiceTestHostActivity::class.java)
        ).use { scenario ->
            try {
                scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                main {
                    app.attachWalkVoiceSessionController(controller)
                    app.setWalkVoiceUserStopListener {
                        assertSame(controller, it)
                        events += "user_stop"
                        app.walkVoiceUserStopHold.recordUserStop("notification-fixture-walk")
                    }
                    requestId = app.registerWalkVoiceStartRequest(controller) { _, _, failure ->
                        events += "failure:$failure"
                    }
                    context.startService(Intent(service).setAction(WalkVoiceForegroundService.ACTION_START)
                        .putExtra(WalkVoiceForegroundService.EXTRA_START_REQUEST_ID, requestId))
                }
                await("Actual foreground notification and recorder did not start") {
                    recorder.isRecording && manager.activeNotifications.any { it.id == 2401 } &&
                        audio.activeRecordingConfigurations.any { it.clientAudioSessionId !in previousSessions }
                }
                val action = manager.activeNotifications.single { it.id == 2401 }.notification.actions
                    .single { it.title.toString() == "음성 입력 중지" }
                val began = SystemClock.elapsedRealtime()
                action.actionIntent.send()
                await("Notification stop must release actual microphone and notification") {
                    !recorder.isRecording && manager.activeNotifications.none { it.id == 2401 } &&
                        audio.activeRecordingConfigurations.none { it.clientAudioSessionId !in previousSessions }
                }
                val elapsed = SystemClock.elapsedRealtime() - began
                main {
                    assertEquals(listOf("start", "user_stop", "stop"), events)
                    assertTrue(app.walkVoiceUserStopHold.blocksAutomaticStart("notification-fixture-walk"))
                    assertTrue(directory.listFiles().orEmpty().isEmpty())
                }
                scenario.recreate()
                SystemClock.sleep(1500)
                main {
                    assertTrue(app.walkVoiceUserStopHold.blocksAutomaticStart("notification-fixture-walk"))
                    assertFalse(recorder.isRecording)
                    assertEquals(listOf("start", "user_stop", "stop"), events)
                    assertFalse(app.acceptsWalkVoiceStartRequest(requestId, controller))
                }
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "NOTIFICATION_STOP native_service=true native_recorder=true real_notification_pending_intent=true cleanup_ms=$elapsed owner_before_stop=true cache_deleted=true recreated_host_hold=true main_walking_session=false owner=SYNTHETIC\n")
                })
            } finally {
                main {
                    context.stopService(service)
                    recorder.cancel()
                    app.setWalkVoiceUserStopListener(null)
                    app.invalidateWalkVoiceStartRequest(requestId, controller)
                    app.detachWalkVoiceSessionController(controller)
                    app.walkVoiceUserStopHold.blocksAutomaticStart("fixture-cleanup")
                }
                check(directory.deleteRecursively())
            }
        }
    }
}
