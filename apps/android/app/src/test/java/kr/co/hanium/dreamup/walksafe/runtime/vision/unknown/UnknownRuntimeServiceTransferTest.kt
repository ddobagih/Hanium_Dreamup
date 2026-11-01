package kr.co.hanium.dreamup.walksafe.runtime.vision.unknown

import kr.co.hanium.dreamup.walksafe.device.DeviceTuningRecheckReason
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.composite.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Actual Java model/post/listener drains; native identity/death are controlled test evidence. */
class UnknownRuntimeServiceTransferTest {
    @Test fun initialTransferRejectionDoesNotTurnPendingListenerDrainIntoAnAck() = Fixture().use { f ->
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        f.onResult = { entered.countDown(); assertTrue(proceed.await(3, TimeUnit.SECONDS)) }
        val old = f.install()
        try {
            old.submitArgb(2, 1, intArrayOf(0, 0), f.frame(old), null)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            f.onInvalidation = { old.closeAsync() } // FINAL_CLOSE wins immediately before the transfer effect.
            val token = f.owner.detachPreparedPairForTransfer(old).toCompletableFuture()
            assertTrue(token.isCompletedExceptionally)
            val host = requireNotNull(f.owner.transferredHostDrain()).toCompletableFuture()
            assertFalse(host.isDone)
            proceed.countDown()
            host.get(3, TimeUnit.SECONDS)
            assertFalse(host.isCompletedExceptionally)
        } finally { proceed.countDown() }
    }

    @Test fun failedTokenAfterDrainDoesNotFailHostProofOrPretendPhysicalDeath() = Fixture().use { f ->
        val marker = IllegalStateException("native detach metadata failed")
        val old = f.install()
        f.native.detachFailure = marker
        f.expectedCloseFailure = marker
        assertTrue(runCatching { f.owner.detachPreparedPairForTransfer(old).toCompletableFuture().get(3, TimeUnit.SECONDS) }.isFailure)
        val host = requireNotNull(f.owner.transferredHostDrain()).toCompletableFuture()
        host.get(3, TimeUnit.SECONDS)
        assertFalse(host.isCompletedExceptionally)
        assertFalse(old.releasedFuture().isDone)
        assertEquals(0, f.native.nativeCloses.get())
    }

    @Test fun detachRevokesAdmissionBeforeWaitingForTheOldListenerWithoutWaitingForPhysicalDeath() = Fixture().use { f ->
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        f.onResult = { entered.countDown(); assertTrue(proceed.await(3, TimeUnit.SECONDS)) }
        val old = f.install()
        try {
            assertEquals(FastSamRuntimeService.Admission.ACCEPTED, old.submitArgb(2, 1, intArrayOf(0, 0), f.frame(old), null))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val drained = f.owner.detachPreparedPairForTransfer(old).toCompletableFuture()
            assertSame(drained, f.owner.detachPreparedPairForTransfer(old))
            assertNull(f.owner.currentService)
            assertNull(f.owner.reservePreparedPair { true })
            assertFalse(drained.isDone)
            assertTrue(f.owner.retainedReleaseReceipts().isEmpty())
            assertFalse(old.releasedFuture().isDone)
            proceed.countDown()
            assertEquals(f.native.modelId, drained.get(3, TimeUnit.SECONDS).modelInstanceId)
            f.owner.transferredHostDrain()!!.toCompletableFuture().get(3, TimeUnit.SECONDS)
            assertFalse(old.releasedFuture().isDone)
            assertEquals(0, f.native.nativeCloses.get())
        } finally { proceed.countDown() }
    }

    @Test fun twoRoundTripsReserveFreshJavaListenersButReuseTheSameNativeModel() = Fixture().use { f ->
        var main = f.install()
        repeat(2) { round ->
            val forward = f.owner.detachPreparedPairForTransfer(main).toCompletableFuture().get(3, TimeUnit.SECONDS)
            val cameraLease = f.native.lease(2L + round * 2).copy(ownerId = "camera-target")
            val cameraHandle = f.native.handle(cameraLease.ownerEpoch)
            val camera = f.attach(100L + round, FastSamRuntimeService.Listener<UnknownRuntimeAttachment> {}, forward, cameraLease, cameraHandle)
            val back = camera.drainForTransfer().get(3, TimeUnit.SECONDS)
            val receipt = f.receipt(3L + round * 2)
            assertTrue(f.owner.resumeTransferredHost(receipt) { true })
            assertFalse(f.owner.resumeTransferredHost(receipt) { true })
            val slot = requireNotNull(f.owner.reservePreparedPair { true })
            assertNull(f.owner.reservePreparedPair { true })
            main = f.attach(slot.epoch, slot.listener, back, receipt.lease,
                receipt.handles.single { it.role == VisionRole.FASTSAM })
            assertTrue(f.owner.registerPreparedPair(slot, main))
            assertTrue(f.owner.adoptPreparedPair(slot, main, f.choice))
            assertNull(f.owner.transferredHostDrain())
            assertSame(main, f.owner.currentService)
            assertFalse(main.releasedFuture().isDone)
            assertEquals(1, f.native.nativeCreates.get())
            assertEquals(0, f.independentFactories.get())
            assertEquals(0, f.native.nativeCloses.get())
        }
    }

