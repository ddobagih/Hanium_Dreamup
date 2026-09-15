package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot

/** Source-clock gate before the detector-only depth pipeline advances its distance history. */
internal class CameraTestDepthSourceGate {
    private var lastFrameTimestampNs = 0L
    private var lastImageTimestampNs = 0L

    fun qualify(snapshot: DepthFrameSnapshot): DepthFrameSnapshot? {
        val imageTimestamp = snapshot.cameraImageTimestampNs ?: return null
        if (snapshot.frameTimestampNs <= lastFrameTimestampNs || imageTimestamp <= lastImageTimestampNs) return null
        lastFrameTimestampNs = snapshot.frameTimestampNs
        lastImageTimestampNs = imageTimestamp
        // Reprojected Raw may still be displayed diagnostically, but is not another distance
        // observation. A fresh Full image remains usable when Raw is missing or reused.
        val rawFresh = snapshot.hasFreshMetricRawDepth && snapshot.rawConfidenceMatchesRawDepth
        return snapshot.copy(
            rawDepth = snapshot.rawDepth.takeIf { rawFresh },
            rawConfidence = snapshot.rawConfidence.takeIf { rawFresh },
            fullDepth = snapshot.fullDepth.takeIf { snapshot.hasFreshFullDepth },
        )
    }
}
