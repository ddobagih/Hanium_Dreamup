package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionPurpose
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionCohortBusyException
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionReleaseReceipt
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionStaleAuthorityException
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeException
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*

class JointRuntimeSampleHostTest {
    private val gpuCpu = JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.GPU, 1), RuntimeCandidate(RuntimeBackend.CPU, 1), 250, 500)

    @Test fun nextAcquireWaitsForAllocationAcknowledgementEvenAfterPhysicalRelease() {
        val physical = CompletableFuture<VisionReleaseReceipt>()
        val available = CompletableFuture<VisionReleaseReceipt>()
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val entered = CountDownLatch(1)
        val own = kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionCohortLease("new", "owner", 1)
        val result = CompletableFuture<kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionCohortLease>()
        val worker = Thread {
            try { result.complete(JointRuntimeSampleAcquisition.acquireWhenAvailable(200, { true }, { 100 }) {
                if (attempts.incrementAndGet() == 1) { entered.countDown(); throw VisionCohortBusyException(available) }
                own
            }) } catch (error: Throwable) { result.completeExceptionally(error) }
        }.apply { start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            physical.complete(VisionReleaseReceipt("old", 11))
            assertTrue(physical.isDone); assertFalse(result.isDone); assertEquals(1, attempts.get())
            available.complete(VisionReleaseReceipt("old", 11))
            assertSame(own, result.get(1, TimeUnit.SECONDS)); assertEquals(2, attempts.get())
        } finally { available.complete(VisionReleaseReceipt("old", 11)); worker.join(1_000) }
    }

    @Test fun availabilityCannotRestartTheOriginalDeadlineOrReviveACancelledOrRolledBackSource() {
        for (boundary in listOf("deadline", "cancel", "rollback")) {
            val clock = java.util.concurrent.atomic.AtomicLong(100)
            val current = java.util.concurrent.atomic.AtomicBoolean(true)
            val available = CompletableFuture<VisionReleaseReceipt>()
            val attempts = java.util.concurrent.atomic.AtomicInteger()
            val entered = CountDownLatch(1); val result = CompletableFuture<Throwable?>()
            val worker = Thread {
                result.complete(runCatching {
                    JointRuntimeSampleAcquisition.acquireWhenAvailable(200, current::get, clock::get) {
                        attempts.incrementAndGet(); entered.countDown(); throw VisionCohortBusyException(available)
                    }
                }.exceptionOrNull())
            }.apply { start() }
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                when (boundary) { "deadline" -> clock.set(200); "cancel" -> current.set(false); else -> clock.set(99) }
                available.complete(VisionReleaseReceipt("old", 11))
                assertTrue(boundary, result.get(1, TimeUnit.SECONDS) is JointRuntimeSampleNoNativeStart)
                assertEquals(boundary, 1, attempts.get())
            } finally { current.set(false); available.complete(VisionReleaseReceipt("old", 11)); worker.join(1_000) }
        }
    }

    @Test fun aGpuTrialMeasuresTheProductionConsumerBeforeAnyCpuReferenceAndReturnsOnlyAfterActualRelease() =
        JointRuntimeSampleTestFixture("gpu-first").use { f ->
            val host = f.host(); val budget = f.budget(JointRuntimeSearchPhase.PROVISIONAL)
            val measured = host.measureProvisional(gpuCpu, budget) { f.current }
            assertEquals(gpuCpu, measured.candidate)
            assertTrue(measured.durationMs >= 2_000)
            assertTrue(measured.primaryTurnaroundMs.size >= 2); assertTrue(measured.auxiliaryTurnaroundMs.size >= 2)
            assertTrue(f.primaryRequests.none { it.first == VisionPurpose.REFERENCE })
            assertEquals(VisionPurpose.WARMUP, f.primaryRequests.first().first)
            assertTrue(f.primaryRequests.drop(1).all { it.first == VisionPurpose.BENCHMARK && it.second == budget.deadlineMs })
            assertTrue(f.auxiliaryFactory.requests.drop(1).all { it.purpose == VisionPurpose.BENCHMARK && !it.captureRawOutputs })
            assertEquals(1, f.primaryCreates); assertEquals(1, f.primaryCloses)
            assertTrue(host.actualReleasedFuture.toCompletableFuture().isDone)
            assertEquals(0, f.ledger.snapshot().activeReservations)
            assertEquals(0, f.liveResults.get())
        }

    @Test fun coldLoadFailureDoesNotPreventANewGpuOrMixedTrialAfterTheOldCohortActuallyReleases() {
        val ledger = JointRuntimeMemoryLedger(); val bundle = JointRuntimeSampleTestFixture.sampleFixtures()
        JointRuntimeSampleTestFixture("failed-open", ledger, bundle).use { failed ->
            JointRuntimeSampleTestFixture("next-trial", ledger, bundle).use { next ->
                val marker = IllegalStateException("cold GPU load failed")
                failed.primaryLoadFailure = marker
                val old = failed.host()
                assertSame(marker, runCatching { old.measureProvisional(gpuCpu, failed.budget(JointRuntimeSearchPhase.PROVISIONAL)) { true } }.exceptionOrNull())
                old.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
                val measured = next.host().measureProvisional(gpuCpu, next.budget(JointRuntimeSearchPhase.PROVISIONAL)) { true }
                assertEquals(gpuCpu, measured.candidate)
                assertEquals(1, next.primaryCreates); assertEquals(0, failed.auxiliaryFactory.creates.get())
            }
        }
    }

    @Test fun cancellationDuringAnAcceptedSampleKeepsTheMemoryFenceUntilTheCapturedNativeReceiptArrives() =
        JointRuntimeSampleTestFixture("cancel-late").use { f ->
            val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
            f.holdNative = true
            f.onPrimarySample = { entered.countDown(); check(proceed.await(3, TimeUnit.SECONDS)) }
            val host = f.host(); val pool = Executors.newSingleThreadExecutor()
            try {
                val running = pool.submit<Throwable?> { runCatching { host.measureProvisional(gpuCpu, f.budget(JointRuntimeSearchPhase.PROVISIONAL)) { f.current } }.exceptionOrNull() }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                host.cancel("caller cancelled"); proceed.countDown()
                assertTrue(running.get(3, TimeUnit.SECONDS) is CancellationException)
                assertFalse(host.actualReleasedFuture.toCompletableFuture().isDone)
                assertNull(f.ledger.beginCandidate("must-not-overlap"))
                assertEquals(0, f.liveResults.get())
                f.nativePhysical.complete(Unit)
                host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
                val fresh = f.ledger.beginCandidate("explicit-new-attempt")
                assertNotNull(fresh); assertTrue(f.ledger.endCandidate(requireNotNull(fresh)))
            } finally { proceed.countDown(); pool.shutdownNow() }
        }

    @Test fun invalidCandidateAndExactOrBackwardDeadlinesDoNotDispatchNativePreparation() {
        JointRuntimeSampleTestFixture("invalid-candidate").use { f ->
            val host = f.host()
            assertTrue(runCatching { host.measureProvisional(gpuCpu.copy(primaryIntervalMs = 0), f.budget(JointRuntimeSearchPhase.PROVISIONAL)) { true } }.isFailure)
            host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
            assertEquals(0, f.begins); assertEquals(0, f.primaryCreates)
        }
        for (deadline in listOf(1_000L, 999L)) JointRuntimeSampleTestFixture("expired-$deadline").use { f ->
            val host = f.host()
            assertTrue(runCatching { host.measureProvisional(gpuCpu,
                JointRuntimePhaseBudget(JointRuntimeSearchPhase.PROVISIONAL, 1_000L, deadline)) { true } }.exceptionOrNull() is TimeoutException)
            host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
            assertEquals(0, f.begins)
        }
        JointRuntimeSampleTestFixture("phase-rollback").use { f ->
            val host = f.host()
            assertTrue(runCatching { host.measureProvisional(gpuCpu,
                JointRuntimePhaseBudget(JointRuntimeSearchPhase.PROVISIONAL, 1_001L, 90_000L)) { true } }.exceptionOrNull() is TimeoutException)
            host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
            assertEquals(0, f.begins); assertEquals(0, f.primaryCreates)
        }
    }

    @Test fun aNativeInstanceChangeDuringWarmupOrMeasurementCannotBeAccepted() = JointRuntimeSampleTestFixture("changed-instance").use { f ->
        f.onPrimarySample = { if (f.primaryCalls.get() == 2) f.primaryMismatch = true }
        val host = f.host()
        assertTrue(runCatching { host.measureProvisional(gpuCpu, f.budget(JointRuntimeSearchPhase.PROVISIONAL)) { true } }.isFailure)
        host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
        assertEquals(1, f.primaryCreates); assertEquals(0, f.liveResults.get())
    }

    @Test fun beginBusyDoesNotBorrowAnotherOwnersPhysicalReceiptOrLeaveItsOwnEmptyHostFenced() =
        JointRuntimeSampleTestFixture("begin-busy").use { f ->
            val foreign = CompletableFuture<VisionReleaseReceipt>(); val busy = VisionCohortBusyException(foreign)
            f.beginFailure = busy
            val host = f.host()
            assertSame(busy, runCatching { host.measureProvisional(gpuCpu, f.budget(JointRuntimeSearchPhase.PROVISIONAL)) { true } }.exceptionOrNull())
            host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
            assertFalse(foreign.isDone); assertEquals(0, f.primaryCreates); assertEquals(0, f.auxiliaryFactory.creates.get())
            assertEquals(0, f.ledger.snapshot().activeReservations)
        }

    @Test fun onlyTheDirectAcquireRejectionCanAcknowledgeNoNativeStartWhileAnUnknownPortStaleStaysFenced() {
        JointRuntimeSampleTestFixture("known-no-start").use { f ->
            val marker = runCatching { JointRuntimeSampleAcquisition.acquire { throw VisionStaleAuthorityException() } }.exceptionOrNull()
            assertTrue(marker is JointRuntimeSampleNoNativeStart)
            f.beginFailure = marker
            val host = f.host()
            assertSame(marker, runCatching { host.measureProvisional(gpuCpu, f.budget(JointRuntimeSearchPhase.PROVISIONAL)) { true } }.exceptionOrNull())
            host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
            assertEquals(0, f.primaryCreates); assertEquals(0, f.ledger.snapshot().activeReservations)
        }
        JointRuntimeSampleTestFixture("unknown-port-stale").use { f ->
            f.expectUnconfirmedBegin = true
            val genericStale = VisionStaleAuthorityException(); f.beginFailure = genericStale
            val host = f.host()
            assertSame(genericStale, runCatching { host.measureProvisional(gpuCpu, f.budget(JointRuntimeSearchPhase.PROVISIONAL)) { true } }.exceptionOrNull())
            host.samplingDrainedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
            f.nativePhysical.complete(Unit)
            assertFalse(host.actualReleasedFuture.toCompletableFuture().isDone)
            assertNull(f.ledger.beginCandidate("unproven-lookup-cannot-unlock"))
        }
    }

    @Test fun everyFrameUsesAFreshHeapObservationAndLowHeadroomStopsDispatchWithoutWeakeningTheReserve() =
        JointRuntimeSampleTestFixture("heap-changes").use { f ->
            var reads = 0
            f.heapObservation = {
                reads++
                if (reads == 1) JointRuntimeManagedHeapSnapshot(512L * 1024 * 1024, 128L * 1024 * 1024, 96L * 1024 * 1024)
                else JointRuntimeManagedHeapSnapshot(64L * 1024 * 1024, 64L * 1024 * 1024, 0)
            }
            val host = f.host()
            val error = runCatching { host.measureProvisional(gpuCpu, f.budget(JointRuntimeSearchPhase.PROVISIONAL)) { true } }.exceptionOrNull()
            assertTrue(error is JointRuntimeSampleFailure)
            assertEquals(JointRuntimeSearchFailureKind.RESOURCE_BUDGET, (error as JointRuntimeSampleFailure).failure.kind)
            assertEquals(2, reads); assertEquals(1, f.primaryCalls.get()); assertEquals(0, f.auxiliaryFactory.invokes.get())
            host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
            assertEquals(0, f.ledger.snapshot().activeReservations)
        }

    @Test fun warmPrimaryCacheDoesNotReserveItsAllocatedScratchAgainWhileAuxiliaryIsAdmitted() =
        JointRuntimeSampleTestFixture("warm-scratch-overlap", bundle = JointRuntimeSampleTestFixture.sampleFixtures(768, 768)).use { f ->
            f.heapObservation = { JointRuntimeManagedHeapSnapshot(128L * 1_048_576, 75L * 1_048_576, 0) }
            val host = f.host()
            host.open(gpuCpu, f.budget(JointRuntimeSearchPhase.PROVISIONAL), { f.current })
            val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
            val pool = Executors.newSingleThreadExecutor()
            f.onPrimarySample = { entered.countDown(); check(proceed.await(3, TimeUnit.SECONDS)) }
            try {
                val primary = pool.submit<JointPairPrimarySample> {
                    host.primarySample(host.entries().first(), VisionPurpose.BENCHMARK, false)
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val auxiliary = runCatching { host.auxiliarySample(host.entries().first(), VisionPurpose.BENCHMARK, false) }
                assertNull("Allocated scratch must not make a fitting auxiliary frame fail admission", auxiliary.exceptionOrNull())
                assertEquals(128_000L, f.ledger.snapshot().pendingManagedBytes)
                assertEquals(7_077_888L + 128_000L, f.ledger.snapshot().managedBytes)
                proceed.countDown(); primary.get(3, TimeUnit.SECONDS)
                assertEquals(7_077_888L, f.ledger.snapshot().managedBytes)
            } finally { proceed.countDown(); pool.shutdownNow() }
            host.finish(false)
            host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
            assertEquals(0, f.ledger.snapshot().activeReservations)
        }

    @Test fun scratchOwnershipIsReservedOnceAcrossSamplesAndNeverAccumulatesAcrossHosts() {
        val ledger = JointRuntimeMemoryLedger()
        repeat(3) { attempt ->
            JointRuntimeSampleTestFixture("scratch-attempt-$attempt", ledger).use { f ->
                val host = f.host()
                host.open(gpuCpu, f.budget(JointRuntimeSearchPhase.PROVISIONAL), { true })
                repeat(3) {
                    assertEquals(7_077_888L, ledger.snapshot().managedBytes)
                    assertEquals(0L, ledger.snapshot().pendingManagedBytes)
                    assertEquals(1, ledger.snapshot().activeReservations)
                    host.primarySample(host.entries().first(), VisionPurpose.BENCHMARK, false)
                    assertEquals(1, f.primaryScratchPrepares)
                }
                host.finish(false)
                host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
                assertEquals(0L, ledger.snapshot().managedBytes)
                assertEquals(0L, ledger.snapshot().pendingManagedBytes)
                assertEquals(0, ledger.snapshot().activeReservations)
            }
        }
    }

    @Test fun initialScratchAndFrameMustBothFitBeforeTheFirstPrimaryDispatch() {
        // The second case can fit scratch alone, but not scratch plus the new frame output.
        for (available in listOf(6L * 1_048_576, 7_100_000L)) JointRuntimeSampleTestFixture("scratch-headroom-$available").use { f ->
            f.heapObservation = { JointRuntimeManagedHeapSnapshot(128L * 1_048_576, 96L * 1_048_576 - available, 0) }
            val host = f.host()
            val failure = runCatching {
                host.measureProvisional(gpuCpu, f.budget(JointRuntimeSearchPhase.PROVISIONAL)) { true }
            }.exceptionOrNull()
            assertTrue(failure is JointRuntimeSampleFailure)
            assertEquals(JointRuntimeSearchFailureKind.RESOURCE_BUDGET, (failure as JointRuntimeSampleFailure).failure.kind)
            assertEquals(0, f.primaryScratchPrepares)
            assertEquals(0, f.primaryCalls.get()); assertEquals(0, f.auxiliaryFactory.invokes.get())
            host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
            assertEquals(0, f.ledger.snapshot().activeReservations)
        }
    }

    @Test fun failedOrCancelledScratchPreparationCannotDispatchAndKeepsItsExactAllocationStateUntilDrain() {
        for (cancel in listOf(false, true)) JointRuntimeSampleTestFixture("scratch-prepare-$cancel").use { f ->
            val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
            val drainEntered = CountDownLatch(1); val drainProceed = CountDownLatch(1)
            val marker = IllegalStateException("partial scratch preparation failed")
            f.onPreparePrimaryScratch = {
                entered.countDown(); check(proceed.await(3, TimeUnit.SECONDS))
                if (!cancel) throw marker
            }
            f.onSamplingDrained = { drainEntered.countDown(); check(drainProceed.await(3, TimeUnit.SECONDS)) }
            val host = f.host(); val pool = Executors.newSingleThreadExecutor()
            try {
                val running = pool.submit<Throwable?> {
                    runCatching { host.measureProvisional(gpuCpu, f.budget(JointRuntimeSearchPhase.PROVISIONAL)) { true } }.exceptionOrNull()
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertEquals(7_077_888L + 128_000L, f.ledger.snapshot().pendingManagedBytes)
                if (cancel) host.cancel("cancel during scratch preparation")
                proceed.countDown()
                val failure = running.get(3, TimeUnit.SECONDS)
                if (cancel) assertTrue(failure is CancellationException) else assertSame(marker, failure)
                assertTrue(drainEntered.await(2, TimeUnit.SECONDS))
                assertEquals(1, f.primaryScratchPrepares); assertEquals(0, f.primaryCalls.get())
                assertEquals(2, f.ledger.snapshot().activeReservations)
                assertEquals(if (cancel) 128_000L else 7_077_888L + 128_000L, f.ledger.snapshot().pendingManagedBytes)
                assertFalse(host.actualReleasedFuture.toCompletableFuture().isDone)
                drainProceed.countDown()
                host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
                assertEquals(0, f.ledger.snapshot().activeReservations)
                assertEquals(0L, f.ledger.snapshot().pendingManagedBytes)
            } finally { proceed.countDown(); drainProceed.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun failedOrCancelledPrimaryKeepsScratchOwnershipUntilTheCpuDrainThenSettlesExactlyOnce() {
        for (cancel in listOf(false, true)) JointRuntimeSampleTestFixture("scratch-drain-$cancel").use { f ->
            val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
            val drainEntered = CountDownLatch(1); val drainProceed = CountDownLatch(1)
            val marker = IllegalStateException("primary failed after preparation")
            f.onPrimarySample = {
                entered.countDown(); check(proceed.await(3, TimeUnit.SECONDS))
                if (!cancel) throw marker
            }
            f.onSamplingDrained = { drainEntered.countDown(); check(drainProceed.await(3, TimeUnit.SECONDS)) }
            val host = f.host(); val pool = Executors.newSingleThreadExecutor()
            try {
                val running = pool.submit<Throwable?> {
                    runCatching { host.measureProvisional(gpuCpu, f.budget(JointRuntimeSearchPhase.PROVISIONAL)) { true } }.exceptionOrNull()
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                if (cancel) host.cancel("cancel during primary")
                proceed.countDown()
                val failure = running.get(3, TimeUnit.SECONDS)
                if (cancel) assertTrue(failure is CancellationException) else assertSame(marker, failure)
                assertTrue(drainEntered.await(2, TimeUnit.SECONDS))
                assertFalse(host.actualReleasedFuture.toCompletableFuture().isDone)
                assertEquals(2, f.ledger.snapshot().activeReservations)
                assertEquals(7_077_888L + 128_000L, f.ledger.snapshot().managedBytes)
                // Cancellation is observed after the successful output allocation; failure returns no output.
                assertEquals(if (cancel) 0L else 128_000L, f.ledger.snapshot().pendingManagedBytes)
                host.finish(false)
                drainProceed.countDown()
                host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
                host.finish(false); host.cancel("duplicate close")
                assertEquals(0, f.ledger.snapshot().activeReservations)
                assertEquals(0L, f.ledger.snapshot().pendingManagedBytes)
            } finally { proceed.countDown(); drainProceed.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun keptNativePairDropsScratchOnlyAfterSamplingDrainAndPreservesTheNativeFence() =
        JointRuntimeSampleTestFixture("kept-scratch").use { f ->
            val drainEntered = CountDownLatch(1); val drainProceed = CountDownLatch(1)
            f.onSamplingDrained = { drainEntered.countDown(); check(drainProceed.await(3, TimeUnit.SECONDS)) }
            val host = f.host()
            host.open(gpuCpu, f.budget(JointRuntimeSearchPhase.FINAL), { true })
            assertEquals(7_077_888L, f.ledger.snapshot().managedBytes)
            try {
                host.finish(true); host.finish(true)
                assertTrue(drainEntered.await(2, TimeUnit.SECONDS))
                assertEquals(7_077_888L, f.ledger.snapshot().managedBytes)
            } finally { drainProceed.countDown() }
            host.samplingDrainedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
            assertEquals(0, f.ledger.snapshot().activeReservations)
            assertEquals(0L, f.ledger.snapshot().managedBytes)
            assertFalse(host.actualReleasedFuture.toCompletableFuture().isDone)
            assertFalse(f.primaryPhysical.isDone); assertFalse(f.nativePhysical.isDone)
            assertNull(f.ledger.beginCandidate("native-pair-is-still-kept"))
            assertEquals(0, f.primaryCloses)
        }

    @Test fun postBeginBusyMarkersOrForeignExceptionReceiptsCannotAcknowledgeTheCapturedCohort() {
        for (kind in 0..2) JointRuntimeSampleTestFixture("post-begin-$kind").use { f ->
            f.holdNative = true
            val foreign = CompletableFuture<VisionReleaseReceipt>()
            val marker = runCatching { JointRuntimeSampleAcquisition.acquire { throw VisionStaleAuthorityException() } }.exceptionOrNull() as JointRuntimeSampleNoNativeStart
            val failure = when (kind) {
                0 -> IllegalStateException("wrapped busy after acquire", VisionCohortBusyException(foreign))
                1 -> marker
                else -> VisionRuntimeException("post-acquire lookup failed", releaseReceipt = foreign)
            }
            f.primaryLoadFailure = failure
            val host = f.host()
            assertSame(failure, runCatching { host.measureProvisional(gpuCpu, f.budget(JointRuntimeSearchPhase.PROVISIONAL)) { true } }.exceptionOrNull())
            host.samplingDrainedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
            foreign.complete(VisionReleaseReceipt("f".repeat(64), 123))
            assertFalse(host.actualReleasedFuture.toCompletableFuture().isDone)
            assertNull(f.ledger.beginCandidate("post-begin-cannot-overlap"))
            f.nativePhysical.complete(Unit)
            host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun missingAppliedRuntimeFieldsFailClosedInsteadOfBeingFilledFromTheCandidate() {
        for (field in listOf("gpuPrecisionLossRequested", "gpuPrecisionLossAllowed", "gpuSerializationCacheRequested", "gpuDelegateAttached"))
            JointRuntimeSampleTestFixture("missing-$field").use { f ->
                f.omitPrimaryRuntimeField = field
                val host = f.host()
                assertTrue(runCatching { host.measureProvisional(gpuCpu, f.budget(JointRuntimeSearchPhase.PROVISIONAL)) { true } }.isFailure)
                host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
                assertEquals(1, f.primaryCalls.get()); assertEquals(0, f.liveResults.get())
            }
    }
}
