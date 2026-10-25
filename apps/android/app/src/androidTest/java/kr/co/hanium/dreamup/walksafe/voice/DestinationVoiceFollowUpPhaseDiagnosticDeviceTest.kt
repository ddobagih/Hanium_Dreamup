package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.MainActivityControllerTestFixture

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.Looper
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
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.SpeechOutputEvent
import kr.co.hanium.dreamup.walksafe.feedback.SpeechOutputKind
import kr.co.hanium.dreamup.walksafe.feedback.SpeechOutputOutcome
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.destinationOriginAgeNoticeKo
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceCommand
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceState
import kr.co.hanium.dreamup.walksafe.session.DeviceTestAccountUiLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MIC06 phase observation v2, separate from the original compound-stage FAIL.
 * Each candidate capture keeps its existing 90-second budget. Real recognizer errors retain the
 * production 10/20-second phase watchdogs. The 20-second diagnostic wait starts when the initial
 * READY observation returns, independently of the product clock and the READY log timestamp.
 * Recovery capture gets its own existing 90-second budget after that actual error is observed.
 * Execute within the unchanged 360-second outer limit.
 * Real login, location/search, candidate TTS completion and microphone READY remain required.
 * Only command finals are synthetic. No audio, account, position or gate is replaced.
 */
