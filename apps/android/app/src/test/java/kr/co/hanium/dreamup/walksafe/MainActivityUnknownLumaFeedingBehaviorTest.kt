package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownLumaFrameQueue
import java.io.File
import android.media.CameraTimestampTestImage
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointTracker
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointCaptureLease
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeOptions
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamResult
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeAttachment
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointLumaCapture
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointTestScene
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownObjectDepthPipeline
import kr.co.hanium.dreamup.walksafe.depth.unknown.sameCapture
import org.junit.Assert.*
import org.junit.Test

/** Main admission leaf/counters and separately labeled SOURCE checks, not Activity/GL/JNI. */
class MainActivityUnknownLumaFeedingBehaviorTest {
    @Test fun availableIntermediateFrameMustEnterTheQueueWhenFastSamIsNotDue() {
        var claims = 0
        var queued = 0
        val selected = MainActivity().selectUnknownAvailableFrameForQueue(false) { claims++; true }
        if (selected != null) queued++
        assertEquals("Every qualified intermediate CPU frame must reach the luma owner", 1, queued)
        assertEquals(false, selected)
        assertEquals("No submission reservation or RGB conversion when not due", 0, claims)
    }

    @Test fun dueFramePreservesTheSingleSubmissionClaim() {
        var claims = 0
        val selected = MainActivity().selectUnknownAvailableFrameForQueue(true) { claims++; true }
        assertEquals(true, selected)
        assertEquals(1, claims)
    }

