package kr.co.hanium.dreamup.walksafe.voice.input

import java.util.concurrent.atomic.AtomicLong
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorDecisionToken
import kr.co.hanium.dreamup.walksafe.voice.*

/** Owns only foreground wake capture and its acknowledgement/handoff leases. */
internal class ForegroundWakeInputController(
    private val environment: VoiceInputEnvironment,
    private val session: ForegroundWakeSession,
    private val presentation: VoiceInputPresentation,
    private val platform: ForegroundWakePlatform,
    private val diagnostics: VoiceInputDiagnostics = AndroidVoiceInputDiagnostics,
) {
    private val foregroundHomeWakeInputGeneration = AtomicLong(0L)
    private var foregroundHomeWakeProbe: WakeProbeEffect? = null
    private var foregroundHomeWakeGeneration = 0L
    private var foregroundHomeWakeOwnerIsCurrent: (() -> Boolean)? = null
    private var foregroundHomeWakeFailed = false
    private var foregroundHomeWakeAcknowledgement: WakeAcknowledgementEffect? = null
    private var foregroundHomeWakeAcknowledgementGeneration = 0L
    private var foregroundWakeHandoffInProgress = false
    fun hasProbe(): Boolean = foregroundHomeWakeProbe != null
    fun acknowledgementPlaying(): Boolean = foregroundHomeWakeAcknowledgement?.isPlaying() == true
    fun invalidateForegroundHomeWakeInput() { foregroundHomeWakeInputGeneration.incrementAndGet() }
    fun closeAcknowledgement() {
        cancelForegroundHomeWakeAcknowledgement()
        foregroundHomeWakeAcknowledgement?.close()
        foregroundHomeWakeAcknowledgement = null
    }

    fun foregroundHomeWakeContextAvailable(): Boolean =
        !session.state().destroyed &&
            ((environment.page().noPendingPageOrGuidance && session.homeCommandAvailable()) ||
                environment.walkWakeContextAvailable()) && environment.disclosureAccepted() &&
            !session.state().recognitionActive && !session.state().promptPending && !session.state().deferredPending &&
            !foregroundWakeHandoffInProgress &&
            foregroundHomeWakeAcknowledgement?.isPlaying() != true &&
            !session.state().gatewayRecording && !session.state().gatewayInteractionPending

    fun cancelForegroundHomeWakeListening(resetFailure: Boolean = false) {
        invalidateForegroundHomeWakeInput()
        foregroundHomeWakeGeneration += 1L
        foregroundHomeWakeOwnerIsCurrent = null
        val probe = foregroundHomeWakeProbe
        foregroundHomeWakeProbe = null
        if (resetFailure) foregroundHomeWakeFailed = false
        // All Main owners call this on the UI thread, so stop/close joins capture first.
        probe?.cancel()
    }

    fun cancelForegroundHomeWakeAcknowledgement() {
        foregroundHomeWakeAcknowledgementGeneration += 1L
        foregroundWakeHandoffInProgress = false
        foregroundHomeWakeAcknowledgement?.cancel()
    }

    fun acknowledgeForegroundHomeWake(
        command: kr.co.hanium.dreamup.walksafe.voice.VoskTranscript?,
        expectedNavigationDecisionToken: RouteNavigatorDecisionToken?,
    ) {
        cancelForegroundHomeWakeAcknowledgement()
        val actor = environment.identity().actorId ?: return
        // Page rendering must not open a second microphone while wake hands off to command input.
        foregroundWakeHandoffInProgress = true
        val walkArea = environment.walkAreaCommandAvailable()
        if (!walkArea) presentation.showCommandPage()
        val inputPage = environment.page().owner
        val processGeneration = environment.identity().processGeneration
        val epoch = requireNotNull(environment.walkOrNull()).epoch
        val recognitionGeneration = session.state().recognitionGeneration
        val generation = ++foregroundHomeWakeAcknowledgementGeneration
        val player = foregroundHomeWakeAcknowledgement
            ?: platform.createAcknowledgement(session::speechActive).also { foregroundHomeWakeAcknowledgement = it }
        val isCurrent = {
            !session.state().destroyed && environment.foreground() &&
                foregroundHomeWakeAcknowledgement === player &&
                foregroundHomeWakeAcknowledgementGeneration == generation &&
                environment.identity().actorId == actor &&
                environment.identity().processGeneration == processGeneration &&
                requireNotNull(environment.walkOrNull()).epoch == epoch &&
                session.state().recognitionGeneration == recognitionGeneration &&
                environment.page().owner == inputPage &&
                environment.walkAreaCommandAvailable() == walkArea &&
                (if (walkArea) environment.walkAreaCommandAvailable() else session.homeCommandAvailable()) &&
                environment.disclosureAccepted()
        }
        presentation.voiceStatus("호출어를 인식했습니다.")
        if (!isCurrent()) {
            foregroundWakeHandoffInProgress = false
            return
        }
        // The lease exists before play: an unavailable tone can fail synchronously.
        if (BuildConfig.DEBUG) diagnostics.wake("event=HOME_WAKE_ACK_REQUESTED")
        invalidateForegroundHomeWakeInput()
        player.play { result ->
            if (!isCurrent()) {
                if (foregroundHomeWakeAcknowledgementGeneration == generation) {
                    foregroundWakeHandoffInProgress = false
                    session.scheduleRestart()
                }
                return@play
            }
            foregroundHomeWakeAcknowledgementGeneration += 1L
            foregroundWakeHandoffInProgress = false
            if (BuildConfig.DEBUG) diagnostics.wake("event=HOME_WAKE_ACK_TERMINAL result=${result.name}",
            )
            if (result == kr.co.hanium.dreamup.walksafe.voice.WakeAcknowledgementResult.CANCELLED ||
                session.speechActive()
            ) {
                session.scheduleRestart()
                return@play
            }
            if (command == null || environment.serverCommandRecognitionEnabled()) {
                // A completed cue is the listening prompt; a failed cue uses the normal spoken prompt.
                if (result == kr.co.hanium.dreamup.walksafe.voice.WakeAcknowledgementResult.COMPLETED) {
                    session.cueCompletedForCurrentRecognition()
                }
                session.startCommandRecognition()
            } else {
                session.recognized(RecognizedVoiceInput(listOf(command.text), listOf(command.confidence ?: Float.NaN),
                    expectedNavigationDecisionToken, false, "on_device_home_wake", OfflineSpeechEngine.VOSK))
            }
        }
    }

    fun refreshForegroundHomeWakeListening() {
        if (!foregroundHomeWakeContextAvailable()) {
            cancelForegroundHomeWakeListening()
            return
        }
        if (foregroundHomeWakeFailed) return
        if (session.recognitionBlocked()) {
            cancelForegroundHomeWakeListening()
            session.scheduleRestart()
            return
        }
        if (foregroundHomeWakeOwnerIsCurrent?.invoke() == true) return
        cancelForegroundHomeWakeListening()
        val generation = ++foregroundHomeWakeGeneration
        val actor = environment.identity().actorId
        val processGeneration = environment.identity().processGeneration
        val page = environment.page().owner
        val walkArea = environment.walkAreaCommandAvailable()
        val epoch = requireNotNull(environment.walkOrNull()).epoch
        val expectedNavigationDecisionToken = environment.pendingNavigationDecisionToken()
        val isCurrent = {
            foregroundHomeWakeGeneration == generation &&
                environment.identity().actorId == actor &&
                environment.identity().processGeneration == processGeneration &&
                environment.page().owner == page && environment.walkAreaCommandAvailable() == walkArea &&
                requireNotNull(environment.walkOrNull()).epoch == epoch &&
                foregroundHomeWakeContextAvailable()
        }
        // Install the owner before effects that can cause another render.
        foregroundHomeWakeOwnerIsCurrent = isCurrent
        val directory = session.modelDirectoryOrNull()
        if (directory == null) {
            // Preparation is single-flight on its worker. Do not hash the voice model on HOME's
            // render thread, or retain an owner that would swallow the worker's ready callback.
            cancelForegroundHomeWakeListening()
            if (session.state().modelFailed) {
                foregroundHomeWakeFailed = true
                if (BuildConfig.DEBUG) {
                    diagnostics.wake("event=HOME_WAKE_BLOCKED reason=MODEL_UNAVAILABLE")
                }
                presentation.navigationStatus("voice_home=model_unavailable_use_voice_button")
            }
            return
        }
        // HOME and non-active walking pages share a capture implementation, with separate ownership.
        // ACTIVE walking continues to use its existing foreground service.
        session.stopHandsFree()
        lateinit var probe: WakeProbeEffect
        probe = platform.createProbe(ForegroundWakeCallbacks(
            directory = directory,
            inputGeneration = foregroundHomeWakeInputGeneration::get,
            suppressed = {
                !isCurrent() ||
                    session.state().recognitionActive || session.recognitionBlocked()
            },
            ready = {
                if (isCurrent() && foregroundHomeWakeProbe === probe) {
                    if (session.recognitionBlocked()) {
                        cancelForegroundHomeWakeListening()
                    } else if (BuildConfig.DEBUG) {
                        diagnostics.wake("event=HOME_WAKE_READY backend=VOSK")
                    }
                }
            },
            result = { result ->
                if (isCurrent() && foregroundHomeWakeProbe === probe) {
                    if (session.recognitionBlocked()) {
                        cancelForegroundHomeWakeListening()
                    } else if (result.availability == VoskWakePhraseProbeAvailability.AVAILABLE) {
                        // HOME opt-in probe delivers only after main-thread stop/close.
                        cancelForegroundHomeWakeListening()
                        if (BuildConfig.DEBUG) {
                            diagnostics.wake("event=HOME_WAKE_HANDOFF backend=VOSK")
                        }
                        acknowledgeForegroundHomeWake(
                            result.commandTranscript, expectedNavigationDecisionToken,
                        )
                    } else {
                        // A passive render must not create an unbounded failure/restart loop.
                        foregroundHomeWakeFailed = true
                        if (BuildConfig.DEBUG) {
                            diagnostics.wake("event=HOME_WAKE_TERMINAL failure=" + result.failure?.name,
                            )
                        }
                        presentation.navigationStatus("voice_home=retry_on_home_reentry_use_voice_button")
                    }
                }
            },
        ))
        foregroundHomeWakeProbe = probe
        if (BuildConfig.DEBUG) {
            diagnostics.wake("event=HOME_WAKE_START_REQUESTED backend=VOSK")
        }
        probe.start()
    }
}
