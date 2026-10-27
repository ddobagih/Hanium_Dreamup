package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualMaterialPointId
import org.junit.Assert.*
import org.junit.Test

/** Public producers with synthetic depth/point observations, not physical collision ground truth. */
class ApproachCollisionConsumerContractTest {
    @Test fun publicNamedPipelineKeepsUrgentApproachWithoutClaimingCollision() {
        val tracker = ObjectTracker()
        val pipeline = ObjectDepthRuntimePipeline(tracker = tracker, rawDepthMotionOnly = true)
        var result: TrackedObjectDepth? = null
        for (i in 0..3) {
            val at = 1_000L + i * 300L
            val frameNs = at * 1_000_000L
            val cpuNs = 10_000_000_000_000L + frameNs
            val snapshot = snapshot(at, cpuNs, 6_700 - i * 900, pose = null)
            result = pipeline.process(snapshot, frameNs, at, listOf(detection),
                mapper = mapper, mapperFrameId = frameNs, capturedAtElapsedRealtimeMs = at + 50_000L).single()
        }
        val output = requireNotNull(result)
        assertEquals(4, output.trackAgeFrames)
        assertEquals(900L, output.trackStableMs)
        assertEquals(DepthAvailability.MEASURED, output.depthAvailability)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
        assertEquals(4f, requireNotNull(output.riskDistanceM), .001f)
        assertEquals(Trend.APPROACHING, output.trend)
        assertEquals(3f, requireNotNull(output.approachSpeedMps), .001f)
        assertTrue(requireNotNull(output.approachTimeMs) in 1_332L..1_334L)
        assertEquals(output.timeToCollisionMs, output.approachTimeMs)
        val produced = tracker.approachKinematics(tracker.activeTracks().single(), output.riskDistanceM, output.source)
        assertEquals(produced.approachTimeMs, output.approachTimeMs)
        assertEquals(produced.timeToCollisionMs, produced.approachTimeMs)
        assertEquals(ObjectMotion.UNKNOWN, output.objectMotion)
        assertNull(output.motionEstimate.observedAtMs)
        assertEquals(CollisionAssessment.UNKNOWN, produced.collisionAssessment)
        assertEquals(CollisionAssessment.UNKNOWN, output.collisionAssessment)
        // Four metres is outside all distance-only warning thresholds: urgency comes from the
        // original approach time, not from a manufactured near-distance or collision state.
        assertEquals(MessageLevel.STOP, output.userFacing.messageLevel)
        val text = requireNotNull(output.userFacing.message)
        assertTrue(text.contains("멈추세요"))
        assertTrue(text.contains("멈추세요."))
        assertFalse(text.contains("충돌"))
        assertEquals(1_900L, output.timestampMs)
        assertEquals(51_900L, output.sourceObservedAtElapsedRealtimeMs)
        assertNotNull(output.sourceValidUntilElapsedRealtimeMs)
        val decision = metricDecision(output)
        assertEquals(output.approachTimeMs, decision.approachTimeMs)
        assertEquals(CollisionAssessment.UNKNOWN, decision.collisionAssessment)
        assertEquals(output.userFacing, MessagePolicy().evaluate(decision, output.timestampMs).userFacing)
    }

    @Test fun measuredNonClosingTrajectoryKeepsNearStopAndSuppressesApproachTime() {
        val direct = trackedSequence(lateralSpeed = 0f, finalX = 0f)
        assertEquals(ObjectMovementDirection.TOWARD_USER, direct.motionEstimate.direction)
        assertEquals(1f, requireNotNull(direct.motionEstimate.relativeClosingSpeedMps), .001f)
        assertTrue(requireNotNull(direct.approachTimeMs) in 1_999L..2_001L)
        assertEquals(direct.timeToCollisionMs, direct.approachTimeMs)
        assertEquals(CollisionAssessment.UNKNOWN, direct.collisionAssessment)
        val nonClosing = trackedSequence(lateralSpeed = 2f, finalX = 1.1f)
        assertEquals(Trend.APPROACHING, nonClosing.trend)
        assertEquals(1f, requireNotNull(nonClosing.approachSpeedMps), .001f)
        assertTrue(requireNotNull(nonClosing.motionEstimate.relativeClosingSpeedMps) < -.05f)
        assertNull(nonClosing.timeToCollisionMs)
        assertNull(nonClosing.approachTimeMs)
        assertEquals(CollisionAssessment.UNKNOWN, nonClosing.collisionAssessment)
        for (output in listOf(direct, nonClosing)) {
            assertEquals(42L, output.motionEstimate.referenceId)
            assertEquals(output.timestampMs, output.motionEstimate.observedAtMs)
            assertEquals(2f, requireNotNull(output.riskDistanceM), .001f)
            assertEquals(MessageLevel.STOP, output.userFacing.messageLevel)
            assertTrue(requireNotNull(output.userFacing.message).contains("멈추세요"))
        }
    }

