package kr.co.hanium.dreamup.walksafe.voice

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceCommand
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceState
import kr.co.hanium.dreamup.walksafe.session.DeviceTestAccountUiLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real login, location/search, candidate TTS completion and microphone READY.
 * Only command finals are synthetic. No audio, account, position or gate is replaced.
 */
@RunWith(AndroidJUnit4::class)
class DestinationVoiceFollowUpDeviceTest {
    @Test
    fun candidateDoneStartsRealCaptureAndRetryMoreAndNumberKeepTheDialog() = withActualHomeVoice { session ->
        startExplicitInput(session)
        val initialGeneration = onMain(session) {
            (mainField(session.activity, "destinationSearchGeneration") as Number).toInt()
        }
        injectFinal(session, "SEARCH", "편의점 안내해줘")
        val firstState = requireNotNull(awaitCapture(session, "SEARCH", candidatePromptRequired = true))
        onMain(session) {
            assertTrue(
                "The actual search request must advance its generation",
                (mainField(session.activity, "destinationSearchGeneration") as Number).toInt() > initialGeneration,
            )
            // Stop the real capture before a new explicit button action, without cancelling the dialog.
            invokeMain(session.activity, "cancelVoiceCommandRecognition")
            assertTrue("Ordinary input cleanup must preserve the dialog", currentDialog(session.activity) === firstState)
            mark(session, "RETRY")
            val button = mainField(session.activity, "voiceReportButton") as Button
            assertTrue("BLOCKED: actual voice retry button is unavailable", button.isShown && button.isEnabled)
            assertTrue("The real retry button must handle the click", button.performClick())
        }
        val retryState = requireNotNull(awaitCapture(session, "RETRY", candidatePromptRequired = true))
        assertTrue("Retry must preserve the existing candidate page", retryState === firstState)
        onMain(session) {
            assertEquals("CHOICE_GUIDANCE", observeDialog(session).lastInteractionKind)
        }
        injectFinal(session, "REPLAY", "다시 듣기")
        val replayState = requireNotNull(awaitCapture(session, "REPLAY", candidatePromptRequired = true))
        assertTrue("Replay must preserve the current candidate state and page", replayState === retryState)
        onMain(session) {
            assertEquals("CURRENT_PAGE", observeDialog(session).lastInteractionKind)
        }
        assertTrue("BLOCKED: actual nearby results have no further candidate page", retryState.canHearMore)

        injectFinal(session, "MORE", "더 듣기")
        val nextState = requireNotNull(awaitCapture(session, "MORE", candidatePromptRequired = true))
        assertTrue("More must keep the same actual search query", nextState.query == QUERY)
        assertTrue("More must advance to a real candidate page", nextState.pageIndex > retryState.pageIndex)
        val selection = (1..nextState.results.size).firstNotNullOfOrNull { index ->
            nextState.onCommand(DestinationSearchVoiceCommand.SelectCandidate(index)).selectedResult?.let {
                Selection(index, it)
            }
        }
        assertTrue("The actual current page must contain a selectable number", selection != null)
        val selected = requireNotNull(selection)
        injectFinal(session, "SELECT", "${selected.index}번")
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        var confirmed = false
        while (SystemClock.elapsedRealtime() < deadline) {
            confirmed = onMain(session) {
                assertRouteUnchanged(session)
                (mainField(session.activity, "nativeUiPage") as Enum<*>).name == "DESTINATION_CONFIRM" &&
                    mainField(session.activity, "pendingUiDestination") == selected.result &&
                    currentDialog(session.activity) == null
            }
            if (confirmed) break
            SystemClock.sleep(100L)
        }
        assertTrue("Number selection must stop at explicit destination confirmation", confirmed)
        assertNoLateCapture(session, "SELECT", 10_000L)
    }

