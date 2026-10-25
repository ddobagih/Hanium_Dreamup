package kr.co.hanium.dreamup.walksafe.navigation.start

import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage
import org.junit.Assert.*
import org.junit.Test

class NativeDestinationStartAdmissionTest {
    @Test fun initialOrdinaryRegistrationCanBeAdoptedOnceWithoutReplacingUserApproval() {
        val f = Fixture()
        val current = f.admission.capture(f.common.destination)!!
        assertTrue(current())
        f.generation++
        f.binding = f.binding(Any())
        assertTrue(current())
        val original = f.binding
        f.binding = f.binding(Any())
        assertFalse(current())
        assertNotSame(original!!.identity, f.binding!!.identity)
    }

    @Test fun stopRestartGenerationJumpCannotAdoptTheEarlierApproval() {
        val f = Fixture()
        val current = f.admission.capture(f.common.destination)!!
        f.generation += 2
        f.binding = f.binding(Any())
        assertFalse(current())
        assertEquals(0, f.collectionReads)
    }

    @Test fun differentGatewaySessionWithEqualValuesCannotRemainCurrent() {
        val f = Fixture()
        f.session = String(charArrayOf('s'))
        val current = f.admission.capture(f.common.destination)!!
        f.session = String(charArrayOf('s'))
        assertFalse(current())
        assertEquals(0, f.collectionReads)
    }

    @Test fun sourceAdmissionChecksNativeCollectionOnlyAfterReferenceAndGenerationMatch() {
        val f = Fixture()
        f.binding = f.binding(Any())
        val current = f.admission.capture(f.common.destination)!!
        f.generation++
        assertFalse(current())
        assertEquals(0, f.collectionReads)
    }

    @Test fun equalDestinationCopyCannotReuseAnExplicitStartForAnotherObject() {
        val f = Fixture()
        val current = f.admission.capture(f.common.destination)!!
        f.common.workflow.pendingUiDestination = f.common.destination.copy()
        assertFalse(current())
    }

    @Test fun changedPermissionGateOrRouteGenerationRetiresTheCapturedStart() {
        val f = Fixture()
        var current = f.admission.capture(f.common.destination)!!
        f.permission = Any()
        assertFalse(current())
        current = f.admission.capture(f.common.destination)!!
        f.routeGeneration++
        assertFalse(current())
    }

    private class Fixture {
        val common = NativeGuidanceTestFixture()
        var generation = 8
        var session: Any = Any()
        var permission: Any = Any()
        var routeGeneration = 7
        var collectionReads = 0
        var binding: NativeDestinationStartAdmission.Binding? = null
        val admission = NativeDestinationStartAdmission(common.workflow,
            object : NativeDestinationStartAdmission.Scope {
                override fun homeAvailable() = true
                override fun runtimeActive() = true
                override fun epoch() = common.epoch
                override fun gatewayOwner() = NativeDestinationStartAdmission.GatewayOwner(common.gatewayGeneration, session)
                override fun reporterId() = common.actor
                override fun permissionRecoveryIdentity() = permission
                override fun routeGeneration() = routeGeneration
            }, object : NativeDestinationStartAdmission.PositioningSource {
                override fun binding() = binding
                override fun generation() = generation
            })
        init { common.workflow.pendingUiDestination = common.destination; common.workflow.nativeUiPage = NativeUiPage.GUIDANCE }
        fun binding(identity: Any) = NativeDestinationStartAdmission.Binding(identity, common.actor, session, common.gatewayGeneration) { collectionReads++; true }
    }
}
