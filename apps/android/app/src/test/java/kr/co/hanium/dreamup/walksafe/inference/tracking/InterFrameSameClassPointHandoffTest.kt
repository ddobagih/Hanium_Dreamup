package kr.co.hanium.dreamup.walksafe.inference.tracking

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.junit.Assert.*
import org.junit.Test

/**
 * Real public tracker calls with controlled measured feature correspondences, as in
 * MaterialPointTrackingPipelineTest. IDs are created by the tracker, never supplied as truth labels.
 * These tests do not execute native LK or establish that an image feature belongs to a real person.
 */
class InterFrameSameClassPointHandoffTest {
    @Test fun separatedSameClassPointsSurviveReversedRowsAtAnExactCommonFrame() {
        val scene = Scene()
        val previous = scene.oldRows()
        val next = scene.replace()
        assertEquals(2, previous.size)
        assertNotSame(previous[0].materialPoint!!.id, previous[1].materialPoint!!.id)
        assertSame(previous[0].materialPoint!!.id, next[1].materialPoint!!.id)
        assertSame(previous[1].materialPoint!!.id, next[0].materialPoint!!.id)
        assertEquals(key(1_150), next[0].materialPoint!!.targetKey)
        assertEquals(previous[0].materialPoint!!.imagePointNorm.x + .005f,
            next[1].materialPoint!!.imagePointNorm.x, .0001f)
    }

    @Test fun anotherSourceSwapKeepsBothDistinctIdsWithoutDependingOnRowOrder() {
        val scene = Scene()
        val original = scene.oldRows()
        scene.replace()
        scene.offer(1_200)
        val next = scene.tracker.trackFrom(key(1_100), boxes(1_100), key(1_200)).observations
        assertSame(original[0].materialPoint!!.id, next[0].materialPoint!!.id)
        assertSame(original[1].materialPoint!!.id, next[1].materialPoint!!.id)
        assertNotSame(next[0].materialPoint!!.id, next[1].materialPoint!!.id)
        assertTrue(next.all { it.status == VisualTrackingStatus.TRACKED })
    }

    @Test fun singleClassPositiveKeepsItsExistingHandoff() {
        val scene = Scene(old = listOf(boxes(1_000).first()))
        val original = scene.oldRows().single().materialPoint!!
        val next = scene.replace(listOf(boxes(1_050).first())).single().materialPoint!!
        assertSame(original.id, next.id)
    }

    @Test fun singleClassStillAbstainsWhenTwoOldSameClassDescriptorsExist() {
        val scene = Scene()
        val ids = scene.oldRows().map { it.materialPoint!!.id }
        val row = scene.replace(listOf(boxes(1_050).first())).single()
        assertTrue(ids.none { it === row.materialPoint?.id })
    }

    @Test fun unadmittedSameClassPeerIsNotEvidenceOfUniqueOwnership() {
        val scene = Scene(config = VisualTrackingConfig(maxTrackedObjects = 2))
        val original = scene.oldRows().mapNotNull { it.materialPoint?.id }
        val next = scene.replace(boxes(1_050) + person(.83f, .12f))
        assertTrue(next.none { row -> original.any { it === row.materialPoint?.id } })
        assertTrue(next.any { it.failure == VisualTrackingFailure.OBJECT_BUDGET_EXCEEDED })
    }

    @Test fun failedSameClassPeerCannotBeIgnoredByTheOtherTarget() {
        val flow = MeasuredFlow(failNewRight = true)
        val scene = Scene(flow)
        val original = scene.oldRows().first().materialPoint!!.id
        val rows = scene.replace(boxes(1_050))
        assertNotSame(original, rows.first().materialPoint?.id)
        assertEquals(VisualTrackingFailure.NATIVE_TRACKING_FAILED, rows.last().failure)
    }

    @Test fun lowQualitySameClassPeerCannotEstablishAUniqueHandoff() {
        val scene = Scene(MeasuredFlow(lowQualityNewRight = true))
        val original = scene.oldRows().first().materialPoint!!.id
        val rows = scene.replace(boxes(1_050))
        assertNotSame(original, rows.first().materialPoint?.id)
        assertNull(rows.last().materialPoint)
    }

