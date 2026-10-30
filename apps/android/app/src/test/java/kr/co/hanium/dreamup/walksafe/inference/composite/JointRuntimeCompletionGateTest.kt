package kr.co.hanium.dreamup.walksafe.inference.composite

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class JointRuntimeCompletionGateTest {
    @Test fun deadlineDecisionDoesNotWaitForUiOrAllowLateNativeSuccess() {
        val clock = AtomicLong(100L)
        val gate = JointRuntimeCompletionGate(100L, clock::get)
        // The watchdog claims while the UI/native owner is still unavailable.
        assertEquals(false, gate.claim(false))
        clock.set(120L)
        assertNull(gate.claim(true))
    }

    @Test fun releaseProofAtOrAfterDeadlineCannotClaimSuccess() {
        val clock = AtomicLong(100L)
        assertEquals(false, JointRuntimeCompletionGate(100L, clock::get).claim(true))
        clock.set(101L)
        assertEquals(false, JointRuntimeCompletionGate(100L, clock::get).claim(true))
        clock.set(99L)
        val before = JointRuntimeCompletionGate(100L, clock::get)
        assertEquals(true, before.claim(true))
        clock.set(100L)
        assertNull(before.claim(false))
    }

    @Test fun concurrentDeadlineAndLateCompletionClaimOnlyOneFailure() {
        val gate = JointRuntimeCompletionGate(100L) { 100L }
        val release = CountDownLatch(1)
        val callers = Executors.newFixedThreadPool(4)
        try {
            val results = (0 until 16).map { index -> callers.submit(Callable {
                assertTrue(release.await(3L, TimeUnit.SECONDS))
                gate.claim(index % 2 == 0)
            }) }
            release.countDown()
            val claims = results.map { it.get(3L, TimeUnit.SECONDS) }
            assertEquals(1, claims.count { it != null })
            assertEquals(false, claims.single { it != null })
        } finally { callers.shutdownNow() }
    }
}
