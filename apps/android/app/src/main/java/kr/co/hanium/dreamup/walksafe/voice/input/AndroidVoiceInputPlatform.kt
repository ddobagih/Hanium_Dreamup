package kr.co.hanium.dreamup.walksafe.voice.input

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kr.co.hanium.dreamup.walksafe.feedback.ForegroundAacRecorder
import kr.co.hanium.dreamup.walksafe.feedback.TemporaryGatewayWavPlayer
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.voice.*

/** Android resource factories only. Existing SDK/state-machine implementations are unchanged. */
internal class AndroidVoiceInputPlatform(private val context: Context) :
    VoiceInputPlatform, ForegroundWakePlatform, GatewayVoicePlatform {
    private val application get() = context.applicationContext as? WalkSafeApplication
    private val modelWorker by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "walksafe-voice-model").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }
    }
    private val gatewayWorker by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "walksafe-gateway-speech").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }
    }
    private val gatewayClient = AndroidGatewaySpeechClient()
    override fun applicationAvailable() = application != null
    override fun modelAssetAvailable() = BundledVoskModelInstaller.bundledModelAvailable(context)
    override fun prepareInstalledModel() = BundledVoskModelInstaller.installedModelOrNull(context)
        ?: BundledVoskModelInstaller.install(context)
    override fun executeModelWork(action: () -> Unit) { modelWorker.execute(action) }
    override fun shutdownModelWorker() { modelWorker.shutdownNow() }
    override fun createRecognizer(modelDirectory: () -> File?): VoiceCommandRecognizer {
        val delegate = PreferredOfflineSpeechRecognizer(context, modelDirectory)
        return object : VoiceCommandRecognizer {
            override fun setRecognitionListener(listener: RecognitionListener) = delegate.setRecognitionListener(listener)
            override fun startListening(intent: Intent, preferPlatform: Boolean, isRequestCurrent: () -> Boolean) =
                delegate.startListening(intent, preferPlatform, isRequestCurrent)
            override fun cancel() = delegate.cancel()
            override fun destroy() = delegate.destroy()
        }
    }
    override fun commandIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }
    override fun createHandsFree(modelDirectory: File, callbacks: HandsFreeInputCallbacks) = HandsFreeVoiceController(
        context = context.applicationContext, modelDirectory = modelDirectory,
        eligibility = callbacks.eligibility, isAppSpeechActive = callbacks.speechActive,
        onCommand = callbacks.command, onStatus = callbacks.status,
        onCommandListeningStarted = callbacks.listeningStarted, onTerminalFailure = callbacks.terminalFailure,
        onSessionEnded = callbacks.sessionEnded, isSpeechRecognitionBlocked = callbacks.recognitionBlocked,
        isCommandWorkPending = callbacks.workPending, commandSpeechProgress = callbacks.commandSpeechProgress, commandSpeechRevision = callbacks.commandSpeechRevision, speakLowConfidenceReprompt = callbacks.lowConfidenceReprompt,
    )
    override fun attachHandsFree(controller: HandsFreeVoiceController): Boolean {
        val app = application ?: return false
        app.attachWalkVoiceSessionController(controller)
        return true
    }
    override fun detachHandsFree(controller: HandsFreeVoiceController) { application?.detachWalkVoiceSessionController(controller) }
    override fun setUserStopListener(listener: ((WalkVoiceSessionController) -> Unit)?) { application?.setWalkVoiceUserStopListener(listener) }
    override fun isAttached(controller: HandsFreeVoiceController) = application?.walkVoiceSessionController === controller
    override fun userStoppedWalk(walkId: String) = application?.walkVoiceUserStopHold?.blocksAutomaticStart(walkId) == true
    override fun recordUserStop(walkId: String) { application?.walkVoiceUserStopHold?.recordUserStop(walkId) }
    override fun registerStart(controller: HandsFreeVoiceController,
        onFailure: (Long, WalkVoiceSessionController, WalkVoiceStartFailure) -> Unit): Long? =
        application?.registerWalkVoiceStartRequest(controller, onFailure)
    override fun invalidateStart(requestId: Long, controller: HandsFreeVoiceController) {
        application?.invalidateWalkVoiceStartRequest(requestId, controller)
    }
    override fun invalidateStartOnSessionEnd(requestId: Long, controller: HandsFreeVoiceController): Boolean? =
        application?.invalidateWalkVoiceStartRequestOnSessionEnd(requestId, controller)
    override fun reportStartFailure(requestId: Long, controller: HandsFreeVoiceController, failure: WalkVoiceStartFailure) {
        application?.reportWalkVoiceStartFailure(requestId, controller, failure)
    }
    override fun startService(requestId: Long) {
        context.startService(Intent(context, WalkVoiceForegroundService::class.java)
            .setAction(WalkVoiceForegroundService.ACTION_START)
            .putExtra(WalkVoiceForegroundService.EXTRA_START_REQUEST_ID, requestId))
    }
    override fun stopService() { context.stopService(Intent(context, WalkVoiceForegroundService::class.java)) }
    override fun inputHeldElsewhere(): Boolean = runCatching {
        val audio = context.getSystemService(AudioManager::class.java) ?: return@runCatching false
        audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audio.activeRecordingConfigurations.isNotEmpty()
    }.getOrDefault(false)
    override fun createAcknowledgement(speechActive: () -> Boolean): WakeAcknowledgementEffect {
        val player = WakeAcknowledgementPlayer(context, speechActive)
        return object : WakeAcknowledgementEffect {
            override fun isPlaying() = player.isPlaying()
            override fun play(onFinished: (WakeAcknowledgementResult) -> Unit) = player.play(onFinished)
            override fun cancel() = player.cancel()
            override fun close() = player.close()
        }
    }
    override fun createProbe(callbacks: ForegroundWakeCallbacks): WakeProbeEffect {
        val probe = VoskWakePhraseProbe(context = context, modelDirectory = callbacks.directory,
            listenUntilWake = true, inputGeneration = callbacks.inputGeneration,
            isInputSuppressed = callbacks.suppressed, onReady = callbacks.ready, onResult = callbacks.result)
        return object : WakeProbeEffect {
            override fun start() { probe.start() }
            override fun cancel() = probe.cancel()
        }
    }
    override fun createRecorder(onLimitReached: () -> Unit): GatewayVoiceRecorder = createRecorder(onLimitReached, {})
    override fun createRecorder(onLimitReached: () -> Unit, onInputFailure: (VoiceInputFailure) -> Unit): GatewayVoiceRecorder {
        val recorder = ForegroundAacRecorder(context.cacheDir, onLimitReached, onInputFailure)
        return object : GatewayVoiceRecorder {
            override val isRecording get() = recorder.isRecording
            override fun start() = recorder.start()
            override fun stop() = recorder.stop()
            override fun cancel() = recorder.cancel()
        }
    }
    override fun createPlayer(): GatewayVoicePlayer {
        val player = TemporaryGatewayWavPlayer()
        return object : GatewayVoicePlayer {
            override fun play(temporaryFile: File, onCompleted: () -> Unit, onFailed: () -> Unit) =
                player.play(temporaryFile, onCompleted, onFailed)
            override fun cancel() = player.cancel()
        }
    }
    override fun transcribe(session: GatewayFieldSession, audio: File, requestId: String) =
        gatewayClient.transcribeCall(session, audio, requestId)
    override fun synthesize(session: GatewayFieldSession, message: String, requestId: String) =
        gatewayClient.synthesizeCall(session, message, requestId, context.cacheDir)
    override fun execute(action: () -> Unit) { gatewayWorker.execute(action) }
    override fun shutdownWorker() { gatewayWorker.shutdownNow() }
}

internal object AndroidVoiceInputDiagnostics : VoiceInputDiagnostics {
    override fun input(event: VoiceInputDiagnosticEvent, confidence: Float?, resultCount: Int?, errorCode: Int?) =
        logVoiceInputDiagnostic(event, confidence = confidence, resultCount = resultCount, errorCode = errorCode)
    override fun wake(message: String) { android.util.Log.d("WalkSafeVoiceInput", message) }
    override fun serviceInfo(message: String) { android.util.Log.i("WalkSafeVoice", message) }
    override fun serviceWarning(message: String, error: Throwable?) {
        if (error == null) android.util.Log.w("WalkSafeVoice", message)
        else android.util.Log.w("WalkSafeVoice", message, error)
    }
}
