package kr.co.hanium.dreamup.walksafe.depth

/** Same existing three-sample / 8 cm coherence rule as unknown spatial selection. Not accuracy. */
internal fun nearestCoherentMeasuredRange(ranges: List<Float>): Float? {
    val sorted = ranges.filter { it.isFinite() && it in .1f..20f }.sorted()
    return sorted.indices.firstOrNull { it + 2 < sorted.size && sorted[it + 2] - sorted[it] <= .08f }
        ?.let { sorted[it] }
}

/** Uses accepted surface pixels and the actual capture mapping, not the bounding-box center. */
internal fun measuredSurfaceRange(stats: DepthStats, mapper: CoordinateMapper, pose: CameraPoseEvidence): Float? {
    val origin = Vec3(pose.positionX, pose.positionY, pose.positionZ)
    return nearestCoherentMeasuredRange(stats.surfaceSamples.mapNotNull { sample ->
        if (sample.confidence?.let { !it.isFinite() || it !in .55f..1f } == true) return@mapNotNull null
        val imagePoint = mapper.depthToImage(sample.depthUv) ?: return@mapNotNull null
        val position = pose.objectCenterInAnchor(imagePoint, sample.zM) ?: return@mapNotNull null
        (position - origin).norm()
    })
}
