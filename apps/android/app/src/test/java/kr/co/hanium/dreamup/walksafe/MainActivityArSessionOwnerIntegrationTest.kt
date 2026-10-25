package kr.co.hanium.dreamup.walksafe

import com.google.ar.core.Session
import kr.co.hanium.dreamup.walksafe.depth.ArCoreFrameProvider
import kr.co.hanium.dreamup.walksafe.device.RuntimeArSessionCleanupOwnership
import kr.co.hanium.dreamup.walksafe.runtime.vision.ar.RuntimeArSessionOwner
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

/** Executes the proposed actual Main adapters; fake SDK references are never invoked. */
class MainActivityArSessionOwnerIntegrationTest {
    @Test fun actualActivityConstructorAndDefaultNativeObservationDoNotRequireALooperOrNativeSession() {
        val main = MainActivity()
        val observation = main.captureRuntimeArObservation()
        assertNull(observation.session)
        assertNull(observation.provider)
        assertEquals(MainActivity.ArSessionPurpose.NONE, observation.purpose)
        assertEquals(0L, observation.generation)
    }

    @Test fun actualMainObservationReadsTheRealOwnerAndKeepsExactSdkReferences() {
        val f = fixture()
        val session = allocate(Session::class.java)
        val provider = allocate(ArCoreFrameProvider::class.java)
        f.owner.publish(session, provider, MainActivity.ArSessionPurpose.RUNTIME)
        val observation = f.main.captureRuntimeArObservation()
        assertSame(session, observation.session)
        assertSame(provider, observation.provider)
        assertEquals(MainActivity.ArSessionPurpose.RUNTIME, observation.purpose)
        assertEquals(1L, observation.generation)
        assertTrue(f.main.isRuntimeArLeaseCurrent(observation))
        assertFalse(f.main.isRuntimeArLeaseCurrent(observation.copy(session = allocate(Session::class.java))))
        assertFalse(f.main.isRuntimeArLeaseCurrent(observation.copy(provider = allocate(ArCoreFrameProvider::class.java))))
        assertFalse(f.main.isRuntimeArLeaseCurrent(observation.copy(generation = 0L)))
    }

    @Test fun failedTargetInActualMainRemainsBlockedAndCannotBeReplacedOrReplayed() {
        val f = fixture()
        val failure = IllegalStateException("uncertain actual target")
        var calls = 0
        assertFalse(f.main.closeRuntimeArSession { calls += 1; throw failure })
        assertEquals(1, calls)
        assertSame(failure, f.owner.cleanupFailureOrNull())
        assertTrue(f.main.runtimeArSessionCleanupPending())
        assertFalse(f.main.closeRetainedRuntimeArSession())
        assertFalse(f.main.closeRuntimeArSession { error("replacement must not execute") })
        assertEquals(1, calls)
    }

    @Test fun successfulActualMainCloseAcknowledgesAndAllowsTheNextIndependentTarget() {
        val f = fixture()
        var calls = 0
        assertTrue(f.main.closeRuntimeArSession { calls += 1 })
        assertFalse(f.main.runtimeArSessionCleanupPending())
        assertTrue(f.main.closeRuntimeArSession { calls += 1 })
        assertEquals(2, calls)
    }

    @Test fun actualMainPreCloseFailureLeavesTheNativeTargetAndContinuesIndependentCleanup() {
        val f = fixture()
        val before = IllegalStateException("before")
        val after = IllegalStateException("after")
        var nativeCalls = 0
        var afterCalls = 0
        val target: () -> Unit = { nativeCalls += 1 }
        assertTrue(f.main.retainRuntimeArSessionCloseOwner(target))
        assertFalse(f.main.completeRuntimeArInvalidationCleanup(target,
            beforeClose = { throw before }, afterClose = { afterCalls += 1; throw after }))
        assertEquals(0, nativeCalls)
        assertEquals(1, afterCalls)
        assertSame(before, f.owner.cleanupFailureOrNull())
        assertEquals(listOf(after), before.suppressed.toList())
        assertTrue(f.main.runtimeArSessionCleanupPending())
        assertFalse(f.main.closeRetainedRuntimeArSession())
        assertEquals(0, nativeCalls)
    }

    @Test fun actualMainTailFailureCannotUndoAnAcknowledgedNativeClose() {
        val f = fixture()
        val tail = IllegalStateException("tail")
        var nativeCalls = 0
        val target: () -> Unit = { nativeCalls += 1 }
        assertTrue(f.main.retainRuntimeArSessionCloseOwner(target))
        assertFalse(f.main.completeRuntimeArInvalidationCleanup(target, {}, { throw tail }))
        assertEquals(1, nativeCalls)
        assertFalse(f.main.runtimeArSessionCleanupPending())
        assertSame(tail, f.owner.cleanupFailureOrNull())
    }

    @Test fun actualMainWithAnotherOwnerCannotConsumeTheProcessTarget() {
        val ownership = RuntimeArSessionCleanupOwnership()
        val first = fixture(ownership)
        val next = fixture(ownership)
        val failure = IllegalStateException("first")
        assertFalse(first.main.closeRuntimeArSession { throw failure })
        assertTrue(next.main.runtimeArSessionCleanupPending())
        assertFalse(next.main.closeRetainedRuntimeArSession { error("another Main must not prepare the target") })
        assertFalse(next.main.closeRuntimeArSession { error("another Main must not replace the target") })
        assertNull(next.owner.cleanupFailureOrNull())
        assertSame(failure, first.owner.cleanupFailureOrNull())
    }

    private fun fixture(ownership: RuntimeArSessionCleanupOwnership = RuntimeArSessionCleanupOwnership()): Fixture {
        val main = MainActivity()
        val stateLock = MainActivity::class.java.getDeclaredField("runtimeMetricStateLock").run {
            isAccessible = true
            get(main)
        }
        val owner = RuntimeArSessionOwner<Session, ArCoreFrameProvider, MainActivity.ArSessionPurpose>(
            stateLock, MainActivity.ArSessionPurpose.NONE,
            { error("fake SDK Session must not be invoked") },
            { error("fake SDK provider must not be invoked") }, ownership,
        )
        MainActivity::class.java.getDeclaredField("arSessionOwner\$delegate").apply {
            isAccessible = true
            set(main, lazyOf(owner))
        }
        return Fixture(main, owner)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T = Unsafe::class.java.getDeclaredField("theUnsafe").run {
        isAccessible = true
        (get(null) as Unsafe).allocateInstance(type) as T
    }

    private data class Fixture(val main: MainActivity,
        val owner: RuntimeArSessionOwner<Session, ArCoreFrameProvider, MainActivity.ArSessionPurpose>)
}
