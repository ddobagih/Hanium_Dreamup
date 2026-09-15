package kr.co.hanium.dreamup.walksafe.inference.tracking

import java.nio.ByteBuffer
import java.util.Random
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ground truth comes from rendered image transforms, never the production matching algorithm. */
class InterFrameDetectionTrackerTest {
    @Test
    fun realPhotographTranslationMovesBboxAndPolygonTogetherAndPreservesDetectorEvidence() {
        val candidate = photographDetection()
        val result = trackPair(camera(), translate(camera(), 4.0, -3.0), candidate)
        val observation = tracked(result)
        assertTranslated(candidate, checkNotNull(observation.geometry), 4.0, -3.0)
        assertEquals(key(0), observation.detectorSourceKey)
        assertEquals(key(1), observation.trackedTargetKey)
        assertEquals(0, observation.sourceIndex)
        assertTrue(observation.trackingQuality > 0f)
        assertTrue(observation.survivingFeatureCount > 0)
    }

    @Test
    fun actualPhotographSurvivesTranslationWithMildExposureAndFixedSeedNoise() {
        val changed = photometric(translate(camera(), -3.0, 4.0), gain = 0.95, offset = 11, noise = 2)
        val result = trackPair(camera(), changed, photographDetection())
        assertTranslated(photographDetection(), checkNotNull(tracked(result).geometry), -3.0, 4.0, 0.75)
    }

    @Test
    fun fixedSeedTextureTracksEverySignedDirectionWithinTheSearchRange() {
        val source = texture(0x571aL)
        for ((dx, dy) in listOf(4 to 0, -4 to 0, 0 to 4, 0 to -4, 3 to -3)) {
            val candidate = detection(50, 50, 88, 88)
            val result = trackPair(source, translate(source, dx.toDouble(), dy.toDouble()), candidate)
            assertTranslated(candidate, checkNotNull(tracked(result).geometry), dx.toDouble(), dy.toDouble())
        }
    }

    @Test
    fun signedCameraShakeUsesImageDisplacementWithoutRefreshingTheDetectorSource() {
        val tracker = tracker()
        val candidate = photographDetection()
        val source = seed(tracker, camera(), candidate)
        val positions = listOf(3 to -2, -2 to 1, 2 to 4, 0 to 0)
        positions.forEachIndexed { index, (dx, dy) ->
            val target = offer(tracker, translate(camera(), dx.toDouble(), dy.toDouble()), index + 1)
            val observation = tracked(tracker.trackFrom(source, listOf(candidate), target))
            assertTranslated(candidate, checkNotNull(observation.geometry), dx.toDouble(), dy.toDouble(), 0.85)
            assertEquals(source, observation.detectorSourceKey)
        }
    }

    @Test
    fun quarterPixelPhotoMotionAccumulatesInsteadOfRoundingEveryFrameToZero() {
        assertFractionalPhotographSequence(step = 0.25, count = 8)
    }

    @Test
    fun halfPixelPhotoMotionAccumulatesInsteadOfDriftingFromTheOriginalTemplate() {
        assertFractionalPhotographSequence(step = 0.5, count = 8)
    }

    @Test
    fun motionOutsideSearchRangeIsLostAlthoughTheWholeObjectRemainsOnScreen() {
        val source = texture(0x18e0L)
        assertLost(trackPair(source, translate(source, 24.0, 0.0), detection(52, 52, 72, 72)))
    }

    @Test
    fun strongRotationOfAsymmetricPixelsIsNotPublishedAsTranslation() {
        val source = texture(0x3e91L)
        assertLost(trackPair(source, affine(source, angleDegrees = 30.0), detection(48, 48, 88, 88)))
    }

    @Test
    fun strongScaleOfAsymmetricPixelsIsNotPublishedAsTranslation() {
        val source = texture(0x3e91L)
        assertLost(trackPair(source, affine(source, scale = 1.35), detection(48, 48, 88, 88)))
    }

    @Test
    fun repeatedStripesHaveNoUniqueTwoDimensionalCorrespondence() {
        val stripes = image { x, _ -> if (x % 6 < 3) 65 else 195 }
        assertLost(trackPair(stripes, translate(stripes, 2.0, 1.0), detection(50, 50, 88, 88)))
    }

    @Test
    fun repeatedTwoDimensionalTilesDoNotChooseAnArbitraryEqualMatch() {
        val tile = texture(0x6312L)
        val repeated = image { x, y -> intensity(tile, x % 6, y % 6) }
        assertLost(trackPair(repeated, translate(repeated, 2.0, 2.0), detection(50, 50, 88, 88)))
    }

    @Test
    fun flatAndWeakGradientPixelsCannotSupportAVisualTrack() {
        for (source in listOf(image { _, _ -> 128 }, image { x, y -> 120 + (x + y) / 96 })) {
            assertLost(trackPair(source, translate(source, 4.0, 3.0), detection(50, 50, 88, 88)))
        }
    }

    @Test
    fun completeOcclusionDropsTheObjectAndUncoveringWithoutNewDetectionDoesNotReviveIt() {
        val tracker = tracker()
        val candidate = photographDetection()
        val source = seed(tracker, camera(), candidate)
        val occluded = offer(tracker, image { _, _ -> 128 }, 1)
        assertLost(tracker.trackFrom(source, listOf(candidate), occluded))
        val uncovered = offer(tracker, translate(camera(), 2.0, 0.0), 2)
        assertLost(tracker.trackFrom(source, listOf(candidate), uncovered))
        val fresh = tracker.trackFrom(uncovered, listOf(candidate), uncovered)
        assertEquals(VisualTrackingStatus.TRACKED, fresh.observations.single().status)
    }

    @Test
    fun independentlyMovingSameClassObjectsNeverSwitchSourceIndexesDuringCrossing() {
        val tracker = tracker()
        val candidates = listOf(detection(42, 70, 32, 32), detection(112, 70, 32, 32))
        val source = offer(tracker, objects(42, 112), 0)
        tracker.trackFrom(source, candidates, source)
        val lostIndexes = mutableSetOf<Int>()
        for (step in 1..14) {
            val target = offer(tracker, objects(42 + step * 4, 112 - step * 4), step)
            val result = tracker.trackFrom(source, candidates, target)
            for (index in candidates.indices) {
                val observation = result.observations.single { it.sourceIndex == index }
                if (step <= 4) tracked(result, index)
                if (index in lostIndexes) assertEquals(VisualTrackingStatus.LOST, observation.status)
                if (observation.status == VisualTrackingStatus.TRACKED) {
                    val dx = (if (index == 0) 4 else -4) * step
                    assertTranslated(candidates[index], checkNotNull(observation.geometry), dx.toDouble(), 0.0, 0.85)
                } else {
                    assertNull(observation.geometry)
                    lostIndexes.add(index)
                }
            }
        }
    }