    @Test fun legacyCopiesRetireTheAliasesActuallyReadByMessagePolicy() {
        val measured = trackedSequence(lateralSpeed = 0f, finalX = 0f)
        // The runtime age-budget branch performs this exact copy. A derived alias must not
        // keep the old value when the compatibility backing slot has been retired.
        val retired = measured.copy(timeToCollisionMs = null, approachSpeedMps = null)
        assertNull(retired.approachTimeMs)
        assertEquals(CollisionAssessment.UNKNOWN, retired.collisionAssessment)
        assertEquals(MessageLevel.STOP, MessagePolicy().evaluate(metricDecision(retired), retired.timestampMs).userFacing.messageLevel)
        val farUrgent = metricDecision(measured).copy(riskDistanceM = 4f, timeToCollisionMs = 1_000L)
        assertEquals(1_000L, farUrgent.approachTimeMs)
        assertEquals(MessageLevel.STOP, MessagePolicy().evaluate(farUrgent, measured.timestampMs).userFacing.messageLevel)
        val withoutTime = farUrgent.copy(timeToCollisionMs = null)
        assertNull(withoutTime.approachTimeMs)
        assertEquals(MessageLevel.NONE, MessagePolicy().evaluate(withoutTime, measured.timestampMs).userFacing.messageLevel)
        val noCollisionProof = farUrgent.copy(motionEstimate = measured.motionEstimate.copy(
            referenceId = -1L, observedAtMs = measured.timestampMs - 1L))
        assertEquals(CollisionAssessment.UNKNOWN, noCollisionProof.collisionAssessment)
        assertEquals(MessageLevel.STOP, MessagePolicy().evaluate(noCollisionProof, measured.timestampMs).userFacing.messageLevel)
        // Existing positional/default callers and copies keep the old constructor semantics.
        val kinematics = ApproachKinematics(Trend.APPROACHING, .5f, 1f, 2_000L)
        assertEquals(2_000L, kinematics.approachTimeMs)
        assertNull(kinematics.copy(timeToCollisionMs = null).approachTimeMs)
        val noTimeDefault = MetricDepthDecision("car", DepthSource.ARCORE_RAW_DEPTH, 4f, Trend.APPROACHING, .9f)
        assertNull(noTimeDefault.approachTimeMs)
        assertEquals(MessageLevel.NONE, MessagePolicy().evaluate(noTimeDefault, measured.timestampMs).userFacing.messageLevel)
    }

    @Test fun missingOrRetiredPoseCannotTurnNearUrgencyIntoCollisionProof() {
        val changes: List<(Int, CameraPoseEvidence) -> CameraPoseEvidence?> = listOf(
            { _, _ -> null },
            { _, p -> p.copy(referenceId = -1L) },
            { i, p -> if (i == 3) p.copy(referenceId = 43L) else p },
        )
        val outputs = changes.map { change -> trackedSequence(0f, 0f, change) } +
            trackedSequence(0f, 0f, { _, _ -> null }, probeRetiredPose = true)
        outputs.forEach { output ->
            assertNull(output.motionEstimate.observedAtMs)
            assertEquals(ObjectMotion.UNKNOWN, output.objectMotion)
            assertEquals(output.timeToCollisionMs, output.approachTimeMs)
            assertEquals(CollisionAssessment.UNKNOWN, output.collisionAssessment)
            // The actual reliable near Raw capture is still an obstacle, independently of
            // missing/expired spatial history. UNKNOWN never means a safety release.
            assertEquals(DepthAvailability.MEASURED, output.depthAvailability)
            assertEquals(2f, requireNotNull(output.riskDistanceM), .001f)
            assertEquals(MessageLevel.STOP, output.userFacing.messageLevel)
            assertTrue(requireNotNull(output.userFacing.message).contains("멈추세요"))
        }
    }

