package kr.co.hanium.dreamup.walksafe.navigation.capture

import kr.co.hanium.dreamup.walksafe.feedback.TactileDotReferenceDeliveryOwner
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.delivery.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Direct actual coordinator/policy/route composition; no Activity allocation or SDK resource. */
class TactileFrameCaptureControllerTest {
    @Test fun constructionDoesNotQueryRouteCoordinatorOrEffect() {
        val f = Fixture(); assertTrue(f.events.isEmpty()); assertEquals(0, f.emitted)
    }
    @Test fun originalSourceReceiptIsInstalledUnderLockAndConsumerRunsOutsideIt() {
        val f = Fixture(); val frame = f.emptyFrame(); var receipt: Receipt? = null
        assertTrue(f.controller.consumeTactileFrameResult(f.source, frame, admissionIsCurrent = {
            assertTrue(Thread.holdsLock(f.lock)); true
        }) { result, admitted ->
            assertFalse(Thread.holdsLock(f.lock)); assertSame(f.source, admitted.source)
            assertSame(frame.matchedEvidence, result.matchedEvidence); receipt = admitted
        })
        assertTrue(f.controller.tactileResultIsCurrent(requireNotNull(receipt)))
    }
    @Test fun staleAdmissionNeverQueriesRouteOrRetiresExistingReceipt() {
        val f = Fixture(); f.consume(); val old = requireNotNull(f.receipt); f.events.clear()
        assertFalse(f.controller.consumeTactileFrameResult(f.source.copy(frameId = 2), f.frame,
            admissionIsCurrent = { false }) { _, _ -> fail("stale admitted") })
        assertTrue(f.events.isEmpty())
        assertTrue(f.controller.tactileResultIsCurrent(old))
    }
    @Test fun receiptUsesExactIdentityAndCannotBeForgedByEqualSource() {
        val f = Fixture(); f.consume()
        val old = requireNotNull(f.receipt)
        assertFalse(f.controller.tactileResultIsCurrent(Receipt(old.source, old.maskUpgrade)))
        assertNull(f.controller.consumeCurrentTactileResult(Receipt(old.source, old.maskUpgrade)) { fail("forged") })
        assertEquals(7, f.controller.consumeCurrentTactileResult(old) { assertTrue(Thread.holdsLock(f.lock)); 7 })
    }
    @Test fun olderFrameOrCaptureClockNeverOverwritesNewerActualEmptyResult() {
        val f = Fixture(); f.consume(f.source.copy(frameId = 8, capturedAtElapsedRealtimeMs = 108))
        val old = requireNotNull(f.receipt)
        listOf(f.source.copy(frameId = 7, capturedAtElapsedRealtimeMs = 109),
            f.source.copy(frameId = 9, capturedAtElapsedRealtimeMs = 107)).forEach {
            assertFalse(f.controller.consumeTactileFrameResult(it, f.frame) { _, _ -> fail("rollback") })
            assertTrue(f.controller.tactileResultIsCurrent(old))
        }
    }
    @Test fun oneExactMaskUpgradeIsPermittedButNotRepeatedOrReplacedByBaseResult() {
        val f = Fixture(); f.consume(); val base = requireNotNull(f.receipt)
        f.consume(mask = true); val upgraded = requireNotNull(f.receipt)
        assertFalse(f.controller.tactileResultIsCurrent(base)); assertTrue(upgraded.maskUpgrade)
        assertFalse(f.controller.consumeTactileFrameResult(f.source, f.frame, maskUpgrade = true) { _, _ -> fail("repeat") })
        assertFalse(f.controller.consumeTactileFrameResult(f.source, f.frame) { _, _ -> fail("base replaced upgrade") })
        assertTrue(f.controller.tactileResultIsCurrent(upgraded))
    }
    @Test fun sameFrameUpgradeRequiresExactSourceValuesIncludingCpuClockAndGeometry() {
        val f = Fixture(); f.consume(); val old = requireNotNull(f.receipt)
        assertFalse(f.controller.consumeTactileFrameResult(f.source.copy(cpuImageTimestampNs = 999), f.frame,
            maskUpgrade = true) { _, _ -> fail("different capture") })
        assertTrue(f.controller.tactileResultIsCurrent(old))
        // A different geometry is an independent publication namespace, retaining original behavior.
        f.consume(f.source.copy(geometryVersion = 2))
        assertFalse(f.controller.tactileResultIsCurrent(old))
    }
    @Test fun changedEpochOwnerDoesNotApplyPreviousOwnersFrameOrdering() {
        val f = Fixture(); f.consume(f.source.copy(frameId = 100, capturedAtElapsedRealtimeMs = 500))
        val old = requireNotNull(f.receipt)
        f.consume(f.source.copy(owner = f.source.owner.copy(epoch = WalkRuntimeEpoch("replacement", 1))))
        assertFalse(f.controller.tactileResultIsCurrent(old)); assertTrue(f.controller.tactileResultIsCurrent(requireNotNull(f.receipt)))
    }
    @Test fun mismatchedRouteAndProgressCannotClaimReceipt() {
        val f = Fixture(); f.consume(); val old = requireNotNull(f.receipt)
        listOf(f.source.copy(routeId = "foreign"), f.source.copy(routeProgress = TactileActiveRouteProgress(9, 8))).forEach {
            assertFalse(f.controller.consumeTactileFrameResult(it, f.frame) { _, _ -> fail("wrong route") })
        }
        assertTrue(f.controller.tactileResultIsCurrent(old))
    }
    @Test fun actualCoordinatorDispatchEvaluatesUnderLockAndEmitsAfterLockRelease() {
        val f = Fixture(); f.consume(); val receipt = requireNotNull(f.receipt)
        val dispatched = f.controller.dispatchConsumedTactileFrameFeedback(receipt, f.frame, false, false, 100)
        assertNotNull(dispatched); assertEquals(1, f.emitted)
        assertEquals(100L, dispatched!!.policyEvaluatedAtMs)
        assertFalse(dispatched.deviceGateAllowsAlerts)
        assertNull(dispatched.action)
        f.controller.retireConsumedResult()
        assertNull(f.controller.dispatchConsumedTactileFrameFeedback(receipt, f.frame, false, true, 101))
        assertEquals(1, f.emitted)
    }
    @Test fun synchronousRetirementDuringEmitCannotRetroactivelyReplacePreparedDispatch() {
        val f = Fixture(); f.consume(); val receipt = requireNotNull(f.receipt)
        var exact: TactileFrameFeedbackDispatch? = null
        val result = f.controller.dispatchConsumedTactileFrameFeedback(receipt, f.frame, false, false, 100,
            TactileFrameFeedbackActuator { assertFalse(Thread.holdsLock(f.lock)); exact = it; f.controller.retireConsumedResult() })
        assertSame(result, exact); assertFalse(f.controller.tactileResultIsCurrent(receipt))
    }
    @Test fun preparedFrameProcessingDoesNotReplayDepthAtFeedbackDispatch() {
        val f = Fixture(); var depthCalls = 0
        val prepared = f.controller.prepareTactileFrameDispatch(DetectionSnapshot.empty(), 100, 100, 400, 400, null,
            processDepth = { _, _ -> depthCalls++; emptyList() })
        val frame = prepared.frame
        assertFalse(frame.depthProcessingAttempted)
        prepared.dispatchFeedback(false, false, 100)
        assertSame(frame, prepared.frame); assertEquals(0, depthCalls); assertEquals(1, f.emitted)
    }
    @Test fun originalConsumedReceiptFencesActualReferencePublicationAndNewerEmptyRetiresNotice() {
        val f = Fixture(); var clearedReady = 0
        val reference = TactileReferenceDeliveryController<Receipt>(f.lock,
            object : TactileReferenceSource<Receipt> {
                override fun currentFrame(generation: Int, epoch: WalkRuntimeEpoch) = generation == f.source.owner.frameGeneration && epoch == f.source.owner.epoch
                override fun currentReceipt(receipt: Receipt) = f.controller.tactileResultIsCurrent(receipt)
                override fun clearReadyRiskInput() { assertTrue(Thread.holdsLock(f.lock)); clearedReady++ }
                override fun updateLocalRouteDiagnostic(message: String) = Unit
            }, object : TactileReferenceOutput {
                override fun currentActor(): AndroidFeedbackActuator? = null
                override fun isMainThread() = true
                override fun postRetiredCancellation(action: () -> Unit) { fail("no actor receipt was registered") }
            }, { 100L })
        val dotFrame = f.frame.copy(guidance = f.frame.guidance.copy(dotReferenceNotice =
            TactileDotReferenceNotice("dot", f.source.frameId, 100, 500)))
        var dotReceipt: Receipt? = null
        assertTrue(f.controller.consumeTactileFrameResult(f.source, dotFrame) { frame, receipt ->
            dotReceipt = receipt; assertTrue(reference.publishConsumedTactileFrameReferenceObservation(frame, f.source.owner, receipt))
        })
        var notice: TactileDotReferenceNotice? = null
        assertTrue(reference.dispatchTactileDotReference(f.source.owner, { 100 }, { true },
            speak = { n, _, _, failed -> notice = n; failed(); true }))
        assertEquals("dot", notice!!.trackId)
        f.consume(f.source.copy(frameId = 2, capturedAtElapsedRealtimeMs = 101))
        assertTrue(reference.publishConsumedTactileFrameReferenceObservation(f.frame, f.source.owner, requireNotNull(f.receipt)))
        assertFalse(reference.publishConsumedTactileFrameReferenceObservation(dotFrame, f.source.owner, requireNotNull(dotReceipt)))
        assertFalse(reference.dispatchTactileDotReference(f.source.owner, { 101 }, { true },
            speak = { _, _, _, _ -> fail("old receipt restored notice"); true }))
        assertEquals(2, clearedReady)
    }

