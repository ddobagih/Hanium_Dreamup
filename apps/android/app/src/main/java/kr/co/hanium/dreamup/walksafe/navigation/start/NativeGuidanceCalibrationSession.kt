package kr.co.hanium.dreamup.walksafe.navigation.start

import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativePhoneMountingCheckRequest

/** Owns pending calibration speech and both retry registrations for one native start request. */
internal class NativeGuidanceCalibrationSession(
    private val scope: Scope,
    private val speech: Speech,
    private val callbacks: Callbacks,
    private val effects: Effects,
    private val elapsedRealtimeMs: () -> Long,
) {
    interface Scope {
        fun requestCurrent(request: NativePhoneMountingCheckRequest): Boolean
        fun ownerCurrent(request: NativePhoneMountingCheckRequest): Boolean
    }
    interface Speech {
        fun pending(): Boolean
        fun commandGeneration(): Long
        fun submit(message: String, current: () -> Boolean, completed: () -> Unit, failed: () -> Unit): Boolean
        fun cancel()
    }
    interface Callbacks {
        fun postDelayed(action: Runnable, delayMs: Long)
        fun remove(action: Runnable)
    }
    interface Effects {
        fun recordStage(stage: String)
        fun reportVisibleFailure(message: String)
        fun visibleFailure(): String?
        fun clearVisibleFailure()
        fun render()
        fun continuePrewalk()
        fun recoverPreparation(request: NativePhoneMountingCheckRequest)
    }
    private val nativeGuidanceCalibration = GuidanceStartCalibration()
    private var nativeCalibrationRetry: Runnable? = null
    private var nativeCalibrationSpeechGeneration: Long? = null
    private var nativeCalibrationSpeechFailures = 0
    private var nativeCalibrationSpeechLease: GuidanceStartCalibration.PromptLease? = null
    private var nativePreparationRecoveryRetry: Runnable? = null

    private fun scheduleNativeCalibration(request: NativePhoneMountingCheckRequest, delayMs: Long) {
        if (nativeCalibrationRetry != null || !scope.ownerCurrent(request)) return
        val task = object : Runnable {
            override fun run() {
                if (nativeCalibrationRetry !== this) return
                nativeCalibrationRetry = null
                if (scope.ownerCurrent(request)) effects.continuePrewalk()
            }
        }
        nativeCalibrationRetry = task
        callbacks.postDelayed(task, delayMs)
    }

    /** Reads completed proof only; rendering must never advance time, claim speech, or post work. */
    fun isReady(request: NativePhoneMountingCheckRequest): Boolean {
        val snapshot = nativeGuidanceCalibration.snapshot ?: return false
        return snapshot.attempt.requestIdentity === request && scope.ownerCurrent(request) &&
            snapshot.phase == GuidanceStartCalibration.Phase.READY &&
            snapshot.readyAtMs?.let { elapsedRealtimeMs() >= it } == true
    }

    fun advance(request: NativePhoneMountingCheckRequest): Boolean {
        val nowMs = elapsedRealtimeMs()
        if (!scope.ownerCurrent(request)) { cancel(); return false }
        val previousAttempt = nativeGuidanceCalibration.currentAttempt
        val attempt = nativeGuidanceCalibration.begin(request, nowMs) ?: return false
        if (attempt !== previousAttempt) nativeCalibrationSpeechFailures = 0
        val snapshot = nativeGuidanceCalibration.evaluate(attempt, nowMs, true) ?: return false
        if (snapshot.phase == GuidanceStartCalibration.Phase.READY) {
            // The window is complete; the existing live startup gates decide sensor quality.
            // A missing sensor must produce its corrective notice there, not an endless quiet calibration.
            effects.recordStage("DIRECTION_CALIBRATION_READY")
            return true
        }
        if (snapshot.phase == GuidanceStartCalibration.Phase.OBSERVING) {
            effects.recordStage("DIRECTION_CALIBRATION_OBSERVING")
            scheduleNativeCalibration(request, (requireNotNull(snapshot.readyAtMs) - nowMs).coerceIn(1L, 300L))
            return false
        }
        val lease = nativeGuidanceCalibration.claimPrompt(attempt, nowMs, true,
            speech.pending())
        if (lease != null) {
            nativeCalibrationSpeechLease = lease
            fun current(): Boolean = scope.ownerCurrent(request) && nativeGuidanceCalibration.isPromptCurrent(lease)
            val failed = {
                if (nativeGuidanceCalibration.voiceFailed(lease, elapsedRealtimeMs(), scope.ownerCurrent(request),
                        retryDelayMs = if (nativeCalibrationSpeechFailures == 0) 1_000L else 5_000L)) {
                    if (nativeCalibrationSpeechLease === lease) { nativeCalibrationSpeechLease = null; nativeCalibrationSpeechGeneration = null }
                    nativeCalibrationSpeechFailures++
                    if (nativeCalibrationSpeechFailures >= 2) {
                        effects.reportVisibleFailure("한국어 음성 안내를 재생하지 못했습니다. 휴대전화의 한국어 음성 설정을 확인해 주세요. 복구되면 자동으로 안내를 시작합니다.")
                        effects.render()
                    }
                    scheduleNativeCalibration(request, if (nativeCalibrationSpeechFailures < 2) 1_000L else 5_000L)
                }
                Unit
            }
            val accepted = speech.submit(
                GuidanceStartCalibration.PROMPT_KO,
                current = ::current,
                completed = {
                    val doneAtMs = elapsedRealtimeMs() // receipt of actual TTS DONE, never estimated duration
                    if (nativeGuidanceCalibration.voiceDone(lease, doneAtMs, doneAtMs, scope.ownerCurrent(request))) {
                        if (nativeCalibrationSpeechLease === lease) { nativeCalibrationSpeechLease = null; nativeCalibrationSpeechGeneration = null }
                        nativeCalibrationSpeechFailures = 0
                        if (effects.visibleFailure()?.startsWith("한국어 음성 안내를 재생하지 못했습니다.") == true) effects.clearVisibleFailure()
                        scheduleNativeCalibration(request, 300L)
                    }
                }, failed = failed,
            )
            if (!accepted) failed()
            else if (nativeGuidanceCalibration.isPromptCurrent(lease)) nativeCalibrationSpeechGeneration = speech.commandGeneration()
            effects.recordStage("DIRECTION_CALIBRATION_PROMPT")
        }
        scheduleNativeCalibration(request, if (nativeCalibrationSpeechFailures < 2) 1_000L else 5_000L)
        return false
    }

    fun cancel() {
        nativePreparationRecoveryRetry?.let { callbacks.remove(it) }
        nativePreparationRecoveryRetry = null
        cancelPrompt()
    }

    /** Access correction must not keep resetting an already-owned recovery timer. */
    fun cancelPrompt() {
        nativeCalibrationRetry?.let { callbacks.remove(it) }
        nativeCalibrationRetry = null
        val ownedSpeech = nativeCalibrationSpeechGeneration
        nativeGuidanceCalibration.cancel()
        nativeCalibrationSpeechFailures = 0
        nativeCalibrationSpeechGeneration = null
        nativeCalibrationSpeechLease = null
        if (ownedSpeech != null && speech.commandGeneration() == ownedSpeech) speech.cancel()
    }

    /** Keep the chosen destination/intent. Retry existing preparation after corrective conditions recover. */
    fun scheduleRecovery(request: NativePhoneMountingCheckRequest) {
        if (nativePreparationRecoveryRetry != null || !scope.requestCurrent(request)) return
        val task = object : Runnable {
            override fun run() {
                if (nativePreparationRecoveryRetry !== this) return
                nativePreparationRecoveryRetry = null
                if (!scope.requestCurrent(request)) return
                effects.recoverPreparation(request)
                effects.continuePrewalk()
            }
        }
        nativePreparationRecoveryRetry = task
        callbacks.postDelayed(task, 2_000L)
    }

}
