package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.inference.tracking.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/** Synthetic same-point metric captures through the real pipeline, not collision ground truth. */
class PointClosestApproachPipelineRegressionTest {
    @Test fun sameRadialApproachTimeCanHaveDifferentPointClosestDistance() {
        val direct = sequence().last().single()
        val lateral = sequence(lateralSpeed = 1f).last().single()
        val directCpa = requireNotNull(direct.pointClosestApproach)
        val lateralCpa = requireNotNull(lateral.pointClosestApproach)
        for (output in listOf(direct, lateral)) {
            assertTrue(requireNotNull(output.approachTimeMs) in 1_999L..2_001L)
            assertEquals(output.timeToCollisionMs, output.approachTimeMs)
            assertEquals(1f, requireNotNull(output.motionEstimate.relativeClosingSpeedMps), .001f)
            assertNearStop(output)
            val cpa = requireNotNull(output.pointClosestApproach)
            assertEquals(output.source, cpa.source)
            assertEquals(output.timestampMs, cpa.observedAtMs)
            assertEquals(42L, cpa.referenceId)
            assertEquals(CPU_BASE + output.frameId, cpa.depthObservationTimestampNs)
            assertEquals(output.motionEstimate.relativeVelocityInAnchorMps, cpa.relativeVelocityInAnchorMps)
            assertSame(cpa, output.motionEstimate.pointClosestApproach)
            assertSame(cpa, output.pointClosestApproachAt(requireNotNull(output.sourceObservedAtElapsedRealtimeMs)))
            assertEquals(cpa.relativePositionInAnchorM, output.motionEstimate.relativePositionInAnchorM)
        }
        assertEquals(2.0, requireNotNull(directCpa.signedTimeSeconds), .002)
        assertEquals(0.0, directCpa.distanceM, .002)
        assertEquals(1.0, requireNotNull(lateralCpa.signedTimeSeconds), .002)
        assertEquals(sqrt(2.0), lateralCpa.distanceM, .002)
        assertFalse(directCpa.relativeVelocityIsZero)
        assertNotSame(directCpa.materialPointId, lateralCpa.materialPointId)
        // The existing decision receives this same qualified motion; point CPA does not
        // replace approach urgency, passage evidence, or the current UNKNOWN assessment.
        val decision = metricDecision(lateral)
        assertSame(lateralCpa, decision.motionEstimate.pointClosestApproach)
        assertEquals(MessageLevel.STOP, MessagePolicy().evaluate(decision, lateral.timestampMs).userFacing.messageLevel)
        assertEquals(CollisionAssessment.UNKNOWN, decision.collisionAssessment)
    }

    @Test fun signedPastPointTimePreservesNonClosingVetoAndNearStop() {
        val sidewaysAway = sequence(lateralSpeed = 2f, finalX = 1.1f).last().single()
        assertEquals(Trend.APPROACHING, sidewaysAway.trend)
        assertEquals(1f, requireNotNull(sidewaysAway.approachSpeedMps), .001f)
        assertTrue(requireNotNull(sidewaysAway.motionEstimate.relativeClosingSpeedMps) < -.05f)
        val sideCpa = requireNotNull(sidewaysAway.pointClosestApproach)
        assertEquals(-.04, requireNotNull(sideCpa.signedTimeSeconds), .002)
        assertEquals(sqrt(5.202), sideCpa.distanceM, .003)
        val receding = sequence(depthMm = { i -> 1_400 + i * 200 }).last().single()
        val pastCpa = requireNotNull(receding.pointClosestApproach)
        assertEquals(Trend.RECEDING, receding.trend)
        assertEquals(-2.0, requireNotNull(pastCpa.signedTimeSeconds), .002)
        assertEquals(0.0, pastCpa.distanceM, .002)
        for (output in listOf(sidewaysAway, receding)) {
            assertNull(output.approachTimeMs)
            assertNull(output.timeToCollisionMs)
            assertNearStop(output)
        }
    }

    @Test fun exactZeroRelativeVelocityHasConstantDistanceWithoutUniqueTime() {
        val output = sequence(depthMm = { 2_000 }).last().single()
        val cpa = requireNotNull(output.pointClosestApproach)
        assertEquals(Vec3(0f, 0f, 0f), cpa.relativeVelocityInAnchorMps)
        assertTrue(cpa.relativeVelocityIsZero)
        assertNull(cpa.signedTimeSeconds)
        assertEquals(2.0, cpa.distanceM, .002)
        assertEquals(Trend.STABLE, output.trend)
        assertNull(output.approachTimeMs)
        assertNearStop(output)
    }