    @Test
    fun recognizerErrorsAndRejectedSelectionsKeepTheCurrentCandidatePage() = withActualHomeVoice { session ->
        startExplicitInput(session)
        injectFinal(session, "RECOVERY_SEARCH", "편의점 안내해줘")
        val original = requireNotNull(awaitCapture(session, "RECOVERY_SEARCH", candidatePromptRequired = true))
        val allowedIndices = (1..original.results.size).filter { index ->
            original.onCommand(DestinationSearchVoiceCommand.SelectCandidate(index)).selectedResult != null
        }
        assertTrue("BLOCKED: actual current page needs two distinct candidates", allowedIndices.size >= 2)

        onMain(session) {
            invokeMain(session.activity, "cancelVoiceCommandRecognition")
            assertTrue("Input cleanup must preserve the candidate page", currentDialog(session.activity) === original)
            mark(session, "RECOGNIZER_ERROR_RETRY")
            val button = mainField(session.activity, "voiceReportButton") as Button
            assertTrue("BLOCKED: actual voice retry button is unavailable", button.isShown && button.isEnabled)
            assertTrue("The real retry button must handle the click", button.performClick())
        }
        // No injected listener callback: the real microphone must report NO_MATCH or SPEECH_TIMEOUT.
        val afterError = requireNotNull(awaitCapture(
            session, "RECOGNIZER_ERROR_RETRY", candidatePromptRequired = true, recognizerErrorRequired = true,
        ))
        assertTrue("Recognizer error must preserve the same candidate state and page", afterError === original)
        assertShortRetry(session)

        injectFinal(session, "LOW_CONFIDENCE_RETRY", "${allowedIndices[0]}번", confidence = 0.01f)
        val afterLowConfidence = requireNotNull(awaitCapture(
            session, "LOW_CONFIDENCE_RETRY", candidatePromptRequired = true,
        ))
        assertTrue("Low confidence must preserve the same candidate state and page", afterLowConfidence === original)
        assertShortRetry(session)

        injectFinal(
            session, "CONFLICTING_NUMBERS_RETRY", "${allowedIndices[0]}번",
            alternativePhrase = "${allowedIndices[1]}번",
        )
        val afterConflict = requireNotNull(awaitCapture(
            session, "CONFLICTING_NUMBERS_RETRY", candidatePromptRequired = true,
        ))
        assertTrue("Conflicting numbers must preserve the same candidate state and page", afterConflict === original)
        assertShortRetry(session)

        injectFinal(session, "INVALID_NUMBER_RETRY", "${original.results.size + 1}번")
        val afterInvalidNumber = requireNotNull(awaitCapture(
            session, "INVALID_NUMBER_RETRY", candidatePromptRequired = true,
        ))
        assertTrue("Invalid number must preserve the same candidate state and page", afterInvalidNumber === original)
        assertShortRetry(session)

        val selected = requireNotNull(
            original.onCommand(DestinationSearchVoiceCommand.SelectCandidate(allowedIndices[0])).selectedResult,
        )
        injectFinal(session, "RECOVERED_SELECT", "${allowedIndices[0]}번")
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        var confirmed = false
        while (SystemClock.elapsedRealtime() < deadline) {
            confirmed = onMain(session) {
                assertRouteUnchanged(session)
                (mainField(session.activity, "nativeUiPage") as Enum<*>).name == "DESTINATION_CONFIRM" &&
                    mainField(session.activity, "pendingUiDestination") == selected &&
                    currentDialog(session.activity) == null
            }
            if (confirmed) break
            SystemClock.sleep(100L)
        }
        assertTrue("A retried number must stop at explicit destination confirmation", confirmed)
        assertNoLateCapture(session, "RECOVERED_SELECT", 10_000L)
    }

