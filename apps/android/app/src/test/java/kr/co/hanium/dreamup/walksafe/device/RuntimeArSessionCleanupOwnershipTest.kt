package kr.co.hanium.dreamup.walksafe.device

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeArSessionCleanupOwnershipTest {
    @Test(timeout = 10_000L)
    fun beforeCloseAndNativeCloseAllowConcurrentObservationButRejectAnotherAttempt() {
        for (phase in listOf("before_close", "native_close")) {
            val ownership = RuntimeArSessionCleanupOwnership()
            val owner = Any()
            val otherOwner = Any()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val nativeCalls = AtomicInteger()
            val replacementCalls = AtomicInteger()
            val workers = Executors.newFixedThreadPool(2)
            val blockUntilReleased = {
                entered.countDown()
                check(release.await(3L, TimeUnit.SECONDS)) { "test close callback was not released" }
            }
            val original: () -> Unit = {
                nativeCalls.incrementAndGet()
                if (phase == "native_close") blockUntilReleased()
            }
            val replacement: () -> Unit = { replacementCalls.incrementAndGet() }
            assertTrue(ownership.retain(owner, original))
            try {
                val close = workers.submit(Callable {
                    ownership.closeRetained(owner) { if (phase == "before_close") blockUntilReleased() }
                })
                assertTrue("$phase must enter the actual callback", entered.await(3L, TimeUnit.SECONDS))
                val observation = workers.submit(Callable {
                    val retained = retainedAction(ownership)
                    val rejected = listOf(
                        ownership.retain(otherOwner, replacement), ownership.close(otherOwner, replacement),
                        ownership.closeRetained(otherOwner), ownership.closeRetained(owner),
                    )
                    retained to rejected
                }).get(3L, TimeUnit.SECONDS)

                assertSame(original, observation.first)
                assertEquals(listOf(false, false, false, false), observation.second)
                assertEquals(0, replacementCalls.get())
                assertEquals(if (phase == "before_close") 0 else 1, nativeCalls.get())
                release.countDown()
                assertTrue(close.get(3L, TimeUnit.SECONDS))
                assertEquals(1, nativeCalls.get())
                assertFalse(ownership.isPending)
                assertNull(retainedAction(ownership))
                assertNull(ownership.failureFor(owner))
            } finally {
                release.countDown()
                workers.shutdownNow()
            }
        }
    }

    private fun retainedAction(ownership: RuntimeArSessionCleanupOwnership): Any? =
        RuntimeArSessionCleanupOwnership::class.java.getDeclaredField("retainedAction").run {
            isAccessible = true
            get(ownership)
        }
}
