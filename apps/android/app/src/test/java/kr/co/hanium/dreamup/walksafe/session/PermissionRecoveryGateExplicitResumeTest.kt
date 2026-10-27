package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S02-16: an explicit resume is bound to the permission observation taken at the tap. */
class PermissionRecoveryGateExplicitResumeTest {
    private val required = setOf(ObservedPermission.CAMERA, ObservedPermission.PRECISE_LOCATION)

    @Test
    fun awaitingApprovalWithCurrentPermissionsClearsTheGate() {
        val gate = awaitingGate().acknowledgeExplicitResume(
            observed = observed(camera = true, location = true),
            requiredPermissions = required,
        )
        assertEquals(PermissionRecoveryGateState.CLEAR, gate.state)
        assertFalse(gate.blocksAutomaticResourceStart)
    }

    @Test
    fun permissionLostAfterTheRecheckKeepsResourcesBlocked() {
        val gate = awaitingGate().acknowledgeExplicitResume(
            observed = observed(camera = false, location = true),
            requiredPermissions = required,
        )
        assertEquals(PermissionRecoveryGateState.BLOCKED, gate.state)
        assertEquals(setOf(ObservedPermission.CAMERA), gate.affectedPermissions)
        assertTrue(gate.blocksAutomaticResourceStart)
    }

    @Test
    fun onlyTheAwaitingStateCanBeAcknowledged() {
        val granted = observed(camera = true, location = true)
        PermissionRecoveryGateState.entries
            .filter { it != PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME }
            .forEach { state ->
                val gate = PermissionRecoveryGate(state = state)
                assertEquals(gate, gate.acknowledgeExplicitResume(granted, required))
            }
    }

    @Test
    fun optionalPermissionsDoNotBlockTheApproval() {
        val gate = awaitingGate().acknowledgeExplicitResume(
            observed = observed(camera = true, location = true, microphone = false),
            requiredPermissions = required,
        )
        assertEquals(PermissionRecoveryGateState.CLEAR, gate.state)
    }

    @Test
    fun revokingAnyRequiredPermissionAfterRecheckBlocksResume() {
        val required = WalkStartPermissionPolicy.required(activityRecognitionRequired = true)
        for (revoked in required) {
            val current = ObservedPermissionSnapshot(
                cameraGranted = revoked != ObservedPermission.CAMERA,
                preciseLocationGranted = revoked != ObservedPermission.PRECISE_LOCATION,
                microphoneGranted = revoked != ObservedPermission.MICROPHONE,
                activityRecognitionGranted = revoked != ObservedPermission.ACTIVITY_RECOGNITION,
            )
            val gate = awaitingGate().acknowledgeExplicitResume(current, required)
            assertEquals(PermissionRecoveryGateState.BLOCKED, gate.state)
            assertEquals(setOf(revoked), gate.affectedPermissions)
            assertTrue(gate.blocksAutomaticResourceStart)
        }
    }

    @Test
    fun mainInteractionRecoversWithoutClearingWalkGateAndRevocationBlocksAgain() {
        val required = WalkStartPermissionPolicy.required(activityRecognitionRequired = false)
        val granted = observed(camera = true, location = true)
        for (state in PermissionRecoveryGateState.entries.filter { it != PermissionRecoveryGateState.CLEAR }) {
            val gate = PermissionRecoveryGate(state)
            assertFalse(gate.blocksInteraction(false, granted, required))
            assertTrue(gate.blocksInteraction(true, granted, required))
            for (permission in required) {
                val revoked = ObservedPermissionSnapshot(
                    cameraGranted = permission != ObservedPermission.CAMERA,
                    preciseLocationGranted = permission != ObservedPermission.PRECISE_LOCATION,
                    microphoneGranted = permission != ObservedPermission.MICROPHONE,
                    activityRecognitionGranted = true,
                )
                assertTrue(gate.blocksInteraction(false, revoked, required))
            }
            assertEquals(state, gate.state)
            assertTrue("Interaction access never certifies walk readiness", gate.blocksAutomaticResourceStart)
        }
    }

    private fun awaitingGate(): PermissionRecoveryGate =
        PermissionRecoveryGate().blocked(required).recheckRequired().completeFullRecheck(
            observed = observed(camera = true, location = true),
            requiredPermissions = required,
            allPrerequisitesReady = true,
        ).also { assertEquals(PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME, it.state) }

    private fun observed(
        camera: Boolean,
        location: Boolean,
        microphone: Boolean = true,
    ) = ObservedPermissionSnapshot(
        cameraGranted = camera,
        preciseLocationGranted = location,
        microphoneGranted = microphone,
        activityRecognitionGranted = true,
    )
}
