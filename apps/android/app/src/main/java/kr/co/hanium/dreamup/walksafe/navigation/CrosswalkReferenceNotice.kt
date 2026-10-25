package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.depth.CurrentTrackedFrameObservation
import kr.co.hanium.dreamup.walksafe.depth.MessagePolicyConfig
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth

/** Issued current-image reference. It never grants crossing, distance or route-progress authority. */
data class CrosswalkReferenceNotice(
    val trackId: String,
    val captureFrameId: Long,
    val capturedAtElapsedRealtimeMs: Long,
    val validUntilElapsedRealtimeMs: Long,
    val routeId: String,
    val routeVersion: Long,
    val routeSegmentIndex: Int,
) {
    val message: String get() = CROSSWALK_REFERENCE_NOTICE_KO

    fun isValidAt(nowElapsedRealtimeMs: Long): Boolean = trackId.isNotBlank() && captureFrameId > 0L &&
        capturedAtElapsedRealtimeMs >= 0L && nowElapsedRealtimeMs >= capturedAtElapsedRealtimeMs &&
        nowElapsedRealtimeMs <= validUntilElapsedRealtimeMs && routeId.isNotBlank() &&
        routeVersion > 0L && routeSegmentIndex >= 0

    fun isValidForRoute(activeRouteId: String?, progress: TactileActiveRouteProgress?): Boolean =
        activeRouteId == routeId && progress != null && progress.routeVersion == routeVersion &&
            progress.segmentIndex == routeSegmentIndex
}

/** Recognition, image position and route context are checked independently of metric depth. */
internal class CrosswalkObservationReferencePolicy(
    private val tactileLimits: TactileRoutePolicyConfig = TactileRoutePolicyConfig(),
    private val messageLimits: MessagePolicyConfig = MessagePolicyConfig(),
) {
    fun noticeFor(
        outputs: List<TrackedObjectDepth>,
        context: TactileProjectionContext?,
        observation: CurrentTrackedFrameObservation?,
        navigationActive: Boolean,
        tmapOnRoute: Boolean,
        activeRouteId: String?,
        activeRouteProgress: TactileActiveRouteProgress?,
    ): CrosswalkReferenceNotice? {
        context ?: return null
        observation ?: return null
        val decisionMs = context.decisionElapsedRealtimeMs ?: return null
        val target = observation.targetKey
        if (!navigationActive || !tmapOnRoute || !observation.isFreshAt(decisionMs) ||
            target.frameId != context.captureFrameId || target.capturedAtElapsedRealtimeMs != context.nowElapsedRealtimeMs ||
            !TactileObservationEvidence.hasFreshCameraFrame(context) || !TactileObservationEvidence.hasFreshLocation(context)
        ) return null
        val sourceAgeMs = decisionMs - observation.sourceKey.capturedAtElapsedRealtimeMs
        if (sourceAgeMs !in 0L..TactileObservationEvidence.MAX_DETECTION_AGE_MS || context.detectionAgeMs != sourceAgeMs) return null
        val routeId = context.expectedRouteId?.takeIf(String::isNotBlank) ?: return null
        val route = context.routeProjection?.takeIf { it.routeId == routeId && it.routeId == activeRouteId } ?: return null
        val routeVersion = route.routeRevision?.takeIf { it > 0L } ?: return null
        val progress = activeRouteProgress ?: return null
        if (route.segmentIndex < 0 || progress.routeVersion != routeVersion ||
            progress.segmentIndex != route.segmentIndex ||
            !route.distanceToRouteM.isFinite() || route.distanceToRouteM < 0.0 || !route.bearingDeg.isFinite() ||
            !validLocation(route.segmentStart) || !validLocation(route.segmentEnd)
        ) return null
        val output = outputs.filter { output ->
            val sourceIndex = output.sourceDetectionIndex
            val tracked = sourceIndex?.let { index -> observation.trackedObservations.singleOrNull { it.sourceIndex == index } }
            val geometry = tracked?.geometry
            output.trackId.isNotBlank() && output.frameId == target.frameId &&
                CrosswalkReferencePolicy.isCrosswalkDetectorClass(output.className) &&
                tracked != null && tracked.failure == null && geometry != null && geometry.className == output.className &&
                geometry.detectionConfidence == output.detectionConfidence &&
                tracked.trackingQuality.isFinite() && tracked.trackingQuality in
                    CurrentTrackedFrameObservation.MIN_QUALITATIVE_TRACKING_QUALITY..1f &&
                output.detectionConfidence.isFinite() && output.detectionConfidence in messageLimits.pathGuidanceMinConfidence..1f &&
                output.trackAgeFrames >= tactileLimits.minimumStableFrames && output.trackStableMs >= tactileLimits.minimumStableMs &&
                validImageGeometry(output)
        }.maxWithOrNull(compareBy<TrackedObjectDepth> { it.detectionConfidence }.thenBy { it.trackStableMs }
            .thenBy { it.trackId }) ?: return null
        // Depth may be absent or leased by an older observation. Only the actual visual source and
        // captured position own this reference deadline; another target or queue cannot renew it.
        val visualDeadline = deadline(observation.sourceKey.capturedAtElapsedRealtimeMs,
            TactileObservationEvidence.MAX_DETECTION_AGE_MS)
        val locationDeadline = deadline(requireNotNull(context.trustedLocation).elapsedRealtimeMs,
            TactileObservationEvidence.MAX_LOCATION_AGE_MS)
        return CrosswalkReferenceNotice(output.trackId, target.frameId, target.capturedAtElapsedRealtimeMs,
            minOf(visualDeadline, locationDeadline), routeId, routeVersion, route.segmentIndex)
    }

    private fun validLocation(point: RoutePoint): Boolean = point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
        point.longitude.isFinite() && point.longitude in -180.0..180.0

    private fun validImageGeometry(output: TrackedObjectDepth): Boolean {
        val box = output.bboxNorm
        return output.imageQuarterTurns?.let { it in 0..3 } == true &&
            listOf(box.x, box.y, box.width, box.height).all(Float::isFinite) &&
            box.x in 0f..1f && box.y in 0f..1f && box.width > 0f && box.height > 0f &&
            box.x + box.width <= 1f && box.y + box.height <= 1f && output.polygonNorm.size >= 3 &&
            output.polygonNorm.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f }
    }

    private fun deadline(observedAtMs: Long, maximumAgeMs: Long): Long =
        if (observedAtMs > Long.MAX_VALUE - maximumAgeMs) Long.MAX_VALUE else observedAtMs + maximumAgeMs
}