    @Test fun wrongServicePendingPairWrongEpochAndWrongModelNeverOpenAReturnReservation() = Fixture().use { f ->
        val reserved = requireNotNull(f.owner.reservePreparedPair { true })
        val unrelated = f.newSource(reserved)
        assertTrue(runCatching { f.owner.detachPreparedPairForTransfer(unrelated).toCompletableFuture().get(3, TimeUnit.SECONDS) }.isFailure)
        f.owner.abortPreparedPair(reserved)
        val main = f.install()
        val source = f.owner.detachPreparedPairForTransfer(main).toCompletableFuture().get(3, TimeUnit.SECONDS)
        assertTrue(runCatching { f.owner.detachPreparedPairForTransfer(unrelated).toCompletableFuture().get(3, TimeUnit.SECONDS) }.isFailure)
        assertFalse(f.owner.resumeTransferredHost(f.receipt(2)) { true })
        assertFalse(f.owner.resumeTransferredHost(f.receipt(4)) { true })
        assertFalse(f.owner.resumeTransferredHost(f.receipt(3).copy(lease = f.receipt(3).lease.copy(ownerId = "another-main"))) { true })
        assertFalse(f.owner.resumeTransferredHost(f.receipt(3).let { it.copy(handles = it.handles.map { handle ->
            if (handle.role == VisionRole.FASTSAM) handle.copy(modelInstanceId = "wrong-model") else handle }) }) { true })
        assertFalse(f.owner.resumeTransferredHost(f.receipt(3)) { false })
        assertFalse(f.owner.resumeTransferredHost(f.receipt(3)) { throw IllegalStateException("old actor") })
        assertNull(f.owner.reservePreparedPair { true })
        assertEquals(f.native.modelId, source.modelInstanceId)
        assertEquals(1, f.native.nativeCreates.get())
    }

    @Test fun lateOldListenerAndCloseCannotPublishIntoOrCloseTheReturnedService() = Fixture().use { f ->
        val old = f.install()
        val oldListener = f.lastSlot!!.listener
        val forward = f.owner.detachPreparedPairForTransfer(old).toCompletableFuture().get(3, TimeUnit.SECONDS)
        val cameraLease = f.native.lease(2).copy(ownerId = "camera-target")
        val camera = f.attach(90, FastSamRuntimeService.Listener<UnknownRuntimeAttachment> {}, forward, cameraLease, f.native.handle(2))
        val back = camera.drainForTransfer().get(3, TimeUnit.SECONDS)
        assertTrue(f.owner.resumeTransferredHost(f.receipt(3)) { true })
        val slot = requireNotNull(f.owner.reservePreparedPair { true })
        val next = f.attach(slot.epoch, slot.listener, back, f.native.lease(3), f.native.handle(3))
        assertTrue(f.owner.registerPreparedPair(slot, next))
        assertTrue(f.owner.adoptPreparedPair(slot, next, f.choice))
        oldListener.onDiscard(f.frame(old), "old source")
        oldListener.onError(IllegalStateException("old native callback"))
        old.closeAsync().get(3, TimeUnit.SECONDS)
        assertSame(next, f.owner.currentService)
        assertTrue(next.isReady)
        assertEquals(0, f.discards.get())
        assertEquals(0, f.native.nativeCloses.get())
        assertFalse(old.releasedFuture().isDone)
    }

    @Test fun asyncReleaseIsNonblockingAndWaitsForLateActualReceiptAfterSemanticCloseFailure() = Fixture().use { f ->
        val service = f.install()
        val marker = IllegalStateException("model close control failed")
        f.native.closeFailure = marker
        val released = f.owner.releaseBeforePrimaryCalibrationAsync().toCompletableFuture()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (f.native.nativeCloses.get() == 0 && System.nanoTime() < until) Thread.yield()
        assertEquals(1, f.native.nativeCloses.get())
        assertFalse(released.isDone)
        assertFalse(service.releasedFuture().isDone)
        assertTrue(f.owner.closing)
        f.native.physical.complete(null) // Controlled actual process-death proof, independent of close's failure.
        assertTrue(released.get(3, TimeUnit.SECONDS))
        assertFalse(f.owner.closing)
        assertNull(f.owner.currentService)
        f.expectedCloseFailure = marker
    }

