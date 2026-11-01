package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import android.media.Image
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.inference.AndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.DetectorCohortRetired
import kr.co.hanium.dreamup.walksafe.inference.NoopAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.OwnedYuvFrame
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

class PrimaryDetectorExecutionOwnerTest {
    @Test fun defaultNativeExecutorRunsDeadlineWorkOneStepAboveNormalAndStillDrainsOnClose() {
        val caller = Thread.currentThread()
        val tuning = Lane()
        val owner = PrimaryDetectorExecutionOwner(
            stateLock = Any(), tuningExecutor = tuning,
            elapsedRealtimeMs = { error("priority observation must not read the clock") },
            nativeScheduler = Scheduler(), rejectBlockedWork = { false },
            auxiliaryCopyInFlight = { false }, onDueButInFlightDrop = {},
            onNativeStall = { _, _ -> error("priority observation must not start a flight") },
            dispatchOnMain = { it() },
        )
        val lane = owner.nativeLane as ExecutorService
        val observed = FutureTask { Thread.currentThread() to Thread.currentThread().priority }
        try {
            lane.execute(observed)
            val (worker, priority) = observed.get(5, TimeUnit.SECONDS)
            assertNotSame(caller, worker)
            assertEquals("walksafe-tflite-detector", worker.name)
            assertEquals(Thread.NORM_PRIORITY + 1, priority)
        } finally {
            owner.closeAfterCalibrationDrains()
            tuning.runNext()
            val terminated = lane.awaitTermination(5, TimeUnit.SECONDS)
            if (!terminated) lane.shutdownNow()
            assertTrue("Default native executor must drain queued close", terminated)
        }
        assertTrue(owner.observe().closing)
        assertTrue(lane.isShutdown)
    }

    @Test fun constructorDoesNotReadClockOrSchedulerOrDomainAuthority() {
        val scheduler = Scheduler()
        val lane = Lane()
        val owner = PrimaryDetectorExecutionOwner(Any(), lane, Lane(),
            { error("constructor clock") }, scheduler,
            { error("constructor authority") }, { error("constructor auxiliary") },
            { error("constructor drop effect") }, { _, _ -> error("constructor stall") },
            { error("constructor dispatcher") })
        assertTrue(owner.observe().detector is NoopAndroidFrameDetector)
        assertFalse(owner.observe().available)
        assertFalse(owner.observe().closing)
        assertEquals(0, scheduler.posts.size)
        assertEquals(0, lane.queue.size)
    }

    @Test fun nativeAdoptionAndOriginalUiAvailabilityRemainSeparateStages() {
        val f = Fixture()
        val detector = Detector()
        val claim = f.owner.beginLoad()
        val result = PrimaryLoadedDetector(detector, true)
        assertTrue(f.owner.adoptLoadedOnNativeLane(claim, result) { false })
        assertSame(detector, f.owner.observe().detector)
        assertFalse(f.owner.observe().available)
        assertTrue(f.owner.admitLoadedOnMain(claim, result) { false })
        assertTrue(f.owner.observe().available)
    }

    @Test fun destroyedLoadCannotReplaceThePointerOrAdmitAvailability() {
        val f = Fixture()
        val original = f.owner.observe().detector
        val claim = f.owner.beginLoad()
        val result = PrimaryLoadedDetector(Detector(), true)
        assertFalse(f.owner.adoptLoadedOnNativeLane(claim, result) { true })
        assertFalse(f.owner.admitLoadedOnMain(claim, result) { true })
        assertSame(original, f.owner.observe().detector)
        assertFalse(f.owner.observe().available)
    }

    @Test fun closingInvalidatesQueuedLoadAndKeepsItsGenerationOwner() {
        val f = Fixture()
        val claim = f.owner.beginLoad()
        f.owner.closeAfterCalibrationDrains()
        assertFalse(f.owner.adoptLoadedOnNativeLane(claim, PrimaryLoadedDetector(Detector(), true)) { false })
        assertTrue(f.owner.observe().closing)
        assertEquals(claim.generation + 1L, f.owner.observe().loadGeneration)
    }

