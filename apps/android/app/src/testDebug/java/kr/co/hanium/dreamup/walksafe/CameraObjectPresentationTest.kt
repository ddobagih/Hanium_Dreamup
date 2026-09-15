package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.junit.Assert.*
import org.junit.Test

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
}
