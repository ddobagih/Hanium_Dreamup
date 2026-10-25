package kr.co.hanium.dreamup.walksafe.inference

import java.util.Collections
import kr.co.hanium.dreamup.walksafe.depth.BinaryImageMask
import kr.co.hanium.dreamup.walksafe.depth.CameraImageTimestampEvidence
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask

/**
 * Original acquisition identity. [FastSamFrameToken.capturedElapsedNs] and
 * [originalDeadlineElapsedNs] use absolute SystemClock.elapsedRealtimeNanos, never nanoTime.
 * Main forwards the original job age cutoff and retains ownership/cancellation/bounded-join checks.
 */
data class TactileSemanticCapture(
    val imageReceipt: CameraImageTimestampEvidence,
    val token: FastSamFrameToken,
    val imageQuarterTurns: Int,
    val originalDeadlineElapsedNs: Long,
)

/** Both actual producers return original unrotated sensor coordinates, after their own inverse transforms. */
enum class TactileSemanticCoordinates { SENSOR_ORIGINAL, UNCONFIRMED }

enum class TactileNamedExecutionState { COMPLETED, COMPLETED_EMPTY, PARTIAL, PENDING, SKIPPED, UNAVAILABLE, FAILED }

/** Snapshot final named output without pretending a skipped/failed inference completed empty. */
class TactileNamedSemanticEvidence private constructor(
    val capture: TactileSemanticCapture,
    val state: TactileNamedExecutionState,
    val activeModelKey: String?,
    val detections: List<DetectionCandidate>,
    val coordinates: TactileSemanticCoordinates,
) {
    companion object {
        fun fromResult(capture: TactileSemanticCapture, result: AndroidDetectionResult,
                       coordinates: TactileSemanticCoordinates = TactileSemanticCoordinates.UNCONFIRMED): TactileNamedSemanticEvidence {
            val state = when {
                result.status == AndroidDetectionStatus.FAILED -> TactileNamedExecutionState.FAILED
                result.status == AndroidDetectionStatus.UNAVAILABLE -> TactileNamedExecutionState.UNAVAILABLE
                result.partial -> TactileNamedExecutionState.PARTIAL
                result.detections.isEmpty() -> TactileNamedExecutionState.COMPLETED_EMPTY
                else -> TactileNamedExecutionState.COMPLETED
            }
            val detections = frozenList(result.detections.map { it.copy(polygonNorm = frozenList(it.polygonNorm)) })
            return TactileNamedSemanticEvidence(capture, state, result.timing.modelKey, detections, coordinates)
        }

        fun pending(capture: TactileSemanticCapture) = absent(capture, TactileNamedExecutionState.PENDING)
        fun skipped(capture: TactileSemanticCapture) = absent(capture, TactileNamedExecutionState.SKIPPED)

        private fun absent(capture: TactileSemanticCapture, state: TactileNamedExecutionState) =
            TactileNamedSemanticEvidence(capture, state, null, emptyList(), TactileSemanticCoordinates.UNCONFIRMED)
    }
}

/** Supply the actual completed SAM result token, never a replacement token made at completion. */
class TactileInstanceMaskEvidence(
    val capture: TactileSemanticCapture,
    val resultToken: FastSamFrameToken,
    masks: List<InstanceMask>,
    val coordinates: TactileSemanticCoordinates = TactileSemanticCoordinates.UNCONFIRMED,
) {
    val masks: List<InstanceMask> = frozenList(masks)
}

enum class TactileSemanticKind(val className: String) {
    LINE("linear_tactile_paving"), DOT("dot_tactile_paving"),
    DAMAGED_LINE("damaged_linear_tactile_paving"), DAMAGED_DOT("damaged_dot_tactile_paving"),
}

/** A geometric candidate only. Scores and mask identity remain separate, with no new confidence. */
class TactileSemanticMaskCandidate internal constructor(
    val capture: TactileSemanticCapture,
    val semantic: TactileSemanticKind,
    val namedDetectionIndex: Int,
    val detection: DetectionCandidate,
    val activeModelKey: String,
    val modelArtifactSha256: String?,
    val modelClassIndex: Int,
    val configuredThreshold: Float,
    val maskIndex: Int,
    val instanceMask: InstanceMask,
    val sensorMask: BinaryImageMask,
)

