package kr.co.hanium.dreamup.walksafe.voice

import android.app.ActivityManager
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.device.RuntimeCalibrationTestHostActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual bound decoder death, not a reproduction of the original Kaldi assertion or human speech. */
@RunWith(AndroidJUnit4::class)
class VoskDecoderIsolationAndroidTest {
    @Test(timeout = 90_000L)
    fun decoderDeathKeepsOwnerAliveAndReleasesMicrophoneForExplicitRetry() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runVoskDecoderIsolation") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = checkNotNull(BundledVoskModelInstaller.installedModelOrNull(context)) {
            "Existing verified model is required; this test does not prepare an enrollment"
        }
        val ownerPid = Process.myPid()
        val uid = Process.myUid()
        val ready = CopyOnWriteArrayList<Long>()
        val failures = CopyOnWriteArrayList<Pair<Long, VoskStreamingError>>()
        val transcriber = VoskStreamingTranscriber(
            context = context,
            modelDirectory = directory,
            isInputSuppressed = { false },
            inputGeneration = { 1L },
            homeWakeEndpointing = true,
            listener = object : VoskStreamingListener {
                override fun onReady(runId: Long) { ready.add(runId) }
                override fun onTranscript(runId: Long, transcript: VoskTranscript) = Unit
                override fun onError(runId: Long, error: VoskStreamingError) { failures.add(runId to error) }
            },
        )
        ActivityScenario.launch(RuntimeCalibrationTestHostActivity::class.java).use { scenario ->
            try {
                waitFor(5_000L) {
                    var focused = false
                    scenario.onActivity { focused = it.hasWindowFocus() }
                    focused
                }
                val firstRun = checkNotNull(transcriber.start())
                waitFor(20_000L) { ready.contains(firstRun) || failures.isNotEmpty() }
                assertTrue("Actual microphone and remote decoder must start", ready.contains(firstRun))
                assertTrue(failures.isEmpty())
                assertTrue(transcriber.isRunning())
                val leaseProbe = Any()
                val unexpectedlyFree = VoiceMicrophoneLease.process.acquire(leaseProbe)
                if (unexpectedlyFree) VoiceMicrophoneLease.process.release(leaseProbe)
                assertFalse("The real capture owns the process microphone lease", unexpectedlyFree)
                val decoder = requireNotNull(context.getSystemService(ActivityManager::class.java).runningAppProcesses)
                    .single { it.processName == context.packageName + VoskDecoderProtocol.PROCESS_SUFFIX }
                assertEquals(uid, decoder.uid)
                assertNotEquals(ownerPid, decoder.pid)
                // Exercise Android's native-crash handling as well as Binder death, in our helper.
                Process.sendSignal(decoder.pid, 6) // SIGABRT; the original Kaldi calculation is not replayed.
                waitFor(10_000L) { failures.isNotEmpty() }
                VoskStreamingWorker.process.submit {}.get(10, TimeUnit.SECONDS)
                assertEquals(listOf(firstRun to VoskStreamingError.INFERENCE_FAILED), failures.toList())
                assertFalse(transcriber.isRunning())
                assertEquals(ownerPid, Process.myPid())
                scenario.onActivity { assertTrue("Owner UI must survive decoder death", it.hasWindowFocus()) }
                assertTrue("AudioRecord release must return the microphone lease", VoiceMicrophoneLease.process.acquire(leaseProbe))
                VoiceMicrophoneLease.process.release(leaseProbe)

                // Retry is an explicit caller action, after the original release completed.
                val retryRun = checkNotNull(transcriber.start())
                assertNotEquals(firstRun, retryRun)
                waitFor(20_000L) { ready.contains(retryRun) || failures.size > 1 }
                assertTrue("A fresh decoder and capture must start on explicit retry", ready.contains(retryRun))
                assertTrue(transcriber.isRunning())
                transcriber.stop()
                VoskStreamingWorker.process.submit {}.get(10, TimeUnit.SECONDS)
                assertFalse(transcriber.isRunning())
                assertEquals("Old callbacks and deliberate stop must not duplicate the failure", 1, failures.size)
                assertTrue(VoiceMicrophoneLease.process.acquire(leaseProbe))
                VoiceMicrophoneLease.process.release(leaseProbe)
                println("VOSK_DECODER_ISOLATION " + JSONObject()
                    .put("owner_pid", ownerPid).put("decoder_pid_killed", decoder.pid).put("same_uid", uid)
                    .put("fault_signal", "SIGABRT").put("fault_signal_number", 6)
                    .put("failure_count", failures.size).put("first_run", firstRun).put("retry_run", retryRun)
                    .put("owner_ui_survived", true).put("microphone_lease_returned", true)
                    .put("original_kaldi_assertion_reproduced", false).put("human_recognition_proved", false))
            } finally {
                transcriber.close()
                VoskStreamingWorker.process.submit {}.get(10, TimeUnit.SECONDS)
            }
        }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(20L)
        }
        assertTrue("Condition must complete within its original bound", condition())
    }
}