    @Test fun sourceCopiesTheUnknownPacketBeforeTheSharedImageUseEnds() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/ArFrameRecognitionCapture.kt").readText()
        val copy = MainActivityRefactorSource.member(owner, "copyFrameRecognitionInput")
        assertTrue(MainActivityRefactorSource.member(source, "copyRuntimeRecognitionInput").contains("frameRecognition.copyFrameRecognitionInput("))
        MainActivityRefactorSource.composition(source, "frameRecognition")
        assertTrue("SOURCE: same open Image supplies the Unknown owned packet", copy.contains("UnknownMaterialPointLumaCapture.copyFromLuma("))
        assertTrue(copy.contains("observedCpuImageTimestampNs = cameraTimestampNs"))
        assertTrue(copy.contains("luma = plane.buffer"))
        assertTrue(copy.contains("imageQuarterTurns = quarterTurns"))
        assertTrue(copy.contains("unknownLuma ="))
    }

    @Test fun sourceAttachesAndConsumesTheExactReservedCaptureLease() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        MainActivityRefactorSource.composition(source, "unknownCaptureCoordinator")
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/unknown/UnknownCaptureCoordinator.kt").readText()
        val attachment = owner.substringAfter("private data class UnknownFrameAttachment(").substringBefore("private class UnknownFrameCopyClaim(")
        assertTrue("SOURCE: a real SAM attachment retains the exact lease", attachment.contains("captureLease: UnknownMaterialPointCaptureLease"))
        val submit = MainActivityRefactorSource.member(owner, "submitUnknownObjectFrame")
        assertTrue(submit.contains("reserveCapture(")); assertTrue(submit.contains("attempt.attachLease(reserved)"))
        val consume = MainActivityRefactorSource.member(owner, "consumeUnknownObjectResult")
        assertTrue(consume.contains("captureLease = attachment.captureLease"))
        MainActivityRefactorSource.composition(source, "unknownServiceOwner")
        val compositionSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/composition/VisionUnknownComposition.kt").readText()
        assertTrue(MainActivityRefactorSource.property(source, "unknownServiceOwnerLazy").contains("visionUnknownComposition.services"))
        assertTrue(MainActivityRefactorSource.property(source, "unknownCaptureCoordinatorLazy").contains("visionUnknownComposition.capture"))
        val service = MainActivityRefactorSource.property(compositionSource, "services")
        assertTrue(service.contains("UnknownRuntimeServiceOwner("))
        assertTrue(service.contains("onResult = { capture.value.consumeUnknownObjectResult(it) }"))
    }
    @Test fun busySubmissionStillQueuesItsIntermediatePacketWithoutAnotherClaim() {
        var claims = 0
        var queues = 0
        val selected = MainActivity().selectUnknownAvailableFrameForQueue(true) { claims++; false }
        if (selected != null) queues++
        assertEquals(1, queues)
        assertEquals(false, selected)
        assertEquals(1, claims)
    }

    @Test fun queueKeepsAtMost32SmallPacketsAndSchedulesExactlyOneDrain() {
        val queue = UnknownLumaFrameQueue<Int>(32)
        val tasks = mutableListOf<() -> Unit>()
        val delivered = mutableListOf<Int>()
        val discarded = mutableListOf<Int>()
        repeat(40) { n ->
            assertTrue(queue.enqueue(n, tasks::add, delivered::add, discarded::add))
            assertTrue(queue.pendingCount <= 32)
        }
        assertEquals(1, tasks.size)
        assertEquals((0..7).toList(), discarded)
        tasks.single().invoke()
        assertEquals((8..39).toList(), delivered)
        assertEquals(0, queue.pendingCount)
        assertTrue(queue.enqueue(40, tasks::add, delivered::add, discarded::add))
        assertEquals(2, tasks.size)
        tasks.last().invoke()
        assertEquals(40, delivered.last())
    }

    @Test fun ownedFramesReachTheProducerChronologicallyIncludingNonDetectionIntermediatePoints() {
        val main = MainActivity()
        val scene = UnknownMaterialPointTestScene()
        val queue = UnknownLumaFrameQueue<UnknownMaterialPointLumaCapture>(32)
        val tasks = mutableListOf<() -> Unit>()
        val offered = mutableListOf<Long>()
        val captures = mutableListOf<Long>()
        var now = 100_000L
        val pipeline = UnknownObjectDepthPipeline(elapsedRealtimeMs = { now })
        for (at in 0L..200L step 25L) {
            val packet = owned(scene, at)
            assertTrue(queue.enqueue(packet, tasks::add, { current ->
                now = current.token.capturedElapsedNs / 1_000_000L
                assertTrue(scene.producer.offerOwnedLuma(current, now).accepted)
                offered += current.token.frameId
                val atMs = current.token.frameId / 1_000_000L - 10_000L
                if (atMs % 200L == 0L) {
                    val lease = requireNotNull(scene.producer.reserveCapture(current.token, 0, now).lease)
                    val result = main.processUnknownMaterialPointCapture(pipeline, scene.producer,
                        scene.capture(atMs), current.token, listOf(scene.mask()), now,
                        scene.nanoTime, 5_000_000L, { scene.nanoTime }, captureLease = lease)
                    assertTrue(lease.isClosed)
                    assertNull(result.rejectionReason)
                    captures += atMs
                }
            }, { fail("No pressure in this nine-packet input") }))
        }
        assertEquals(1, tasks.size)
        tasks.single().invoke()
        assertEquals((0L..200L step 25L).map { scene.token(it).frameId }, offered)
        assertEquals(listOf(0L, 200L), captures)
        assertTrue(scene.flow.seeds > 0)
        assertTrue("The measured path includes non-detection frame100", scene.flow.targets.any {
            it.frameId == scene.token(100L).frameId
        })
    }

    @Test fun oldQueueCancellationDropsOnlyItsOwnPacketsAndCannotCancelANewerDrain() {
        val old = UnknownLumaFrameQueue<Int>(32)
        val fresh = UnknownLumaFrameQueue<Int>(32)
        val tasks = mutableListOf<() -> Unit>()
        val dropped = mutableListOf<Int>()
        val delivered = mutableListOf<Int>()
        old.enqueue(1, tasks::add, delivered::add, dropped::add)
        fresh.enqueue(2, tasks::add, delivered::add, dropped::add)
        old.invalidate(dropped::add)
        assertFalse(old.isAccepting)
        assertFalse(old.enqueue(3, tasks::add, delivered::add, dropped::add))
        tasks.forEach { it() }
        assertEquals(listOf(1), dropped)
        assertEquals(listOf(2), delivered)
        assertTrue(fresh.isAccepting)
    }

    @Test fun queuedCancellationDoesNotWaitForTheProducerMonitor() {
        val scene = UnknownMaterialPointTestScene()
        val packet = owned(scene, 0L)
        val queue = UnknownLumaFrameQueue<UnknownMaterialPointLumaCapture>(32)
        val tasks = mutableListOf<() -> Unit>()
        val entered = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        var publications = 0
        queue.enqueue(packet, tasks::add, {
            entered.countDown()
            scene.producer.offerOwnedLuma(it, 100_000L)
            if (queue.isAccepting) publications++
        }, { })
        val worker = Thread {
            try { tasks.single().invoke() } catch (failure: Throwable) { error.set(failure) }
            finally { completed.countDown() }
        }
        synchronized(scene.producer) {
            worker.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, worker.state)
            queue.invalidate { }
            assertFalse(queue.isAccepting)
            assertEquals("Cancellation completed while the worker still waits", 1L, completed.count)
        }
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        error.get()?.let { throw AssertionError(it) }
        assertEquals(0, publications)
    }

    @Test fun scheduleFailureReleasesQueuedOwnershipAndPreservesTheOriginalCause() {
        val queue = UnknownLumaFrameQueue<Int>(32)
        val failure = IllegalStateException("executor rejected the old owner")
        val discarded = mutableListOf<Int>()
        try {
            queue.enqueue(1, { throw failure }, { fail("No worker admitted") }, discarded::add)
            fail("Original scheduling failure must propagate")
        } catch (observed: IllegalStateException) { assertSame(failure, observed) }
        assertEquals(listOf(1), discarded)
        assertFalse(queue.isAccepting)
        assertEquals(0, queue.pendingCount)
    }

    @Test fun workerFailureDiscardsItsFrameAndAllRemainingOwnership() {
        val queue = UnknownLumaFrameQueue<Int>(32)
        val failure = IllegalStateException("worker failed before admission")
        val tasks = mutableListOf<() -> Unit>()
        val discarded = mutableListOf<Int>()
        queue.enqueue(1, tasks::add, { throw failure }, discarded::add)
        queue.enqueue(2, tasks::add, { throw failure }, discarded::add)
        try { tasks.single().invoke(); fail("Original worker failure must propagate") }
        catch (observed: IllegalStateException) { assertSame(failure, observed) }
        assertEquals(listOf(1, 2), discarded)
        assertFalse(queue.isAccepting)
        assertEquals(0, queue.pendingCount)
    }

    @Test fun rejectedAndThrowingSamSubmissionReleaseTheExactOriginalReservation() {
        for (admission in listOf(FastSamRuntimeService.Admission.CLOSED, FastSamRuntimeService.Admission.STALE,
            FastSamRuntimeService.Admission.COPY_BUSY, FastSamRuntimeService.Admission.NOT_READY)) {
            val scene = UnknownMaterialPointTestScene()
            scene.producer.offerOwnedLuma(owned(scene, 0L), 100_000L)
            val lease = requireNotNull(scene.producer.reserveCapture(scene.token(0L), 0, 100_000L).lease)
            var calls = 0
            assertEquals(admission, MainActivity().submitReservedUnknownFrame(lease) { calls++; admission })
            assertEquals(1, calls)
            assertTrue(lease.isClosed)
            assertTrue(sameCapture(scene.token(0L), lease.token))
        }
        val scene = UnknownMaterialPointTestScene()
        scene.producer.offerOwnedLuma(owned(scene, 0L), 100_000L)
        val lease = requireNotNull(scene.producer.reserveCapture(scene.token(0L), 0, 100_000L).lease)
        val failure = IllegalStateException("SAM submit callback failed")
        try { MainActivity().submitReservedUnknownFrame(lease) { throw failure }; fail("Must preserve cause") }
        catch (observed: IllegalStateException) { assertSame(failure, observed) }
        assertTrue(lease.isClosed)
        assertEquals(0, scene.flow.seeds)
    }

    @Test fun acceptedReservationRemainsOpenUntilActualConsumeAndClosedFlagDoesNotBlockOutput() {
        val scene = UnknownMaterialPointTestScene()
        scene.producer.offerOwnedLuma(owned(scene, 0L), 100_000L)
        val lease = requireNotNull(scene.producer.reserveCapture(scene.token(0L), 0, 100_000L).lease)
        val main = MainActivity()
        assertEquals(FastSamRuntimeService.Admission.ACCEPTED,
            main.submitReservedUnknownFrame(lease) { FastSamRuntimeService.Admission.ACCEPTED })
        assertFalse(lease.isClosed)
        val result = main.processUnknownMaterialPointCapture(UnknownObjectDepthPipeline(elapsedRealtimeMs = { 100_000L }),
            scene.producer, scene.capture(0L), scene.token(0L), listOf(scene.mask()), 100_000L,
            0L, 5_000_000L, { scene.nanoTime }, captureLease = lease)
        assertTrue(lease.isClosed)
        assertNull(result.rejectionReason)
        assertEquals(2f, result.objects.single().riskDistanceM!!, .001f)
    }

    @Test fun ownedPacketRetainsOnlyTheSmallOriginalImageCopyAndUnmodifiedClocks() {
        val scene = UnknownMaterialPointTestScene(width = 640, height = 480)
        val packet = owned(scene, 25L)
        assertTrue(packet.sampledWidth <= 192 && packet.sampledHeight <= 192)
        assertTrue(packet.retainedImageBytes <= 192 * 192)
        assertTrue(sameCapture(scene.token(25L), packet.token))
        assertEquals(scene.token(25L).cpuImageTimestampNs, packet.observedCpuImageTimestampNs)
        assertEquals(640, packet.imageWidth)
        assertEquals(480, packet.imageHeight)
    }

    @Test fun sourceBindsCopyQueueAndWorkerWithoutMovingNativeOrRgbWorkIntoGl() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/unknown/UnknownCaptureCoordinator.kt").readText()
        MainActivityRefactorSource.composition(source, "unknownCaptureCoordinator")
        fun block(marker: String) = ReportStaticSourceInspector.functionBlock(owner, marker)
        val schedule = block("fun scheduleUnknownDetectionIfDue")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(schedule,
            "getOrCreateUnknownProcessingContext(", "selectUnknownAvailableFrameForQueue(", "context.frameQueue.enqueue("))
        assertTrue(schedule.contains("schedule = { detectorExecutor.execute(it) }"))
        assertFalse(schedule.contains("offerOwnedLuma("))
        assertFalse(schedule.contains("toArgb("))
        val worker = block("private fun processQueuedUnknownLumaFrame")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(worker,
            "offerOwnedLuma(", "!admission.accepted", "frame.submission ?: return",
            "CameraDetectorAdmissionPolicy.admit(", "submitUnknownObjectFrame("))
        val submit = block("private fun submitUnknownObjectFrame")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(submit,
            "serviceState.inflight || serviceState.pending || serviceState.copying", "context.pacing.tryStart(",
            "FrozenUnknownDepthCapture.freeze(", "reserveCapture(", "input.cameraFrame.toArgb()", "service.submitArgb("))
        val invalidation = block("internal fun invalidateUnknownObjectObservations")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(invalidation,
            "context.frameQueue.invalidate", "context.activeAttempt?.retireCaptureLease()",
            "detectorExecutor.execute { context.materialPointTracker.reset() }"))
        assertTrue(invalidation.contains("invalidatedContext?.service?.discardPending("))
        assertFalse(invalidation.contains("unknownRuntimeService?.discardPending("))
        assertFalse(invalidation.contains("synchronized(context.materialPointTracker)"))
        assertTrue(MainActivityRefactorSource.composition(source, "unknownCaptureCoordinator").contains("mainDispatcher = onMain"))
        assertTrue(MainActivityRefactorSource.member(source, "createVisionUnknownComposition").contains("onMain = { runOnUiThread(it) }"))
        assertTrue(MainActivityRefactorSource.member(source, "invalidateUnknownObjectObservations").contains("unknownCaptureCoordinator.invalidateUnknownObjectObservations()"))
        val context = block("private fun getOrCreateUnknownProcessingContext")
        assertTrue(context.contains("unknownRuntimeService !== bound.service"))
        assertTrue(context.contains("it.imageQuarterTurns == input.imageQuarterTurns && it.frameQueue.isAccepting"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(context,
            "invalidateUnknownObjectObservations()", "UnknownProcessingContext("))
        val discard = block("fun handleUnknownFrameDiscard")
        assertTrue(discard.contains("!attempt.matches(token)"))
        val captureAttempt = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/unknown/UnknownCaptureAttempt.kt").readText()
        val matches = MainActivityRefactorSource.member(captureAttempt, "matches")
        assertTrue(matches.contains("sameCapture(token, candidate)"))
        assertTrue(matches.contains("ticket.startedAtElapsedRealtimeMs == candidate.capturedElapsedNs / 1_000_000L"))
    }

    @Test fun staticSmallCopyUsesItsOwnedImageBeforeOneCloseAndNeedsNoImageAtWorkerAdmission() {
        val image = CameraTimestampTestImage(9_980_000_000L, 1)
        var reads = 0
        val packet = image.use { sameImage ->
            assertEquals(0, image.closeCalls)
            reads++
            val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA, 9L, 10_000_000_000L,
                10_000_000_000L, sameImage.timestamp, 100_000_000_000L, "same-image-use", sameImage.width, sameImage.height)
            val plane = sameImage.planes.single()
            requireNotNull(UnknownMaterialPointLumaCapture.copyFromLuma(token, 0, sameImage.timestamp,
                sameImage.width, sameImage.height, plane.buffer, plane.rowStride, plane.pixelStride))
        }
        assertEquals(1, reads)
        assertEquals(1, image.closeCalls)
        val scene = UnknownMaterialPointTestScene(width = 2, height = 2)
        assertTrue(scene.producer.offerOwnedLuma(packet, 100_000L).accepted)
        assertEquals(1, image.closeCalls)
        assertEquals(0, scene.flow.seeds)
    }

    /** Executes the real private Main cancellation body with owned JVM context/service state. */
    @Test fun actualMainCancellationClosesTheOriginalLeaseAndItsDelayedResetCannotReleaseANewerContext() {
        val fixture = cancellationFixture()
        try {
            val old = fixture.context
            val oldProducer = contextField(old, "materialPointTracker") as UnknownMaterialPointTracker
            val scene = UnknownMaterialPointTestScene()
            assertTrue(oldProducer.offerOwnedLuma(owned(scene, 0L), 100_000L).accepted)
            val oldLease = requireNotNull(oldProducer.reserveCapture(scene.token(0L), 0, 100_000L).lease)
            attachCaptureLease(old, oldLease)
            invokeCancellation(fixture.main)
            assertTrue(oldLease.isClosed)
            assertNull(mainField(fixture.main, "unknownProcessingContext").get(fixture.main))
            assertEquals(1, fixture.executor.tasks.size)

            val fresh = newContext(fixture.service, 2L)
            val freshProducer = contextField(fresh, "materialPointTracker") as UnknownMaterialPointTracker
            assertTrue(freshProducer.offerOwnedLuma(owned(scene, 25L), 100_025L).accepted)
            val freshLease = requireNotNull(freshProducer.reserveCapture(scene.token(25L), 0, 100_025L).lease)
            attachCaptureLease(fresh, freshLease)
            mainField(fixture.main, "unknownProcessingContext").set(fixture.main, fresh)
            fixture.executor.tasks.single().run()
            assertSame(fresh, mainField(fixture.main, "unknownProcessingContext").get(fixture.main))
            assertFalse("An old reset must never close the reused service's new source owner", freshLease.isClosed)
            freshLease.close()
        } finally { fixture.service.closeAsync(); fixture.executor.shutdown() }
    }

    @Test fun actualMainResetSchedulingFailurePreservesCauseAfterImmediateOwnershipRelease() {
        val failure = IllegalStateException("reset owner rejected work")
        val fixture = cancellationFixture(failure)
        try {
            val producer = contextField(fixture.context, "materialPointTracker") as UnknownMaterialPointTracker
            val scene = UnknownMaterialPointTestScene()
            producer.offerOwnedLuma(owned(scene, 0L), 100_000L)
            val lease = requireNotNull(producer.reserveCapture(scene.token(0L), 0, 100_000L).lease)
            attachCaptureLease(fixture.context, lease)
            try { invokeCancellation(fixture.main); fail("The original rejection must propagate after tail") }
            catch (observed: IllegalStateException) { assertSame(failure, observed) }
            assertTrue(lease.isClosed)
            assertNull(mainField(fixture.main, "unknownProcessingContext").get(fixture.main))
            assertFalse((contextField(fixture.context, "frameQueue") as UnknownLumaFrameQueue<*>).isAccepting)
        } finally { fixture.service.closeAsync(); fixture.executor.shutdown() }
    }

    private val contextClocks = java.util.IdentityHashMap<FastSamRuntimeService<UnknownRuntimeAttachment>,
        kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.UnknownCaptureClock>()

    private data class CancellationFixture(val main: MainActivity, val context: Any,
        val service: FastSamRuntimeService<UnknownRuntimeAttachment>, val executor: RecordingExecutor)

    private fun cancellationFixture(failure: RuntimeException? = null): CancellationFixture {
        val main = MainActivity()
        val app = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        val service = FastSamRuntimeService(app, 9L,
            FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU, 1, true, 60_000_000_000L, 800L, false),
            object : FastSamRuntimeService.Listener<UnknownRuntimeAttachment> {
                override fun onResult(result: FastSamResult<UnknownRuntimeAttachment>) = Unit
            })
        contextClocks[service] = mainField(main, "unknownCaptureClock").get(main) as
            kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.UnknownCaptureClock
        val context = newContext(service, 1L)
        val executorField = mainField(main, "detectorExecutor")
        (executorField.get(main) as ExecutorService).shutdown()
        val executor = RecordingExecutor(failure)
        executorField.set(main, executor)
        mainField(main, "unknownProcessingContext").set(main, context)
        mainField(main, "unknownRuntimeService").set(main, service)
        return CancellationFixture(main, context, service, executor)
    }

    private fun newContext(service: FastSamRuntimeService<UnknownRuntimeAttachment>, id: Long): Any =
        kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.UnknownCaptureCoordinator::class.java.declaredClasses.single { it.simpleName == "UnknownProcessingContext" }
            .declaredConstructors.single().apply { isAccessible = true }
            .newInstance(id, service, 0, WalkRuntimeEpoch("owned-queue-cancel", 1L), 7L, "geometry-$id", 0, .7f,
                requireNotNull(contextClocks[service]) { "Missing original Main clock for this capture service" })

    private fun mainField(main: MainActivity, name: String) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }
    private fun contextField(context: Any, name: String): Any? =
        context.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(context)
    @Suppress("UNCHECKED_CAST")
    private fun attachCaptureLease(context: Any, lease: UnknownMaterialPointCaptureLease) {
        val pacing = contextField(context, "pacing") as kr.co.hanium.dreamup.walksafe.inference.AdaptiveInferencePacingPolicy
        val ticket = requireNotNull(pacing.tryStart(lease.token.capturedElapsedNs / 1_000_000L))
        val attempt = kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.UnknownCaptureAttempt(lease.token, ticket, pacing)
        assertTrue(attempt.attachLease(lease))
        context.javaClass.getDeclaredField("activeAttempt").apply { isAccessible = true }.set(context, attempt)
    }
    private fun invokeCancellation(main: MainActivity) {
        main.invalidateUnknownObjectObservations { action -> action() }
    }

    private class RecordingExecutor(private val failure: RuntimeException?) : AbstractExecutorService() {
        val tasks = mutableListOf<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { failure?.let { throw it }; tasks += command }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return tasks.toMutableList().also { tasks.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
    }

    private fun owned(scene: UnknownMaterialPointTestScene, at: Long) =
        requireNotNull(UnknownMaterialPointLumaCapture.copyFromLuma(scene.token(at), 0,
            scene.token(at).cpuImageTimestampNs, scene.width, scene.height,
            ByteBuffer.wrap(ByteArray(scene.width * scene.height) { 77 }), scene.width, 1))

}