enum class TactileSemanticMaskStatus { ASSOCIATE_CANDIDATE, AMBIGUOUS, UNAVAILABLE }

enum class TactileSemanticMaskReason {
    UNIQUE_CONTAINED_MASK, FOREIGN_IMAGE_RECEIPT, FOREIGN_CAPTURE, INVALID_CAPTURE,
    EXPIRED_CAPTURE, NAMED_PENDING, NAMED_SKIPPED, NAMED_UNAVAILABLE, NAMED_FAILED,
    NAMED_PARTIAL, NAMED_COMPLETED_EMPTY, COORDINATES_UNCONFIRMED, ACTIVE_MODEL_UNCONFIRMED,
    NO_REFINED_TACTILE_SEMANTIC, CLASS_NOT_IN_ACTIVE_MODEL, CLASS_NOT_ALLOWED,
    THRESHOLD_UNCONFIRMED, BELOW_CONFIGURED_THRESHOLD, INVALID_NAMED_SCORE, INVALID_NAMED_GEOMETRY,
    MASKS_EMPTY, EMPTY_MASK, INVALID_MASK, FOREIGN_MASK_DIMENSIONS,
    NO_CONTAINED_MASK, MULTIPLE_CONTAINED_MASKS, OVERLAPPING_TACTILE_DETECTIONS, SHARED_MASK,
}

/** Counts use the actual true pixel cells, never the mask's reported box or a filled rectangle. */
data class TactileMaskContainmentFact(
    val maskIndex: Int,
    val instanceMask: InstanceMask,
    val sensorMask: BinaryImageMask?,
    val containedPixelCount: Int?,
    val overlappingPixelCount: Int?,
    val sharedDetectionIndices: List<Int>,
    val reason: TactileSemanticMaskReason?,
)

data class TactileSemanticMaskDecision(
    val namedDetectionIndex: Int,
    val detection: DetectionCandidate,
    val semantic: TactileSemanticKind?,
    val configuredThreshold: Float?,
    val thresholdPassed: Boolean,
    val status: TactileSemanticMaskStatus,
    val reason: TactileSemanticMaskReason,
    val overlappingDetectionIndices: List<Int>,
    val maskFacts: List<TactileMaskContainmentFact>,
    val candidate: TactileSemanticMaskCandidate?,
)

/** Original packets remain available on rejection, including raw scores, masks and named state. */
class TactileSemanticMaskSelection internal constructor(
    val namedEvidence: TactileNamedSemanticEvidence,
    val maskEvidence: TactileInstanceMaskEvidence,
    decisions: List<TactileSemanticMaskDecision>,
    val reason: TactileSemanticMaskReason?,
) {
    val decisions: List<TactileSemanticMaskDecision> = frozenList(decisions)
    val candidates: List<TactileSemanticMaskCandidate> = frozenList(decisions.mapNotNull { it.candidate })
}

/**
 * Associates actual mask pixels with an already completed named result. This is geometric evidence,
 * not a calibrated semantic confidence or a walking/foot authority. Main owns the live join/lease.
 */
class TactileSemanticMaskSelector(runtimeConfig: TwoModelRuntimeConfig) {
    private val primaryModelKey = runtimeConfig.primaryModelKey
    private val fallbackModelKey = runtimeConfig.fallbackModelKey
    private val unifiedModel = runtimeConfig.unifiedWalksafe?.let {
        it.copy(classes = frozenList(it.classes),
            allowlist = Collections.unmodifiableSet(HashSet(it.allowlist)),
            thresholds = Collections.unmodifiableMap(HashMap(it.thresholds)))
    }

