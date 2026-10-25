package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class CameraObjectPresentationTest {
    private val source = CameraObjectPresentation.Capture(1, 1_000_000_000L, 1_000L, "sensor-portrait")
    private val box = RectNorm(.2f, .2f, .3f, .4f)
    private fun region(id: String? = "person", rect: RectNorm = box, depth: Double? = 2.0) =
        CameraObjectPresentation.Region(rect, id, depth, .9f)
    private fun capture(atMs: Long) = source.copy(frameId = atMs * 1_000_000L, capturedAtMs = atMs)
    private fun presentation(regions: List<CameraObjectPresentation.Region> = listOf(region())) =
        CameraObjectPresentation().also { assertTrue(it.recordPrimary(source, regions)) }

    @Test fun oneKnownAndOneUnknownBecomeOneVisualObjectWithoutMutatingInputs() {
        val known = listOf(region())
        val unknown = listOf(region("mask"))
        val presentation = presentation(known)
        assertEquals(2, known.size + unknown.size)
        val merged = presentation.mergedUnknownIndices(source, unknown, 1_020L)
        assertEquals(mapOf(0 to 0), merged)
        assertEquals(1, known.size + unknown.filterIndexed { index, _ -> index !in merged }.size)
        assertEquals(1, known.size)
        assertEquals(1, unknown.size)
    }

    @Test fun distinctObjectRemainsBesideTheMergedKnownObject() {
        val unknown = listOf(region("duplicate"), region("independent", RectNorm(.7f, .2f, .2f, .4f)))
        assertEquals(mapOf(0 to 0), presentation().mergedUnknownIndices(source, unknown, 1_020L))
    }

    @Test fun matchingSameCaptureDoesNotRequireTrackingHistory() {
        assertEquals(mapOf(0 to 0), presentation(listOf(region(null)))
            .mergedUnknownIndices(source, listOf(region(null)), 1_010L))
    }

    @Test fun delayedResultFollowsEverySourceFrameAndReturnsCurrentPrimaryIndex() {
        val presentation = presentation()
        presentation.recordPrimary(capture(1_100), listOf(region(rect = box.copy(x = .23f))))
        presentation.recordPrimary(capture(1_200), listOf(region("other", RectNorm(.7f, .2f, .2f, .4f)),
            region(rect = box.copy(x = .26f))))
        assertEquals(mapOf(0 to 1), presentation.mergedUnknownIndices(source, listOf(region("mask")), 1_250))
    }

    @Test fun lateResultCannotUseOnlyCurrentOverlappingBoxWithoutItsSourceAnchor() {
        val presentation = presentation(emptyList())
        presentation.recordPrimary(capture(1_100), listOf(region()))
        assertTrue(presentation.mergedUnknownIndices(source, listOf(region("mask")), 1_200).isEmpty())
    }

    @Test fun nearbyCurrentFrameCannotSubstituteForMissingExactSourceFrame() {
        assertTrue(presentation().mergedUnknownIndices(capture(1_001), listOf(region()), 1_010).isEmpty())
        assertTrue(presentation().mergedUnknownIndices(source.copy(capturedAtMs = 999), listOf(region()), 1_010).isEmpty())
    }

    @Test fun overlappingObjectsAtDifferentDepthArePreserved() {
        assertTrue(presentation().mergedUnknownIndices(source, listOf(region(depth = 5.0)), 1_010).isEmpty())
    }

    @Test fun missingMetricDepthCannotMergeIdenticalBoxes() {
        assertTrue(presentation(listOf(region(depth = null))).mergedUnknownIndices(source, listOf(region()), 1_010).isEmpty())
        assertTrue(presentation().mergedUnknownIndices(source, listOf(region(depth = null)), 1_010).isEmpty())
    }

    @Test fun partialMaskWithinTheKnownBoxIsPreserved() {
        val partial = box.copy(width = .1f)
        assertTrue(presentation().mergedUnknownIndices(source, listOf(region(rect = partial)), 1_010).isEmpty())
    }

    @Test fun multipleOverlappingPrimaryBoxesAreAmbiguousEvenWithMissingDepth() {
        val primary = listOf(region(), region("other", box.copy(x = .21f), null))
        assertTrue(presentation(primary).mergedUnknownIndices(source, listOf(region("mask")), 1_010).isEmpty())
    }

    @Test fun multipleUnknownRegionsForOnePrimaryArePreserved() {
        assertTrue(presentation().mergedUnknownIndices(source,
            listOf(region("mask-a"), region("mask-b", box.copy(x = .21f))), 1_010).isEmpty())
    }

    @Test fun changedGeometryDropsSourceHistory() {
        val presentation = presentation()
        presentation.recordPrimary(capture(1_100).copy(geometryId = "landscape"), listOf(region()))
        assertTrue(presentation.mergedUnknownIndices(source, listOf(region()), 1_110).isEmpty())
    }

    @Test fun changedEpochDropsSourceHistoryAndRejectsOldCallbacks() {
        val presentation = presentation()
        val nextEpoch = capture(1_100).copy(epoch = 2)
        assertTrue(presentation.recordPrimary(nextEpoch, listOf(region())))
        assertFalse(presentation.recordPrimary(capture(1_200), listOf(region())))
        assertTrue(presentation.mergedUnknownIndices(source, listOf(region()), 1_210).isEmpty())
        assertEquals(mapOf(0 to 0), presentation.mergedUnknownIndices(nextEpoch, listOf(region()), 1_210))
    }

    @Test fun sourceExpiresWithoutRefreshingItsTimestampOnPrimaryUpdates() {
        val presentation = presentation()
        presentation.recordPrimary(capture(1_700), listOf(region()))
        assertEquals(mapOf(0 to 0), presentation.mergedUnknownIndices(source, listOf(region()), 1_800))
        assertTrue(presentation.mergedUnknownIndices(source, listOf(region()), 1_801).isEmpty())
    }

    @Test fun expiredCurrentPrimaryAndReversedClockCannotMerge() {
        assertTrue(presentation().mergedUnknownIndices(source, listOf(region()), 1_801).isEmpty())
        assertTrue(presentation().mergedUnknownIndices(source, listOf(region()), 999).isEmpty())
        val presentation = presentation()
        presentation.recordPrimary(capture(1_100), listOf(region()))
        assertTrue(presentation.mergedUnknownIndices(source, listOf(region()), 1_050).isEmpty())
    }

    @Test fun primaryLossRestoresUnknownImmediatelyAndReappearanceDoesNotBridgeTheGap() {
        val presentation = presentation()
        assertEquals(mapOf(0 to 0), presentation.mergedUnknownIndices(source, listOf(region()), 1_010))
        presentation.recordPrimary(capture(1_100), emptyList())
        assertTrue(presentation.mergedUnknownIndices(source, listOf(region()), 1_110).isEmpty())
        presentation.recordPrimary(capture(1_200), listOf(region()))
        assertTrue(presentation.mergedUnknownIndices(source, listOf(region()), 1_210).isEmpty())
    }

    @Test fun differentTrackAtTheSameLocationCannotInheritAnOldUnknownRegion() {
        val presentation = presentation()
        presentation.recordPrimary(capture(1_100), listOf(region("replacement")))
        assertTrue(presentation.mergedUnknownIndices(source, listOf(region()), 1_110).isEmpty())
    }

    @Test fun sameTrackIdWithAbruptGeometryChangeCannotHideAnOldUnknownRegion() {
        val presentation = presentation()
        presentation.recordPrimary(capture(1_100), listOf(region(rect = box.copy(x = .6f))))
        assertTrue(presentation.mergedUnknownIndices(source, listOf(region()), 1_110).isEmpty())
    }

    @Test fun delayedAssociationRequiresMetricContinuityAtEveryFrame() {
        for (intermediateDepth in listOf(null, 4.0)) {
            val presentation = presentation()
            presentation.recordPrimary(capture(1_100), listOf(region(depth = intermediateDepth)))
            presentation.recordPrimary(capture(1_200), listOf(region()))
            assertTrue(presentation.mergedUnknownIndices(source, listOf(region()), 1_210).isEmpty())
        }
    }

    @Test fun gradualDepthDriftCannotExceedOriginalUnknownMeasurementAgreement() {
        val presentation = presentation()
        presentation.recordPrimary(capture(1_100), listOf(region(depth = 2.2)))
        presentation.recordPrimary(capture(1_200), listOf(region(depth = 2.4)))
        assertTrue(presentation.mergedUnknownIndices(source, listOf(region()), 1_210).isEmpty())
    }

    @Test fun currentAmbiguityOrDuplicatedTrackingIdPreservesUnknown() {
        for (other in listOf(region("other", box.copy(x = .21f)), region("person", box.copy(x = .6f)))) {
            val presentation = presentation()
            presentation.recordPrimary(capture(1_100), listOf(region(), other))
            assertTrue(presentation.mergedUnknownIndices(source, listOf(region()), 1_110).isEmpty())
        }
    }

    @Test fun delayedResultWithoutStableIdIsPreserved() {
        val presentation = presentation(listOf(region(null)))
        presentation.recordPrimary(capture(1_100), listOf(region(null)))
        assertTrue(presentation.mergedUnknownIndices(source, listOf(region()), 1_110).isEmpty())
    }

    @Test fun invalidDepthConfidenceAndGeometryNeverCauseSuppression() {
        val invalid = listOf(region(depth = Double.NaN), region(depth = Double.POSITIVE_INFINITY),
            region(depth = 0.0), region(depth = -1.0), region().copy(confidence = .54f),
            region().copy(confidence = Float.NaN), region(rect = box.copy(x = Float.NaN)),
            region(rect = box.copy(width = 0f)), region(rect = box.copy(x = -.1f)),
            region(rect = box.copy(width = 1f)))
        invalid.forEach { candidate ->
            assertTrue(presentation().mergedUnknownIndices(source, listOf(candidate), 1_010).isEmpty())
            assertTrue(presentation(listOf(candidate)).mergedUnknownIndices(source, listOf(region()), 1_010).isEmpty())
        }
    }

    @Test fun oldDuplicateAndInvalidPrimaryCallbacksCannotRollBackCurrentState() {
        val presentation = presentation()
        assertTrue(presentation.recordPrimary(capture(1_100), emptyList()))
        assertFalse(presentation.recordPrimary(source, listOf(region())))
        assertFalse(presentation.recordPrimary(capture(1_100), listOf(region())))
        assertFalse(presentation.recordPrimary(capture(1_200).copy(capturedAtMs = 1_050), listOf(region())))
        assertFalse(presentation.recordPrimary(capture(1_200).copy(frameId = 0L), listOf(region())))
        assertTrue(presentation.mergedUnknownIndices(source, listOf(region()), 1_210).isEmpty())
    }

    @Test fun historyStorageIsBoundedEvenWhenManyFramesShareOneMillisecond() {
        val presentation = presentation()
        repeat(64) { offset -> presentation.recordPrimary(source.copy(frameId = source.frameId + offset + 1), listOf(region())) }
        assertTrue(presentation.mergedUnknownIndices(source, listOf(region()), 1_010).isEmpty())
    }

    @Test fun callerListMutationCannotEraseARecordedPrimaryFrame() {
        val regions = mutableListOf(region())
        val presentation = presentation(regions)
        regions.clear()
        assertEquals(mapOf(0 to 0), presentation.mergedUnknownIndices(source, listOf(region()), 1_010))
        presentation.clear()
        assertTrue(presentation.mergedUnknownIndices(source, listOf(region()), 1_010).isEmpty())
    }

    @Test fun mergeBeforeDisplayCapLetsAnIndependentSeventhCandidateFillTheFreedSlot() {
        val unrelated = (0..5).map { index -> region("other-$index", RectNorm(.75f, index * .12f, .1f, .08f)) }
        val unknown = listOf(region("duplicate")) + unrelated
        val merged = presentation().mergedUnknownIndices(source, unknown, 1_010)
        val displayed = unknown.filterIndexed { index, _ -> index !in merged }.take(6)
        assertEquals(unrelated, displayed)
        assertEquals("other-5", displayed.last().trackId)
    }

    @Test fun repeatedFreshQueriesReuseTheMergeWithoutRepeatingAnyOverlapWork() {
        val regions = (0 until 16).map { index ->
            region("track-$index", RectNorm(.05f + index % 4 * .22f, .05f + index / 4 * .22f, .1f, .1f))
        }
        val presentation = presentation(regions)
        repeat(30) { index -> presentation.recordPrimary(capture(1_010L + index * 10L), regions) }
        val expected = regions.indices.associateWith { it }
        assertEquals(expected, presentation.mergedUnknownIndices(source, regions, 1_400L))
        val first = presentation.computationStats
        assertEquals(31L, first.geometricPasses)
        assertEquals(31L * 16L * 16L, first.overlapComparisons)
        repeat(30) { index ->
            assertEquals(expected, presentation.mergedUnknownIndices(source, regions, 1_400L + index))
        }
        assertEquals(first, presentation.computationStats)
        assertTrue(presentation.mergedUnknownIndices(source, regions, 1_801L).isEmpty())
        assertTrue(presentation.mergedUnknownIndices(source, regions, 1_299L).isEmpty())
        assertEquals(first, presentation.computationStats)
        assertEquals(expected, presentation.mergedUnknownIndices(source, regions, 1_800L))
    }

    @Test fun everyNewPrimaryComputesOnlyItsNewEdgeAndCannotSkipAMetricContinuityFailure() {
        val unknown = listOf(region("mask"))
        val presentation = presentation()
        assertEquals(mapOf(0 to 0), presentation.mergedUnknownIndices(source, unknown, 1_010L))
        assertEquals(1L, presentation.computationStats.overlapComparisons)
        presentation.recordPrimary(capture(1_100L), listOf(region(depth = 2.2)))
        assertEquals(mapOf(0 to 0), presentation.mergedUnknownIndices(source, unknown, 1_110L))
        assertEquals(2L, presentation.computationStats.overlapComparisons)
        presentation.recordPrimary(capture(1_200L), listOf(region(depth = 2.4)))
        assertTrue(presentation.mergedUnknownIndices(source, unknown, 1_210L).isEmpty())
        assertEquals(3L, presentation.computationStats.overlapComparisons)
        presentation.recordPrimary(capture(1_300L), listOf(region()))
        assertTrue(presentation.mergedUnknownIndices(source, unknown, 1_310L).isEmpty())
        assertEquals(4L, presentation.computationStats.overlapComparisons)
    }

    @Test fun changedUnknownRegionsInvalidateReuseEvenWhenTheCallerMutatesItsOriginalList() {
        val unknown = mutableListOf(region("mask"))
        val presentation = presentation()
        assertEquals(mapOf(0 to 0), presentation.mergedUnknownIndices(source, unknown, 1_010L))
        unknown.add(region("second-mask"))
        assertTrue(presentation.mergedUnknownIndices(source, unknown, 1_020L).isEmpty())
        unknown.removeAt(1)
        unknown[0] = region("mask", depth = 5.0)
        assertTrue(presentation.mergedUnknownIndices(source, unknown, 1_030L).isEmpty())
        unknown[0] = region("mask")
        assertEquals(mapOf(0 to 0), presentation.mergedUnknownIndices(source, unknown, 1_040L))
        assertEquals(4L, presentation.computationStats.mergePasses)
    }

    @Test fun aNewUnknownCaptureWithEqualRegionValuesUsesItsOwnExactAnchor() {
        val unknown = listOf(region("mask"))
        val presentation = presentation()
        assertEquals(mapOf(0 to 0), presentation.mergedUnknownIndices(source, unknown, 1_010L))
        val replacement = capture(1_100L)
        presentation.recordPrimary(replacement, listOf(region("replacement")))
        assertTrue(presentation.mergedUnknownIndices(source, unknown, 1_110L).isEmpty())
        assertEquals(mapOf(0 to 0), presentation.mergedUnknownIndices(replacement, unknown, 1_110L))
        assertTrue(presentation.mergedUnknownIndices(replacement.copy(capturedAtMs = 1_099L), unknown, 1_110L).isEmpty())
    }

    @Test fun clearAndGeometryOrEpochReplacementCannotReuseAnOldExactCaptureResult() {
        val unknown = listOf(region("mask"))
        val presentation = presentation()
        assertEquals(mapOf(0 to 0), presentation.mergedUnknownIndices(source, unknown, 1_010L))
        presentation.clear()
        assertTrue(presentation.mergedUnknownIndices(source, unknown, 1_010L).isEmpty())
        presentation.recordPrimary(source, listOf(region(depth = 5.0)))
        assertTrue(presentation.mergedUnknownIndices(source, unknown, 1_010L).isEmpty())
        val rotated = capture(1_100L).copy(geometryId = "sensor-landscape")
        presentation.recordPrimary(rotated, listOf(region()))
        assertTrue(presentation.mergedUnknownIndices(source, unknown, 1_110L).isEmpty())
        assertEquals(mapOf(0 to 0), presentation.mergedUnknownIndices(rotated, unknown, 1_110L))
        val nextEpoch = rotated.copy(epoch = 2, frameId = 1L)
        presentation.recordPrimary(nextEpoch, listOf(region(depth = 5.0)))
        assertTrue(presentation.mergedUnknownIndices(rotated, unknown, 1_110L).isEmpty())
        assertTrue(presentation.mergedUnknownIndices(nextEpoch, unknown, 1_110L).isEmpty())
    }

    @Test fun aCachedSourceEvictedByTheFrameBoundCannotContinueMerging() {
        val unknown = listOf(region("mask"))
        val presentation = presentation()
        assertEquals(mapOf(0 to 0), presentation.mergedUnknownIndices(source, unknown, 1_010L))
        repeat(64) { offset ->
            presentation.recordPrimary(source.copy(frameId = source.frameId + offset + 1), listOf(region()))
        }
        assertTrue(presentation.mergedUnknownIndices(source, unknown, 1_010L).isEmpty())
    }

    @Test fun ambiguousReverseMatchesAreCountedOnceAndStillPreserveEveryUnknownRegion() {
        val unknown = List(80) { region("mask-$it") }
        val presentation = presentation(List(40) { region("track-$it") })
        assertTrue(presentation.mergedUnknownIndices(source, unknown, 1_010L).isEmpty())
        assertEquals(3_200L, presentation.computationStats.overlapComparisons)
        repeat(10) { assertTrue(presentation.mergedUnknownIndices(source, unknown, 1_020L).isEmpty()) }
        assertEquals(3_200L, presentation.computationStats.overlapComparisons)
    }

    @Test fun optimizedOutputMatchesTheUncachedBaselineAcrossDelayedAndAmbiguousScenes() {
        val random = Random(6_102_026)
        repeat(80) { scene ->
            val frames = mutableListOf<Pair<CameraObjectPresentation.Capture, List<CameraObjectPresentation.Region>>>()
            val unknown = (0 until 6).map { index ->
                region("mask-$index", gridBox(index), 2.0 + index * .5)
            }
            val presentation = CameraObjectPresentation()
            repeat(12) { step ->
                val current = capture(1_000L + step * 40L)
                val regions = unknown.mapIndexedNotNull { index, item ->
                    if (step > 0 && random.nextInt(10) == 0) null else item.copy(
                        trackId = if (step > 0 && random.nextInt(12) == 0) "replacement-$step-$index" else "track-$index",
                        bboxNorm = item.bboxNorm.copy(x = item.bboxNorm.x + random.nextInt(4) * .005f),
                        metricDistanceM = if (random.nextInt(9) == 0) null else item.metricDistanceM?.plus(random.nextInt(3) * .1),
                    )
                }.let { rows -> if (random.nextInt(8) == 0 && rows.isNotEmpty()) rows + rows.first() else rows }
                frames.add(current to regions)
                assertTrue(presentation.recordPrimary(current, regions))
                val now = current.capturedAtMs + 1L
                val expected = baselineMerge(frames, unknown)
                assertEquals("scene=$scene step=$step", expected, presentation.mergedUnknownIndices(source, unknown, now))
                assertEquals("cached scene=$scene step=$step", expected, presentation.mergedUnknownIndices(source, unknown.toList(), now + 1L))
            }
        }
    }

    private fun gridBox(index: Int) = RectNorm(.05f + index % 3 * .3f, .05f + index / 3 * .3f, .12f, .16f)

    /** Kept as the previous uncached algorithm to compare behavior, not a mirror of cache mechanics. */
    private fun baselineMerge(
        frames: List<Pair<CameraObjectPresentation.Capture, List<CameraObjectPresentation.Region>>>,
        unknown: List<CameraObjectPresentation.Region>,
    ): Map<Int, Int> {
        fun overlaps(left: RectNorm, right: RectNorm): Boolean {
            val width = max(0f, min(left.x + left.width, right.x + right.width) - max(left.x, right.x))
            val height = max(0f, min(left.y + left.height, right.y + right.height) - max(left.y, right.y))
            val intersection = width * height
            return intersection / (left.area + right.area - intersection) >= .70f
        }
        fun matches(left: List<CameraObjectPresentation.Region>, right: List<CameraObjectPresentation.Region>): Map<Int, Int> {
            val candidates = left.map { row -> right.indices.filter { overlaps(row.bboxNorm, right[it].bboxNorm) } }
            return candidates.mapIndexedNotNull { index, rows ->
                rows.singleOrNull()?.takeIf { other -> candidates.count { other in it } == 1 }?.let { index to it }
            }.toMap()
        }
        fun sameMetric(left: CameraObjectPresentation.Region, right: CameraObjectPresentation.Region): Boolean {
            val a = left.metricDistanceM ?: return false
            val b = right.metricDistanceM ?: return false
            return abs(a - b) <= .30
        }
        val sourceRows = frames.first().second
        val following = frames.drop(1)
        val edges = frames.zipWithNext { previous, next -> matches(previous.second, next.second) }
        return matches(unknown, sourceRows).mapNotNull { (unknownIndex, primaryIndex) ->
            val mask = unknown[unknownIndex]
            if (!sameMetric(mask, sourceRows[primaryIndex])) return@mapNotNull null
            var index = primaryIndex
            var previous = sourceRows
            for ((step, frame) in following.withIndex()) {
                val rows = frame.second
                val id = previous[index].trackId?.takeIf { it.isNotBlank() }
                val nextIndex = id?.let { track -> rows.indices.singleOrNull { rows[it].trackId == track } }
                if (id == null || nextIndex == null || previous.count { it.trackId == id } != 1 ||
                    edges[step][index] != nextIndex || !sameMetric(previous[index], rows[nextIndex]) ||
                    !sameMetric(mask, rows[nextIndex])) return@mapNotNull null
                index = nextIndex
                previous = rows
            }
            unknownIndex to index
        }.toMap()
    }
}
