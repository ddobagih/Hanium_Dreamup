package kr.co.hanium.dreamup.walksafe.inference

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemotePrimaryTensorEngineTest {
    @Test fun remoteCloseFailureCanConfirmLocalDrainWhileItsNativeReceiptStillWaits() {
        val port = Port(); val factory = factory(port); val facade = facade(factory, DetectorThreadOwner())
        assertFalse(factory.hostReleaseConfirmed())
        port.retired = true
        assertTrue(runCatching { facade.close() }.exceptionOrNull() is DetectorCohortRetired)
        assertTrue(factory.hostReleaseConfirmed())
        assertEquals(Unit, facade.hostReleasedFuture().toCompletableFuture().join())
        assertFalse(facade.actualReleasedFuture().toCompletableFuture().isDone)
        port.death.complete(Unit)
        assertEquals(Unit, facade.actualReleasedFuture().toCompletableFuture().join())
    }

    @Test fun aSuppressedLocalBackingFailureIsNotExcusedByLateNativeRelease() {
        val port = Port()
        val arena = ParentDirectBufferArena(kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeMemoryLedger())
        val factory = RemotePrimaryTensorFactory(port, { 1_000L }, 20_000L, arena = arena)
        val facade = facade(factory, DetectorThreadOwner())
        val buffers = RemotePrimaryTensorFactory::class.java.getDeclaredField("hostBuffers").apply { isAccessible = true }
            .get(factory) as Map<*, *>
        assertTrue((buffers.values.first() as ParentDirectBufferArena.Lease).releaseAfterHostDrain())
        port.retired = true
        val error = runCatching { facade.close() }.exceptionOrNull()
        assertTrue(error is DetectorCohortRetired)
        assertTrue(requireNotNull(error).suppressed.isNotEmpty())
        port.death.complete(Unit)
        assertEquals(Unit, facade.actualReleasedFuture().toCompletableFuture().join())
        assertFalse(factory.hostReleaseConfirmed())
        assertTrue(facade.hostReleasedFuture().toCompletableFuture().isCompletedExceptionally)
        assertSame(error, runCatching { facade.close() }.exceptionOrNull())
        // A repeated close preserves the original task failure and cannot erase its local evidence.
        assertFalse(factory.hostReleaseConfirmed())
    }

    @Test fun backingMapRemovalDoesNotConfirmTheReturnWhileTheArenaAckIsBlocked() {
        val port = Port()
        val arena = ParentDirectBufferArena(kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeMemoryLedger())
        val factory = RemotePrimaryTensorFactory(port, { 1_000L }, 20_000L, arena = arena)
        val facade = facade(factory, DetectorThreadOwner())
        val field = RemotePrimaryTensorFactory::class.java.getDeclaredField("hostBuffers").apply { isAccessible = true }
        val lock = RemotePrimaryTensorFactory::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(factory)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val closing = synchronized(arena) {
                val task = pool.submit { facade.close() }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (synchronized(lock) { (field.get(factory) as Map<*, *>).isNotEmpty() }) {
                    check(System.nanoTime() < deadline); Thread.yield()
                }
                assertFalse(factory.hostReleaseConfirmed())
                assertFalse(facade.hostReleasedFuture().toCompletableFuture().isDone)
                assertFalse(task.isDone)
                task
            }
            closing.get(2, TimeUnit.SECONDS)
            assertTrue(factory.hostReleaseConfirmed())
            assertEquals(Unit, facade.hostReleasedFuture().toCompletableFuture().join())
        } finally { pool.shutdownNow(); runCatching { facade.close() } }
    }

    @Test fun productionTensorFacadeParsesTheRemoteOutputAndRetainsItsActualOwner() {
        val port = Port()
        val factory = factory(port)
        val model = TfliteSingleModelDetector(model(), DetectorLoadTimingRecorder(), "unified_walksafe", factory)
        val result = model.detect(input())
        assertEquals("person", result.detections.single().className)
        assertEquals("gpu", result.runtime.activeDelegate)
        assertEquals(234, result.runtime.modelOwnerPid)
        assertEquals(55L, result.runtime.modelOwnerThreadId)
        assertEquals(port.lease.cohortId, result.runtime.cohortId)
        assertEquals("model-1", result.runtime.modelInstanceId)
        assertEquals("configured", result.runtime.gpuSerializationCacheStatus)
        assertEquals(300 * 6, model.copyLastOutput()!!.size)
        model.detect(input())
        assertEquals(listOf(VisionPurpose.WARMUP, VisionPurpose.LIVE), port.purposes)
        model.close(); factory.close()
        assertEquals(1, port.closes)
        assertTrue(factory.actualReleasedFuture.toCompletableFuture().isDone)
        assertFalse(port.death.isDone) // Other roles may still use this cohort.
    }

    @Test fun failedOpenCannotStartPartialCpuFallbackAndItsStableReceiptWaitsForLateDeath() {
        val port = Port().apply { openFailure = true }
        val factory = factory(port)
        val receipt = factory.actualReleasedFuture
        val failure = runCatching {
            TfliteSingleModelDetector(model(fallback = true), DetectorLoadTimingRecorder(), "unified_walksafe", factory)
        }.exceptionOrNull()
        assertTrue(failure is DetectorCohortRetired)
        assertEquals(listOf("gpu"), port.options.map { it.delegate })
        factory.close()
        assertFalse(receipt.toCompletableFuture().isDone)
        port.death.complete(Unit)
        assertEquals(Unit, receipt.toCompletableFuture().getNow(null))
        assertSame(receipt, (failure as DetectorCohortRetired).actualReleasedFuture)
    }

    @Test fun aRetiredCohortInvalidatesBothLegacyModelsWithoutOpeningACpuReplacement() {
        val port = Port()
        val factory = factory(port)
        val first = factory.open(model(), "gpu", DetectorLoadTimingRecorder(), "custom_tactile")
        val second = factory.open(model(), "gpu", DetectorLoadTimingRecorder(), "coco_general")
        port.invokeFailure = true
        assertTrue(runCatching { first.invoke(input().inputBuffer, output(), 2, 2, VisionPurpose.LIVE) }
            .exceptionOrNull() is DetectorCohortRetired)
        assertTrue(runCatching { second.invoke(input().inputBuffer, output(), 2, 2, VisionPurpose.LIVE) }
            .exceptionOrNull() is DetectorCohortRetired)
        assertEquals(2, port.options.size)
        assertEquals(1, port.invokes)
        assertTrue(runCatching { factory.close() }.exceptionOrNull() is DetectorCohortRetired)
        assertFalse(factory.actualReleasedFuture.toCompletableFuture().isDone)
        port.death.complete(Unit)
        assertEquals(Unit, factory.actualReleasedFuture.toCompletableFuture().getNow(null))
    }

    @Test fun aSuccessfulCloseResultCannotReplaceTheIndependentModelReleaseReceipt() {
        val port = Port().apply { completeModelReceiptOnClose = false }
        val factory = factory(port)
        factory.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe")
        factory.close()
        assertFalse(factory.actualReleasedFuture.toCompletableFuture().isDone)
        assertEquals(1, port.closes)
        port.modelReceipts.values.single().complete(Unit)
        assertTrue(factory.actualReleasedFuture.toCompletableFuture().isDone)
        assertFalse(port.death.isDone)
    }

    @Test fun mismatchedOpenRuntimeOrLaterNativeOwnerNeverPublishesTheRequestedMetadataAsObserved() {
        val wrongOpen = Port().apply { actualDelegate = "cpu" }
        val openFactory = factory(wrongOpen)
        assertTrue(runCatching { openFactory.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe") }
            .exceptionOrNull() is DetectorCohortRetired)
        openFactory.close()
        assertFalse(openFactory.actualReleasedFuture.toCompletableFuture().isDone)
        wrongOpen.death.complete(Unit)
        assertTrue(openFactory.actualReleasedFuture.toCompletableFuture().isDone)

        val changed = Port()
        val factory = factory(changed)
        val engine = factory.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe")
        changed.ownerThread = 99L
        assertTrue(runCatching { engine.invoke(input().inputBuffer, output(), 2, 2, VisionPurpose.LIVE) }
            .exceptionOrNull() is DetectorCohortRetired)
        assertEquals(55L, engine.runtime.modelOwnerThreadId)
        runCatching { factory.close() }
        changed.death.complete(Unit)
        assertTrue(factory.actualReleasedFuture.toCompletableFuture().isDone)
    }

    @Test fun previousCohortFailureBeforeOpenStillRetainsItsReleaseReceipt() {
        val port = Port().apply { acquireFailure = true }
        val factory = factory(port)
        assertTrue(runCatching { factory.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe") }
            .exceptionOrNull() is DetectorCohortRetired)
        factory.close()
        assertEquals(0, port.options.size)
        assertFalse(factory.actualReleasedFuture.toCompletableFuture().isDone)
        port.death.complete(Unit)
        assertTrue(factory.actualReleasedFuture.toCompletableFuture().isDone)
    }

    @Test fun aBusyFactoryPreservesTheOriginalExceptionAndDoesNotOwnTheOtherCohort() {
        val port = Port()
        val busy = VisionCohortBusyException(port.death.thenApply { VisionReleaseReceipt(port.lease.cohortId, 234) })
        port.busy = busy
        val factory = factory(port)
        assertSame(busy, runCatching {
            TfliteSingleModelDetector(model(fallback = true), DetectorLoadTimingRecorder(), "unified_walksafe", factory)
        }.exceptionOrNull())
        assertTrue(port.options.isEmpty())
        factory.close()
        assertEquals(Unit, factory.actualReleasedFuture.toCompletableFuture().getNow(null))
        assertFalse("Availability is somebody else's resource ownership", port.death.isDone)
        assertFalse(port.retired)
        assertSame(busy, runCatching { TfliteAndroidFrameDetector.loadModel<Unit> { throw busy } }.exceptionOrNull())
    }

    @Test fun aConcreteFactoryDetachesTheInvokedModelWithoutClosingOrClaimingPhysicalRelease() {
        val port = Port(); val factory = factory(port)
        val engine = factory.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe")
        engine.invoke(input().inputBuffer, output(), 2, 2, VisionPurpose.LIVE)
        val token = factory.detachForTransfer()
        assertEquals("model-1", token.oldHandles.single().modelInstanceId)
        assertSame(port.lease, token.oldLease)
        assertFalse(token.isConsumed)
        assertEquals(1, port.invokes)
        assertTrue(runCatching { engine.invoke(input().inputBuffer, output(), 2, 2, VisionPurpose.LIVE) }.isFailure)
        engine.close(); factory.close()
        assertEquals(0, port.closes); assertFalse(port.retired)
        assertFalse(factory.actualReleasedFuture.toCompletableFuture().isDone)
        port.death.complete(Unit)
        assertEquals(Unit, factory.actualReleasedFuture.toCompletableFuture().getNow(null))
    }

    @Test fun detachRejectsAnUndrainedConcreteInvocationWithoutClosingTheNativeHandle() {
        val port = Port(); val factory = factory(port)
        val engine = factory.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe")
        port.invokeHook = {
            assertTrue(runCatching { factory.detachForTransfer() }.exceptionOrNull() is IllegalStateException)
            assertEquals(0, port.closes)
        }
        engine.invoke(input().inputBuffer, output(), 2, 2, VisionPurpose.LIVE)
        assertEquals(1, port.invokes)
        factory.detachForTransfer()
        assertEquals(0, port.closes)
    }

    @Test fun aConcreteTransferredAttachmentKeepsTheSameModelAndRequiresAdoptionWithoutAnotherOpen() {
        val port = Port(); val old = factory(port)
        val original = old.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe")
        original.invoke(input().inputBuffer, output(), 2, 2, VisionPurpose.LIVE)
        val token = old.detachForTransfer()
        val nextLease = port.lease.copy(ownerId = "camera", ownerEpoch = 2L)
        val nextHandles = token.oldHandles.map { it.copy(ownerEpoch = 2L) }
        port.currentLease = nextLease; port.adopted = false
        val attached = token.claimForAttach(listOf(PrimaryTensorEngines.spec(model(), "gpu", "unified_walksafe")), nextLease, nextHandles)
        val next = RemotePrimaryTensorFactory(port, { 1_000L }, 20_000L, attached)
        val engine = next.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe")
        assertEquals(1, port.acquires); assertEquals(1, port.options.size)
        assertEquals(original.runtime.modelInstanceId, engine.runtime.modelInstanceId)
        assertTrue(runCatching { engine.invoke(input().inputBuffer, output(), 2, 2, VisionPurpose.LIVE) }
            .exceptionOrNull() is DetectorAttachmentInactive)
        assertTrue(runCatching { next.activateTransferred() }.isFailure)
        assertEquals(1, port.invokes); assertEquals(0, port.closes)
        port.adopted = true; next.activateTransferred()
        engine.invoke(input().inputBuffer, output(), 2, 2, VisionPurpose.LIVE)
        assertEquals(2, port.invokes)
        assertTrue(runCatching { token.claimForAttach(attached.specs, nextLease, nextHandles) }.isFailure)
        original.close(); old.close()
        assertEquals(0, port.closes); assertFalse(old.actualReleasedFuture.toCompletableFuture().isDone)
        next.close()
        assertEquals(1, port.closes)
        assertEquals(Unit, old.actualReleasedFuture.toCompletableFuture().getNow(null))
        assertEquals(Unit, next.actualReleasedFuture.toCompletableFuture().getNow(null))
    }

    @Test fun aTransferCannotReplayAnOldEpochOrSubstituteAModelInstance() {
        val port = Port(); val old = factory(port)
        old.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe")
        val token = old.detachForTransfer()
        val specs = listOf(PrimaryTensorEngines.spec(model(), "gpu", "unified_walksafe"))
        assertTrue(runCatching { token.claimForAttach(specs, port.lease, token.oldHandles) }.isFailure)
        val lease = port.lease.copy(ownerId = "camera", ownerEpoch = 2L)
        assertTrue(runCatching { token.claimForAttach(specs, lease,
            token.oldHandles.map { it.copy(ownerEpoch = 2L, modelInstanceId = "replacement") }) }.isFailure)
        assertFalse(token.isConsumed); assertEquals(1, port.options.size); assertEquals(0, port.closes)
    }

    @Test fun productionFacadeTransferDrainsItsHostOwnerWithoutClosingTheRemoteModel() {
        val port = Port(); val factory = factory(port); val owner = DetectorThreadOwner()
        val facade = facade(factory, owner)
        try {
            assertTrue(facade.isRuntimeAdmissionCurrent())
            val result = facade.detectCalibrationImage(ArgbImage(2, 2, IntArray(4) { -1 }))
            assertEquals("model-1", result.timing.modelRuntime?.modelInstanceId)
            val token = facade.detachForTransfer()
            assertEquals("model-1", token.oldHandles.single().modelInstanceId)
            assertFalse(facade.isRuntimeAdmissionCurrent())
            assertTrue(runCatching { owner.call { error("retired host accepted work") } }.isFailure)
            facade.close(); assertEquals(0, port.closes)
            assertFalse(facade.actualReleasedFuture().toCompletableFuture().isDone)
            port.death.complete(Unit)
            assertEquals(Unit, facade.actualReleasedFuture().toCompletableFuture().getNow(null))
        } finally { facade.close() }
    }

    @Test fun metadataQueryDoesNotJoinABlockedConcreteInvokeAndStopsBeforeCloseCanDrain() {
        val entered = CountDownLatch(1); val allow = CountDownLatch(1)
        val port = Port().apply { invokeHook = { entered.countDown(); check(allow.await(5, TimeUnit.SECONDS)) } }
        val factory = factory(port); val facade = facade(factory, DetectorThreadOwner())
        val pool = Executors.newFixedThreadPool(3)
        try {
            val invocation = pool.submit<AndroidDetectionResult> { facade.detectCalibrationImage(ArgbImage(2, 2, IntArray(4))) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(pool.submit<Boolean> { facade.isRuntimeAdmissionCurrent() }.get(1, TimeUnit.SECONDS))
            val closing = pool.submit { facade.close() }
            val stopped = pool.submit<Boolean> {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (facade.isRuntimeAdmissionCurrent()) { check(System.nanoTime() < deadline); Thread.yield() }
                true
            }
            assertTrue(stopped.get(2, TimeUnit.SECONDS))
            assertFalse("Host close still waits for the accepted invocation", closing.isDone)
            assertFalse(facade.hostReleasedFuture().toCompletableFuture().isDone)
            assertFalse(facade.isRuntimeAdmissionCurrent())
            allow.countDown(); invocation.get(2, TimeUnit.SECONDS); closing.get(2, TimeUnit.SECONDS)
            assertEquals(Unit, facade.hostReleasedFuture().toCompletableFuture().join())
        } finally {
            allow.countDown()
            runCatching { facade.close() }
            pool.shutdownNow()
        }
    }

    @Test fun failedAttachmentOwnershipValidationCannotExitTheCurrentTargetCohort() {
        val port = Port(); val original = factory(port)
        original.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe")
        val token = original.detachForTransfer()
        val target = port.lease.copy(ownerId = "camera", ownerEpoch = 2L)
        val handles = token.oldHandles.map { it.copy(ownerEpoch = 2L) }
        val attachment = token.claimForAttach(listOf(PrimaryTensorEngines.spec(model(), "gpu", "unified_walksafe")), target, handles)
        port.currentLease = target; port.rejectAttach = true
        val failed = RemotePrimaryTensorFactory(port, { 1_000L }, 20_000L, attachment)
        assertTrue(runCatching { failed.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe") }.isFailure)
        failed.close()
        assertFalse(port.retired); assertEquals(0, port.closes)
        assertFalse(failed.actualReleasedFuture.toCompletableFuture().isDone)
        port.death.complete(Unit)
        assertEquals(Unit, failed.actualReleasedFuture.toCompletableFuture().getNow(null))
    }

    @Test fun externalAuthorityGuardsNeverRunWhileTheFactoryMetadataLockIsHeld() {
        val port = Port(); val factory = factory(port)
        val lock = RemotePrimaryTensorFactory::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(factory)
        var guards = 0
        port.validationHook = { assertFalse(Thread.holdsLock(lock)); guards++ }
        val engine = factory.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe")
        engine.invoke(input().inputBuffer, output(), 2, 2, VisionPurpose.LIVE)
        assertTrue(factory.canAdmit())
        factory.detachForTransfer()
        assertTrue(guards >= 3)
        assertEquals(0, port.closes)
    }

    @Test fun confirmedNonFiniteNativeOutputUsesTheTypedFaultHookAndKeepsReleasePending() {
        val port = Port().apply { nonFiniteOutput = true }; val factory = factory(port)
        val engine = factory.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe")
        assertTrue(runCatching { engine.invoke(input().inputBuffer, output(), 2, 2, VisionPurpose.LIVE) }
            .exceptionOrNull() is DetectorCohortRetired)
        assertEquals(listOf(VisionNativeFaultReason.NON_FINITE_OUTPUT), port.outputFaults)
        runCatching { factory.close() }
        assertFalse(factory.actualReleasedFuture.toCompletableFuture().isDone)
        port.death.complete(Unit)
        assertEquals(Unit, factory.actualReleasedFuture.toCompletableFuture().getNow(null))
    }

    @Test fun callerBufferAndInputValueFailuresNeverReportANativeOutputFault() {
        val port = Port().apply { nonFiniteOutput = true }; val factory = factory(port)
        val engine = factory.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe")
        assertTrue(runCatching { engine.invoke(ByteBuffer.allocateDirect(4), output(), 2, 2, VisionPurpose.LIVE) }.isFailure)
        assertTrue(runCatching { engine.invoke(input().inputBuffer, ByteBuffer.allocateDirect(4), 2, 2, VisionPurpose.LIVE) }.isFailure)
        assertEquals(0, port.invokes); assertTrue(port.outputFaults.isEmpty())
        val invalid = input().inputBuffer.apply { putFloat(0, Float.NaN) }
        assertTrue(runCatching { engine.invoke(invalid, output(), 2, 2, VisionPurpose.LIVE) }.isFailure)
        assertEquals(1, port.invokes); assertTrue(port.outputFaults.isEmpty())
        runCatching { factory.close() }; port.death.complete(Unit)
    }

    @Test fun explicitFixturePurposesReachTheConcreteEngineWithTheirOwnDeadlines() {
        val port = Port(); val facade = facade(factory(port), DetectorThreadOwner())
        try {
            val image = ArgbImage(2, 2, IntArray(4))
            facade.detectCalibrationImage(image, VisionPurpose.REFERENCE)
            facade.detectCalibrationImage(image, VisionPurpose.BENCHMARK)
            facade.inferPreparedUnifiedForCalibration(input(), 0L, YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR,
                purpose = VisionPurpose.REFERENCE)
            facade.detectCalibrationImage(image, VisionPurpose.WARMUP)
            assertEquals(listOf(VisionPurpose.REFERENCE, VisionPurpose.BENCHMARK, VisionPurpose.REFERENCE, VisionPurpose.WARMUP), port.purposes)
            assertEquals(listOf(11_000L, 3_000L, 11_000L, 16_000L), port.invocationDeadlines)
            assertEquals(1, facade.lifecycleTiming().phases.count { it.phase == AndroidDetectorLoadPhase.FIRST_INFERENCE })
        } finally { facade.close() }
    }

    @Test fun explicitFirstBenchmarkDoesNotBecomeWarmupAndLegacyCalibrationKeepsItsDefaults() {
        val explicit = Port(); val selected = facade(factory(explicit), DetectorThreadOwner())
        try {
            selected.detectCalibrationImage(ArgbImage(2, 2, IntArray(4)), VisionPurpose.BENCHMARK)
            assertEquals(listOf(VisionPurpose.BENCHMARK), explicit.purposes)
            assertEquals(listOf(3_000L), explicit.invocationDeadlines)
        } finally { selected.close() }
        val legacy = Port(); val ordinary = facade(factory(legacy), DetectorThreadOwner())
        try {
            ordinary.detectCalibrationImage(ArgbImage(2, 2, IntArray(4)))
            ordinary.inferPreparedUnifiedForCalibration(input(), 0L, YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR, true)
            assertEquals(listOf(VisionPurpose.WARMUP, VisionPurpose.LIVE), legacy.purposes)
            assertEquals(listOf(16_000L, 3_000L), legacy.invocationDeadlines)
        } finally { ordinary.close() }
    }

    @Test fun callerDeadlineTruncatesThePurposeBudgetAcrossBothCalibrationEntries() {
        val port = Port(); val model = facade(factory(port), DetectorThreadOwner())
        try {
            model.detectCalibrationImage(ArgbImage(2, 2, IntArray(4)), VisionPurpose.REFERENCE, 1_500L)
            model.inferPreparedUnifiedForCalibration(input(), 0L, YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR,
                purpose = VisionPurpose.BENCHMARK, absoluteDeadlineMs = 1_600L)
            assertEquals(listOf(1_500L, 1_600L), port.invocationDeadlines)
            assertEquals(listOf(VisionPurpose.REFERENCE, VisionPurpose.BENCHMARK), port.purposes)
        } finally { model.close() }
    }

    @Test fun expiredCallerDeadlineDoesNotDispatchAndLateOrBackwardCompletionCannotSucceed() {
        var now = 1_000L
        val port = Port(); val factory = RemotePrimaryTensorFactory(port, { now }, 20_000L)
        val engine = factory.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe")
        assertTrue(runCatching { engine.invoke(input().inputBuffer, output(), 2, 2, VisionPurpose.REFERENCE, now) }
            .exceptionOrNull() is java.util.concurrent.TimeoutException)
        assertEquals(0, port.invokes); assertFalse(port.retired)
        port.invokeHook = { now = 1_500L }
        assertTrue(runCatching { engine.invoke(input().inputBuffer, output(), 2, 2, VisionPurpose.REFERENCE, 1_500L) }
            .exceptionOrNull() is DetectorCohortRetired)
        assertEquals(1, port.invokes); runCatching { factory.close() }; port.death.complete(Unit)

        now = 2_000L
        val backward = Port().apply { invokeHook = { now = 1_999L } }
        val other = RemotePrimaryTensorFactory(backward, { now }, 20_000L)
        val second = other.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe")
        assertTrue(runCatching { second.invoke(input().inputBuffer, output(), 2, 2, VisionPurpose.BENCHMARK, 3_000L) }
            .exceptionOrNull() is DetectorCohortRetired)
        assertTrue(backward.outputFaults.isEmpty())
        runCatching { other.close() }; backward.death.complete(Unit)
    }

    @Test fun processArenaPreprocessorAndOutputsReuseBackingOnlyAfterTheHostInvocationDrains() {
        val arena = ParentDirectBufferArena(kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeMemoryLedger())
        val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
        val port = Port().apply { invokeHook = { entered.countDown(); check(proceed.await(5, TimeUnit.SECONDS)) } }
        val first = RemotePrimaryTensorFactory(port, { 1_000L }, 20_000L, arena = arena)
        val preprocessor = YuvImagePreprocessor(first::inputBuffer)
        val prepared = preprocessor.preprocessBilinear(ArgbImage(2, 2, IntArray(4)), 2)
        assertEquals(48, prepared.inputBuffer.capacity()); assertEquals(ByteOrder.nativeOrder(), prepared.inputBuffer.order())
        val backing = first.outputBuffer("unified_walksafe", 7_200)
        val engine = first.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe")
        val pool = Executors.newSingleThreadExecutor()
        try {
            val invoking = pool.submit { engine.invoke(prepared.inputBuffer, backing, 2, 2, VisionPurpose.LIVE) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            first.close()
            val second = RemotePrimaryTensorFactory(Port(), { 1_000L }, 20_000L, arena = arena)
            assertTrue(runCatching { second.inputBuffer(2) }.isFailure)
            assertTrue(runCatching { second.outputBuffer("unified_walksafe", 7_200) }.isFailure)
            proceed.countDown(); invoking.get(2, TimeUnit.SECONDS)
            val charged = arena.memoryLedger.snapshot().directBytes
            val reused = second.inputBuffer(2); val output = second.outputBuffer("unified_walksafe", 7_200)
            reused.putFloat(0, 0.5f); assertEquals(0.5f, prepared.inputBuffer.getFloat(0))
            output.putFloat(0, 0.25f); assertEquals(0.25f, backing.getFloat(0))
            assertEquals(charged, arena.memoryLedger.snapshot().directBytes)
            second.close()
        } finally { proceed.countDown(); pool.shutdownNow(); runCatching { first.close() } }
    }

    @Test fun injectedPreprocessorRejectsWrongSlicesAndByteOrderAndPreservesTheDefaultEntry() {
        val image = ArgbImage(2, 2, IntArray(4))
        assertEquals(48, YuvImagePreprocessor().preprocessBilinear(image, 2).inputBuffer.capacity())
        assertTrue(runCatching { YuvImagePreprocessor { ByteBuffer.allocateDirect(44).order(ByteOrder.nativeOrder()) }
            .preprocessBilinear(image, 2) }.isFailure)
        val wrongOrder = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        assertTrue(runCatching { YuvImagePreprocessor { ByteBuffer.allocateDirect(48).order(wrongOrder) }
            .preprocessBilinear(image, 2) }.isFailure)
    }

    @Test fun aForeignActiveArenaCannotMaskAcquireBusyOrBecomeTheNewFactoriesOwnReceipt() {
        val arena = ParentDirectBufferArena(kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeMemoryLedger())
        val foreground = RemotePrimaryTensorFactory(Port(), { 1_000L }, 20_000L, arena = arena)
        foreground.inputBuffer(2); foreground.outputBuffer("unified_walksafe", 7_200)
        val availability = CompletableFuture<VisionReleaseReceipt>()
        val busy = VisionCohortBusyException(availability)
        val port = Port().apply { this.busy = busy }
        val next = RemotePrimaryTensorFactory(port, { 1_000L }, 20_000L, arena = arena)
        val charged = arena.memoryLedger.snapshot().directBytes
        assertSame(busy, runCatching { next.inputBuffer(2) }.exceptionOrNull())
        assertSame(busy, runCatching { next.outputBuffer("unified_walksafe", 7_200) }.exceptionOrNull())
        assertEquals(charged, arena.memoryLedger.snapshot().directBytes)
        next.close()
        assertEquals(Unit, next.actualReleasedFuture.toCompletableFuture().getNow(null))
        assertFalse(availability.isDone); assertFalse(port.retired)
        foreground.close()
    }

    private fun facade(factory: PrimaryTensorEngineFactory, owner: DetectorThreadOwner): TfliteAndroidFrameDetector {
        val recorder = DetectorLoadTimingRecorder()
        val single = TfliteSingleModelDetector(model(), recorder, "unified_walksafe", factory)
        val type = TfliteSingleModelDetector::class.java
        val constructor = TfliteAndroidFrameDetector::class.java.getDeclaredConstructor(DetectorThreadOwner::class.java,
            DetectorLoadTimingRecorder::class.java, type, type, type, YuvImagePreprocessor::class.java,
            YuvPreprocessingStrategy::class.java, type).apply { isAccessible = true }
        val facade = constructor.newInstance(owner, recorder, null, null, single, YuvImagePreprocessor(),
            YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR, null) as TfliteAndroidFrameDetector
        TfliteAndroidFrameDetector::class.java.getDeclaredField("tensorFactory").apply { isAccessible = true }.set(facade, factory)
        return facade
    }

    private fun factory(port: Port) = RemotePrimaryTensorFactory(port, { 1_000L }, 20_000L)
    private fun model(fallback: Boolean = false) = ModelRuntimeConfig("unified_walksafe", "models/test.tflite", 2,
        listOf("person"), setOf("person"), mapOf("person" to 0.5f), artifactSha256 = "a".repeat(64),
        runtime = ModelRuntimeOptions("gpu", 2, fallback))
    private fun input() = PreprocessedImage(ByteBuffer.allocateDirect(2 * 2 * 3 * 4).order(ByteOrder.nativeOrder()),
        LetterboxTransform(2, 2, 2, 1f, 0f, 0f))
    private fun output() = ByteBuffer.allocateDirect(300 * 6 * 4).order(ByteOrder.nativeOrder())

    private class Port : PrimaryTensorRemotePort {
        val lease = VisionCohortLease("a".repeat(64), "foreground", 1L)
        var currentLease = lease; var adopted = true; var acquires = 0; var rejectAttach = false
        val death = CompletableFuture<Unit>()
        val options = mutableListOf<VisionRuntimeOptions>()
        val modelReceipts = linkedMapOf<String, CompletableFuture<Unit>>()
        val requested = linkedMapOf<String, VisionRuntimeOptions>()
        val purposes = mutableListOf<VisionPurpose>()
        val invocationDeadlines = mutableListOf<Long>()
        val outputFaults = mutableListOf<VisionNativeFaultReason>()
        var acquireFailure = false; var openFailure = false; var invokeFailure = false
        var busy: VisionCohortBusyException? = null
        var actualDelegate: String? = null; var ownerThread = 55L
        var completeModelReceiptOnClose = true
        var nonFiniteOutput = false
        var invokeHook: (() -> Unit)? = null
        var validationHook: (() -> Unit)? = null
        var retired = false; var closes = 0; var invokes = 0
        override fun acquire(deadlineMs: Long): VisionCohortLease {
            acquires++
            busy?.let { throw it }
            if (acquireFailure) throw VisionRuntimeException("previous cohort", releaseReceipt = death.thenApply { VisionReleaseReceipt(lease.cohortId, 234) })
            return lease
        }
        override fun released(lease: VisionCohortLease) = death
        override fun open(lease: VisionCohortLease, spec: VisionModelSpec, deadlineMs: Long): VisionModelHandle {
            val runtime = VisionRuntimeCodec.decodeOptions(spec.runtimeJson)
            assertFalse(runtime.fallbackToCpu)
            options += runtime
            if (openFailure) throw VisionRuntimeException("OPEN failed")
            val id = "model-${options.size}"
            requested[id] = runtime
            modelReceipts[id] = CompletableFuture()
            return VisionModelHandle(lease.cohortId, id, lease.ownerEpoch, spec.role, spec.modelKey, runtimeJson(runtime))
        }
        override fun modelReleased(handle: VisionModelHandle) = modelReceipts.getValue(handle.modelInstanceId)
        override fun invoke(handle: VisionModelHandle, input: ByteBuffer, output: ByteBuffer, width: Int, height: Int,
            purpose: VisionPurpose, deadlineMs: Long): VisionInvokeResult {
            invokes++; purposes += purpose; invocationDeadlines += deadlineMs
            invokeHook?.invoke()
            if (invokeFailure) throw VisionRuntimeException("invoke failed")
            output.asFloatBuffer().put(floatArrayOf(if (nonFiniteOutput) Float.NaN else 0f, 0f, 1f, 1f, 0.9f, 0f))
            return VisionInvokeResult(runtimeJson(requested.getValue(handle.modelInstanceId)), 0.0, 2.0, 0.0)
        }
        override fun close(handle: VisionModelHandle, deadlineMs: Long): VisionCloseResult {
            closes++
            if (retired) throw VisionRuntimeException("cohort retired")
            if (completeModelReceiptOnClose) modelReceipts.getValue(handle.modelInstanceId).complete(Unit)
            return VisionCloseResult(handle.modelInstanceId, true)
        }
        override fun exit(lease: VisionCohortLease, reason: String) { retired = true }
        override fun validateAttach(lease: VisionCohortLease, handle: VisionModelHandle) {
            validationHook?.invoke()
            if (rejectAttach) throw VisionStaleAuthorityException()
            check(lease === currentLease && handle.cohortId == lease.cohortId && handle.ownerEpoch == lease.ownerEpoch)
            check(handle.modelInstanceId in modelReceipts)
        }
        override fun validateAdopted(lease: VisionCohortLease, handle: VisionModelHandle) {
            validateAttach(lease, handle); check(adopted)
        }
        override fun reportNativeOutputFault(lease: VisionCohortLease, handle: VisionModelHandle, reason: VisionNativeFaultReason) {
            validateAttach(lease, handle); outputFaults += reason; retired = true
        }
        private fun runtimeJson(value: VisionRuntimeOptions) = JSONObject().put("requestedDelegate", value.delegate)
            .put("activeDelegate", actualDelegate ?: value.delegate).put("numThreads", value.numThreads)
            .put("fallbackUsed", false).put("gpuPrecisionLossAllowed", if (value.delegate == "gpu") false else JSONObject.NULL)
            .put("gpuSerializationCacheStatus", if (value.delegate == "gpu") "configured" else JSONObject.NULL)
            .put("gpuSerializationCacheToken", if (value.delegate == "gpu") "observed-token" else JSONObject.NULL)
            .put("modelOwnerPid", 234).put("modelOwnerThreadId", ownerThread).put("loadMs", 3.0).toString()
    }
}