    @Test fun peerWithoutAMeasuredPointPreventsRepeatedClassAdoption() {
        val scene = Scene(MeasuredFlow(dropNewRightPoint = true))
        val original = scene.oldRows().first().materialPoint!!.id
        val rows = scene.replace(boxes(1_050))
        assertNotSame(original, rows.first().materialPoint?.id)
        assertNull(rows.last().materialPoint)
    }

    @Test fun budgetDeferredPeerCannotCauseAnEarlyPointAdoption() {
        var now = 0L
        val flow = MeasuredFlow(afterNewAdvance = { now = 10_000_000L })
        val scene = Scene(flow, clock = { now })
        val original = scene.oldRows().mapNotNull { it.materialPoint?.id }
        val rows = scene.replace(boxes(1_050))
        assertTrue(rows.all { it.materialPoint == null })
        assertTrue(rows.any { it.failure == VisualTrackingFailure.TIME_BUDGET_EXCEEDED })
        val firstNewInput = flow.pointInputs.first { it.sourceAt == 1_050L }
        assertTrue(original.none { it === firstNewInput.id })
    }

    @Test fun twoEligibleOldPointsRemainAmbiguousInEitherRowOrder() {
        for (reverse in listOf(false, true)) {
            val old = listOf(person(.10f, .40f), person(.20f, .40f))
            val flow = MeasuredFlow(speed = 0f, oldEdgeSeeds = true)
            val scene = Scene(flow, old = old)
            val ids = scene.oldRows().map { it.materialPoint!!.id }
            val current = listOf(person(.10f, .50f), person(.75f, .15f)).let { if (reverse) it.reversed() else it }
            val rows = scene.replace(current)
            assertTrue(rows.all { row -> ids.none { it === row.materialPoint?.id } })
        }
    }

    @Test fun oneOldPointInsideTwoCurrentTargetsCannotBeOwnedByEither() {
        val scene = Scene(MeasuredFlow(speed = 0f))
        val ids = scene.oldRows().map { it.materialPoint!!.id }
        val rows = scene.replace(listOf(person(.08f, .24f), person(.10f, .24f)))
        assertTrue(rows.all { row -> ids.none { it === row.materialPoint?.id } })
    }

    @Test fun oldPeerWithoutPointCanBeExcludedByItsMeasuredSameFrameGeometry() {
        val old = listOf(person(.10f, .40f), person(.20f, .40f))
        val scene = Scene(MeasuredFlow(speed = 0f, oldEdgeSeeds = true, dropOldRightPoint = true), old = old)
        val original = scene.oldRows().first().materialPoint!!.id
        assertNull(scene.previous.last().materialPoint)
        // Its normal, tracked region excludes the other old point even without an auxiliary point.
        val rows = scene.replace(listOf(person(.10f, .40f), person(.75f, .15f)))
        assertSame(original, rows.first().materialPoint!!.id)
    }

    @Test fun oldNonvisualFailureCannotBeTreatedAsAnAbsentSameClassOwner() {
        val scene = Scene(MeasuredFlow(failOldRightNonvisual = true))
        val previous = scene.oldRows()
        assertEquals(VisualTrackingFailure.WORK_BUDGET_EXCEEDED, previous.last().failure)
        val original = previous.first().materialPoint?.id
        val rows = scene.replace(boxes(1_050))
        assertTrue(rows.none { original != null && it.materialPoint?.id === original })
    }

    @Test fun oldSingleClassHandoffDoesNotGainARepeatedClassPeerRequirement() {
        val old = listOf(boxes(1_000).first(), boxes(1_000).last().copy(className = "bicycle"))
        val scene = Scene(MeasuredFlow(failOldRightNonvisual = true), old = old)
        val original = scene.oldRows().first().materialPoint!!.id
        val rows = scene.replace(listOf(boxes(1_050).first()))
        assertSame(original, rows.single().materialPoint!!.id)
    }

