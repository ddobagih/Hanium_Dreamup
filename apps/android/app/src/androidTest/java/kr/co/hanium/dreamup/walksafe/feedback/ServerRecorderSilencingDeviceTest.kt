package kr.co.hanium.dreamup.walksafe.feedback

import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.device.RuntimeCalibrationTestHostActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ServerRecorderSilencingDeviceTest {
    /** Real 20-second native cap. Ignores the ordinary endpoint callback ONLY in this test
     * to keep quiet-room capture running until the MediaRecorder cap, then requires deletion.
     * No audio is retained or uploaded; no claim about slow human speech accuracy.
     */
    @Test fun nativeDurationCapDiscardsAudioInsteadOfReturningATruncatedCommand() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowRecorderDurationTest") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "recorder-limit-test-${UUID.randomUUID()}").apply { mkdir() }
        val exceeded = CountDownLatch(1)
        var recorder: ForegroundAacRecorder? = null
        var callbacks = 0
        val scenario = ActivityScenario.launch<RuntimeCalibrationTestHostActivity>(
            Intent(context, RuntimeCalibrationTestHostActivity::class.java))
        try {
            val started = SystemClock.elapsedRealtime()
            scenario.onActivity {
                recorder = ForegroundAacRecorder(directory, onCaptureLimitExceeded = {
                    callbacks++
                    exceeded.countDown()
                }) { /* Keep native capture alive to reach the real duration cap. */ }
                assertTrue(recorder!!.start())
            }
            assertTrue("Native duration limit must discard audio", exceeded.await(25, TimeUnit.SECONDS))
            val elapsed = SystemClock.elapsedRealtime() - started
            assertTrue("Expected the unchanged twenty-second bound", elapsed in 19_000L..25_000L)
            scenario.onActivity {
                assertFalse(recorder!!.isRecording)
                assertNull("No truncated audio may escape via a later stop", recorder!!.stop())
                assertEquals(1, callbacks)
            }
            assertTrue("Rejected recording must be deleted", directory.listFiles()!!.isEmpty())
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "SERVER_RECORDER native_limit_ms=$elapsed rejected=true uploadable_file=false temp_audio_deleted=true endpoint_callback=test_ignored\n")
            })
        } finally {
            scenario.onActivity { recorder?.cancel() }
            scenario.close()
            directory.deleteRecursively()
        }
    }

    @Test fun osSilencedAacIsCancelledAndDeletedWithoutUpload() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allowRecorderAppOpTest") == "true")
        val original = requireNotNull(args.getString("originalRecordAudioUidMode"))
        require(original in setOf("allow", "foreground", "default"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "recorder-test-${UUID.randomUUID()}").apply { mkdir() }
        fun mode(value: String) {
            val command = "cmd appops set --uid ${context.packageName} RECORD_AUDIO $value"
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
                assertTrue("AppOps command failed", it.readBytes().toString(Charsets.UTF_8).isBlank())
            }
        }
        var recorder: ForegroundAacRecorder? = null
        val silenced = CountDownLatch(1)
        var limits = 0
        val scenario = ActivityScenario.launch<RuntimeCalibrationTestHostActivity>(
            Intent(context, RuntimeCalibrationTestHostActivity::class.java))
        try {
            scenario.onActivity {
                recorder = ForegroundAacRecorder(directory, onInputSilenced = { silenced.countDown() }) { limits++ }
                assertTrue("Real AAC microphone start failed", recorder!!.start())
            }
            mode("ignore")
            assertTrue("OS silencing must cancel within three seconds", silenced.await(3, TimeUnit.SECONDS))
            scenario.onActivity { assertFalse(recorder!!.isRecording); assertNull(recorder!!.stop()) }
            assertEquals(0, limits)
            assertTrue("Silenced audio must be removed", directory.listFiles()!!.isEmpty())
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SERVER_RECORDER os_appop_silenced=true native_config_detected=true uploadable_file=false temp_audio_deleted=true real_phone_call=NOT_TESTED\n") })
        } finally {
            mode(original)
            scenario.onActivity { recorder?.cancel() }
            scenario.close()
            directory.deleteRecursively()
        }
    }
}