    @Test
    fun explicitCancelDuringCandidateSpeechCannotRestartCaptureLater() = withActualHomeVoice { session ->
        startExplicitInput(session)
        injectFinal(session, "SEARCH_TO_CANCEL", "편의점 안내해줘")
        val deadline = SystemClock.elapsedRealtime() + 60_000L
        var cancelled = false
        var observed = DialogObservation()
        while (SystemClock.elapsedRealtime() < deadline) {
            cancelled = onMain(session) {
                observed = observeDialog(session)
                if (!observed.current || !observed.queryMatches || observed.candidates == 0 ||
                    !observed.promptPending || observed.capturing
                ) {
                    false
                } else {
                    mark(session, "CANCEL")
                    invokeMain(session.activity, "cancelNativeVoiceCommandAndReturnHome")
                    assertEquals("HOME", (mainField(session.activity, "nativeUiPage") as Enum<*>).name)
                    assertTrue("Explicit cancellation must close the dialog", currentDialog(session.activity) == null)
                    true
                }
            }
            if (cancelled) break
            SystemClock.sleep(50L)
        }
        if (!cancelled) {
            val events = readOwnEvents(session, "SEARCH_TO_CANCEL")
            throw AssertionError(
                "A real candidate speech request must be pending before cancellation: $observed $events",
            )
        }
        assertNoLateCapture(session, "CANCEL", 12_000L)
    }

    private fun assertShortRetry(session: DeviceSession) {
        onMain(session) {
            assertEquals("RETRY_GUIDANCE", observeDialog(session).lastInteractionKind)
        }
    }

    private fun startExplicitInput(session: DeviceSession) {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        var clicked = false
        var readiness = "state=not_observed"
        while (SystemClock.elapsedRealtime() < deadline) {
            clicked = onMain(session) {
                val activity = session.activity
                val page = (mainField(activity, "nativeUiPage") as Enum<*>).name
                val button = mainField(activity, "voiceReportButton") as Button
                val shown = button.isShown
                val enabled = button.isEnabled
                val homeGate = mainBoolean(activity, "homeVoiceCommandAvailable")
                val active = mainField(activity, "voiceRecognitionActive") as Boolean
                val promptPending = mainField(activity, "voiceCommandPromptPending") as Boolean
                val modelPreparing = mainField(activity, "handsFreeVoiceModelPreparing") as Boolean
                readiness = "page=$page shown=$shown enabled=$enabled homeGate=$homeGate " +
                    "voiceRecognitionActive=$active voiceCommandPromptPending=$promptPending " +
                    "handsFreeVoiceModelPreparing=$modelPreparing"
                if (page != "VOICE_COMMAND" || !shown || !enabled || !homeGate) {
                    false
                } else {
                    mark(session, "INITIAL_INPUT")
                    assertTrue("The real voice button must handle the click", button.performClick())
                    true
                }
            }
            if (clicked) break
            SystemClock.sleep(100L)
        }
        assertTrue("BLOCKED: actual voice button readiness timed out; $readiness", clicked)
        awaitCapture(session, "INITIAL_INPUT", candidatePromptRequired = false)
    }

    private fun injectFinal(
        session: DeviceSession,
        step: String,
        phrase: String,
        confidence: Float = 0f,
        alternativePhrase: String = phrase,
    ) {
        onMain(session) {
            assertTrue("BLOCKED: actual HOME voice gate changed", mainBoolean(session.activity, "homeVoiceCommandAvailable"))
            // The previous real READY is required by the caller. Stop its resources and use the
            // existing final handler, as the real final listener does after ending capture.
            invokeMain(session.activity, "cancelVoiceCommandRecognition")
            mark(session, step)
            val navigator = mainField(session.activity, "routeNavigator")!!
            val token = navigator.javaClass.methods.single {
                it.name.substringBefore('$') == "pendingDecisionToken" && it.parameterCount == 0
            }.invoke(navigator)
            MainActivity::class.java.declaredMethods.single {
                it.name == "handleVoiceCommandPhrases" && it.parameterCount == 7
            }.apply { isAccessible = true }.invoke(
                session.activity,
                listOf(phrase, alternativePhrase),
                floatArrayOf(confidence, confidence),
                token,
                false,
                "synthetic_platform_zero_fixture",
                OfflineSpeechEngine.PLATFORM,
                false,
            )
        }
    }

