package kr.co.hanium.dreamup.walksafe.runtime.vision.unknown

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kr.co.hanium.dreamup.walksafe.device.DeviceTuningRecheckReason
import kr.co.hanium.dreamup.walksafe.device.RuntimeCalibrationResult
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.composite.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionCohortBusyException
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionReleaseReceipt

/** Real FastSam model/post owners; release futures are driven by a controlled test engine. */
class UnknownRuntimeServiceOwnerTest {
    @Test fun asynchronousCalibrationReleaseReturnsBeforeCloseAndAcceptsOnlyTheActualReceipt() {
        val f = Fixture()
        val service = f.startAndAdopt()
        f.native.closeEntered = CountDownLatch(1)
        f.native.releaseClose = CountDownLatch(1)
        val released = f.owner.releaseBeforePrimaryCalibrationAsync().toCompletableFuture()
        try {
            assertTrue(f.native.closeEntered.await(3, TimeUnit.SECONDS))
            assertFalse(released.isDone)
            assertNull(f.owner.currentService)
            assertTrue(f.owner.closing)
        } finally { f.native.releaseClose.countDown() }
        assertTrue(released.get(3, TimeUnit.SECONDS))
        assertTrue(service.releasedFuture().isDone)
        assertFalse(f.owner.closing)
    }

    @Test fun asyncReleaseRevokesQueuedEmptyPreparationWithoutWaitingForTheTuningQueue() {
        val f = Fixture()
        f.queueTuning = true
        f.start()
        assertEquals(1, f.tuningTasks.size)
        assertTrue(f.owner.releaseBeforePrimaryCalibrationAsync().toCompletableFuture().get(3, TimeUnit.SECONDS))
        requireNotNull(f.tuningTasks.pollFirst()).run()
        assertEquals(0, f.native.creations.get())
        assertNull(f.owner.loadingService)
        assertNull(f.owner.currentService)
    }