    /** [decisionElapsedNs] is the current elapsedRealtimeNanos value from the original job's clock. */
    fun select(
        named: TactileNamedSemanticEvidence,
        masks: TactileInstanceMaskEvidence,
        decisionElapsedNs: Long,
    ): TactileSemanticMaskSelection {
        val captureFailure = captureFailure(named.capture, masks, decisionElapsedNs)
        if (captureFailure != null) return rejected(named, masks, captureFailure)
        val stateFailure = when (named.state) {
            TactileNamedExecutionState.PENDING -> TactileSemanticMaskReason.NAMED_PENDING
            TactileNamedExecutionState.SKIPPED -> TactileSemanticMaskReason.NAMED_SKIPPED
            TactileNamedExecutionState.UNAVAILABLE -> TactileSemanticMaskReason.NAMED_UNAVAILABLE
            TactileNamedExecutionState.FAILED -> TactileSemanticMaskReason.NAMED_FAILED
            TactileNamedExecutionState.PARTIAL -> TactileSemanticMaskReason.NAMED_PARTIAL
            TactileNamedExecutionState.COMPLETED_EMPTY -> TactileSemanticMaskReason.NAMED_COMPLETED_EMPTY
            TactileNamedExecutionState.COMPLETED -> null
        }
        if (stateFailure != null) return rejected(named, masks, stateFailure)
        if (named.coordinates != TactileSemanticCoordinates.SENSOR_ORIGINAL ||
            masks.coordinates != TactileSemanticCoordinates.SENSOR_ORIGINAL) {
            return rejected(named, masks, TactileSemanticMaskReason.COORDINATES_UNCONFIRMED)
        }
        val model = unifiedModel?.takeIf {
            it.enabled && it.key == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY &&
                named.activeModelKey == it.key &&
                (primaryModelKey == it.key || fallbackModelKey == it.key)
        } ?: return rejected(named, masks, TactileSemanticMaskReason.ACTIVE_MODEL_UNCONFIRMED)

        val qualified = named.detections.mapIndexed { index, detection -> qualify(index, detection, model) }
        val preparedMasks = masks.masks.mapIndexed { index, mask -> prepareMask(index, mask, named.capture.token) }
        val pixelFactsByDetection = qualified.map { q ->
            if (q.failure != null) emptyList() else preparedMasks.map { mask -> containment(q.detection.bboxNorm, mask) }
        }
        val decisions = qualified.map { q ->
            val overlappingIndices = if (q.failure != null) emptyList() else qualified.filter {
                it.index != q.index && it.failure == null && overlaps(q.detection.bboxNorm, it.detection.bboxNorm)
            }.map { it.index }
            val facts = pixelFactsByDetection[q.index].map { fact ->
                val shared = if (fact.sensorMask == null || fact.sensorMask.area == 0 ||
                    fact.containedPixelCount != fact.sensorMask.area) emptyList() else qualified.filter {
                    it.index != q.index && it.failure == null &&
                        pixelFactsByDetection[it.index][fact.maskIndex].containedPixelCount == fact.sensorMask.area
                }.map { it.index }
                fact.copy(sharedDetectionIndices = frozenList(shared))
            }
            val eligible = facts.filter { it.reason == null && it.sensorMask?.area == it.containedPixelCount }
            val reason = when {
                q.failure != null -> q.failure
                masks.masks.isEmpty() -> TactileSemanticMaskReason.MASKS_EMPTY
                eligible.size > 1 -> TactileSemanticMaskReason.MULTIPLE_CONTAINED_MASKS
                eligible.any { it.sharedDetectionIndices.isNotEmpty() } -> TactileSemanticMaskReason.SHARED_MASK
                overlappingIndices.isNotEmpty() -> TactileSemanticMaskReason.OVERLAPPING_TACTILE_DETECTIONS
                eligible.isEmpty() -> TactileSemanticMaskReason.NO_CONTAINED_MASK
                else -> TactileSemanticMaskReason.UNIQUE_CONTAINED_MASK
            }
            val status = when (reason) {
                TactileSemanticMaskReason.UNIQUE_CONTAINED_MASK -> TactileSemanticMaskStatus.ASSOCIATE_CANDIDATE
                TactileSemanticMaskReason.MULTIPLE_CONTAINED_MASKS,
                TactileSemanticMaskReason.SHARED_MASK,
                TactileSemanticMaskReason.OVERLAPPING_TACTILE_DETECTIONS -> TactileSemanticMaskStatus.AMBIGUOUS
                else -> TactileSemanticMaskStatus.UNAVAILABLE
            }
            val candidate = if (status == TactileSemanticMaskStatus.ASSOCIATE_CANDIDATE) {
                val selected = eligible.single()
                TactileSemanticMaskCandidate(named.capture, requireNotNull(q.semantic), q.index, q.detection,
                    model.key, model.artifactSha256, model.classes.indexOf(q.detection.className),
                    requireNotNull(q.threshold), selected.maskIndex, selected.instanceMask,
                    requireNotNull(selected.sensorMask))
            } else null
            TactileSemanticMaskDecision(q.index, q.detection, q.semantic, q.threshold, q.thresholdPassed,
                status, reason, frozenList(overlappingIndices), frozenList(facts), candidate)
        }
        return TactileSemanticMaskSelection(named, masks, decisions, null)
    }