    private fun observeDialog(session: DeviceSession): DialogObservation {
        assertRouteUnchanged(session)
        val state = currentDialog(session.activity)
        val actuator = mainField(session.activity, "feedbackActuator")
        val lastInteractionMessage = actuator?.let {
            it.javaClass.getDeclaredField("lastInteractionMessage").apply { isAccessible = true }.get(it)
        } as? String
        return DialogObservation(
            current = state != null,
            queryMatches = state?.query == QUERY,
            candidates = state?.results?.size ?: 0,
            searchFinished = mainField(session.activity, "destinationSearchInFlight") == false,
            capturing = mainField(session.activity, "voiceRecognitionActive") == true,
            promptPending = mainField(session.activity, "voiceCommandPromptPending") == true,
            rawQueryMatches = mainField(session.activity, "destinationSearchQuery") == QUERY,
            rawCandidateCount = (mainField(session.activity, "destinationSearchResults") as List<*>).size,
            page = (mainField(session.activity, "nativeUiPage") as Enum<*>).name,
            isActivityForeground = mainField(session.activity, "isActivityForeground") == true,
            nativeHomeContext = mainBoolean(session.activity, "nativeHomeFeatureContextAvailable"),
            leasePresent = mainField(session.activity, "homeDestinationVoiceDialogLease") != null,
            lastInteractionKind = when {
                lastInteractionMessage == null -> "UNOBSERVED"
                lastInteractionMessage == "인식하지 못하였습니다. 다시 듣기 또는 해당 번호를 말해주세요." ->
                    "RETRY_GUIDANCE"
                lastInteractionMessage == "다시 듣기 또는 해당 번호를 말해주세요." -> "CHOICE_GUIDANCE"
                state != null && lastInteractionMessage == state.voicePrompt() -> "CURRENT_PAGE"
                lastInteractionMessage == "먼저 목적지를 검색해 주세요." -> "NEEDS_SEARCH"
                lastInteractionMessage.startsWith("더 안내할 목적지 후보가 없습니다.") -> "NO_MORE"
                else -> "OTHER"
            },
        )
    }

    private fun awaitCapture(
        session: DeviceSession,
        step: String,
        candidatePromptRequired: Boolean,
        recognizerErrorRequired: Boolean = false,
    ): DestinationSearchVoiceState? {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 90_000L
        var events = DialogEvents()
        var observed = DialogObservation()
        while (SystemClock.elapsedRealtime() < deadline) {
            observed = onMain(session) { observeDialog(session) }
            events = readOwnEvents(session, step)
            assertFalse("A delivered candidate prompt could not request follow-up capture", events.blocked)
            val ready = if (candidatePromptRequired) {
                events.readyAfterCandidateDone && observed.current && observed.queryMatches &&
                    observed.candidates > 0 && observed.searchFinished
            } else {
                events.anyReady
            }
            if (events.markerSeen && ready && (!recognizerErrorRequired || events.readyAfterRecognizerError)) {
                if (candidatePromptRequired) {
                    assertEquals(
                        "Candidate follow-up must keep the 길라잡이 results page visible",
                        "DESTINATION_SEARCH",
                        observed.page,
                    )
                }
                Log.i(
                    TAG,
                    "event=DESTINATION_DIALOG_DEVICE_READY step=$step " +
                        "candidate_done_required=$candidatePromptRequired elapsed_ms=${SystemClock.elapsedRealtime() - started}",
                )
                return onMain(session) { currentDialog(session.activity) }
            }
            SystemClock.sleep(100L)
        }
        throw AssertionError("No actual candidate-DONE to STT-READY handoff for $step: $observed $events")
    }

