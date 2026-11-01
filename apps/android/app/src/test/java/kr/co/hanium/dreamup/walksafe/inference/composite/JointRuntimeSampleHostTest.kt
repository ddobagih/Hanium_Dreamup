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
