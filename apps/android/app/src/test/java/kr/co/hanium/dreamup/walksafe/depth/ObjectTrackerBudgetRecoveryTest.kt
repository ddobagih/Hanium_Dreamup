package kr.co.hanium.dreamup.walksafe.depth

import java.util.Random
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.toFeedbackCandidate
import kr.co.hanium.dreamup.walksafe.inference.tracking.*
import org.junit.Assert.*
import org.junit.Test

class ObjectTrackerBudgetRecoveryTest {
    @Test fun exactSourceRecoveryKeepsIdsDepthHistoryAndImmediateWarningEligibility() {
        val continuous = movingScene(gap = false, count = 2, spacing = .18f)
        val recovered = movingScene(gap = true, count = 2, spacing = .18f)
        assertTrue(recovered.filter { it.at in 1_900L..1_950L }.all { it.outputs.isEmpty() })
        val before = recovered.single { it.at == 1_850L }
        for (sample in recovered.filter { it.at >= 2_000L }) {
            val control = continuous.single { it.at == sample.at }
            assertEquals("IDs at ${sample.at}", before.outputs.map { it.trackId }, sample.outputs.map { it.trackId })
            assertEquals("Depth history at ${sample.at}", control.historySizes, sample.historySizes)
            assertEquals("Stable time at ${sample.at}", control.outputs.map { it.trackStableMs }, sample.outputs.map { it.trackStableMs })
            assertNotNull("New warning must not wait another 700 ms at ${sample.at}", sample.warningTrackId)
        }
    }

    @Test fun singleAndWidelySeparatedPairAlsoRetainIdentityThroughTheSameBudgetGap() {
        for ((count, spacing) in listOf(1 to .18f, 2 to .30f)) {
            val samples = movingScene(gap = true, count = count, spacing = spacing)
            val before = samples.single { it.at == 1_850L }
            val after = samples.single { it.at == 2_000L }
            assertEquals(before.outputs.map { it.trackId }, after.outputs.map { it.trackId })
            assertEquals(List(count) { 12 }, after.historySizes)
            assertNotNull(after.warningTrackId)
        }
    }

    @Test fun parallelMovementPastOldNeighborBoxesKeepsBothIdsInEitherDirection() {
        for (shift in listOf(-.11f, .11f)) {
            val tracker = ObjectTracker()
            val source = shapes(.30f, .48f)
            val first = tracker.updateSourceWithAssignments(source, 1_800L)
            tracker.applyTrackedObservations(first.mapIndexed { index, assignment ->
                assignment.track.trackId to source[index].geometry
            }, 1_850L)
            val next = tracker.updateSourceWithAssignments(shapes(.30f, .48f), 1_850L,
                shapes(.30f + shift, .48f + shift), 2_000L)
            assertEquals(first.map { it.track.trackId }, next.map { it.track.trackId })
        }
    }

    @Test fun identityExchangeAfterTheSameGapStillRequiresNewIds() {
        val tracker = ObjectTracker()
        val source = shapes(.30f, .48f)
        val first = tracker.updateSourceWithAssignments(source, 1_800L)
        tracker.applyTrackedObservations(first.mapIndexed { index, assignment ->
            assignment.track.trackId to source[index].geometry
        }, 1_850L)
        val next = tracker.updateSourceWithAssignments(shapes(.30f, .48f), 1_850L,
            shapes(.48f, .30f), 2_000L)
        assertTrue(next.none { candidate -> first.any { it.track.trackId == candidate.track.trackId } })
        assertTrue(next.all { it.track.ageFrames == 1 && !it.track.stable })
    }