    @Test fun reloadDrainsBeforeClosingOutsideTheFrameLockAndRequiresANewGeneration() {
        val f = Fixture(); val previous = Detector(); val claim = f.owner.beginLoad()
        val loaded = PrimaryLoadedDetector(previous, true)
        assertTrue(f.owner.adoptLoadedOnNativeLane(claim, loaded) { false })
        assertTrue(f.owner.admitLoadedOnMain(claim, loaded) { false })
        val order = mutableListOf<String>(); var next: PrimaryDetectorLoadClaim? = null
        f.owner.nativeLane.execute { order += "old invocation completed" }
        f.owner.nativeLane.execute {
            next = f.owner.retireLoadedForReloadOnNativeLane(claim, { true }) { owned ->
                assertFalse(Thread.holdsLock(f.lock))
                assertSame(previous, owned)
                assertFalse(f.owner.projection().available); assertTrue(f.owner.projection().closing)
                assertTrue(f.owner.projection().detector is NoopAndroidFrameDetector)
                order += "close"; (owned as Closeable).close()
            }
        }
        f.lane.runNext(); assertEquals(0, previous.closes)
        f.lane.runNext(); assertEquals(listOf("old invocation completed", "close"), order)
        assertEquals(1, previous.closes); assertEquals(claim.generation + 1L, next?.generation)
        assertFalse(f.owner.projection().closing); assertFalse(f.owner.projection().available)
        assertFalse(f.owner.adoptLoadedOnNativeLane(claim, PrimaryLoadedDetector(Detector(), true)) { false })
        assertTrue(f.owner.adoptLoadedOnNativeLane(requireNotNull(next), PrimaryLoadedDetector(Detector(), true)) { false })
    }
    @Test fun failedReloadReleaseRetainsTheFenceAndCannotAdmitAnotherNativeOwner() {
        val f = Fixture(); val previous = Detector(); val claim = f.owner.beginLoad()
        f.owner.adoptLoadedOnNativeLane(claim, PrimaryLoadedDetector(previous, true)) { false }
        val failure = IllegalStateException("close"); previous.closeFailure = failure
        assertSame(failure, runCatching {
            f.owner.retireLoadedForReloadOnNativeLane(claim, { true }) { (it as Closeable).close() }
        }.exceptionOrNull())
        assertTrue(f.owner.projection().closing); assertFalse(f.owner.projection().available)
        assertNull(f.owner.retireLoadedForReloadOnNativeLane(f.owner.beginLoad(), { true }) { error("no release retry") })
        assertFalse(f.owner.adoptLoadedOnNativeLane(f.owner.beginLoad(), PrimaryLoadedDetector(Detector(), true)) { false })
        assertEquals(1, previous.closes)
        f.owner.closeAfterCalibrationDrains(); f.tuning.runNext(); f.lane.runNext()
        assertTrue(f.owner.closedFuture().isCompletedExceptionally)
        assertSame(failure, runCatching { f.owner.closedFuture().join() }.exceptionOrNull()!!.cause)
        assertEquals(1, previous.closes)
    }
    @Test fun terminalCloseDuringReloadReleaseIsNeverReopenedByTheReleaseAcknowledgement() {
        val f = Fixture(); val claim = f.owner.beginLoad()
        f.owner.adoptLoadedOnNativeLane(claim, PrimaryLoadedDetector(Detector(), true)) { false }
        assertNull(f.owner.retireLoadedForReloadOnNativeLane(claim, { true }) {
            f.owner.fenceForClose(); (it as Closeable).close()
        })
        assertTrue(f.owner.projection().closing)
    }

    @Test fun invocationUsesThePointerAtNativeInvocationTimeAndTheSameOwnedFrame() {
        val f = Fixture()
        val first = Detector()
        val next = Detector()
        val input = frame()
        assertTrue(f.owner.adoptLoadedOnNativeLane(f.owner.beginLoad(), PrimaryLoadedDetector(first, true)) { false })
        assertTrue(f.owner.adoptLoadedOnNativeLane(f.owner.beginLoad(), PrimaryLoadedDetector(next, true)) { false })
        var partials = 0
        f.owner.detectOriented(input, 91L, 3) { partials += 1 }
        assertEquals(0, first.invocations)
        assertEquals(1, next.invocations)
        assertSame(input, next.frame)
        assertEquals(91L, next.timestamp)
        assertEquals(3, next.quarterTurns)
        assertEquals(1, partials)
    }

