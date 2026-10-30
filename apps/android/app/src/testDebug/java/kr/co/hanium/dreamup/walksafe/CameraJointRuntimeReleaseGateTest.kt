package kr.co.hanium.dreamup.walksafe

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeOptions
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.inference.unknown.UnknownRuntimeServiceTestFactory
import org.junit.Assert.*
import org.junit.Test

class CameraJointRuntimeReleaseGateTest {
    @Test fun primaryPreparationWaitsForActualAuxiliaryNativeRelease() {
        val f = Fixture()
        val service = f.closingService()
        assertTrue(f.defer(service))
        assertEquals(0, f.prepared)
        assertTrue(f.dispatched.isEmpty())
        f.finishRelease(service)
        assertEquals(1, f.prepared)
        assertEquals(0, f.failures)
        assertFalse(f.defer(service))
    }

    @Test fun leavingTheForegroundOrCameraEpochRetiresTheReleaseCallback() {
        val f = Fixture()
        val service = f.closingService()
        assertTrue(f.defer(service))
        f.current = false
        f.finishRelease(service)
        assertEquals(0, f.prepared)
        assertEquals(0, f.failures)
    }

    @Test fun failedNativeReleaseNeverRestartsPrimaryPreparation() {
        val f = Fixture()
        f.native.closeFailure = IllegalStateException("native close failed")
        val service = f.closingService()
        assertTrue(f.defer(service))
        f.finishRelease(service)
        assertEquals(0, f.prepared)
        assertEquals(1, f.failures)
        assertFalse(service.stats().nativeReleaseConfirmed)
    }

    private class Fixture {
        val native = UnknownRuntimeServiceTestFactory()
        val dispatched = LinkedBlockingQueue<() -> Unit>()
        var current = true
        var prepared = 0
        var failures = 0
        fun closingService(): FastSamRuntimeService<Unit> {
            val service = native.create(1L, FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU,
                1, false, 61_000_000_000L, 800L, false), FastSamRuntimeService.Listener<Unit> {},
                { 1_000_000_000L })
            service.start().get(3, TimeUnit.SECONDS)
            native.closeEntered = CountDownLatch(1)
            native.releaseClose = CountDownLatch(1)
            service.closeAsync()
            assertTrue(native.closeEntered.await(3, TimeUnit.SECONDS))
            return service
        }
        fun defer(service: FastSamRuntimeService<Unit>) = deferCameraRuntimeUntilAuxiliaryReleased(
            service, { dispatched.put(it) }, { current }, { prepared++ }, { failures++ })
        fun finishRelease(service: FastSamRuntimeService<Unit>) {
            native.releaseClose.countDown()
            runCatching { service.releasedFuture().get(3, TimeUnit.SECONDS) }
            val callback = requireNotNull(dispatched.poll(3, TimeUnit.SECONDS))
            callback()
        }
    }
}
