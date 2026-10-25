package kr.co.hanium.dreamup.walksafe.runtime.vision.unknown

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kr.co.hanium.dreamup.walksafe.device.DeviceTuningRecheckReason
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Real FastSam model/post owners; release futures are driven by a controlled test engine. */
class UnknownRuntimeServiceOwnerTest {
    @Test fun concurrentTuningAndMainReleaseCompletionCannotClearASuccessorsRelease() {
        repeat(30) { iteration ->
            val f = Fixture()
            val old = f.startAndAdopt()
            val entered = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            f.native.closeEntered = entered
            f.native.releaseClose = proceed
            val failure = AtomicReference<Throwable?>()
            val successor = AtomicReference<FastSamRuntimeService<UnknownRuntimeAttachment>?>()
            val tuning = thread(name = "unknown-test-tuning-release") {
                try { assertTrue(f.owner.releaseBeforePrimaryCalibration()) }
                catch (error: Throwable) { failure.compareAndSet(null, error) }
            }
            assertTrue("Native close must precede both completion writers", entered.await(3, TimeUnit.SECONDS))
            val main = thread(name = "unknown-test-main-release") {
                try {
                    f.awaitQueue(1)
                    requireNotNull(f.main.pollFirst())() // Same actual proof as the tuning wait.
                    f.native.closeEntered = null
                    f.native.releaseClose = null
                    val next = f.startAndAdopt()
                    successor.set(next)
                    f.owner.handleUnknownRuntimeError(next.sessionEpoch, IllegalStateException("successor"))
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
            }
            // One actual native close releases the get() waiter and schedules the main callback.
            proceed.countDown()
            tuning.join(3_000L); main.join(3_000L)
            assertFalse("Tuning must not deadlock with release dispatch", tuning.isAlive)
            assertFalse("Main must not deadlock with tuning", main.isAlive)
            failure.get()?.let { throw AssertionError("Concurrent release iteration $iteration", it) }
            val next = requireNotNull(successor.get())
            next.releasedFuture().get(3, TimeUnit.SECONDS)
            assertTrue("Old completions cannot consume the successor's pending close proof", f.owner.closing)
            f.start()
            assertEquals("No new native slot before successor completion is applied", 2, f.native.creations.get())
            f.awaitQueue(1); f.drain()
            assertFalse(f.owner.closing)
            assertEquals(2, f.native.nativeCloses.get())
            assertTrue(old.stats().nativeReleaseConfirmed)
            // A source explicitly requested during close starts only after that close proof.
            val latest = requireNotNull(f.owner.loadingService ?: f.owner.currentService)
            f.owner.releaseLoadingIfPresent()
            f.owner.closeActiveForDetector()
            latest.releasedFuture().get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun alreadyCompletedNativeProofDispatchesOutsideTheOwnerTransitionLock() {
        val f = Fixture()
        val service = f.startAndAdopt()
        service.closeAsync()
        service.releasedFuture().get(3, TimeUnit.SECONDS)
        f.dispatchOnOtherThreadAndWait = true
        f.owner.closeActiveForDetector()
        assertFalse(f.owner.closing)
        assertNull(f.owner.currentService)
        assertEquals(1, f.native.nativeCloses.get())
    }

    @Test fun lateRestoredReadyCannotOverwriteNewReadyStatusOrFinishItsPreparation() {
        assertRetiredRestoreCannotPublish(failBeforeReady = false)
    }

    @Test fun lateRestoredFailureCannotOverwriteNewReadyStatusOrFinishItsPreparation() {
        assertRetiredRestoreCannotPublish(failBeforeReady = true)
    }

    private fun assertRetiredRestoreCannotPublish(failBeforeReady: Boolean) {
        val f = Fixture()
        if (failBeforeReady) {
            f.native.loadEntered = CountDownLatch(1)
            f.native.releaseLoad = CountDownLatch(1)
        }
        f.prepare()
        val old = requireNotNull(f.owner.loadingService)
        if (failBeforeReady) assertTrue(f.native.loadEntered.await(3, TimeUnit.SECONDS))
        else { old.start().get(3, TimeUnit.SECONDS); f.awaitQueue(1) }
        f.owner.releaseLoadingIfPresent()
        f.native.releaseLoad?.countDown()
        old.releasedFuture().get(3, TimeUnit.SECONDS)
        f.awaitQueue(2)
        requireNotNull(f.main.pollLast())() // Actual old release, ahead of its stale readiness callback.
        assertFalse(f.owner.closing)
        assertNull(f.owner.loadingService)
        f.native.loadEntered = null
        f.native.releaseLoad = null
        f.prepare()
        val newer = requireNotNull(f.owner.loadingService)
        newer.start().get(3, TimeUnit.SECONDS)
        f.awaitQueue(2)
        requireNotNull(f.main.pollLast())() // New owner's readiness is deliberately delivered first.
        val status = f.owner.status
        val binding = f.owner.binding
        assertSame(newer, f.owner.currentService)
        assertEquals(1, f.events.count { it is UnknownRuntimeServiceEvent.PreparationFinished })
        requireNotNull(f.main.pollFirst())()
        assertSame(newer, f.owner.currentService)
        assertSame(binding, f.owner.binding)
        assertEquals(status, f.owner.status)
        assertFalse(f.owner.closing)
        assertEquals("Retired work must not finish a successor's preparation", 1,
            f.events.count { it is UnknownRuntimeServiceEvent.PreparationFinished })
        assertEquals(0, f.pendingMarks)
        f.owner.closeActiveForDetector()
        newer.releasedFuture().get(3, TimeUnit.SECONDS)
        assertEquals(2, f.native.nativeCloses.get())
    }

    @Test fun restoredReadinessCompletionIsPublishedOnlyOnceWhenDispatcherRepeatsCallback() {
        val f = Fixture()
        f.prepare()
        val service = requireNotNull(f.owner.loadingService)
        service.start().get(3, TimeUnit.SECONDS)
        f.awaitQueue(1)
        val callback = requireNotNull(f.main.pollFirst())
        callback()
        val status = f.owner.status
        callback()
        assertSame(service, f.owner.currentService)
        assertEquals(status, f.owner.status)
        assertEquals(1, f.events.count { it is UnknownRuntimeServiceEvent.PreparationFinished })
        f.owner.closeActiveForDetector()
        service.releasedFuture().get(3, TimeUnit.SECONDS)
    }

    @Test fun repeatedOldReleaseCallbackCannotClearTheNewServicesClosingOwner() {
        val f = Fixture()
        val old = f.startAndAdopt()
        f.owner.handleUnknownRuntimeError(old.sessionEpoch, IllegalStateException("old"))
        old.releasedFuture().get(3, TimeUnit.SECONDS)
        f.awaitQueue(1)
        val released = requireNotNull(f.main.pollFirst())
        released()
        val newer = f.startAndAdopt()
        f.owner.handleUnknownRuntimeError(newer.sessionEpoch, IllegalStateException("new"))
        assertTrue(f.owner.closing)
        released()
        assertTrue("Only the exact native release may open the slot", f.owner.closing)
        newer.releasedFuture().get(3, TimeUnit.SECONDS)
        f.awaitQueue(1); f.drain()
        assertFalse(f.owner.closing)
        assertEquals(2, f.native.nativeCloses.get())
    }

    @Test fun constructorIsPureAndStartReadsOriginalAdmissionBeforeConfiguration() {
        val f = Fixture()
        assertEquals(0, f.authorityReads)
        assertEquals(0, f.native.creations.get())
        f.mayStart = false
        f.owner.startForWalk(f.source(), { fail("Blocked source must not read configuration"); null },
            { fail("Blocked source must not read candidate"); null }) { error("Blocked scope") }
        assertNull(f.owner.currentService)
        assertEquals(0, f.native.creations.get())
    }

    @Test fun primaryCalibrationWaitsForActualAuxiliaryReleaseBeforeClearingClosing() {
        val f = Fixture()
        val service = f.startAndAdopt()
        assertSame(service, f.owner.currentService)
        assertTrue(f.owner.releaseBeforePrimaryCalibration())
        assertNull(f.owner.currentService)
        assertFalse(f.owner.closing)
        assertTrue(service.stats().nativeReleaseConfirmed)
        assertEquals(1, f.native.nativeCloses.get())
        assertEquals(1, f.invalidations)
    }

    @Test fun failedActualReleaseKeepsPrimaryCalibrationBlocked() {
        val f = Fixture()
        val service = f.startAndAdopt()
        f.native.closeFailure = IllegalStateException("native release")
        assertFalse(f.owner.releaseBeforePrimaryCalibration())
        assertNull(f.owner.currentService)
        assertTrue(f.owner.closing)
        assertFalse(service.stats().nativeReleaseConfirmed)
        assertEquals("추가 물체 인식 정리 확인 필요", f.owner.status)
        f.native.closeFailure = null
    }

    @Test fun detectorTeardownDetachesSameAuxiliaryAndRequestsItsActualClose() {
        val f = Fixture()
        val service = f.startAndAdopt()
        f.owner.closeActiveForDetector()
        assertNull(f.owner.currentService)
        service.releasedFuture().get(3, TimeUnit.SECONDS)
        assertTrue(service.stats().nativeReleaseConfirmed)
        assertEquals(1, f.native.nativeCloses.get())
        assertEquals(1, f.invalidations)
    }

    @Test fun releasedLoadingOwnerCannotAdoptFromItsLateReadyCallbackOrClearNewOwner() {
        val f = Fixture()
        f.start()
        val old = requireNotNull(f.owner.loadingService)
        old.start().get(3, TimeUnit.SECONDS)
        f.awaitQueue(1)
        f.owner.releaseLoadingIfPresent()
        old.releasedFuture().get(3, TimeUnit.SECONDS)
        f.awaitQueue(2)
        // The actual close callback is delivered first; readiness remains queued and becomes stale.
        requireNotNull(f.main.pollLast())()
        assertNull(f.owner.loadingService)
        assertFalse(f.owner.closing)
        f.start()
        val newer = requireNotNull(f.owner.loadingService)
        assertNotSame(old, newer)
        requireNotNull(f.main.pollFirst())()
        assertSame(newer, f.owner.loadingService)
        assertNull(f.owner.currentService)
        newer.start().get(3, TimeUnit.SECONDS)
        f.awaitQueue(1); f.drain()
        assertSame(newer, f.owner.currentService)
        f.owner.closeActiveForDetector()
        newer.releasedFuture().get(3, TimeUnit.SECONDS)
        assertEquals(2, f.native.nativeCloses.get())
    }

    @Test fun foreignServiceErrorDoesNotRetireTheActualCurrentOwner() {
        val f = Fixture()
        val service = f.startAndAdopt()
        f.owner.handleUnknownRuntimeError(service.sessionEpoch + 1L, IllegalStateException("foreign"))
        assertSame(service, f.owner.currentService)
        assertFalse(f.owner.closing)
        assertEquals(0, f.invalidations)
        f.owner.closeActiveForDetector()
        service.releasedFuture().get(3, TimeUnit.SECONDS)
    }

    @Test fun missingArSourceDoesNotReadProfilesOrCreateAnAutomaticRuntime() {
        val f = Fixture()
        f.start(null)
        assertEquals(0, f.configReads)
        assertEquals(0, f.native.creations.get())
        assertNull(f.owner.loadingService)
        assertNull(f.owner.currentService)
    }

    @Test fun samePublishedResourceWithANewCallbackKeepsTheExactReadyService() {
        val f = Fixture()
        val service = f.startAndAdopt()
        repeat(3) { f.start(f.source()) }
        assertSame(service, f.owner.currentService)
        assertEquals(1, f.native.creations.get())
        assertEquals(1, f.configReads)
        f.close(service)
    }

    @Test fun sourceRetiredDuringProfileReadCannotCreateNativeWork() {
        val f = Fixture()
        f.onBinding = { f.start(null) }
        f.start()
        assertNull(f.owner.loadingService)
        assertEquals(0, f.native.creations.get())
        f.onBinding = {}
        f.close(f.startAndAdopt())
    }

    @Test fun readyFromANoLongerCurrentSourceIsClosedWithoutAdoptionOrRetry() {
        val f = Fixture()
        f.start()
        val service = requireNotNull(f.owner.loadingService)
        service.start().get(3, TimeUnit.SECONDS); f.awaitQueue(1)
        f.sourceCurrent = false
        requireNotNull(f.main.pollFirst())()
        assertNull(f.owner.currentService)
        service.releasedFuture().get(3, TimeUnit.SECONDS); f.awaitQueue(1); f.drain()
        assertEquals(1, f.native.creations.get())
        assertNull(f.owner.loadingService)
        assertFalse(f.owner.closing)
    }

    @Test fun onlyLatestPublishedSourceStartsAfterTheOldPhysicalCloseAcknowledges() {
        val f = Fixture()
        val old = f.startAndAdopt()
        f.native.closeEntered = CountDownLatch(1)
        f.native.releaseClose = CountDownLatch(1)
        f.generation++
        f.start()
        assertNull(f.owner.currentService)
        assertTrue(f.native.closeEntered.await(3, TimeUnit.SECONDS))
        f.generation++
        f.start()
        assertEquals(1, f.native.creations.get())
        assertTrue(f.owner.closing)
        f.native.releaseClose.countDown()
        old.releasedFuture().get(3, TimeUnit.SECONDS); f.awaitQueue(1)
        f.native.closeEntered = null; f.native.releaseClose = null
        f.drain()
        val latest = requireNotNull(f.owner.loadingService ?: f.owner.currentService)
        latest.start().get(3, TimeUnit.SECONDS)
        if (f.owner.currentService == null) { f.awaitQueue(1); f.drain() }
        assertSame(latest, f.owner.currentService)
        assertEquals(2, f.native.creations.get())
        assertEquals(1, f.native.nativeCloses.get())
        f.close(latest)
    }

    @Test fun invalidationReentryCannotCreateBeforeTheOldReleaseSlotWasReserved() {
        val f = Fixture()
        val old = f.startAndAdopt()
        f.onInvalidation = {
            assertNull(f.owner.currentService)
            assertTrue(f.owner.closing)
            f.generation++
            f.start()
            assertEquals(1, f.native.creations.get())
        }
        f.start(null)
        old.releasedFuture().get(3, TimeUnit.SECONDS); f.awaitQueue(1); f.drain()
        val next = requireNotNull(f.owner.loadingService ?: f.owner.currentService)
        f.onInvalidation = {}
        next.start().get(3, TimeUnit.SECONDS)
        if (f.owner.currentService == null) { f.awaitQueue(1); f.drain() }
        assertEquals(2, f.native.creations.get())
        f.close(next)
    }

    @Test fun pauseCancelsQueuedAutomaticIntentBeforeLoadingCloseAcknowledges() {
        val f = Fixture()
        f.start()
        val old = requireNotNull(f.owner.loadingService)
        old.start().get(3, TimeUnit.SECONDS); f.awaitQueue(1)
        f.generation++; f.start()
        f.owner.cancelCalibration("app_paused")
        f.owner.releaseLoadingIfPresent()
        old.releasedFuture().get(3, TimeUnit.SECONDS); f.awaitQueue(2); f.drain()
        assertNull(f.owner.currentService)
        assertNull(f.owner.loadingService)
        assertEquals(1, f.native.creations.get())
    }

    @Test fun sourceLossDoesNotCancelAnExplicitCompositionPreparation() {
        val f = Fixture()
        f.prepare()
        val explicit = requireNotNull(f.owner.loadingService)
        f.start(null)
        assertSame(explicit, f.owner.loadingService)
        explicit.start().get(3, TimeUnit.SECONDS); f.awaitQueue(1); f.drain()
        assertSame(explicit, f.owner.currentService)
        assertEquals(1, f.events.count { it is UnknownRuntimeServiceEvent.PreparationFinished })
        f.start(null)
        assertSame(explicit, f.owner.currentService)
        f.close(explicit)
    }

    @Test fun explicitlyPreparedServiceBecomesSourceOwnedOnlyWhenWalkingReusesIt() {
        val f = Fixture()
        f.prepare()
        val explicit = requireNotNull(f.owner.loadingService)
        explicit.start().get(3, TimeUnit.SECONDS); f.awaitQueue(1); f.drain()
        f.start()
        assertSame(explicit, f.owner.currentService)
        assertEquals(1, f.native.creations.get())
        f.start(null)
        assertNull(f.owner.currentService)
        explicit.releasedFuture().get(3, TimeUnit.SECONDS); f.awaitQueue(1); f.drain()
        assertEquals(1, f.native.nativeCloses.get())
        assertNull(f.owner.loadingService)
    }

    @Test fun failedNativeReleaseCannotStartTheLatestArSource() {
        val f = Fixture()
        val old = f.startAndAdopt()
        f.native.closeFailure = IllegalStateException("release")
        f.generation++; f.start()
        runCatching { old.releasedFuture().get(3, TimeUnit.SECONDS) }
        f.awaitQueue(1); f.drain()
        f.generation++; f.start()
        assertTrue(f.owner.closing)
        assertEquals(1, f.native.creations.get())
        assertNull(f.owner.currentService)
        assertNull(f.owner.loadingService)
        f.native.closeFailure = null
    }

    private class Fixture : UnknownCaptureClock {
        val native = UnknownRuntimeServiceTestFactory()
        val main = ConcurrentLinkedDeque<() -> Unit>()
        var sourceCurrent = true
        var generation = 1L
        private val arSession = Any()
        private val arProvider = Any()
        private val epoch = WalkRuntimeEpoch("unknown-test-walk", 1L)
        fun source(): UnknownWalkingSource {
            val capturedGeneration = generation
            return UnknownWalkingSource(arSession, arProvider, "runtime", capturedGeneration, epoch) {
                sourceCurrent && generation == capturedGeneration
            }
        }
        var configReads = 0
        var onBinding: () -> Unit = {}
        var onInvalidation: () -> Unit = {}
        var invalidations = 0
        var authorityReads = 0
        var mayStart = true
        var pendingMarks = 0
        val events = mutableListOf<UnknownRuntimeServiceEvent>()
        var dispatchOnOtherThreadAndWait = false
        private val config = TwoModelRuntimeConfig("test", null, TwoModelRuntimeConfig.UNIFIED_MODEL_KEY,
            null, null, null, null)
        private val candidate = RuntimeCandidate(RuntimeBackend.CPU, 1)
        private val profileBinding = UnknownRuntimeBinding("a".repeat(64), "b".repeat(64),
            "primary-rgb", "fast-rgb", "primary-decode", "fast-decode", candidate, "schedule",
            "device", "runtime", "environment", "fixture", "c".repeat(64), RuntimeFeatureScope.CAMERA_TRACKING)
        override fun elapsedRealtimeMs() = 100_000L
        override fun elapsedRealtimeNanos() = 100_000_000_000L
        override fun cpuNanos() = 0L
        val owner = UnknownRuntimeServiceOwner(object : UnknownRuntimeProfileRepository {
            override fun createBinding(config: TwoModelRuntimeConfig, primary: RuntimeCandidate,
                scope: RuntimeFeatureScope): UnknownRuntimeBinding = profileBinding.also { onBinding() }
            override fun load(binding: UnknownRuntimeBinding): UnknownRuntimeProfile? = null
            override fun needsRecheck(binding: UnknownRuntimeBinding, manual: Boolean) = false
            override fun markPending(binding: UnknownRuntimeBinding, reason: DeviceTuningRecheckReason): Boolean {
                pendingMarks++; return true
            }
        }, object : UnknownRuntimeFactory {
            override fun createService(epoch: Long, options: FastSamRuntimeOptions,
                listener: FastSamRuntimeService.Listener<UnknownRuntimeAttachment>): FastSamRuntimeService<UnknownRuntimeAttachment> =
                native.create(epoch, options, listener, this@Fixture::elapsedRealtimeNanos)
            override fun createCalibration(request: UnknownCalibrationRequest): UnknownCalibrationSession =
                error("No calibration factory call is expected")
        }, object : UnknownRuntimeServiceAuthority {
            override fun destroyedOrDetectorClosing(): Boolean { authorityReads++; return false }
            override fun mayStartWalkingService(): Boolean { authorityReads++; return mayStart }
            override fun calibrationCameraAvailable(): Boolean { authorityReads++; return true }
            override fun speechActive(): Boolean { authorityReads++; return false }
        }, this, Executor { it.run() }, { action ->
            if (dispatchOnOtherThreadAndWait) {
                val failure = AtomicReference<Throwable?>()
                val callback = thread(name = "unknown-test-release-dispatch") {
                    try { action() } catch (error: Throwable) { failure.set(error) }
                }
                callback.join(3_000L)
                assertFalse("External dispatch must run without the owner's lock", callback.isAlive)
                failure.get()?.let { throw AssertionError("Release dispatch", it) }
            } else main.addLast(action)
        },
            { invalidations++; onInvalidation() }, {}, { _, _ -> }, { events += it })
        fun prepare(current: () -> Boolean = { true }) {
            // The restore path only carries this primary identity; it never invokes the native model.
            val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
            val primary = unsafe.allocateInstance(TfliteAndroidFrameDetector::class.java) as TfliteAndroidFrameDetector
            owner.prepareComposition(config, primary, candidate, RuntimeFeatureScope.CAMERA_TRACKING,
                false, 160_000L, current)
        }
        fun start(source: UnknownWalkingSource? = source()) = owner.startForWalk(source, { configReads++; config }, { candidate }) { RuntimeFeatureScope.CAMERA_TRACKING }
        fun close(service: FastSamRuntimeService<UnknownRuntimeAttachment>) {
            owner.closeActiveForDetector()
            service.releasedFuture().get(3, TimeUnit.SECONDS)
        }
        fun startAndAdopt(): FastSamRuntimeService<UnknownRuntimeAttachment> {
            start()
            val service = requireNotNull(owner.loadingService)
            service.start().get(3, TimeUnit.SECONDS)
            awaitQueue(1); drain()
            assertSame(service, owner.currentService)
            return service
        }
        fun awaitQueue(size: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (main.size < size && System.nanoTime() < deadline) Thread.yield()
            assertTrue("Expected $size actual owner callbacks, found ${main.size}", main.size >= size)
        }
        fun drain() { while (true) (main.pollFirst() ?: return).invoke() }
    }
}