    @Test fun missingAndRetiredPointReferenceOrCaptureCannotRepublishOldBasis() {
        // Controls first publish a qualified basis. The fifth capture then loses one
        // original fact; no fake motion estimate, injected id-switch or new threshold is used.
        val episodes = listOf(
            sequence(frames = 5, poseChange = { i, p -> if (i == 4) p.copy(referenceId = 43L) else p }),
            sequence(frames = 5, poseChange = { i, p -> if (i == 4) p.copy(referenceId = -1L) else p }),
            sequence(frames = 5, poseChange = { i, p -> if (i == 4) p.copy(positionX = Float.NaN) else p }),
            sequence(frames = 5, poseChange = { i, p -> if (i == 4) null else p }),
            sequence(frames = 5, pointChange = { i, id -> if (i == 4) VisualMaterialPointId() else id }),
            sequence(frames = 5, pointChange = { i, id -> if (i == 4) null else id }),
        )
        episodes.forEach { outputs ->
            assertNotNull(outputs[3].single().pointClosestApproach)
            val retired = outputs.last().single()
            assertNull(retired.pointClosestApproach)
            assertNearStop(retired)
        }
        val missingPose = sequence(poseChange = { _, _ -> null }).last().single()
        assertNull(missingPose.pointClosestApproach)
        assertTrue(requireNotNull(missingPose.approachTimeMs) in 1_999L..2_001L)
        assertNearStop(missingPose)
        val foreignPoseClock = sequence(frames = 5,
            poseChange = { i, p -> if (i == 4) p.copy(timestampMs = p.timestampMs - 1L) else p })
        assertNotNull(foreignPoseClock[3].single().pointClosestApproach)
        // The public capture guard returns no output for a foreign pose clock.
        assertTrue(foreignPoseClock.last().isEmpty())
        val reusedDepth = sequence(frames = 5, depthClockChange = { i, ns -> if (i == 4) ns - 200_000_000L else ns })
        assertNotNull(reusedDepth[3].single().pointClosestApproach)
        assertNull(reusedDepth.last().single().pointClosestApproach)
        val missingDepth = sequence(frames = 5, depthMm = { i -> if (i == 4) 0 else 2_800 - i * 200 })
        assertNotNull(missingDepth[3].single().pointClosestApproach)
        val gap = missingDepth.last().single()
        assertNull(gap.pointClosestApproach)
        assertNull(gap.approachTimeMs)
        assertEquals(CollisionAssessment.UNKNOWN, gap.collisionAssessment)
    }

    @Test fun copiedBasisAndOriginalElapsedLeaseRetireOnlyPointEvidence() {
        val output = sequence().last().single()
        val cpa = requireNotNull(output.pointClosestApproach)
        val motion = output.motionEstimate
        val changedMotions = listOf(
            motion.copy(referenceId = 43L),
            motion.copy(observedAtMs = requireNotNull(motion.observedAtMs) + 1L),
            motion.copy(relativeVelocityInAnchorMps = Vec3(1f, 0f, 1f)),
            motion.copy(confidence = motion.confidence - .001f),
            motion.copy(elapsedMs = motion.elapsedMs + 100L),
        )
        changedMotions.forEach { changed ->
            assertNull(changed.pointClosestApproach)
            val copied = output.copy(motionEstimate = changed)
            assertNull(copied.pointClosestApproach)
            assertEquals(output.approachTimeMs, copied.approachTimeMs)
            assertNearStop(copied)
        }
        val changedOutputs = listOf(
            output.copy(source = DepthSource.ARCORE_FULL_DEPTH),
            output.copy(depthAvailability = DepthAvailability.UNAVAILABLE),
            output.copy(spatialCoverageLimited = true),
            output.copy(depthReprojected = true),
            output.copy(depthObservedAtMs = output.timestampMs - 1L),
            output.copy(frameId = output.frameId + 1L), // Same ms is still a foreign full Frame identity.
            output.copy(frameId = output.frameId + 1_000_000L),
            output.copy(timestampMs = output.timestampMs + 1L),
            output.copy(sourceObservedAtElapsedRealtimeMs = null),
            output.copy(sourceValidUntilElapsedRealtimeMs = Long.MIN_VALUE),
            output.copy(sourceMaxAgeMs = null),
            output.copy(sourceObservedAtElapsedRealtimeMs = requireNotNull(output.sourceObservedAtElapsedRealtimeMs) + 1L),
            output.copy(sourceValidUntilElapsedRealtimeMs = requireNotNull(output.sourceValidUntilElapsedRealtimeMs) + 1L),
            output.copy(sourceMaxAgeMs = requireNotNull(output.sourceMaxAgeMs) + 1L),
        )
        changedOutputs.forEach {
            assertNull(it.pointClosestApproach)
            assertNull(it.pointClosestApproachAt(requireNotNull(output.sourceObservedAtElapsedRealtimeMs)))
        }
        val renewed = output.copy(
            sourceObservedAtElapsedRealtimeMs = requireNotNull(output.sourceObservedAtElapsedRealtimeMs) + 1_000L,
            sourceValidUntilElapsedRealtimeMs = requireNotNull(output.sourceValidUntilElapsedRealtimeMs) + 1_000L,
            sourceMaxAgeMs = requireNotNull(output.sourceMaxAgeMs) + 1_000L)
        assertNull(renewed.pointClosestApproachAt(requireNotNull(renewed.sourceObservedAtElapsedRealtimeMs)))
        // Even the actual internal publisher cannot renew a carrier already bound to an
        // original publication. These start from a real public pipeline output.
        assertNull(renewed.bindPointClosestApproachPublication().motionEstimate.pointClosestApproach)
        assertNull(output.copy(frameId = output.frameId + 1L)
            .bindPointClosestApproachPublication().motionEstimate.pointClosestApproach)
        val observed = requireNotNull(output.sourceObservedAtElapsedRealtimeMs)
        val until = requireNotNull(output.sourceValidUntilElapsedRealtimeMs)
        assertSame(cpa, output.pointClosestApproachAt(observed))
        assertSame(cpa, output.pointClosestApproachAt(until))
        assertSame(cpa, output.copy().pointClosestApproachAt(observed))
        assertNull(output.pointClosestApproachAt(observed - 1L))
        assertNull(output.pointClosestApproachAt(until + 1L))
        assertNull(output.copy(sourceMaxAgeMs = 0L).pointClosestApproachAt(observed + 1L))
        val retiredNumeric = output.copy(timeToCollisionMs = null, approachSpeedMps = null)
        assertNull(retiredNumeric.approachTimeMs)
        // Retiring a scalar approach countdown alone does not turn off independently
        // qualified current point geometry or assert collision safety.
        assertSame(cpa, retiredNumeric.pointClosestApproach)
        assertNearStop(retiredNumeric)
    }

