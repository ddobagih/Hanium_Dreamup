package kr.co.hanium.dreamup.walksafe.startup

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PrivacyStartupResourcesTest {
    @Test fun failedSecondUiPreparationReleasesTheFirstAcquisitionAndPreservesTheOriginalFailure() {
        val owner = PrivacyStartupResources()
        val original = IllegalStateException("second probe")
        var closes = 0
        val observed = assertThrows(IllegalStateException::class.java) {
            owner.prepareOnUiThread {
                owner.acquireOnUiThread({ Any() }, { closes++ })
                owner.acquireOnUiThread({ throw original }, { _: Any -> fail("No second resource was acquired") })
            }
        }
        assertSame(original, observed)
        owner.closeOnUiThread()
        assertEquals(1, closes)
    }

    @Test fun failedCloseDoesNotSkipOtherResourcesAndRepeatedCloseDoesNotRepeatEffects() {
        val owner = PrivacyStartupResources()
        val events = mutableListOf<String>()
        val failure = IllegalStateException("uploader")
        owner.acquireOnWorker({ Any() }, { events += "uploader"; throw failure })
        owner.acquireOnWorker({ Any() }, { events += "logger"; error("logger") })
        owner.acquireOnUiThread({ Any() }, { events += "sensor" })
        assertSame(failure, assertThrows(IllegalStateException::class.java) { owner.closeOnWorker() })
        assertEquals(1, failure.suppressed.size)
        assertEquals(listOf("uploader", "logger"), events)
        owner.closeOnWorker()
        owner.closeOnUiThread()
        owner.closeOnUiThread()
        assertEquals(listOf("uploader", "logger", "sensor"), events)
    }

    @Test fun replacementProbeRetiresTheOriginalInstanceAndClosesTheCurrentInstanceOnce() {
        val owner = PrivacyStartupResources()
        val closed = mutableListOf<Any>()
        val old = owner.acquireOnUiThread({ Any() }, { closed += it })
        owner.releaseOnUiThread(old)
        val current = owner.acquireOnUiThread({ Any() }, { closed += it })
        owner.releaseOnUiThread(old)
        owner.closeOnUiThread()
        assertEquals(listOf(old, current), closed)
        var created = false
        assertThrows(IllegalStateException::class.java) {
            owner.acquireOnUiThread({ created = true; Any() }, {})
        }
        assertFalse(created)
    }

    @Test fun acquisitionFinishingAfterCloseReclaimsItsOwnInstanceOnTheAcquiringThread() {
        val owner = PrivacyStartupResources()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val cleanupThread = AtomicReference<Thread?>()
        val resource = Any()
        val worker = Thread {
            try {
                owner.acquireOnWorker({
                    entered.countDown()
                    check(finish.await(2, TimeUnit.SECONDS))
                    resource
                }, { assertSame(resource, it); cleanupThread.set(Thread.currentThread()) })
            } catch (error: Throwable) { failure.set(error) }
        }
        worker.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            owner.closeOnWorker()
        } finally { finish.countDown(); worker.join(2_000) }
        assertFalse(worker.isAlive)
        assertTrue(failure.get() is IllegalStateException)
        assertSame(worker, cleanupThread.get())
    }
}
