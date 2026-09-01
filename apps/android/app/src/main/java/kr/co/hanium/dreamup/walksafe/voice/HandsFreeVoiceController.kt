package kr.co.hanium.dreamup.walksafe.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable
import java.io.File
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState

internal data class HandsFreeVoiceEligibility(
    val walkState: WalkSessionState,
    val microphoneGranted: Boolean,
    val disclosureAccepted: Boolean,
    val modelReady: Boolean,
)

/** Binds the pure wake/command lifecycle to one continuous on-device Vosk stream. */
internal class HandsFreeVoiceController(
    context: Context,
    modelDirectory: File,
    private val eligibility: () -> HandsFreeVoiceEligibility,
    private val isAppSpeechActive: () -> Boolean,
    private val onCommand: (text: String, confidence: Float?) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onTerminalError: () -> Unit = {},
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : WalkVoiceSessionController, Closeable {
    private val stateMachine = HandsFreeVoiceStateMachine()
    private var activeRunId: Long? = null
    private var starting = false
    private var closed = false
    private var commandTimeout: Runnable? = null
    private var outputPoll: Runnable? = null
    private var outputObserved = false
    private var outputWaitStartedAtMs = 0L
    private var outputQuietSinceMs: Long? = null

    private val transcriber = VoskStreamingTranscriber(
        context = context.applicationContext,
        modelDirectory = modelDirectory,
        isInputSuppressed = ::shouldSuppressInput,
        listener = object : VoskStreamingListener {
            override fun onReady(runId: Long) {
                mainHandler.post { handleReady(runId) }
            }

            override fun onTranscript(runId: Long, transcript: VoskTranscript) {
                mainHandler.post { handleTranscript(runId, transcript) }
            }

            override fun onError(runId: Long, error: VoskStreamingError) {
                mainHandler.post { handleError(runId, error) }
            }
        },
    )

    override fun start() {
        checkMainThread()
        if (closed || starting || transcriber.isRunning()) return
        val current = eligibility()
        if (!current.allowsListening()) {
            onStatus("voice_hands_free=blocked eligibility")
            return
        }
        activeRunId = null
        starting = true
        onStatus("voice_hands_free=model_loading")
        transcriber.start()
    }

    override fun stop() {
        checkMainThread()
        if (closed) return
        activeRunId = null
        starting = false
        cancelScheduledWork()
        stateMachine.stop()
        transcriber.stop()
        onStatus("voice_hands_free=stopped")
    }

    override fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        activeRunId = null
        starting = false
        cancelScheduledWork()
        stateMachine.stop()
        transcriber.close()
    }

    private fun handleReady(runId: Long) {
        if (closed || !starting) return
        starting = false
        val current = eligibility()
        if (!current.allowsListening()) {
            transcriber.stop()
            onStatus("voice_hands_free=blocked eligibility_changed")
            return
        }
        activeRunId = runId
        val transition = stateMachine.startIfEligible(
            walkState = current.walkState,
            voiceConsentGranted = current.disclosureAccepted,
            modelsReady = current.modelReady,
        )
        if (transition.state !is HandsFreeVoiceState.WaitingForWakeWord) {
            activeRunId = null
            transcriber.stop()
            onStatus("voice_hands_free=blocked state")
            return
        }
        onStatus("voice_hands_free=waiting_wake_phrase")
    }

    private fun handleTranscript(runId: Long, transcript: VoskTranscript) {
        if (
            closed ||
            activeRunId != runId ||
            !transcript.isFinal ||
            !eligibility().allowsListening()
        ) return
        if (!transcript.isTrustedForCommand()) {
            onStatus("voice_hands_free=low_confidence")
            return
        }
        when (stateMachine.snapshot()) {
            is HandsFreeVoiceState.WaitingForWakeWord -> {
                when (
                    val extraction = WakePhraseCommandExtractor.extractFinalTranscript(
                        transcript.text,
                        WakePhraseCommandMode.WAKE_PHRASE_REQUIRED,
                    )
                ) {
                    WakePhraseCommandExtraction.NotAddressed -> Unit
                    WakePhraseCommandExtraction.AwaitingCommand -> {
                        openCommandWindow()
                    }
                    is WakePhraseCommandExtraction.Command -> {
                        openCommandWindow()
                        dispatchCommand(extraction.text, transcript.confidence)
                    }
                }
            }
            is HandsFreeVoiceState.WaitingForCommand -> {
                when (
                    val extraction = WakePhraseCommandExtractor.extractFinalTranscript(
                        transcript.text,
                        WakePhraseCommandMode.COMMAND_AWAITED,
                    )
                ) {
                    WakePhraseCommandExtraction.NotAddressed,
                    WakePhraseCommandExtraction.AwaitingCommand,
                    -> Unit
                    is WakePhraseCommandExtraction.Command ->
                        dispatchCommand(extraction.text, transcript.confidence)
                }
            }
            else -> Unit
        }
    }

    private fun openCommandWindow() {
        val transition = stateMachine.onWakeWordDetected(stateMachine.snapshot().generation)
        if (transition.state !is HandsFreeVoiceState.WaitingForCommand) return
        scheduleCommandTimeout(transition.state.generation)
        onStatus("voice_hands_free=waiting_command")
    }

    private fun dispatchCommand(text: String, confidence: Float?) {
        val transition = stateMachine.onCommandRecognized(
            callbackGeneration = stateMachine.snapshot().generation,
            command = text,
        )
        val processing = transition.state as? HandsFreeVoiceState.ProcessingCommand ?: return
        commandTimeout?.let(mainHandler::removeCallbacks)
        commandTimeout = null
        onStatus("voice_hands_free=processing_command")
        try {
            onCommand(processing.command, confidence)
        } catch (_: RuntimeException) {
            failClosed(processing.generation, "command_dispatch_failed")
            return
        }
        if (!eligibility().allowsListening()) {
            stop()
            return
        }
        val speaking = stateMachine.onCommandDispatched(processing.generation)
        if (speaking.state !is HandsFreeVoiceState.Speaking) return
        waitForOutputToSettle(speaking.state.generation)
    }

    private fun scheduleCommandTimeout(generation: Long) {
        commandTimeout?.let(mainHandler::removeCallbacks)
        lateinit var timeout: Runnable
        timeout = Runnable {
            if (commandTimeout !== timeout) return@Runnable
            commandTimeout = null
            val transition = stateMachine.onCommandWindowTimedOut(generation)
            if (transition.accepted) {
                onStatus("voice_hands_free=command_timeout")
            }
        }
        commandTimeout = timeout
        mainHandler.postDelayed(timeout, COMMAND_WINDOW_MS)
    }

    private fun waitForOutputToSettle(generation: Long) {
        outputPoll?.let(mainHandler::removeCallbacks)
        outputObserved = false
        outputWaitStartedAtMs = SystemClock.elapsedRealtime()
        outputQuietSinceMs = null
        lateinit var poll: Runnable
        poll = Runnable {
            if (outputPoll !== poll) return@Runnable
            val state = stateMachine.snapshot()
            if (state !is HandsFreeVoiceState.Speaking || state.generation != generation) {
                outputPoll = null
                return@Runnable
            }
            val nowMs = SystemClock.elapsedRealtime()
            val outputActive = runCatching(isAppSpeechActive).getOrDefault(true)
            if (outputActive) {
                outputObserved = true
                outputQuietSinceMs = null
            } else if (outputObserved) {
                val quietSince = outputQuietSinceMs ?: nowMs.also { outputQuietSinceMs = it }
                if (nowMs - quietSince >= OUTPUT_QUIET_MS) {
                    finishOutput(generation)
                    return@Runnable
                }
            } else if (nowMs - outputWaitStartedAtMs >= NO_OUTPUT_SETTLE_MS) {
                finishOutput(generation)
                return@Runnable
            }
            if (nowMs - outputWaitStartedAtMs >= MAX_OUTPUT_WAIT_MS) {
                failClosed(generation, "output_timeout")
                return@Runnable
            }
            mainHandler.postDelayed(poll, OUTPUT_POLL_MS)
        }
        outputPoll = poll
        mainHandler.post(poll)
    }

    private fun finishOutput(generation: Long) {
        outputPoll = null
        val transition = stateMachine.onTtsFinished(generation)
        if (transition.accepted) {
            onStatus("voice_hands_free=waiting_wake_phrase")
        }
    }

    private fun handleError(runId: Long, error: VoskStreamingError) {
        if (closed) return
        if (activeRunId != null) {
            if (activeRunId != runId) return
        } else if (!starting) {
            return
        }
        activeRunId = null
        starting = false
        cancelScheduledWork()
        val generation = stateMachine.snapshot().generation
        stateMachine.onError(generation)
        onStatus("voice_hands_free=error ${error.name.lowercase()}")
        runCatching(onTerminalError)
    }

    private fun failClosed(generation: Long, reason: String) {
        outputPoll = null
        stateMachine.onError(generation)
        activeRunId = null
        starting = false
        transcriber.stop()
        onStatus("voice_hands_free=error $reason")
        runCatching(onTerminalError)
    }

    private fun shouldSuppressInput(): Boolean {
        if (closed || runCatching(isAppSpeechActive).getOrDefault(true)) return true
        return when (stateMachine.snapshot()) {
            is HandsFreeVoiceState.WaitingForWakeWord,
            is HandsFreeVoiceState.WaitingForCommand,
            -> false
            else -> true
        }
    }

    private fun cancelScheduledWork() {
        commandTimeout?.let(mainHandler::removeCallbacks)
        outputPoll?.let(mainHandler::removeCallbacks)
        commandTimeout = null
        outputPoll = null
    }

    private fun HandsFreeVoiceEligibility.allowsListening(): Boolean =
        walkState == WalkSessionState.ACTIVE &&
            microphoneGranted &&
            disclosureAccepted &&
            modelReady

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "HandsFreeVoiceController must be called on the main thread"
        }
    }

    private companion object {
        const val COMMAND_WINDOW_MS = 6_000L
        const val OUTPUT_POLL_MS = 100L
        const val OUTPUT_QUIET_MS = 500L
        const val NO_OUTPUT_SETTLE_MS = 1_200L
        const val MAX_OUTPUT_WAIT_MS = 30_000L
    }
}