    private fun captureFailure(
        capture: TactileSemanticCapture,
        masks: TactileInstanceMaskEvidence,
        nowElapsedNs: Long,
    ): TactileSemanticMaskReason? {
        if (capture.imageReceipt !== masks.capture.imageReceipt) return TactileSemanticMaskReason.FOREIGN_IMAGE_RECEIPT
        if (!sameToken(capture.token, masks.capture.token) || !sameToken(capture.token, masks.resultToken) ||
            capture.imageQuarterTurns != masks.capture.imageQuarterTurns ||
            capture.originalDeadlineElapsedNs != masks.capture.originalDeadlineElapsedNs) {
            return TactileSemanticMaskReason.FOREIGN_CAPTURE
        }
        if (capture.token.source != FastSamFrameToken.Source.LIVE_CAMERA ||
            capture.imageQuarterTurns !in 0..3 ||
            capture.imageReceipt.cameraImageTimestampNs != capture.token.cpuImageTimestampNs ||
            capture.imageReceipt.cameraImageTimestampNs <= 0L ||
            capture.originalDeadlineElapsedNs <= capture.token.capturedElapsedNs ||
            nowElapsedNs < capture.token.capturedElapsedNs) return TactileSemanticMaskReason.INVALID_CAPTURE
        if (nowElapsedNs > capture.originalDeadlineElapsedNs) return TactileSemanticMaskReason.EXPIRED_CAPTURE
        return null
    }

    private fun sameToken(a: FastSamFrameToken, b: FastSamFrameToken): Boolean =
        a.source == b.source && a.sessionEpoch == b.sessionEpoch && a.frameId == b.frameId &&
            a.cameraTimestampNs == b.cameraTimestampNs && a.cpuImageTimestampNs == b.cpuImageTimestampNs &&
            a.capturedElapsedNs == b.capturedElapsedNs && a.geometryId == b.geometryId &&
            a.width == b.width && a.height == b.height

    private data class QualifiedDetection(
        val index: Int, val detection: DetectionCandidate, val semantic: TactileSemanticKind?,
        val threshold: Float?, val thresholdPassed: Boolean, val failure: TactileSemanticMaskReason?,
    )

    private fun qualify(index: Int, detection: DetectionCandidate, model: ModelRuntimeConfig): QualifiedDetection {
        val semantic = TactileSemanticKind.values().firstOrNull { it.className == detection.className }
        val threshold = model.thresholdForClassOrNull(detection.className)
        val score = detection.detectionConfidence
        val thresholdPassed = threshold != null && threshold.isFinite() && threshold in 0f..1f &&
            score.isFinite() && score in 0f..1f && score >= threshold
        val failure = when {
            semantic == null -> TactileSemanticMaskReason.NO_REFINED_TACTILE_SEMANTIC
            detection.className !in model.classes -> TactileSemanticMaskReason.CLASS_NOT_IN_ACTIVE_MODEL
            !model.isAllowedClass(detection.className) -> TactileSemanticMaskReason.CLASS_NOT_ALLOWED
            threshold == null || !threshold.isFinite() || threshold !in 0f..1f -> TactileSemanticMaskReason.THRESHOLD_UNCONFIRMED
            !score.isFinite() || score !in 0f..1f -> TactileSemanticMaskReason.INVALID_NAMED_SCORE
            !thresholdPassed -> TactileSemanticMaskReason.BELOW_CONFIGURED_THRESHOLD
            !validBox(detection.bboxNorm) -> TactileSemanticMaskReason.INVALID_NAMED_GEOMETRY
            else -> null
        }
        return QualifiedDetection(index, detection, semantic, threshold, thresholdPassed, failure)
    }