    @Test
    fun identicalOverlappingObjectsCannotBothClaimOneVisiblePatch() {
        val tracker = tracker()
        val candidates = listOf(detection(50, 70, 32, 32), detection(90, 70, 32, 32))
        val source = offer(tracker, objects(50, 90, samePattern = true), 0)
        tracker.trackFrom(source, candidates, source)
        val lostIndexes = mutableSetOf<Int>()
        for (step in 1..6) {
            val target = offer(tracker, objects(50 + step * 4, 90 - step * 4, samePattern = true), step)
            val result = tracker.trackFrom(source, candidates, target)
            assertEquals(setOf(0, 1), result.observations.map { it.sourceIndex }.toSet())
            assertEquals(2, result.observations.size)
            if (step == 5) assertTrue("Both detector identities must not claim the only visible patch", result.observations.count {
                it.status == VisualTrackingStatus.TRACKED
            } < 2)
            for (index in candidates.indices) {
                val observation = result.observations.single { it.sourceIndex == index }
                if (step == 1) tracked(result, index)
                if (index in lostIndexes) assertEquals(VisualTrackingStatus.LOST, observation.status)
                if (observation.status == VisualTrackingStatus.TRACKED) {
                    val dx = (if (index == 0) 4 else -4) * step
                    assertTranslated(candidates[index], checkNotNull(observation.geometry), dx.toDouble(), 0.0, 0.85)
                } else {
                    assertNull(observation.geometry)
                    lostIndexes.add(index)
                }
            }
        }
    }

    @Test
    fun twoIdenticalCandidatePatchesInsideSearchRangeAreAmbiguous() {
        val source = image { x, y ->
            val value = textureTileValue(x % 8, y, 0x910aL)
            value
        }
        assertLost(trackPair(source, translate(source, 4.0, 0.0), detection(50, 50, 88, 88)))
    }

    @Test
    fun exactGapLimitWorksAndLargerGapDoesNotBridgeContinuity() {
        val candidate = photographDetection()
        val valid = tracker()
        val validSource = seed(valid, camera(), candidate)
        val validTarget = offer(valid, translate(camera(), 3.0, 0.0), 1, elapsed = 120L)
        assertTranslated(candidate, checkNotNull(tracked(valid.trackFrom(validSource, listOf(candidate), validTarget)).geometry), 3.0, 0.0)

        val invalid = tracker()
        val invalidSource = seed(invalid, camera(), candidate)
        val invalidTarget = offer(invalid, translate(camera(), 3.0, 0.0), 1, elapsed = 121L)
        assertLost(invalid.trackFrom(invalidSource, listOf(candidate), invalidTarget))
    }

    @Test
    fun epochAndGeometryChangesFenceOffOldDetectionHistory() {
        for (newKey in listOf(key(1).copy(epoch = 2L), key(1).copy(geometryVersion = 2L))) {
            val tracker = tracker()
            val source = seed(tracker, camera(), photographDetection())
            val admission = tracker.offerFrame(frame(camera(), newKey))
            assertTrue(admission.accepted)
            assertTrue(admission.reset)
            assertLost(tracker.trackFrom(source, listOf(photographDetection()), newKey))
        }
    }

    @Test
    fun explicitClearPreventsLateDetectionFromReusingOldHistory() {
        val tracker = tracker()
        val source = seed(tracker, camera(), photographDetection())
        tracker.clear()
        val target = offer(tracker, camera(), 1)
        assertLost(tracker.trackFrom(source, listOf(photographDetection()), target))
    }

    @Test
    fun sourcePixelGeometryChangeCannotKeepAnOldMapping() {
        val tracker = tracker()
        val source = seed(tracker, camera(), photographDetection())
        val target = key(1)
        val changedDimensions = GrayTrackingFrame.copyOf(target, EDGE, EDGE, camera(), 1280, 720)
        val admission = tracker.offerFrame(changedDimensions)
        assertFalse(admission.accepted)
        assertTrue(admission.reset)
        assertEquals(VisualTrackingFailure.FRAME_IDENTITY_CONFLICT, admission.failure)
        assertLost(tracker.trackFrom(source, listOf(photographDetection()), target))
        for (clearBeforeRetry in listOf(false, true)) {
            if (clearBeforeRetry) tracker.clear()
            val retried = tracker.offerFrame(changedDimensions)
            assertFalse("Clearing history must not forget the accepted dimensions", retried.accepted)
            assertEquals(VisualTrackingFailure.FRAME_IDENTITY_CONFLICT, retried.failure)
        }
        val newerFrameWithoutGeometryChange = GrayTrackingFrame.copyOf(key(2), EDGE, EDGE, camera(), 1280, 720)
        val newerConflict = tracker.offerFrame(newerFrameWithoutGeometryChange)
        assertFalse("A newer frame identity must still honor the accepted dimensions", newerConflict.accepted)
        assertEquals(VisualTrackingFailure.FRAME_IDENTITY_CONFLICT, newerConflict.failure)
        val newGeometry = key(3).copy(geometryVersion = 2L)
        val newAdmission = tracker.offerFrame(GrayTrackingFrame.copyOf(newGeometry, EDGE, EDGE, camera(), 1280, 720))
        assertTrue("New geometry version may establish the changed dimensions", newAdmission.accepted)
        tracked(tracker.trackFrom(newGeometry, listOf(photographDetection()), newGeometry))
    }

    @Test
    fun completedEmptyDetectionClearsAndOlderPartialCannotResurrectIt() {
        val tracker = tracker()
        val candidate = photographDetection()
        val source = offer(tracker, camera(), 0)
        tracker.trackFrom(source, listOf(candidate), source, detectionRevision = 0L, completed = false)
        val target = offer(tracker, translate(camera(), 3.0, 0.0), 1)
        assertTrue(tracker.trackFrom(source, emptyList(), target, detectionRevision = 1L, completed = true).observations.isEmpty())
        val old = tracker.trackFrom(source, listOf(candidate), target, detectionRevision = 0L, completed = false)
        assertTrue(old.observations.none { it.status == VisualTrackingStatus.TRACKED })
        val next = offer(tracker, translate(camera(), 4.0, 0.0), 2)
        assertTrue(tracker.trackFrom(source, emptyList(), next, detectionRevision = 1L, completed = true).observations.isEmpty())
    }

    @Test
    fun refinedCompleteRevisionStartsAtOriginalSourceCoordinatesWithoutDoubleTranslation() {
        val tracker = tracker()
        val original = photographDetection()
        val source = offer(tracker, camera(), 0)
        tracker.trackFrom(source, listOf(original), source, detectionRevision = 0L, completed = false)
        val target = offer(tracker, translate(camera(), 4.0, 2.0), 1)
        tracked(tracker.trackFrom(source, listOf(original), target, detectionRevision = 0L, completed = false))
        val refined = detection(56, 50, 70, 70)
        val complete = tracker.trackFrom(source, listOf(refined), target, detectionRevision = 1L, completed = true)
        assertTranslated(refined, checkNotNull(tracked(complete).geometry), 4.0, 2.0, 0.85)
    }

    @Test
    fun newerCompletedEmptySourcePreventsLateOlderDetectionsFromReappearing() {
        val tracker = tracker()
        val candidate = photographDetection()
        val source = seed(tracker, camera(), candidate)
        val emptySource = offer(tracker, translate(camera(), 3.0, 0.0), 1)
        assertTrue(tracker.trackFrom(emptySource, emptyList(), emptySource).observations.isEmpty())
        val target = offer(tracker, translate(camera(), 4.0, 0.0), 2)
        val older = tracker.trackFrom(source, listOf(candidate), target)
        assertTrue(older.observations.none { it.status == VisualTrackingStatus.TRACKED })
    }

