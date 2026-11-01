package kr.co.hanium.dreamup.walksafe.runtime.metric

import kr.co.hanium.dreamup.walksafe.inference.composite.FixtureRuntimeConnectionLease
import kr.co.hanium.dreamup.walksafe.inference.composite.FixtureRuntimeEndpointRole
import kr.co.hanium.dreamup.walksafe.inference.composite.FixtureRuntimeReleaseProof
import org.junit.Assert.*
import org.junit.Test

class MetricSupportIsolationContractTest {
    @Test fun mainOrSiblingProcessAndForeignUidCannotAuthenticate() {
        val expected = "walksafe:metric_support"
        assertTrue(MetricSupportProtocol.validIdentity(expected, expected, 100, 200, 1000, 1000))
        for (name in listOf("walksafe", "walksafe:fixture_runtime", null))
            assertFalse(MetricSupportProtocol.validIdentity(expected, name, 100, 200, 1000, 1000))
        assertFalse(MetricSupportProtocol.validIdentity(expected, expected, 100, 100, 1000, 1000))
        assertFalse(MetricSupportProtocol.validIdentity(expected, expected, 100, 200, 1000, 1001))
        assertFalse(MetricSupportProtocol.validIdentity("walksafe", "walksafe", 100, 200, 1000, 1000))
    }
    @Test fun nativeAdmissionRequiresOneNonceAndTheAuthenticatedParent() {
        val gate = MetricSupportAttemptGate()
        val nonce = "a".repeat(64)
        assertFalse(gate.start(nonce, 100))
        assertFalse(gate.hello("a", 100))
        assertFalse(gate.hello(nonce, 0))
        assertTrue(gate.hello(nonce, 100))
        assertFalse(gate.hello(nonce, 101))
        assertFalse(gate.hello("b".repeat(64), 100))
        assertFalse(gate.start(nonce, 101))
        assertTrue(gate.start(nonce, 100))
        assertFalse(gate.start(nonce, 100))
    }
    @Test fun nativeFaultHooksAreAcceptedOnlyInDebugBuilds() {
        assertTrue(MetricSupportProtocol.validFault(MetricSupportProtocol.FAULT_NONE, false))
        for (fault in MetricSupportProtocol.FAULT_CREATE_HANG..MetricSupportProtocol.FAULT_QUERY_ERROR) {
            assertFalse(MetricSupportProtocol.validFault(fault, false))
            assertTrue(MetricSupportProtocol.validFault(fault, true))
        }
        assertFalse(MetricSupportProtocol.validFault(99, true))
    }
    @Test fun resultOrCancellationCannotReleaseNativeOwnershipWithoutDeathAndUnbind() {
        val lease = FixtureRuntimeConnectionLease<String>()
        assertTrue(lease.beginBind()); lease.finishBind(true)
        assertEquals(FixtureRuntimeEndpointRole.FIRST, lease.endpointConnected("worker"))
        assertTrue(lease.authenticate("worker")); assertTrue(lease.authorizeStart("worker"))
        lease.retire()
        assertEquals(FixtureRuntimeReleaseProof.NONE, lease.releaseProof)
        assertTrue(lease.claimUnbind()); lease.finishUnbind(true)
        assertEquals(FixtureRuntimeReleaseProof.NONE, lease.releaseProof)
        lease.endpointDied("worker")
        assertEquals(FixtureRuntimeReleaseProof.WORKER_DEATH, lease.releaseProof)
    }
    @Test fun lateEndpointCannotAcquireNativeAuthorityAfterCancellation() {
        val lease = FixtureRuntimeConnectionLease<String>()
        assertTrue(lease.beginBind()); lease.retire()
        lease.finishBind(true); lease.endpointConnected("late")
        assertFalse(lease.authenticate("late")); assertFalse(lease.authorizeStart("late"))
        assertEquals(FixtureRuntimeReleaseProof.NO_NATIVE_START, lease.releaseProof)
    }
    @Test fun failedUnbindAndReconnectedWorkerKeepTheNativeFence() {
        val lease = FixtureRuntimeConnectionLease<String>()
        lease.beginBind(); lease.finishBind(true); lease.endpointConnected("first")
        lease.authenticate("first"); lease.authorizeStart("first"); lease.retire()
        lease.endpointConnected("reconnected")
        lease.claimUnbind(); lease.finishUnbind(false)
        lease.endpointDied("first"); lease.endpointDied("reconnected")
        assertEquals(FixtureRuntimeReleaseProof.NONE, lease.releaseProof)
    }
    @Test fun processWideOwnerRejectsRetriesUntilAConfirmedRelease() {
        val first = Any(); val retry = Any()
        assertTrue(MetricSupportSupervisor.acquire(first))
        try {
            assertFalse(MetricSupportSupervisor.acquire(retry))
            assertFalse(MetricSupportSupervisor.release(first, false))
            assertFalse(MetricSupportSupervisor.release(retry, true))
            assertTrue(MetricSupportSupervisor.owns(first))
            assertTrue(MetricSupportSupervisor.release(first, true))
            assertTrue(MetricSupportSupervisor.acquire(retry))
        } finally {
            MetricSupportSupervisor.release(first, true); MetricSupportSupervisor.release(retry, true)
        }
    }
}
