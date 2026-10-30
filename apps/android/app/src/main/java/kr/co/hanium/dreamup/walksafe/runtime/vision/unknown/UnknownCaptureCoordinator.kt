package kr.co.hanium.dreamup.walksafe.runtime.vision.unknown

import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownLumaFrameQueue
import kr.co.hanium.dreamup.walksafe.navigation.TactileMaskCaptureJoin
import kr.co.hanium.dreamup.walksafe.navigation.TactileMaskCaptureBundle
import kr.co.hanium.dreamup.walksafe.inference.RuntimeLoadCompletion
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingConfig
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointTracker
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointCaptureLease
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import kr.co.hanium.dreamup.walksafe.device.CameraDetectorAdmissionPolicy
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.inference.TactileSemanticCapture
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.inference.AdaptiveInferencePacingPolicy
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamResult
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeAttachment
import kr.co.hanium.dreamup.walksafe.depth.UnknownObjectFeedbackBatch
import kr.co.hanium.dreamup.walksafe.depth.UnknownObjectFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownDepthFrameResult
import kr.co.hanium.dreamup.walksafe.depth.unknown.sameCapture
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownObjectDepthPipeline
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class UnknownCaptureCoordinator(
    private val frameStateLock: Any,
    private val detectorExecutor: Executor,
    private val mainDispatcher: (() -> Unit) -> Unit,
    private val scopeReader: UnknownCaptureScopeReader,
    private val clock: UnknownCaptureClock,
    private val observer: UnknownCaptureObserver,
    private val services: UnknownRuntimeServiceOwner,
    private val detectionInFlight: () -> Boolean,
) {
    private val unknownFrameCopyInFlight = AtomicBoolean(false)
    @Volatile private var unknownFrameCopyOwner: UnknownFrameCopyClaim? = null
    @Volatile private var unknownProcessingContext: UnknownProcessingContext? = null
    @Volatile private var latestUnknownFeedback: UnknownFeedbackObservation? = null
    private var unknownRuntimeStatus: String
        get() = services.status
        set(value) { services.reportCaptureStatus(value) }
    private val unknownContextSequence = AtomicLong(0L)
    private val unknownRuntimeService get() = services.currentService
    private val isActivityForeground get() = scopeReader.runtime().foreground
    private val detectorClosing get() = scopeReader.runtime().detectorClosing
    private val detectorLoadGeneration get() = scopeReader.runtime().modelGeneration
    private val visualGeometryVersion get() = scopeReader.runtime().geometryVersion
    private val arSessionGeneration get() = scopeReader.runtime().arGeneration
    private val feedbackLifecycleGeneration get() = scopeReader.runtime().feedbackLifecycleGeneration
    private val reportRuntimeConfig get() = scopeReader.runtime().runtimeConfig
    private val latestInferenceThermalThrottled get() = scopeReader.execution().thermalThrottled
    private val latestUnknownLoadObservation get() = services.latestLoadObservation
    private val activeCameraFrameQualityProfile get() = scopeReader.cameraProfile()
    val status: String get() = unknownRuntimeStatus
    val frameCopyInFlight: Boolean get() = unknownFrameCopyInFlight.get()

    fun feedbackTrackIsCurrent(trackId: String): Boolean =
        if (trackId.startsWith("unknown:")) unknownProcessingContext?.let { context ->
            trackId.startsWith("unknown:${context.identity}:") && isUnknownContextCurrent(context)
        } == true else true

    private fun isCurrentFrameGeneration(generation: Int, epoch: WalkRuntimeEpoch): Boolean =
        scopeReader.ownsFrame(generation, epoch)
    private fun currentRuntimeMetricOutputAllowsWork(arGeneration: Long): Boolean =
        scopeReader.metricOutputAllowsWork(arGeneration)
    private fun runtimeRecognitionMatchesDepth(cameraTimestampNs: Long?, frameId: Long, snapshot: DepthFrameSnapshot): Boolean =
        cameraTimestampNs != null && cameraTimestampNs > 0L && frameId > 0L &&
            snapshot.frameTimestampNs == frameId && snapshot.cameraImageTimestampNs == cameraTimestampNs
    fun invalidateUnknownObjectObservations() = invalidateUnknownObjectObservations(mainDispatcher)


    /** Each service/camera/walk lease owns its history; a late callback can only mutate its old copy. */
    private class UnknownProcessingContext(
        val identity: Long,
        val service: FastSamRuntimeService<UnknownRuntimeAttachment>,
        val detectorGeneration: Int,
        val walkEpoch: WalkRuntimeEpoch,
        val arSessionGeneration: Long,
        val geometryId: String,
        val imageQuarterTurns: Int,
        stepLengthM: Float,
        clock: UnknownCaptureClock,
        minimumIntervalMs: Long?,
    ) {
        val pipeline = UnknownObjectDepthPipeline(
            maximumSourceAgeMs = MAX_DEPTH_DETECTION_SOURCE_AGE_MS,
            elapsedRealtimeMs = clock::elapsedRealtimeMs,
        ).also { it.setUserStepLength(stepLengthM) }
        val materialPointTrackingConfig = VisualTrackingConfig()
        val materialPointTracker = UnknownMaterialPointTracker(materialPointTrackingConfig)
        val frameQueue = UnknownLumaFrameQueue<UnknownQueuedLumaFrame>(materialPointTrackingConfig.maxHistoryFrames)
        val feedback = UnknownObjectFeedbackPolicy(stepLengthM)
        val pacing = AdaptiveInferencePacingPolicy(MAX_DEPTH_DETECTION_SOURCE_AGE_MS).also { pacing ->
            minimumIntervalMs?.let(pacing::configureMinimumInterval)
        }
        @Volatile var activeAttempt: UnknownCaptureAttempt? = null
        var tactileJoin: UnknownTactileJoin? = null
    }

    private data class UnknownTactileJoin(
        val context: UnknownProcessingContext,
        val processingLease: UnknownCaptureProcessingLease,
        val modelGeneration: Long,
        val captureLease: UnknownMaterialPointCaptureLease,
        val capture: TactileMaskCaptureJoin,
        val geometryVersion: Long,
    )

    private data class UnknownFrameAttachment(
        val context: UnknownProcessingContext,
        val capture: FrozenUnknownDepthCapture,
        val attempt: UnknownCaptureAttempt,
        val processingLease: UnknownCaptureProcessingLease,
        val captureLease: UnknownMaterialPointCaptureLease,
        val tactileJoin: UnknownTactileJoin? = null,
    ) : UnknownRuntimeAttachment

    private class UnknownFrameCopyClaim(val context: UnknownProcessingContext)

    private data class UnknownFrameSubmission(
        val input: UnknownCaptureFrameInput,
        val evidence: UnknownCaptureEvidence,
        val claim: UnknownFrameCopyClaim,
    )

    private data class UnknownQueuedLumaFrame(
        val input: UnknownCaptureLumaInput,
        val processingLease: UnknownCaptureProcessingLease,
        // Only the one admitted submission retains RGB and frozen depth/motion evidence.
        val submission: UnknownFrameSubmission?,
    )

    private data class UnknownFeedbackObservation(
        val context: UnknownProcessingContext,
        val batches: List<UnknownObjectFeedbackBatch>,
    )

    fun unknownFrameCaptureIsDue(nowMs: Long): Boolean {
        if (detectionInFlight() || unknownFrameCopyInFlight.get()) return false
        val service = unknownRuntimeService ?: return false
        val state = service.stats()
        if (state.stopping || state.inflight || state.pending || state.copying) return false
        val context = unknownProcessingContext?.takeIf { it.service === service } ?: return true
        // Backstop for an owner that terminated without a result callback. Do not invent a latency
        // sample; normal discards carry their actual completion time through onDiscard below.
        synchronized(frameStateLock) {
            context.activeAttempt?.takeIf {
                nowMs - it.ticket.startedAtElapsedRealtimeMs > MAX_DEPTH_DETECTION_SOURCE_AGE_MS
            }?.let { cancelAttempt(context, it) }
        }
        return context.pacing.isDue(nowMs)
    }

    /** Qualified intermediate frames still enter the luma queue without claiming RGB work. */
    internal fun selectUnknownAvailableFrameForQueue(
        captureIsDue: Boolean,
        claimSubmission: () -> Boolean,
    ): Boolean? = captureIsDue && claimSubmission()

    private fun getOrCreateUnknownProcessingContext(
        input: UnknownCaptureFrameInput, generation: Int, epoch: WalkRuntimeEpoch, arGeneration: Long,
    ): UnknownProcessingContext? {
        val bound = input.unknownLuma ?: return null
        val token = bound.capture.token
        return synchronized(frameStateLock) {
            if (unknownRuntimeService !== bound.service || !isCurrentFrameGeneration(generation, epoch) ||
                visualGeometryVersion != input.geometryVersion || arSessionGeneration != arGeneration ||
                token.sessionEpoch != bound.service.sessionEpoch || token.cpuImageTimestampNs != input.cameraTimestampNs ||
                token.geometryId != "$arGeneration:${input.geometryVersion}:${token.width}x${token.height}:q${input.imageQuarterTurns}"
            ) return null
            unknownProcessingContext?.takeIf {
                it.service === bound.service && it.detectorGeneration == generation && it.walkEpoch == epoch &&
                    it.arSessionGeneration == arGeneration && it.geometryId == token.geometryId &&
                    it.imageQuarterTurns == input.imageQuarterTurns && it.frameQueue.isAccepting
            } ?: run {
                invalidateUnknownObjectObservations()
                UnknownProcessingContext(unknownContextSequence.incrementAndGet(), bound.service, generation,
                    epoch, arGeneration, token.geometryId, input.imageQuarterTurns, scopeReader.runtime().stepLengthM,
                    clock, services.minimumAuxiliaryIntervalMs(bound.service))
                    .also { unknownProcessingContext = it; latestUnknownFeedback = null }
            }
        }
    }

    private fun releaseUnknownFrameCopyClaim(claim: UnknownFrameCopyClaim?) = synchronized(frameStateLock) {
        if (claim != null && unknownFrameCopyOwner === claim) {
            unknownFrameCopyOwner = null
            unknownFrameCopyInFlight.set(false)
        }
    }

    /** The existing detector owner may wait for LK; the GL/UI queue and cancellation never do. */
    private fun processQueuedUnknownLumaFrame(context: UnknownProcessingContext, frame: UnknownQueuedLumaFrame) {
        try {
            if (frame.input.service !== context.service || !isUnknownCaptureContextCurrent(context, frame.processingLease)) return
            val admission = context.materialPointTracker.offerOwnedLuma(frame.input.capture, clock.elapsedRealtimeMs())
            if (!admission.accepted || !isUnknownCaptureContextCurrent(context, frame.processingLease)) return
            val submission = frame.submission ?: return
            val input = submission.input
            val qualityObservation = input.qualityObservation
            observer.observeCamera(qualityObservation)
            CameraDetectorAdmissionPolicy.admit(context.walkEpoch, clock.elapsedRealtimeMs(),
                qualityObservation, activeCameraFrameQualityProfile) {
                submitUnknownObjectFrame(context, input, submission.evidence,
                    context.detectorGeneration, context.walkEpoch, context.arSessionGeneration,
                    frame.input.capture.token.capturedElapsedNs / 1_000_000L, input.geometryVersion)
            }
        } finally { releaseUnknownFrameCopyClaim(frame.submission?.claim) }
    }

    /** A rejected/throwing service callback cannot strand this exact reservation. */
    internal fun submitReservedUnknownFrame(
        captureLease: UnknownMaterialPointCaptureLease,
        submit: () -> FastSamRuntimeService.Admission,
    ): FastSamRuntimeService.Admission {
        var transferred = false
        try {
            val admission = submit()
            transferred = admission == FastSamRuntimeService.Admission.ACCEPTED ||
                admission == FastSamRuntimeService.Admission.REPLACED_PENDING
            return admission
        } finally { if (!transferred) captureLease.close() }
    }

    /** Converts only admitted auxiliary frames from the same owned YUV pixels as primary input. */
    private fun submitUnknownObjectFrame(
        context: UnknownProcessingContext,
        input: UnknownCaptureFrameInput,
        evidence: UnknownCaptureEvidence,
        generation: Int,
        epoch: WalkRuntimeEpoch,
        arGeneration: Long,
        capturedElapsedMs: Long,
        geometryVersion: Long,
    ) {
        val service = context.service
        if (unknownRuntimeService !== service) return
        val cameraFrame = input.cameraFrame
        val cameraTimestampNs = input.cameraTimestampNs ?: return
        if (!runtimeRecognitionMatchesDepth(cameraTimestampNs, evidence.frameId, evidence.depthSnapshot)) return
        // Preserve the service's existing full-image geometry admission for LIVE_CAMERA.
        if (cameraFrame.cropRect != kr.co.hanium.dreamup.walksafe.inference.YuvCropRect.full(
                cameraFrame.width, cameraFrame.height,
            )
        ) return
        if (service.stats().stopping) {
            latestUnknownFeedback = null
            unknownRuntimeStatus = "추가 물체 인식 확인 필요"
            return
        }
        if (!isCurrentFrameGeneration(generation, epoch) ||
            !currentUnknownCaptureProcessingAllowsWork(evidence.processingLease) || !isActivityForeground
        ) return
        val token = input.unknownLuma?.takeIf { it.service === service }?.capture?.token ?: return
        if (token.frameId != evidence.frameId || token.cpuImageTimestampNs != cameraTimestampNs ||
            token.geometryId != context.geometryId || context.imageQuarterTurns != evidence.imageQuarterTurns
        ) return
        // A service can discard an expired capture without calling the depth consumer. Do not let
        // its old pacing ticket prevent all later fresh work; never cancel an active native flight.
        context.activeAttempt?.let { old ->
            val state = service.stats()
            if (!state.inflight && !state.pending && !state.copying &&
                clock.elapsedRealtimeMs() - old.ticket.startedAtElapsedRealtimeMs > MAX_DEPTH_DETECTION_SOURCE_AGE_MS
            ) {
                cancelAttempt(context, old)
            }
        }
        // This walking owner never replaces an existing pending/whole consumer flight.
        val serviceState = service.stats()
        if (serviceState.stopping || serviceState.inflight || serviceState.pending || serviceState.copying) return
        val ticket = context.pacing.tryStart(capturedElapsedMs) ?: return
        val attempt = UnknownCaptureAttempt(token, ticket, context.pacing)
        context.activeAttempt = attempt
        var submitted = false
        var unattachedLease: UnknownMaterialPointCaptureLease? = null
        var tactileJoin: UnknownTactileJoin? = null
        try {
            if (!token.isFreshAt(clock.elapsedRealtimeNanos(), MAX_DEPTH_DETECTION_SOURCE_AGE_MS)) {
                handleUnknownFrameDiscard(token, "stale_before_copy")
                return
            }
            val capture = FrozenUnknownDepthCapture.freeze(
                token, evidence.depthSnapshot,
                evidence.depthMapper?.imagePixelsToDepthUvMatrix(cameraFrame.width, cameraFrame.height),
                evidence.depthMapper?.frameId ?: evidence.frameId,
                evidence.motionContext,
                imageQuarterTurns = evidence.imageQuarterTurns,
            ) ?: return
            if (!isUnknownCaptureContextCurrent(context, evidence.processingLease)) return
            val reserved = context.materialPointTracker.reserveCapture(token, evidence.imageQuarterTurns,
                clock.elapsedRealtimeMs()).lease ?: return
            unattachedLease = reserved
            synchronized(frameStateLock) {
                if (!isUnknownCaptureContextCurrent(context, evidence.processingLease) ||
                    context.activeAttempt !== attempt || !attempt.attachLease(reserved)
                ) return
                unattachedLease = null // The attempt now owns release on every terminal path.
                val receipt = input.timestampEvidence
                val config = reportRuntimeConfig
                if (receipt != null && config != null && evidence.modelGeneration == detectorLoadGeneration &&
                    receipt.cameraImageTimestampNs == token.cpuImageTimestampNs
                ) {
                    val deadline = token.capturedElapsedNs + MAX_DEPTH_DETECTION_SOURCE_AGE_MS * 1_000_000L
                    val captureKey = TactileSemanticCapture(receipt, token, evidence.imageQuarterTurns, deadline)
                    context.tactileJoin?.capture?.cancel()
                    tactileJoin = UnknownTactileJoin(context, evidence.processingLease, evidence.modelGeneration,
                        reserved, TactileMaskCaptureJoin(captureKey, capture, config), visualGeometryVersion)
                        .also { context.tactileJoin = it }
                }
            }
            val rgb = input.cameraFrame.toArgb()
            if (!isUnknownCaptureContextCurrent(context, evidence.processingLease)) return
            val admission = submitReservedUnknownFrame(reserved) {
                service.submitArgb(rgb.width, rgb.height, rgb.pixels, token,
                    UnknownFrameAttachment(context, capture, attempt, evidence.processingLease, reserved, tactileJoin))
            }
            submitted = admission == FastSamRuntimeService.Admission.ACCEPTED ||
                admission == FastSamRuntimeService.Admission.REPLACED_PENDING
            if (admission == FastSamRuntimeService.Admission.STALE) {
                handleUnknownFrameDiscard(token, "stale_during_copy")
            }
        } catch (error: RuntimeException) {
            if (isUnknownCaptureContextCurrent(context, evidence.processingLease)) {
                unknownRuntimeStatus = "추가 물체 프레임 확인 실패: ${error.javaClass.simpleName}"
            }
        } finally {
            if (!submitted) {
                tactileJoin?.let(::cancelUnknownTactileJoin)
                // A reservation rejected before attachment still belongs to this submit scope.
                unattachedLease?.close()
                cancelAttempt(context, attempt)
            }
        }
    }

    /**
     * FastSAM CPU-post owns this work, independently of the GL primary-tracker cadence.
     * Its one deadline starts at the actual callback entrance; checks and producer-monitor wait
     * consume that same allowance. Source age always uses the original capture's elapsed time.
     */
    internal fun processUnknownMaterialPointCapture(
        pipeline: UnknownObjectDepthPipeline,
        materialPointTracker: UnknownMaterialPointTracker,
        capture: FrozenUnknownDepthCapture,
        token: FastSamFrameToken,
        masks: List<InstanceMask>,
        nowElapsedRealtimeMs: Long,
        callbackStartedNs: Long,
        maximumExecutionNs: Long,
        clockNanos: () -> Long = clock::cpuNanos,
        captureLease: UnknownMaterialPointCaptureLease? = null,
        contextIsCurrent: () -> Boolean = { true },
        elapsedRealtimeMs: () -> Long = { nowElapsedRealtimeMs },
    ): UnknownDepthFrameResult {
        fun inactive() = UnknownDepthFrameResult(token, emptyList(), emptyList(), "capture_context_invalidated")
        var dispatchedToProducer = false
        try {
            if (!contextIsCurrent()) return inactive()
            // A foreign result cannot advance the material-point tracker's original capture.
            if (!sameCapture(capture.token, token)) {
                return pipeline.process(capture, token, masks, elapsedRealtimeMs())
            }
            val tracked = synchronized(materialPointTracker) {
                if (!contextIsCurrent()) return inactive()
                val maximum = maximumExecutionNs.coerceAtLeast(0L)
                val consumedAtElapsedRealtimeMs = elapsedRealtimeMs()
                val remaining = (maximum - (clockNanos() - callbackStartedNs).coerceAtLeast(0L))
                    .coerceIn(0L, maximum)
                dispatchedToProducer = true
                materialPointTracker.consume(token, capture.imageQuarterTurns, masks,
                    consumedAtElapsedRealtimeMs, remaining, captureLease)
            }
            if (!contextIsCurrent()) return inactive()
            return pipeline.process(capture, token, masks, elapsedRealtimeMs(),
                materialPointsByMaskIndex = tracked.materialPointsByMaskIndex)
        } finally {
            // Once dispatched, the producer closes only its own lease, including on failure.
            // Before dispatch the caller releases its canceled attachment. isClosed is no gate.
            if (!dispatchedToProducer) captureLease?.let(materialPointTracker::closeCaptureLeaseIfOwned)
        }
    }

    /** Runs on FastSAM's CPU post owner, including the complete mask/depth/consumer flight. */
    fun consumeUnknownObjectResult(result: FastSamResult<UnknownRuntimeAttachment>) {
        val callbackStartedNs = clock.cpuNanos()
        val attachment = result.attachment as? UnknownFrameAttachment ?: return
        val context = attachment.context
        if (!attachment.attempt.claimResult()) return
        val started = clock.elapsedRealtimeMs()
        try {
            if (!isUnknownCaptureContextCurrent(context, attachment.processingLease)) return
            attachment.tactileJoin?.let { join ->
                if (join.capture.recordMasks(result.token, result.masks)) scheduleUnknownTactileJoin(join)
            }
            val processed = processUnknownMaterialPointCapture(
                context.pipeline, context.materialPointTracker,
                attachment.capture, result.token, result.masks, started,
                callbackStartedNs, context.materialPointTrackingConfig.maxExecutionNs,
                captureLease = attachment.captureLease,
                contextIsCurrent = { isUnknownCaptureContextCurrent(context, attachment.processingLease) },
                elapsedRealtimeMs = clock::elapsedRealtimeMs,
            )
            val completed = clock.elapsedRealtimeMs()
            if (!isUnknownCaptureContextCurrent(context, attachment.processingLease)) return
            if (!isUnknownContextCurrent(context)) {
                unknownRuntimeStatus = "추가 영역 ${result.masks.size}개 · Depth 일시 공백 · 거리 안내 대기"
                return
            }
            val batch = context.feedback.admit(
                outputs = (processed.objects + processed.proximityObjects).map { output ->
                    output.copy(trackId = "unknown:${context.identity}:${output.trackId}")
                },
                sourceFrameId = result.token.frameId,
                sourceTimestampMs = attachment.capture.frameTimestampMs,
                sourceCapturedAtElapsedRealtimeNs = result.token.capturedElapsedNs,
                completedAtElapsedRealtimeMs = completed,
                sourceEpoch = context.walkEpoch,
                currentEpoch = scopeReader.runtime().epoch,
                nowElapsedRealtimeMs = completed,
            )
            synchronized(frameStateLock) {
                if (!isUnknownContextCurrent(context)) return
                val batches = UnknownObjectFeedbackPolicy.mergeRetainedRegions(
                    previous = latestUnknownFeedback?.takeIf { it.context === context }?.batches.orEmpty(),
                    fresh = batch,
                    retainedTrackIds = processed.retainedProximityRegionIds.mapTo(hashSetOf()) {
                        "unknown:${context.identity}:$it"
                    },
                    currentEpoch = context.walkEpoch,
                    nowMs = completed,
                )
                // Each retained region keeps its original capture and deadline alongside new depth.
                latestUnknownFeedback = batches.takeIf { it.isNotEmpty() }?.let { UnknownFeedbackObservation(context, it) }
                val measured = (processed.objects + processed.proximityObjects).count { it.source.metric && it.riskDistanceM != null }
                unknownRuntimeStatus = "추가 영역 ${result.masks.size}개 · 유효 거리 ${measured}개 · " +
                    "${completed - result.token.capturedElapsedNs / 1_000_000L}ms · ${result.runtime.actualBackend}"
            }
        } finally {
            val completed = attachment.attempt.complete(
                clock.elapsedRealtimeMs(),
                inferenceDurationMs = result.inferenceMs.toLong().coerceAtLeast(0L),
                thermalThrottled = latestInferenceThermalThrottled,
                loadObservation = latestUnknownLoadObservation?.takeIf {
                    clock.elapsedRealtimeMs() - it.observedAtElapsedRealtimeMs in 0L..1_000L
                },
            )
            if (completed && isUnknownCaptureContextCurrent(context, attachment.processingLease)) {
                val now = clock.elapsedRealtimeMs()
                val age = now - result.token.capturedElapsedNs / 1_000_000L
                val normal = latestInferenceThermalThrottled == false &&
                    runCatching { !scopeReader.powerSaveMode() }.getOrDefault(false)
                observer.observeLoad(now, normal, RuntimeLoadCompletion(
                    age, staleOrDropped = age > MAX_DEPTH_DETECTION_SOURCE_AGE_MS,
                ))
            }
            clearAttempt(context, attachment.attempt)
        }
    }

    fun handleUnknownFrameDiscard(token: FastSamFrameToken, reason: String) {
        if (token.source != FastSamFrameToken.Source.LIVE_CAMERA) return
        val now = clock.elapsedRealtimeMs()
        val context = synchronized(frameStateLock) {
            val active = unknownProcessingContext ?: return
            active.tactileJoin?.takeIf { sameCapture(it.capture.semanticCapture.token, token) }
                ?.let(::cancelUnknownTactileJoin)
            val attempt = active.activeAttempt ?: return
            if (active.service.sessionEpoch != token.sessionEpoch || active.geometryId != token.geometryId ||
                !attempt.matches(token)
            ) return
            val finished = if (reason.startsWith("stale")) {
                attempt.complete(now, thermalThrottled = latestInferenceThermalThrottled,
                    loadObservation = latestUnknownLoadObservation?.takeIf {
                        now - it.observedAtElapsedRealtimeMs in 0L..1_000L
                    })
            } else attempt.cancel()
            clearAttempt(active, attempt)
            if (!finished) return

            active
        }
        if (reason.startsWith("stale") && isUnknownContextCurrent(context)) {
            unknownRuntimeStatus = "추가 물체 입력 지연 · 다음 촬영 프레임으로 재시도"
            observer.observeLoad(now,
                normal = latestInferenceThermalThrottled == false &&
                    runCatching { !scopeReader.powerSaveMode() }.getOrDefault(false),
                completion = RuntimeLoadCompletion(
                    now - token.capturedElapsedNs / 1_000_000L, staleOrDropped = true))
        }
    }

    private fun clearAttempt(context: UnknownProcessingContext, attempt: UnknownCaptureAttempt) = synchronized(frameStateLock) {
        if (context.activeAttempt === attempt) context.activeAttempt = null
    }

    private fun cancelAttempt(context: UnknownProcessingContext, attempt: UnknownCaptureAttempt) {
        attempt.cancel()
        clearAttempt(context, attempt)
    }

    private fun isUnknownContextCurrent(context: UnknownProcessingContext): Boolean =
        unknownProcessingContext === context && unknownRuntimeService === context.service && context.frameQueue.isAccepting &&
            isActivityForeground && !detectorClosing &&
            isCurrentFrameGeneration(context.detectorGeneration, context.walkEpoch) &&
            arSessionGeneration == context.arSessionGeneration &&
            currentRuntimeMetricOutputAllowsWork(context.arSessionGeneration)

    private fun cancelUnknownTactileJoin(join: UnknownTactileJoin) = synchronized(frameStateLock) {
        join.capture.cancel()
        if (join.context.tactileJoin === join) join.context.tactileJoin = null
    }

    fun recordCompletedNamedTactileCapture(
        input: UnknownCaptureFrameInput, snapshot: DetectionSnapshot, result: AndroidDetectionResult,
    ) {
        val join = synchronized(frameStateLock) { unknownProcessingContext?.tactileJoin } ?: return
        if (input.timestampEvidence !== join.capture.semanticCapture.imageReceipt ||
            input.unknownLuma?.service !== join.context.service
        ) return
        if (join.capture.recordNamed(snapshot, result)) scheduleUnknownTactileJoin(join)
    }

    fun recordFrozenTactileCaptureOutputs(snapshot: DetectionSnapshot, outputs: List<TrackedObjectDepth>) {
        val join = synchronized(frameStateLock) { unknownProcessingContext?.tactileJoin } ?: return
        if (join.capture.recordOutputs(snapshot, outputs)) scheduleUnknownTactileJoin(join)
    }

    private fun unknownTactileResultOwnerIsCurrent(
        join: UnknownTactileJoin, bundle: TactileMaskCaptureBundle, decisionNs: Long,
    ): Boolean = !join.capture.isCancelled() && join.modelGeneration == detectorLoadGeneration &&
        join.geometryVersion == visualGeometryVersion &&
        isUnknownCaptureContextCurrent(join.context, join.processingLease) && isUnknownContextCurrent(join.context) &&
        decisionNs >= bundle.semanticCapture.token.capturedElapsedNs &&
        decisionNs <= bundle.semanticCapture.originalDeadlineElapsedNs

    private fun isUnknownCaptureContextCurrent(
        context: UnknownProcessingContext,
        capture: UnknownCaptureProcessingLease,
    ): Boolean = unknownProcessingContext === context && unknownRuntimeService === context.service && context.frameQueue.isAccepting &&
        isActivityForeground && !detectorClosing &&
        isCurrentFrameGeneration(context.detectorGeneration, context.walkEpoch) &&
        context.arSessionGeneration == capture.arLease.generation &&
        currentUnknownCaptureProcessingAllowsWork(capture)

    fun freshUnknownFeedbackBatches(nowMs: Long): List<UnknownObjectFeedbackBatch> {
        val observation = latestUnknownFeedback ?: return emptyList()
        if (!isUnknownContextCurrent(observation.context)) return emptyList()
        val epoch = scopeReader.runtime().epoch
        return observation.batches.filter { it.sourceEpoch == epoch && it.isFreshAt(nowMs) }
    }

    fun currentUnknownCaptureProcessingAllowsWork(capture: UnknownCaptureProcessingLease): Boolean =
    scopeReader.withDepthAuthority(capture) { authority ->
        val nowMs = clock.elapsedRealtimeMs()
        capture.frameId > 0L && capture.frameId <= authority.latestFrameTimestampNs &&
            nowMs - capture.capturedAtElapsedRealtimeMs in 0L..MAX_DEPTH_DETECTION_SOURCE_AGE_MS &&
            (capture.gapDeadlineElapsedRealtimeMs == null || nowMs <= capture.gapDeadlineElapsedRealtimeMs) &&
            authority.visualFrameTracking && authority.visualOutputAllowsWork()
    }

    fun scheduleUnknownDetectionIfDue(
        recognitionInput: UnknownCaptureFrameInput?, snapshot: DepthFrameSnapshot,
        frameId: Long, capturedElapsedMs: Long, generation: Int, epoch: WalkRuntimeEpoch,
        processingLease: UnknownCaptureProcessingLease,
        evidenceSupplier: () -> UnknownCaptureEvidence,
    ) {
        val input = recognitionInput ?: return
        val luma = input.unknownLuma ?: return
        if (!runtimeRecognitionMatchesDepth(input.cameraTimestampNs, frameId, snapshot)) return
        if (luma.capture.token.frameId != frameId ||
            luma.capture.token.capturedElapsedNs != capturedElapsedMs * 1_000_000L) return
        if (!currentUnknownCaptureProcessingAllowsWork(processingLease)) return
        val context = getOrCreateUnknownProcessingContext(input, generation, epoch, processingLease.arLease.generation) ?: return
        var claim: UnknownFrameCopyClaim? = null
        var queued = false
        try {
            val selected = synchronized(frameStateLock) {
                if (!isUnknownCaptureContextCurrent(context, processingLease)) return
                selectUnknownAvailableFrameForQueue(unknownFrameCaptureIsDue(capturedElapsedMs)) {
                    if (!unknownFrameCopyInFlight.compareAndSet(false, true)) false
                    else {
                        claim = UnknownFrameCopyClaim(context).also { unknownFrameCopyOwner = it }
                        true
                    }
                } ?: return
            }
            // This supplier runs before enqueue and is never retained by the non-GL queue.
            val submission = if (selected) UnknownFrameSubmission(input, evidenceSupplier(), requireNotNull(claim)) else null
            queued = context.frameQueue.enqueue(UnknownQueuedLumaFrame(luma, processingLease, submission),
                schedule = { detectorExecutor.execute(it) },
                process = { processQueuedUnknownLumaFrame(context, it) },
                discard = { releaseUnknownFrameCopyClaim(it.submission?.claim) })
        } catch (error: RuntimeException) {
            if (isCurrentFrameGeneration(generation, epoch)) {
                unknownRuntimeStatus = "추가 물체 프레임 대기: ${error.javaClass.simpleName}"
            }
        } finally { if (!queued) releaseUnknownFrameCopyClaim(claim) }
    }

    private fun scheduleUnknownTactileJoin(join: UnknownTactileJoin) {
        val bundle = synchronized(frameStateLock) {
            if (join.context.tactileJoin !== join || join.modelGeneration != detectorLoadGeneration ||
                !isUnknownCaptureContextCurrent(join.context, join.processingLease) ||
                !isUnknownContextCurrent(join.context)
            ) { cancelUnknownTactileJoin(join); return }
            join.capture.claim(clock.elapsedRealtimeNanos())
        } ?: return
        try {
            detectorExecutor.execute {
                var finished = false
                try {
                    val surfaces = observer.projectTactile(bundle, clock.elapsedRealtimeNanos())
                    val decisionNs = clock.elapsedRealtimeNanos()
                    val publication = synchronized(frameStateLock) {
                        if (join.context.tactileJoin !== join || !unknownTactileResultOwnerIsCurrent(join, bundle, decisionNs)) null
                        else UnknownTactilePublication(join.context.walkEpoch, join.context.detectorGeneration,
                            join.context.arSessionGeneration, feedbackLifecycleGeneration, join.geometryVersion,
                            join.context.geometryId, join.processingLease)
                    }
                    if (publication != null) {
                        finished = observer.publishTactile(bundle, surfaces, publication, decisionNs,
                            admissionIsCurrent = {
                                join.context.tactileJoin === join &&
                                    publication.feedbackLifecycleGeneration == feedbackLifecycleGeneration &&
                                    unknownTactileResultOwnerIsCurrent(join, bundle, decisionNs)
                            },
                            outputIsCurrent = { unknownTactileResultOwnerIsCurrent(join, bundle, clock.elapsedRealtimeNanos()) },
                        )
                    }
                } finally {
                    synchronized(frameStateLock) {
                        if (!finished) join.capture.cancel()
                        if (join.context.tactileJoin === join) join.context.tactileJoin = null
                    }
                }
            }
        } catch (_: RuntimeException) { cancelUnknownTactileJoin(join) }
    }

    /** Same-call cleanup body; only Android UI dispatch is supplied by the actual caller. */
    internal fun invalidateUnknownObjectObservations(
        scheduleTerminalFeedbackCleanup: (() -> Unit) -> Unit,
    ) = synchronized(frameStateLock) {
        val invalidatedContext = unknownProcessingContext
        invalidatedContext?.tactileJoin?.let(::cancelUnknownTactileJoin)
        unknownProcessingContext = null
        latestUnknownFeedback = null
        var resetSchedulingFailure: RuntimeException? = null
        invalidatedContext?.let { context ->
            context.frameQueue.invalidate { releaseUnknownFrameCopyClaim(it.submission?.claim) }
            context.activeAttempt?.retireCaptureLease()
            unknownFrameCopyOwner?.takeIf { it.context === context }?.let(::releaseUnknownFrameCopyClaim)
            // Queue reset on the existing owner; never wait for the native tracker on UI/GL.
            try { detectorExecutor.execute { context.materialPointTracker.reset() } }
            catch (error: RuntimeException) { resetSchedulingFailure = error }
            val trackPrefix = "unknown:${context.identity}:"
            observer.cancelFeedbackForTrackPrefix(trackPrefix)
            // A producer restart invalidates its accepted output, while a reused depth frame
            // merely retains old evidence until its original deadline.
            scheduleTerminalFeedbackCleanup { observer.retireTerminalFeedbackForTrackPrefix(trackPrefix) }
        }
        // Cancellation is immediate for the withdrawing owner. Queued old resets never touch a
        // reused service or a newer context's pending submission.
        invalidatedContext?.service?.discardPending("runtime_evidence_invalidated")
        resetSchedulingFailure?.let { throw it }
    }

    private companion object { const val MAX_DEPTH_DETECTION_SOURCE_AGE_MS = 800L }
}