    @Test
    fun changedGeometryOrConfidenceUnderTheSameDetectorRevisionIsRejected() {
        val candidate = photographDetection()
        for (changed in listOf(candidate.copy(detectionConfidence = 0.42f), detection(56, 50, 70, 70))) {
            val tracker = tracker()
            val source = seed(tracker, camera(), candidate)
            val target = offer(tracker, camera(), 1)
            val result = tracker.trackFrom(source, listOf(changed), target)
            assertLost(result)
            assertEquals(VisualTrackingFailure.DETECTION_IDENTITY_CONFLICT, result.observations.single().failure)
        }
    }

    @Test
    fun lateDetectorBackfillMatchesTheSameImagesProcessedIncrementally() {
        val incremental = tracker()
        val late = tracker()
        val candidate = photographDetection()
        val source = seed(incremental, camera(), candidate)
        offer(late, camera(), 0)
        var incrementalResult: VisualTrackingResult? = null
        var target = source
        for (step in 1..5) {
            val pixels = translate(camera(), step * 0.5, -step * 0.25)
            target = offer(incremental, pixels, step)
            offer(late, pixels, step)
            incrementalResult = incremental.trackFrom(source, listOf(candidate), target)
        }
        val lateResult = late.trackFrom(source, listOf(candidate), target)
        val actual = checkNotNull(tracked(lateResult).geometry)
        val expected = checkNotNull(tracked(checkNotNull(incrementalResult)).geometry)
        assertEquals(expected, actual)
        assertTranslated(candidate, actual, 2.5, -1.25, 0.65)
    }

    @Test
    fun repeatedPublicationReturnsCachedGeometryWithoutReplayingImageMatches() {
        val tracker = tracker()
        val candidate = photographDetection()
        val source = seed(tracker, camera(), candidate)
        val target = offer(tracker, translate(camera(), 4.0, 1.0), 1)
        val first = tracker.trackFrom(source, listOf(candidate), target)
        tracked(first)
        val repeated = tracker.trackFrom(source, listOf(candidate), target)
        assertEquals(first.observations, repeated.observations)
        assertTrue(repeated.metrics.cacheHit)
        assertEquals(0, repeated.metrics.processedEdges)
        assertEquals(0L, repeated.metrics.pixelComparisons)
    }

    @Test
    fun sourceAgeOfExactly800MillisecondsWorksButPredictionCannotExtendIt() {
        val tracker = tracker()
        val candidate = photographDetection()
        val source = seed(tracker, camera(), candidate)
        for (step in 1..8) {
            val target = offer(tracker, camera(), step, elapsed = step * 100L)
            val observation = tracked(tracker.trackFrom(source, listOf(candidate), target))
            assertEquals(source, observation.detectorSourceKey)
        }
        val expired = offer(tracker, camera(), 9, elapsed = 801L)
        val result = tracker.trackFrom(source, listOf(candidate), expired)
        assertLost(result)
        assertEquals(VisualTrackingFailure.SOURCE_EXPIRED, result.observations.single().failure)
    }

    @Test
    fun evictedSourceCannotBeBackfilledEvenWhenYoungerThan800Milliseconds() {
        val tracker = tracker(VisualTrackingConfig(maxHistoryFrames = 3))
        val source = offer(tracker, camera(), 0)
        var target = source
        for (step in 1..3) target = offer(tracker, camera(), step)
        val result = tracker.trackFrom(source, listOf(photographDetection()), target)
        assertLost(result)
        assertEquals(VisualTrackingFailure.SOURCE_NOT_IN_HISTORY, result.observations.single().failure)
        assertTrue(result.metrics.retainedFrames <= 3)
    }

    @Test
    fun exhaustedPixelWorkBudgetCannotPublishThePreviousPositionAsCurrent() {
        val tracker = tracker(VisualTrackingConfig(maxPixelComparisonsPerCall = 1L))
        val candidate = photographDetection()
        val source = seed(tracker, camera(), candidate)
        val target = offer(tracker, translate(camera(), 4.0, 0.0), 1)
        val result = tracker.trackFrom(source, listOf(candidate), target)
        assertLost(result)
        assertEquals(VisualTrackingFailure.WORK_BUDGET_EXCEEDED, result.observations.single().failure)
        assertTrue(result.metrics.pixelComparisons <= 1L)
    }

    @Test
    fun exhaustedExecutionTimeBudgetDropsTheUnfinishedPublication() {
        var now = 0L
        val tracker = InterFrameDetectionTracker(
            config = VisualTrackingConfig(
                maxExecutionNs = 1L,
                maxFeaturesPerObject = 8,
                backend = VisualTrackingBackend.PATCH_DIAGNOSTIC,
            ),
            clockNanos = { now += 1_000L; now },
        )
        val candidate = photographDetection()
        val source = seed(tracker, camera(), candidate)
        val target = offer(tracker, translate(camera(), 4.0, 0.0), 1)
        val result = tracker.trackFrom(source, listOf(candidate), target)
        assertLost(result)
        assertEquals(VisualTrackingFailure.TIME_BUDGET_EXCEEDED, result.observations.single().failure)
    }

    @Test
    fun transientTimeBudgetFailureCanRetryFromLastCompletedEdgeWithoutDoubleTranslation() {
        var clockTick = 0L
        var exhaustBudget = false
        val tracker = InterFrameDetectionTracker(
            config = VisualTrackingConfig(
                maxExecutionNs = 1_000L,
                maxFeaturesPerObject = 8,
                backend = VisualTrackingBackend.PATCH_DIAGNOSTIC,
            ),
            clockNanos = { if (exhaustBudget) clockTick += 10_000L; clockTick },
        )
        val candidate = photographDetection()
        val source = seed(tracker, camera(), candidate)
        val first = offer(tracker, translate(camera(), 2.0, 0.0), 1)
        tracked(tracker.trackFrom(source, listOf(candidate), first))
        val second = offer(tracker, translate(camera(), 4.0, 1.0), 2)
        exhaustBudget = true
        assertLost(tracker.trackFrom(source, listOf(candidate), second))
        exhaustBudget = false
        val resumed = tracker.trackFrom(source, listOf(candidate), second)
        assertTranslated(candidate, checkNotNull(tracked(resumed).geometry), 4.0, 1.0)
    }

    @Test
    fun objectBudgetLeavesUnadmittedDetectorIndexesExplicitlyLost() {
        val tracker = tracker(VisualTrackingConfig(maxTrackedObjects = 1))
        val source = offer(tracker, camera(), 0)
        val candidates = listOf(photographDetection(), detection(108, 102, 56, 56))
        val target = offer(tracker, translate(camera(), 3.0, 0.0), 1)
        val result = tracker.trackFrom(source, candidates, target, prioritySourceIndices = listOf(0))
        tracked(result, 0)
        val rejected = result.observations.single { it.sourceIndex == 1 }
        assertEquals(VisualTrackingStatus.LOST, rejected.status)
        assertNull(rejected.geometry)
        assertEquals(VisualTrackingFailure.OBJECT_BUDGET_EXCEEDED, rejected.failure)
    }

