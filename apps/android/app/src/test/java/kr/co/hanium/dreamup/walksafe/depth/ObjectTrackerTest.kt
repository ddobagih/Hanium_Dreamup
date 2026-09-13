package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectTrackerTest {
    @Test
    fun synchronizedEgoMotionSeparatesWalkingTowardStationaryObstacleFromIncomingObject() {
        assertEquals(ObjectMotion.USER_APPROACHING_STATIONARY, motionTrack(egoSpeedMps = 1f).second.objectMotion)
        assertEquals(ObjectMotion.OBJECT_APPROACHING, motionTrack(egoSpeedMps = 0f).second.objectMotion)
        assertEquals(ObjectMotion.OBJECT_APPROACHING, motionTrack(egoSpeedMps = 0.5f).second.objectMotion)
    }

    @Test
    fun relativeClosingWithoutEgoEvidenceNeverClaimsTheObjectIsMovingOrStationary() {
        val (_, kinematics) = motionTrack(egoSpeedMps = null)

        assertEquals(Trend.APPROACHING, kinematics.trend)
        assertNotNull(kinematics.timeToCollisionMs)
        assertEquals(ObjectMotion.UNKNOWN, kinematics.objectMotion)
    }

    @Test
    fun sidewaysMovingObjectIsNotCalledStationaryWhenItsAxialSpeedMatchesTheUser() {
        val depths = listOf(3.25f, 3f, 2.75f, 2.5f)
        val centers = depths.mapIndexed { index, z -> 0.5f + 0.7f * (0.32f * index * 0.25f) / z }
        val (_, motion) = motionTrack(1f, intervalMs = 250L, distances = depths, centersX = centers)

        assertEquals(Trend.APPROACHING, motion.trend)
        assertEquals(ObjectMotion.UNKNOWN, motion.objectMotion)
    }

    @Test
    fun motionAttributionRequiresFourUniqueObservationsSpanningAtLeastSixHundredMilliseconds() {
        val distances = listOf(4f, 3.8f, 3.6f, 3.4f)
        assertEquals(ObjectMotion.USER_APPROACHING_STATIONARY,
            motionTrack(1f, distances = distances, timestamps = listOf(0L, 200L, 400L, 600L)).second.objectMotion)
        assertEquals(ObjectMotion.UNKNOWN,
            motionTrack(1f, distances = distances, timestamps = listOf(0L, 200L, 400L, 599L)).second.objectMotion)
        assertEquals(ObjectMotion.UNKNOWN,
            motionTrack(1f, distances = listOf(4f, 3.7f, 3.4f), timestamps = listOf(0L, 300L, 600L)).second.objectMotion)
    }

    @Test
    fun motionClassificationRejectsShortNoisyMixedSourceOrMissingEvidence() {
        val rejected = listOf(
            motionTrack(egoSpeedMps = 1f, intervalMs = 100L),
            motionTrack(egoSpeedMps = 1f, distances = listOf(4f, 3.8f, 3.0f, 2.5f)),
            motionTrack(egoSpeedMps = Float.NaN),
            motionTrack(egoSpeedMps = 1f, lastSource = DepthSource.ARCORE_FULL_DEPTH),
            motionTrack(egoSpeedMps = 1f, poseTransform = { index, pose -> if (index == 3) null else pose }),
            motionTrack(egoSpeedMps = 1f, centerX = 0.2f),
            motionTrack(egoSpeedMps = 1f, poseTransform = { index, pose -> pose.copy(referenceId = index.toLong()) }),
            motionTrack(egoSpeedMps = 1f, poseTransform = { _, pose -> pose.copy(timestampMs = pose.timestampMs + 1L) }),
            motionTrack(egoSpeedMps = 1f, poseTransform = { index, pose ->
                if (index == 3) pose.copy(forwardX = 0.6f, forwardZ = -0.8f) else pose
            }),
            motionTrack(egoSpeedMps = 1f, poseTransform = { index, pose -> pose.copy(positionX = index.toFloat()) }),
            motionTrack(egoSpeedMps = 1f, poseTransform = { _, pose -> pose.copy(imageProjection = null) }),
            motionTrack(egoSpeedMps = 1f, poseTransform = { _, pose ->
                pose.copy(imageProjection = requireNotNull(pose.imageProjection).copy(fx = 0f))
            }),
        )

        rejected.forEach { (_, kinematics) -> assertEquals(ObjectMotion.UNKNOWN, kinematics.objectMotion) }
    }

    @Test
    fun repeatedOrOutOfOrderFrozenFramesDoNotCreateMetricMotionHistory() {
        val tracker = ObjectTracker()
        val shape = geometry(x = 0.40f, width = 0.20f)
        val track = tracker.update(listOf(shape), 1_000L).single()
        tracker.update(listOf(shape), 1_500L)
        tracker.update(listOf(shape), 2_000L)
        listOf(2_000L, 2_000L, 1_500L, 2_000L).forEachIndexed { index, at ->
            tracker.recordDistance(track, 4f - index * 0.5f, DepthSource.ARCORE_RAW_DEPTH, 0.9f, at, pose(at, 1f))
        }

        assertEquals(1, track.distanceHistory.size)
        assertEquals(4f, track.distanceHistory.single().distanceM, 0f)
        assertEquals(Trend.UNKNOWN, tracker.approachKinematics(track, 4f, DepthSource.ARCORE_RAW_DEPTH).trend)
    }

    private fun motionTrack(
        egoSpeedMps: Float?,
        intervalMs: Long = 500L,
        distances: List<Float> = listOf(4f, 3.5f, 3f, 2.5f),
        lastSource: DepthSource = DepthSource.ARCORE_RAW_DEPTH,
        centerX: Float = 0.5f,
        poseTransform: (Int, CameraPoseEvidence) -> CameraPoseEvidence? = { _, pose -> pose },
        timestamps: List<Long> = distances.indices.map { it * intervalMs },
        centersX: List<Float> = distances.indices.map { centerX },
    ): Pair<TrackState, ApproachKinematics> {
        val tracker = ObjectTracker()
        var latest: TrackState? = null
        distances.forEachIndexed { index, distance ->
            val at = timestamps[index]
            val track = tracker.update(listOf(geometry(x = centersX[index] - 0.1f, width = 0.2f)), at).single()
            val cameraPose = egoSpeedMps?.let { poseTransform(index, pose(at, it)) }
            tracker.recordDistance(
                track, distance,
                if (index == distances.lastIndex) lastSource else DepthSource.ARCORE_RAW_DEPTH,
                0.9f, at,
                cameraPose,
                cameraPose?.objectCenterInAnchor(Point2(centersX[index], 0.5f), distance),
                depthObservationTimestampNs = 9_000_000_000L + at * 1_000_000L,
            )
            latest = track
        }
        val track = requireNotNull(latest)
        return track to tracker.approachKinematics(track, distances.last(), lastSource)
    }

    private fun pose(at: Long, forwardSpeedMps: Float) = CameraPoseEvidence(
        referenceId = 1L, timestampMs = at,
        positionX = 0f, positionY = 0f, positionZ = -forwardSpeedMps * at / 1_000f,
        forwardX = 0f, forwardY = 0f, forwardZ = -1f,
        imageProjection = CameraImageProjection(
            imageWidth = 1_000, imageHeight = 1_000, fx = 700f, fy = 700f, cx = 500f, cy = 500f,
            rightX = 1f, rightY = 0f, rightZ = 0f, upX = 0f, upY = 1f, upZ = 0f,
        ),
    )

    @Test
    fun metricDistanceHistoryProducesApproachSpeedAndTtc() {
        val history = listOf(
            distance(at = 0L, meters = 4.0f),
            distance(at = 500L, meters = 3.2f),
            distance(at = 1_000L, meters = 2.4f),
        )

        val speed = ApproachSpeed.fromDistanceHistory(history)
        val ttc = TTC.fromMetricDistance(currentDistanceM = 2.4f, approachSpeed = speed)

        assertEquals(Trend.APPROACHING, speed.trend)
        assertEquals(1.6f, speed.metersPerSecond!!, 0.01f)
        assertEquals(1_500L, ttc.milliseconds)
        assertEquals(TtcSource.METRIC_DISTANCE, ttc.source)
    }

    @Test
    fun objectTrackerKeepsStableGeometryAndIgnoresPseudoDistanceAsMetricHistory() {
        val tracker = ObjectTracker()
        val geometry = geometry(x = 0.40f, width = 0.20f)
        val track = tracker.update(listOf(geometry), timestampMs = 0L).single()
        tracker.update(listOf(geometry(x = 0.405f, width = 0.20f)), timestampMs = 500L)
        val stableTrack = tracker.update(listOf(geometry(x = 0.410f, width = 0.20f)), timestampMs = 1_000L).single()

        tracker.recordDistance(
            track = stableTrack,
            distanceM = 1.0f,
            source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH,
            confidence = 0.9f,
            timestampMs = 1_000L,
        )

        assertEquals(track.trackId, stableTrack.trackId)
        assertTrue(stableTrack.stable)
        assertFalse(stableTrack.idSwitchSuspected)
        assertTrue(stableTrack.distanceHistory.isEmpty())
    }

    @Test
    fun idSwitchPolicyFlagsImplausibleGeometryJump() {
        val tracker = ObjectTracker()
        val track = tracker.update(listOf(geometry(x = 0.20f, width = 0.20f)), timestampMs = 0L).single()
        val policy = IdSwitchSuspicionPolicy()

        val suspicion = policy.assess(
            track = track,
            currentGeometry = geometry(x = 0.55f, width = 0.45f),
        )

        assertTrue(suspicion.suspected)
        assertEquals(track.trackId, suspicion.trackId)
        assertTrue(suspicion.confidence >= 0.5f)
    }

    @Test
    fun largeGeometryJumpStartsSeparateTrackInsteadOfMerging() {
        val tracker = ObjectTracker()
        tracker.update(listOf(geometry(x = 0.20f, width = 0.20f)), timestampMs = 0L)
        val active = tracker.update(listOf(geometry(x = 0.80f, width = 0.20f)), timestampMs = 500L)

        assertEquals(2, active.size)
        assertFalse(active.any { it.idSwitchSuspected })
    }

    @Test
    fun duplicateDetectionsAreNotCollapsedBeforeTracking() {
        val tracker = ObjectTracker()
        val duplicate = geometry(x = 0.30f, width = 0.20f)

        val first = tracker.update(listOf(duplicate, duplicate), timestampMs = 0L)
        val next = tracker.update(listOf(duplicate, duplicate), timestampMs = 500L)
            .filter { it.missedFrames == 0 }

        assertEquals(2, first.size)
        assertEquals(2, next.size)
        assertEquals(2, next.map { it.trackId }.toSet().size)
    }

    @Test
    fun detectorObservationGapStartsAFreshUnstableTrack() {
        val tracker = ObjectTracker()
        val geometry = geometry(x = 0.30f, width = 0.20f)
        val first = tracker.update(listOf(geometry), timestampMs = 1_000L).single()
        tracker.update(listOf(geometry), timestampMs = 1_500L)

        val afterStall = tracker.update(listOf(geometry), timestampMs = 3_001L).single()

        assertFalse(first.trackId == afterStall.trackId)
        assertEquals(1, afterStall.ageFrames)
        assertFalse(afterStall.stable)
    }

    @Test
    fun reappearanceAfterAnyMissStartsAFreshUnstableTrack() {
        val tracker = ObjectTracker()
        val geometry = geometry(x = 0.30f, width = 0.20f)
        val first = tracker.update(listOf(geometry), timestampMs = 1_000L).single()

        tracker.update(emptyList(), timestampMs = 1_100L)
        val reappeared = tracker.update(listOf(geometry), timestampMs = 1_200L)
            .single { it.missedFrames == 0 }

        assertFalse(first.trackId == reappeared.trackId)
        assertEquals(1, reappeared.ageFrames)
        assertFalse(reappeared.stable)
    }

    @Test
    fun reappearedTrackDoesNotInheritMetricHistoryOrTtc() {
        val tracker = ObjectTracker()
        val geometry = geometry(x = 0.30f, width = 0.20f)
        val distances = listOf(4.0f, 3.5f, 3.0f, 2.5f)
        var previousTrack: TrackState? = null
        distances.forEachIndexed { index, distanceM ->
            val timestampMs = index * 500L
            val track = tracker.update(listOf(geometry), timestampMs).single()
            tracker.recordDistance(
                track = track,
                distanceM = distanceM,
                source = DepthSource.ARCORE_RAW_DEPTH,
                confidence = 0.90f,
                timestampMs = timestampMs,
            )
            previousTrack = track
        }
        val oldTrack = requireNotNull(previousTrack)

        tracker.update(emptyList(), timestampMs = 1_600L)
        val reappeared = tracker.update(listOf(geometry), timestampMs = 1_700L)
            .single { it.missedFrames == 0 }
        val kinematics = tracker.approachKinematics(
            track = reappeared,
            currentDistanceM = 2.0f,
            source = DepthSource.ARCORE_RAW_DEPTH,
        )

        assertFalse(oldTrack.trackId == reappeared.trackId)
        assertTrue(reappeared.distanceHistory.isEmpty())
        assertEquals(Trend.UNKNOWN, kinematics.trend)
        assertEquals(0f, kinematics.approachScore, 0.001f)
        assertNull(kinematics.timeToCollisionMs)
    }

    @Test
    fun overlappingImplausibleAreaOrCenterChangeStartsNewTrack() {
        val cases = listOf(
            geometry(x = 0.30f, width = 0.40f) to geometry(x = 0.46f, width = 0.08f),
            geometry(x = 0.00f, width = 0.80f) to geometry(x = 0.55f, width = 0.45f),
        )

        cases.forEach { (firstGeometry, implausibleGeometry) ->
            val tracker = ObjectTracker()
            val first = tracker.update(listOf(firstGeometry), timestampMs = 0L).single()

            val current = tracker.update(listOf(implausibleGeometry), timestampMs = 500L)
                .single { it.missedFrames == 0 }

            assertFalse(first.trackId == current.trackId)
            assertEquals(1, current.ageFrames)
            assertFalse(current.stable)
            assertFalse(current.idSwitchSuspected)
        }
    }

    @Test
    fun oneDetectionAmbiguousBetweenTwoTracksStartsWithoutMetricHistoryOrTtc() {
        val tracker = ObjectTracker()
        val left = geometry(x = 0.20f, width = 0.20f)
        val right = geometry(x = 0.60f, width = 0.20f)
        val leftDistances = listOf(4.0f, 3.5f, 3.0f, 2.5f)
        val rightDistances = listOf(6.0f, 6.2f, 6.4f, 6.6f)
        var oldTrackIds = emptySet<String>()
        leftDistances.indices.forEach { index ->
            val timestampMs = index * 500L
            val visible = tracker.update(listOf(left, right), timestampMs)
                .filter { it.missedFrames == 0 }
            visible.forEach { track ->
                val isLeft = requireNotNull(track.latestGeometry).centerNorm.x < 0.5f
                tracker.recordDistance(
                    track = track,
                    distanceM = if (isLeft) leftDistances[index] else rightDistances[index],
                    source = DepthSource.ARCORE_RAW_DEPTH,
                    confidence = 0.90f,
                    timestampMs = timestampMs,
                )
            }
            oldTrackIds = visible.map { it.trackId }.toSet()
        }

        val fresh = tracker.update(
            listOf(geometry(x = 0.25f, width = 0.50f)),
            timestampMs = 2_000L,
        ).single { it.missedFrames == 0 }
        val kinematics = tracker.approachKinematics(
            track = fresh,
            currentDistanceM = 2.0f,
            source = DepthSource.ARCORE_RAW_DEPTH,
        )

        assertFalse(fresh.trackId in oldTrackIds)
        assertEquals(1, fresh.ageFrames)
        assertTrue(fresh.distanceHistory.isEmpty())
        assertEquals(Trend.UNKNOWN, kinematics.trend)
        assertNull(kinematics.timeToCollisionMs)
    }

    @Test
    fun reducedDetectionCountDoesNotReuseStrongAnchorFromAmbiguousTracks() {
        val tracker = ObjectTracker()
        val left = geometry(x = 0.20f, width = 0.20f)
        val right = geometry(x = 0.45f, width = 0.20f)
        val leftDistances = listOf(4.0f, 3.5f, 3.0f, 2.5f)
        val rightDistances = listOf(6.0f, 6.2f, 6.4f, 6.6f)
        var oldTrackIds = emptySet<String>()
        leftDistances.indices.forEach { index ->
            val timestampMs = index * 500L
            val visible = tracker.update(listOf(left, right), timestampMs)
                .filter { it.missedFrames == 0 }
            visible.forEach { track ->
                val isLeft = requireNotNull(track.latestGeometry).centerNorm.x < 0.5f
                tracker.recordDistance(
                    track = track,
                    distanceM = if (isLeft) leftDistances[index] else rightDistances[index],
                    source = DepthSource.ARCORE_RAW_DEPTH,
                    confidence = 0.90f,
                    timestampMs = timestampMs,
                )
            }
            oldTrackIds = visible.map { it.trackId }.toSet()
        }

        val fresh = tracker.update(
            listOf(geometry(x = 0.25f, width = 0.20f)),
            timestampMs = 2_000L,
        ).single { it.missedFrames == 0 }
        val kinematics = tracker.approachKinematics(
            track = fresh,
            currentDistanceM = 2.0f,
            source = DepthSource.ARCORE_RAW_DEPTH,
        )

        assertFalse(fresh.trackId in oldTrackIds)
        assertEquals(1, fresh.ageFrames)
        assertFalse(fresh.stable)
        assertTrue(fresh.distanceHistory.isEmpty())
        assertEquals(Trend.UNKNOWN, kinematics.trend)
        assertNull(kinematics.timeToCollisionMs)
    }

    @Test
    fun sameCountReplacementDoesNotReuseAmbiguousStrongAnchor() {
        val tracker = ObjectTracker()
        val left = geometry(x = 0.20f, width = 0.20f)
        val right = geometry(x = 0.45f, width = 0.20f)
        val leftDistances = listOf(4.0f, 3.5f, 3.0f, 2.5f)
        val rightDistances = listOf(6.0f, 6.2f, 6.4f, 6.6f)
        var oldTrackIds = emptySet<String>()
        leftDistances.indices.forEach { index ->
            val timestampMs = index * 500L
            val visible = tracker.update(listOf(left, right), timestampMs)
                .filter { it.missedFrames == 0 }
            visible.forEach { track ->
                val isLeft = requireNotNull(track.latestGeometry).centerNorm.x < 0.5f
                tracker.recordDistance(
                    track = track,
                    distanceM = if (isLeft) leftDistances[index] else rightDistances[index],
                    source = DepthSource.ARCORE_RAW_DEPTH,
                    confidence = 0.90f,
                    timestampMs = timestampMs,
                )
            }
            oldTrackIds = visible.map { it.trackId }.toSet()
        }

        val current = tracker.update(
            listOf(
                geometry(x = 0.25f, width = 0.20f),
                geometry(x = 0.80f, width = 0.20f),
            ),
            timestampMs = 2_000L,
        ).filter { it.missedFrames == 0 }
        val ambiguous = current.single {
            requireNotNull(it.latestGeometry).centerNorm.x < 0.5f
        }
        val kinematics = tracker.approachKinematics(
            track = ambiguous,
            currentDistanceM = 2.0f,
            source = DepthSource.ARCORE_RAW_DEPTH,
        )

        assertEquals(2, current.size)
        assertTrue(current.none { it.trackId in oldTrackIds })
        assertEquals(1, ambiguous.ageFrames)
        assertFalse(ambiguous.stable)
        assertTrue(ambiguous.distanceHistory.isEmpty())
        assertEquals(Trend.UNKNOWN, kinematics.trend)
        assertNull(kinematics.timeToCollisionMs)
    }

    @Test
    fun ambiguousTwoObjectCrossingDoesNotReuseEitherOldTrackId() {
        val tracker = ObjectTracker()
        val oldTrackIds = tracker.update(
            listOf(
                geometry(x = 0.20f, width = 0.20f),
                geometry(x = 0.60f, width = 0.20f),
            ),
            timestampMs = 0L,
        ).map { it.trackId }.toSet()

        val crossing = tracker.update(
            listOf(
                geometry(x = 0.35f, width = 0.20f),
                geometry(x = 0.45f, width = 0.20f),
            ),
            timestampMs = 500L,
        ).filter { it.missedFrames == 0 }

        assertEquals(2, crossing.size)
        assertTrue(crossing.none { it.trackId in oldTrackIds })
        assertTrue(crossing.all { it.ageFrames == 1 && !it.stable })
    }

    @Test
    fun nearbyStationaryObjectsKeepPositionBoundIdsAndBecomeStable() {
        val tracker = ObjectTracker()
        val detections = listOf(
            geometry(x = 0.20f, width = 0.20f),
            geometry(x = 0.45f, width = 0.20f),
        )
        val first = tracker.update(detections, timestampMs = 0L)
        val firstLeft = first.single { requireNotNull(it.latestGeometry).centerNorm.x < 0.5f }
        val firstRight = first.single { requireNotNull(it.latestGeometry).centerNorm.x > 0.5f }

        tracker.update(detections, timestampMs = 500L)
        val third = tracker.update(detections, timestampMs = 1_000L)
            .filter { it.missedFrames == 0 }
        val thirdLeft = third.single { requireNotNull(it.latestGeometry).centerNorm.x < 0.5f }
        val thirdRight = third.single { requireNotNull(it.latestGeometry).centerNorm.x > 0.5f }

        assertEquals(firstLeft.trackId, thirdLeft.trackId)
        assertEquals(firstRight.trackId, thirdRight.trackId)
        assertEquals(3, thirdLeft.ageFrames)
        assertEquals(3, thirdRight.ageFrames)
        assertTrue(thirdLeft.stable)
        assertTrue(thirdRight.stable)
    }

    @Test
    fun separatedObjectsKeepPositionBoundIdsWhenDetectionOrderChanges() {
        val tracker = ObjectTracker()
        val first = tracker.update(
            listOf(
                geometry(x = 0.10f, width = 0.15f),
                geometry(x = 0.75f, width = 0.15f),
            ),
            timestampMs = 0L,
        )
        val firstLeft = first.single { requireNotNull(it.latestGeometry).centerNorm.x < 0.5f }
        val firstRight = first.single { requireNotNull(it.latestGeometry).centerNorm.x > 0.5f }

        val reordered = tracker.update(
            listOf(
                geometry(x = 0.74f, width = 0.15f),
                geometry(x = 0.11f, width = 0.15f),
            ),
            timestampMs = 500L,
        ).filter { it.missedFrames == 0 }
        val currentLeft = reordered.single { requireNotNull(it.latestGeometry).centerNorm.x < 0.5f }
        val currentRight = reordered.single { requireNotNull(it.latestGeometry).centerNorm.x > 0.5f }

        assertEquals(firstLeft.trackId, currentLeft.trackId)
        assertEquals(firstRight.trackId, currentRight.trackId)
    }

    @Test
    fun riskPolicyUsesTtcAlertBoundary() {
        val policy = TrackingRiskPolicy()
        val boundary = policy.evaluate(riskSignals(ttcMs = 10_000L))
        val awareBoundary = policy.evaluate(riskSignals(ttcMs = 30_000L))
        val outsideBoundary = policy.evaluate(riskSignals(ttcMs = 30_001L))

        assertTrue(boundary.alertable)
        assertEquals(TrackingRiskType.APPROACHING_OBJECT, boundary.riskType)
        assertFalse(awareBoundary.alertable)
        assertEquals(TrackingRiskType.APPROACHING_OBJECT, awareBoundary.riskType)
        assertEquals(MessageLevel.AWARE, awareBoundary.messageLevel)
        assertFalse(outsideBoundary.alertable)
        assertEquals(TrackingRiskType.DISPLAY_ONLY, outsideBoundary.riskType)
    }

    @Test
    fun closeMetricDistanceBecomesBlockingRiskButPseudoDistanceDoesNot() {
        val policy = TrackingRiskPolicy()
        val metric = policy.evaluate(
            TrackRiskSignals(
                stableFrames = 3,
                stableMs = 700L,
                distance = distance(at = 0L, meters = 1.1f),
                trend = Trend.STABLE,
                timeToCollision = TtcEstimate(null, 0f, TtcSource.NONE, "none"),
                areaGrowthRatio = 0f,
                areaGrowthPerSecond = 0f,
            ),
        )
        val pseudo = policy.evaluate(
            TrackRiskSignals(
                stableFrames = 3,
                stableMs = 1_000L,
                distance = DistanceObservation(
                    timestampMs = 1_000L,
                    distanceM = 1.0f,
                    source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH,
                    confidence = 0.9f,
                ),
                trend = Trend.STABLE,
                timeToCollision = TtcEstimate(null, 0f, TtcSource.NONE, "none"),
                areaGrowthRatio = 0f,
                areaGrowthPerSecond = 0f,
            ),
        )

        assertTrue(metric.alertable)
        assertEquals(TrackingRiskType.BLOCKING_OBJECT, metric.riskType)
        assertEquals(MessageLevel.STOP, metric.messageLevel)
        assertFalse(pseudo.alertable)
    }

    @Test
    fun bboxScaleTtcUsesGrowingBoxAsClosingSignal() {
        val ttc = TTC.fromBboxScale(
            bboxHistory = listOf(
                RectNorm(0.4f, 0.4f, 0.10f, 0.10f),
                RectNorm(0.375f, 0.375f, 0.15f, 0.15f),
                RectNorm(0.35f, 0.35f, 0.20f, 0.20f),
            ),
            observedAtHistory = listOf(0L, 500L, 1_000L),
        )

        assertNotNull(ttc.milliseconds)
        assertTrue(ttc.milliseconds!! > 0L)
        assertEquals(TtcSource.BBOX_SCALE, ttc.source)
    }

    private fun distance(at: Long, meters: Float): DistanceObservation = DistanceObservation(
        timestampMs = at,
        distanceM = meters,
        source = DepthSource.ARCORE_RAW_DEPTH,
        confidence = 0.90f,
    )

    private fun riskSignals(ttcMs: Long): TrackRiskSignals = TrackRiskSignals(
        stableFrames = 3,
        stableMs = 1_000L,
        distance = null,
        trend = Trend.APPROACHING,
        timeToCollision = TtcEstimate(ttcMs, 0.7f, TtcSource.BBOX_SCALE, "test"),
        areaGrowthRatio = 0f,
        areaGrowthPerSecond = 0f,
    )

    private fun geometry(x: Float, width: Float): ObjectGeometry {
        val bbox = RectNorm(x = x, y = 0.35f, width = width, height = 0.30f)
        return ObjectGeometry(
            className = "person",
            detectionConfidence = 0.90f,
            bboxNorm = bbox,
            polygonNorm = bboxPolygon(bbox, erosionRatio = 0f),
            maskAreaNorm = bbox.area,
            centerNorm = bbox.center,
            bottomContactNorm = Point2(bbox.center.x, bbox.y + bbox.height),
        )
    }
}
