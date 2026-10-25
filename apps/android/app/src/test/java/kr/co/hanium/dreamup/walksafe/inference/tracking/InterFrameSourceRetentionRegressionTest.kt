package kr.co.hanium.dreamup.walksafe.inference.tracking

import java.util.Random
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S10-26: synthetic ingress isolates source retention from native availability and device FPS. */
class InterFrameSourceRetentionRegressionTest {
    private val pixels = Random(0x571aL).let { random ->
        ByteArray(EDGE * EDGE) { (40 + random.nextInt(176)).toByte() }
    }
    private val candidate = DetectionCandidate(
        className = "person",
        detectionConfidence = 0.87f,
        bboxNorm = RectNorm(50f / EDGE, 50f / EDGE, 88f / EDGE, 88f / EDGE),
        polygonNorm = listOf(
            Point2(50f / EDGE, 50f / EDGE), Point2(138f / EDGE, 50f / EDGE),
            Point2(138f / EDGE, 138f / EDGE), Point2(50f / EDGE, 138f / EDGE),
        ),
        maskAreaNorm = 88f * 88f / (EDGE * EDGE),
    )

    @Test
    fun activeOriginalAt612MsSurvives37AcceptedFramesWithinThe800MsLifetime() {
        val tracker = tracker()
        val source = offer(tracker, 0)
        assertTracked(tracker.trackFrom(source, listOf(candidate), source), source, source)

        var target = source
        for (index in 1..36) target = offer(tracker, index)
        assertEquals(612L, target.capturedAtElapsedRealtimeMs - source.capturedAtElapsedRealtimeMs)

        val result = tracker.trackFrom(source, listOf(candidate), target)
        assertTracked(result, source, target)
        assertTrue("Protection must keep the existing 32-image bound", result.metrics.retainedFrames <= 32)
    }

    @Test
    fun recentOriginalStillTracksOnDiagnosticBackendAfterTheSame37FrameIngress() {
        val tracker = tracker()
        var source = offer(tracker, 0)
        for (index in 1..35) source = offer(tracker, index)
        assertTracked(tracker.trackFrom(source, listOf(candidate), source), source, source)
        val target = offer(tracker, 36)

        val result = tracker.trackFrom(source, listOf(candidate), target)
        assertTracked(result, source, target)
        assertTrue("This control must execute image correspondence", result.metrics.processedEdges > 0)
        assertTrue(result.metrics.pixelComparisons > 0L)
    }

    @Test
    fun pendingOriginalTransfersToPublishedBatchAndItsLastKeySurvivesThe800MsWindow() {
        val tracker = tracker()
        val source = offer(tracker, 0)
        val lease = requireNotNull(tracker.reservePendingSourceFrame(source))
        assertTrue(tracker.publishPendingSourceFrame(lease))

        var target = source
        for (index in 1..18) target = offer(tracker, index)
        assertTracked(tracker.trackFrom(source, listOf(candidate), target), source, target)

        for (index in 19..36) target = offer(tracker, index)
        assertEquals(612L, target.capturedAtElapsedRealtimeMs - source.capturedAtElapsedRealtimeMs)
        val result = tracker.trackFrom(source, listOf(candidate), target)
        assertTracked(result, source, target)
        assertTrue("Pending and published ownership must stay within the 32-image bound",
            result.metrics.retainedFrames <= 32)
    }

    @Test
    fun canceledPendingOriginalCanBeReleasedBeforeHistoryEvictsIt() {
        val tracker = tracker()
        val source = offer(tracker, 0)
        val lease = requireNotNull(tracker.reservePendingSourceFrame(source))
        assertTrue(tracker.releasePendingSourceFrame(lease))
        assertTrue(!tracker.releasePendingSourceFrame(lease))

        var target = source
        for (index in 1..36) target = offer(tracker, index)
        val result = tracker.trackFrom(source, listOf(candidate), target)
        assertEquals(VisualTrackingFailure.SOURCE_NOT_IN_HISTORY,
            result.observations.single().failure)
        assertTrue("Released source must not bypass the history bound",
            result.metrics.retainedFrames <= 32)
    }

    @Test
    fun completedOriginalAndNewPendingBothSurviveBeforeGlConsumption() {
        val tracker = tracker()
        val completedSource = offer(tracker, 0)
        val completedLease = requireNotNull(tracker.reservePendingSourceFrame(completedSource))
        assertTrue(tracker.publishPendingSourceFrame(completedLease))
        var pendingSource = completedSource
        for (index in 1..18) pendingSource = offer(tracker, index)
        val pendingLease = requireNotNull(tracker.reservePendingSourceFrame(pendingSource))
        var target = pendingSource
        for (index in 19..36) target = offer(tracker, index)

        assertTrue(!tracker.releasePendingSourceFrame(completedLease))
        assertTracked(tracker.trackFrom(completedSource, listOf(candidate), target), completedSource, target)
        assertTrue(tracker.publishPendingSourceFrame(pendingLease))
        val result = tracker.trackFrom(pendingSource, listOf(candidate), target)
        assertTracked(result, pendingSource, target)
        assertTrue(result.metrics.retainedFrames <= 32)
    }