    private class Fixture : AutoCloseable {
        val native = UnknownRuntimeTransferTestFactory()
        val independentFactories = AtomicInteger()
        val discards = AtomicInteger()
        val choice = JointRuntimeTestFixtures.choice(JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.CPU, 1), RuntimeCandidate(RuntimeBackend.CPU, 1)))
        val services = mutableListOf<FastSamRuntimeService<UnknownRuntimeAttachment>>()
        var expectedCloseFailure: Throwable? = null
        var onResult: () -> Unit = {}
        var onInvalidation: () -> Unit = {}
        var lastSlot: UnknownRuntimeServiceOwner.PreparedPairSlot? = null
        fun options() = FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU, 1, false,
            System.nanoTime() + TimeUnit.SECONDS.toNanos(30), 5000, false, false, true)
        val owner = UnknownRuntimeServiceOwner(object : UnknownRuntimeProfileRepository {
            override val requiresJointChoice = true
            override fun createBinding(config: TwoModelRuntimeConfig, primary: RuntimeCandidate, scope: RuntimeFeatureScope): UnknownRuntimeBinding? = null
            override fun load(binding: UnknownRuntimeBinding): UnknownRuntimeProfile? = null
            override fun needsRecheck(binding: UnknownRuntimeBinding, manual: Boolean) = false
            override fun markPending(binding: UnknownRuntimeBinding, reason: DeviceTuningRecheckReason) = false
        }, object : UnknownRuntimeFactory {
            override fun createService(epoch: Long, options: FastSamRuntimeOptions, listener: FastSamRuntimeService.Listener<UnknownRuntimeAttachment>): FastSamRuntimeService<UnknownRuntimeAttachment> {
                independentFactories.incrementAndGet(); error("No independent OPEN is permitted")
            }
            override fun createCalibration(request: UnknownCalibrationRequest): UnknownCalibrationSession = error("No calibration")
        }, object : UnknownRuntimeServiceAuthority {
            override fun destroyedOrDetectorClosing() = false
            override fun mayStartWalkingService() = true
            override fun calibrationCameraAvailable() = false
            override fun speechActive() = false
        }, object : UnknownCaptureClock {
            override fun elapsedRealtimeMs() = System.nanoTime() / 1_000_000L
            override fun elapsedRealtimeNanos() = System.nanoTime()
            override fun cpuNanos() = System.nanoTime()
        }, Executor(Runnable::run), { it() }, { onInvalidation() }, { onResult() }, { _, _ -> discards.incrementAndGet() }, {})
        fun newSource(slot: UnknownRuntimeServiceOwner.PreparedPairSlot) = native.source(slot.epoch, options(), slot.listener, System::nanoTime).also(services::add)
        fun install(): FastSamRuntimeService<UnknownRuntimeAttachment> {
            val slot = requireNotNull(owner.reservePreparedPair { true }).also { lastSlot = it }
            return newSource(slot).also {
                assertTrue(owner.registerPreparedPair(slot, it))
                it.start().get(3, TimeUnit.SECONDS)
                assertTrue(owner.adoptPreparedPair(slot, it, choice))
            }
        }
        fun attach(epoch: Long, listener: FastSamRuntimeService.Listener<UnknownRuntimeAttachment>, token: FastSamTransferredModel,
            lease: VisionCohortLease, handle: VisionModelHandle) = native.attach(epoch, options(), listener, token, lease, handle, System::nanoTime)
                .also { services += it; it.start().get(3, TimeUnit.SECONDS); assertTrue(it.activateTransferred()) }
        fun receipt(epoch: Long) = VisionTransferResult(native.lease(epoch), listOf(
            VisionModelHandle(native.cohort, "same-primary", epoch, VisionRole.PRIMARY, TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, "{}"), native.handle(epoch)))
        fun frame(service: FastSamRuntimeService<*>) = FastSamFrameToken(FastSamFrameToken.Source.CALIBRATION_FIXTURE,
            service.sessionEpoch, 1, 0, 0, System.nanoTime(), "sample", 2, 1)
        override fun close() {
            owner.closeActiveForDetector()
            services.forEach { service ->
                try { service.closeAsync().get(3, TimeUnit.SECONDS) }
                catch (error: ExecutionException) { assertSame(expectedCloseFailure, error.cause) }
            }
            native.physical.complete(null)
            services.forEach { it.releasedFuture().get(3, TimeUnit.SECONDS) }
        }
    }
}