    private fun validBox(box: RectNorm): Boolean = box.x.isFinite() && box.y.isFinite() &&
        box.width.isFinite() && box.height.isFinite() && box.x >= 0f && box.y >= 0f &&
        box.width > 0f && box.height > 0f && box.x + box.width <= 1f && box.y + box.height <= 1f

    private fun overlaps(a: RectNorm, b: RectNorm): Boolean =
        minOf(a.x + a.width, b.x + b.width) > maxOf(a.x, b.x) &&
            minOf(a.y + a.height, b.y + b.height) > maxOf(a.y, b.y)

    private data class PreparedMask(
        val index: Int, val original: InstanceMask, val binary: BinaryImageMask?,
        val failure: TactileSemanticMaskReason?,
    )

    private fun prepareMask(index: Int, mask: InstanceMask, token: FastSamFrameToken): PreparedMask {
        if (mask.imageWidth() != token.width || mask.imageHeight() != token.height) {
            return PreparedMask(index, mask, null, TactileSemanticMaskReason.FOREIGN_MASK_DIMENSIONS)
        }
        val left = mask.maskLeft()
        val top = mask.maskTop()
        val right = mask.maskRightExclusive()
        val bottom = mask.maskBottomExclusive()
        if (left < 0 || top < 0 || right <= left || bottom <= top || right > token.width || bottom > token.height ||
            !mask.score().isFinite() || mask.score() !in 0f..1f) {
            return PreparedMask(index, mask, null, TactileSemanticMaskReason.INVALID_MASK)
        }
        val binary = BinaryImageMask.fromPackedRoi(token.width, token.height, left, top, right - left,
            bottom - top, mask.copyPackedBits())
        var actualArea = 0
        var pixelsValid = true
        mask.forEachPixel { x, y ->
            actualArea++
            if (x !in left until right || y !in top until bottom || !binary.contains(x, y)) pixelsValid = false
        }
        val failure = when {
            !pixelsValid || actualArea != mask.area() || actualArea != binary.area -> TactileSemanticMaskReason.INVALID_MASK
            actualArea == 0 -> TactileSemanticMaskReason.EMPTY_MASK
            else -> null
        }
        return PreparedMask(index, mask, binary, failure)
    }

    private fun containment(box: RectNorm, prepared: PreparedMask): TactileMaskContainmentFact {
        val mask = prepared.original
        if (prepared.failure != null) return TactileMaskContainmentFact(prepared.index, mask, prepared.binary,
            null, null, emptyList(), prepared.failure)
        var contained = 0
        var overlapping = 0
        mask.forEachPixel { x, y ->
            // Compare whole pixel cells at the same normalized precision as the original named box.
            val left = x.toFloat() / mask.imageWidth()
            val top = y.toFloat() / mask.imageHeight()
            val right = (x + 1).toFloat() / mask.imageWidth()
            val bottom = (y + 1).toFloat() / mask.imageHeight()
            if (left >= box.x && top >= box.y && right <= box.x + box.width && bottom <= box.y + box.height) contained++
            if (minOf(right, box.x + box.width) > maxOf(left, box.x) &&
                minOf(bottom, box.y + box.height) > maxOf(top, box.y)) overlapping++
        }
        return TactileMaskContainmentFact(prepared.index, mask, prepared.binary, contained, overlapping,
            emptyList(), if (contained == requireNotNull(prepared.binary).area) null else TactileSemanticMaskReason.NO_CONTAINED_MASK)
    }

    private fun rejected(named: TactileNamedSemanticEvidence, masks: TactileInstanceMaskEvidence,
                         reason: TactileSemanticMaskReason) = TactileSemanticMaskSelection(named, masks, emptyList(), reason)
}

private fun <T> frozenList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