    @Test fun rejectionAndAuxiliaryCopyGuardsRetainTheirOriginalLazyOrder() {
        val f = Fixture()
        f.reject = true
        assertNull(f.owner.tryStartWork(0L))
        assertEquals(listOf("reject"), f.events)
        f.events.clear()
        f.reject = false
        f.auxiliary = true
        assertNull(f.owner.tryStartWork(0L))
        assertEquals(listOf("reject", "auxiliary"), f.events)
        assertFalse(f.owner.observe().workInFlight)
    }

    @Test fun anInFlightDueFrameCountsADropWithoutTakingAnotherPacingTicket() {
        val f = Fixture()
        assertNotNull(f.owner.tryStartWork(0L))
        val due = f.owner.pacingSnapshot().nextEligibleAtElapsedRealtimeMs
        assertNull(f.owner.tryStartWork(due))
        assertEquals(1, f.drops)
        assertTrue(f.owner.observe().workInFlight)
    }

    @Test fun cancellationDoesNotReadCompletionClockOrLoadFactsAndReleasesThePhysicalSlot() {
        val f = Fixture()
        val ticket = requireNotNull(f.owner.tryStartWork(0L))
        f.owner.finishWork(ticket, false,
            observation = { error("canceled work observation") },
            onValidCompletion = { _, _ -> error("canceled work completion") })
        assertEquals(0, f.clockReads)
        assertFalse(f.owner.observe().workInFlight)
        // cancel retains the previously admitted start deadline, exactly as the pacing policy does.
        assertNull(f.owner.tryStartWork(0L))
        assertNotNull(f.owner.tryStartWork(f.owner.pacingSnapshot().nextEligibleAtElapsedRealtimeMs))
    }

    @Test fun completionFactsExecuteAtTheOriginalSharedLockAndCannotKeepInFlightOnFailure() {
        val f = Fixture()
        val ticket = requireNotNull(f.owner.tryStartWork(0L))
        val failure = IllegalStateException("observation")
        assertSame(failure, runCatching {
            f.owner.finishWork(ticket, true,
                observation = { assertTrue(Thread.holdsLock(f.lock)); throw failure },
                onValidCompletion = { _, _ -> error("observation failed") })
        }.exceptionOrNull())
        assertEquals(1, f.clockReads)
        assertFalse(f.owner.observe().workInFlight)
    }

    @Test fun validCompletionKeepsTheOriginalElapsedDurationAndSourceAgeBound() {
        val f = Fixture()
        val ticket = requireNotNull(f.owner.tryStartWork(20L))
        f.now = 130L
        var sample: Pair<Long, Boolean>? = null
        f.owner.finishWork(ticket, true, inferenceDurationMs = 80L, validCompletion = true,
            observation = { assertEquals(130L, it); PrimaryCompletionObservation(false, null) },
            onValidCompletion = { end, stale -> sample = end to stale })
        assertEquals(110L to false, sample)
        assertFalse(f.owner.observe().workInFlight)
    }

    @Test fun nativeFlightRetainsItsDeadlineAndExpiredTimeoutRequestsStopOnlyOnce() {
        val f = Fixture()
        val flight = requireNotNull(f.owner.beginNativeFlight(f.epoch, 4))
        assertEquals(800L, flight.ticket.deadlineElapsedRealtimeMs)
        assertEquals(800L, f.scheduler.posts.single().second)
        f.now = 800L
        f.scheduler.posts.single().first.run()
        f.owner.completeNativeFlight(flight)
        f.owner.requestNativeStall(flight)
        assertEquals(listOf(f.epoch to 4), f.stalls)
        assertTrue(f.owner.nativeWorkBlocked())
    }