@RunWith(AndroidJUnit4::class)
class DestinationVoiceFollowUpPhaseDiagnosticDeviceTest {
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
            session.partObservation = CandidatePartObservation(session)
            invokeMain(session.activity, "cancelVoiceCommandRecognition")
            assertTrue("Input cleanup must preserve the candidate page", currentDialog(session.activity) === original)
            mark(session, "RECOGNIZER_ERROR_RETRY", observedResponses = 2)
            val button = mainField(session.activity, "voiceReportButton") as Button
            assertTrue("BLOCKED: actual voice retry button is unavailable", button.isShown && button.isEnabled)
            assertTrue("The real retry button must handle the click", button.performClick())
        }
        // Finish the manual candidate response before observing the real error/recovery phase.
        // This phase diagnostic does not replace the original compound 90-second result.
        val beforeError = requireNotNull(awaitCapture(
            session, "RECOGNIZER_ERROR_RETRY", candidatePromptRequired = true, initialReadyObservation = true,
        ))
        // This diagnostic wait starts after initial READY observation returns; product clocks stay unchanged.
        val recognizerErrorDeadline = SystemClock.elapsedRealtime() + 20_000L
        assertTrue("Manual retry must preserve the same candidate state and page", beforeError === original)
        // No injected listener callback: observe the real producer before timing the recovery page.
        var errorEvents = DialogEvents()
        var actualErrorObserved = false
        while (SystemClock.elapsedRealtime() < recognizerErrorDeadline) {
            errorEvents = readOwnEvents(session, "RECOGNIZER_ERROR_RETRY", strictRecognizerErrorOrdering = true)
            assertFalse("A delivered candidate prompt could not request follow-up capture", errorEvents.blocked)
            actualErrorObserved = errorEvents.markerSeen && errorEvents.recognizerErrorCount == 1 &&
                errorEvents.dialogRecognizerError == errorEvents.retryableRecognizerError &&
                errorEvents.dialogRecognizerError in setOf("NO_MATCH", "SPEECH_TIMEOUT") &&
                errorEvents.dialogRecognizerErrorPage == original.pageIndex &&
                errorEvents.firstCandidateReadyOrdinal > 0 &&
                errorEvents.recognizerErrorOrdinal > errorEvents.firstCandidateReadyOrdinal &&
                errorEvents.dialogRecognizerErrorOrdinal > errorEvents.recognizerErrorOrdinal
            if (actualErrorObserved) break
            SystemClock.sleep(100L)
        }
        assertTrue("No actual microphone INPUT_ERROR 6/7 within the 20-second diagnostic wait after initial READY observation: $errorEvents",
            actualErrorObserved)
        val afterError = requireNotNull(awaitCapture(
            session, "RECOGNIZER_ERROR_RETRY", candidatePromptRequired = true, recognizerErrorRequired = true,
        ))
        assertTrue("Recognizer error must preserve the same candidate state and page", afterError === original)
        assertRetryResponse(session, afterError, "말씀을 정확히 듣지 못했습니다.", recognizerErrorRequired = true)

        injectFinal(session, "LOW_CONFIDENCE_RETRY", "${allowedIndices[0]}번", confidence = 0.01f)
        val afterLowConfidence = requireNotNull(awaitCapture(
            session, "LOW_CONFIDENCE_RETRY", candidatePromptRequired = true,
        ))
        assertTrue("Low confidence must preserve the same candidate state and page", afterLowConfidence === original)
        assertRetryResponse(session, afterLowConfidence, "명령을 이해하지 못했습니다.")

        injectFinal(
            session, "CONFLICTING_NUMBERS_RETRY", "${allowedIndices[0]}번",
            alternativePhrase = "${allowedIndices[1]}번",
        )
        val afterConflict = requireNotNull(awaitCapture(
            session, "CONFLICTING_NUMBERS_RETRY", candidatePromptRequired = true,
        ))
        assertTrue("Conflicting numbers must preserve the same candidate state and page", afterConflict === original)
        assertRetryResponse(session, afterConflict, "음성 후보를 하나로 확정하지 못했습니다. 다시 말씀해 주세요.")

        injectFinal(session, "INVALID_NUMBER_RETRY", "${original.results.size + 1}번")
        val afterInvalidNumber = requireNotNull(awaitCapture(
            session, "INVALID_NUMBER_RETRY", candidatePromptRequired = true,
        ))
        assertTrue("Invalid number must preserve the same candidate state and page", afterInvalidNumber === original)
        assertRetryResponse(session, afterInvalidNumber, "안내한 후보 번호를 확인하지 못했습니다.")

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

    private fun assertRetryResponse(
        session: DeviceSession, state: DestinationSearchVoiceState, expectedPrefix: String,
        recognizerErrorRequired: Boolean = false,
    ) {
        onMain(session) {
            val events = requireNotNull(session.lastCaptureEvents)
            val facts = requireNotNull(session.partObservation).responseFacts(state, expectedPrefix, events,
                recognizerErrorRequired)
            assertTrue("NOT_VERIFIED: the actual reason prefix was not delivered: $facts", facts.reasonPrefixObserved)
            assertTrue("NOT_VERIFIED: the actual whole candidate page was not delivered: $facts", facts.currentPageObserved)
            if (recognizerErrorRequired) {
                assertTrue("NOT_VERIFIED: initial page/READY and actual error/recovery order were not observed: $facts",
                    facts.initialPageAndErrorRecoveryObserved)
            }
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
                val modelPreparing = MainActivityControllerTestFixture.handsFreeModelPreparing(activity)
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
        initialReadyObservation: Boolean = false,
    ): DestinationSearchVoiceState? {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 90_000L
        var events = DialogEvents()
        var observed = DialogObservation()
        while (SystemClock.elapsedRealtime() < deadline) {
            observed = onMain(session) { observeDialog(session) }
            events = readOwnEvents(session, step, strictRecognizerErrorOrdering = recognizerErrorRequired)
            assertFalse("A delivered candidate prompt could not request follow-up capture", events.blocked)
            if (recognizerErrorRequired && events.readyAfterCandidateDone &&
                events.retryableRecognizerError == "NONE"
            ) {
                onMain(session) {
                    currentDialog(session.activity)?.let { session.partObservation?.confirmInitialReady(it) }
                }
            }
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
                return onMain(session) {
                    val state = currentDialog(session.activity)
                    if (candidatePromptRequired) {
                        if (initialReadyObservation) {
                            val observation = requireNotNull(session.partObservation)
                            val candidateState = requireNotNull(state)
                            observation.confirmInitialReady(candidateState)
                            val facts = observation.facts(candidateState)
                            assertTrue("NOT_VERIFIED: initial candidate page parts were not delivered in order: $facts",
                                facts.currentPageObserved)
                        } else session.partObservation?.confirmReady(requireNotNull(state))
                    }
                    session.lastCaptureEvents = events
                    state
                }
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
        assertTrue(
            "BLOCKED: allowDestinationVoiceFollowUpPhaseDiagnosticDeviceTest=true is required",
            InstrumentationRegistry.getArguments().getString("allowDestinationVoiceFollowUpPhaseDiagnosticDeviceTest").toBoolean(),
        )
        assertTrue("BLOCKED: DEBUG metadata events are required", BuildConfig.DEBUG)
        val reader = Executors.newSingleThreadExecutor()
        var opened: MainActivity? = null
        var session: DeviceSession? = null
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
            val deviceSession = DeviceSession(instrumentation, activity, reader, SystemClock.elapsedRealtimeNanos(), before)
            session = deviceSession
            test(deviceSession)
        } finally {
            try {
                session?.let { onMain(it) { it.partObservation?.restore() } }
            } finally {
                reader.shutdownNow()
                opened?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
            }
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

    private fun readOwnEvents(
        session: DeviceSession, step: String, strictRecognizerErrorOrdering: Boolean = false,
    ): DialogEvents {
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
                        events.eventOrdinal++
                        when (event) {
                            "OUTPUT_DONE" -> {
                                events.outputDone = true
                                if (events.retryableRecognizerError != "NONE") events.outputDoneAfterRecognizerError = true
                            }
                            "DESTINATION_VOICE_PROMPT_DONE" -> {
                                events.candidateDone = events.outputDone
                                events.candidateDoneAfterRecognizerError = events.outputDoneAfterRecognizerError
                                if (events.candidateDoneAfterRecognizerError) events.recoveredCandidateDoneOrdinal = events.eventOrdinal
                            }
                            "DESTINATION_VOICE_FOLLOW_UP_REQUESTED" -> {
                                events.followUpRequested = true
                                events.orderedRequest = events.candidateDone
                                events.requestAfterRecognizerError = events.candidateDoneAfterRecognizerError
                            }
                            "DESTINATION_VOICE_FOLLOW_UP_BLOCKED" -> events.blocked = true
                            "ONE_SHOT_UI_ERROR" -> {
                                val code = line.substringAfter("error_code=", "").substringBefore(' ').toIntOrNull()
                                if (events.anyReady && code in setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                                    events.retryableRecognizerError = if (code == SpeechRecognizer.ERROR_NO_MATCH) "NO_MATCH" else "SPEECH_TIMEOUT"
                                    events.recognizerErrorCount++
                                    events.recognizerErrorOrdinal = events.eventOrdinal
                                    if (strictRecognizerErrorOrdering) {
                                        // A prior batch's completion cannot stand in for error recovery.
                                        events.outputDone = false
                                        events.candidateDone = false
                                        events.orderedRequest = false
                                        events.readyAfterCandidateDone = false
                                        events.outputDoneAfterRecognizerError = false
                                        events.candidateDoneAfterRecognizerError = false
                                        events.requestAfterRecognizerError = false
                                        events.readyAfterRecognizerError = false
                                    }
                                }
                            }
                            "VOICE_DIALOG" -> {
                                val stage = line.substringAfter("stage=", "").substringBefore(' ')
                                val reason = line.substringAfter("reason=", "").substringBefore(' ')
                                val context = line.substringAfter("context=", "").substringBefore(' ')
                                val origin = line.substringAfter("origin=", "").substringBefore(' ')
                                if (stage == "INPUT_ERROR" && context == "VALID" && origin == "MICROPHONE_PATH" &&
                                    reason == events.retryableRecognizerError && reason in setOf("NO_MATCH", "SPEECH_TIMEOUT")
                                ) {
                                    events.dialogRecognizerError = reason
                                    events.dialogRecognizerErrorOrdinal = events.eventOrdinal
                                    events.dialogRecognizerErrorPage = line.substringAfter("page_index=", "").substringBefore(' ').toIntOrNull()
                                }
                            }
                            "ONE_SHOT_UI_READY" -> {
                                events.anyReady = true
                                events.readyAfterCandidateDone = events.orderedRequest
                                if (events.readyAfterCandidateDone && events.retryableRecognizerError == "NONE") {
                                    events.firstCandidateReadyOrdinal = events.eventOrdinal
                                }
                                if (events.requestAfterRecognizerError) {
                                    events.readyAfterRecognizerError = true
                                    events.recoveredReadyOrdinal = events.eventOrdinal
                                }
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

    private fun mark(session: DeviceSession, step: String, observedResponses: Int = 1) {
        session.lastCaptureEvents = null
        session.partObservation?.beginWindow(observedResponses)
        Log.d(TAG, "event=SYNTHETIC_DESTINATION_DIALOG_STEP run_id=${session.runId} step=$step")
    }

    private fun currentDialog(activity: MainActivity): DestinationSearchVoiceState? =
        invokeMain(activity, "currentVoiceDestinationDialogState") as? DestinationSearchVoiceState

    private fun invokeMain(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)

    private fun mainBoolean(activity: MainActivity, name: String): Boolean =
        invokeMain(activity, name) as Boolean

    private fun mainField(activity: MainActivity, name: String): Any? =
        MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(activity)

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
    ) {
        var partObservation: CandidatePartObservation? = null
        var lastCaptureEvents: DialogEvents? = null
    }

    /** Observes the existing actor only; messages stay in memory and never enter diagnostics. */
    private inner class CandidatePartObservation(private val session: DeviceSession) {
        private val activity = session.activity
        private val actuator: AndroidFeedbackActuator = mainField(activity, "feedbackActuator") as? AndroidFeedbackActuator
            ?: throw AssertionError("NOT_VERIFIED: existing candidate actuator is unavailable")
        private val previous = actuator.speechOutputListener
        private var window: PartWindow? = null
        private var initialResponse: PartWindow? = null
        private var responseLimit = 1
        private val listener: (SpeechOutputEvent) -> Unit = { event ->
            try {
                previous?.invoke(event)
            } finally {
                try { record(event) } catch (_: Throwable) { window?.invalid = true }
            }
        }

        init {
            check(Looper.myLooper() === Looper.getMainLooper())
            actuator.speechOutputListener = listener
        }

        fun beginWindow(observedResponses: Int = 1) {
            check(Looper.myLooper() === Looper.getMainLooper())
            require(observedResponses in 1..2)
            responseLimit = observedResponses
            initialResponse = null
            window = PartWindow(
                SystemClock.elapsedRealtime(), responseGeneration(),
                mainField(activity, "reporterUserId"), mainField(activity, "feedbackLifecycleGeneration"),
                GatewaySessionProcessCoordinator.snapshot().generation, approvalAtMs(),
            )
            if (actuator.speechOutputListener !== listener || mainField(activity, "feedbackActuator") !== actuator) {
                window?.invalid = true
            }
        }

        private fun responseGeneration() = (mainField(activity, "commandSpeechResponseGeneration") as Number).toLong()
        private fun approvalAtMs() = (actuatorField("lastInteractionMessageAtMs") as Number).toLong()
        private fun actuatorField(name: String): Any? =
            actuator.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(actuator)

        private fun ownerCurrent(w: PartWindow): Boolean =
            Looper.myLooper() === Looper.getMainLooper() && actuator.speechOutputListener === listener &&
                mainField(activity, "feedbackActuator") === actuator &&
                mainField(activity, "reporterUserId") == w.actor &&
                mainField(activity, "feedbackLifecycleGeneration") == w.lifetime &&
                GatewaySessionProcessCoordinator.snapshot().generation == w.processGeneration &&
                mainField(activity, "isActivityForeground") == true &&
                (mainField(activity, "nativeUiPage") as Enum<*>).name == "DESTINATION_SEARCH" &&
                (w.state == null || (currentDialog(activity) === w.state &&
                    mainField(activity, "homeDestinationVoiceDialogLease") === w.lease &&
                    mainField(activity, "destinationSearchGeneration") == w.searchGeneration &&
                    responseGeneration() == w.responseGeneration &&
                    actuatorField("ttsEngineGeneration") == w.engineGeneration))

        private fun record(event: SpeechOutputEvent) {
            var w = window ?: return
            if (event.kind != SpeechOutputKind.INTERACTION || w.invalid) return
            if (event.outcome == SpeechOutputOutcome.REQUESTED && w.state != null &&
                responseGeneration() != w.responseGeneration
            ) {
                // A second batch is admitted only after the first exact page and real READY.
                val sameOwner = Looper.myLooper() === Looper.getMainLooper() &&
                    actuator.speechOutputListener === listener && mainField(activity, "feedbackActuator") === actuator &&
                    mainField(activity, "reporterUserId") == w.actor &&
                    mainField(activity, "feedbackLifecycleGeneration") == w.lifetime &&
                    GatewaySessionProcessCoordinator.snapshot().generation == w.processGeneration &&
                    mainField(activity, "isActivityForeground") == true &&
                    (mainField(activity, "nativeUiPage") as Enum<*>).name == "DESTINATION_SEARCH" &&
                    currentDialog(activity) === w.state &&
                    mainField(activity, "destinationSearchGeneration") == w.searchGeneration &&
                    actuatorField("ttsEngineGeneration") == w.engineGeneration
                if (responseLimit != 2 || initialResponse != null || !w.readyConfirmed || !sameOwner ||
                    w.activeMessage != null || w.requestedAtMs != null ||
                    !w.expectedPages.any { it == w.completedParts } ||
                    responseGeneration() <= w.responseGeneration || event.atElapsedRealtimeMs <= w.lastDoneAtMs ||
                    mainField(activity, "homeDestinationVoiceDialogLease") === w.lease
                ) { w.invalid = true; return }
                initialResponse = w
                w = PartWindow(w.startedAtMs, w.responseGeneration, w.actor, w.lifetime,
                    w.processGeneration, w.lastApprovalAtMs)
                window = w
            }
            if (!ownerCurrent(w) || event.atElapsedRealtimeMs < w.startedAtMs) { w.invalid = true; return }
            if (w.state == null) {
                val state = currentDialog(activity)
                val generation = responseGeneration()
                if (state == null || state.query != QUERY || state.currentPageResults.isEmpty() ||
                    generation <= w.responseBefore || event.outcome != SpeechOutputOutcome.REQUESTED
                ) { w.invalid = true; return }
                w.state = state
                w.lease = mainField(activity, "homeDestinationVoiceDialogLease")
                w.searchGeneration = mainField(activity, "destinationSearchGeneration")
                w.responseGeneration = generation
                w.engineGeneration = actuatorField("ttsEngineGeneration")
                // The existing DTO permits the once-per-list age notice in the exact page header.
                val ageNotice = destinationOriginAgeNoticeKo(state.origin, event.atElapsedRealtimeMs)
                w.expectedPages = listOf(state.voicePromptParts(), state.voicePromptParts(ageNotice)).distinct()
            }
            when (event.outcome) {
                SpeechOutputOutcome.REQUESTED -> {
                    if (w.requestedAtMs != null || w.activeMessage != null) w.invalid = true
                    else w.requestedAtMs = event.atElapsedRealtimeMs
                }
                SpeechOutputOutcome.STARTED -> {
                    val approvedAt = approvalAtMs()
                    val message = actuatorField("lastInteractionMessage") as? String
                    if (w.activeMessage != null || message.isNullOrBlank() ||
                        w.requestedAtMs != approvedAt || approvedAt <= w.lastApprovalAtMs ||
                        approvedAt < w.startedAtMs || event.atElapsedRealtimeMs < approvedAt
                    ) { w.invalid = true; return }
                    w.activeMessage = message
                    w.activeStartedAtMs = event.atElapsedRealtimeMs
                    w.lastApprovalAtMs = approvedAt
                    w.requestedAtMs = null
                }
                SpeechOutputOutcome.DONE -> {
                    val message = w.activeMessage
                    if (message == null || actuatorField("lastInteractionMessage") != message ||
                        approvalAtMs() != w.lastApprovalAtMs || event.atElapsedRealtimeMs < w.activeStartedAtMs ||
                        w.completedParts.size >= 16
                    ) { w.invalid = true; return }
                    w.completedParts += message
                    w.lastDoneAtMs = event.atElapsedRealtimeMs
                    w.activeMessage = null
                }
                else -> w.invalid = true
            }
        }

        fun confirmInitialReady(state: DestinationSearchVoiceState) {
            val w = window ?: return
            if (responseLimit == 2 && initialResponse == null && !w.invalid &&
                w.requestedAtMs == null && w.activeMessage == null &&
                w.expectedPages.any { it == w.completedParts }
            ) confirmReady(state)
        }

        fun confirmReady(state: DestinationSearchVoiceState) {
            val w = window ?: throw AssertionError("NOT_VERIFIED: candidate part window was not opened")
            if (!ownerCurrent(w) || w.state !== state || w.invalid || w.requestedAtMs != null ||
                w.activeMessage != null || w.completedParts.isEmpty() ||
                mainField(activity, "voiceCommandPromptPending") != false ||
                mainField(activity, "voiceRecognitionActive") != true ||
                SystemClock.elapsedRealtime() < w.lastDoneAtMs
            ) { w.invalid = true; return }
            // Called only after the unchanged own-PID candidate-DONE -> follow-up -> READY oracle.
            w.readyConfirmed = true
        }

        fun facts(state: DestinationSearchVoiceState): PartFacts {
            val w = window ?: return PartFacts()
            val valid = !w.invalid && w.readyConfirmed && w.state === state && ownerCurrent(w) &&
                w.requestedAtMs == null && w.activeMessage == null && w.completedParts.isNotEmpty()
            val footer = w.expectedPages.firstOrNull()?.lastOrNull()
            return PartFacts(
                choiceGuidanceObserved = valid && footer != null && w.completedParts.lastOrNull() == footer,
                currentPageObserved = valid && w.expectedPages.any { it == w.completedParts },
                completedParts = w.completedParts.size,
                observationCurrent = valid,
            )
        }

        fun responseFacts(
            state: DestinationSearchVoiceState, expectedPrefix: String, events: DialogEvents,
            recognizerErrorRequired: Boolean,
        ): ResponseFacts {
            val w = window ?: return ResponseFacts()
            val first = initialResponse
            val valid = !w.invalid && w.readyConfirmed && w.state === state && ownerCurrent(w) &&
                w.requestedAtMs == null && w.activeMessage == null && w.completedParts.isNotEmpty()
            val errorOrder = first != null && !first.invalid && first.readyConfirmed && first.state === state &&
                first.expectedPages.any { it == first.completedParts } &&
                first.actor == w.actor && first.lifetime == w.lifetime &&
                first.processGeneration == w.processGeneration && first.engineGeneration == w.engineGeneration &&
                first.searchGeneration == w.searchGeneration && first.responseGeneration < w.responseGeneration &&
                first.lease !== w.lease && events.recognizerErrorCount == 1 &&
                events.dialogRecognizerError == events.retryableRecognizerError &&
                events.dialogRecognizerError in setOf("NO_MATCH", "SPEECH_TIMEOUT") &&
                events.dialogRecognizerErrorPage == state.pageIndex &&
                events.firstCandidateReadyOrdinal > 0 &&
                events.recognizerErrorOrdinal > events.firstCandidateReadyOrdinal &&
                events.dialogRecognizerErrorOrdinal > events.recognizerErrorOrdinal &&
                events.recoveredCandidateDoneOrdinal > events.dialogRecognizerErrorOrdinal &&
                events.recoveredReadyOrdinal > events.recoveredCandidateDoneOrdinal &&
                events.readyAfterRecognizerError
            val current = valid && if (recognizerErrorRequired) errorOrder else first == null
            return ResponseFacts(
                reasonPrefixObserved = current && w.completedParts.firstOrNull() == expectedPrefix,
                currentPageObserved = current && w.expectedPages.any { listOf(expectedPrefix) + it == w.completedParts },
                initialPageAndErrorRecoveryObserved = valid && errorOrder,
                observedResponses = if (first != null) 2 else if (w.state != null) 1 else 0,
                observationCurrent = current,
            )
        }

        fun restore() {
            check(Looper.myLooper() === Looper.getMainLooper())
            if (actuator.speechOutputListener === listener) actuator.speechOutputListener = previous
            window = null
            initialResponse = null
        }
    }

    private class PartWindow(
        val startedAtMs: Long, val responseBefore: Long, val actor: Any?, val lifetime: Any?,
        val processGeneration: Long, var lastApprovalAtMs: Long,
    ) {
        var state: DestinationSearchVoiceState? = null
        var lease: Any? = null
        var searchGeneration: Any? = null
        var responseGeneration = -1L
        var engineGeneration: Any? = null
        var expectedPages: List<List<String>> = emptyList()
        var requestedAtMs: Long? = null
        var activeMessage: String? = null
        var activeStartedAtMs = 0L
        var lastDoneAtMs = 0L
        val completedParts = mutableListOf<String>()
        var readyConfirmed = false
        var invalid = false
    }

    private data class PartFacts(
        val choiceGuidanceObserved: Boolean = false,
        val currentPageObserved: Boolean = false,
        val completedParts: Int = 0,
        val observationCurrent: Boolean = false,
    )

    private data class ResponseFacts(
        val reasonPrefixObserved: Boolean = false,
        val currentPageObserved: Boolean = false,
        val initialPageAndErrorRecoveryObserved: Boolean = false,
        val observedResponses: Int = 0,
        val observationCurrent: Boolean = false,
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
        var eventOrdinal: Int = 0,
        var firstCandidateReadyOrdinal: Int = 0,
        var recognizerErrorCount: Int = 0,
        var recognizerErrorOrdinal: Int = 0,
        var dialogRecognizerError: String = "NONE",
        var dialogRecognizerErrorOrdinal: Int = 0,
        var dialogRecognizerErrorPage: Int? = null,
        var recoveredCandidateDoneOrdinal: Int = 0,
        var recoveredReadyOrdinal: Int = 0,
    )

    private companion object {
        const val TAG = "WalkSafeVoiceInput"
        const val QUERY = "편의점"
    }
}
