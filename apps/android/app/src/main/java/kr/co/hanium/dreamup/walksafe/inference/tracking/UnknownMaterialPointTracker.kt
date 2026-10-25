package kr.co.hanium.dreamup.walksafe.inference.tracking

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.UNNAMED_OBSTACLE_CLASS
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownMaterialPointObservation
import kr.co.hanium.dreamup.walksafe.depth.unknown.sameCapture
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.FastSamDecoder
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask

data class UnknownMaterialPointTrackingResult(
    val materialPointsByMaskIndex: Map<Int, UnknownMaterialPointObservation>,
    /** Original per-call metrics, including completed native work that exceeded its deadline. */
    val trackingMetrics: List<VisualTrackingMetrics>,
    val durationNs: Long,
    val executionBudgetNs: Long,
    val executionBudgetOverrun: Boolean,
    /** Ring retention only; the separate tracker also has its existing bounded history. */
    val retainedFrames: Int,
    val retainedImageBytes: Int,
    val failure: VisualTrackingFailure? = null,
)

/** Only a reservation identity and release flag; never owns an Image, buffer, or native flight. */
class UnknownMaterialPointCaptureLease internal constructor(
    val token: FastSamFrameToken,
    val imageQuarterTurns: Int,
    private val owner: Any,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    /** This flag does not imply freshness; reserve/consume also check the original capture TTL. */
    val isClosed: Boolean get() = closed.get()
    /** Safe on UI/callback threads: closing never waits for the producer's native-work monitor. */
    override fun close() { closed.set(true) }
    internal fun isOwnedBy(owner: Any): Boolean = this.owner === owner
}

data class UnknownMaterialPointCaptureReservation(
    val lease: UnknownMaterialPointCaptureLease?,
    val failure: VisualTrackingFailure? = null,
)

/**
 * Supplies actual LK points at delayed FastSAM captures. Future GL frames stay in an immutable
 * ring: they are never offered to the tracker before the mask's original capture is consumed.
 * The first mask has no point. A subsequent mask may inherit a measured point only after actual
 * flow reaches that exact image and the existing conservative same-class handoff succeeds.
 */