    @Test fun failedNativeTimerAdmissionCompletesTheTicketWithoutStartingASdkFlight() {
        val f = Fixture()
        f.scheduler.accept = false
        assertNull(f.owner.beginNativeFlight(f.epoch, 2))
        f.scheduler.accept = true
        assertNotNull(f.owner.beginNativeFlight(f.epoch, 2))
        assertTrue(f.stalls.isEmpty())
    }

    @Test fun successfulNativeCompletionRejectsItsLateTimerAndDoesNotPoisonTheNextFlight() {
        val f = Fixture()
        val first = requireNotNull(f.owner.beginNativeFlight(f.epoch, 2))
        f.now = 10L
        f.owner.completeNativeFlight(first)
        f.now = 1_000L
        first.timeout.run()
        assertTrue(f.stalls.isEmpty())
        assertFalse(f.owner.nativeWorkBlocked())
        assertNotNull(f.owner.beginNativeFlight(f.epoch, 2))
    }

    @Test fun nativePublicationCannotApplyAfterItsTicketDeadlineOrCompletion() {
        val f = Fixture()
        val flight = requireNotNull(f.owner.beginNativeFlight(f.epoch, 1))
        var published = 0
        assertTrue(f.owner.publishNativeIfCurrent(flight) { published += 1 })
        f.now = 800L
        assertFalse(f.owner.publishNativeIfCurrent(flight) { published += 1 })
        f.owner.completeNativeFlight(flight)
        assertFalse(f.owner.publishNativeIfCurrent(flight) { published += 1 })
        assertEquals(1, published)
    }

    @Test fun staleStallCannotAdvanceGenerationOrRetireTheNewNativeSlot() {
        val f = Fixture()
        val generation = f.owner.retireFrameGeneration()
        var ownerReads = 0
        assertFalse(f.owner.fenceStalledIfCurrent(generation - 1) { ownerReads += 1; true })
        assertEquals(1, ownerReads) // original stall path reads the walk before comparing generation.
        assertFalse(f.owner.fenceStalledIfCurrent(generation) { false })
        assertFalse(f.owner.observe().closing)
        assertTrue(f.owner.fenceStalledIfCurrent(generation) { true })
        assertEquals(generation + 1, f.owner.observe().frameGeneration)
        assertTrue(f.owner.observe().closing)
        assertFalse(f.owner.observe().available)
    }

    @Test fun teardownWaitsInTheSamePhysicalLaneAndDoesNotCloseDuringAnEarlierInvoke() {
        val f = Fixture()
        val detector = Detector()
        f.owner.adoptLoadedOnNativeLane(f.owner.beginLoad(), PrimaryLoadedDetector(detector, true)) { false }
        f.lane.execute { f.events += "invoke" }
        f.owner.closeAfterCalibrationDrains()
        assertEquals(0, detector.closes)
        assertFalse(f.lane.isShutdown)
        assertTrue(f.tuning.isShutdown)
        f.lane.runNext()
        assertEquals(listOf("invoke"), f.events)
        assertEquals(0, detector.closes)
        assertFalse(f.owner.closedFuture().isDone)
        f.tuning.runNext()
        assertTrue(f.lane.isShutdown)
        f.lane.runNext()
        assertEquals(1, detector.closes)
        assertTrue(f.owner.observe().detector is NoopAndroidFrameDetector)
        assertTrue(f.owner.closedFuture().isDone)
        assertFalse(f.owner.closedFuture().isCompletedExceptionally)
    }

    @Test fun rejectedTeardownDoesNotFallBackToInlineNativeClose() {
        val f = Fixture()
        val detector = Detector()
        f.owner.adoptLoadedOnNativeLane(f.owner.beginLoad(), PrimaryLoadedDetector(detector, true)) { false }
        f.lane.reject = true
        f.owner.closeAfterCalibrationDrains()
        f.tuning.runNext()
        assertEquals(0, detector.closes)
        assertSame(detector, f.owner.observe().detector)
        assertTrue(f.owner.observe().closing)
        assertTrue(f.owner.closedFuture().isCompletedExceptionally)
    }

