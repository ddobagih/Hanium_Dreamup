package kr.co.hanium.dreamup.walksafe.inference.composite

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FixtureRuntimeIsolationContractTest {
    private val first = "a".repeat(64)
    private val second = "b".repeat(64)

    @Test fun handshakeRejectsParentVoiceWrongUidAndWrongNonce() {
        val expected = "app.package:fixture_runtime"
        assertTrue(FixtureRuntimeProtocol.validIdentity(expected, expected, 100, 200, 7000, 7000))
        assertFalse(FixtureRuntimeProtocol.validIdentity(expected, expected, 100, 100, 7000, 7000))
        assertFalse(FixtureRuntimeProtocol.validIdentity(expected, "app.package:vosk_decoder", 100, 200, 7000, 7000))
        assertFalse(FixtureRuntimeProtocol.validIdentity(expected, "app.package", 100, 200, 7000, 7000))
        assertFalse(FixtureRuntimeProtocol.validIdentity(expected, expected, 100, 200, 7000, 8000))
        val authority = FixtureRuntimeAuthority(first)
        assertFalse(authority.authenticate(second, true))
        assertFalse(authority.authenticate(first, false))
        assertFalse(authority.accepts(first))
        authority.observeDeath()
        assertTrue(authority.releaseConfirmed) // Empty pre-HELLO child really died; no native START was admitted.
        assertFalse(authority.canAdopt)
    }

    @Test fun childLifetimeAdmitsOnlyOneNonceAndOneNativeCandidate() {
        val gate = FixtureRuntimeAttemptGate()
        var allocations = 0
        assertFalse(gate.start(first))
        assertTrue(gate.hello(first))
        if (gate.start(first)) allocations++
        if (gate.start(first)) allocations++
        assertFalse(gate.hello(second))
        if (gate.start(second)) allocations++
        assertEquals(1, allocations)
        val freshProcess = FixtureRuntimeAttemptGate()
        assertTrue(freshProcess.hello(second))
        assertTrue(freshProcess.start(second))
    }

    @Test fun nativeCloseResultAndUnbindCannotReplaceActualDeathAcknowledgement() {
        val authority = FixtureRuntimeAuthority(first)
        assertTrue(authority.authenticate(first, true))
        assertTrue(authority.recordResult(first, true))
        assertFalse(authority.canAdopt)
        assertFalse(authority.releaseConfirmed)
        authority.retire() // Same transition used before unbind; this is not release proof.
        assertFalse(authority.releaseConfirmed)
        assertFalse(authority.canAdopt)
        authority.observeDeath()
        assertTrue(authority.releaseConfirmed)
        assertTrue(authority.canAdopt)
    }

    @Test fun timeoutKeepsGlobalFenceUntilDeathThenAllowsFreshCpuTrial() {
        val oldCheck = Any()
        val cpuCheck = Any()
        try {
            assertTrue(FixtureRuntimeSupervisor.acquire(oldCheck))
            val gpu = FixtureRuntimeAuthority(first)
            assertTrue(gpu.authenticate(first, true))
            gpu.retire() // The native GPU call has not returned and no close acknowledgement exists.
            assertFalse(FixtureRuntimeSupervisor.release(oldCheck, gpu.releaseConfirmed))
            assertFalse(FixtureRuntimeSupervisor.acquire(cpuCheck))
            assertFalse(gpu.canAdopt)
            gpu.observeDeath()
            assertTrue(FixtureRuntimeSupervisor.release(oldCheck, gpu.releaseConfirmed))
            assertFalse(gpu.canAdopt) // Process death recovers ownership, never invents measurements.
            assertTrue(FixtureRuntimeSupervisor.acquire(cpuCheck))
            assertFalse(FixtureRuntimeSupervisor.release(oldCheck, true))
            assertTrue(FixtureRuntimeSupervisor.owns(cpuCheck))
        } finally {
            FixtureRuntimeSupervisor.release(oldCheck, true)
            FixtureRuntimeSupervisor.release(cpuCheck, true)
        }
    }

    @Test fun retiredAttemptMetricsCannotMutateNewAttemptAuthority() {
        val old = FixtureRuntimeAuthority(first)
        val fresh = FixtureRuntimeAuthority(second)
        assertTrue(old.authenticate(first, true))
        old.retire()
        assertFalse(old.recordResult(first, true))
        assertTrue(fresh.authenticate(second, true))
        assertFalse(fresh.recordResult(first, true))
        old.observeDeath()
        assertTrue(old.releaseConfirmed)
        assertFalse(fresh.releaseConfirmed)
        assertFalse(fresh.canAdopt)
        assertTrue(fresh.recordResult(second, true))
        fresh.observeDeath()
        assertTrue(fresh.canAdopt)
    }

    @Test fun nonceAndCandidateInputAreBoundedBeforeWorkerAdmission() {
        assertFalse(FixtureRuntimeProtocol.validNonce("a".repeat(63)))
        assertFalse(FixtureRuntimeProtocol.validNonce("a".repeat(65)))
        assertFalse(FixtureRuntimeProtocol.validNonce("G".repeat(64)))
        assertThrows(IllegalArgumentException::class.java) { FixtureRuntimeProtocol.candidate("nnapi", 1) }
        assertThrows(IllegalArgumentException::class.java) { FixtureRuntimeProtocol.candidate("GPU", 0) }
        assertThrows(IllegalArgumentException::class.java) { FixtureRuntimeProtocol.candidate("CPU", 5) }
    }

    @Test fun deathReceiptWakesAdoptionOnlyAfterAuthorityWasPublished() {
        val authority = FixtureRuntimeAuthority(first)
        assertTrue(authority.authenticate(first, true))
        assertTrue(authority.recordResult(first, true))
        val receipt = CompletableFuture<Unit>()
        val waiter = Executors.newSingleThreadExecutor()
        try {
            val result = waiter.submit<Boolean> {
                receipt.get(3L, TimeUnit.SECONDS)
                authority.canAdopt
            }
            authority.publishDeath(receipt) { assertTrue(authority.canAdopt) }
            assertTrue(result.get(3L, TimeUnit.SECONDS))
        } finally { waiter.shutdownNow() }
    }
}
