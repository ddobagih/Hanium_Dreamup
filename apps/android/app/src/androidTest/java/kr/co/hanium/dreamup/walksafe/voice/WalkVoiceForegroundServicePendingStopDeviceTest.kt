package kr.co.hanium.dreamup.walksafe.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalkVoiceForegroundServicePendingStopDeviceTest {
    @Test
    fun foregroundStartImmediatelyStoppedBeforeMainLooperCanCreateService() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext

        requireProbeCondition(
            arguments.getString(PROBE_ARGUMENT) == "true",
            "explicit_flag_missing",
        )
        requireProbeCondition(isEmulator(), "physical_device_forbidden")
        requireProbeCondition(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
            "record_audio_permission_missing",
        )
        val provider = context.applicationContext as? WalkVoiceSessionControllerProvider
        requireProbeCondition(provider != null, "controller_provider_missing")
        requireProbeCondition(
            provider?.walkVoiceSessionController == null,
            "controller_present_cold_target_required",
        )

        Log.i(
            TAG,
            "status=preflight_ok emulator=true record_audio=granted controller=absent",
        )
        val operationFailure = AtomicReference<RuntimeException?>()
        instrumentation.runOnMainSync {
            val intent = Intent(context, WalkVoiceForegroundService::class.java)
                .setAction(WalkVoiceForegroundService.ACTION_START)
            try {
                Log.i(TAG, "status=start_issuing action=ACTION_START")
                context.startForegroundService(intent)
                Log.i(TAG, "status=start_returned")
                val stopped = context.stopService(intent)
                Log.i(TAG, "status=stop_returned result=$stopped")
            } catch (error: RuntimeException) {
                operationFailure.set(error)
                Log.i(
                    TAG,
                    "status=operation_rejected type=${error.javaClass.name}",
                )
            }
        }
        operationFailure.get()?.let { error ->
            fail("Foreground-service race probe was rejected: ${error.javaClass.name}")
        }

        SystemClock.sleep(EXTERNAL_CRASH_WINDOW_MS)
        Log.i(TAG, "status=survived_10s external_crash_not_observed")
        fail("Target process survived the 10-second external crash window")
    }

    @Test
    fun ordinaryStartImmediatelyStoppedBeforeMainLooperCanCreateServiceSurvives() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext

        requireProbeCondition(
            arguments.getString(SAFE_PROBE_ARGUMENT) == "true",
            "explicit_safe_flag_missing",
        )
        requireProbeCondition(isEmulator(), "physical_device_forbidden")
        requireProbeCondition(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
            "record_audio_permission_missing",
        )
        val provider = context.applicationContext as? WalkVoiceSessionControllerProvider
        requireProbeCondition(provider != null, "controller_provider_missing")
        requireProbeCondition(
            provider?.walkVoiceSessionController == null,
            "controller_present_cold_target_required",
        )
        val audioManager = context.getSystemService(AudioManager::class.java)
        val recordingSessionsBefore = audioManager.activeRecordingConfigurations
            .map { configuration -> configuration.clientAudioSessionId }
            .toSet()

        Log.i(
            TAG,
            "status=safe_preflight_ok emulator=true record_audio=granted controller=absent",
        )
        val operationFailure = AtomicReference<RuntimeException?>()
        instrumentation.runOnMainSync {
            val intent = Intent(context, WalkVoiceForegroundService::class.java)
                .setAction(WalkVoiceForegroundService.ACTION_START)
            try {
                Log.i(TAG, "status=safe_start_issuing mode=ordinary action=ACTION_START")
                context.startService(intent)
                Log.i(TAG, "status=safe_start_returned mode=ordinary")
                val stopped = context.stopService(intent)
                Log.i(TAG, "status=safe_stop_returned result=$stopped")
            } catch (error: RuntimeException) {
                operationFailure.set(error)
                Log.i(
                    TAG,
                    "status=safe_operation_rejected type=${error.javaClass.name}",
                )
            }
        }
        operationFailure.get()?.let { error ->
            fail("Ordinary-service race probe was rejected: ${error.javaClass.name}")
        }

        SystemClock.sleep(EXTERNAL_CRASH_WINDOW_MS)
        val controllerAbsent = provider?.walkVoiceSessionController == null
        val newRecordingSessions = audioManager.activeRecordingConfigurations
            .map { configuration -> configuration.clientAudioSessionId }
            .filterNot(recordingSessionsBefore::contains)
        Log.i(
            TAG,
            "status=safe_survived_10s controller_absent=$controllerAbsent " +
                "new_recording_sessions=${newRecordingSessions.size}",
        )
        assertTrue("Controller must remain absent", controllerAbsent)
        assertTrue("Probe must not create a recorder", newRecordingSessions.isEmpty())
    }

    private fun requireProbeCondition(condition: Boolean, reason: String) {
        if (condition) return
        Log.i(TAG, "status=blocked reason=$reason")
        fail("Foreground-service race probe blocked: $reason")
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.startsWith("sdk_gphone") ||
            Build.HARDWARE == "goldfish" ||
            Build.HARDWARE == "ranchu" ||
            Build.HARDWARE == "cutf_cuttlefish"

    private companion object {
        const val TAG = "WalkVoiceFgsRaceProbe"
        const val PROBE_ARGUMENT = "walksafe.fgs_pending_stop_probe"
        const val SAFE_PROBE_ARGUMENT = "walksafe.service_pending_stop_safe_probe"
        const val EXTERNAL_CRASH_WINDOW_MS = 10_000L
    }
}
