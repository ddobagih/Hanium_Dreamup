package kr.co.hanium.dreamup.walksafe.runtime.vision.frame

import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot

internal object FrameRecognitionAdmission {
    fun matchesDepth(
        cameraTimestampNs: Long?,
        frameId: Long,
        snapshot: DepthFrameSnapshot,
    ): Boolean = cameraTimestampNs != null && cameraTimestampNs > 0L && frameId > 0L &&
        snapshot.frameTimestampNs == frameId && snapshot.cameraImageTimestampNs == cameraTimestampNs

    /** A visual lease cannot upgrade a missing/foreign CPU observation into recognition evidence. */
    fun allowsVisualWork(
        cameraTimestampNs: Long?,
        frameId: Long,
        snapshot: DepthFrameSnapshot,
        visualLeaseAllowsWork: () -> Boolean,
    ): Boolean = matchesDepth(cameraTimestampNs, frameId, snapshot) && visualLeaseAllowsWork()

    /** Preserve the existing reprojected Raw policy only behind this capture's actual CPU clock. */
    fun hasMetricDepth(
        cameraTimestampNs: Long?,
        frameId: Long,
        snapshot: DepthFrameSnapshot,
    ): Boolean = matchesDepth(cameraTimestampNs, frameId, snapshot) &&
        (snapshot.hasMetricRawDepth || snapshot.hasFreshFullDepth)

    fun metricSampleCount(
        cameraTimestampNs: Long?,
        frameId: Long,
        snapshot: DepthFrameSnapshot,
        countSamples: () -> Int,
    ): Int = if (matchesDepth(cameraTimestampNs, frameId, snapshot)) {
        countSamples()
    } else 0
}