    @Test fun delayedCaptureDoesNotUndoAnAlreadyObservedPathAroundAnotherObject() {
        val tracker = ObjectTracker()
        val first = tracker.updateSourceWithAssignments(shapes(.20f, .55f), 1_000L)
        val positions = listOf(
            listOf(shape(.20f, .35f), shape(.55f, .35f)),
            listOf(shape(.20f, .15f), shape(.55f, .55f)),
            listOf(shape(.40f, .15f), shape(.35f, .55f)),
            listOf(shape(.55f, .35f), shape(.20f, .35f)),
        )
        for ((step, geometries) in positions.withIndex()) {
            assertEquals(2, tracker.applyTrackedObservations(first.mapIndexed { index, assignment ->
                assignment.track.trackId to geometries[index]
            }, 1_050L + step * 100L).size)
        }
        // The 1050 capture still has the original ordering. Actual observations at 1150,
        // 1250 and 1350 show the separated objects going around each other before 1400.
        val next = tracker.updateSourceWithAssignments(shapes(.20f, .55f), 1_050L,
            shapes(.56f, .19f), 1_400L)
        assertEquals(first.map { it.track.trackId }, next.map { it.track.trackId })
    }

    @Test fun narrowDiagonalExchangeResetsBothIdsAlongEitherImageAxis() {
        for (width in listOf(.06f, .10f, .12f)) for (transposed in listOf(false, true)) {
            fun box(x: Float, y: Float) = MaskPolygonExtractor().extract(DetectionCandidate("person", .95f,
                if (transposed) RectNorm(y, x, .30f, width) else RectNorm(x, y, width, .30f)))
            val source = listOf(box(.27f, .35f), box(.57f, .35f))
            val current = listOf(box(.57f, .50f), box(.27f, .20f))
            val (before, after) = associateDiagonalMotion(source, current)
            assertTrue("width=$width transposed=$transposed", after.none { it.track.trackId in before })
            assertTrue(after.all { it.track.ageFrames == 1 && !it.track.stable })
        }
    }

    @Test fun diagonalNearPassBelowTheOverlapThresholdKeepsBothIds() {
        fun box(x: Float, y: Float) = MaskPolygonExtractor().extract(
            DetectionCandidate("person", .95f, RectNorm(x, y, .06f, .30f)))
        val (before, after) = associateDiagonalMotion(
            listOf(box(.30f, .35f), box(.50f, .35f)),
            listOf(box(.50f, .60f), box(.30f, .10f)),
        )
        assertEquals(before, after.map { it.track.trackId })
    }

    @Test fun overlapPeakBetweenAxisAlignmentsResetsAmbiguousIds() {
        fun box(x: Float, y: Float) = MaskPolygonExtractor().extract(
            DetectionCandidate("person", .95f, RectNorm(x, y, .17f, .32f)))
        val (before, after) = associateDiagonalMotion(
            listOf(box(.45f, .26f), box(.48f, .52f)),
            listOf(box(.50f, .50f), box(.22f, .30f)),
        )
        assertTrue(after.none { it.track.trackId in before })
        assertTrue(after.all { it.track.ageFrames == 1 && !it.track.stable })
    }

    private fun associateDiagonalMotion(
        source: List<ObjectGeometry>, current: List<ObjectGeometry>,
    ): Pair<List<String>, List<IndexedTrackAssignment>> {
        val tracker = ObjectTracker()
        val sourceIndices = source.mapIndexed { index, geometry -> IndexedObjectGeometry(index, geometry) }
        val first = tracker.updateSourceWithAssignments(sourceIndices, 1_000L)
        for (at in listOf(1_050L, 1_100L)) {
            assertEquals(2, tracker.applyTrackedObservations(first.mapIndexed { index, assignment ->
                assignment.track.trackId to source[index]
            }, at).size)
        }
        val next = tracker.updateSourceWithAssignments(sourceIndices, 1_090L,
            current.mapIndexed { index, geometry -> IndexedObjectGeometry(index, geometry) }, 1_150L)
        assertEquals(2, tracker.applyTrackedObservations(next.mapIndexed { index, assignment ->
            assignment.track.trackId to current[index]
        }, 1_150L).size)
        return first.map { it.track.trackId } to next
    }