    private data class Source(
        override val owner: TactileDotReferenceDeliveryOwner, override val frameId: Long,
        override val capturedAtElapsedRealtimeMs: Long, override val cpuImageTimestampNs: Long?,
        override val geometryVersion: Long, override val routeId: String?, override val routeProgress: TactileActiveRouteProgress?,
    ) : TactileCaptureSourceFacts
    private class Receipt(override val source: Source, override val maskUpgrade: Boolean) : TactileCaptureReceiptFacts<Source>
    private class Fixture {
        val lock = Any(); val events = mutableListOf<String>(); var emitted = 0; var receipt: Receipt? = null
        val navigator = RouteNavigator(nowElapsedRealtimeMs = { error("route clock unexpectedly read") })
        val coordinator = AndroidTactileFrameCoordinator(AndroidTactileRouteGuidance(), WalkSafeFeedbackPolicy(),
            TactileFrameFeedbackActuator { assertFalse(Thread.holdsLock(lock)); emitted++ })
        val controller = TactileFrameCaptureController(lock, { events += "coordinator"; coordinator },
            { assertTrue(Thread.holdsLock(lock)); events += "route"; navigator },
            object : TactileCaptureRouteFacts {
                override fun navigationActiveNow(): Boolean { assertTrue(Thread.holdsLock(lock)); events += "navigation"; return false }
                override fun tmapOnRouteNow(): Boolean { assertTrue(Thread.holdsLock(lock)); events += "on-route"; return true }
            }, ::Receipt, { assertFalse(Thread.holdsLock(lock)); emitted++ })
        val source = Source(TactileDotReferenceDeliveryOwner(WalkRuntimeEpoch("capture", 0), 1, 2, 3),
            1, 100, 77, 1, navigator.currentRouteId(), navigator.currentTactileRouteProgress())
        val frame = TactileSnapshotFrameResult(null, emptyList(), false, TactileRouteGuidanceResult(emptyList(), null,
            TactileRouteDecision(LocalRouteMode.TMAP, null, "empty", null)))
        fun emptyFrame() = controller.processTactileSnapshotFrame(DetectionSnapshot.empty(), 100, 100, 400, 400, null,
            processDepth = { _, _ -> fail("empty processed depth"); emptyList() })
        fun consume(source: Source = this.source, mask: Boolean = false) {
            assertTrue(controller.consumeTactileFrameResult(source, frame, maskUpgrade = mask) { _, result -> receipt = result })
        }
    }
}