    @Test fun aDetectorRegionExcludingTheOldPointStartsWithItsOwnMeasuredId() {
        val scene = Scene(MeasuredFlow(speed = 0f))
        val ids = scene.oldRows().map { it.materialPoint!!.id }
        // A high-overlap old box is insufficient when its actual point is outside the new polygon.
        val withoutOldPoint = boxes(1_050).first().copy(polygonNorm = listOf(
            kr.co.hanium.dreamup.walksafe.depth.Point2(.08f, .35f),
            kr.co.hanium.dreamup.walksafe.depth.Point2(.14f, .35f),
            kr.co.hanium.dreamup.walksafe.depth.Point2(.14f, .65f),
            kr.co.hanium.dreamup.walksafe.depth.Point2(.08f, .65f),
        ))
        val rows = scene.replace(listOf(withoutOldPoint, boxes(1_050).last()))
        assertTrue(ids.none { it === rows.first().materialPoint?.id })
    }

    @Test fun aRotationChangeNeverCopiesThePreviousPointIdentity() {
        val scene = Scene()
        val ids = scene.oldRows().map { it.materialPoint!!.id }
        val rows = scene.replace(turns = 1)
        assertTrue(rows.all { row -> ids.none { it === row.materialPoint?.id } })
    }

    @Test fun changedEpochAndGeometryStartAnIndependentMeasuredPointHistory() {
        for (changeEpoch in listOf(false, true)) {
            val scene = Scene()
            val ids = scene.oldRows().map { it.materialPoint!!.id }
            val source = key(1_150).copy(epoch = if (changeEpoch) 8L else 7L,
                geometryVersion = if (changeEpoch) 3L else 4L)
            val target = source.copy(frameId = source.frameId + 50_000_000L,
                cameraTimestampNs = source.cameraTimestampNs + 50_000_000L,
                capturedAtElapsedRealtimeMs = source.capturedAtElapsedRealtimeMs + 50L)
            assertTrue(scene.tracker.offerFrame(frame(source)).reset)
            assertTrue(scene.tracker.offerFrame(frame(target)).accepted)
            val rows = scene.tracker.trackFrom(source, boxes(1_150), target).observations
            assertEquals(2, rows.size)
            assertTrue(rows.all { it.status == VisualTrackingStatus.TRACKED })
            assertTrue(rows.all { row -> ids.none { it === row.materialPoint?.id } })
        }
    }

    @Test fun aForeignCaptureTimestampCannotQualifyAsTheCommonFrame() {
        val scene = Scene()
        scene.oldRows()
        scene.offer(1_150)
        val result = scene.tracker.trackFrom(key(1_050).copy(cameraTimestampNs = key(1_050).cameraTimestampNs + 1L),
            boxes(1_050), key(1_150))
        assertTrue(result.observations.all { it.failure == VisualTrackingFailure.FRAME_IDENTITY_CONFLICT })
        assertTrue(result.observations.all { it.materialPoint == null })
    }

    @Test fun partialDetectorResultsCannotCopyCompletedPointIdentities() {
        val scene = Scene()
        scene.oldRows()
        val rows = scene.replace(completed = false)
        assertTrue(rows.all { it.materialPoint == null })
    }

    private class Scene(
        val flow: MeasuredFlow = MeasuredFlow(),
        config: VisualTrackingConfig = VisualTrackingConfig(),
        val old: List<DetectionCandidate> = boxes(1_000),
        clock: () -> Long = { 0L },
    ) {
        val tracker = InterFrameDetectionTracker(config, clock, flow)
        lateinit var previous: List<VisualTrackingObservation>
        fun offer(at: Long) { assertTrue(tracker.offerFrame(frame(key(at))).accepted) }
        fun oldRows(): List<VisualTrackingObservation> {
            for (at in 1_000L..1_100L step 50L) offer(at)
            return tracker.trackFrom(key(1_000), old, key(1_100)).observations.also { previous = it }
        }
        fun replace(detections: List<DetectionCandidate> = boxes(1_050).reversed(),
            turns: Int = 0, completed: Boolean = true): List<VisualTrackingObservation> {
            offer(1_150)
            return tracker.trackFrom(key(1_050), detections, key(1_150),
                completed = completed, uprightQuarterTurns = turns).observations
        }
    }