    @Test
    fun copiedFrameIsNotChangedWhenTheProducerReusesItsByteArray() {
        val pixels = camera().copyOf()
        val owned = frame(pixels, key(0))
        pixels.fill(0)
        val tracker = tracker()
        assertTrue(tracker.offerFrame(owned).accepted)
        val target = offer(tracker, translate(camera(), 3.0, 0.0), 1)
        val candidate = photographDetection()
        assertTranslated(candidate, checkNotNull(tracked(tracker.trackFrom(key(0), listOf(candidate), target)).geometry), 3.0, 0.0)
    }

    @Test
    fun conflictingFrameIdentityCannotReplacePreviouslyAcceptedPixels() {
        val tracker = tracker()
        val source = seed(tracker, camera(), photographDetection())
        val conflict = tracker.offerFrame(frame(image { _, _ -> 0 }, source))
        assertFalse(conflict.accepted)
        assertNotNull(conflict.failure)
    }

    @Test
    fun oversizedDetectionBatchIsRejectedBeforeReadingOrCopyingAnyCandidate() {
        val tracker = tracker()
        val source = offer(tracker, camera(), 0)
        val oversized = object : AbstractList<DetectionCandidate>() {
            override val size: Int = Int.MAX_VALUE
            override fun get(index: Int): DetectionCandidate = throw AssertionError("Oversized input must not be traversed")
        }

        val result = tracker.trackFrom(source, oversized, source)

        assertEquals(VisualTrackingFailure.INPUT_BUDGET_EXCEEDED, result.batchFailure)
        assertEquals(Int.MAX_VALUE, result.rejectedInputCount)
        assertTrue(result.observations.isEmpty())
        assertEquals(0L, result.metrics.pixelComparisons)
    }

    @Test
    fun oversizedPolygonAndClassNameAreRejectedBeforeCopyingPolygonContents() {
        val oversizedPolygon = object : AbstractList<Point2>() {
            override val size: Int = 129
            override fun get(index: Int): Point2 = throw AssertionError("Oversized polygon must not be copied")
        }
        val unreadableValidLengthPolygon = object : AbstractList<Point2>() {
            override val size: Int = 4
            override fun get(index: Int): Point2 = throw AssertionError("Overlong class name must be rejected before polygon copy")
        }
        val original = photographDetection()
        for (candidate in listOf(
            original.copy(polygonNorm = oversizedPolygon),
            original.copy(className = "p".repeat(129), polygonNorm = unreadableValidLengthPolygon),
        )) {
            val tracker = tracker()
            val source = offer(tracker, camera(), 0)
            val result = tracker.trackFrom(source, listOf(candidate), source)
            assertLost(result)
            assertEquals(VisualTrackingFailure.INVALID_GEOMETRY, result.observations.single().failure)
            assertEquals(0L, result.metrics.pixelComparisons)
        }
    }

    @Test
    fun sameFrameKeyCannotBeReusedWithDifferentGrayOrSourceDimensions() {
        val source = key(0)
        val conflicts = listOf(
            GrayTrackingFrame.copyOf(source, EDGE / 2, EDGE, ByteArray(EDGE * EDGE / 2)),
            GrayTrackingFrame.copyOf(source, EDGE, EDGE, camera(), sourceWidth = 640, sourceHeight = 480),
        )
        for (conflictingFrame in conflicts) {
            val tracker = tracker()
            seed(tracker, camera(), photographDetection())
            val admission = tracker.offerFrame(conflictingFrame)
            assertFalse(admission.accepted)
            assertTrue(admission.reset)
            assertEquals(VisualTrackingFailure.FRAME_IDENTITY_CONFLICT, admission.failure)
            assertLost(tracker.trackFrom(source, listOf(photographDetection()), source))
        }
    }

    @Test
    fun lateOlderEpochOrGeometryCannotClearTheCurrentGeneration() {
        val candidate = photographDetection()
        val current = key(0).copy(epoch = 2L, geometryVersion = 2L)
        val staleKeys = listOf(
            key(1).copy(epoch = 1L, geometryVersion = 99L),
            key(1).copy(epoch = 2L, geometryVersion = 1L),
        )
        for (stale in staleKeys) {
            val tracker = tracker()
            assertTrue(tracker.offerFrame(frame(camera(), current)).accepted)
            tracked(tracker.trackFrom(current, listOf(candidate), current))

            val rejected = tracker.offerFrame(frame(camera(), stale))
            assertFalse(rejected.accepted)
            assertFalse(rejected.reset)
            assertEquals(VisualTrackingFailure.EPOCH_OR_GEOMETRY_CHANGED, rejected.failure)

            val target = key(2).copy(epoch = 2L, geometryVersion = 2L)
            assertTrue(tracker.offerFrame(frame(translate(camera(), 3.0, 0.0), target)).accepted)
            assertTranslated(candidate, checkNotNull(tracked(tracker.trackFrom(current, listOf(candidate), target)).geometry), 3.0, 0.0)
        }
    }

    @Test
    fun cachedGeometryExpiresByActualObservationTimeWhileCameraTargetIsStopped() {
        val tracker = tracker()
        val candidate = photographDetection()
        val source = seed(tracker, camera(), candidate)
        val target = offer(tracker, translate(camera(), 3.0, 0.0), 1)
        tracked(tracker.trackFrom(source, listOf(candidate), target))

        val atLimit = tracker.trackFrom(
            source, listOf(candidate), target,
            observedAtElapsedRealtimeMs = source.capturedAtElapsedRealtimeMs + 800L,
        )
        assertTranslated(candidate, checkNotNull(tracked(atLimit).geometry), 3.0, 0.0)
        val expired = tracker.trackFrom(
            source, listOf(candidate), target,
            observedAtElapsedRealtimeMs = source.capturedAtElapsedRealtimeMs + 801L,
        )
        assertLost(expired)
        assertEquals(VisualTrackingFailure.SOURCE_EXPIRED, expired.observations.single().failure)
        assertEquals(0L, expired.metrics.pixelComparisons)
    }

    @Test
    fun unfinishedObjectBudgetHidesTheWholePublicationAndCompletedWorkCanResume() {
        val before = objects(42, 112, samePattern = true)
        val after = objects(46, 108, samePattern = true)
        val candidates = listOf(detection(42, 70, 32, 32), detection(112, 70, 32, 32))
        // Calibrate only the resource boundary, never the motion oracle: the cap fits
        // either object's measured work individually, but cannot fit both together.
        val singleCosts = candidates.map { candidate ->
            val probe = trackPair(before, after, candidate)
            tracked(probe)
            probe.metrics.pixelComparisons
        }
        val budget = singleCosts.maxOrNull()!! + 1L
        assertTrue(singleCosts.all { it > 1L })
        assertTrue(budget < singleCosts.sum())
        val tracker = tracker(VisualTrackingConfig(maxPixelComparisonsPerCall = budget))
        val source = offer(tracker, before, 0)
        tracker.trackFrom(source, candidates, source)
        val target = offer(tracker, after, 1)

        val unfinished = tracker.trackFrom(source, candidates, target)
        assertEquals(2, unfinished.observations.size)
        assertLost(unfinished)
        assertTrue(unfinished.observations.any { it.failure == VisualTrackingFailure.WORK_BUDGET_EXCEEDED })
        assertTrue(unfinished.metrics.pixelComparisons <= budget)

        val resumed = tracker.trackFrom(source, candidates, target)
        assertTranslated(candidates[0], checkNotNull(tracked(resumed, 0).geometry), 4.0, 0.0)
        assertTranslated(candidates[1], checkNotNull(tracked(resumed, 1).geometry), -4.0, 0.0)
        assertTrue(resumed.metrics.pixelComparisons <= budget)
    }