    @Test
    fun lateWorkerCannotReleaseOrPublishAReplacementReservationOfTheSameKey() {
        val tracker = tracker()
        val source = offer(tracker, 0)
        val oldLease = requireNotNull(tracker.reservePendingSourceFrame(source))
        assertTrue(tracker.releasePendingSourceFrame(oldLease))
        val replacement = requireNotNull(tracker.reservePendingSourceFrame(source))
        assertTrue(!tracker.releasePendingSourceFrame(oldLease))
        assertTrue(!tracker.publishPendingSourceFrame(oldLease))
        assertTrue(tracker.publishPendingSourceFrame(replacement))

        var target = source
        for (index in 1..36) target = offer(tracker, index)
        assertTracked(tracker.trackFrom(source, listOf(candidate), target), source, target)
    }

    @Test
    fun clearInvalidatesTokensEvenWhenTheIdenticalFrameIsOfferedAndReservedAgain() {
        val tracker = tracker()
        val source = offer(tracker, 0)
        val oldLease = requireNotNull(tracker.reservePendingSourceFrame(source))
        tracker.clear()
        assertEquals(source, offer(tracker, 0))
        val replacement = requireNotNull(tracker.reservePendingSourceFrame(source))
        assertTrue(!tracker.publishPendingSourceFrame(oldLease))
        assertTrue(!tracker.releasePendingSourceFrame(oldLease))
        assertTrue(tracker.publishPendingSourceFrame(replacement))

        var target = source
        for (index in 1..36) target = offer(tracker, index)
        assertTracked(tracker.trackFrom(source, listOf(candidate), target), source, target)
    }

    @Test
    fun failedNewPendingDoesNotReleaseThePreviousCompletedSource() {
        val tracker = tracker()
        val completedSource = offer(tracker, 0)
        val completedLease = requireNotNull(tracker.reservePendingSourceFrame(completedSource))
        assertTrue(tracker.publishPendingSourceFrame(completedLease))
        var pendingSource = completedSource
        for (index in 1..18) pendingSource = offer(tracker, index)
        val pendingLease = requireNotNull(tracker.reservePendingSourceFrame(pendingSource))
        assertTrue(tracker.releasePendingSourceFrame(pendingLease))
        var target = pendingSource
        for (index in 19..36) target = offer(tracker, index)

        val result = tracker.trackFrom(completedSource, listOf(candidate), target)
        assertTracked(result, completedSource, target)
        assertTrue(result.metrics.retainedFrames <= 32)
    }

    @Test
    fun partialConsumptionDoesNotCompleteTheWorkerOrUnprotectTheOlderPublication() {
        val tracker = tracker()
        val completedSource = offer(tracker, 0)
        val completedLease = requireNotNull(tracker.reservePendingSourceFrame(completedSource))
        assertTrue(tracker.publishPendingSourceFrame(completedLease))
        var pendingSource = completedSource
        for (index in 1..18) pendingSource = offer(tracker, index)
        val pendingLease = requireNotNull(tracker.reservePendingSourceFrame(pendingSource))
        assertTracked(tracker.trackFrom(pendingSource, listOf(candidate), pendingSource, completed = false),
            pendingSource, pendingSource)
        var target = pendingSource
        for (index in 19..36) target = offer(tracker, index)

        val older = tracker.trackFrom(completedSource, listOf(candidate), target)
        // Source-order rejection comes after the history check: the completed source remains
        // retained, but must not replace geometry from the already admitted newer partial batch.
        assertEquals(VisualTrackingFailure.DETECTOR_SOURCE_OUT_OF_ORDER, older.observations.single().failure)
        assertNull(older.observations.single().geometry)
        assertTrue(tracker.publishPendingSourceFrame(pendingLease))
        val newer = tracker.trackFrom(pendingSource, listOf(candidate), target, detectionRevision = 1L)
        assertTracked(newer, pendingSource, target)
        assertTrue(newer.metrics.retainedFrames <= 32)
    }

    @Test
    fun twoImageConfigurationKeepsItsHardCapWhenAllOlderImagesHaveOwners() {
        val tracker = tracker(maxHistoryFrames = 2)
        val activeSource = offer(tracker, 0)
        assertTracked(tracker.trackFrom(activeSource, listOf(candidate), activeSource), activeSource, activeSource)
        val publishedSource = offer(tracker, 1)
        val publishedLease = requireNotNull(tracker.reservePendingSourceFrame(publishedSource))
        assertTrue(tracker.publishPendingSourceFrame(publishedLease))
        val target = offer(tracker, 2)

        val evicted = tracker.trackFrom(activeSource, listOf(candidate), target)
        assertEquals(VisualTrackingFailure.SOURCE_NOT_IN_HISTORY, evicted.observations.single().failure)
        assertNull(evicted.observations.single().geometry)
        val result = tracker.trackFrom(publishedSource, listOf(candidate), target)
        assertTracked(result, publishedSource, target)
        assertTrue(result.metrics.retainedFrames <= 2)
    }

