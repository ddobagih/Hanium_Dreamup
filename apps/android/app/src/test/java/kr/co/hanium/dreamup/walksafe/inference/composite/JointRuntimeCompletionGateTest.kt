package kr.co.hanium.dreamup.walksafe.inference.composite

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class JointRuntimeCompletionGateTest {
    @Test fun deadlineFailureDoesNotRepeatButLateDeathHasSeparateAcknowledgement() {
        val gate = JointRuntimeCompletionGate()
        val trial = Any()
        assertTrue(gate.acquireResource(trial))
        // The watchdog claims while the UI/native owner is still unavailable.
        assertEquals(false, gate.claim(false))
        assertNull(gate.claim(true))
        assertTrue(gate.acknowledgeRelease(trial))
        assertFalse(gate.acknowledgeRelease(trial))
        assertTrue(gate.releaseConfirmed)
        assertNull(gate.claim(true))
    }

    @Test fun physicalReleaseFactIsIndependentOfSelectionDeadline() {
        val gate = JointRuntimeCompletionGate()
        // A caller's expired selection window cannot turn an actual death receipt into false.
        assertEquals(true, gate.claim(true))
        assertTrue(gate.releaseConfirmed)
        assertFalse(gate.acknowledgeRelease(Any()))
        assertNull(gate.claim(false))
    }

    @Test fun concurrentReportsClaimOnlyOnceWithoutInventingRelease() {
        val gate = JointRuntimeCompletionGate()
        val release = CountDownLatch(1)
        val callers = Executors.newFixedThreadPool(4)
        try {
            val results = (0 until 16).map { index -> callers.submit(Callable {
                assertTrue(release.await(3L, TimeUnit.SECONDS))
                val proof = index % 2 == 0
                proof to gate.claim(proof)
            }) }
            release.countDown()
            val claims = results.map { it.get(3L, TimeUnit.SECONDS) }
            assertEquals(1, claims.count { it.second != null })
            val winner = claims.single { it.second != null }
            assertEquals(winner.first, winner.second)
        } finally { callers.shutdownNow() }
    }

    @Test fun releaseBeforeTerminalClaimIsRememberedAndNewCandidateReopensOwnership() {
        val gate = JointRuntimeCompletionGate(initiallyReleased = true)
        val first = Any()
        val second = Any()
        assertTrue(gate.acquireResource(first))
        assertFalse(gate.releaseConfirmed)
        assertFalse(gate.acknowledgeRelease(first))
        assertTrue(gate.acquireResource(second))
        assertFalse(gate.releaseConfirmed)
        assertEquals(false, gate.claim(false))
        assertFalse(gate.acquireResource(Any()))
        assertTrue(gate.acknowledgeRelease(second))
    }

    @Test fun delayedOldTrialDeathCannotUnlockNewTrialAtDeadline() {
        val gate = JointRuntimeCompletionGate(initiallyReleased = true)
        val oldTrial = Any()
        val newTrial = Any()
        val check = Any()
        val competingCheck = Any()
        try {
            assertTrue(FixtureRuntimeSupervisor.acquire(check))
            assertTrue(gate.acquireResource(oldTrial))
            assertFalse(gate.acknowledgeRelease(oldTrial)) // Synchronous old-trial cleanup.
            assertTrue(gate.acquireResource(newTrial))
            assertFalse(gate.acknowledgeRelease(oldTrial)) // Delayed old Binder callback resumes here.
            assertFalse(gate.releaseConfirmed)
            assertEquals(false, gate.claim(false))
            assertFalse(FixtureRuntimeSupervisor.release(check, gate.releaseConfirmed))
            assertFalse(FixtureRuntimeSupervisor.acquire(competingCheck))
            assertTrue(gate.acknowledgeRelease(newTrial))
            assertTrue(FixtureRuntimeSupervisor.release(check, gate.releaseConfirmed))
            assertTrue(FixtureRuntimeSupervisor.acquire(competingCheck))
        } finally {
            FixtureRuntimeSupervisor.release(check, true)
            FixtureRuntimeSupervisor.release(competingCheck, true)
        }
    }

    @Test fun deathAcknowledgedBeforeTerminalClaimKeepsTheActualReleaseFact() {
        val gate = JointRuntimeCompletionGate()
        val trial = Any()
        assertTrue(gate.acquireResource(trial))
        assertFalse(gate.acknowledgeRelease(trial))
        assertEquals(true, gate.claim(false))
        assertFalse(gate.acknowledgeRelease(trial))
    }
}