    @Test fun asyncReleaseCannotLetALateDiagnosticConstructorStartAfterItsReservationWasRetired() {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        f.native.loadEntered = CountDownLatch(1)
        f.beforeServiceReturned = { entered.countDown(); assertTrue(proceed.await(3, TimeUnit.SECONDS)) }
        val failure = AtomicReference<Throwable?>()
        val worker = thread(name = "unknown-late-constructor") { try { f.prepare() } catch (error: Throwable) { failure.set(error) } }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(f.owner.releaseBeforePrimaryCalibrationAsync().toCompletableFuture().get(3, TimeUnit.SECONDS))
        } finally { proceed.countDown() }
        worker.join(3_000L)
        assertFalse(worker.isAlive)
        failure.get()?.let { throw AssertionError("late diagnostic constructor", it) }
        assertEquals(1, f.native.creations.get()) // Inert Java service, never native create/start.
        assertEquals(1L, f.native.loadEntered.count)
        assertNull(f.owner.loadingService)
        assertNull(f.owner.currentService)
    }

    @Test fun aFailedCallerGuardAfterReservationCannotLeakThePendingPairSlot() {
        for (throwing in listOf(false, true)) {
            val f = Fixture()
            var reads = 0
            val result = runCatching { f.owner.reservePreparedPair {
                reads++
                if (reads == 1) true else if (throwing) throw IllegalStateException("retired caller guard") else false
            } }
            assertNull("A caller-observation exception must retire the reservation", result.exceptionOrNull())
            assertNull(result.getOrNull())
            assertEquals(0, f.native.creations.get())
            val retry = requireNotNull(f.owner.reservePreparedPair { true })
            f.owner.abortPreparedPair(retry)
        }
    }

    @Test fun aSynchronousCallerReentryCannotRegisterAnOldAuxiliaryIntoTheNewReservation() {
        val f = Fixture()
        var onCurrent: () -> Unit = {}
        val slot = requireNotNull(f.owner.reservePreparedPair { onCurrent(); true })
        val service = f.createPairService(slot)
        var fresh: UnknownRuntimeServiceOwner.PreparedPairSlot? = null
        onCurrent = {
            onCurrent = {}
            f.owner.abortPreparedPair(slot)
            fresh = f.owner.reservePreparedPair { true }
        }
        assertFalse(f.owner.registerPreparedPair(slot, service))
        assertTrue(requireNotNull(fresh).isCurrent())
        assertNull(f.owner.loadingService)
        assertFalse(service.stats().modelOwnerAlive)
        service.closeAsync().get(3, TimeUnit.SECONDS)
        f.owner.abortPreparedPair(requireNotNull(fresh))
    }

    @Test fun aPreparedPairReservationOwnsTheOnlyAuxiliarySlotWithoutStartingNativeWork() {
        val f = Fixture()
        f.native.loadEntered = CountDownLatch(1)
        val slot = requireNotNull(f.owner.reservePreparedPair { true })
        assertTrue(slot.isCurrent())
        assertNull(f.owner.reservePreparedPair { true })
        f.prepare()
        assertEquals(0, f.native.creations.get())
        val service = f.createPairService(slot)
        assertTrue(f.owner.registerPreparedPair(slot, service))
        assertSame(service, f.owner.loadingService)
        assertSame(service.releasedFuture(), f.owner.retainedReleaseReceipts().single())
        assertEquals(1, f.native.creations.get()) // Exactly one Java service was constructed.
        assertEquals(1L, f.native.loadEntered.count) // Its native factory has not entered.
        assertFalse(service.stats().modelOwnerAlive)
        assertFalse(service.stats().cpuPostOwnerAlive)
        f.owner.abortPreparedPair(slot)
        service.releasedFuture().get(3, TimeUnit.SECONDS)
        f.drain()
        assertFalse(slot.isCurrent())
        assertNull(f.owner.loadingService)
    }

    @Test fun anActuallyPreparedPairAdoptsTheSameAuxiliaryWithoutAnotherFactoryOpen() {
        val f = Fixture()
        val measured = jointChoice(JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.CPU, 1),
            RuntimeCandidate(RuntimeBackend.CPU, 1), auxiliaryIntervalMs = 550L))
        val slot = requireNotNull(f.owner.reservePreparedPair { true })
        val service = f.createPairService(slot)
        try {
            assertTrue(f.owner.registerPreparedPair(slot, service))
            service.start().get(3, TimeUnit.SECONDS)
            assertTrue(f.owner.adoptPreparedPair(slot, service, measured))
            assertSame(service, f.owner.currentService)
            assertNull(f.owner.loadingService)
            assertEquals(550L, f.owner.minimumAuxiliaryIntervalMs(service))
            assertEquals(1, f.native.creations.get())
            assertFalse(slot.isCurrent())
            f.owner.abortPreparedPair(slot)
            assertSame(service, f.owner.currentService)
            assertFalse(service.stats().stopping)
            f.requiresJoint = true
            f.joint = measured
            f.start()
            assertSame(service, f.owner.currentService)
            assertEquals(1, f.native.creations.get()) // Walking binds the warmed instance.
        } finally {
            f.owner.abortPreparedPair(slot)
            f.owner.closeActiveForDetector()
            service.closeAsync().get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun losingOrThrowingCallerAfterPairSamplesCannotAdoptAndRetainsItsCleanupReceipt() {
        for (throwing in listOf(false, true)) {
            val f = Fixture()
            var permitted = true
            val slot = requireNotNull(f.owner.reservePreparedPair {
                if (!permitted && throwing) throw IllegalStateException("caller retired after samples")
                permitted
            })
            val service = f.createPairService(slot)
            assertTrue(f.owner.registerPreparedPair(slot, service))
            service.start().get(3, TimeUnit.SECONDS)
            permitted = false
            val measured = jointChoice(JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.CPU, 1),
                RuntimeCandidate(RuntimeBackend.CPU, 1)))
            assertFalse(f.owner.adoptPreparedPair(slot, service, measured))
            assertNull(f.owner.currentService)
            service.releasedFuture().get(3, TimeUnit.SECONDS)
            f.awaitQueue(1); f.drain()
            assertTrue(service.stats().nativeReleaseConfirmed)
            assertNull(f.owner.loadingService)
            assertEquals(0, f.jointInvalidations)
        }
    }

    @Test fun cancellingRegisteredPairLoadKeepsTheActualReceiptUntilItsLateNativeClose() {
        val f = Fixture()
        f.native.loadEntered = CountDownLatch(1)
        f.native.releaseLoad = CountDownLatch(1)
        val slot = requireNotNull(f.owner.reservePreparedPair { true })
        val service = f.createPairService(slot)
        assertTrue(f.owner.registerPreparedPair(slot, service))
        service.start()
        assertTrue(f.native.loadEntered.await(3, TimeUnit.SECONDS))
        f.owner.abortPreparedPair(slot)
        assertTrue(f.owner.closing)
        assertNull(f.owner.reservePreparedPair { true })
        assertFalse(service.releasedFuture().isDone)
        assertSame(service.releasedFuture(), f.owner.retainedReleaseReceipts().single())
        f.native.releaseLoad.countDown()
        service.releasedFuture().get(3, TimeUnit.SECONDS)
        f.awaitQueue(1); f.drain()
        assertFalse(f.owner.closing)
        assertNull(f.owner.loadingService)
        val fresh = requireNotNull(f.owner.reservePreparedPair { true })
        f.owner.abortPreparedPair(fresh)
    }

    @Test fun aLateOldPairCallbackCannotRegisterOrAbortTheNewlyAdoptedService() {
        val f = Fixture()
        val old = requireNotNull(f.owner.reservePreparedPair { true })
        f.owner.abortPreparedPair(old)
        val fresh = requireNotNull(f.owner.reservePreparedPair { true })
        val staleService = f.createPairService(old)
        assertFalse(f.owner.registerPreparedPair(old, staleService))
        staleService.closeAsync().get(3, TimeUnit.SECONDS)
        val service = f.createPairService(fresh)
        val factoriesBeforeAdopt = f.native.creations.get()
        try {
            assertTrue(f.owner.registerPreparedPair(fresh, service))
            service.start().get(3, TimeUnit.SECONDS)
            val measured = jointChoice(JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.CPU, 1),
                RuntimeCandidate(RuntimeBackend.CPU, 1)))
            assertTrue(f.owner.adoptPreparedPair(fresh, service, measured))
            f.owner.abortPreparedPair(old)
            assertSame(service, f.owner.currentService)
            assertFalse(service.stats().stopping)
            assertEquals(factoriesBeforeAdopt, f.native.creations.get())
        } finally {
            f.owner.abortPreparedPair(fresh); f.owner.closeActiveForDetector()
            service.closeAsync().get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun legacyDiagnosticBusyPreservesTheMeasuredChoiceAndRetriesOnlyAfterAvailability() {
        val f = Fixture()
        f.requiresJoint = false // Production never performs this independent auxiliary OPEN.
        val measured = jointChoice(JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.CPU, 1),
            RuntimeCandidate(RuntimeBackend.CPU, 1)))
        f.joint = measured
        val availability = CompletableFuture<VisionReleaseReceipt>()
        f.native.loadFailure = VisionCohortBusyException(availability)
        f.start()
        val rejected = requireNotNull(f.owner.loadingService)
        assertThrows(java.util.concurrent.ExecutionException::class.java) { rejected.start().get(3, TimeUnit.SECONDS) }
        rejected.releasedFuture().get(3, TimeUnit.SECONDS)
        f.awaitQueue(1); f.drain()
        assertSame(measured, f.joint)
        assertEquals(0, f.jointInvalidations)
        assertEquals(1, f.native.creations.get())
        assertNull(f.owner.currentService)
        f.native.loadFailure = null
        availability.complete(VisionReleaseReceipt("previous-cohort", 71))
        f.awaitQueue(1); f.drain()
        val ready = requireNotNull(f.owner.loadingService ?: f.owner.currentService)
        ready.start().get(3, TimeUnit.SECONDS)
        if (f.owner.currentService !== ready) { f.awaitQueue(1); f.drain() }
        assertSame(ready, f.owner.currentService)
        assertEquals(2, f.native.creations.get())
        assertEquals(0, f.jointInvalidations)
        f.close(ready)
    }

    @Test fun legacyDiagnosticStaleSourceOrLateBusyAvailabilityCannotStartAnotherService() {
        for (retireSource in listOf(false, true)) {
            val f = Fixture()
            f.requiresJoint = false
            f.joint = jointChoice(JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.CPU, 1),
                RuntimeCandidate(RuntimeBackend.CPU, 1)))
            val availability = CompletableFuture<VisionReleaseReceipt>()
            f.native.loadFailure = IllegalStateException("adapter wrapper", VisionCohortBusyException(availability))
            f.start()
            val rejected = requireNotNull(f.owner.loadingService)
            assertThrows(java.util.concurrent.ExecutionException::class.java) { rejected.start().get(3, TimeUnit.SECONDS) }
            rejected.releasedFuture().get(3, TimeUnit.SECONDS)
            f.awaitQueue(1); f.drain()
            if (retireSource) { f.sourceCurrent = false; f.owner.startForWalk(null, { null }, { null }) }
            else f.nowNs += TimeUnit.SECONDS.toNanos(15L)
            f.native.loadFailure = null
            availability.complete(VisionReleaseReceipt("old-cohort", 71))
            f.drain()
            assertEquals(1, f.native.creations.get())
            assertEquals(0, f.jointInvalidations)
            assertNotNull(f.joint)
            assertNull(f.owner.currentService)
        }
    }

    @Test fun productionWalkingBindsTheAlreadyPreparedAuxiliaryAndItsExactPairCadence() {
        val f = Fixture()
        f.requiresJoint = true
        val candidate = JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.CPU, 1),
            RuntimeCandidate(RuntimeBackend.CPU, 1), primaryIntervalMs = 300L, auxiliaryIntervalMs = 500L)
        f.joint = jointChoice(candidate)

        val slot = requireNotNull(f.owner.reservePreparedPair { true })
        val service = f.createPairService(slot)
        assertTrue(f.owner.registerPreparedPair(slot, service))
        service.start().get(3, TimeUnit.SECONDS)
        assertTrue(f.owner.adoptPreparedPair(slot, service, requireNotNull(f.joint)))
        f.start()
        assertSame(service, f.owner.currentService)
        val options = f.createdOptions.single()
        assertEquals(FastSamRuntimeOptions.Backend.CPU, options.requestedBackend)
        assertEquals(1, options.numThreads)
        assertFalse(options.allowCpuFallback)
        assertEquals(800L, options.maximumResultAgeMs)
        assertEquals(500L, f.owner.minimumAuxiliaryIntervalMs(service))
        assertEquals(0, f.legacyLoads)
        f.close(service)
    }

    @Test fun missingJointChoiceBlocksProductionWalkingInsteadOfStartingAnUnmeasuredDefault() {
        val f = Fixture()
        f.requiresJoint = true
        f.start()
        assertNull(f.owner.currentService)
        assertNull(f.owner.loadingService)
        assertEquals(0, f.native.creations.get())
        assertEquals(0, f.legacyLoads)
        assertTrue(f.owner.status.contains("기기 점검 필요"))
    }

    @Test fun aJointChoiceForAnotherPrimaryCannotConfigureWalkingAuxiliary() {
        val f = Fixture()
        f.requiresJoint = true
        f.joint = jointChoice(JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.GPU, 1),
            RuntimeCandidate(RuntimeBackend.CPU, 1)))
        f.start()
        assertEquals(0, f.native.creations.get())
        assertNull(f.owner.currentService)
        assertEquals(0, f.legacyLoads)
        assertTrue(f.owner.status.contains("기기 점검 필요"))
    }

    @Test fun legacyDiagnosticRetiredLiveSourceCannotAdoptOrInvalidateItsMeasuredChoice() {
        val f = Fixture()
        f.requiresJoint = false
        f.joint = jointChoice(JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.CPU, 1),
            RuntimeCandidate(RuntimeBackend.CPU, 1)))
        f.native.loadEntered = CountDownLatch(1)
        f.native.releaseLoad = CountDownLatch(1)
        f.start()
        val service = requireNotNull(f.owner.loadingService)
        assertTrue(f.native.loadEntered.await(3, TimeUnit.SECONDS))
        f.sourceCurrent = false
        f.native.releaseLoad.countDown()
        service.start().get(3, TimeUnit.SECONDS)
        f.awaitQueue(1); f.drain()
        service.releasedFuture().get(3, TimeUnit.SECONDS)
        if (f.owner.closing) { f.awaitQueue(1); f.drain() }
        assertNull(f.owner.currentService)
        assertEquals(0, f.jointInvalidations)
        assertNotNull(f.joint)
        assertTrue(service.stats().nativeReleaseConfirmed)
    }

    @Test fun legacyDiagnosticMismatchingAuxiliaryInvalidatesOnlyItsChoiceAndReleases() {
        val f = Fixture()
        f.requiresJoint = false
        // The controlled native engine reports CPU even though this measured choice requests GPU.
        f.joint = jointChoice(JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.CPU, 1),
            RuntimeCandidate(RuntimeBackend.GPU, 1)))
        f.start()
        val service = requireNotNull(f.owner.loadingService)
        service.start().get(3, TimeUnit.SECONDS)
        f.awaitQueue(1); f.drain()
        service.releasedFuture().get(3, TimeUnit.SECONDS)
        if (f.owner.closing) { f.awaitQueue(1); f.drain() }
        assertNull(f.owner.currentService)
        assertEquals(1, f.jointInvalidations)
        assertEquals(0, f.pendingMarks)
        assertTrue(f.owner.status.contains("기기 점검 필요"))
        assertTrue(service.stats().nativeReleaseConfirmed)
    }

    @Test fun legacyDiagnosticOldMismatchingServiceCannotInvalidateANewerMeasuredChoice() {
        val f = Fixture()
        f.requiresJoint = false
        f.joint = jointChoice(JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.CPU, 1),
            RuntimeCandidate(RuntimeBackend.GPU, 1)))
        f.start()
        val service = requireNotNull(f.owner.loadingService)
        service.start().get(3, TimeUnit.SECONDS)
        val newer = jointChoice(JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.CPU, 1),
            RuntimeCandidate(RuntimeBackend.CPU, 1), auxiliaryIntervalMs = 500L))
        f.joint = newer
        f.awaitQueue(1); f.drain()
        service.releasedFuture().get(3, TimeUnit.SECONDS)
        if (f.owner.closing) { f.awaitQueue(1); f.drain() }
        assertSame(newer, f.joint)
        assertEquals(0, f.jointInvalidations)
        assertNull(f.owner.currentService)
        assertTrue(service.stats().nativeReleaseConfirmed)
    }

    private fun jointChoice(candidate: JointRuntimeCandidate): ValidatedJointRuntimeChoice =
        JointRuntimeTestFixtures.choice(candidate)

    @Test fun historicalChoiceWithoutAnActualPreparedPairCannotStartAnIndependentAuxiliary() {
        val f = Fixture()
        f.requiresJoint = true
        val measured = jointChoice(JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.CPU, 1),
            RuntimeCandidate(RuntimeBackend.CPU, 1)))
        f.joint = measured
        f.queueTuning = true
        f.start()
        assertEquals(0, f.native.creations.get())
        assertEquals(0, f.configReads)
        assertTrue(f.tuningTasks.isEmpty())
        assertNull(f.owner.currentService)
        assertNull(f.owner.loadingService)
        assertSame(measured, f.joint)
        assertEquals(0, f.jointInvalidations)
        assertTrue(f.events.single() is UnknownRuntimeServiceEvent.PairPreparationRequired)
    }

    @Test fun walkingCannotStealAPendingAtomicPairReservationOrStartItsOwnFactory() {
        val f = Fixture()
        f.requiresJoint = true
        val slot = requireNotNull(f.owner.reservePreparedPair { true })
        f.start()
        assertTrue(slot.isCurrent())
        assertEquals(0, f.native.creations.get())
        assertNull(f.owner.loadingService)
        assertTrue(f.events.isEmpty())
        f.owner.abortPreparedPair(slot)
    }

    @Test fun strictLegacyCompositionCannotOpenAnAuxiliaryOrStealThePendingPair() {
        val f = Fixture()
        f.requiresJoint = true
        f.prepare(); f.drain()
        assertEquals(0, f.native.creations.get())
        assertEquals(0, f.legacyLoads)
        assertEquals(1, f.events.filterIsInstance<UnknownRuntimeServiceEvent.PairPreparationRequired>().size)
        assertTrue(f.events.last() === UnknownRuntimeServiceEvent.PreparationFinished)
        f.events.clear()
        val slot = requireNotNull(f.owner.reservePreparedPair { true })
        f.prepare(); f.drain()
        assertTrue(slot.isCurrent())
        assertEquals(0, f.native.creations.get())
        assertEquals(listOf(UnknownRuntimeServiceEvent.PreparationFinished), f.events)
        f.owner.abortPreparedPair(slot)
    }

    @Test fun strictLegacyCompositionCompletesWithoutClosingOrReplacingAnAlreadyGoodPair() {
        val f = Fixture()
        f.requiresJoint = true
        val choice = jointChoice(JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.CPU, 1),
            RuntimeCandidate(RuntimeBackend.CPU, 1)))
        val slot = requireNotNull(f.owner.reservePreparedPair { true })
        val service = f.createPairService(slot)
        try {
            assertTrue(f.owner.registerPreparedPair(slot, service))
            service.start().get(3, TimeUnit.SECONDS)
            assertTrue(f.owner.adoptPreparedPair(slot, service, choice))
            f.prepare(); f.drain()
            assertSame(service, f.owner.currentService)
            assertFalse(service.stats().stopping)
            assertEquals(1, f.native.creations.get())
            assertEquals(listOf(UnknownRuntimeServiceEvent.PreparationFinished), f.events)
        } finally { f.close(service) }
    }

    @Test fun aRetiredPreparedAuxiliaryRequestsAtomicPairRecoveryWithoutOpeningAReplacement() {
        val f = Fixture()
        f.requiresJoint = true
        val measured = jointChoice(JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.CPU, 1),
            RuntimeCandidate(RuntimeBackend.CPU, 1)))
        f.joint = measured
        val slot = requireNotNull(f.owner.reservePreparedPair { true })
        val service = f.createPairService(slot)
        assertTrue(f.owner.registerPreparedPair(slot, service))
        service.start().get(3, TimeUnit.SECONDS)
        assertTrue(f.owner.adoptPreparedPair(slot, service, measured))
        f.native.closeEntered = CountDownLatch(1)
        f.native.releaseClose = CountDownLatch(1)
        service.closeAsync()
        try {
            assertTrue(f.native.closeEntered.await(3, TimeUnit.SECONDS))
            f.start()
            assertEquals(1, f.native.creations.get())
            assertTrue(f.owner.closing)
            assertNull(f.owner.currentService)
            assertEquals(1, f.events.filterIsInstance<UnknownRuntimeServiceEvent.PairPreparationRequired>().size)
            assertEquals(0, f.jointInvalidations)
        } finally {
            f.native.releaseClose.countDown()
            service.releasedFuture().get(3, TimeUnit.SECONDS)
        }
        f.awaitQueue(1); f.drain()
        assertEquals(1, f.native.creations.get())
        assertNull(f.owner.loadingService)
    }

    @Test fun explicitCheckReturnsItsWarmedModelBeforePublishingCompletion() {
        val f = Fixture()
        f.needsRecheck = true
        val request = AtomicReference<UnknownCalibrationRequest>()
        f.calibrationFactory = { value ->
            request.set(value)
            object : UnknownCalibrationSession {
                override val isRunning = true
                override val isDraining = false
                override fun start() = true
                override fun cancel(reason: String) {}
            }
        }
        f.prepare(); f.drain()
        val check = requireNotNull(request.get())
        val ready = f.native.create(check.serviceEpoch,
            FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU, 1, false,
                f.elapsedRealtimeNanos() + 60_000_000_000L, 800L, false),
            FastSamRuntimeService.Listener<UnknownRuntimeAttachment> {}, f::elapsedRealtimeNanos)
        ready.start().get(3, TimeUnit.SECONDS)
        check.onSelected(UnknownRuntimeSelection(null, ready, RuntimeCandidate(RuntimeBackend.CPU, 1),
            1L, { swap -> swap() }, {}))
        assertSame(ready, f.owner.currentService)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.native.closeEntered = entered
        f.native.releaseClose = release
        try {
            check.onClosed(RuntimeCalibrationResult(RuntimeSelectionStatus.PARTIAL, "unconfirmed", null,
                null, true, true, null))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertNull("The separate HOME camera must not share another idle FastSAM", f.owner.currentService)
            assertTrue(f.owner.closing)
            assertFalse("Completion cannot precede native release proof",
                f.events.any { it is UnknownRuntimeServiceEvent.CalibrationClosed })
        } finally { release.countDown() }
        ready.releasedFuture().get(3, TimeUnit.SECONDS)
        f.awaitQueue(1); f.drain()
        assertFalse(f.owner.closing)
        assertEquals(1, f.events.count { it is UnknownRuntimeServiceEvent.CalibrationClosed })
        assertEquals(1, f.native.nativeCloses.get())
    }

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
            { fail("Blocked source must not read candidate"); null })
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

    @Test fun primaryCalibrationJoinsAnAlreadyRequestedLoadingReleaseAndWaitsForItsNativeAck() {
        val f = Fixture()
        f.start()
        val service = requireNotNull(f.owner.loadingService)
        service.start().get(3, TimeUnit.SECONDS)
        f.awaitQueue(1)
        f.native.closeEntered = CountDownLatch(1)
        f.native.releaseClose = CountDownLatch(1)
        f.owner.releaseLoadingIfPresent()
        assertTrue(f.native.closeEntered.await(3, TimeUnit.SECONDS))
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val result = AtomicReference<Boolean?>()
        val release = thread {
            started.countDown()
            try { result.set(f.owner.releaseBeforePrimaryCalibration()) }
            finally { finished.countDown() }
        }
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            assertFalse("A normal close already in progress is not an immediate failure", finished.await(100, TimeUnit.MILLISECONDS))
            assertTrue(f.owner.closing)
        } finally { f.native.releaseClose.countDown() }
        release.join(3_000L)
        assertFalse(release.isAlive)
        assertEquals(true, result.get())
        assertTrue(service.stats().nativeReleaseConfirmed)
        assertFalse(f.owner.closing)
        assertNull(f.owner.loadingService)
        assertEquals(1, f.native.nativeCloses.get())
    }

    @Test fun primaryCalibrationRetiresAQueuedEmptyPreparationWithoutWaitingOnItsOwnTuningLane() {
        val f = Fixture()
        f.queueTuning = true
        f.start()
        assertEquals(1, f.tuningTasks.size)
        assertNull(f.owner.loadingService)
        assertTrue(f.owner.releaseBeforePrimaryCalibration())
        requireNotNull(f.tuningTasks.pollFirst()).run()
        assertEquals(0, f.native.creations.get())
        assertNull(f.owner.currentService)
        assertNull(f.owner.loadingService)
        assertFalse(f.owner.closing)
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
        var beforeServiceReturned: () -> Unit = {}
        var onInvalidation: () -> Unit = {}
        var invalidations = 0
        var authorityReads = 0
        var mayStart = true
        var pendingMarks = 0
        var needsRecheck = false
        var requiresJoint = false
        var joint: ValidatedJointRuntimeChoice? = null
        var jointInvalidations = 0
        var legacyLoads = 0
        val createdOptions = mutableListOf<FastSamRuntimeOptions>()
        var calibrationFactory: ((UnknownCalibrationRequest) -> UnknownCalibrationSession)? = null
        val events = mutableListOf<UnknownRuntimeServiceEvent>()
        var dispatchOnOtherThreadAndWait = false
        var queueTuning = false
        val tuningTasks = ConcurrentLinkedDeque<Runnable>()
        private val config = TwoModelRuntimeConfig("test", null, TwoModelRuntimeConfig.UNIFIED_MODEL_KEY,
            null, null, null, null)
        private val candidate = RuntimeCandidate(RuntimeBackend.CPU, 1)
        private val profileBinding = UnknownRuntimeBinding("a".repeat(64), "b".repeat(64),
            "primary-rgb", "fast-rgb", "primary-decode", "fast-decode", candidate, "schedule",
            "device", "runtime", "environment", "fixture", "c".repeat(64), RuntimeFeatureScope.CAMERA_TRACKING)
        var nowNs = 100_000_000_000L
        override fun elapsedRealtimeMs() = nowNs / 1_000_000L
        override fun elapsedRealtimeNanos() = nowNs
        override fun cpuNanos() = 0L
        val owner = UnknownRuntimeServiceOwner(object : UnknownRuntimeProfileRepository {
            override val requiresJointChoice get() = requiresJoint
            override fun loadJoint(config: TwoModelRuntimeConfig, primary: RuntimeCandidate) = joint
            override fun invalidateJointChoice(choice: ValidatedJointRuntimeChoice): Boolean {
                if (joint != choice) return false
                jointInvalidations++
                joint = null
                return true
            }
            override fun createBinding(config: TwoModelRuntimeConfig, primary: RuntimeCandidate,
                scope: RuntimeFeatureScope): UnknownRuntimeBinding = profileBinding.also { onBinding() }
            override fun load(binding: UnknownRuntimeBinding): UnknownRuntimeProfile? { legacyLoads++; return null }
            override fun needsRecheck(binding: UnknownRuntimeBinding, manual: Boolean) = this@Fixture.needsRecheck
            override fun markPending(binding: UnknownRuntimeBinding, reason: DeviceTuningRecheckReason): Boolean {
                pendingMarks++; return true
            }
        }, object : UnknownRuntimeFactory {
            override fun createService(epoch: Long, options: FastSamRuntimeOptions,
                listener: FastSamRuntimeService.Listener<UnknownRuntimeAttachment>): FastSamRuntimeService<UnknownRuntimeAttachment> =
                native.create(epoch, options.also(createdOptions::add), listener, this@Fixture::elapsedRealtimeNanos)
                    .also { beforeServiceReturned() }
            override fun createCalibration(request: UnknownCalibrationRequest): UnknownCalibrationSession =
                calibrationFactory?.invoke(request) ?: error("No calibration factory call is expected")
        }, object : UnknownRuntimeServiceAuthority {
            override fun destroyedOrDetectorClosing(): Boolean { authorityReads++; return false }
            override fun mayStartWalkingService(): Boolean { authorityReads++; return mayStart }
            override fun calibrationCameraAvailable(): Boolean { authorityReads++; return true }
            override fun speechActive(): Boolean { authorityReads++; return false }
        }, this, Executor { if (queueTuning) tuningTasks.addLast(it) else it.run() }, { action ->
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
        fun createPairService(slot: UnknownRuntimeServiceOwner.PreparedPairSlot): FastSamRuntimeService<UnknownRuntimeAttachment> =
            native.create(slot.epoch, FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU, 1, false,
                nowNs + TimeUnit.SECONDS.toNanos(60), 800L, true, false, true).also(createdOptions::add), slot.listener,
                this::elapsedRealtimeNanos)
        fun start(source: UnknownWalkingSource? = source()) = owner.startForWalk(source,
            { configReads++; config }, { candidate })
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