    private fun assertNoLateCapture(session: DeviceSession, step: String, durationMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            onMain(session) {
                assertRouteUnchanged(session)
                assertFalse("Closed dialog restarted capture", mainField(session.activity, "voiceRecognitionActive") == true)
                assertTrue("Closed dialog became current again", currentDialog(session.activity) == null)
            }
            val events = readOwnEvents(session, step)
            assertTrue("BLOCKED: cancellation/selection marker was not observable", events.markerSeen)
            assertFalse("A late completion restarted microphone READY", events.anyReady)
            assertFalse("A closed dialog requested another input", events.followUpRequested)
            SystemClock.sleep(100L)
        }
    }

    private fun withActualHomeVoice(test: (DeviceSession) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(
            "BLOCKED: allowDestinationVoiceFollowUpDeviceTest=true is required",
            InstrumentationRegistry.getArguments().getString("allowDestinationVoiceFollowUpDeviceTest").toBoolean(),
        )
        assertTrue("BLOCKED: DEBUG metadata events are required", BuildConfig.DEBUG)
        val reader = Executors.newSingleThreadExecutor()
        var opened: MainActivity? = null
        try {
            val activity = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            opened = activity
            val deadline = SystemClock.elapsedRealtime() + 20_000L
            DeviceTestAccountUiLogin.ensureLoggedIn(instrumentation, activity, deadline)
            var ready = false
            while (SystemClock.elapsedRealtime() < deadline) {
                ready = onMain(instrumentation) {
                    activity.hasWindowFocus() &&
                        mainBoolean(activity, "firstRunOnboardingComplete") &&
                        mainBoolean(activity, "nativeHomeFeatureContextAvailable") &&
                        mainBoolean(activity, "homeVoiceCommandAvailable") &&
                        activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                        (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                            activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) &&
                        !mainBoolean(activity, "isHandsFreeVoiceOutputActive")
                }
                if (ready) break
                SystemClock.sleep(100L)
            }
            assertTrue("BLOCKED: actual account/education/HOME/microphone/location readiness", ready)
            val before = onMain(instrumentation) {
                val page = mainField(activity, "nativeUiPage") as Enum<*>
                val voicePage = page.javaClass.enumConstants.single { (it as Enum<*>).name == "VOICE_COMMAND" }
                MainActivity::class.java.declaredMethods.single {
                    it.name == "showNativeUiPage" && it.parameterCount == 1
                }.apply { isAccessible = true }.invoke(activity, voicePage)
                RouteBaseline(
                    generation = (mainField(activity, "routeRequestGeneration") as Number).toInt(),
                    destination = mainField(activity, "currentDestination"),
                    active = mainField(activity, "isRouteActive") as Boolean,
                    requestInFlight = (mainField(activity, "routeRequestInFlight") as AtomicBoolean).get(),
                )
            }
            test(DeviceSession(instrumentation, activity, reader, SystemClock.elapsedRealtimeNanos(), before))
        } finally {
            reader.shutdownNow()
            opened?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
        }
    }

    private fun assertRouteUnchanged(session: DeviceSession) {
        val activity = session.activity
        val before = session.routeBefore
        assertTrue(
            "Candidate dialogue must not request or start a route",
            (mainField(activity, "routeRequestGeneration") as Number).toInt() == before.generation &&
                mainField(activity, "currentDestination") == before.destination &&
                mainField(activity, "isRouteActive") == before.active &&
                (mainField(activity, "routeRequestInFlight") as AtomicBoolean).get() == before.requestInFlight,
        )
    }

    private fun readOwnEvents(session: DeviceSession, step: String): DialogEvents {
        val process = ProcessBuilder(
            "logcat", "-d", "-b", "main", "-v", "brief",
            "--pid=" + Process.myPid(), TAG + ":D", "*:S",
        ).redirectErrorStream(true).start()
        val read = session.reader.submit<DialogEvents> {
            var events = DialogEvents()
            process.inputStream.bufferedReader().use { lines ->
                lines.forEachLine { line ->
                    val event = line.substringAfter("event=", "").substringBefore(' ')
                    if (event == "SYNTHETIC_DESTINATION_DIALOG_STEP" &&
                        line.contains("run_id=${session.runId} ") && line.trimEnd().endsWith("step=$step")
                    ) {
                        events = DialogEvents(markerSeen = true)
                    } else if (events.markerSeen) {
                        when (event) {
                            "OUTPUT_DONE" -> {
                                events.outputDone = true
                                if (events.retryableRecognizerError != "NONE") events.outputDoneAfterRecognizerError = true
                            }
                            "DESTINATION_VOICE_PROMPT_DONE" -> {
                                events.candidateDone = events.outputDone
                                events.candidateDoneAfterRecognizerError = events.outputDoneAfterRecognizerError
                            }
                            "DESTINATION_VOICE_FOLLOW_UP_REQUESTED" -> {
                                events.followUpRequested = true
                                events.orderedRequest = events.candidateDone
                                events.requestAfterRecognizerError = events.candidateDoneAfterRecognizerError
                            }
                            "DESTINATION_VOICE_FOLLOW_UP_BLOCKED" -> events.blocked = true
                            "ONE_SHOT_UI_ERROR" -> {
                                val code = line.substringAfter("error_code=", "").substringBefore(' ').toIntOrNull()
                                if (events.anyReady) {
                                    when (code) {
                                        SpeechRecognizer.ERROR_NO_MATCH -> events.retryableRecognizerError = "NO_MATCH"
                                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> events.retryableRecognizerError = "SPEECH_TIMEOUT"
                                    }
                                }
                            }
                            "ONE_SHOT_UI_READY" -> {
                                events.anyReady = true
                                events.readyAfterCandidateDone = events.orderedRequest
                                if (events.requestAfterRecognizerError) events.readyAfterRecognizerError = true
                            }
                        }
                    }
                }
            }
            events
        }
        return try {
            read.get(3L, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            throw AssertionError("BLOCKED: own-PID metadata read timed out")
        } finally {
            read.cancel(true)
            process.destroy()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    private fun mark(session: DeviceSession, step: String) {
        Log.d(TAG, "event=SYNTHETIC_DESTINATION_DIALOG_STEP run_id=${session.runId} step=$step")
    }

    private fun currentDialog(activity: MainActivity): DestinationSearchVoiceState? =
        invokeMain(activity, "currentVoiceDestinationDialogState") as? DestinationSearchVoiceState

    private fun invokeMain(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)

    private fun mainBoolean(activity: MainActivity, name: String): Boolean =
        invokeMain(activity, name) as Boolean

    private fun mainField(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun <T> onMain(session: DeviceSession, action: () -> T): T = onMain(session.instrumentation, action)

    private fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { result = action() } catch (caught: Throwable) { failure = caught }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private class DeviceSession(
        val instrumentation: Instrumentation,
        val activity: MainActivity,
        val reader: ExecutorService,
        val runId: Long,
        val routeBefore: RouteBaseline,
    )

    private class RouteBaseline(val generation: Int, val destination: Any?, val active: Boolean, val requestInFlight: Boolean)
    private class Selection(val index: Int, val result: Any)

    private data class DialogObservation(
        val current: Boolean = false,
        val queryMatches: Boolean = false,
        val candidates: Int = 0,
        val searchFinished: Boolean = false,
        val capturing: Boolean = false,
        val promptPending: Boolean = false,
        val rawQueryMatches: Boolean = false,
        val rawCandidateCount: Int = 0,
        val page: String = "UNOBSERVED",
        val isActivityForeground: Boolean = false,
        val nativeHomeContext: Boolean = false,
        val leasePresent: Boolean = false,
        val lastInteractionKind: String = "UNOBSERVED",
    )

    private data class DialogEvents(
        val markerSeen: Boolean = false,
        var outputDone: Boolean = false,
        var candidateDone: Boolean = false,
        var followUpRequested: Boolean = false,
        var orderedRequest: Boolean = false,
        var anyReady: Boolean = false,
        var readyAfterCandidateDone: Boolean = false,
        var blocked: Boolean = false,
        var retryableRecognizerError: String = "NONE",
        var outputDoneAfterRecognizerError: Boolean = false,
        var candidateDoneAfterRecognizerError: Boolean = false,
        var requestAfterRecognizerError: Boolean = false,
        var readyAfterRecognizerError: Boolean = false,
    )

    private companion object {
        const val TAG = "WalkSafeVoiceInput"
        const val QUERY = "편의점"
    }
}