class UnknownMaterialPointTracker(
    private val config: VisualTrackingConfig = VisualTrackingConfig(),
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private var maskEstimator = MaskConstrainedEstimator(when (config.backend) {
        VisualTrackingBackend.OPENCV_PYRAMIDAL_LK -> OpenCvPyramidalMotionEstimator(config.maxFeaturesPerObject, config.allowSimilarityTransform)
        VisualTrackingBackend.PATCH_DIAGNOSTIC -> PatchMotionEstimator(minOf(config.maxFeaturesPerObject, 16))
    })
    private var tracker = InterFrameDetectionTracker(config, clockNanos, maskEstimator)
    internal constructor(config: VisualTrackingConfig, clockNanos: () -> Long, estimator: VisualMotionEstimator) :
        this(config, clockNanos) {
            maskEstimator = MaskConstrainedEstimator(estimator)
            tracker = InterFrameDetectionTracker(config, clockNanos, maskEstimator)
        }

    private data class Capture(val token: FastSamFrameToken, val turns: Int, val frame: GrayTrackingFrame,
        var lease: UnknownMaterialPointCaptureLease? = null)
    private data class Batch(val capture: Capture, val detections: List<DetectionCandidate>)
    private val ring = ArrayDeque<Capture>()
    private val leaseOwner = Any()
    private var latestOffered: FastSamFrameToken? = null
    private var latestTurns = 0
    private var geometryVersion = 0L
    private var lastConsumed: FastSamFrameToken? = null
    private var fedKey: VisualFrameKey? = null
    private var batch: Batch? = null

    /**
     * Call while owning the SAME acquired CPU Image used to construct token. The observed
     * timestamp/dimensions must come from that Image; equality alone is not proof of provenance.
     * This copies unrotated luma and retains neither Image nor buffer. It cannot retag old gray.
     */
    @Synchronized
    fun offerLuma(
        token: FastSamFrameToken,
        imageQuarterTurns: Int,
        nowElapsedRealtimeMs: Long,
        observedCpuImageTimestampNs: Long,
        imageWidth: Int,
        imageHeight: Int,
        luma: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
    ): TrackingFrameAdmission = offerCapture(token, imageQuarterTurns, nowElapsedRealtimeMs,
        observedCpuImageTimestampNs, imageWidth, imageHeight) { key ->
        GrayTrackingFrame.copyFromLuma(key, imageWidth, imageHeight, luma, rowStride, pixelStride)
    }

    /**
     * Admit the original small copy on a non-GL owner. This may wait for native consume's existing
     * monitor; copyFromLuma does not. The packet alone supplies its token/receipt, preventing retag.
     */
    @Synchronized
    fun offerOwnedLuma(capture: UnknownMaterialPointLumaCapture,
        nowElapsedRealtimeMs: Long): TrackingFrameAdmission = offerCapture(capture.token,
        capture.imageQuarterTurns, nowElapsedRealtimeMs, capture.observedCpuImageTimestampNs,
        capture.imageWidth, capture.imageHeight) { key -> capture.toFrame(key.geometryVersion) }

    /** Both entry points use the original validation, duplicate, pressure and retention order. */
    private fun offerCapture(
        token: FastSamFrameToken,
        imageQuarterTurns: Int,
        nowElapsedRealtimeMs: Long,
        observedCpuImageTimestampNs: Long,
        imageWidth: Int,
        imageHeight: Int,
        frameFactory: (VisualFrameKey) -> GrayTrackingFrame,
    ): TrackingFrameAdmission {
        fun reject(failure: VisualTrackingFailure, clear: Boolean = false): TrackingFrameAdmission {
            if (clear) reset()
            return TrackingFrameAdmission(false, clear, failure)
        }
        if (token.source != FastSamFrameToken.Source.LIVE_CAMERA || imageQuarterTurns !in 0..3 ||
            observedCpuImageTimestampNs != token.cpuImageTimestampNs ||
            imageWidth != token.width || imageHeight != token.height
        ) return reject(VisualTrackingFailure.FRAME_IDENTITY_CONFLICT)
        if (nowElapsedRealtimeMs < token.capturedElapsedNs / 1_000_000L ||
            nowElapsedRealtimeMs - token.capturedElapsedNs / 1_000_000L > config.maxDetectionAgeMs
        ) return reject(VisualTrackingFailure.SOURCE_EXPIRED)
        val latest = latestOffered
        if (latest != null && token.sessionEpoch < latest.sessionEpoch) {
            return reject(VisualTrackingFailure.EPOCH_OR_GEOMETRY_CHANGED)
        }
        val duplicate = ring.firstOrNull { it.token.sessionEpoch == token.sessionEpoch && it.token.frameId == token.frameId }
        if (duplicate != null && (!sameCapture(duplicate.token, token) || duplicate.turns != imageQuarterTurns)) {
            return reject(VisualTrackingFailure.FRAME_IDENTITY_CONFLICT, clear = true)
        }
        if (duplicate == null && latest != null && token.sessionEpoch == latest.sessionEpoch &&
            (token.frameId <= latest.frameId || token.cameraTimestampNs <= latest.cameraTimestampNs ||
                token.cpuImageTimestampNs <= latest.cpuImageTimestampNs || token.capturedElapsedNs < latest.capturedElapsedNs)
        ) return reject(VisualTrackingFailure.FRAME_ORDER_INVALID)
        val scopeChanged = latest != null && (token.sessionEpoch != latest.sessionEpoch ||
            token.geometryId != latest.geometryId || token.width != latest.width || token.height != latest.height ||
            imageQuarterTurns != latestTurns)
        val version = duplicate?.frame?.key?.geometryVersion ?: if (latest == null || scopeChanged) geometryVersion + 1L else geometryVersion
        val key = VisualFrameKey(token.sessionEpoch, token.frameId, token.cpuImageTimestampNs,
            token.capturedElapsedNs / 1_000_000L, version)
        // The count bound stays 32. Only live pending sources are excluded from the existing
        // oldest-first eviction order; preserving a source does not promise LK path continuity.
        val retentionClock = if (duplicate != null) latest ?: token else token
        val expired = if (scopeChanged) emptyList() else ring.filter { !withinHistoryAge(it.token, retentionClock) }
        if (!scopeChanged) expireLeases(retentionClock, nowElapsedRealtimeMs)
        val victim = if (duplicate == null && !scopeChanged && ring.size - expired.size >= config.maxHistoryFrames) {
            ring.firstOrNull { it !in expired && it.lease?.isClosed != false }
                ?: return reject(VisualTrackingFailure.INPUT_BUDGET_EXCEEDED)
        } else null
        val frame = try {
            frameFactory(key)
        } catch (_: IllegalArgumentException) {
            return reject(VisualTrackingFailure.INVALID_GEOMETRY)
        }
        if (duplicate != null) return if (duplicate.frame.samePixels(frame)) TrackingFrameAdmission(true, false)
            else reject(VisualTrackingFailure.FRAME_IDENTITY_CONFLICT, clear = true)
        if (scopeChanged) reset()
        geometryVersion = version
        latestOffered = token
        latestTurns = imageQuarterTurns
        for (capture in expired) removeCapture(capture)
        victim?.let(::removeCapture)
        ring.addLast(Capture(token, imageQuarterTurns, frame))
        if (batch?.let { old -> ring.none { it.frame.key == old.capture.frame.key } } == true) clearTracking()
        return TrackingFrameAdmission(true, scopeChanged)
    }

    /**
     * Reserve the already copied, exact source BEFORE submitting FastSAM. Failure means it must
     * not be submitted as a protected source. Duplicate owners are refused, not reference-counted.
     * Methods serialize on the existing producer monitor; callers must not wait for it on UI/GL.
     */
    @Synchronized
    fun reserveCapture(token: FastSamFrameToken, imageQuarterTurns: Int,
        nowElapsedRealtimeMs: Long): UnknownMaterialPointCaptureReservation {
        fun reject(failure: VisualTrackingFailure) = UnknownMaterialPointCaptureReservation(null, failure)
        val latest = latestOffered
        if (latest == null || token.sessionEpoch != latest.sessionEpoch || token.geometryId != latest.geometryId ||
            token.width != latest.width || token.height != latest.height || imageQuarterTurns != latestTurns
        ) return reject(VisualTrackingFailure.EPOCH_OR_GEOMETRY_CHANGED)
        expireLeases(latest, nowElapsedRealtimeMs)
        val consumed = lastConsumed
        if (consumed != null && token.frameId <= consumed.frameId) return reject(VisualTrackingFailure.DETECTOR_SOURCE_OUT_OF_ORDER)
        val capture = ring.firstOrNull { sameCapture(it.token, token) && it.turns == imageQuarterTurns }
            ?: return reject(VisualTrackingFailure.SOURCE_NOT_IN_HISTORY)
        if (!withinDetectionAge(token, latest, nowElapsedRealtimeMs)) return reject(VisualTrackingFailure.SOURCE_EXPIRED)
        if (capture.lease?.isClosed == false) return reject(VisualTrackingFailure.DETECTION_IDENTITY_CONFLICT)
        val lease = UnknownMaterialPointCaptureLease(token, imageQuarterTurns, leaseOwner)
        capture.lease = lease
        return UnknownMaterialPointCaptureReservation(lease)
    }

    /** Nonblocking logical close only: foreign owners are untouched; true is not a native ACK. */
    fun closeCaptureLeaseIfOwned(lease: UnknownMaterialPointCaptureLease): Boolean {
        if (!lease.isOwnedBy(leaseOwner)) return false
        lease.close()
        return true
    }

    /** Clears owned state, preserving the admission watermark that rejects late old captures. */
    @Synchronized
    fun reset() {
        ring.forEach { it.lease?.close() }
        ring.clear()
        lastConsumed = null
        clearTracking()
    }

    /** A late cancellation of an already consumed capture cannot release a newer batch. */
    @Synchronized
    fun cancelCapture(token: FastSamFrameToken): Boolean {
        val consumed = lastConsumed
        if (consumed != null && token.sessionEpoch == consumed.sessionEpoch && token.frameId < consumed.frameId) return false
        val capture = ring.firstOrNull { sameCapture(it.token, token) } ?: return false
        removeCapture(capture)
        clearTracking()
        return true
    }

    /**
     * executionBudgetNs is the caller's REMAINING allowance; no second default 5 ms is granted.
     * Reserved sources require their exact lease. Success, refusal, and exceptions close that
     * provided lease. Submission failure/discard/cancel must close it even without consuming.
     */
    @Synchronized
    fun consume(
        token: FastSamFrameToken,
        imageQuarterTurns: Int,
        masks: List<InstanceMask>,
        nowElapsedRealtimeMs: Long,
        executionBudgetNs: Long,
        captureLease: UnknownMaterialPointCaptureLease? = null,
    ): UnknownMaterialPointTrackingResult = try {
        consumeLocked(token, imageQuarterTurns, masks, nowElapsedRealtimeMs, executionBudgetNs, captureLease)
    } finally {
        captureLease?.takeIf { it.isOwnedBy(leaseOwner) }?.close()
    }

    private fun consumeLocked(
        token: FastSamFrameToken,
        imageQuarterTurns: Int,
        masks: List<InstanceMask>,
        nowElapsedRealtimeMs: Long,
        executionBudgetNs: Long,
        captureLease: UnknownMaterialPointCaptureLease?,
    ): UnknownMaterialPointTrackingResult {
        require(executionBudgetNs in 0L..config.maxExecutionNs)
        val started = clockNanos()
        val metrics = mutableListOf<VisualTrackingMetrics>()
        fun remaining() = (executionBudgetNs - (clockNanos() - started).coerceAtLeast(0L)).coerceAtLeast(0L)
        fun result(points: Map<Int, UnknownMaterialPointObservation> = emptyMap(), failure: VisualTrackingFailure? = null): UnknownMaterialPointTrackingResult {
            val duration = (clockNanos() - started).coerceAtLeast(0L)
            return UnknownMaterialPointTrackingResult(points.toMap(), metrics.toList(), duration, executionBudgetNs,
                duration > executionBudgetNs || metrics.any { it.executionBudgetOverrun }, ring.size,
                ring.sumOf { it.frame.retainedBytes }, failure)
        }
        val latest = latestOffered
        if (latest == null || token.sessionEpoch != latest.sessionEpoch || token.geometryId != latest.geometryId ||
            token.width != latest.width || token.height != latest.height || imageQuarterTurns != latestTurns
        ) return result(failure = VisualTrackingFailure.EPOCH_OR_GEOMETRY_CHANGED)
        expireLeases(latest, nowElapsedRealtimeMs)
        val previousToken = lastConsumed
        if (previousToken != null && (token.frameId <= previousToken.frameId ||
                token.cameraTimestampNs <= previousToken.cameraTimestampNs || token.cpuImageTimestampNs <= previousToken.cpuImageTimestampNs ||
                token.capturedElapsedNs < previousToken.capturedElapsedNs)) {
            return result(failure = VisualTrackingFailure.DETECTOR_SOURCE_OUT_OF_ORDER)
        }
        val capture = ring.firstOrNull { sameCapture(it.token, token) && it.turns == imageQuarterTurns }
            ?: return result(failure = VisualTrackingFailure.SOURCE_NOT_IN_HISTORY)
        if (nowElapsedRealtimeMs < token.capturedElapsedNs / 1_000_000L ||
            nowElapsedRealtimeMs - token.capturedElapsedNs / 1_000_000L > config.maxDetectionAgeMs ||
            captureLease != null && !withinDetectionAge(token, latest, nowElapsedRealtimeMs)
        ) {
            clearTracking()
            return result(failure = VisualTrackingFailure.SOURCE_EXPIRED)
        }
        if (captureLease != null && (capture.lease !== captureLease || captureLease.isClosed ||
                !sameCapture(captureLease.token, token) || captureLease.imageQuarterTurns != imageQuarterTurns) ||
            captureLease == null && capture.lease?.isClosed == false
        ) return result(failure = VisualTrackingFailure.DETECTION_IDENTITY_CONFLICT)
        if (masks.size > minOf(config.maxInputDetections, FastSamDecoder.MAX_DETECTIONS)) {
            clearTracking()
            return result(failure = VisualTrackingFailure.INPUT_BUDGET_EXCEEDED)
        }
        if (masks.any { !validMask(it, token) }) {
            clearTracking()
            return result(failure = VisualTrackingFailure.INVALID_GEOMETRY)
        }
        lastConsumed = token
        if (remaining() == 0L) {
            clearTracking()
            return result(failure = VisualTrackingFailure.TIME_BUDGET_EXCEEDED)
        }
        // Immutable copies are already bounded. Feed only through this exact historical capture.
        for (item in ring) {
            if (item.frame.key.cameraTimestampNs > capture.frame.key.cameraTimestampNs) break
            if (fedKey?.let { item.frame.key.cameraTimestampNs <= it.cameraTimestampNs } == true) continue
            val admission = tracker.offerFrame(item.frame)
            if (!admission.accepted) {
                clearTracking()
                return result(failure = admission.failure)
            }
            fedKey = item.frame.key
        }
        val detections = masks.map { detection(it, token) }
        val oldBatch = batch
        var flowFailure: VisualTrackingFailure? = null
        if (oldBatch != null) {
            val allowance = remaining()
            if (allowance == 0L) {
                clearTracking()
                return result(failure = VisualTrackingFailure.TIME_BUDGET_EXCEEDED)
            }
            val flowed = tracker.trackFrom(oldBatch.capture.frame.key, oldBatch.detections, capture.frame.key,
                observedAtElapsedRealtimeMs = nowElapsedRealtimeMs, executionBudgetNs = allowance,
                uprightQuarterTurns = imageQuarterTurns)
            metrics += flowed.metrics
            flowFailure = flowed.batchFailure ?: flowed.observations.firstOrNull { it.failure != null }?.failure
        }
        val allowance = remaining()
        if (allowance == 0L) {
            clearTracking()
            return result(failure = VisualTrackingFailure.TIME_BUDGET_EXCEEDED)
        }
        val handed = tracker.trackFrom(capture.frame.key, detections, capture.frame.key,
            observedAtElapsedRealtimeMs = nowElapsedRealtimeMs, executionBudgetNs = allowance,
            uprightQuarterTurns = imageQuarterTurns)
        metrics += handed.metrics
        batch = Batch(capture, detections)
        maskEstimator.setSource(capture.frame.key, masks.toList(), detections)
        val points = mutableMapOf<Int, UnknownMaterialPointObservation>()
        for (observation in handed.observations) {
            val point = observation.materialPoint ?: continue
            val trackedNorm = point.imagePointNorm
            val index = observation.sourceIndex
            if (point.targetKey != capture.frame.key || !trackedNorm.x.isFinite() || !trackedNorm.y.isFinite() ||
                trackedNorm.x < 0f || trackedNorm.x >= 1f || trackedNorm.y < 0f || trackedNorm.y >= 1f || index !in masks.indices) {
                clearTracking()
                return result(failure = VisualTrackingFailure.FRAME_IDENTITY_CONFLICT)
            }
            // LK coordinates name resampled gray-cell centers, not original-image cell edges.
            val pixel = imagePointPixels(capture.frame, trackedNorm.x * capture.frame.width, trackedNorm.y * capture.frame.height)
            val norm = Point2(pixel.x / token.width, pixel.y / token.height)
            val x = (norm.x * token.width).toInt()
            val y = (norm.y * token.height).toInt()
            if (!masks[index].contains(x, y) || !masks[index].contains(pixel.x.toInt(), pixel.y.toInt()) ||
                masks.indices.any { it != index && (masks[it].contains(x, y) || masks[it].contains(pixel.x.toInt(), pixel.y.toInt())) }) {
                // The crop/bbox is only flow geometry. A hole or overlapping real mask retires ID.
                clearTracking()
                return result(failure = VisualTrackingFailure.OBJECT_COMPETITION)
            }
            points[index] = UnknownMaterialPointObservation(token, imageQuarterTurns, point.copy(imagePointNorm = norm))
        }
        if (nowElapsedRealtimeMs - token.capturedElapsedNs / 1_000_000L +
            (clockNanos() - started).coerceAtLeast(0L) / 1_000_000L > config.maxDetectionAgeMs) {
            clearTracking()
            return result(failure = VisualTrackingFailure.SOURCE_EXPIRED)
        }
        if (captureLease?.isClosed == true) {
            clearTracking()
            return result(failure = VisualTrackingFailure.DETECTION_IDENTITY_CONFLICT)
        }
        return result(points, handed.batchFailure ?: flowFailure)
    }

    private fun withinHistoryAge(source: FastSamFrameToken, current: FastSamFrameToken): Boolean =
        current.capturedElapsedNs / 1_000_000L - source.capturedElapsedNs / 1_000_000L in 0L..config.maxHistoryAgeMs &&
            (current.cpuImageTimestampNs - source.cpuImageTimestampNs) / 1_000_000L in 0L..config.maxHistoryAgeMs

    private fun withinDetectionAge(source: FastSamFrameToken, current: FastSamFrameToken, now: Long): Boolean =
        now - source.capturedElapsedNs / 1_000_000L in 0L..config.maxDetectionAgeMs &&
            current.capturedElapsedNs / 1_000_000L - source.capturedElapsedNs / 1_000_000L in 0L..config.maxDetectionAgeMs &&
            (current.cpuImageTimestampNs - source.cpuImageTimestampNs) / 1_000_000L in 0L..config.maxDetectionAgeMs &&
            withinHistoryAge(source, current)

    private fun expireLeases(current: FastSamFrameToken, now: Long) {
        for (capture in ring) {
            val lease = capture.lease ?: continue
            if (lease.isClosed || !withinDetectionAge(capture.token, current, maxOf(now, capture.token.capturedElapsedNs / 1_000_000L))) {
                lease.close()
                capture.lease = null
            }
        }
    }

    private fun removeCapture(capture: Capture) {
        capture.lease?.close()
        ring.remove(capture)
    }

    private fun clearTracking() {
        tracker.clear()
        fedKey = null
        batch = null
        maskEstimator.clear()
    }

    private fun validMask(mask: InstanceMask, token: FastSamFrameToken): Boolean =
        mask.imageWidth() == token.width && mask.imageHeight() == token.height && mask.area() > 0 &&
            mask.maskLeft() >= 0 && mask.maskTop() >= 0 && mask.maskRightExclusive() <= token.width &&
            mask.maskBottomExclusive() <= token.height && mask.maskLeft() < mask.maskRightExclusive() &&
            mask.maskTop() < mask.maskBottomExclusive() && mask.score().isFinite() && mask.score() in 0f..1f

    private fun detection(mask: InstanceMask, token: FastSamFrameToken) = DetectionCandidate(
        UNNAMED_OBSTACLE_CLASS, mask.score(), RectNorm(mask.maskLeft().toFloat() / token.width,
            mask.maskTop().toFloat() / token.height, (mask.maskRightExclusive() - mask.maskLeft()).toFloat() / token.width,
            (mask.maskBottomExclusive() - mask.maskTop()).toFloat() / token.height),
        maskAreaNorm = mask.area().toFloat() / (token.width.toLong() * token.height),
    )

    /** Restricts existing measured seeds; it never constructs a point from mask geometry. */
    private class MaskConstrainedEstimator(private val delegate: VisualMotionEstimator) : VisualMotionEstimator {
        override val supportsMaterialPointTracking: Boolean get() = delegate.supportsMaterialPointTracking
        private var sourceKey: VisualFrameKey? = null
        private var masks: List<InstanceMask> = emptyList()
        private var detections: List<DetectionCandidate> = emptyList()
        fun clear() { sourceKey = null; masks = emptyList(); detections = emptyList() }
        fun setSource(key: VisualFrameKey, masks: List<InstanceMask>, detections: List<DetectionCandidate>) {
            sourceKey = key; this.masks = masks; this.detections = detections
        }
        override fun initialize(): Boolean = delegate.initialize()
        override fun seed(frame: GrayTrackingFrame, detection: DetectionCandidate, budget: TrackingWorkBudget): List<VisualMotionFeature> {
            if (frame.key != sourceKey) return emptyList()
            val index = detections.indices.filter { detections[it] == detection }.singleOrNull() ?: return emptyList()
            val mask = masks[index]
            return delegate.seed(frame, detection, budget).filter { feature ->
                val grayX = feature.currentX
                val grayY = feature.currentY
                if (!grayX.isFinite() || !grayY.isFinite() || grayX < 0f || grayY < 0f || grayX >= frame.width || grayY >= frame.height) {
                    return@filter false
                }
                fun owns(x: Int, y: Int) = mask.contains(x, y) && masks.indices.none { it != index && masks[it].contains(x, y) }
                val center = imagePointPixels(frame, grayX, grayY)
                if (!owns(center.x.toInt(), center.y.toInt())) return@filter false
                // These are exactly copyFromLuma's four source samples, not the larger LK window.
                for (sampleY in 0..1) for (sampleX in 0..1) {
                    val x = sampleImageCoordinate(grayX.toInt(), frame.width, frame.sourceWidth, sampleX)
                    val y = sampleImageCoordinate(grayY.toInt(), frame.height, frame.sourceHeight, sampleY)
                    if (!owns(x, y)) return@filter false
                }
                true
            }
        }
        override fun advance(source: GrayTrackingFrame, previous: GrayTrackingFrame, target: GrayTrackingFrame,
            detection: DetectionCandidate, features: List<VisualMotionFeature>, originalFeatureCount: Int,
            budget: TrackingWorkBudget, materialPoint: VisualMotionPoint?): VisualMotionEstimate {
            val measured = delegate.advance(source, previous, target, detection, features, originalFeatureCount, budget, materialPoint)
            val owned = measured.materialPoint?.takeIf { point ->
                // Peer transforms may still be pending on this edge. Repeated unknown classes
                // cannot prove physical association here; restart their points conservatively.
                masks.size == 1 && imagePointPixels(target, point.x, point.y).let { pixel ->
                    containsTransformed(detection, measured.transformNorm,
                        Point2(pixel.x / target.sourceWidth, pixel.y / target.sourceHeight))
                }
            }
            // Discard at EACH completed image edge. Returning inside later cannot revive this ID.
            return measured.copy(materialPoint = owned)
        }
    }

    private companion object {
        /** Keep 1:1 coordinates; interpolate the actual .25/.75 resampling centers when scaled. */
        fun imagePointPixels(frame: GrayTrackingFrame, x: Float, y: Float) = Point2(
            imageCoordinate(x, frame.width, frame.sourceWidth), imageCoordinate(y, frame.height, frame.sourceHeight))
        fun imageCoordinate(value: Float, graySize: Int, sourceSize: Int): Float {
            if (graySize == sourceSize) return value
            val cell = value.toInt()
            fun center(index: Int) = (sampleImageCoordinate(index, graySize, sourceSize, 0) +
                sampleImageCoordinate(index, graySize, sourceSize, 1)) / 2f
            return center(cell) + (value - cell) * (center(cell + 1) - center(cell))
        }
        fun sampleImageCoordinate(index: Int, graySize: Int, sourceSize: Int, sample: Int): Int =
            ((index + .25f + sample * .5f) * sourceSize / graySize).toInt().coerceAtMost(sourceSize - 1)
        /** Trackers share the cumulative transform; crop right/bottom are exclusive for points. */
        fun containsTransformed(detection: DetectionCandidate, transform: VisualAffineTransform, point: Point2): Boolean {
            if (!point.x.isFinite() || !point.y.isFinite()) return false
            val box = detection.bboxNorm
            val corners = listOf(Point2(box.x, box.y), Point2(box.x + box.width, box.y),
                Point2(box.x + box.width, box.y + box.height), Point2(box.x, box.y + box.height)).map(transform::map)
            if (corners.any { !it.x.isFinite() || !it.y.isFinite() } ||
                point.x < corners.minOf { it.x } || point.x >= corners.maxOf { it.x } ||
                point.y < corners.minOf { it.y } || point.y >= corners.maxOf { it.y }) return false
            if (detection.polygonNorm.isEmpty()) return true
            val polygon = detection.polygonNorm.map(transform::map)
            var inside = false
            var before = polygon.last()
            for (after in polygon) {
                if ((after.y > point.y) != (before.y > point.y) &&
                    point.x < (before.x - after.x) * (point.y - after.y) / (before.y - after.y) + after.x) inside = !inside
                before = after
            }
            return inside
        }
    }
}
