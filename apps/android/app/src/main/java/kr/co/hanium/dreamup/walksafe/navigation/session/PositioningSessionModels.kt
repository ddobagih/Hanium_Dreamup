package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.device.PhoneMountingAssessment
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingUserConfirmation
import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HeadingObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionStateOwner
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingSpeedObservation
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

internal class PositioningCollectionBinding(
        val coordinator: PositioningCoordinator,
        val inputGeneration: Long,
        val owner: LocationCollectionOwner,
        val locationGeneration: Int,
        val startedAtElapsedRealtimeMs: Long,
    ) {
        // The first counter delta may span the previous collection boundary.
        var counterBoundaryEstablished = false
    }

internal data class LocationCollectionOwner(
        val actorId: String?,
        val gatewaySession: GatewayFieldSession?,
        val gatewayGeneration: Long,
        val fieldWalkEpoch: WalkRuntimeEpoch? = null,
        val fieldScopeId: String? = null,
    )

internal data class PositioningMountTarget(
        val binding: PositioningCollectionBinding,
        val motionOwner: UserMotionStateOwner,
    )

internal data class PositioningMountObservation(
        val target: PositioningMountTarget,
        val observationGeneration: Long,
        val confirmation: PhoneMountingUserConfirmation?,
        val explicitConfirmation: PhoneMountingUserConfirmation?,
        val assessment: PhoneMountingAssessment?,
        val observedAtElapsedRealtimeMs: Long,
    )

internal data class GpsMotionInputs(
        val speed: WalkingSpeedObservation?,
        val course: HeadingObservation?,
        val evidence: UserMotionLocationEvidence,
    )

internal data class WalkingCalibrationScope(val actorId: String?, val walkEpoch: WalkRuntimeEpoch?)
