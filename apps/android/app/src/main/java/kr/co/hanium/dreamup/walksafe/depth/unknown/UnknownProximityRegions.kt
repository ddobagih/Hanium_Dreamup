package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.depth.BinaryImageMask
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.DepthStats
import kr.co.hanium.dreamup.walksafe.depth.ObjectGeometry
import kotlin.math.abs

/** Queue grouping of repeatedly measured regions. It conveys no object identity or motion. */
internal class UnknownProximityRegions {
    data class Sample(val index: Int, val mask: BinaryImageMask, val geometry: ObjectGeometry,
                      val source: DepthSource, val stats: DepthStats, val rayDistanceM: Float?,
                      val depthTimestampNs: Long, val newDepthInformation: Boolean = true)
    data class Observation(val sample: Sample, val regionId: String, val count: Int,
                           val firstMs: Long, val lastMs: Long,
                           val sourceIndices: Set<Int> = setOf(sample.index)) {
        val stableMs: Long get() = lastMs - firstMs
    }
    private var generation = 0L
    private var serial = 0L
    private var previous = emptyList<Observation>()
    /** Matching reused support only; callers may preserve original, still-unexpired queue evidence. */
    var retainedRegionIds: Set<String> = emptySet()
        private set

    fun reset() { generation++; previous = emptyList(); retainedRegionIds = emptySet() }

    fun update(samples: List<Sample>, timestampMs: Long): List<Observation> {
        val grouped = groupDuplicateSupport(samples)
        val eligible = grouped.map { group -> previous.filter { old ->
            val sample = group.first()
            val gapMs = timestampMs - old.lastMs
            val independent = sample.newDepthInformation && sample.depthTimestampNs > old.sample.depthTimestampNs
            val reused = !sample.newDepthInformation && sample.depthTimestampNs == old.sample.depthTimestampNs
            gapMs in 1L..800L && sample.source == old.sample.source && (independent || reused) &&
                sample.mask.iou(old.sample.mask) >= .60f &&
                // This is a bounded region-continuity envelope, never a measured object velocity.
                // Allow ordinary approach across slower samples, but reset on a large one-frame jump.
                abs(requireNotNull(sample.stats.medianM) - requireNotNull(old.sample.stats.medianM)) <=
                    if (independent) minOf(1f, .10f + 2f * gapMs / 1_000f) else .15f
        } }
        val retained = mutableListOf<Observation>()
        val current = mutableListOf<Observation>()
        val reusedIds = mutableSetOf<String>()
        grouped.forEachIndexed { index, group ->
            val sample = group.first()
            // Genuine splits/merges remain ambiguous; duplicate support was collapsed beforehand.
            val old = eligible[index].singleOrNull()?.takeIf { candidate ->
                eligible.count { candidate in it } == 1
            }
            if (sample.newDepthInformation) {
                val next = Observation(sample, old?.regionId ?: "unknown-proximity-$generation-${++serial}",
                    (old?.count ?: 0) + 1, old?.firstMs ?: timestampMs, timestampMs,
                    group.map { it.index }.toSet())
                retained += next
                current += next
            } else if (old != null) {
                // A current matching mask can bridge a reused depth frame. It cannot extend the
                // evidence lifetime, increment its count, or produce a new metric warning.
                retained += old
                reusedIds += old.regionId
            }
        }
        previous = retained
        retainedRegionIds = reusedIds.toSet()
        return current
    }

    private fun groupDuplicateSupport(samples: List<Sample>): List<List<Sample>> {
        val groups = mutableListOf<MutableList<Sample>>()
        for (sample in samples.sortedWith(compareByDescending<Sample> { it.stats.validSampleCount }
            .thenByDescending { it.mask.area }.thenBy { it.mask.left }.thenBy { it.mask.top })) {
            // High foreground IoU excludes neighbouring/disconnected surfaces. The tighter depth
            // tolerance is for duplicate support in ONE capture, not inter-frame approach.
            // All-pairs agreement prevents an intermediate mask from bridging distinct regions.
            val group = groups.firstOrNull { group -> group.all { other ->
                sample.source == other.source && sample.depthTimestampNs == other.depthTimestampNs &&
                    sample.newDepthInformation == other.newDepthInformation &&
                    sample.mask.iou(other.mask) >= .85f &&
                    abs(requireNotNull(sample.stats.medianM) - requireNotNull(other.stats.medianM)) <= .15f
            } }
            if (group == null) groups += mutableListOf(sample) else group += sample
        }
        // Keep one real representative's support/statistics. Overlap never adds independent pixels.
        return groups
    }
}
