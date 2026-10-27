package kr.co.hanium.dreamup.walksafe.report.runtime

import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.rawcollection.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorRuntime
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionStateSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After

class RawCollectionTransferControllerTest {
    @Before @After fun clearGateway() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }
    @Test fun sensitiveStorageFailureRemovesSessionFromRawContextWithoutInventingConsent() {
        val f = Fixture()
        try {
            f.publishSession()
            assertNotNull(f.controller.currentRawCollectionRuntimeContextOrNull()?.session)
            f.sensitiveBlocked = true
            assertNull(f.controller.currentRawCollectionRuntimeContextOrNull()?.session)
        } finally {
            GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
        }
    }

    @Test fun constructionDoesNotObserveRuntimeOrStartWork() {
        val f = Fixture(false)
        assertTrue(f.events.isEmpty())
        f.controller.executeRawCollectionTask { fail("uninitialized") }
        assertTrue(f.executor.tasks.isEmpty())
    }
    @Test fun observationClocksAndDetectorFactsAreReadBeforeWorkerContext() {
        val f = Fixture()
        f.record()
        assertEquals(listOf("wall", "elapsed", "detector", "identity"), f.events)
        f.run()
        assertTrue(f.events.indexOf("walk") > f.events.indexOf("identity"))
    }
    @Test fun staleWalkEpochBeforeWorkerCannotPeekMotionOrCaptureMetadata() {
        val f = Fixture()
        f.record()
        f.epoch = f.epoch.copy(recoveryGeneration = 1L)
        f.run()
        assertFalse(f.events.contains("motion"))
        assertFalse(f.events.contains("capture"))
    }
    @Test fun absentRawConfirmationDoesNotObserveMotion() {
        val f = Fixture()
        f.record(); f.run()
        assertFalse(f.events.contains("motion"))
    }
    @Test fun confirmedRawObservesMotionOnlyAfterActiveEpochAndConsentChecks() {
        val f = Fixture()
        assertTrue(f.consent.accept(confirmation()))
        f.record(); f.run()
        assertTrue(f.events.contains("motion"))
        assertTrue(f.events.indexOf("walk") < f.events.indexOf("motion"))
    }
    @Test fun completedInferenceAndDueDropCountersStayInTheThirtySecondWindow() {
        val f = Fixture()
        f.controller.recordDueInferenceDrop()
        f.controller.recordDueInferenceDrop()
        f.record(); f.run()
        assertFalse(f.events.contains("capture"))
        f.elapsed += 30_001L
        f.record(); f.run()
        assertEquals(2, f.performance!!.processedFrameCount)
        assertEquals(2, f.performance!!.droppedFrameCount)
        assertEquals(4, f.detection!!.detectionCount)
        assertEquals(9L, f.detection!!.averageInferenceMs)
        assertNotNull(f.detection!!.windowIdentity)
        assertSame(f.detection!!.windowIdentity, f.performance!!.windowIdentity)
    }
    @Test fun activeWalkNeverStartsRawUploadOrReadsCharging() {
        val f = Fixture()
        f.controller.markUploadWalk(f.epoch.walkSessionId)
        f.controller.scheduleRawCollectionPostWalkUpload(); f.run()
        assertFalse(f.events.contains("charging"))
        assertEquals(0, f.uploadStarts)
    }
    @Test fun pausedWalkNeverStartsRawUpload() {
        val f = Fixture()
        f.state = WalkSessionState.PAUSED
        f.controller.markUploadWalk(f.epoch.walkSessionId)
        f.controller.scheduleRawCollectionPostWalkUpload(); f.run()
        assertEquals(0, f.uploadStarts)
    }
    @Test fun postWalkForegroundWifiBindingChargingAndNoLeaseAllRemainRequired() {
        listOf<(Fixture)->Unit>(
            { it.foreground = false }, { it.transport = ActiveNetworkTransport.CELLULAR },
            { it.binding = null }, { it.charging = false }, { it.publishSession(); it.lease = gatewayLease(it.epoch) },
            { it.controller.markUploadWalk("another-walk") },
        ).forEach { change ->
            val f = Fixture(); f.state = WalkSessionState.ENDED
            f.controller.markUploadWalk(f.epoch.walkSessionId); change(f)
            f.controller.scheduleRawCollectionPostWalkUpload(); f.run()
            assertEquals(0, f.uploadStarts)
            assertFalse(f.events.contains("seal"))
        }
    }
    @Test fun exactReceiptOnlyOutcomeContinuesAtMostFourUploadsBeforeRescheduling() {
        val f = Fixture(); f.state = WalkSessionState.ENDED
        f.controller.markUploadWalk(f.epoch.walkSessionId)
        f.controller.scheduleRawCollectionPostWalkUpload(); f.run()
        assertEquals(4, f.uploadStarts)
        assertEquals(1, f.executor.tasks.size)
        assertTrue(f.events.indexOf("seal") < f.events.indexOf("upload"))
    }
    @Test fun walkInProgressSchedulesOneCommonRetryAndLogsItsBatchExit() {
        val f = Fixture(); f.state = WalkSessionState.ENDED
        f.outcome = RawCollectionUploadOutcome.WALK_IN_PROGRESS
        f.controller.markUploadWalk(f.epoch.walkSessionId)
        f.controller.scheduleRawCollectionPostWalkUpload(); f.run()
        assertEquals(1, f.uploadStarts)
        assertTrue(f.events.contains("retry"))
        assertTrue(f.events.contains("log"))
        assertTrue(f.executor.tasks.isEmpty())
    }
    @Test fun uploadContextLostInsideCallGetsFallbackWithSessionAndLeaseCleared() {
        val f = Fixture(); f.state = WalkSessionState.ENDED
        f.controller.markUploadWalk(f.epoch.walkSessionId)
        f.contextLossBeforeExecute = true
        f.controller.scheduleRawCollectionPostWalkUpload(); f.run()
        assertNull(f.uploadContext!!.session)
        assertNull(f.uploadContext!!.gatewayWalkLease)
        assertEquals(1, f.uploadStarts)
    }
    @Test fun unchangedOutcomeCountersDoNotProduceDuplicateFieldLogEvents() {
        val f = Fixture()
        f.controller.recordRawCollectionOutcomesIfChanged()
        f.controller.recordRawCollectionOutcomesIfChanged()
        assertEquals(1, f.events.count { it == "log" })
        f.counters = mapOf("saved" to 2L)
        f.controller.recordRawCollectionOutcomesIfChanged()
        assertEquals(2, f.events.count { it == "log" })
    }
    @Test fun shutdownAndRejectedExecutorCannotRunQueuedMetadata() {
        val f = Fixture()
        f.controller.shutdownExecutor()
        f.controller.executeRawCollectionTask { fail("shutdown") }
        assertTrue(f.executor.tasks.isEmpty())
        val g = Fixture(); g.executor.reject = true
        g.controller.executeRawCollectionTask { fail("rejected") }
        assertTrue(g.executor.tasks.isEmpty())
    }
    @Test fun metadataBacklogDropsDoNotBecomeOneCompleteThirtySecondWindow() {
        val f = Fixture()
        repeat(1_000) { f.record() }
        assertEquals(8, f.executor.tasks.size)
        repeat(8) { f.run() }
        f.elapsed += 30_001L; f.record(); f.run()
        assertNull(f.detection)
        f.elapsed += 30_001L; f.record(); f.run()
        assertEquals(2, f.detection!!.processedFrameCount)
        assertEquals(2, f.performance!!.processedFrameCount)
        assertSame(f.detection!!.windowIdentity, f.performance!!.windowIdentity)
    }

    private class Fixture(initialized: Boolean = true) : RawCollectionTransferController.Runtime,
        RawCollectionTransferController.Network, RawCollectionTransferController.Capture, RawCollectionTransferController.Log,
        RawCollectionTransferController.PostWalk, RawCollectionTransferController.Peer {
        val events = mutableListOf<String>()
        val executor = ManualExecutor()
        val consent = IntegratedConsentSession()
        var epoch = WalkRuntimeEpoch("11111111-1111-4111-8111-111111111111", 0L)
        var state = WalkSessionState.ACTIVE
        var elapsed = 1_000L
        var foreground = true
        var sensitiveBlocked = false
        override fun sensitiveStorageBlocked() = sensitiveBlocked
        var walkReady = true
        var transport = ActiveNetworkTransport.WIFI
        var binding: IntegratedConsentNetworkBinding? = IntegratedConsentNetworkBinding.forTest(IntegratedConsentNetworkTransport.WIFI) { it.openConnection() }
        var charging = true
        var lease: GatewayWalkLease? = null
        var outcome = RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT
        var uploadStarts = 0
        var detection: RawDetectionMetadataSample? = null
        var performance: RawPerformanceMetadataSample? = null
        var uploadContext: RawCollectionRuntimeContext? = null
        var contextLossBeforeExecute = false
        var counters = mapOf("saved" to 1L)
        val controller = RawCollectionTransferController(consent, executor, this, this, this, this, this,
            { events.add("wall"); 1_800_000_000_000L + elapsed }, { events.add("elapsed"); elapsed }).also {
                if (initialized) it.initializeCoordinator(this)
            }
        fun publishSession() {
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            val session = GatewayFieldSession.verified("https://gateway.example.test", "walker-1", "walksafe_field_session=raw-controller-test-cookie-00001", Long.MAX_VALUE)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, FirstRunOnboardingPolicy.initialEmailAccount(7L)))
        }
        fun record() = controller.recordRawCollectionDetectionMetadata(epoch, 2, 9L, "model-v1", null)
        fun run() = executor.tasks.removeFirst().run()
        override fun isWalkReady() = walkReady
        override fun walkSnapshot(): WalkSessionSnapshot {
            events.add("walk")
            val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val observations = WalkSessionReadinessRequirement.entries.associateWith {
                WalkSessionReadinessObservation(epoch,it,if (plan.requires(it)) WalkSessionReadinessStatus.READY else WalkSessionReadinessStatus.NOT_REQUIRED)
            }
            return WalkSessionSnapshot(epoch,state,if(state==WalkSessionState.ENDED) WalkSessionMode.UNAVAILABLE else WalkSessionMode.FULL,
                if(state==WalkSessionState.PAUSED) WalkSessionRecoveryStage.RECHECK_REQUIRED else null,true,
                if(state==WalkSessionState.ACTIVE) WalkSessionReadinessSnapshot(epoch,plan,1L,observations) else null)
        }
        override fun deviceCheckSnapshot() = PostLoginDeviceCheckSnapshot(PostLoginDeviceCheckState.FULL, "walker-1", 1L, 1L)
        override fun deletionProcessingBlocked() = false
        override fun activeGatewayWalkLeaseOrNull(epoch: WalkRuntimeEpoch) = lease
        override fun isCharging(): Boolean { events.add("charging"); return charging }
        override fun isForeground() = foreground
        override fun isReady() = true
        override fun currentTransport() = transport
        override fun currentBinding() = binding
        override fun detectorAvailable(): Boolean { events.add("detector"); return true }
        override fun runtimeIdentity(executedRuntime: AndroidDetectorRuntime?): RawDetectorRuntimeIdentity? {
            events.add("identity"); return RawDetectorRuntimeIdentity("a".repeat(64),"b".repeat(64),"c".repeat(64),"cpu",false)
        }
        override fun peekMotion(nowElapsedRealtimeMs: Long): UserMotionStateSnapshot? { events.add("motion"); return null }
        override fun thermalThrottledIfReady(): Boolean? = null
        override fun recordEvent(name: String, counters: Map<String, Long>) { events.add("log") }
        override fun scheduleTransfers() { events.add("schedule-transfers") }
        override fun scheduleWalkInProgressRetry() { events.add("retry") }
        override fun scheduleRetryAfter(delayMs: Long) { events.add("retry-after:$delayMs") }
        override fun revalidate(context: RawCollectionRuntimeContext) { events.add("revalidate") }
        override fun cancelActiveUpload() { events.add("cancel-upload") }
        override fun captureMetadataBatch(context: RawCollectionRuntimeContext, detection: RawDetectionMetadataSample, performance: RawPerformanceMetadataSample, observedAtElapsedRealtimeMs: Long) {
            events.add("capture"); this.detection=detection; this.performance=performance
        }
        override fun sealActiveSegment(context: RawCollectionRuntimeContext) { events.add("seal") }
        override fun startNextUpload(context: () -> RawCollectionRuntimeContext): CancellableNetworkCall<RawCollectionUploadOutcome>? {
            uploadStarts++; events.add("upload")
            return CancellableNetworkCall(executeBlock = {
                if (contextLossBeforeExecute) walkReady=false
                uploadContext=context()
                outcome
            })
        }
        override fun onWalkEnded(context: RawCollectionRuntimeContext) { events.add("ended") }
        override fun discardEndedPartials(context: RawCollectionRuntimeContext) { events.add("discard-partials") }
        override fun recordIdentitySplitWindows(count: Int) { events.add("identity-splits:$count") }
        override fun outcomeCountersSnapshot() = counters
        override fun nextUploadRetryDelayMs(context: RawCollectionRuntimeContext): Long? = null
    }
    private class ManualExecutor : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        var stopped = false
        var reject = false
        override fun execute(command: Runnable) { if (stopped || reject) throw RejectedExecutionException(); tasks.add(command) }
        override fun shutdown() { stopped=true }
        override fun shutdownNow(): MutableList<Runnable> { stopped=true; return tasks.toMutableList().also { tasks.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
    private companion object {
        fun confirmation() = IntegratedConsentConfirmation(INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,INTEGRATED_CONSENT_POLICY_VERSION,
            "installation_raw_controller", "raw_controller_request_0001",IntegratedConsentItemVersions(),1L,1L,
            IntegratedConsentSelections(rawSourceCollection=true),"2026-10-06T00:00:00.000Z","d".repeat(64),"a".repeat(64),"e".repeat(64))
        fun gatewayLease(epoch: WalkRuntimeEpoch) = GatewayWalkLease("ACQUIRED","walker-1","test-device",epoch.walkSessionId,
            "lease-id",1L,1_800_000_000_000L,1_800_000_090_000L,1_800_000_000_000L,100_000L)
    }
}