    @Test
    fun newFrameCopiesAndByteAccountingDoNotAllocateDiagnosticIntegralStorage() {
        val pixels = camera().copyOf()
        val copied = GrayTrackingFrame.copyOf(key(0), EDGE, EDGE, pixels)
        val copiedFromLuma = GrayTrackingFrame.copyFromLuma(
            key(0), EDGE, EDGE, ByteBuffer.wrap(pixels), rowStride = EDGE, pixelStride = 1,
        )
        for (frame in listOf(copied, copiedFromLuma)) {
            repeat(3) {
                assertEquals("Creating a frame or reading its byte count must retain only luma", EDGE * EDGE, frame.retainedBytes)
            }
        }
    }

    @Test
    fun nativeFrameOnlyHistoryRetainsThirtyTwoLumaPayloadsWithoutDiagnosticIntegrals() {
        val width = 192
        val height = 144
        val pixels = camera().copyOf(width * height)
        val tracker = InterFrameDetectionTracker(
            config = VisualTrackingConfig(backend = VisualTrackingBackend.OPENCV_PYRAMIDAL_LK),
            clockNanos = { 0L },
        )
        val frames = List(32) { index ->
            GrayTrackingFrame.copyOf(key(index, elapsed = index * 25L), width, height, pixels)
        }
        frames.forEach { assertTrue(tracker.offerFrame(it).accepted) }
        val latest = frames.last().key
        // Empty detector publication reads the history metric without asking native LK
        // or the diagnostic backend to select features or process an image edge.
        val result = tracker.trackFrom(latest, emptyList(), latest)

        assertTrue(result.observations.isEmpty())
        assertEquals(32, result.metrics.retainedFrames)
        assertEquals(32 * width * height, result.metrics.retainedImageBytes)
        frames.forEach { assertEquals(width * height, it.retainedBytes) }
    }

    @Test
    fun diagnosticEdgeAddsOnlyItsLazyIntegralPayloadAndStillOwnsTheLumaCopy() {
        val producerPixels = camera().copyOf()
        val source = GrayTrackingFrame.copyFromLuma(
            key(0), EDGE, EDGE, ByteBuffer.wrap(producerPixels), rowStride = EDGE, pixelStride = 1,
        )
        val grayBytes = EDGE * EDGE
        val integralBytes = (EDGE + 1) * (EDGE + 1) * Int.SIZE_BYTES
        assertEquals(grayBytes, source.retainedBytes)
        producerPixels.fill(0)
        val tracker = tracker()
        val candidate = photographDetection()
        assertTrue(tracker.offerFrame(source).accepted)
        val original = tracker.trackFrom(source.key, listOf(candidate), source.key)
        tracked(original)
        assertEquals(source.retainedBytes, original.metrics.retainedImageBytes)
        val target = frame(translate(camera(), 3.0, 0.0), key(1))
        assertEquals(grayBytes, target.retainedBytes)
        assertTrue(tracker.offerFrame(target).accepted)

        val result = tracker.trackFrom(source.key, listOf(candidate), target.key)

        assertTranslated(candidate, checkNotNull(tracked(result).geometry), 3.0, 0.0)
        assertEquals(grayBytes + integralBytes, source.retainedBytes)
        assertEquals(grayBytes + integralBytes, target.retainedBytes)
        assertEquals(source.retainedBytes + target.retainedBytes, result.metrics.retainedImageBytes)
        val cached = tracker.trackFrom(source.key, listOf(candidate), target.key)
        assertEquals(result.observations, cached.observations)
        assertEquals(result.metrics.retainedImageBytes, cached.metrics.retainedImageBytes)
    }

    @Test
    fun finalImageEdgeCompletedAfterDeadlineCanPublishWithoutRepeatingItsWork() {
        val candidate = photographDetection()
        val probeClock = DeadlineClock()
        val probeTracker = tracker(clockNanos = probeClock::read)
        val source = seed(probeTracker, camera(), candidate)
        val target = offer(probeTracker, translate(camera(), 3.0, 0.0), 1)
        probeClock.reset()
        val probe = probeTracker.trackFrom(source, listOf(candidate), target)
        tracked(probe)
        assertFalse(probe.metrics.executionBudgetOverrun)
        // The normal probe records the public call's resource checks. Its last
        // budget fence precedes the final publication clock read by one call.
        val finalBudgetRead = probeClock.calls - 1
        assertTrue(finalBudgetRead > 1)
        val clock = DeadlineClock()
        val tracker = tracker(clockNanos = clock::read)
        seed(tracker, camera(), candidate)
        offer(tracker, translate(camera(), 3.0, 0.0), 1)
        clock.reset(expireOnRead = finalBudgetRead)

        val completed = tracker.trackFrom(source, listOf(candidate), target)

        assertTranslated(candidate, checkNotNull(tracked(completed).geometry), 3.0, 0.0)
        assertTrue(completed.metrics.executionBudgetOverrun)
        assertEquals(1, completed.metrics.processedEdges)
        assertTrue(completed.metrics.pixelComparisons > 0L)
        assertEquals(probe.metrics.pixelComparisons, completed.metrics.pixelComparisons)
        clock.reset()
        val cached = tracker.trackFrom(source, listOf(candidate), target)
        assertEquals(completed.observations, cached.observations)
        assertEquals(0L, cached.metrics.pixelComparisons)
    }

    @Test
    fun deadlineAfterOnlyTheFirstObjectCompletesWithholdsAllGeometryAndStopsMoreWork() {
        val before = objects(42, 112, samePattern = true)
        val after = objects(46, 108, samePattern = true)
        val candidates = listOf(detection(42, 70, 32, 32), detection(112, 70, 32, 32))
        val probeClock = DeadlineClock()
        val probeTracker = tracker(clockNanos = probeClock::read)
        val source = seed(probeTracker, before, candidates[0])
        val target = offer(probeTracker, after, 1)
        probeClock.reset()
        val probe = probeTracker.trackFrom(source, listOf(candidates[0]), target)
        tracked(probe)
        val firstObjectFinalBudgetRead = probeClock.calls - 1
        val clock = DeadlineClock()
        val tracker = tracker(clockNanos = clock::read)
        offer(tracker, before, 0)
        tracker.trackFrom(source, candidates, source)
        offer(tracker, after, 1)
        clock.reset(expireOnRead = firstObjectFinalBudgetRead)

        val unfinished = tracker.trackFrom(source, candidates, target)

        assertEquals(2, unfinished.observations.size)
        assertLost(unfinished)
        assertTrue(unfinished.observations.all { it.failure == VisualTrackingFailure.TIME_BUDGET_EXCEEDED })
        assertTrue(unfinished.metrics.executionBudgetOverrun)
        assertEquals(1, unfinished.metrics.processedEdges)
        assertEquals("No second estimator may start after the deadline", probe.metrics.pixelComparisons, unfinished.metrics.pixelComparisons)
        clock.reset()
        val resumed = tracker.trackFrom(source, candidates, target)
        assertEquals(1, resumed.metrics.processedEdges)
        assertTranslated(candidates[0], checkNotNull(tracked(resumed, 0).geometry), 4.0, 0.0)
        assertTranslated(candidates[1], checkNotNull(tracked(resumed, 1).geometry), -4.0, 0.0)
    }