    @Test fun terminalShutdownWaitsForLateNativeCleanupQueuedByAcceptedCreation() {
        val f = Fixture(); val late = Detector(); val order = mutableListOf<String>()
        f.tuning.execute {
            order += "creation completed"
            f.owner.nativeLane.execute { late.close(); order += "late allocation closed" }
        }
        f.owner.closeAfterCalibrationDrains()
        assertFalse(f.lane.isShutdown); assertFalse(f.owner.closedFuture().isDone)
        f.tuning.runNext()
        assertEquals(1, f.lane.queue.size); assertFalse(f.owner.closedFuture().isDone)
        f.tuning.runNext()
        assertTrue(f.lane.isShutdown); assertEquals(2, f.lane.queue.size)
        assertTrue(runCatching { f.owner.nativeLane.execute { error("Late work was accepted") } }.exceptionOrNull() is RejectedExecutionException)
        f.lane.runNext()
        assertEquals(listOf("creation completed", "late allocation closed"), order)
        assertEquals(1, late.closes); assertFalse(f.owner.closedFuture().isDone)
        f.lane.runNext()
        assertEquals(Unit, f.owner.closedFuture().getNow(null))
    }

    @Test fun repeatedShutdownKeepsOneFutureAndOneTerminalClose() {
        val f = Fixture(); val detector = Detector()
        f.owner.adoptLoadedOnNativeLane(f.owner.beginLoad(), PrimaryLoadedDetector(detector, true)) { false }
        val future = f.owner.closedFuture()
        assertFalse(future.isDone)
        f.owner.closeAfterCalibrationDrains(); f.owner.closeAfterCalibrationDrains()
        assertSame(future, f.owner.closedFuture()); assertEquals(1, f.tuning.queue.size)
        f.tuning.runNext(); assertEquals(1, f.lane.queue.size)
        f.owner.closeAfterCalibrationDrains(); f.lane.runNext()
        assertEquals(1, detector.closes); assertEquals(Unit, future.getNow(null))
        assertTrue(f.tuning.queue.isEmpty()); assertTrue(f.lane.queue.isEmpty())
    }

    @Test fun terminalNativeCloseFailureIsExceptionalAndRetainsTheFailedOwner() {
        val f = Fixture(); val failure = IllegalStateException("native close")
        val detector = Detector().also { it.closeFailure = failure }
        f.owner.adoptLoadedOnNativeLane(f.owner.beginLoad(), PrimaryLoadedDetector(detector, true)) { false }
        f.owner.closeAfterCalibrationDrains(); f.tuning.runNext(); f.lane.runNext()
        assertTrue(f.owner.closedFuture().isCompletedExceptionally)
        assertSame(failure, runCatching { f.owner.closedFuture().join() }.exceptionOrNull()!!.cause)
        assertSame(detector, f.owner.projection().detector)
        assertTrue(f.owner.projection().closing); assertFalse(f.owner.projection().available)
        f.owner.closeAfterCalibrationDrains(); assertEquals(1, detector.closes)
    }

    @Test fun remoteCloseFailureWaitsForLatePhysicalProofAfterBothConsumerBarriers() {
        val f = Fixture(); val physical = CompletableFuture<Unit>()
        val detector = Detector().also { it.closeFailure = DetectorCohortRetired(IllegalStateException("close timeout"), physical) }
        f.owner.adoptLoadedOnNativeLane(f.owner.beginLoad(), PrimaryLoadedDetector(detector, true)) { false }
        val closed = f.owner.closedFuture()
        f.owner.closeAfterCalibrationDrains(); f.tuning.runNext(); f.lane.runNext()
        assertFalse("A semantic timeout cannot finish the physical release future", closed.isDone)
        assertSame(detector, f.owner.projection().detector)
        physical.complete(Unit)
        assertEquals(Unit, closed.getNow(null))
        assertTrue(f.owner.projection().detector is NoopAndroidFrameDetector)
        assertEquals(1, detector.closes)
    }

