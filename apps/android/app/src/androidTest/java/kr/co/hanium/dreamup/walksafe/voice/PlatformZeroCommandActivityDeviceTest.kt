package kr.co.hanium.dreamup.walksafe.voice

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
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
 * Synthetic final candidates exercise the real Main policy and real TTS twice.
 * This does not start recognition, collect microphone audio, or measure ASR accuracy.
 */
@RunWith(AndroidJUnit4::class)
class PlatformZeroCommandActivityDeviceTest {
    @Test
    fun platformZeroHelpCompletesRealSpeechAndCanRepeat() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(
            "BLOCKED: allowPlatformZeroCommandActivityTest=true is required",
            InstrumentationRegistry.getArguments()
                .getString("allowPlatformZeroCommandActivityTest").toBoolean(),
        )
        assertTrue("BLOCKED: DEBUG metadata events are required", BuildConfig.DEBUG)
        val logReader = Executors.newSingleThreadExecutor()
        var openedActivity: MainActivity? = null
        try {
            val activity = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            openedActivity = activity
            val preflightDeadline = SystemClock.elapsedRealtime() + 20_000L
            DeviceTestAccountUiLogin.ensureLoggedIn(
                instrumentation,
                activity,
                preflightDeadline,
            )
            awaitActualHomeReadiness(instrumentation, activity, preflightDeadline)
            onMain(instrumentation) {
                val page = mainField(activity, "nativeUiPage") as Enum<*>
                val voicePage = page.javaClass.enumConstants.single {
                    (it as Enum<*>).name == "VOICE_COMMAND"
                }
                MainActivity::class.java.declaredMethods.single {
                    it.name == "showNativeUiPage" && it.parameterCount == 1
                }.apply { isAccessible = true }.invoke(activity, voicePage)
            }
            while (onMain(instrumentation) {
                    mainBoolean(activity, "isHandsFreeVoiceOutputActive")
                } && SystemClock.elapsedRealtime() < preflightDeadline
            ) {
                SystemClock.sleep(100L)
            }
            onMain(instrumentation) {
                assertFalse(
                    "BLOCKED: previous app speech is still active",
                    mainBoolean(activity, "isHandsFreeVoiceOutputActive"),
                )
            }

            val candidateHandler = MainActivity::class.java.declaredMethods.single {
                it.name == "handleVoiceCommandPhrases" && it.parameterCount == 7
            }.apply { isAccessible = true }
            val runId = SystemClock.elapsedRealtimeNanos()
            for (ordinal in 1..2) {
                onMain(instrumentation) {
                    assertTrue(
                        "BLOCKED: actual HOME voice gate changed before fixture",
                        mainBoolean(activity, "homeVoiceCommandAvailable"),
                    )
                    assertEquals(
                        "VOICE_COMMAND",
                        (mainField(activity, "nativeUiPage") as Enum<*>).name,
                    )
                    assertFalse(
                        "Previous speech must finish before the next explicit request",
                        mainBoolean(activity, "isHandsFreeVoiceOutputActive"),
                    )
                    val decisionToken = currentNavigationDecisionToken(activity)
                    // The marker and dispatch share the main-thread turn. No old queued
                    // completion can interleave before the new response is registered.
                    Log.d(TAG, "event=SYNTHETIC_ZERO_FIXTURE_BEGIN run_id=$runId ordinal=$ordinal")
                    candidateHandler.invoke(
                        activity,
                        listOf("도움말", "도움말"),
                        floatArrayOf(0f, 0f),
                        decisionToken,
                        false,
                        "synthetic_platform_zero_fixture",
                        OfflineSpeechEngine.PLATFORM,
                        false,
                    )
                }
                awaitRealSpeechCompletion(logReader, runId, ordinal)
                // No debounce sleep or synthetic completion: repeat immediately after
                // the real OUTPUT_DONE so silent duplicate suppression remains a failure.
            }
        } finally {
            logReader.shutdownNow()
            openedActivity?.let { activity ->
                instrumentation.runOnMainSync { activity.finish() }
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

    private fun awaitRealSpeechCompletion(
        reader: ExecutorService,
        runId: Long,
        ordinal: Int,
    ) {
        val start = SystemClock.elapsedRealtime()
        val deadline = start + 60_000L
        var events = OutputEvents()
        while (SystemClock.elapsedRealtime() < deadline) {
            events = readOwnMetadataEvents(reader, runId, ordinal)
            assertEquals("Real speech failed for fixture $ordinal: $events", 0, events.failed)
            assertEquals("Speech dispatch was rejected for fixture $ordinal: $events", 0, events.rejected)
            if (events.done > 0) {
                assertEquals("One actual terminal completion is required: $events", 1, events.done)
                assertEquals("The ZERO preview branch must be used: $events", 1, events.preview)
                assertEquals("The real command selector must select Help: $events", 1, events.selected)
                assertEquals("The actual command execution entry must be reached: $events", 1, events.executed)
                assertEquals("Dispatch is required but is not completion: $events", 1, events.accepted)
                return
            }
            assertTrue(
                "BLOCKED: own-PID DEBUG event marker could not be observed",
                events.markerSeen || SystemClock.elapsedRealtime() - start < 5_000L,
            )
            SystemClock.sleep(200L)
        }
        throw AssertionError("No actual OUTPUT_DONE for fixture $ordinal within 60 seconds: $events")
    }

    private fun readOwnMetadataEvents(
        reader: ExecutorService,
        runId: Long,
        ordinal: Int,
    ): OutputEvents {
        // Do not use UiAutomation: connecting it can suppress accessibility services
        // and change TTS behavior. Only this process and this metadata tag are read.
        val process = ProcessBuilder(
            "logcat", "-d", "-b", "main", "-v", "brief",
            "--pid=" + Process.myPid(), TAG + ":D", "*:S",
        ).redirectErrorStream(true).start()
        val read = reader.submit<OutputEvents> {
            var events = OutputEvents()
            process.inputStream.bufferedReader().use { lines ->
                lines.forEachLine { line ->
                    val event = line.substringAfter("event=", "").substringBefore(' ')
                    if (event == "SYNTHETIC_ZERO_FIXTURE_BEGIN" &&
                        line.contains("run_id=$runId ") && line.trimEnd().endsWith("ordinal=$ordinal")
                    ) {
                        events = OutputEvents(markerSeen = true)
                    } else if (events.markerSeen) {
                        when (event) {
                            "PLATFORM_CANDIDATE_POLICY" -> if (line.contains("disposition=PREVIEW_ONLY")) {
                                events.preview++
                            }
                            "COMMAND_SELECTED" -> if (line.contains("matched=true")) events.selected++
                            "COMMAND_EXECUTION_REQUESTED" -> events.executed++
                            "OUTPUT_DISPATCH" -> when {
                                line.contains("result=ACCEPTED") -> events.accepted++
                                line.contains("result=UNAVAILABLE") || line.contains("result=SUPPRESSED") ->
                                    events.rejected++
                            }
                            "OUTPUT_DONE" -> events.done++
                            "OUTPUT_FAILED" -> events.failed++
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

    private fun currentNavigationDecisionToken(activity: MainActivity): Any? {
        val navigator = mainField(activity, "routeNavigator") ?: return null
        val method = navigator.javaClass.methods.single {
            it.name.substringBefore('$') == "pendingDecisionToken" && it.parameterCount == 0
        }
        return method.invoke(navigator)
    }

    private fun mainField(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.get(activity)

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

    private data class OutputEvents(
        val markerSeen: Boolean = false,
        var preview: Int = 0,
        var selected: Int = 0,
        var executed: Int = 0,
        var accepted: Int = 0,
        var rejected: Int = 0,
        var done: Int = 0,
        var failed: Int = 0,
    )

    private companion object {
        const val TAG = "WalkSafeVoiceInput"
    }
}