    private fun trackedSequence(lateralSpeed: Float, finalX: Float,
        poseChange: (Int, CameraPoseEvidence) -> CameraPoseEvidence? = { _, p -> p },
        probeRetiredPose: Boolean = false): TrackedObjectDepth {
        val pipeline = ObjectDepthRuntimePipeline(rawDepthMotionOnly = true)
        val pointId = VisualMaterialPointId()
        var output: TrackedObjectDepth? = null
        for (i in 0..3) {
            val at = 1_000L + i * 200L
            val key = VisualFrameKey(7L, at * 1_000_000L, 10_000_000_000_000L + at * 1_000_000L,
                at + 50_000L, 3L)
            val millimeters = 2_600 - i * 200
            val x = finalX - lateralSpeed * (3 - i) * .2f
            val point = Point2(.5f + .7f * x / (millimeters / 1_000f), .5f)
            val observation = knownMaterialPointObservation(key, key, detection, pointId, point)
            val pose = CameraPoseEvidence(42L, at, 0f, 0f, 0f, 0f, 0f, -1f,
                CameraImageProjection(SIZE, SIZE, 70f, 70f, 50f, 50f, 1f, 0f, 0f, 0f, 1f, 0f))
            if (probeRetiredPose) {
                // A foreign pose clock is rejected before any DTO is produced. It cannot be
                // relabelled UNKNOWN as though that old capture had been admitted.
                val retiredPoseSnapshot = snapshot(at, key.cameraTimestampNs, millimeters,
                    pose.copy(timestampMs = at - 1L))
                assertFalse(observation.matchesDepthSnapshot(retiredPoseSnapshot))
                assertTrue(pipeline.processTrackedObservation(retiredPoseSnapshot, observation, mapper,
                    key.frameId, key.capturedAtElapsedRealtimeMs).isEmpty())
            }
            // The separate valid missing-pose capture retains the original near warning.
            output = pipeline.processTrackedObservation(snapshot(at, key.cameraTimestampNs, millimeters,
                poseChange(i, pose)), observation, mapper, key.frameId, key.capturedAtElapsedRealtimeMs).single()
        }
        return requireNotNull(output)
    }

    private fun snapshot(at: Long, cpuNs: Long, millimeters: Int, pose: CameraPoseEvidence?) =
        DepthFrameSnapshot(at * 1_000_000L, DepthImage16(SIZE, SIZE, IntArray(SIZE * SIZE) { millimeters }),
            ConfidenceImage8(SIZE, SIZE, ByteArray(SIZE * SIZE) { 255.toByte() }), null,
            rawDepthTimestampNs = cpuNs, rawConfidenceTimestampNs = cpuNs,
            cameraImageTimestampNs = cpuNs, cameraPoseEvidence = pose)

    private fun metricDecision(output: TrackedObjectDepth) = MetricDepthDecision(
        output.className, output.source, output.riskDistanceM, output.trend, output.confidence.finalScore,
        output.trackId, output.approachTimeMs, output.objectMotion, output.motionEstimate,
        // This fixture's original, unrotated full-image detection is centered on the camera.
        CameraScreenLateralPosition.CENTER)

    private companion object {
        const val SIZE = 100
        val detection = DetectionCandidate("car", .95f, RectNorm(0f, 0f, 1f, 1f))
        val mapper = LetterboxCoordinateMapper(ModelInputTransform(SIZE, SIZE, SIZE, SIZE, 1f, 0f, 0f), ImageSize(SIZE, SIZE))
    }
}