    @Test
    fun finalPublicationClockIncludesCallDurationInThe800MillisecondSourceLimit() {
        val clock = DeadlineClock()
        val tracker = tracker(clockNanos = clock::read)
        val candidate = photographDetection()
        val source = seed(tracker, camera(), candidate)
        val target = offer(tracker, translate(camera(), 3.0, 0.0), 1)
        tracked(tracker.trackFrom(source, listOf(candidate), target))
        clock.reset(expireOnRead = 2, elapsedNs = 1_000_000L)
        val exactLimit = tracker.trackFrom(
            source, listOf(candidate), target,
            observedAtElapsedRealtimeMs = source.capturedAtElapsedRealtimeMs + 799L,
        )
        assertTranslated(candidate, checkNotNull(tracked(exactLimit).geometry), 3.0, 0.0)
        assertEquals(1_000_000L, exactLimit.metrics.durationNs)
        clock.reset(expireOnRead = 2, elapsedNs = 1_000_000L)

        val expiredDuringCall = tracker.trackFrom(
            source, listOf(candidate), target,
            observedAtElapsedRealtimeMs = source.capturedAtElapsedRealtimeMs + 800L,
        )

        assertLost(expiredDuringCall)
        assertEquals(VisualTrackingFailure.SOURCE_EXPIRED, expiredDuringCall.observations.single().failure)
        assertEquals(1_000_000L, expiredDuringCall.metrics.durationNs)
        assertEquals(0L, expiredDuringCall.metrics.pixelComparisons)
    }

