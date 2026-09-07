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
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.session.DeviceTestAccountUiLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Synthetic final input, real account gates, current-location lookup, Gateway search and feedback.
 * No ASR accuracy is measured. Candidate speech acceptance is not a successful TTS terminal receipt.
 */
@RunWith(AndroidJUnit4::class)
class PlatformZeroDestinationPreviewDeviceTest {
    @Test
    fun platformZeroDestinationReturnsPreviewAndQueuesFeedbackWithoutStartingRoute() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(
            "BLOCKED: allowPlatformZeroDestinationPreviewTest=true is required",
            InstrumentationRegistry.getArguments()
                .getString("allowPlatformZeroDestinationPreviewTest").toBoolean(),
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
            DeviceTestAccountUiLogin.ensureLoggedIn(instrumentation, activity, preflightDeadline)
            awaitActualHomeReadiness(instrumentation, activity, preflightDeadline)
            val before = onMain(instrumentation) {
                val page = mainField(activity, "nativeUiPage") as Enum<*>
                val voicePage = page.javaClass.enumConstants.single {
                    (it as Enum<*>).name == "VOICE_COMMAND"
                }
                MainActivity::class.java.declaredMethods.single {
                    it.name == "showNativeUiPage" && it.parameterCount == 1
                }.apply { isAccessible = true }.invoke(activity, voicePage)
                Baseline(
                    searchGeneration = (mainField(activity, "destinationSearchGeneration") as Number).toLong(),
                    routeRequestGeneration = (mainField(activity, "routeRequestGeneration") as Number).toInt(),
                    destination = mainField(activity, "currentDestination"),
                    pendingDestination = mainField(activity, "pendingUiDestination"),
                    routeActive = mainField(activity, "isRouteActive") as Boolean,
                    routeRequestInFlight = (mainField(activity, "routeRequestInFlight") as AtomicBoolean).get(),
                    interactionAtMs = mainField(activity, "feedbackActuator")?.let {
                        field(it, "lastInteractionMessageAtMs")
                    },
                )
            }
            val candidateHandler = MainActivity::class.java.declaredMethods.single {
                it.name == "handleVoiceCommandPhrases" && it.parameterCount == 7
            }.apply { isAccessible = true }
            val runId = SystemClock.elapsedRealtimeNanos()
            onMain(instrumentation) {
                assertTrue("BLOCKED: actual HOME voice gate changed", mainBoolean(activity, "homeVoiceCommandAvailable"))
                assertEquals("VOICE_COMMAND", (mainField(activity, "nativeUiPage") as Enum<*>).name)
                assertFalse("BLOCKED: previous app speech is active", mainBoolean(activity, "isHandsFreeVoiceOutputActive"))
                Log.d(TAG, "event=SYNTHETIC_ZERO_DESTINATION_FIXTURE_BEGIN run_id=$runId")
                candidateHandler.invoke(
                    activity,
                    listOf(FINAL_PHRASE, FINAL_PHRASE),
                    floatArrayOf(0f, 0f),
                    currentNavigationDecisionToken(activity),
                    false,
                    "synthetic_platform_zero_fixture",
                    OfflineSpeechEngine.PLATFORM,
                    false,
                )
            }
            val deadline = SystemClock.elapsedRealtime() + 60_000L
            var observed = PreviewObservation()
            while (SystemClock.elapsedRealtime() < deadline) {
                observed = onMain(instrumentation) { observePreview(activity, before) }
                assertTrue("Preview must not select a destination or start a route", observed.routeUnchanged)
                if (observed.ready) break
                SystemClock.sleep(100L)
            }
            assertTrue("Real destination preview/feedback was not ready: $observed", observed.ready)
            val events = readOwnMetadataEvents(logReader, runId)
            assertTrue("BLOCKED: own-PID fixture marker was not observed", events.markerSeen)
            assertEquals("The ZERO preview policy must be used", 1, events.preview)
            assertEquals("The real selector must select the command", 1, events.selected)
            assertEquals("One command execution must reach Main", 1, events.executed)
            // Do not interrupt the real candidate audio at the end of the test. Idle is
            // deliberately not reported as successful delivery: cancellation also becomes idle.
            while (onMain(instrumentation) { mainBoolean(activity, "isHandsFreeVoiceOutputActive") } &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                SystemClock.sleep(100L)
            }
            onMain(instrumentation) {
                assertFalse("Candidate output did not settle", mainBoolean(activity, "isHandsFreeVoiceOutputActive"))
                assertTrue("Preview changed route/selection state", routeUnchanged(activity, before))
            }
            Log.i(
                TAG,
                "event=SYNTHETIC_ZERO_DESTINATION_PREVIEW_RESULT candidates_returned=true " +
                    "candidate_feedback=ACCEPTED candidate_tts_terminal=UNVERIFIED route_unchanged=true",
            )
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
                    locationGranted = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED ||
                        activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED,
                    idle = !mainBoolean(activity, "isHandsFreeVoiceOutputActive") &&
                        mainField(activity, "voiceRecognitionActive") == false &&
                        mainField(activity, "voiceCommandPromptPending") == false &&
                        mainField(activity, "destinationSearchInFlight") == false,
                )
            }
            if (observed.ready) return
            if (SystemClock.elapsedRealtime() >= deadlineMs) break
            SystemClock.sleep(100L)
        } while (true)
        assertTrue("BLOCKED: actual account/education/input/location readiness $observed", observed.ready)
    }

    private fun observePreview(activity: MainActivity, before: Baseline): PreviewObservation {
        val results = mainField(activity, "destinationSearchResults") as List<*>
        val voiceState = mainField(activity, "destinationSearchVoiceState")
        val voiceQueryMatches = voiceState != null && field(voiceState, "query") == QUERY
        val voiceResultsMatch = voiceState != null && field(voiceState, "results") == results
        val prompt = if (voiceQueryMatches && voiceResultsMatch) {
            voiceState!!.javaClass.methods.single {
                it.name.substringBefore('$') == "voicePrompt" && it.parameterCount == 0
            }.invoke(voiceState) as String
        } else {
            null
        }
        val actuator = mainField(activity, "feedbackActuator")
        // Prompt text remains in memory. Never pass it or the candidate objects to assertion output.
        val feedbackAccepted = prompt != null && actuator != null &&
            field(actuator, "lastInteractionMessage") == prompt &&
            field(actuator, "lastInteractionMessageAtMs") != before.interactionAtMs
        return PreviewObservation(
            newGeneration = (mainField(activity, "destinationSearchGeneration") as Number).toLong() >
                before.searchGeneration,
            searchFinished = mainField(activity, "destinationSearchInFlight") == false,
            queryMatches = mainField(activity, "destinationSearchQuery") == QUERY,
            candidateCount = results.size,
            voiceQueryMatches = voiceQueryMatches,
            voiceResultsMatch = voiceResultsMatch,
            pendingVoiceQueryCleared = mainField(activity, "pendingVoiceDestinationQuery") == null,
            approvedCandidatePresent = prompt?.contains(APPROVED_DESTINATION_NAME) == true,
            feedbackAccepted = feedbackAccepted,
            routeUnchanged = routeUnchanged(activity, before),
        )
    }

    private fun routeUnchanged(activity: MainActivity, before: Baseline): Boolean =
        (mainField(activity, "routeRequestGeneration") as Number).toInt() == before.routeRequestGeneration &&
            mainField(activity, "currentDestination") == before.destination &&
            mainField(activity, "pendingUiDestination") == before.pendingDestination &&
            mainField(activity, "isRouteActive") == before.routeActive &&
            (mainField(activity, "routeRequestInFlight") as AtomicBoolean).get() == before.routeRequestInFlight

    private fun readOwnMetadataEvents(reader: ExecutorService, runId: Long): SelectionEvents {
        // Only own-process metadata is consumed; no audio, coordinates, tokens or raw lines are saved.
        val process = ProcessBuilder(
            "logcat", "-d", "-b", "main", "-v", "brief",
            "--pid=" + Process.myPid(), TAG + ":D", "*:S",
        ).redirectErrorStream(true).start()
        val read = reader.submit<SelectionEvents> {
            var events = SelectionEvents()
            process.inputStream.bufferedReader().use { lines ->
                lines.forEachLine { line ->
                    val event = line.substringAfter("event=", "").substringBefore(' ')
                    if (event == "SYNTHETIC_ZERO_DESTINATION_FIXTURE_BEGIN" &&
                        line.trimEnd().endsWith("run_id=$runId")
                    ) {
                        events = SelectionEvents(markerSeen = true)
                    } else if (events.markerSeen) {
                        when (event) {
                            "PLATFORM_CANDIDATE_POLICY" -> if (line.contains("disposition=PREVIEW_ONLY")) events.preview++
                            "COMMAND_SELECTED" -> if (line.contains("matched=true")) events.selected++
                            "COMMAND_EXECUTION_REQUESTED" -> events.executed++
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
        return navigator.javaClass.methods.single {
            it.name.substringBefore('$') == "pendingDecisionToken" && it.parameterCount == 0
        }.invoke(navigator)
    }

    private fun mainField(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private fun mainBoolean(activity: MainActivity, name: String): Boolean =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity) as Boolean

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

    private class Baseline(
        val searchGeneration: Long,
        val routeRequestGeneration: Int,
        val destination: Any?,
        val pendingDestination: Any?,
        val routeActive: Boolean,
        val routeRequestInFlight: Boolean,
        val interactionAtMs: Any?,
    )

    private data class Preconditions(
        val focused: Boolean,
        val onboarding: Boolean,
        val homeContext: Boolean,
        val voiceAvailable: Boolean,
        val microphoneGranted: Boolean,
        val locationGranted: Boolean,
        val idle: Boolean,
    ) {
        val ready: Boolean
            get() = focused && onboarding && homeContext && voiceAvailable && microphoneGranted && locationGranted && idle
    }

    private data class PreviewObservation(
        val newGeneration: Boolean = false,
        val searchFinished: Boolean = false,
        val queryMatches: Boolean = false,
        val candidateCount: Int = 0,
        val voiceQueryMatches: Boolean = false,
        val voiceResultsMatch: Boolean = false,
        val pendingVoiceQueryCleared: Boolean = false,
        val approvedCandidatePresent: Boolean = false,
        val feedbackAccepted: Boolean = false,
        val routeUnchanged: Boolean = false,
    ) {
        val ready: Boolean
            get() = newGeneration && searchFinished && queryMatches && candidateCount > 0 &&
                voiceQueryMatches && voiceResultsMatch && pendingVoiceQueryCleared &&
                approvedCandidatePresent && feedbackAccepted && routeUnchanged
    }

    private data class SelectionEvents(
        val markerSeen: Boolean = false,
        var preview: Int = 0,
        var selected: Int = 0,
        var executed: Int = 0,
    )

    private companion object {
        const val TAG = "WalkSafeVoiceInput"
        const val QUERY = "금오공대"
        const val FINAL_PHRASE = "금오공대 목적지로 해줘"
        const val APPROVED_DESTINATION_NAME = "국립금오공과대학교"
    }
}
