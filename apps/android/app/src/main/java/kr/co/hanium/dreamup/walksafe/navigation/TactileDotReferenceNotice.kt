package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.depth.DepthAvailability
import kr.co.hanium.dreamup.walksafe.depth.MessagePolicyConfig
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth

const val TACTILE_DOT_REFERENCE_NOTICE_KO = "점형 점자블록 참고 정보입니다. 주변 환경을 확인하세요."

/** A captured observation for the advisory channel; it never grants movement or report authority. */
data class TactileDotReferenceNotice(
    val trackId: String,
    val captureFrameId: Long,
    /** Capture receipt in the elapsed clock, kept apart from the ARCore camera timestamp. */
    val capturedAtElapsedRealtimeMs: Long,
    /** Issued deadline bounded by the source lease and detector age; queueing never extends it. */
    val validUntilElapsedRealtimeMs: Long,
) {
    val message: String get() = TACTILE_DOT_REFERENCE_NOTICE_KO

    fun isValidAt(nowElapsedRealtimeMs: Long): Boolean =
        trackId.isNotBlank() && captureFrameId > 0L && capturedAtElapsedRealtimeMs >= 0L &&
            nowElapsedRealtimeMs >= capturedAtElapsedRealtimeMs && nowElapsedRealtimeMs <= validUntilElapsedRealtimeMs
}

/** Selects reference evidence without using route direction or claiming what the dot block marks. */
internal class TactileDotReferenceNoticePolicy(
    private val tactileLimits: TactileRoutePolicyConfig = TactileRoutePolicyConfig(),
    private val messageLimits: MessagePolicyConfig = MessagePolicyConfig(),
) {
    fun noticeFor(outputs: List<TrackedObjectDepth>, context: TactileProjectionContext?): TactileDotReferenceNotice? {
        context ?: return null
        val decisionMs = context.decisionElapsedRealtimeMs ?: return null
        val captureId = context.captureFrameId?.takeIf { it > 0L } ?: return null
        val detectionAgeMs = context.detectionAgeMs ?: return null
        if (!TactileObservationEvidence.hasFreshCameraFrame(context) ||
            detectionAgeMs !in 0L..TactileObservationEvidence.MAX_DETECTION_AGE_MS
        ) return null
        val output = outputs.filter {
            it.className.equals("dot_tactile_paving", ignoreCase = true) && it.trackId.isNotBlank() &&
                it.frameId == captureId && it.depthAvailability == DepthAvailability.MEASURED &&
                !it.spatialCoverageLimited && TactileObservationEvidence.hasTrustedMetricDepth(it) &&
                it.detectionConfidence.isFinite() && it.detectionConfidence in tactileLimits.minimumConfidence..1f &&
                it.confidence.finalScore >= tactileLimits.minimumConfidence &&
                it.trackAgeFrames >= tactileLimits.minimumStableFrames && it.trackStableMs >= tactileLimits.minimumStableMs &&
                it.riskDistanceM?.let { distance -> distance.isFinite() && distance > 0f && distance <= messageLimits.warningDistanceM } == true &&
                it.sourceValidUntilElapsedRealtimeMs?.let { deadline -> decisionMs <= deadline } == true &&
                validImageGeometry(it)
        }.minWithOrNull(compareBy<TrackedObjectDepth> { requireNotNull(it.riskDistanceM) }
            .thenByDescending { it.confidence.finalScore }.thenBy { it.trackId }) ?: return null
        val remainingDetectionAgeMs = TactileObservationEvidence.MAX_DETECTION_AGE_MS - detectionAgeMs
        val detectionDeadline = if (decisionMs > Long.MAX_VALUE - remainingDetectionAgeMs) Long.MAX_VALUE
            else decisionMs + remainingDetectionAgeMs
        return TactileDotReferenceNotice(
            trackId = output.trackId, captureFrameId = captureId,
            capturedAtElapsedRealtimeMs = context.nowElapsedRealtimeMs,
            validUntilElapsedRealtimeMs = minOf(requireNotNull(output.sourceValidUntilElapsedRealtimeMs), detectionDeadline),
        )
    }

    private fun validImageGeometry(output: TrackedObjectDepth): Boolean {
        val box = output.bboxNorm
        return listOf(box.x, box.y, box.width, box.height).all(Float::isFinite) &&
            box.x in 0f..1f && box.y in 0f..1f && box.width > 0f && box.height > 0f &&
            box.x + box.width <= 1f && box.y + box.height <= 1f && validPoint(output.centerNorm) &&
            output.maskAreaNorm.isFinite() && output.maskAreaNorm > 0f &&
            output.polygonNorm.size >= 3 && output.polygonNorm.all(::validPoint)
    }

    private fun validPoint(point: Point2): Boolean = point.x.isFinite() && point.y.isFinite() && point.x in 0f..1f && point.y in 0f..1f
}