    private data class Sample(
        val at: Long,
        val outputs: List<TrackedObjectDepth>,
        val historySizes: List<Int>,
        val warningTrackId: String?,
    )

    private fun movingScene(gap: Boolean, count: Int, spacing: Float): List<Sample> {
        fun key(at: Long) = VisualFrameKey(1, at * 1_000_000L, at * 1_000_000L, at, 1)
        fun shift(at: Long): Int {
            val phase = ((at - 1_000L) / 50L).toInt() % 16
            return (if (phase <= 8) phase else 16 - phase) * 7
        }
        var clock = 0L
        var clockStep = 0L
        val visual = InterFrameDetectionTracker(VisualTrackingConfig(
            backend = VisualTrackingBackend.PATCH_DIAGNOSTIC, maxFeaturesPerObject = 8),
            clockNanos = { clock.also { clock += clockStep } })
        val tracker = ObjectTracker()
        val pipeline = ObjectDepthRuntimePipeline(tracker = tracker, rawDepthMotionOnly = true)
        val mapper = LetterboxCoordinateMapper(ModelInputTransform(192, 192, 192, 192, 1f, 0f, 0f), ImageSize(192, 192))
        val pixels = ByteArray(192 * 192).also { Random(5729L).nextBytes(it) }
        val samples = mutableListOf<Sample>()
        for (at in 1_000L..2_700L step 50L) {
            val offset = shift(at)
            val image = ByteArray(pixels.size) { pixel -> if (pixel % 192 >= offset) pixels[pixel - offset] else 0 }
            assertTrue(visual.offerFrame(GrayTrackingFrame.copyOf(key(at), 192, 192, image)).accepted)
            if (at == 1_000L) continue
            val sourceAt = if (at in 1_900L..2_000L) 1_850L else 1_000L + (at - 1_050L) / 100L * 100L
            val detections = List(count) { index -> DetectionCandidate("person", .95f,
                RectNorm(.05f + index * spacing + shift(sourceAt) / 192f, .35f, .13f, .30f)) }
            clock = 0L
            clockStep = if (gap && at in 1_900L..1_950L) 6_000_000L else 0L
            val tracked = visual.trackFrom(key(sourceAt), detections, key(at), observedAtElapsedRealtimeMs = at)
            val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(tracked, detections, true, at))
            val snapshot = DepthFrameSnapshot(key(at).frameId,
                DepthImage16(192, 192, IntArray(192 * 192) { 1_000 }),
                ConfidenceImage8(192, 192, ByteArray(192 * 192) { 255.toByte() }), null,
                rawDepthTimestampNs = key(at).cameraTimestampNs,
                rawConfidenceTimestampNs = key(at).cameraTimestampNs,
                cameraImageTimestampNs = key(at).cameraTimestampNs)
            val outputs = pipeline.processTrackedObservation(snapshot, observation, mapper, key(at).frameId, at)
                .sortedBy { it.bboxNorm.x }
            val expectedGap = gap && at in 1_900L..1_950L
            assertEquals(if (expectedGap) 0 else count, outputs.size)
            assertTrue(tracked.observations.all { observation ->
                if (expectedGap) observation.failure == VisualTrackingFailure.TIME_BUDGET_EXCEEDED
                else observation.status == VisualTrackingStatus.TRACKED
            })
            val action = WalkSafeFeedbackPolicy().evaluateCandidates(outputs.mapNotNull { it.toFeedbackCandidate() }, true, at)
            samples += Sample(at, outputs, tracker.activeTracks().filter { it.missedFrames == 0 }.map { it.distanceHistory.size }, action?.trackId)
        }
        return samples
    }

    private fun shapes(vararg xs: Float) = xs.mapIndexed { index, x -> IndexedObjectGeometry(index, shape(x, .35f)) }
    private fun shape(x: Float, y: Float) =
        MaskPolygonExtractor().extract(DetectionCandidate("person", .95f, RectNorm(x, y, .13f, .30f)))
}
