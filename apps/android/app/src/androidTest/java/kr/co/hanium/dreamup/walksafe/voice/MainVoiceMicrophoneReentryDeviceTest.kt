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
 * Opt-in real microphone lifecycle only: prompt completion, actual recognizer ready,
 * explicit cancellation, HOME return, and re-entry. No transcript is injected or saved.
 * A ready callback does not prove useful PCM, human recognition accuracy, or a command result.
 */
@RunWith(AndroidJUnit4::class)
class MainVoiceMicrophoneReentryDeviceTest {
    @Test
    fun actualReadyCanCancelReturnHomeAndStartAgain() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(
            "BLOCKED: allowActualMicrophoneLifecycleTest=true is required",
            InstrumentationRegistry.getArguments()
                .getString("allowActualMicrophoneLifecycleTest").toBoolean(),
        )
        assertTrue("BLOCKED: DEBUG metadata events are required", BuildConfig.DEBUG)
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
            awaitActualHomeReadiness(instrumentation, activity, preflightDeadline)
            onMain(instrumentation) { showPage(activity, "HOME") }

            val start = MainActivity::class.java.declaredMethods.single {
                it.name == "startVoiceCommandRecognition" && it.parameterCount == 2
            }.apply { isAccessible = true }
            val commandPurpose = start.parameterTypes[0].enumConstants.single {
                (it as Enum<*>).name == "COMMAND"
            }
            val runId = SystemClock.elapsedRealtimeNanos()
            for (ordinal in 1..2) {
                onMain(instrumentation) {
                    assertTrue(
                        "BLOCKED: actual HOME voice gate is unavailable before entry",
                        mainBoolean(activity, "homeVoiceCommandAvailable"),
                    )
                    assertEquals("HOME", currentPage(activity))
                    assertFalse("No prior one-shot may remain active", recognitionActive(activity))
                    showPage(activity, "VOICE_COMMAND")
                    assertEquals("VOICE_COMMAND", currentPage(activity))
                    Log.d(TAG, "event=MICROPHONE_REENTRY_BEGIN run_id=$runId ordinal=$ordinal")
                    // The String is the optional takeover operation ID, not prompt text.
                    // COMMAND/null retains the real default preparation prompt and token.
                    start.invoke(activity, commandPurpose, null)
                }
                val ready = awaitReady(reader, runId, ordinal)
                onMain(instrumentation) {
                    assertTrue(
                        "Recognizer ended before the explicit cancel; no accuracy verdict",
                        recognitionActive(activity),
                    )
                    cancelRecognition(activity)
                    assertFalse("Cancellation must clear Main recording state", recognitionActive(activity))
                    showPage(activity, "HOME")
                    assertEquals("HOME", currentPage(activity))
                    assertTrue(
                        "Cancellation must not permanently limit HOME voice input",
                        mainBoolean(activity, "homeVoiceCommandAvailable"),
                    )
                }
                awaitCancellation(reader, runId, ordinal)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString(
                        "stream",
                        "MIC_LIFECYCLE ordinal=$ordinal backend=" + ready.backend +
                            " actual_ready=true cancelled=true human_accuracy=NOT_TESTED\n",
                    )
                })
            }
        } finally {
            reader.shutdownNow()
            openedActivity?.let { activity ->
                instrumentation.runOnMainSync {
                    try {
                        cancelRecognition(activity)
                    } finally {
                        activity.finish()
                    }
                }
            }
        }
    }

    private fun awaitActualHomeReadiness(
        instrumentation: Instrumentation,
        activity: MainActivity,
        deadlineMs: Long,
    ) {
        var observed: Preconditions
        do {
            observed = onMain(instrumentation) {
                Preconditions(
                    focused = activity.hasWindowFocus(),
                    onboarding = mainBoolean(activity, "firstRunOnboardingComplete"),
                    homeContext = mainBoolean(activity, "nativeHomeFeatureContextAvailable"),
                    voiceAvailable = mainBoolean(activity, "homeVoiceCommandAvailable"),
                    microphoneGranted = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED,
                )
            }
            if (observed.ready) return
            if (SystemClock.elapsedRealtime() >= deadlineMs) break
            SystemClock.sleep(100L)
        } while (true)
        assertTrue("BLOCKED: actual account/education/capability readiness $observed", observed.ready)
    }

    private fun awaitReady(reader: ExecutorService, runId: Long, ordinal: Int): Events {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + 45_000L
        var events = Events()
        while (SystemClock.elapsedRealtime() < deadline) {
            events = readEvents(reader, runId, ordinal)
            assertNoFailure(events)
            if (events.uiReadyAt > 0) {
                assertEquals("One explicit recognition request is required: $events", 1, events.requests)
                assertEquals("One actual recognizer ready is required: $events", 1, events.ready)
                assertEquals("One current Main ready callback is required: $events", 1, events.uiReady)
                assertTrue("The actual preparation TTS must finish first: $events", events.doneAt > 0)
                assertTrue("TTS completion must precede recognition request: $events", events.requestAt > events.doneAt)
                assertTrue("Actual ready must follow the request: $events", events.readyAt > events.requestAt)
                assertTrue("Main ready must follow recognizer ready: $events", events.uiReadyAt > events.readyAt)
                assertTrue("The selected offline backend must be identified: $events", events.backend != null)
                assertEquals("No injected or prematurely ended result is part of this test: $events", 0, events.finals)
                return events
            }
            assertTrue(
                "BLOCKED: own-PID DEBUG marker could not be observed",
                events.markerSeen || SystemClock.elapsedRealtime() - startedAt < 5_000L,
            )
            SystemClock.sleep(100L)
        }
        throw AssertionError("No actual recognizer/Main ready within 45 seconds: $events")
    }

    private fun awaitCancellation(reader: ExecutorService, runId: Long, ordinal: Int) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        var events = Events()
        while (SystemClock.elapsedRealtime() < deadline) {
            events = readEvents(reader, runId, ordinal)
            assertNoFailure(events)
            if (events.cancelled == 1) return
            SystemClock.sleep(100L)
        }
        throw AssertionError("No active recognizer cancellation was observed: $events")
    }

    private fun assertNoFailure(events: Events) {
        assertEquals("Actual input/output lifecycle failed, not an accuracy verdict: $events", 0, events.failures)
        assertEquals("The preparation speech was not accepted: $events", 0, events.rejected)
    }

    private fun readEvents(reader: ExecutorService, runId: Long, ordinal: Int): Events {
        // Avoid UiAutomation connections that can disturb TalkBack/TTS.
        // Only own-PID metadata is counted; raw lines and audio are never persisted.
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
                    if (event == "MICROPHONE_REENTRY_BEGIN" &&
                        line.contains("run_id=$runId ") && line.trimEnd().endsWith("ordinal=$ordinal")
                    ) {
                        events = Events(markerSeen = true)
                        position = 0
                    } else if (events.markerSeen) {
                        position++
                        when (event) {
                            "OUTPUT_DONE" -> events.doneAt = position
                            "ONE_SHOT_REQUESTED" -> {
                                events.requests++
                                events.requestAt = position
                            }
                            "READY" -> {
                                events.ready++
                                events.readyAt = position
                                events.backend = when {
                                    line.contains("backend=PLATFORM") -> OfflineSpeechEngine.PLATFORM
                                    line.contains("backend=VOSK") -> OfflineSpeechEngine.VOSK
                                    else -> null
                                }
                            }
                            "ONE_SHOT_UI_READY" -> {
                                events.uiReady++
                                events.uiReadyAt = position
                            }
                            "CANCELLED" -> events.cancelled++
                            "FINAL_RECEIVED" -> events.finals++
                            "ERROR", "ONE_SHOT_UI_ERROR", "WATCHDOG", "OUTPUT_FAILED" -> events.failures++
                            "OUTPUT_DISPATCH" -> if (
                                line.contains("result=UNAVAILABLE") || line.contains("result=SUPPRESSED")
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

    private fun recognitionActive(activity: MainActivity): Boolean =
        mainField(activity, "voiceRecognitionActive") as Boolean

    private fun cancelRecognition(activity: MainActivity) {
        MainActivity::class.java.getDeclaredMethod("cancelVoiceCommandRecognition").apply {
            isAccessible = true
        }.invoke(activity)
    }

    private fun mainField(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun mainBoolean(activity: MainActivity, name: String): Boolean =
        MainActivity::class.java.getDeclaredMethod(name).apply {
            isAccessible = true
        }.invoke(activity) as Boolean

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

    private data class Preconditions(
        val focused: Boolean,
        val onboarding: Boolean,
        val homeContext: Boolean,
        val voiceAvailable: Boolean,
        val microphoneGranted: Boolean,
    ) {
        val ready: Boolean
            get() = focused && onboarding && homeContext && voiceAvailable && microphoneGranted
    }

    private data class Events(
        val markerSeen: Boolean = false,
        var doneAt: Int = 0,
        var requestAt: Int = 0,
        var readyAt: Int = 0,
        var uiReadyAt: Int = 0,
        var requests: Int = 0,
        var ready: Int = 0,
        var uiReady: Int = 0,
        var cancelled: Int = 0,
        var finals: Int = 0,
        var failures: Int = 0,
        var rejected: Int = 0,
        var backend: OfflineSpeechEngine? = null,
    )

    private companion object {
        const val TAG = "WalkSafeVoiceInput"
    }
}

