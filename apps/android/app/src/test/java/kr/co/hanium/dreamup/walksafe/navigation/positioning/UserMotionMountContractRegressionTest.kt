package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.*
import org.junit.Test

/** Public output contract: inspecting its getter also compiles against the pre-feature product. */
class UserMotionMountContractRegressionTest {
    @Test fun absentMountEvidenceIsUnknownInEstimatorAndCoordinatorOutputs() {
        val coordinator = PositioningCoordinator()
        val outputs = listOf(UserMotionStateEstimator().snapshot(0L),
            coordinator.currentUserMotion(0L), requireNotNull(coordinator.evaluate(0L).userMotion))
        outputs.forEach { state ->
            val getter = state.javaClass.methods.firstOrNull {
                it.name == "getMount" && it.parameterCount == 0
            }
            assertNotNull("The common motion snapshot must preserve the device-body relation axis", getter)
            assertEquals("UNKNOWN", getter!!.invoke(state).toString())
            assertEquals(UserMovementState.UNKNOWN, state.movement)
            assertEquals(UserLocomotionState.UNKNOWN, state.locomotion)
            assertEquals(UserDeviceRotationState.UNKNOWN, state.deviceRotation)
            assertFalse(state.zeroVelocityUpdateAllowed)
            assertNull(state.expiresAtElapsedRealtimeMs)
        }
    }
}
