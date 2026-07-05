package kr.co.hanium.dreamup.walksafe.depth

import java.util.Locale

data class DepthSmokeResult(
    val source: DepthSource,
    val stats: DepthStats,
) {
    fun summaryText(): String {
        val distance = stats.medianM?.let { String.format(Locale.US, "%.2fm", it) } ?: "거리 없음"
        return "${source.name} · $distance · samples ${stats.validSampleCount}"
    }
}

class DepthSmokePipeline(
    private val sampler: DepthSampler = DepthSampler(),
) {
    fun sampleCenter(snapshot: DepthFrameSnapshot): DepthSmokeResult? {
        val centerPolygon = bboxPolygon(RectNorm(0.30f, 0.30f, 0.40f, 0.40f), erosionRatio = 0.05f)
        val rawDepth = snapshot.rawDepth
        val rawConfidence = snapshot.rawConfidence
        if (rawDepth != null && rawConfidence != null) {
            val rawStats = sampler.sampleObjectDepth(
                depthImage = rawDepth,
                confidenceImage = rawConfidence,
                polygonDepthNorm = centerPolygon,
                options = DepthSampleOptions(minValidSamples = 12, minConfidence = 0.35f),
            )
            if (rawStats.medianM != null) {
                return DepthSmokeResult(DepthSource.ARCORE_RAW_DEPTH, rawStats)
            }
        }

        val fullDepth = snapshot.fullDepth ?: return null
        val fullStats = sampler.sampleObjectDepth(
            depthImage = fullDepth,
            confidenceImage = null,
            polygonDepthNorm = centerPolygon,
            options = DepthSampleOptions(minValidSamples = 12, minConfidence = null),
        )
        return if (fullStats.medianM != null) DepthSmokeResult(DepthSource.ARCORE_FULL_DEPTH, fullStats) else null
    }
}
