package kr.co.hanium.dreamup.walksafe.session.permission

import kr.co.hanium.dreamup.walksafe.navigation.start.NativeGuidanceTestFixture
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/** Real permission/prewalk controllers share actor, generation and epoch. Platform checks,
 * first-run admission and sensor results are controlled leaves; Main/native probes are not run. */
class PermissionRecoveryPreparationTest {
    @Test fun destinationlessMeasurementsCompleteRecheckButStillRequireExplicitResume() {
        val (permissions, native) = connected()
        permissions.flow.onPermissionDenialConfirmed()
        assertNull(native.workflow.pendingNativeUiPage)
        assertNull(requireNotNull(native.workflow.nativePhoneMountingCheckRequest).destination)
        assertEquals(PermissionRecoveryGateState.RECHECK_REQUIRED, permissions.flow.permissionRecoveryGate.state)
        native.completeReadiness()
        native.walk = native.walk.copy(readiness = null)
        native.prewalk.continueFeaturePreparation()
        assertEquals(PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME, permissions.flow.permissionRecoveryGate.state)
        assertTrue(permissions.flow.permissionRecoveryGate.blocksAutomaticResourceStart)
        assertFalse(permissions.events.contains("startAfterRecovery"))
        assertFalse(native.events.contains("completeStartupConfirmation"))
        assertTrue(native.startupPosts.isEmpty())
        permissions.flow.onPermissionDenialConfirmed()
        assertEquals(PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME, permissions.flow.permissionRecoveryGate.state)
        assertTrue(permissions.flow.resumePermissionRecoveryFromExplicitUserAction())
        assertEquals(PermissionRecoveryGateState.CLEAR, permissions.flow.permissionRecoveryGate.state)
        assertTrue(permissions.events.contains("startAfterRecovery"))
    }

    @Test fun retiredMeasurementOwnerCannotCompleteThePermissionRecheck() {
        val (permissions, native) = connected()
        permissions.flow.onPermissionDenialConfirmed()
        native.completeReadiness()
        native.gatewayGeneration++
        native.prewalk.continueFeaturePreparation()
        assertNull(native.workflow.nativePhoneMountingCheckRequest)
        assertEquals(PermissionRecoveryGateState.RECHECK_REQUIRED, permissions.flow.permissionRecoveryGate.state)
        assertTrue(permissions.flow.permissionRecoveryGate.blocksAutomaticResourceStart)
        assertFalse(permissions.events.contains("startAfterRecovery"))
    }

    private fun connected(): Pair<PermissionFlowTestFixture, NativeGuidanceTestFixture> {
        val permissions = PermissionFlowTestFixture()
        val native = NativeGuidanceTestFixture()
        permissions.lifecycle = WalkSessionLifecycle({ "walk" }, { native.now }, { native.now }).also {
            it.handle(WalkSessionEvent.EnteredForeground)
        }
        permissions.publishGateway()
        native.actor = permissions.actor
        native.gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
        native.walk = permissions.lifecycle.snapshot()
        permissions.onBlockReason = {
            permissions.block = if (native.readinessStatus == WalkSessionReadinessStatus.READY)
                null else "환경과 장착 미확인"
        }
        permissions.afterRecoveryPreparation = { native.prewalk.beginPermissionRecoveryRecheck() }
        native.afterPermissionRecoveryRecheck = { permissions.flow.completePermissionRecoveryRecheckIfPossible() }
        permissions.flow.enterPermissionRecoveryBarrier(setOf(ObservedPermission.CAMERA), "test")
        permissions.flow.completePermissionRecoveryRecheckIfPossible()
        permissions.events.clear()
        native.events.clear()
        return permissions to native
    }
}