    @Test fun earlyChildDeathCannotAcknowledgeAcceptedConsumerWorkBeforeItsBarrier() {
        val f = Fixture(); val physical = CompletableFuture<Unit>()
        f.owner.recordReleaseFailure(DetectorCohortRetired(IllegalStateException("OPEN timeout"), physical))
        f.lane.execute { f.events += "accepted consumer drained" }
        f.owner.closeAfterCalibrationDrains()
        physical.complete(Unit)
        assertFalse(f.owner.closedFuture().isDone)
        f.tuning.runNext(); f.lane.runNext()
        assertEquals(listOf("accepted consumer drained"), f.events)
        assertFalse(f.owner.closedFuture().isDone)
        f.lane.runNext()
        assertEquals(Unit, f.owner.closedFuture().getNow(null))
    }

    @Test fun childDeathCannotRepairARejectedConsumerDrainBarrier() {
        val f = Fixture(); val physical = CompletableFuture<Unit>()
        f.owner.recordReleaseFailure(DetectorCohortRetired(IllegalStateException("OPEN timeout"), physical))
        f.lane.reject = true
        f.owner.closeAfterCalibrationDrains(); f.tuning.runNext()
        assertTrue(f.owner.closedFuture().isCompletedExceptionally)
        physical.complete(Unit)
        assertTrue(f.owner.closedFuture().isCompletedExceptionally)
        assertTrue(f.owner.projection().closing)
    }

    @Test fun aNormalCloseReturnStillWaitsForTheIndependentModelReleaseReceipt() {
        val f = Fixture(); val physical = CompletableFuture<Unit>(); val detector = Detector()
        f.owner.retainPhysicalRelease(physical, detector)
        f.owner.adoptLoadedOnNativeLane(f.owner.beginLoad(), PrimaryLoadedDetector(detector, true)) { false }
        f.owner.closeAfterCalibrationDrains(); f.tuning.runNext(); f.lane.runNext()
        assertEquals(1, detector.closes)
        assertSame(detector, f.owner.projection().detector)
        assertFalse(f.owner.closedFuture().isDone)
        physical.complete(Unit)
        assertEquals(Unit, f.owner.closedFuture().getNow(null))
        assertTrue(f.owner.projection().detector is NoopAndroidFrameDetector)
    }

    @Test fun aRegisteredPhysicalReceiptCanResolveAWrappedCloseErrorWithoutHidingTheFailure() {
        val f = Fixture(); val physical = CompletableFuture<Unit>(); val failure = IllegalStateException("wrapper close")
        val detector = Detector().also { it.closeFailure = failure }
        f.owner.retainPhysicalRelease(physical, detector)
        f.owner.adoptLoadedOnNativeLane(f.owner.beginLoad(), PrimaryLoadedDetector(detector, true)) { false }
        f.owner.closeAfterCalibrationDrains(); f.tuning.runNext(); f.lane.runNext()
        assertFalse(f.owner.closedFuture().isDone)
        assertTrue(f.owner.projection().closing); assertFalse(f.owner.projection().available)
        assertSame(failure, detector.closeFailure)
        physical.complete(Unit)
        assertEquals(Unit, f.owner.closedFuture().getNow(null))
        assertTrue(f.owner.projection().closing) // Physical recovery does not authorize another load.
    }

    @Test fun reloadCannotAllocateAnotherOwnerFromACloseReturnWithoutPhysicalProof() {
        val f = Fixture(); val physical = CompletableFuture<Unit>(); val previous = Detector()
        f.owner.retainPhysicalRelease(physical, previous)
        val claim = f.owner.beginLoad()
        f.owner.adoptLoadedOnNativeLane(claim, PrimaryLoadedDetector(previous, true)) { false }
        assertTrue(runCatching {
            f.owner.retireLoadedForReloadOnNativeLane(claim, { true }) { (it as Closeable).close() }
        }.exceptionOrNull() is IllegalStateException)
        assertTrue(f.owner.projection().closing)
        assertFalse(f.owner.adoptLoadedOnNativeLane(f.owner.beginLoad(), PrimaryLoadedDetector(Detector(), true)) { false })
        f.owner.closeAfterCalibrationDrains(); f.tuning.runNext(); f.lane.runNext()
        assertFalse(f.owner.closedFuture().isDone)
        physical.complete(Unit)
        assertEquals(Unit, f.owner.closedFuture().getNow(null))
        assertEquals(1, previous.closes)
    }

