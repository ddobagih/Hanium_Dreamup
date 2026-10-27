package kr.co.hanium.dreamup.walksafe.feedback.delivery

import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.feedback.shouldSuppressFeedbackDuringVoiceRecognition

/** Owns delivery claims, terminal timers and the READY slot; never collects sensor observations. */
internal class RiskFeedbackDeliveryController<Receipt>(
    private val feedbackPolicy: WalkSafeFeedbackPolicy,
    private val frameStateLock: Any,
    private val phoneMountingObservationLock: Any,
    private val admission: RiskFeedbackAdmission,
    private val output: RiskFeedbackOutput,
    private val scheduler: RiskFeedbackScheduler,
    private val log: RiskFeedbackLog,
    private val frames: RiskFeedbackFrameDelivery<Receipt>,
) {
    private data class SpeechReadyRiskInput<Receipt>(
        val frame: TactileSnapshotFrameResult,
        val stale: Boolean,
        val qualitativeMode: Boolean,
        val feedbackGeneration: Int,
        val producerIsCurrent: () -> Boolean,
        val currentGateAllowsAlerts: (Long) -> Boolean,
        val feedbackActuatorOverride: TactileFrameFeedbackActuator?,
        val warningContext: kr.co.hanium.dreamup.walksafe.navigation.WarningRegionContext?,
        val guardedFeedbackDelivery: ((TactileFrameFeedbackDispatch, () -> Boolean) -> Unit)?,
        val sourceReceipt: Receipt?,
        val nowElapsedRealtimeMs: () -> Long,
    )

    private data class FeedbackDeliveryState(
        val activeFeedbackDeliveryKeys: Set<String> = emptySet(),
        val deviceGateAllowsAlerts: Boolean = false,
        val observedAtMs: Long = 0L,
        val distanceOutputsByDeliveryKey: Map<String, TrackedObjectDepth> = emptyMap(),
    )

    private data class PendingFeedbackTerminalResolution(
        val action: FeedbackAction,
        val policyEvaluatedAtMs: Long,
        val runnable: Runnable,
    )

    private var latestSpeechReadyRiskInput: SpeechReadyRiskInput<Receipt>? = null

    @Volatile
    private var latestFeedbackDeliveryState = FeedbackDeliveryState()

    private var feedbackRejectionFieldSessionId: String? = null

    private var lastFeedbackRejectionProducerScope: FeedbackRejectionProducerScope? = null

    private var lastFeedbackRejectionLifecycleGeneration: Int? = null

    private var lastFeedbackRejectionReasons: Set<FeedbackOutputRejection> = emptySet()

    private var pendingFeedbackTerminalResolution: PendingFeedbackTerminalResolution? = null

    fun emitTactileFrameFeedback(
        dispatch: TactileFrameFeedbackDispatch,
        producerScope: FeedbackRejectionProducerScope? = null,
        startIsCurrent: (() -> Boolean)? = null,
        producerIsCurrent: (() -> Boolean)? = null,
    ) {
        val feedbackGeneration = admission.generation()
        val activeRiskTrackIds = dispatch.activeRiskTrackIds
        val feedbackAction = dispatch.action
        val elapsedRealtimeMs = dispatch.policyEvaluatedAtMs
        scheduler.runOnUiThread {
            if (!admission.lifecycleCurrent(feedbackGeneration)) {
                feedbackAction?.let { action ->
                    feedbackPolicy.rejectUndeliveredFeedback(action, elapsedRealtimeMs)
                }
                return@runOnUiThread
            }
            if (producerIsCurrent?.invoke() == false) {
                feedbackAction?.let { action ->
                    feedbackPolicy.rejectUndeliveredFeedback(action, elapsedRealtimeMs)
                }
                return@runOnUiThread
            }
            latestFeedbackDeliveryState = FeedbackDeliveryState(
                activeFeedbackDeliveryKeys = dispatch.activeFeedbackDeliveryKeys,
                deviceGateAllowsAlerts = dispatch.deviceGateAllowsAlerts,
                observedAtMs = dispatch.policyEvaluatedAtMs,
                distanceOutputsByDeliveryKey = dispatch.distanceOutputsByDeliveryKey,
            )
            val outputEvaluation = dispatch.outputEvaluation
            if (
                producerScope != null && producerIsCurrent != null &&
                feedbackRejectionFieldSessionId != null && log.initialized() &&
                outputEvaluation != null &&
                outputEvaluation.evaluatedAtMs == dispatch.policyEvaluatedAtMs &&
                outputEvaluation.deviceGateAllowsAlerts == dispatch.deviceGateAllowsAlerts
            ) {
                val reasons = (
                    outputEvaluation.rejectionsByTrack.values.flatten() +
                        listOfNotNull(outputEvaluation.frameRejection)
                    ).distinct().toSet()
                if (
                    lastFeedbackRejectionProducerScope != producerScope ||
                    lastFeedbackRejectionLifecycleGeneration != feedbackGeneration ||
                    lastFeedbackRejectionReasons != reasons
                ) {
                    reasons.forEach { reason ->
                        log.recordEvent(
                            "feedback_output_rejected",
                            mapOf(
                                "reason" to reason.name,
                                "elapsed_realtime_ms" to outputEvaluation.evaluatedAtMs,
                            ),
                        )
                    }
                    lastFeedbackRejectionProducerScope = producerScope
                    lastFeedbackRejectionLifecycleGeneration = feedbackGeneration
                    lastFeedbackRejectionReasons = reasons
                }
            }
            output.reconcileTalkBackRiskTracks(activeRiskTrackIds)
            feedbackAction?.let { emitFeedbackAction(it, elapsedRealtimeMs, startIsCurrent) }
        }
    }

    fun resetFeedbackOutputRejectionDiagnostics() {
        lastFeedbackRejectionProducerScope = null
        lastFeedbackRejectionLifecycleGeneration = null
        lastFeedbackRejectionReasons = emptySet()
    }

    /** App-owned risk feedback retains explicit speech completion, including with TalkBack. */
    private fun emitFeedbackAction(
        action: FeedbackAction,
        policyEvaluatedAtMs: Long,
        startIsCurrent: (() -> Boolean)? = null,
    ) {
        val generation = admission.generation()
        if (!scheduler.isMainThread()) {
            scheduler.runOnUiThread {
                if (!admission.lifecycleCurrent(generation)) {
                    feedbackPolicy.rejectUndeliveredFeedback(action, policyEvaluatedAtMs)
                    return@runOnUiThread
                }
                emitFeedbackAction(action, policyEvaluatedAtMs, startIsCurrent)
            }
            return
        }
        if (!admission.lifecycleCurrent(generation) || startIsCurrent?.invoke() == false ||
            !isFeedbackActionStillDeliverable(action)) {
            feedbackPolicy.rejectUndeliveredFeedback(action, policyEvaluatedAtMs)
            return
        }
        // A nonurgent caution waits for a fresh observation after the current sentence. Do not
        // claim it or confirm vibration-only delivery, which would consume its speech retry.
        if (action.level == MessageLevel.CAUTION && output.currentActor()?.isAppSpeechActive() == true) {
            feedbackPolicy.rejectUndeliveredFeedback(action, policyEvaluatedAtMs)
            return
        }
        // A rejected replacement proposal must leave the accepted old waveform's timer intact.
        if (action.replacesDeliveryOwner == null) cancelPendingFeedbackTerminalResolution()
        if (!feedbackPolicy.claimFeedbackDelivery(action, policyEvaluatedAtMs, scheduler.nowMs())) return
        if (!feedbackPolicy.hasClaimedFeedbackDelivery(action)) {
            feedbackPolicy.rejectUndeliveredFeedback(action, policyEvaluatedAtMs)
            return
        }
        val expectedWalkEpoch = admission.runtimeEpochOrNull()
        var cancelled = false
        val completionCurrent: () -> Boolean = {
            !cancelled && expectedWalkEpoch != null &&
                admission.lifecycleCurrent(generation) &&
                admission.runtimeEpochOrNull() == expectedWalkEpoch &&
                feedbackPolicy.hasClaimedFeedbackDelivery(action)
        }
        val outputCurrent: () -> Boolean = {
            completionCurrent() && startIsCurrent?.invoke() != false && isFeedbackActionStillDeliverable(action) &&
                (action.level != MessageLevel.INFO || !output.guidanceVoiceInputActive())
        }
        val cancelDelivery: () -> Unit = {
            scheduler.runOnUiThread {
                cancelled = true
                // An old callback must not retire a newer action's terminal timer.
                val pending = pendingFeedbackTerminalResolution
                if (pending?.action == action && pending.policyEvaluatedAtMs == policyEvaluatedAtMs) {
                    cancelPendingFeedbackTerminalResolution()
                }
                feedbackPolicy.rejectUndeliveredFeedback(action, policyEvaluatedAtMs)
            }
        }
        if (!outputCurrent()) {
            cancelDelivery()
            return
        }
        val isRisk = action.level != MessageLevel.INFO
        if (isRisk) output.cancelGatewaySpeechInteraction("risk_feedback")
        if (isRisk && output.recognitionActive()) output.cancelVoiceCommandRecognition()
        if (shouldSuppressFeedbackDuringVoiceRecognition(output.guidanceVoiceInputActive(), isRisk) || !outputCurrent()) {
            cancelDelivery()
            return
        }
        val actuator = output.ensureActor()
        if (isRisk) {
            val feedbackStartedAtMs = scheduler.nowMs()
            var vibrationAccepted = false
            val dispatch = actuator.emit(
                action = action,
                isStillValidAtStart = outputCurrent,
                onSpeechCompleted = {
                    scheduler.runOnUiThread {
                        if (completionCurrent()) {
                            confirmFeedbackDelivery(action, policyEvaluatedAtMs, outputCompleted = true)
                        } else {
                            cancelDelivery()
                        }
                    }
                },
                onSpeechFailed = {
                    scheduler.runOnUiThread {
                        if (!completionCurrent()) {
                            cancelDelivery()
                        } else if (vibrationAccepted) {
                            scheduleFeedbackTerminalResolution(
                                action = action,
                                policyEvaluatedAtMs = policyEvaluatedAtMs,
                                generation = generation,
                                delayMs = (
                                    feedbackStartedAtMs + vibrationDurationMs(action) -
                                        scheduler.nowMs()
                                    ).coerceAtLeast(1L),
                            )
                        } else {
                            feedbackPolicy.rejectUndeliveredFeedback(action, policyEvaluatedAtMs)
                        }
                    }
                },
                onSpeechCancelled = cancelDelivery,
                distanceAfterWarning = {
                    val source = latestFeedbackDeliveryState.distanceOutputsByDeliveryKey[action.deliveryKey]
                    val refreshed = source?.sourceValidUntilElapsedRealtimeMs?.let { action.copy(validUntilMs = it) }
                    val detailCurrent = {
                        !cancelled && expectedWalkEpoch != null &&
                            admission.lifecycleCurrent(generation) &&
                            admission.runtimeEpochOrNull() == expectedWalkEpoch &&
                            startIsCurrent?.invoke() != false && refreshed != null && isFeedbackActionStillDeliverable(refreshed)
                    }
                    val text = source?.distanceBriefingFor(action, scheduler.nowMs())
                    if (!detailCurrent() || source == null || text == null) null
                    else ObstacleDistanceSpeech(text, source.toFeedbackCandidate()!!.validUntilMs) {
                        detailCurrent() &&
                            latestFeedbackDeliveryState.distanceOutputsByDeliveryKey[action.deliveryKey] === source &&
                            source.distanceBriefingFor(action, scheduler.nowMs()) != null
                    }
                },
            )
            vibrationAccepted = dispatch.vibrationAccepted
            if (dispatch.speech != NavigationSpeechDispatchResult.ACCEPTED && !cancelled) {
                if (vibrationAccepted) {
                    scheduleFeedbackTerminalResolution(
                        action = action,
                        policyEvaluatedAtMs = policyEvaluatedAtMs,
                        generation = generation,
                        delayMs = (
                            feedbackStartedAtMs + vibrationDurationMs(action) - scheduler.nowMs()
                            ).coerceAtLeast(1L),
                    )
                } else {
                    feedbackPolicy.rejectUndeliveredFeedback(action, policyEvaluatedAtMs)
                }
            }
        } else {
            val speech = actuator.speakNavigation(
                message = action.message,
                isStillValidAtStart = outputCurrent,
                requiresExplicitTerminalCallback = true,
                onCompleted = {
                    scheduler.runOnUiThread {
                        if (completionCurrent()) {
                            confirmFeedbackDelivery(action, policyEvaluatedAtMs, outputCompleted = true)
                        } else {
                            cancelDelivery()
                        }
                    }
                },
                onFailed = cancelDelivery,
                onCancelled = cancelDelivery,
            )
            if (speech != NavigationSpeechDispatchResult.ACCEPTED) cancelDelivery()
        }
    }

    private fun isFeedbackActionStillDeliverable(action: FeedbackAction): Boolean {
        val current = latestFeedbackDeliveryState
        val nowMs = scheduler.nowMs()
        val requiresRuntimeSafetyPermission = action.reason == "fresh_metric_depth_alert" ||
            action.reason == "fresh_metric_path_guidance" ||
            action.reason == "fresh_qualitative_depth_caution"
        if (requiresRuntimeSafetyPermission) {
            val epoch = admission.runtimeEpochOrNull() ?: return false
            val decision = admission.evaluateSafety(nowMs)
            if (!admission.runtimeEpochCurrent(epoch) ||
                decision.epoch != epoch || !decision.safetyOutputsAllowed
            ) return false
        }
        if (action.trackId.startsWith("unknown:") && admission.freshUnknownFeedbackBatches(nowMs).none { batch ->
                batch.outputs.any { output ->
                    output.trackId == action.trackId && output.userFacing.message == action.message &&
                        output.userFacing.messageLevel == action.level
                }
            }
        ) return false
        val currentDeliveryGateAllowsAlerts = if (action.reason == "fresh_qualitative_depth_caution") {
            admission.qualitativeAllowsWork(nowMs)
        } else {
            admission.deviceGateAllowsAlerts()
        }
        return nowMs <= action.validUntilMs &&
            action.deliveryKey in current.activeFeedbackDeliveryKeys &&
            current.deviceGateAllowsAlerts &&
            current.observedAtMs <= nowMs &&
            currentDeliveryGateAllowsAlerts
    }

    private fun vibrationDurationMs(action: FeedbackAction): Long =
        action.vibrationPatternMs?.sum()?.coerceAtLeast(1L) ?: 1L

    private fun scheduleFeedbackTerminalResolution(
        action: FeedbackAction,
        policyEvaluatedAtMs: Long,
        generation: Int,
        delayMs: Long,
    ) {
        cancelPendingFeedbackTerminalResolution()
        if (!scheduler.hasTerminalView()) {
            feedbackPolicy.rejectUndeliveredFeedback(action, policyEvaluatedAtMs)
            return
        }
        lateinit var resolution: Runnable
        resolution = Runnable {
            if (pendingFeedbackTerminalResolution?.runnable !== resolution) return@Runnable
            pendingFeedbackTerminalResolution = null
            if (admission.lifecycleCurrent(generation)) {
                // Only an accepted, uncancelled waveform owns this timer. Its completion can
                // outlive the capture deadline, which was checked before output started.
                confirmFeedbackDelivery(action, policyEvaluatedAtMs, outputCompleted = true)
            } else {
                feedbackPolicy.rejectUndeliveredFeedback(action, policyEvaluatedAtMs)
            }
        }
        pendingFeedbackTerminalResolution = PendingFeedbackTerminalResolution(
            action = action,
            policyEvaluatedAtMs = policyEvaluatedAtMs,
            runnable = resolution,
        )
        scheduler.postTerminal(resolution, delayMs.coerceAtLeast(1L))
    }

    fun cancelPendingFeedbackTerminalResolution() {
        val pending = pendingFeedbackTerminalResolution ?: return
        pendingFeedbackTerminalResolution = null
        if (scheduler.hasTerminalView()) scheduler.removeTerminal(pending.runnable)
        feedbackPolicy.rejectUndeliveredFeedback(
            pending.action,
            pending.policyEvaluatedAtMs,
        )
    }

    private fun confirmFeedbackDelivery(
        action: FeedbackAction,
        policyEvaluatedAtMs: Long,
        outputCompleted: Boolean = false,
    ) {
        // Capture freshness limits output START, not completion of accepted speech/vibration.
        // Completion still requires the caller's lifecycle fence and the policy's claimed lease.
        val unknownContextCurrent = if (action.trackId.startsWith("unknown:"))
            admission.unknownFeedbackTrackIsCurrent(action.trackId)
            else true
        if (!unknownContextCurrent || !admission.deviceGateAllowsAlerts() ||
            (!outputCompleted && !isFeedbackActionStillDeliverable(action))
        ) {
            feedbackPolicy.rejectUndeliveredFeedback(action, policyEvaluatedAtMs)
            return
        }
        val deliveredAtMs = scheduler.nowMs()
        val confirmed = synchronized(phoneMountingObservationLock) {
            if (!admission.phoneMountingOutputsAllowed()) {
                feedbackPolicy.rejectUndeliveredFeedback(
                    action,
                    policyEvaluatedAtMs,
                )
                false
            } else {
                feedbackPolicy.confirmFeedbackDelivery(
                    action,
                    policyEvaluatedAtMs,
                    deliveredAtMs,
                )
            }
        }
        if (!confirmed) return
        log.recordEvent(
            if (action.level == kr.co.hanium.dreamup.walksafe.depth.MessageLevel.INFO) {
                "path_guidance_emitted"
            } else {
                "risk_feedback_emitted"
            },
            mapOf("level" to action.level.name, "reason" to action.reason),
        )
    }

    fun reevaluateCurrentRiskAfterSpeechReady(
        expectedActuator: AndroidFeedbackActuator,
        expectedLifecycleGeneration: Int,
        nowMs: Long,
    ) {
        val input = synchronized(frameStateLock) { latestSpeechReadyRiskInput } ?: return
        fun inputCurrent(atMs: Long): Boolean {
            if (input.feedbackGeneration != expectedLifecycleGeneration ||
                !admission.lifecycleCurrent(expectedLifecycleGeneration) ||
                output.currentActor() !== expectedActuator || input.stale ||
                synchronized(frameStateLock) { latestSpeechReadyRiskInput !== input }) return false
            // Source and capability checks can take other locks; the slot lock protects only identity.
            if (!input.producerIsCurrent() || !input.currentGateAllowsAlerts(atMs)) return false
            return admission.lifecycleCurrent(expectedLifecycleGeneration) &&
                output.currentActor() === expectedActuator &&
                synchronized(frameStateLock) { latestSpeechReadyRiskInput === input }
        }
        if (!inputCurrent(nowMs)) return
        // Keep the complete original capture for warning-region continuity. Only risk actions
        // may leave this READY evaluation; INFO/AWARE are never replayed as deferred speech.
        val independentRisk = if (input.qualitativeMode) emptyList() else admission.freshUnknownFeedbackBatches(nowMs)
        if (input.frame.guidance.outputs.none { it.userFacing.messageLevel >= MessageLevel.CAUTION } &&
            independentRisk.none { batch -> batch.outputs.any { it.userFacing.messageLevel >= MessageLevel.CAUTION } }) return
        if (!inputCurrent(nowMs)) return
        val delivery = TactileFrameFeedbackActuator { dispatch ->
            if (!inputCurrent(nowMs) || dispatch.action?.level?.let { it < MessageLevel.CAUTION } == true) {
                dispatch.action?.let { feedbackPolicy.rejectUndeliveredFeedback(it, dispatch.policyEvaluatedAtMs) }
            } else {
                val stillCurrent = { inputCurrent(input.nowElapsedRealtimeMs()) }
                val guarded = input.guardedFeedbackDelivery
                if (guarded != null) guarded(dispatch, stillCurrent)
                else (input.feedbackActuatorOverride ?: TactileFrameFeedbackActuator {
                    frames.deliver(it)
                }).emit(dispatch)
            }
        }
        val receipt = input.sourceReceipt
        if (receipt != null) frames.dispatchConsumedTactileFrameFeedback(
            receipt = receipt,
            frame = input.frame,
            stale = false,
            deviceGateAllowsAlerts = true,
            nowMs = nowMs,
            feedbackActuatorOverride = delivery,
            independentFreshOutputs = independentRisk,
            warningContext = input.warningContext,
        ) else frames.dispatchTactileFrameFeedback(
            frame = input.frame,
            stale = false,
            deviceGateAllowsAlerts = true,
            nowMs = nowMs,
            feedbackActuatorOverride = delivery,
            independentFreshOutputs = independentRisk,
            warningContext = input.warningContext,
        )
    }

    /** Retains only the latest input values; it owns no action, utterance, or camera buffer. */
    fun rememberSpeechReadyRiskInput(
        frame: TactileSnapshotFrameResult,
        stale: Boolean,
        qualitativeMode: Boolean,
        producerIsCurrent: () -> Boolean,
        currentGateAllowsAlerts: (Long) -> Boolean,
        feedbackActuatorOverride: TactileFrameFeedbackActuator? = null,
        warningContext: kr.co.hanium.dreamup.walksafe.navigation.WarningRegionContext? = null,
        guardedFeedbackDelivery: ((TactileFrameFeedbackDispatch, () -> Boolean) -> Unit)? = null,
        sourceReceipt: Receipt? = null,
        nowElapsedRealtimeMs: () -> Long = scheduler::nowMs,
    ) {
        val input = SpeechReadyRiskInput<Receipt>(
            frame = frame.copy(matchedEvidence = null, depthDetections = emptyList()),
            stale = stale,
            qualitativeMode = qualitativeMode,
            feedbackGeneration = admission.generation(),
            producerIsCurrent = producerIsCurrent,
            currentGateAllowsAlerts = currentGateAllowsAlerts,
            feedbackActuatorOverride = feedbackActuatorOverride,
            warningContext = warningContext,
            guardedFeedbackDelivery = guardedFeedbackDelivery,
            sourceReceipt = sourceReceipt,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
        )
        synchronized(frameStateLock) { latestSpeechReadyRiskInput = input }
    }

    fun clearSpeechReadyRiskInput() {
        synchronized(frameStateLock) { latestSpeechReadyRiskInput = null }
    }

    fun cancelTerminalForTrackPrefix(prefix: String) {
        pendingFeedbackTerminalResolution?.takeIf { it.action.trackId.startsWith(prefix) }
            ?.let { cancelPendingFeedbackTerminalResolution() }
    }
    fun resetDeliveryState() { latestFeedbackDeliveryState = FeedbackDeliveryState() }
    fun latestDeviceGateAllowsAlerts(): Boolean = latestFeedbackDeliveryState.deviceGateAllowsAlerts
    fun clearRejectionFieldSession() { feedbackRejectionFieldSessionId = null }
    fun setRejectionFieldSession(sessionId: String?) { feedbackRejectionFieldSessionId = sessionId }
    fun rejectionFieldSessionId(): String? = feedbackRejectionFieldSessionId
    fun canSpeakNavigation(nowMs: Long) = feedbackPolicy.canSpeakNavigation(nowMs)
    fun cancelFeedbackForTrackPrefix(prefix: String) = feedbackPolicy.cancelFeedbackForTrackPrefix(prefix)
    fun discardUnstartedFeedbackForTrackPrefix(prefix: String) = feedbackPolicy.discardUnstartedFeedbackForTrackPrefix(prefix)
    fun cancelPendingFeedbackDeliveries() = feedbackPolicy.cancelPendingFeedbackDeliveries()
    fun resetForNewWalk() = feedbackPolicy.resetForNewWalk()
}
