package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S03-6: a disabled camera, location or model feature is never reported as READY. */
class WalkSessionFeatureReadinessTest {
    private val ready = WalkSessionReadinessStatus.READY to ""

    @Test
    fun disabledCameraGuidanceIsUnavailableEvenWithPermission() {
        val result = WalkSessionFeatureReadiness.camera(
            guidanceEnabled = false,
            permissionGranted = true,
            capability = ready,
        )
        assertEquals(WalkSessionReadinessStatus.UNAVAILABLE, result.first)
        assertEquals("camera_guidance_disabled", result.second)
    }

    @Test
    fun missingCameraPermissionIsUnavailableAndNamesThePermission() {
        val result = WalkSessionFeatureReadiness.camera(
            guidanceEnabled = false,
            permissionGranted = false,
            capability = ready,
        )
        assertEquals(WalkSessionReadinessStatus.UNAVAILABLE, result.first)
        assertEquals("camera_permission_missing", result.second)
    }

    @Test
    fun enabledCameraFollowsTheStartupCapability() {
        assertEquals(
            ready,
            WalkSessionFeatureReadiness.camera(true, true, ready),
        )
        val pending = WalkSessionReadinessStatus.PENDING to "startup_pending:CAMERA"
        assertEquals(pending, WalkSessionFeatureReadiness.camera(true, true, pending))
        val unavailable = WalkSessionReadinessStatus.UNAVAILABLE to "startup_unavailable:CAMERA"
        assertEquals(
            WalkSessionReadinessStatus.UNAVAILABLE to
                "camera_guidance_disabled,startup_unavailable:CAMERA",
            WalkSessionFeatureReadiness.camera(false, true, unavailable),
        )
    }

    @Test
    fun disabledLocationGuidanceIsUnavailable() {
        assertEquals(
            WalkSessionReadinessStatus.UNAVAILABLE to "location_guidance_disabled",
            WalkSessionFeatureReadiness.location(false, true, ready),
        )
        assertEquals(
            WalkSessionReadinessStatus.UNAVAILABLE to "precise_location_permission_missing",
            WalkSessionFeatureReadiness.location(false, false, ready),
        )
        assertEquals(ready, WalkSessionFeatureReadiness.location(true, true, ready))
    }

    @Test
    fun modelIsUnavailableWhenCameraGuidanceIsDisabledOrTheDetectorIsMissing() {
        assertEquals(
            WalkSessionReadinessStatus.UNAVAILABLE to "model_unavailable:camera_guidance_disabled",
            WalkSessionFeatureReadiness.model(
                cameraGuidanceEnabled = false,
                detectorAvailable = true,
                detectorLoadReason = null,
            ),
        )
        assertEquals(
            WalkSessionReadinessStatus.UNAVAILABLE to "model_unavailable:not_loaded",
            WalkSessionFeatureReadiness.model(true, false, null),
        )
        assertEquals(ready, WalkSessionFeatureReadiness.model(true, true, "loaded"))
    }

    @Test
    fun disabledCameraBlocksBothApprovedModesWhileDistanceAloneOnlyLimits() {
        val epoch = WalkRuntimeEpoch("walk-1", 0)
        listOf(WalkSessionMode.FULL, WalkSessionMode.DISTANCE_LIMITED).forEach { mode ->
            val snapshot = snapshot(epoch, mode) { requirement ->
                when (requirement) {
                    WalkSessionReadinessRequirement.CAMERA ->
                        WalkSessionFeatureReadiness.camera(false, true, ready)
                    WalkSessionReadinessRequirement.MODEL ->
                        WalkSessionFeatureReadiness.model(false, true, null)
                    else -> ready
                }
            }
            assertFalse(snapshot.isReady)
            assertTrue(snapshot.hasUnavailableRequirement)
            assertTrue(snapshot.blockingReasons.contains("camera_guidance_disabled"))
        }
        val distanceLimited = snapshot(epoch, WalkSessionMode.DISTANCE_LIMITED) { ready }
        assertEquals(
            WalkSessionReadinessStatus.NOT_REQUIRED,
            distanceLimited.observations.getValue(WalkSessionReadinessRequirement.METRIC_DISTANCE)
                .status,
        )
        assertTrue(distanceLimited.isReady)
    }

    private fun snapshot(
        epoch: WalkRuntimeEpoch,
        mode: WalkSessionMode,
        status: (WalkSessionReadinessRequirement) -> Pair<WalkSessionReadinessStatus, String>,
    ): WalkSessionReadinessSnapshot {
        val collector = WalkSessionReadinessCollector(
            epoch,
            WalkSessionReadinessPlan(WalkSessionAction.START_WALK, mode),
        )
        collector.plan.requiredRequirements.forEach { requirement ->
            val (value, reason) = status(requirement)
            collector.record(WalkSessionReadinessObservation(epoch, requirement, value, reason))
        }
        return collector.build(assessedAtElapsedRealtimeMs = 10L)
    }
}