    @Test fun rejectedTuningBarrierCannotAcknowledgeOrCloseInline() {
        val f = Fixture(); val detector = Detector()
        f.owner.adoptLoadedOnNativeLane(f.owner.beginLoad(), PrimaryLoadedDetector(detector, true)) { false }
        f.tuning.reject = true
        f.owner.closeAfterCalibrationDrains()
        assertTrue(f.owner.closedFuture().isCompletedExceptionally)
        assertEquals(0, detector.closes); assertTrue(f.lane.queue.isEmpty())
        assertFalse(f.lane.isShutdown)
    }

    @Test fun earlierUnconfirmedCloseCannotBecomeSuccessAfterALaterNoThrowClose() {
        val f = Fixture(); val failure = IllegalStateException("first release")
        val detector = Detector().also { it.closeFailure = failure }
        f.owner.adoptLoadedOnNativeLane(f.owner.beginLoad(), PrimaryLoadedDetector(detector, true)) { false }
        assertSame(failure, runCatching { f.owner.disableFailedIfCurrent({ true }, {}, {}) }.exceptionOrNull())
        detector.closeFailure = null
        f.owner.closeAfterCalibrationDrains(); f.tuning.runNext(); f.lane.runNext()
        assertTrue(f.owner.closedFuture().isCompletedExceptionally)
        assertSame(failure, runCatching { f.owner.closedFuture().join() }.exceptionOrNull()!!.cause)
    }

    @Test fun originalUiMetadataCommitPrecedesAvailabilityWithoutANewFrameTransaction() {
        val f = Fixture()
        val result = PrimaryLoadedDetector(Detector(), true)
        assertTrue(f.owner.admitLoadedOnMain(f.owner.beginLoad(), result,
            beforeAvailability = {
                assertFalse(Thread.holdsLock(f.lock))
                assertFalse(f.owner.projection().available)
                f.events += "config"
            }, destroyed = { false }))
        assertEquals(listOf("config"), f.events)
        assertTrue(f.owner.projection().available)
    }

    @Test fun unverifiedIdentityCannotDetachANewerDetectorButExactIdentityRetiresGenerations() {
        val f = Fixture()
        val detector = Detector()
        f.owner.adoptLoadedOnNativeLane(f.owner.beginLoad(), PrimaryLoadedDetector(detector, true)) { false }
        assertFalse(f.owner.disableUnverified(Detector()) { error("foreign identity commit") })
        assertSame(detector, f.owner.projection().detector)
        assertTrue(f.owner.disableUnverified(detector) {
            assertTrue(Thread.holdsLock(f.lock))
            assertTrue(f.owner.projection().detector is NoopAndroidFrameDetector)
            assertFalse(f.owner.projection().available)
        })
        assertEquals(1, f.owner.projection().frameGeneration)
        assertEquals(1L, f.owner.projection().loadGeneration)
        assertEquals(0, detector.closes) // queued close is a distinct native-lane stage in Main.
    }

    @Test fun nativeFailureCloseExceptionPreservesTheOldPointerAndDoesNotAdvanceGeneration() {
        val f = Fixture()
        val failure = IllegalStateException("native close")
        val detector = Detector().also { it.closeFailure = failure }
        f.owner.adoptLoadedOnNativeLane(f.owner.beginLoad(), PrimaryLoadedDetector(detector, true)) { false }
        assertSame(failure, runCatching {
            f.owner.disableFailedIfCurrent({ true }, { error("close failed before reset") }, { error("close failed before status") })
        }.exceptionOrNull())
        assertSame(detector, f.owner.projection().detector)
        assertEquals(0, f.owner.projection().frameGeneration)
        assertEquals(1, detector.closes)
    }