    private fun sequence(
        lateralSpeed: Float = 0f,
        finalX: Float = 0f,
        frames: Int = 4,
        depthMm: (Int) -> Int = { i -> 2_000 + (frames - 1 - i) * 200 },
        poseChange: (Int, CameraPoseEvidence) -> CameraPoseEvidence? = { _, p -> p },
        pointChange: (Int, VisualMaterialPointId) -> VisualMaterialPointId? = { _, id -> id },
        depthClockChange: (Int, Long) -> Long = { _, ns -> ns },
    ): List<List<TrackedObjectDepth>> {
        val pipeline = ObjectDepthRuntimePipeline(rawDepthMotionOnly = true)
        val pointId = VisualMaterialPointId()
        return (0 until frames).map { i ->
            val at = 1_000L + i * 200L
            val key = VisualFrameKey(7L, at * 1_000_000L, CPU_BASE + at * 1_000_000L, at + 50_000L, 3L)
            val mm = depthMm(i)
            val x = finalX - lateralSpeed * (frames - 1 - i) * .2f
            val point = Point2(.5f + .7f * x / (mm.coerceAtLeast(1) / 1_000f), .5f)
            val id = pointChange(i, pointId)
            val observation = if (id != null) knownMaterialPointObservation(key, key, detection, id, point) else
                requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(
                    VisualTrackingResult(key, key, listOf(VisualTrackingObservation(
                        0, key, key, VisualTrackingStatus.TRACKED, detection, .95f, null,
                        Point2(0f, 0f), 39, 39, 0f, false, materialPoint = null,
                    )), VisualTrackingMetrics(0L, 1, 0L, false, 2, 0)),
                    listOf(detection), true, key.capturedAtElapsedRealtimeMs,
                ))
            val pose = CameraPoseEvidence(42L, at, 0f, 0f, 0f, 0f, 0f, -1f,
                CameraImageProjection(SIZE, SIZE, 70f, 70f, 50f, 50f, 1f, 0f, 0f, 0f, 1f, 0f))
            val depthNs = depthClockChange(i, key.cameraTimestampNs)
            val snapshot = DepthFrameSnapshot(key.frameId,
                DepthImage16(SIZE, SIZE, IntArray(SIZE * SIZE) { mm }),
                ConfidenceImage8(SIZE, SIZE, ByteArray(SIZE * SIZE) { 255.toByte() }), null,
                rawDepthTimestampNs = depthNs, rawConfidenceTimestampNs = depthNs,
                cameraImageTimestampNs = key.cameraTimestampNs, cameraPoseEvidence = poseChange(i, pose))
            pipeline.processTrackedObservation(snapshot, observation, mapper, key.frameId, key.capturedAtElapsedRealtimeMs)
        }
    }

    private fun assertNearStop(output: TrackedObjectDepth) {
        assertEquals(DepthAvailability.MEASURED, output.depthAvailability)
        assertEquals(2f, requireNotNull(output.riskDistanceM), .001f)
        assertEquals(CollisionAssessment.UNKNOWN, output.collisionAssessment)
        assertEquals(MessageLevel.STOP, output.userFacing.messageLevel)
        assertTrue(requireNotNull(output.userFacing.message).contains("멈추세요"))
    }

    private fun metricDecision(output: TrackedObjectDepth) = MetricDepthDecision(
        output.className, output.source, output.riskDistanceM, output.trend, output.confidence.finalScore,
        output.trackId, output.approachTimeMs, output.objectMotion, output.motionEstimate,
        CameraScreenLateralPosition.CENTER)

    private companion object {
        const val SIZE = 100
        const val CPU_BASE = 10_000_000_000_000L
        val detection = DetectionCandidate("car", .95f, RectNorm(0f, 0f, 1f, 1f))
        val mapper = LetterboxCoordinateMapper(ModelInputTransform(SIZE, SIZE, SIZE, SIZE, 1f, 0f, 0f), ImageSize(SIZE, SIZE))
    }
}