    private data class PointInput(val sourceAt: Long, val id: VisualMaterialPointId?)

    private class MeasuredFlow(
        val speed: Float = .1f,
        val failNewRight: Boolean = false,
        val lowQualityNewRight: Boolean = false,
        val dropNewRightPoint: Boolean = false,
        val failOldRightNonvisual: Boolean = false,
        val oldEdgeSeeds: Boolean = false,
        val dropOldRightPoint: Boolean = false,
        val afterNewAdvance: (() -> Unit)? = null,
    ) : VisualMotionEstimator {
        override val supportsMaterialPointTracking: Boolean get() = true
        val pointInputs = mutableListOf<PointInput>()
        override fun seed(frame: GrayTrackingFrame, detection: DetectionCandidate,
            budget: TrackingWorkBudget): List<VisualMotionFeature> {
            val box = detection.bboxNorm
            return (0 until 40).map { index ->
                val x = if (oldEdgeSeeds && frame.key == key(1_000)) {
                    if (box.x < .15f) .11f + (index % 8) * .001f else .59f - (index % 8) * .001f
                } else box.x + box.width * (.1f + (index % 8) * .8f / 7f)
                VisualMotionFeature((x * frame.width).toInt(),
                    ((box.y + box.height * (.1f + (index / 8) * .8f / 4f)) * frame.height).toInt())
            }
        }
        override fun advance(source: GrayTrackingFrame, previous: GrayTrackingFrame, target: GrayTrackingFrame,
            detection: DetectionCandidate, features: List<VisualMotionFeature>, originalFeatureCount: Int,
            budget: TrackingWorkBudget, materialPoint: VisualMotionPoint?): VisualMotionEstimate {
            val sourceAt = source.key.frameId / 1_000_000L
            val right = detection.bboxNorm.x >= .2f
            pointInputs += PointInput(sourceAt, materialPoint?.id)
            if ((sourceAt > 1_000L && right && failNewRight) ||
                (sourceAt == 1_000L && right && failOldRightNonvisual)) {
                return VisualMotionEstimate(features, VisualAffineTransform(), .95f, 0f,
                    if (sourceAt == 1_000L) VisualTrackingFailure.WORK_BUDGET_EXCEEDED else VisualTrackingFailure.NATIVE_TRACKING_FAILED)
            }
            val dx = speed * (target.key.frameId - source.key.frameId) / 1_000_000_000f
            val edgeDx = speed * (target.key.frameId - previous.key.frameId) / 1_000_000_000f
            val point = materialPoint?.takeUnless { right && ((sourceAt > 1_000 && dropNewRightPoint) ||
                (sourceAt == 1_000L && dropOldRightPoint)) }?.let { it.copy(x = it.x + edgeDx * target.width) }
            if (sourceAt > 1_000L) afterNewAdvance?.invoke()
            return VisualMotionEstimate(features.map { it.copy(currentX = it.sourceX + dx * target.width) },
                VisualAffineTransform(tx = dx), if (sourceAt > 1_000L && right && lowQualityNewRight) .64f else .95f,
                0f, materialPoint = point)
        }
    }

    private companion object {
        fun key(at: Long) = VisualFrameKey(7L, at * 1_000_000L,
            10_000_000_000_000L + at * 1_000_000L, at + 50_000L, 3L)
        fun frame(key: VisualFrameKey) = GrayTrackingFrame.copyOf(key, 100, 100, ByteArray(10_000))
        fun person(x: Float, width: Float = .24f) = DetectionCandidate("person", .95f, RectNorm(x, .35f, width, .3f))
        fun boxes(at: Long) = listOf(person(.08f + .1f * (at - 1_000L) / 1_000f),
            person(.50f + .1f * (at - 1_000L) / 1_000f))
    }
}