    @Test fun measuredRestorationMaintainsPointerGenerationAndVerificationCommitOrder() {
        val f = Fixture()
        val detector = Detector()
        assertFalse(f.owner.restoreMeasuredIfCurrent(detector, { false }) { error("stale measurement") })
        assertTrue(f.owner.restoreMeasuredIfCurrent(detector, { true }) {
            assertTrue(Thread.holdsLock(f.lock))
            assertSame(detector, f.owner.projection().detector)
            assertEquals(1L, f.owner.projection().loadGeneration)
        })
    }

    @Test fun selectionAdoptionPreservesPendingCloseAndMetadataBeforeGeneration() {
        val f = Fixture()
        val original = f.owner.projection().detector
        val next = Detector()
        assertTrue(f.owner.adoptMeasuredIfCurrent(next, { true },
            beforeInstall = { assertSame(original, f.owner.projection().detector); f.events += "retain" },
            afterInstall = { assertSame(next, f.owner.projection().detector); assertEquals(0L, f.owner.projection().loadGeneration); f.events += "metadata" },
            afterGeneration = { assertEquals(1L, f.owner.projection().loadGeneration); f.events += "verify" }))
        assertEquals(listOf("retain", "metadata", "verify"), f.events)
    }

    private class Fixture {
        val lock = Any()
        val lane = Lane()
        val tuning = Lane()
        val scheduler = Scheduler()
        val epoch = WalkRuntimeEpoch("primary", 1L)
        val events = mutableListOf<String>()
        val stalls = mutableListOf<Pair<WalkRuntimeEpoch, Int>>()
        var now = 0L
        var reject = false
        var auxiliary = false
        var drops = 0
        var clockReads = 0
        val owner = PrimaryDetectorExecutionOwner(lock, lane, tuning,
            { clockReads += 1; now }, scheduler,
            { events += "reject"; reject }, { events += "auxiliary"; auxiliary },
            { drops += 1 }, { e, g -> stalls += e to g }, { it() })
    }
    private class Scheduler : PrimaryNativeFlightScheduler {
        val posts = mutableListOf<Pair<Runnable, Long>>()
        var accept = true
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean { posts += task to delayMs; return accept }
        override fun remove(task: Runnable) = Unit
    }
    private class Lane : AbstractExecutorService() {
        val queue = ArrayDeque<Runnable>()
        private var stopped = false
        var reject = false
        override fun execute(command: Runnable) { if (reject || stopped) throw RejectedExecutionException(); queue.addLast(command) }
        fun runNext() { queue.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): List<Runnable> { stopped = true; return queue.toList().also { queue.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && queue.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
    private class Detector : AndroidFrameDetector, Closeable {
        var invocations = 0
        var closes = 0
        var closeFailure: RuntimeException? = null
        var frame: OwnedYuvFrame? = null
        var timestamp = -1L
        var quarterTurns = -1
        override fun detectOriented(cameraFrame: OwnedYuvFrame, timestampMs: Long, quarterTurns: Int,
            onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult {
            invocations += 1; frame = cameraFrame; timestamp = timestampMs; this.quarterTurns = quarterTurns
            return AndroidDetectionResult.unavailable().also(onPartialResult)
        }
        override fun detectOriented(cameraImage: Image, timestampMs: Long, quarterTurns: Int,
            onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult = error("must use owned pixels")
        override fun detect(cameraImage: Image, timestampMs: Long,
            onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult = error("must orient owned pixels")
        override fun detect(cameraFrame: OwnedYuvFrame, timestampMs: Long,
            onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult = error("must orient owned pixels")
        override fun close() { closes += 1; closeFailure?.let { throw it } }
    }
    private fun frame(): OwnedYuvFrame = OwnedYuvFrame.copyOf(2, 2,
        ByteBuffer.wrap(byteArrayOf(0, 0, 0, 0)), ByteBuffer.wrap(byteArrayOf(0)), ByteBuffer.wrap(byteArrayOf(0)),
        2, 1, 1, 1)
}