    @Test
    fun optimizedLumaSamplingMatchesThePreviousFourSampleFormulaForStridedOddImages() {
        data class Layout(val width: Int, val height: Int, val pixelStride: Int, val padding: Int, val base: Int, val maxEdge: Int)
        val layouts = listOf(
            Layout(513, 377, 1, 11, 7, 192),
            Layout(641, 479, 2, 17, 13, 160),
            Layout(19, 13, 3, 5, 9, 16),
            Layout(17, 9, 2, 3, 5, 192),
            Layout(17, 193, 1, 7, 11, 64),
        )
        for ((index, layout) in layouts.withIndex()) {
            val rowStride = layout.width * layout.pixelStride + layout.padding
            val limit = layout.base + (layout.height - 1) * rowStride + (layout.width - 1) * layout.pixelStride + 1
            val random = Random(0x6a11L + index)
            val bytes = ByteArray(limit + 19).also { random.nextBytes(it) }
            val buffer = ByteBuffer.wrap(bytes).apply { position(layout.base); limit(limit) }
            val scale = minOf(1f, layout.maxEdge.toFloat() / maxOf(layout.width, layout.height))
            val width = maxOf(1, (layout.width * scale).roundToInt())
            val height = maxOf(1, (layout.height * scale).roundToInt())
            val expected = ByteArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                var sum = 0
                for (sampleY in 0..1) for (sampleX in 0..1) {
                    val sx = ((x + 0.25f + sampleX * 0.5f) * layout.width / width).toInt().coerceAtMost(layout.width - 1)
                    val sy = ((y + 0.25f + sampleY * 0.5f) * layout.height / height).toInt().coerceAtMost(layout.height - 1)
                    sum += bytes[layout.base + sy * rowStride + sx * layout.pixelStride].toInt() and 0xff
                }
                expected[y * width + x] = ((sum + 2) / 4).toByte()
            }
            val identity = key(index)
            val reference = GrayTrackingFrame.copyOf(identity, width, height, expected, layout.width, layout.height)
            val actual = GrayTrackingFrame.copyFromLuma(
                identity, layout.width, layout.height, buffer, rowStride, layout.pixelStride, layout.maxEdge,
            )
            assertEquals(layout.base, buffer.position())
            assertEquals(limit, buffer.limit())
            val tracker = tracker()
            assertTrue(tracker.offerFrame(reference).accepted)
            assertTrue("Duplicate identity requires every copied pixel and source dimension to match: $layout", tracker.offerFrame(actual).accepted)
            assertEquals(width * height, actual.retainedBytes)
        }
    }

    @Test
    fun omittedCallBudgetUsesTheConfiguredDefaultAndDoesNotRetainAPreviousOverride() {
        for (configuredNs in listOf(5_000_000L, 7_000_000L)) {
            val tracker = tracker(VisualTrackingConfig(maxExecutionNs = configuredNs))
            val candidate = photographDetection()
            val source = seed(tracker, camera(), candidate)
            val default = tracker.trackFrom(source, listOf(candidate), source)
            assertEquals(configuredNs, default.metrics.executionBudgetNs)
            val overridden = tracker.trackFrom(source, listOf(candidate), source, executionBudgetNs = 10_000_000L)
            assertEquals(10_000_000L, overridden.metrics.executionBudgetNs)

            val restored = tracker.trackFrom(source, listOf(candidate), source)

            assertEquals(configuredNs, restored.metrics.executionBudgetNs)
            assertEquals(default.observations, restored.observations)
        }
    }

    @Test
    fun tenMillisecondOverrideAllowsEightMillisecondsOfImageWorkWithoutAnOverrun() {
        val candidate = photographDetection()
        for (overrideNs in listOf(null, 10_000_000L)) {
            val clock = DeadlineClock()
            val tracker = tracker(clockNanos = clock::read)
            val source = seed(tracker, camera(), candidate)
            val target = offer(tracker, translate(camera(), 3.0, 0.0), 1)
            clock.reset(expireOnRead = 2, elapsedNs = 8_000_000L)

            val result = if (overrideNs == null) tracker.trackFrom(source, listOf(candidate), target)
                else tracker.trackFrom(source, listOf(candidate), target, executionBudgetNs = overrideNs)

            assertEquals(8_000_000L, result.metrics.durationNs)
            if (overrideNs == null) {
                assertLost(result)
                assertEquals(VisualTrackingFailure.TIME_BUDGET_EXCEEDED, result.observations.single().failure)
                assertEquals(5_000_000L, result.metrics.executionBudgetNs)
                assertTrue(result.metrics.executionBudgetOverrun)
                assertEquals(0L, result.metrics.pixelComparisons)
            } else {
                assertTranslated(candidate, checkNotNull(tracked(result).geometry), 3.0, 0.0)
                assertEquals(10_000_000L, result.metrics.executionBudgetNs)
                assertFalse(result.metrics.executionBudgetOverrun)
                assertTrue(result.metrics.pixelComparisons > 0L)
            }
        }
    }

    @Test
    fun tenMillisecondCallBudgetStopsUnfinishedWorkAtTenAndReportsOverrunOnlyAboveTen() {
        for (elapsedNs in listOf(10_000_000L, 11_000_000L)) {
            val clock = DeadlineClock()
            val tracker = tracker(clockNanos = clock::read)
            val candidate = photographDetection()
            val source = seed(tracker, camera(), candidate)
            val target = offer(tracker, translate(camera(), 3.0, 0.0), 1)
            clock.reset(expireOnRead = 2, elapsedNs = elapsedNs)

            val result = tracker.trackFrom(source, listOf(candidate), target, executionBudgetNs = 10_000_000L)

            assertLost(result)
            assertEquals(VisualTrackingFailure.TIME_BUDGET_EXCEEDED, result.observations.single().failure)
            assertEquals(10_000_000L, result.metrics.executionBudgetNs)
            assertEquals(elapsedNs == 11_000_000L, result.metrics.executionBudgetOverrun)
            assertEquals(0L, result.metrics.pixelComparisons)
            assertEquals(0, result.metrics.processedEdges)
        }
    }

    @Test
    fun completedImageEdgeUsesTheSelectedTenMillisecondBudgetForItsOverrunFlag() {
        val candidate = photographDetection()
        val probeClock = DeadlineClock()
        val probeTracker = tracker(clockNanos = probeClock::read)
        val source = seed(probeTracker, camera(), candidate)
        val target = offer(probeTracker, translate(camera(), 3.0, 0.0), 1)
        probeClock.reset()
        val probe = probeTracker.trackFrom(source, listOf(candidate), target, executionBudgetNs = 10_000_000L)
        tracked(probe)
        val finalBudgetRead = probeClock.calls - 1
        assertTrue(finalBudgetRead > 1)
        for (elapsedNs in listOf(9_000_000L, 11_000_000L)) {
            val clock = DeadlineClock()
            val tracker = tracker(clockNanos = clock::read)
            seed(tracker, camera(), candidate)
            offer(tracker, translate(camera(), 3.0, 0.0), 1)
            clock.reset(expireOnRead = finalBudgetRead, elapsedNs = elapsedNs)

            val result = tracker.trackFrom(source, listOf(candidate), target, executionBudgetNs = 10_000_000L)

            assertTranslated(candidate, checkNotNull(tracked(result).geometry), 3.0, 0.0)
            assertEquals(10_000_000L, result.metrics.executionBudgetNs)
            assertEquals(elapsedNs == 11_000_000L, result.metrics.executionBudgetOverrun)
            assertEquals(1, result.metrics.processedEdges)
            assertEquals(probe.metrics.pixelComparisons, result.metrics.pixelComparisons)
        }
    }

    @Test
    fun largerCallBudgetStillExpiresTheOriginalDetectionAtTheFinalEightHundredMillisecondLimit() {
        val clock = DeadlineClock()
        val tracker = tracker(clockNanos = clock::read)
        val candidate = photographDetection()
        val source = seed(tracker, camera(), candidate)
        val target = offer(tracker, translate(camera(), 3.0, 0.0), 1)
        tracked(tracker.trackFrom(source, listOf(candidate), target))
        clock.reset(expireOnRead = 2, elapsedNs = 9_000_000L)
        val exactLimit = tracker.trackFrom(
            source, listOf(candidate), target,
            observedAtElapsedRealtimeMs = source.capturedAtElapsedRealtimeMs + 791L,
            executionBudgetNs = 10_000_000L,
        )
        assertTranslated(candidate, checkNotNull(tracked(exactLimit).geometry), 3.0, 0.0)
        assertFalse(exactLimit.metrics.executionBudgetOverrun)
        clock.reset(expireOnRead = 2, elapsedNs = 9_000_000L)

        val expired = tracker.trackFrom(
            source, listOf(candidate), target,
            observedAtElapsedRealtimeMs = source.capturedAtElapsedRealtimeMs + 792L,
            executionBudgetNs = 10_000_000L,
        )

        assertLost(expired)
        assertEquals(VisualTrackingFailure.SOURCE_EXPIRED, expired.observations.single().failure)
        assertEquals(10_000_000L, expired.metrics.executionBudgetNs)
        assertEquals(9_000_000L, expired.metrics.durationNs)
        assertFalse(expired.metrics.executionBudgetOverrun)
        assertEquals(0L, expired.metrics.pixelComparisons)
    }

    @Test
    fun callBudgetRejectsValuesOutsideTheExistingDiagnosticRangeAndAcceptsBothEndpoints() {
        val tracker = tracker()
        val candidate = photographDetection()
        val source = seed(tracker, camera(), candidate)
        for (invalidNs in listOf(Long.MIN_VALUE, -1L, 0L, 100_000_001L, Long.MAX_VALUE)) {
            try {
                tracker.trackFrom(source, listOf(candidate), source, executionBudgetNs = invalidNs)
                throw AssertionError("Invalid call budget $invalidNs must be rejected")
            } catch (_: IllegalArgumentException) {
                // The public argument contract rejects the value, not the image track.
            }
        }
        for (validNs in listOf(1L, 100_000_000L)) {
            val result = tracker.trackFrom(source, listOf(candidate), source, executionBudgetNs = validNs)
            assertEquals(candidate, checkNotNull(tracked(result).geometry))
            assertEquals(validNs, result.metrics.executionBudgetNs)
        }
    }

    private fun assertFractionalPhotographSequence(step: Double, count: Int) {
        val tracker = tracker()
        val candidate = photographDetection()
        val source = seed(tracker, camera(), candidate)
        for (index in 1..count) {
            val target = offer(tracker, translate(camera(), index * step, -index * step), index)
            val result = tracker.trackFrom(source, listOf(candidate), target)
            assertTranslated(candidate, checkNotNull(tracked(result).geometry), index * step, -index * step, 0.65)
        }
    }

    private fun trackPair(sourcePixels: ByteArray, targetPixels: ByteArray, candidate: DetectionCandidate): VisualTrackingResult {
        val tracker = tracker()
        val source = seed(tracker, sourcePixels, candidate)
        val target = offer(tracker, targetPixels, 1)
        return tracker.trackFrom(source, listOf(candidate), target)
    }

    private fun tracker(config: VisualTrackingConfig = VisualTrackingConfig(), clockNanos: () -> Long = { 0L }) =
        InterFrameDetectionTracker(
            config.copy(maxFeaturesPerObject = 8, backend = VisualTrackingBackend.PATCH_DIAGNOSTIC),
            clockNanos = clockNanos,
        )

    private class DeadlineClock {
        var calls: Int = 0
            private set
        private var expireOnRead = Int.MAX_VALUE
        private var elapsedNs = 6_000_000L

        fun reset(expireOnRead: Int = Int.MAX_VALUE, elapsedNs: Long = 6_000_000L) {
            require(expireOnRead > 1)
            calls = 0
            this.expireOnRead = expireOnRead
            this.elapsedNs = elapsedNs
        }

        fun read(): Long {
            calls++
            return if (calls >= expireOnRead) elapsedNs else 0L
        }
    }

    private fun seed(tracker: InterFrameDetectionTracker, pixels: ByteArray, candidate: DetectionCandidate): VisualFrameKey {
        val source = offer(tracker, pixels, 0)
        assertEquals(candidate, checkNotNull(tracked(tracker.trackFrom(source, listOf(candidate), source)).geometry))
        return source
    }

    private fun offer(tracker: InterFrameDetectionTracker, pixels: ByteArray, index: Int, elapsed: Long = index * 50L): VisualFrameKey {
        val key = key(index, elapsed)
        assertTrue("Frame $index must be admitted", tracker.offerFrame(frame(pixels, key)).accepted)
        return key
    }

    private fun key(index: Int, elapsed: Long = index * 50L) = VisualFrameKey(
        epoch = 1L,
        frameId = index.toLong(),
        cameraTimestampNs = (10_000L + elapsed) * 1_000_000L,
        capturedAtElapsedRealtimeMs = 10_000L + elapsed,
        geometryVersion = 1L,
    )

    private fun frame(pixels: ByteArray, key: VisualFrameKey) = GrayTrackingFrame.copyOf(key, EDGE, EDGE, pixels)

    private fun tracked(result: VisualTrackingResult, sourceIndex: Int = 0): VisualTrackingObservation {
        val observation = result.observations.single { it.sourceIndex == sourceIndex }
        assertEquals("Expected tracked object $sourceIndex, failure=${observation.failure}", VisualTrackingStatus.TRACKED, observation.status)
        assertNotNull(observation.geometry)
        assertNull(observation.failure)
        return observation
    }

    private fun assertLost(result: VisualTrackingResult) {
        assertTrue("Loss must be represented for the detector input", result.observations.isNotEmpty())
        for (observation in result.observations) {
            assertEquals("Unexpected visual correspondence: ${observation.geometry}", VisualTrackingStatus.LOST, observation.status)
            assertNull(observation.geometry)
            assertNotNull(observation.failure)
        }
    }

    private fun assertTranslated(original: DetectionCandidate, actual: DetectionCandidate, dx: Double, dy: Double, tolerancePx: Double = 0.65) {
        val tolerance = tolerancePx / EDGE
        assertEquals(original.bboxNorm.x + dx / EDGE, actual.bboxNorm.x.toDouble(), tolerance)
        assertEquals(original.bboxNorm.y + dy / EDGE, actual.bboxNorm.y.toDouble(), tolerance)
        assertEquals(original.bboxNorm.width.toDouble(), actual.bboxNorm.width.toDouble(), 0.000001)
        assertEquals(original.bboxNorm.height.toDouble(), actual.bboxNorm.height.toDouble(), 0.000001)
        assertEquals(original.className, actual.className)
        assertEquals(original.detectionConfidence, actual.detectionConfidence, 0f)
        assertEquals(original.maskAreaNorm, actual.maskAreaNorm)
        assertEquals(original.polygonNorm.size, actual.polygonNorm.size)
        for (index in original.polygonNorm.indices) {
            assertEquals(original.polygonNorm[index].x + dx / EDGE, actual.polygonNorm[index].x.toDouble(), tolerance)
            assertEquals(original.polygonNorm[index].y + dy / EDGE, actual.polygonNorm[index].y.toDouble(), tolerance)
            assertEquals(
                (actual.bboxNorm.x - original.bboxNorm.x).toDouble(),
                (actual.polygonNorm[index].x - original.polygonNorm[index].x).toDouble(),
                0.000001,
            )
            assertEquals(
                (actual.bboxNorm.y - original.bboxNorm.y).toDouble(),
                (actual.polygonNorm[index].y - original.polygonNorm[index].y).toDouble(),
                0.000001,
            )
        }
    }

    private fun photographDetection() = detection(54, 48, 76, 76)

    private fun detection(x: Int, y: Int, width: Int, height: Int): DetectionCandidate {
        val left = x.toFloat() / EDGE
        val top = y.toFloat() / EDGE
        val right = (x + width).toFloat() / EDGE
        val bottom = (y + height).toFloat() / EDGE
        return DetectionCandidate(
            className = "person",
            detectionConfidence = 0.87f,
            bboxNorm = RectNorm(left, top, width.toFloat() / EDGE, height.toFloat() / EDGE),
            polygonNorm = listOf(Point2(left, top), Point2(right, top), Point2(right, bottom), Point2(left, bottom)),
            maskAreaNorm = width.toFloat() * height / (EDGE * EDGE),
        )
    }

    private fun camera(): ByteArray = photograph

    private fun image(pixel: (Int, Int) -> Int) = ByteArray(EDGE * EDGE) { index ->
        pixel(index % EDGE, index / EDGE).coerceIn(0, 255).toByte()
    }

    private fun texture(seed: Long): ByteArray {
        val random = Random(seed)
        return ByteArray(EDGE * EDGE) { (40 + random.nextInt(176)).toByte() }
    }

    private fun photometric(source: ByteArray, gain: Double, offset: Int, noise: Int): ByteArray {
        val random = Random(0x5eedL)
        return image { x, y ->
            (intensity(source, x, y) * gain).roundToInt() + offset + random.nextInt(noise * 2 + 1) - noise
        }
    }

    /** Inverse image warp with bilinear sampling; blank fill is outside every test's object ROI. */
    private fun translate(source: ByteArray, dx: Double, dy: Double) = image { x, y -> sample(source, x - dx, y - dy) }

    private fun affine(source: ByteArray, angleDegrees: Double = 0.0, scale: Double = 1.0): ByteArray {
        val angle = angleDegrees * Math.PI / 180.0
        val center = (EDGE - 1) / 2.0
        return image { x, y ->
            val px = (x - center) / scale
            val py = (y - center) / scale
            sample(source, center + cos(angle) * px + sin(angle) * py, center - sin(angle) * px + cos(angle) * py)
        }
    }

    private fun sample(source: ByteArray, x: Double, y: Double): Int {
        if (x < 0.0 || y < 0.0 || x > EDGE - 1.0 || y > EDGE - 1.0) return 128
        val left = floor(x).toInt()
        val top = floor(y).toInt()
        val right = (left + 1).coerceAtMost(EDGE - 1)
        val bottom = (top + 1).coerceAtMost(EDGE - 1)
        val fx = x - left
        val fy = y - top
        return (
            intensity(source, left, top) * (1 - fx) * (1 - fy) +
                intensity(source, right, top) * fx * (1 - fy) +
                intensity(source, left, bottom) * (1 - fx) * fy +
                intensity(source, right, bottom) * fx * fy
            ).roundToInt()
    }

    private fun intensity(source: ByteArray, x: Int, y: Int) = source[y * EDGE + x].toInt() and 0xff

    private fun objects(leftA: Int, leftB: Int, samePattern: Boolean = false): ByteArray {
        val result = image { _, _ -> 128 }
        for ((left, seed) in listOf(leftA to 0x9a21L, leftB to if (samePattern) 0x9a21L else 0x7bf3L)) {
            val patch = texture(seed)
            for (y in 0 until 32) for (x in 0 until 32) result[(70 + y) * EDGE + left + x] = patch[y * EDGE + x]
        }
        return result
    }

    private fun textureTileValue(x: Int, y: Int, seed: Long): Int = Random(seed + x * 79L + y * 10_007L).nextInt(176) + 40

    companion object {
        private const val EDGE = 192
        private val photograph: ByteArray = checkNotNull(InterFrameDetectionTrackerTest::class.java.getResourceAsStream("/tracking/camera-192x192.gray")) {
            "Missing CC0 photograph fixture; see work/heterogeneous-runtime/tracking-fixture-notes.md"
        }.use { it.readBytes() }.also { require(it.size == EDGE * EDGE) }
    }
}