    @Test
    fun exact800MsConsumerAgeStillAdmitsTheOriginalCapture() {
        val tracker = tracker()
        val source = offer(tracker, 0)
        assertTracked(tracker.trackFrom(source, listOf(candidate), source), source, source)
        val target = offer(tracker, 1)

        val result = tracker.trackFrom(
            source, listOf(candidate), target,
            observedAtElapsedRealtimeMs = source.capturedAtElapsedRealtimeMs + 800L,
        )
        assertTracked(result, source, target)
        assertEquals(source.capturedAtElapsedRealtimeMs, result.detectorSourceKey.capturedAtElapsedRealtimeMs)
    }

    @Test
    fun consumerAge801MsRejectsTheOriginalWithoutPublishingGeometry() {
        val tracker = tracker()
        val source = offer(tracker, 0)
        assertTracked(tracker.trackFrom(source, listOf(candidate), source), source, source)
        val target = offer(tracker, 1)

        val result = tracker.trackFrom(
            source, listOf(candidate), target,
            observedAtElapsedRealtimeMs = source.capturedAtElapsedRealtimeMs + 801L,
        )
        val observation = result.observations.single()
        assertEquals(VisualTrackingFailure.SOURCE_EXPIRED, observation.failure)
        assertEquals(VisualTrackingStatus.LOST, observation.status)
        assertNull(observation.geometry)
        assertEquals(source, observation.detectorSourceKey)
    }

    @Test
    fun unreservedHistoryKeepsThe32ImageBoundAndReportsActualOwnedBytes() {
        val tracker = tracker()
        val frames = (0..36).map { index -> frame(index).also { assertTrue(tracker.offerFrame(it).accepted) } }
        val current = frames.last()
        val result = tracker.trackFrom(current.key, listOf(candidate), current.key)
        assertTracked(result, current.key, current.key)

        assertEquals(32, VisualTrackingConfig().maxHistoryFrames)
        assertTrue(result.metrics.retainedFrames in 1..32)
        // The same-capture call does not construct diagnostic integral images. Measure the
        // actual owned copies, rather than deriving memory from source-camera dimensions.
        val bytesPerOwnedImage = current.retainedBytes
        assertEquals(EDGE * EDGE, bytesPerOwnedImage)
        assertEquals(
            result.metrics.retainedFrames * bytesPerOwnedImage,
            result.metrics.retainedImageBytes,
        )
        assertTrue(result.metrics.retainedImageBytes <= 32 * bytesPerOwnedImage)
    }

    private fun tracker(maxHistoryFrames: Int = 32) = InterFrameDetectionTracker(
        VisualTrackingConfig(backend = VisualTrackingBackend.PATCH_DIAGNOSTIC,
            maxFeaturesPerObject = 8, maxHistoryFrames = maxHistoryFrames),
        // Retention and consumer-age checks have deterministic timing; this is no latency benchmark.
        clockNanos = { 0L },
    )

    private fun offer(tracker: InterFrameDetectionTracker, index: Int): VisualFrameKey {
        val frame = frame(index)
        assertTrue("Synthetic frame $index must be admitted", tracker.offerFrame(frame).accepted)
        return frame.key
    }

    private fun frame(index: Int) = GrayTrackingFrame.copyOf(
        key = VisualFrameKey(
            epoch = 7L,
            frameId = 10_000L + index,
            cameraTimestampNs = 900_000_000L + index * 17_000_000L,
            capturedAtElapsedRealtimeMs = 50_000L + index * 17L,
            geometryVersion = 4L,
        ),
        width = EDGE,
        height = EDGE,
        pixels = pixels,
    )

    private fun assertTracked(result: VisualTrackingResult, source: VisualFrameKey, target: VisualFrameKey) {
        val observation = result.observations.single()
        assertEquals("Expected valid source tracking, failure=${observation.failure}",
            VisualTrackingStatus.TRACKED, observation.status)
        assertNull(observation.failure)
        assertEquals(source, observation.detectorSourceKey)
        assertEquals(target, observation.trackedTargetKey)
        val actual = checkNotNull(observation.geometry)
        // The diagnostic subpixel fit can move identical texture by a few thousandths of a
        // pixel. A 0.1-pixel bound is stricter than the existing 0.65-pixel tracking fixture.
        val coordinateTolerance = 0.1f / EDGE
        assertEquals(candidate.bboxNorm.x, actual.bboxNorm.x, coordinateTolerance)
        assertEquals(candidate.bboxNorm.y, actual.bboxNorm.y, coordinateTolerance)
        assertEquals(candidate.bboxNorm.width, actual.bboxNorm.width, coordinateTolerance)
        assertEquals(candidate.bboxNorm.height, actual.bboxNorm.height, coordinateTolerance)
    }

    private companion object {
        const val EDGE = 192
    }
}
