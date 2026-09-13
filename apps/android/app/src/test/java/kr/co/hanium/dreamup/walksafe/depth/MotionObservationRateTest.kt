package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MotionObservationRateTest {
    @Test
    fun twoHertzObservationsRetainWorldRelativeAndCameraVelocities() {
        assertRateIndependentMotion(hz = 2)
    }

    @Test
    fun tenHertzObservationsRetainWorldRelativeAndCameraVelocities() {
        assertRateIndependentMotion(hz = 10)
    }

    @Test
    fun twentyNineHertzObservationsRetainWorldRelativeAndCameraVelocities() {
        assertRateIndependentMotion(hz = 29)
    }

    @Test
    fun thirtyHertzObservationsRetainWorldRelativeAndCameraVelocities() {
        assertRateIndependentMotion(hz = 30)
    }

    @Test
    fun thirtyHertzCurrentTrackingMeasuresMotionWithOnlyTwoHertzDetectorConfirmations() {
        val fixture = Fixture(INCOMING)
        timestamps(hz = 30, durationMs = 2_000L).forEachIndexed { index, at ->
            fixture.observe(at, detectorObservation = index % 15 == 0)
        }

        assertMeasuredMotion(INCOMING, fixture.estimate(), 2_000L, "30 Hz tracking and 2 Hz detector")
        assertEquals(5, fixture.track.ageFrames)
        assertEquals(2_000L, fixture.track.lastDetectionAtMs)
    }

    @Test
    fun rollingThirtyHertzHistoryDoesNotLoseKnownMotionAfterWarmup() {
        val fixture = Fixture(INCOMING)
        for (at in timestamps(hz = 30, durationMs = 2_000L)) {
            val estimate = fixture.observe(at)
            if (at >= 1_000L) assertMeasuredMotion(INCOMING, estimate, at, "30 Hz at $at ms")
        }
    }

    @Test
    fun repeatedTimestampCannotReplaceFourUniqueFreshObservations() {
        val fixture = Fixture(INCOMING)
        listOf(0L, 300L, 600L).forEach { fixture.observe(it) }

        repeat(90) { assertUnknown(fixture.observe(600L)) }

        assertMeasuredMotion(INCOMING, fixture.observe(800L), 800L, "fourth unique frame")
    }

    @Test
    fun duplicateOldDepthCannotRefreshMotionAfterDetectionAdvances() {
        val fixture = warmFixture()
        val previous = fixture.track.distanceHistory.last()
        fixture.detectWithoutDepth(2_033L)

        repeat(30) {
            fixture.tracker.recordDistance(
                track = fixture.track,
                distanceM = previous.distanceM,
                source = previous.source,
                confidence = previous.confidence,
                timestampMs = previous.timestampMs,
                cameraPoseEvidence = previous.cameraPoseEvidence,
                objectPositionInAnchor = previous.objectPositionInAnchor,
                depthObservationTimestampNs = previous.depthObservationTimestampNs,
                requireIndependentDepthObservation = true,
            )
            assertUnknown(fixture.estimate())
        }

        assertMeasuredMotion(INCOMING, fixture.observe(2_066L), 2_066L, "fresh depth after duplicate")
    }

    @Test
    fun retrogradeObservationDoesNotReviveEarlierMotionOnNextFreshFrame() {
        val fixture = warmFixture()
        val oldTrackId = fixture.track.trackId

        fixture.rejectRetrogradeDetection(1_900L)
        assertUnknown(fixture.estimate())
        assertUnknown(fixture.observe(2_033L))
        assertFalse(oldTrackId == fixture.track.trackId)
        assertFreshWarmupRequired(fixture, firstFreshAtMs = 2_033L)
    }

    @Test
    fun anchorResetRequiresNewReferenceHistoryBeforeMotionReturns() {
        val fixture = warmFixture()

        assertUnknown(fixture.observe(2_033L, referenceId = 43L))
        assertFreshWarmupRequired(fixture, firstFreshAtMs = 2_033L, referenceId = 43L)
        assertEquals(43L, fixture.estimate().referenceId)
    }

    @Test
    fun missingPoseIsUnknownNowButPreservesHistoryForNextFreshObservation() {
        val fixture = warmFixture()

        assertUnknown(fixture.observe(2_033L, evidence = Evidence.POSE_MISSING))
        assertMeasuredMotion(INCOMING, fixture.observe(2_066L), 2_066L, "fresh depth after missing pose")
    }

    @Test
    fun reprojectedDepthIsUnknownNowButFreshResumeWithinFifteenHundredMillisecondsUsesHistory() {
        val fixture = warmFixture()
        for (at in timestamps(hz = 30, durationMs = 1_000L).drop(1).map { 2_000L + it }) {
            assertUnknown(fixture.observe(at, evidence = Evidence.REPROJECTED))
        }

        assertMeasuredMotion(INCOMING, fixture.observe(3_033L), 3_033L, "fresh depth after short reprojection gap")
    }

    @Test
    fun stalePoseTimestampCannotBeTreatedAsFreshDepthEvidence() {
        val fixture = warmFixture()

        assertUnknown(fixture.observe(2_033L, evidence = Evidence.POSE_STALE))
        assertMeasuredMotion(INCOMING, fixture.observe(2_066L), 2_066L, "fresh depth after stale pose")
    }

    @Test
    fun sourceChangeRequiresFreshSingleSourceHistoryBeforeMotionReturns() {
        val fixture = warmFixture()

        assertUnknown(fixture.observe(2_033L, source = DepthSource.ARCORE_FULL_DEPTH))
        assertFreshWarmupRequired(
            fixture,
            firstFreshAtMs = 2_033L,
            source = DepthSource.ARCORE_FULL_DEPTH,
        )
    }

    @Test
    fun occludedObjectCannotInheritMotionWhenItReappears() {
        val fixture = warmFixture()
        val previousTrackId = fixture.track.trackId
        fixture.tracker.update(emptyList(), 2_033L)

        assertUnknown(fixture.estimate())
        assertUnknown(fixture.observe(2_066L))
        assertFalse(previousTrackId == fixture.track.trackId)
        assertFreshWarmupRequired(fixture, firstFreshAtMs = 2_066L)
    }

    @Test
    fun twoHertzFreshDepthSurvivesThirtyHertzReprojectionWithoutInventingFreshMeasurements() {
        val fixture = Fixture(INCOMING)
        for ((index, atMs) in timestamps(hz = 30, durationMs = 2_000L).withIndex()) {
            val fresh = index % 15 == 0
            val estimate = fixture.observe(atMs, evidence = if (fresh) Evidence.FRESH else Evidence.REPROJECTED)
            if (!fresh) {
                assertUnknown(estimate)
            } else if (atMs >= 1_500L) {
                assertMeasuredMotion(INCOMING, estimate, atMs, "2 Hz fresh depth at $atMs ms")
            }
        }
    }

    @Test
    fun newerArFramesWithSameDepthTimestampDoNotIncreaseIndependentObservationCount() {
        val fixture = Fixture(INCOMING)
        listOf(0L, 300L, 600L).forEach { fixture.observe(it) }

        for (atMs in listOf(700L, 800L, 900L)) {
            assertUnknown(fixture.observe(atMs, depthObservationTimestampNs = depthClock(600L)))
            assertEquals(3, fixture.track.distanceHistory.size)
            assertEquals(600L, fixture.track.distanceHistory.last().timestampMs)
            assertEquals(Trend.UNKNOWN, fixture.kinematics().trend)
            assertNull(fixture.kinematics().timeToCollisionMs)
        }

        assertMeasuredMotion(INCOMING, fixture.observe(1_000L), 1_000L, "fourth independent depth image")
    }

    @Test
    fun missingZeroAndNegativeDepthTimestampsCannotEstablishIndependentMetricMotion() {
        for (invalidTimestamp in listOf(null, 0L, -1L)) {
            val fixture = Fixture(INCOMING)
            for (atMs in timestamps(hz = 30, durationMs = 2_000L)) {
                assertUnknown(fixture.observe(atMs, depthObservationTimestampNs = invalidTimestamp))
            }
            assertTrue(fixture.track.distanceHistory.isEmpty())
            val kinematics = fixture.tracker.approachKinematics(fixture.track, 6f, DepthSource.ARCORE_RAW_DEPTH)
            assertEquals(Trend.UNKNOWN, kinematics.trend)
            assertNull(kinematics.approachSpeedMps)
            assertNull(kinematics.timeToCollisionMs)
        }
    }

    @Test
    fun independentDepthClockRollbackClearsMotionEvenWhenArClockIncreases() {
        val fixture = warmFixture()

        assertUnknown(fixture.observe(2_033L, depthObservationTimestampNs = depthClock(1_900L)))
        assertFreshWarmupRequired(fixture, firstFreshAtMs = 2_066L)
    }

    @Test
    fun lastThirtyThreeMillisecondMovementCannotBeAveragedIntoStationaryDirection() {
        val stationary = SCENARIOS.first()
        val fixture = Fixture(stationary)
        timestamps(hz = 30, durationMs = 2_000L).forEach { fixture.observe(it) }
        assertMeasuredMotion(stationary, fixture.estimate(), 2_000L, "before movement starts")

        val started = fixture.observe(2_033L, objectOffset = Vec3(0f, 0f, 0.5f * 0.033f))

        assertFalse("last segment moves at 0.5 m/s", started.direction == ObjectMovementDirection.STATIONARY)
    }

    @Test
    fun scalarClosingSpeedAndTtcStayConsistentAtTwoTenAndThirtyHertz() {
        val walkingTowardFixedObject = SCENARIOS[1]
        val ttcValues = mutableListOf<Long>()
        for (hz in listOf(2, 10, 30)) {
            val fixture = Fixture(walkingTowardFixedObject)
            timestamps(hz, durationMs = 2_000L).forEach { fixture.observe(it) }
            val kinematics = fixture.kinematics()

            assertEquals("$hz Hz", Trend.APPROACHING, kinematics.trend)
            assertEquals("$hz Hz", 1f, requireNotNull(kinematics.approachSpeedMps), TOLERANCE)
            val ttcMs = requireNotNull(kinematics.timeToCollisionMs)
            assertTrue("$hz Hz TTC is $ttcMs ms", abs(ttcMs - 6_000L) <= 1L)
            ttcValues += ttcMs
        }
        assertTrue("rate changes must not change TTC beyond rounding", ttcValues.maxOrNull()!! - ttcValues.minOrNull()!! <= 1L)
    }

    @Test
    fun freshDepthGapLongerThanFifteenHundredMillisecondsRequiresNewWarmup() {
        val fixture = warmFixture()
        for (elapsedMs in timestamps(hz = 30, durationMs = 1_600L).drop(1)) {
            assertUnknown(fixture.observe(2_000L + elapsedMs, evidence = Evidence.REPROJECTED))
        }

        assertUnknown(fixture.observe(3_633L))
        assertFreshWarmupRequired(fixture, firstFreshAtMs = 3_633L)
    }

    @Test
    fun intermediateAnchorResetCannotBeHiddenWhenOriginalReferenceReturns() {
        val fixture = warmFixture()

        assertUnknown(fixture.observe(2_033L, referenceId = 43L))
        assertUnknown(fixture.observe(2_066L, referenceId = 42L))
        assertFreshWarmupRequired(fixture, firstFreshAtMs = 2_066L, referenceId = 42L)
    }

    @Test
    fun coordinateOrSourceBoundaryStillClearsHistoryWhenDepthIsDuplicateOrMissing() {
        for (source in listOf(DepthSource.ARCORE_RAW_DEPTH, DepthSource.ARCORE_FULL_DEPTH)) {
            for (depthTimestamp in listOf(null, depthClock(2_000L))) {
                val fixture = warmFixture()
                assertUnknown(fixture.observe(
                    2_033L, referenceId = if (source == DepthSource.ARCORE_RAW_DEPTH) 43L else 42L,
                    source = source, depthObservationTimestampNs = depthTimestamp,
                ))
                assertUnknown(fixture.observe(2_066L))
                assertFreshWarmupRequired(fixture, firstFreshAtMs = 2_066L)
            }
        }
    }

    @Test
    fun longRunningFreshMotionHistoryIsBoundedByElapsedTimeAndCount() {
        val fixture = Fixture(SCENARIOS.first())
        timestamps(hz = 30, durationMs = 20_000L).forEach { fixture.observe(it) }
        val history = fixture.track.denseMotionHistory()
        assertTrue(history.size <= 256)
        assertTrue(history.all { 20_000L - it.timestampMs in 0L..4_000L })
        assertMeasuredMotion(SCENARIOS.first(), fixture.estimate(), 20_000L, "bounded steady history")
    }

    @Test
    fun missingPoseDoesNotDisableIndependentRelativeSpeedOrDistanceRiskEvidence() {
        val fixture = Fixture(SCENARIOS[1])
        timestamps(hz = 30, durationMs = 2_000L).forEach { fixture.observe(it, evidence = Evidence.POSE_MISSING) }

        assertUnknown(fixture.estimate())
        assertEquals(Trend.APPROACHING, fixture.kinematics().trend)
        assertEquals(1f, requireNotNull(fixture.kinematics().approachSpeedMps), TOLERANCE)
        assertTrue(fixture.track.metricDistanceReliable)
    }

    @Test
    fun sourceOrAnchorBoundaryWithoutIndependentDepthCannotEraseTheDistanceJumpBaseline() {
        for (depthTimestamp in listOf(null, depthClock(2_000L))) {
            val fixture = warmFixture()
            val baseline = fixture.track.distanceHistory.last()
            for (at in listOf(2_033L, 2_066L)) {
                fixture.detectWithoutDepth(at)
                val accepted = fixture.tracker.recordDistance(
                    fixture.track, 1f, DepthSource.ARCORE_FULL_DEPTH, 0.9f, at,
                    depthObservationTimestampNs = depthTimestamp,
                    requireIndependentDepthObservation = true,
                )
                assertFalse(accepted)
                assertEquals(baseline, fixture.track.distanceHistory.last())
                assertNull(fixture.tracker.approachKinematics(fixture.track, 1f, DepthSource.ARCORE_FULL_DEPTH).timeToCollisionMs)
            }
            assertUnknown(fixture.observe(2_099L, source = DepthSource.ARCORE_FULL_DEPTH))
            assertTrue(fixture.track.metricDistanceReliable)
            assertEquals(1, fixture.track.distanceHistory.size)
        }
    }

    @Test
    fun invalidFreshConfidenceCannotConsumePendingBoundaryAndAuthorizeARepeatedDistanceJump() {
        val fixture = warmFixture()
        val baseline = fixture.track.distanceHistory.last()
        fun record(at: Long, confidence: Float, depthTimestamp: Long?): Boolean {
            fixture.detectWithoutDepth(at)
            return fixture.tracker.recordDistance(
                fixture.track, 1f, DepthSource.ARCORE_FULL_DEPTH, confidence, at,
                depthObservationTimestampNs = depthTimestamp, requireIndependentDepthObservation = true,
            )
        }

        assertFalse(record(2_033L, 0.9f, null))
        assertFalse(record(2_066L, Float.NaN, depthClock(2_066L)))
        assertEquals(baseline, fixture.track.distanceHistory.lastOrNull())
        assertFalse(record(2_099L, 0.9f, null))
        assertEquals(baseline, fixture.track.distanceHistory.lastOrNull())
        assertTrue(record(2_132L, 0.9f, depthClock(2_132L)))
        assertEquals(1f, fixture.track.distanceHistory.single().distanceM, 0f)
        assertNull(fixture.kinematics().timeToCollisionMs)
    }

    private fun assertRateIndependentMotion(hz: Int) {
        for (scenario in SCENARIOS) {
            val fixture = Fixture(scenario)
            timestamps(hz, durationMs = 2_000L).forEach { fixture.observe(it) }
            val estimate = fixture.estimate()

            assertMeasuredMotion(scenario, estimate, atMs = 2_000L, label = "${scenario.name} at $hz Hz")
            assertEquals(estimate, fixture.kinematics().motionEstimate)
        }
    }

    private fun assertMeasuredMotion(scenario: Scenario, estimate: ObjectMotionEstimate, atMs: Long, label: String) {
        assertEquals(label, scenario.direction, estimate.direction)
        assertVector(label, scenario.objectVelocity, estimate.objectVelocityInAnchorMps)
        assertVector(label, scenario.cameraVelocity, estimate.cameraVelocityInAnchorMps)
        val expectedRelative = scenario.objectVelocity - scenario.cameraVelocity
        assertVector(label, expectedRelative, estimate.relativeVelocityInAnchorMps)
        assertEquals(label, scenario.objectVelocity.norm(), requireNotNull(estimate.objectSpeedMps), TOLERANCE)
        val seconds = atMs / 1_000f
        val objectPosition = OBJECT_START + scenario.objectVelocity * seconds
        val cameraPosition = scenario.cameraVelocity * seconds
        val expectedClosing = expectedRelative.dot((cameraPosition - objectPosition).normalized())
        assertEquals(label, expectedClosing, requireNotNull(estimate.relativeClosingSpeedMps), TOLERANCE)
        assertEquals(label, atMs, estimate.observedAtMs)
        assertTrue("$label needs at least 600 ms of observations", estimate.elapsedMs >= 600L)
        assertEquals(label, 0.9f, estimate.confidence, TOLERANCE)
    }

    private fun assertFreshWarmupRequired(
        fixture: Fixture,
        firstFreshAtMs: Long,
        referenceId: Long = 42L,
        source: DepthSource = DepthSource.ARCORE_RAW_DEPTH,
    ) {
        // Resume with actual 33/34 ms observations; old evidence must not shorten this window.
        for (elapsedMs in timestamps(hz = 30, durationMs = 1_000L)) {
            val estimate = fixture.observe(firstFreshAtMs + elapsedMs, referenceId = referenceId, source = source)
            if (elapsedMs < 600L) assertUnknown(estimate)
        }
        assertMeasuredMotion(INCOMING, fixture.estimate(), firstFreshAtMs + 1_000L, "fresh history after reset")
    }

    private fun warmFixture(): Fixture = Fixture(INCOMING).also { fixture ->
        timestamps(hz = 30, durationMs = 2_000L).forEach { fixture.observe(it) }
        assertMeasuredMotion(INCOMING, fixture.estimate(), atMs = 2_000L, label = "before interruption")
    }

    private fun assertVector(label: String, expected: Vec3, actual: Vec3?) {
        val vector = requireNotNull(actual) { "$label has no measured vector" }
        assertEquals(label, expected.x, vector.x, TOLERANCE)
        assertEquals(label, expected.y, vector.y, TOLERANCE)
        assertEquals(label, expected.z, vector.z, TOLERANCE)
    }

    private fun assertUnknown(estimate: ObjectMotionEstimate) {
        assertEquals(ObjectMovementDirection.UNKNOWN, estimate.direction)
        assertNull(estimate.objectVelocityInAnchorMps)
        assertNull(estimate.relativeVelocityInAnchorMps)
        assertNull(estimate.cameraVelocityInAnchorMps)
        assertNull(estimate.objectSpeedMps)
        assertNull(estimate.relativeClosingSpeedMps)
        assertNull(estimate.observedAtMs)
        assertNull(estimate.referenceId)
    }

    private class Fixture(private val scenario: Scenario) {
        val tracker = ObjectTracker()
        lateinit var track: TrackState
            private set

        fun observe(
            atMs: Long,
            referenceId: Long = 42L,
            source: DepthSource = DepthSource.ARCORE_RAW_DEPTH,
            evidence: Evidence = Evidence.FRESH,
            depthObservationTimestampNs: Long? = depthClock(atMs),
            objectOffset: Vec3 = ZERO,
            detectorObservation: Boolean = true,
        ): ObjectMotionEstimate {
            val sample = sample(atMs, referenceId, objectOffset)
            track = if (detectorObservation) {
                tracker.update(listOf(sample.geometry), atMs).single { it.missedFrames == 0 }
            } else {
                tracker.applyTrackedObservations(listOf(track.trackId to sample.geometry), atMs).single()
            }
            val pose = when (evidence) {
                Evidence.FRESH -> sample.pose
                Evidence.POSE_MISSING, Evidence.REPROJECTED -> null
                Evidence.POSE_STALE -> sample.pose.copy(timestampMs = atMs - 33L)
            }
            val objectPosition = when (evidence) {
                Evidence.REPROJECTED -> track.distanceHistory.lastOrNull()?.objectPositionInAnchor
                Evidence.POSE_MISSING -> null
                else -> pose?.objectCenterInAnchor(sample.geometry.centerNorm, sample.depthM)
            }
            tracker.recordDistance(
                track, sample.depthM, source, 0.9f, atMs, pose, objectPosition,
                depthObservationTimestampNs = if (evidence == Evidence.REPROJECTED) {
                    track.distanceHistory.lastOrNull()?.depthObservationTimestampNs
                } else depthObservationTimestampNs,
                requireIndependentDepthObservation = true,
            )
            return estimate()
        }

        fun detectWithoutDepth(atMs: Long) {
            track = tracker.update(listOf(sample(atMs, referenceId = 42L).geometry), atMs)
                .single { it.missedFrames == 0 }
        }

        fun rejectRetrogradeDetection(atMs: Long) {
            val assignments = tracker.updateWithAssignments(
                listOf(IndexedObjectGeometry(0, sample(atMs, referenceId = 42L).geometry)), atMs,
            )
            assertTrue(assignments.isEmpty())
        }

        fun estimate(): ObjectMotionEstimate = ObjectMotionPolicy.estimate(track)

        fun kinematics(): ApproachKinematics {
            val observation = track.distanceHistory.last()
            return tracker.approachKinematics(track, observation.distanceM, observation.source)
        }

        private fun sample(atMs: Long, referenceId: Long, objectOffset: Vec3 = ZERO): Sample {
            val seconds = atMs / 1_000f
            val cameraPosition = scenario.cameraVelocity * seconds
            val objectPosition = OBJECT_START + scenario.objectVelocity * seconds + objectOffset
            val relative = objectPosition - cameraPosition
            val depth = -relative.z
            val center = Point2(0.5f + 0.7f * relative.x / depth, 0.5f - 0.7f * relative.y / depth)
            val bbox = RectNorm(center.x - 0.1f, center.y - 0.15f, 0.2f, 0.3f)
            val geometry = ObjectGeometry(
                className = "person", detectionConfidence = 0.9f,
                bboxNorm = bbox, polygonNorm = bboxPolygon(bbox, erosionRatio = 0f),
                maskAreaNorm = bbox.area, centerNorm = center,
                bottomContactNorm = Point2(center.x, bbox.y + bbox.height),
            )
            val pose = CameraPoseEvidence(
                referenceId = referenceId, timestampMs = atMs,
                positionX = cameraPosition.x, positionY = cameraPosition.y, positionZ = cameraPosition.z,
                forwardX = 0f, forwardY = 0f, forwardZ = -1f,
                imageProjection = CameraImageProjection(
                    imageWidth = 1_000, imageHeight = 1_000,
                    fx = 700f, fy = 700f, cx = 500f, cy = 500f,
                    rightX = 1f, rightY = 0f, rightZ = 0f,
                    upX = 0f, upY = 1f, upZ = 0f,
                ),
            )
            return Sample(geometry, pose, depth)
        }
    }

    private enum class Evidence { FRESH, POSE_MISSING, POSE_STALE, REPROJECTED }

    private data class Sample(val geometry: ObjectGeometry, val pose: CameraPoseEvidence, val depthM: Float)

    private data class Scenario(
        val name: String,
        val objectVelocity: Vec3,
        val cameraVelocity: Vec3,
        val direction: ObjectMovementDirection,
    )

    private companion object {
        const val TOLERANCE = 0.001f
        val ZERO = Vec3(0f, 0f, 0f)
        val OBJECT_START = Vec3(0f, 0f, -8f)
        val INCOMING = Scenario("incoming object", Vec3(0f, 0f, 0.8f), ZERO, ObjectMovementDirection.TOWARD_USER)
        val SCENARIOS = listOf(
            Scenario("stationary user and object", ZERO, ZERO, ObjectMovementDirection.STATIONARY),
            Scenario("user approaching fixed object", ZERO, Vec3(0f, 0f, -1f), ObjectMovementDirection.STATIONARY),
            INCOMING,
            Scenario("sideways object", Vec3(0.32f, 0f, 0f), ZERO, ObjectMovementDirection.RIGHT),
            Scenario("camera lateral motion only", ZERO, Vec3(0.6f, 0f, 0f), ObjectMovementDirection.STATIONARY),
            Scenario("incoming object and moving user", Vec3(0f, 0f, 0.8f), Vec3(0f, 0f, -0.5f), ObjectMovementDirection.TOWARD_USER),
        )

        fun timestamps(hz: Int, durationMs: Long): List<Long> =
            (0L..(durationMs * hz / 1_000L)).map { index -> index * 1_000L / hz }

        fun depthClock(atMs: Long): Long = 9_000_000_000L + atMs * 1_000_000L
    }
}
