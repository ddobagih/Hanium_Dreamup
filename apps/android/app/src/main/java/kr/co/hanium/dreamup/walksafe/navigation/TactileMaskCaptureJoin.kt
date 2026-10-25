package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.depth.unknown.sameCapture
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.inference.TactileInstanceMaskEvidence
import kr.co.hanium.dreamup.walksafe.inference.TactileNamedSemanticEvidence
import kr.co.hanium.dreamup.walksafe.inference.TactileSemanticCapture
import kr.co.hanium.dreamup.walksafe.inference.TactileSemanticCoordinates
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot

/** One admitted SAM capture; three original producers meet here without replaying depth. */
internal class TactileMaskCaptureJoin(
    val semanticCapture: TactileSemanticCapture,
    val frozenCapture: FrozenUnknownDepthCapture,
    val runtimeConfig: TwoModelRuntimeConfig,
) {
    private var named: Pair<DetectionSnapshot, TactileNamedSemanticEvidence>? = null
    private var masks: TactileInstanceMaskEvidence? = null
    private var outputs: Pair<DetectionSnapshot, List<TrackedObjectDepth>>? = null
    private var cancelled = false
    private var claimed = false

    @Synchronized fun recordNamed(snapshot: DetectionSnapshot, result: AndroidDetectionResult): Boolean {
        if (cancelled || claimed || !matches(snapshot) || result.partial || !result.inferenceCompleted ||
            snapshot.detections != result.detections
        ) return false
        if (named == null) named = snapshot to TactileNamedSemanticEvidence.fromResult(
            semanticCapture, result, TactileSemanticCoordinates.SENSOR_ORIGINAL)
        return ready()
    }
    @Synchronized fun recordMasks(token: FastSamFrameToken, values: List<InstanceMask>): Boolean {
        if (cancelled || claimed || !sameCapture(semanticCapture.token, token)) return false
        if (masks == null) masks = TactileInstanceMaskEvidence(semanticCapture, token, values,
            TactileSemanticCoordinates.SENSOR_ORIGINAL)
        return ready()
    }
    @Synchronized fun recordOutputs(snapshot: DetectionSnapshot, values: List<TrackedObjectDepth>): Boolean {
        if (cancelled || claimed || !matches(snapshot)) return false
        if (outputs == null) outputs = snapshot to values.toList()
        return ready()
    }
    @Synchronized fun cancel() { cancelled = true; named = null; masks = null; outputs = null }
    @Synchronized fun isCancelled(): Boolean = cancelled
    @Synchronized fun claim(decisionNs: Long): TactileMaskCaptureBundle? {
        if (cancelled || claimed || !ready() || decisionNs < semanticCapture.token.capturedElapsedNs ||
            decisionNs > semanticCapture.originalDeadlineElapsedNs
        ) return null
        claimed = true
        val completed = requireNotNull(named)
        return TactileMaskCaptureBundle(semanticCapture, frozenCapture, runtimeConfig,
            completed.first, completed.second, requireNotNull(masks), requireNotNull(outputs).second)
    }
    private fun ready(): Boolean = named != null && masks != null && outputs?.first === named?.first
    private fun matches(snapshot: DetectionSnapshot): Boolean {
        val token = semanticCapture.token
        val evidence = snapshot.frameEvidence ?: return false
        return !snapshot.partial && snapshot.captureFrameId == token.frameId &&
            snapshot.frameTimestampMs == token.cameraTimestampNs / 1_000_000L &&
            snapshot.capturedAtElapsedRealtimeMs == token.capturedElapsedNs / 1_000_000L &&
            evidence.frameId == token.frameId && evidence.imageQuarterTurns == semanticCapture.imageQuarterTurns &&
            evidence.depthSnapshot.cameraImageTimestampNs == token.cpuImageTimestampNs &&
            snapshot.imageWidth == token.width && snapshot.imageHeight == token.height
    }
}

internal data class TactileMaskCaptureBundle(
    val semanticCapture: TactileSemanticCapture,
    val frozenCapture: FrozenUnknownDepthCapture,
    val runtimeConfig: TwoModelRuntimeConfig,
    val snapshot: DetectionSnapshot,
    val named: TactileNamedSemanticEvidence,
    val masks: TactileInstanceMaskEvidence,
    val outputs: List<TrackedObjectDepth>,
)
