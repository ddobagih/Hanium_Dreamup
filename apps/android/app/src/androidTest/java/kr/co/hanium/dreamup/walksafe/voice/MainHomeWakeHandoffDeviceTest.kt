package kr.co.hanium.dreamup.walksafe.voice

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.session.DeviceTestAccountUiLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real HOME microphone ready -> explicitly synthetic wake final -> real preparation TTS
 * completion -> real one-shot ready -> cancel -> real HOME ready again.
 * The final fixture bypasses acoustic recognition; no human accuracy claim or audio storage.
 * Account, education, disclosure, microphone permission and current capability gates stay real.
 */
@RunWith(AndroidJUnit4::class)
class MainHomeWakeHandoffDeviceTest {
    @Test
    fun actualHomeReadyHandsOffSyntheticWakeAndCanReturnHome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(
            "BLOCKED: allowMainHomeWakeHandoffTest=true is required for actual microphone lifecycle",
            InstrumentationRegistry.getArguments()
                .getString("allowMainHomeWakeHandoffTest").toBoolean(),
        )
        assertTrue("BLOCKED: own-PID DEBUG metadata is required", BuildConfig.DEBUG)
        val reader = Executors.newSingleThreadExecutor()
        var openedActivity: MainActivity? = null
        try {
            val activity = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            openedActivity = activity
            val preflightDeadline = SystemClock.elapsedRealtime() + 20_000L
            DeviceTestAccountUiLogin.ensureLoggedIn(instrumentation, activity, preflightDeadline)
            awaitHomeEligibility(instrumentation, activity, preflightDeadline)
            val runId = SystemClock.elapsedRealtimeNanos()
            onMain(instrumentation) {
                Log.d(TAG, "event=HOME_WAKE_TEST_BEGIN run_id=$runId ordinal=1")
                showPage(activity, "HOME")
            }
            awaitEvents(reader, runId, 1, 35_000L) { it.homeReady > 0 }.also {
assertTrue("A real HOME attempt is required: $it", it.homeRequests >= 1)
                assertEquals("No handoff may happen before the explicit fixture: $it", 0, it.handoffs)
            }

            val target = onMain(instrumentation) {
                assertEquals("HOME", currentPage(activity))
                assertTrue("HOME authorization changed", mainBoolean(activity, "homeVoiceCommandAvailable"))
                assertFalse("One-shot cannot already own the microphone", mainField(activity, "voiceRecognitionActive") as Boolean)
                val probe = checkNotNull(mainField(activity, "foregroundHomeWakeProbe")) {
                    "Actual HOME probe disappeared after ready"
                }
                val run = (probeField(probe, "activeRunId") as? Number)?.toLong()
                assertTrue("Actual ready must assign a live Vosk run", run != null && run > 0L)
                val listener = probeField(probe, "listener") as VoskStreamingListener
                Log.d(TAG, "event=HOME_WAKE_TEST_SYNTHETIC_FINAL run_id=$runId ordinal=1")
                listener to checkNotNull(run)
            }
            // Called from the instrumentation thread, not Main and not a fake onResult.
            // Production HOME mode must dispatch cleanup before invoking its real Main owner.
            target.first.onTranscript(
                target.second,
                VoskTranscript(text = "길라잡이", confidence = 0.95f, isFinal = true),
            )
            val handoff = awaitEvents(reader, runId, 1, 45_000L) { it.uiReadyAt > 0 }
assertTrue("A current HOME ready callback is required: $handoff", handoff.homeReady >= 1)
            assertEquals("One wake handoff is required: $handoff", 1, handoff.handoffs)
            assertEquals("One explicit one-shot request is required: $handoff", 1, handoff.requests)
            assertEquals("One actual recognizer ready is required: $handoff", 1, handoff.ready)
            assertTrue("Synthetic final must follow actual HOME ready: $handoff", handoff.syntheticAt > handoff.homeReadyAt)
            assertTrue("Handoff must follow the fixture: $handoff", handoff.handoffAt > handoff.syntheticAt)
            assertEquals("Exactly one wake cue must be requested: $handoff", 1, handoff.acknowledgements)
            assertEquals("The actual cue API must complete its bounded interval: $handoff", "COMPLETED", handoff.acknowledgementResult)
            assertEquals("Exactly one cue terminal callback is required: $handoff", 1, handoff.acknowledgementTerminals)
            assertTrue("The cue must finish after handoff: $handoff", handoff.acknowledgementTerminalAt > handoff.handoffAt)
            assertTrue("Recognition may start only after the cue releases its output: $handoff", handoff.requestAt > handoff.acknowledgementTerminalAt)
            assertTrue("Actual recognition ready must follow its request: $handoff", handoff.readyAt > handoff.requestAt)
            assertTrue("Current Main ready must follow actual backend ready: $handoff", handoff.uiReadyAt > handoff.readyAt)
            assertTrue("An actual offline backend must be identified: $handoff", handoff.backend != null)

            onMain(instrumentation) {
                assertEquals("VOICE_COMMAND", currentPage(activity))
                assertEquals("HOME owner must be gone before one-shot listening", null, mainField(activity, "foregroundHomeWakeProbe"))
                assertTrue("One-shot ended before explicit cancellation; no accuracy verdict", mainField(activity, "voiceRecognitionActive") as Boolean)
                invokeNoArgs(activity, "cancelVoiceCommandRecognition")
                assertFalse("Explicit cancellation must clear one-shot recording", mainField(activity, "voiceRecognitionActive") as Boolean)
                Log.d(TAG, "event=HOME_WAKE_TEST_BEGIN run_id=$runId ordinal=2")
                showPage(activity, "HOME")
            }
            val returned = awaitEvents(reader, runId, 2, 35_000L) { it.homeReady > 0 }
assertTrue("HOME re-entry must start an actual attempt: $returned", returned.homeRequests >= 1)
assertTrue("HOME re-entry needs a new actual ready: $returned", returned.homeReady >= 1)
            assertEquals("Returning HOME alone must not start a command: $returned", 0, returned.requests)
            instrumentation.sendStatus(0, Bundle().apply {
                putString(
                    "stream",
                    "HOME_WAKE_HANDOFF actual_home_ready=true synthetic_final=true " +
                        "actual_cue_terminal=true actual_command_ready=true home_reentry_ready=true " +
                        "backend=" + handoff.backend + " human_audibility=NOT_TESTED human_accuracy=NOT_TESTED\n",
                )
            })
        } finally {
            reader.shutdownNow()
            openedActivity?.let { activity ->
                instrumentation.runOnMainSync {
                    try {
                        invokeNoArgs(activity, "cancelVoiceCommandRecognition")
                        MainActivity::class.java.getDeclaredMethod(
                            "cancelForegroundHomeWakeListening", Boolean::class.javaPrimitiveType,
                        ).apply { isAccessible = true }.invoke(activity, false)
                    } finally {
                        activity.finish()
                    }
                }
            }
        }
    }

    private fun awaitHomeEligibility(
        instrumentation: Instrumentation,
        activity: MainActivity,
        deadlineMs: Long,
    ) {
        var state = "not_observed"
        while (true) {
            val ready = onMain(instrumentation) {
                val focused = activity.hasWindowFocus()
                val onboarding = mainBoolean(activity, "firstRunOnboardingComplete")
                val context = mainBoolean(activity, "nativeHomeFeatureContextAvailable")
                val voice = mainBoolean(activity, "homeVoiceCommandAvailable")
                val disclosure = mainBoolean(activity, "isHandsFreeVoiceDisclosureAccepted")
                val microphone = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                val model = BundledVoskModelInstaller.installedModelOrNull(activity) != null
                state = "focused=$focused onboarding=$onboarding home=$context voice=$voice " +
                    "disclosure=$disclosure microphone=$microphone installed_model=$model"
                focused && onboarding && context && voice && disclosure && microphone && model
            }
            if (ready) return
            if (SystemClock.elapsedRealtime() >= deadlineMs) break
            SystemClock.sleep(100L)
        }
        throw AssertionError("BLOCKED: real HOME prerequisites did not become ready: $state")
    }

    private fun awaitEvents(
        reader: ExecutorService,
        runId: Long,
        ordinal: Int,
        timeoutMs: Long,
        complete: (Events) -> Boolean,
    ): Events {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + timeoutMs
        var events = Events()
        while (SystemClock.elapsedRealtime() < deadline) {
            events = readEvents(reader, runId, ordinal)
            assertEquals("Actual lifecycle failure, not acoustic accuracy: $events", 0, events.failures)
            assertEquals("Speech was unavailable or suppressed: $events", 0, events.rejected)
            if (complete(events)) return events
            assertTrue(
                "BLOCKED: own-PID marker unavailable",
                events.markerSeen || SystemClock.elapsedRealtime() - startedAt < 5_000L,
            )
            SystemClock.sleep(100L)
        }
        throw AssertionError("No expected actual ready/completion before deadline: $events")
    }

    private fun readEvents(reader: ExecutorService, runId: Long, ordinal: Int): Events {
        // No UiAutomation connection, other process logs, raw transcript, or audio persistence.
        val process = ProcessBuilder(
            "logcat", "-d", "-b", "main", "-v", "brief",
            "--pid=" + Process.myPid(), TAG + ":D", "*:S",
        ).redirectErrorStream(true).start()
        val read = reader.submit<Events> {
            var events = Events()
            var position = 0
            process.inputStream.bufferedReader().use { lines ->
                lines.forEachLine { line ->
                    val event = line.substringAfter("event=", "").substringBefore(' ')
                    if (event == "HOME_WAKE_TEST_BEGIN" &&
                        line.contains("run_id=$runId ") && line.trimEnd().endsWith("ordinal=$ordinal")
                    ) {
                        events = Events(markerSeen = true)
                        position = 0
                    } else if (events.markerSeen) {
                        position++
                        when (event) {
                            "HOME_WAKE_START_REQUESTED" -> events.homeRequests++
                            "HOME_WAKE_READY" -> { events.homeReady++; events.homeReadyAt = position }
                            "HOME_WAKE_TEST_SYNTHETIC_FINAL" -> events.syntheticAt = position
                            "HOME_WAKE_HANDOFF" -> { events.handoffs++; events.handoffAt = position }
                            "HOME_WAKE_ACK_REQUESTED" -> events.acknowledgements++
                            "HOME_WAKE_ACK_TERMINAL" -> {
                                events.acknowledgementTerminals++
                                events.acknowledgementTerminalAt = position
                                events.acknowledgementResult = line.substringAfter("result=", "").substringBefore(' ')
                            }
                            "OUTPUT_DONE" -> if (events.handoffAt > 0) events.doneAt = position
                            "ONE_SHOT_REQUESTED" -> { events.requests++; events.requestAt = position }
                            "READY" -> {
                                events.ready++
                                events.readyAt = position
                                events.backend = when {
                                    line.contains("backend=PLATFORM") -> OfflineSpeechEngine.PLATFORM
                                    line.contains("backend=VOSK") -> OfflineSpeechEngine.VOSK
                                    else -> null
                                }
                            }
                            "ONE_SHOT_UI_READY" -> events.uiReadyAt = position
                            "HOME_WAKE_BLOCKED", "HOME_WAKE_TERMINAL",
                            "WAKE_TIMEOUT", "WAKE_STREAM_ERROR", "WAKE_START_FAILED" -> events.failures++
                            // Earlier cold-start speech may cancel/re-arm HOME listening.
                            // Only the actual wake handoff owns the prompt tested below.
                            "ERROR", "ONE_SHOT_UI_ERROR", "WATCHDOG", "OUTPUT_FAILED" ->
                                if (events.handoffAt > 0) events.failures++
                            "OUTPUT_DISPATCH" -> if (
                                events.handoffAt > 0 &&
                                (line.contains("result=UNAVAILABLE") || line.contains("result=SUPPRESSED"))
                            ) events.rejected++
                        }
                    }
                }
            }
            events
        }
        return try {
            read.get(3L, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            throw AssertionError("BLOCKED: own-PID metadata log read timed out")
        } finally {
            read.cancel(true)
            process.destroy()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    private fun showPage(activity: MainActivity, name: String) {
        val method = MainActivity::class.java.declaredMethods.single {
            it.name == "showNativeUiPage" && it.parameterCount == 1
        }.apply { isAccessible = true }
        val page = method.parameterTypes[0].enumConstants.single { (it as Enum<*>).name == name }
        method.invoke(activity, page)
    }

    private fun currentPage(activity: MainActivity): String =
        (mainField(activity, "nativeUiPage") as Enum<*>).name

    private fun probeField(probe: Any, name: String): Any? =
        VoskWakePhraseProbe::class.java.getDeclaredField(name).apply { isAccessible = true }.get(probe)

    private fun mainField(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun invokeNoArgs(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)

    private fun mainBoolean(activity: MainActivity, name: String): Boolean =
        invokeNoArgs(activity, name) as Boolean

    private fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                result = action()
            } catch (caught: Throwable) {
                failure = caught
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }


    private data class Events(
        val markerSeen: Boolean = false,
        var homeRequests: Int = 0,
        var homeReady: Int = 0,
        var homeReadyAt: Int = 0,
        var syntheticAt: Int = 0,
        var handoffs: Int = 0,
        var handoffAt: Int = 0,
        var acknowledgements: Int = 0,
        var acknowledgementTerminals: Int = 0,
        var acknowledgementTerminalAt: Int = 0,
        var acknowledgementResult: String? = null,
        var doneAt: Int = 0,
        var requests: Int = 0,
        var requestAt: Int = 0,
        var ready: Int = 0,
        var readyAt: Int = 0,
        var uiReadyAt: Int = 0,
        var backend: OfflineSpeechEngine? = null,
        var failures: Int = 0,
        var rejected: Int = 0,
    )

    private companion object {
        const val TAG = "WalkSafeVoiceInput"
    }
}
