package kr.co.hanium.dreamup.walksafe.device

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.diagnostics.BoundedRuntimeDiagnosticLog
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Local debug output only, explicitly opted-in emulator; no camera, account or store reset. */
@RunWith(AndroidJUnit4::class)
class DeviceCheckTraceAndroidTest {
    @Test(timeout = 10_000L)
    fun typedRecoveryFailureIsRetainedWithTheExactRuntimeBuildIdentity() {
        assumeTrue(BuildConfig.DEBUG && InstrumentationRegistry.getArguments()
            .getString("allowDeviceCheckTraceTests") == "true")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish") || Build.MODEL.contains("sdk_gphone"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BoundedRuntimeDiagnosticLog.initialize(context)
        val directory = File(context.cacheDir, "runtime-diagnostics")
        fun existingLines(): List<String> = listOf("events.log", "events.previous.log")
            .flatMap { name -> runCatching { File(directory, name).readLines() }.getOrDefault(emptyList()) }
        val before = existingLines().toSet() // A prior run of this same APK cannot prove this enqueue.
        val flow = DeviceCheckTraceIds.next()
        BoundedRuntimeDiagnosticLog.recordDeviceCheck(DeviceCheckTraceEvent(flow,
            DeviceCheckTraceStage.AUTHORITY_RECONCILE, DeviceCheckTraceOutcome.FAILED,
            DeviceCheckTraceReason.READ_FAILED, 17L, 3L,
            PostLoginDeviceCheckState.FAIL, PostLoginDeviceCheckFailure.RESULT_SAVE_UNCONFIRMED))
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        var retained: String? = null
        do {
            retained = listOf("events.log", "events.previous.log").asSequence()
                .mapNotNull { name -> runCatching { File(directory, name).readText() }.getOrNull() }
                .flatMap { it.lineSequence() }.firstOrNull {
                    it !in before && it.contains("event=DEVICE_CHECK flow_id=$flow ") &&
                        it.contains("runtime_build_id=${BuildConfig.WALKSAFE_RUNTIME_BUILD_ID}") &&
                        it.contains("failure=RESULT_SAVE_UNCONFIRMED")
                }
            if (retained == null) Thread.sleep(25L)
        } while (retained == null && SystemClock.elapsedRealtime() < deadline)
        val line = requireNotNull(retained) { "Device-check metadata did not reach the bounded cache file" }
        assertTrue(line.contains("source_commit=${BuildConfig.WALKSAFE_SOURCE_COMMIT}"))
        assertTrue(line.contains("run_id=")); assertTrue(line.contains("elapsed_ms="))
        assertTrue(line.contains("stage=AUTHORITY_RECONCILE outcome=FAILED reason=READ_FAILED"))
        assertFalse(line.contains("actor_id=")); assertFalse(line.contains("operation_id="))
        assertTrue(line.toByteArray().size <= 1024)
        assertTrue(File(directory, "events.log").length() <= 128L * 1024L)
    }
}
