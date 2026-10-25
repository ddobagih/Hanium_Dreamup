package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.device.DeviceGateState
import kr.co.hanium.dreamup.walksafe.feedback.CrosswalkReferenceDeliveryOwner
import kr.co.hanium.dreamup.walksafe.navigation.CROSSWALK_REFERENCE_NOTICE_KO
import kr.co.hanium.dreamup.walksafe.navigation.CrosswalkReferenceNotice
import kr.co.hanium.dreamup.walksafe.navigation.TactileActiveRouteProgress
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Runs the actual Main consumer/policy with held transport callbacks, without launching Activity. */
class MainActivityCrosswalkReferenceDeliveryTest {
    private val owner = CrosswalkReferenceDeliveryOwner(WalkRuntimeEpoch("crosswalk-walk", 0L), 1, 2, 3L)
    private val progress = TactileActiveRouteProgress(1L, 0)
    private val routeCurrent: (CrosswalkReferenceNotice) -> Boolean = { it.isValidForRoute("route", progress) }
    private val readyDevice = DeviceGateState(
        cameraPermissionGranted = true, arCoreSupported = true, depthSupported = true,
        tfliteConfigLoaded = true, detectorAvailable = true, arSessionRunning = true,
    )

    private fun notice(frame: Long = 1L, captured: Long = 1_000L, until: Long = 1_300L) =
        CrosswalkReferenceNotice("crosswalk-1", frame, captured, until, "route", 1L, 0)

    @Test
    fun readyActuatorsWithoutMetricObjectsOrRiskAlertsStillDeliverTheReference() {
        val main = MainActivity()
        assertTrue(readyDevice.actuatorsAllowed)
        assertFalse(readyDevice.freshDepthObject)
        assertFalse(readyDevice.freshQualitativeObject)
        assertFalse(readyDevice.alertsAllowed)
        main.updateCrosswalkReferenceObservation(notice(), owner, 1_000L)
        var calls = 0
        assertTrue(main.dispatchCrosswalkReference(
            owner, { 1_000L }, { main.crosswalkReferenceDeviceGateAllowsSpeech(readyDevice) }, routeCurrent,
        ) { issued, valid, _, _ ->
            calls += 1
            assertEquals(CROSSWALK_REFERENCE_NOTICE_KO, issued.message)
            assertTrue(issued.isValidForRoute("route", progress))
            valid()
        })
        assertEquals(1, calls)
        assertFalse(main.crosswalkReferenceDeviceGateAllowsSpeech(readyDevice.copy(cameraPermissionGranted = false)))
        assertFalse(main.crosswalkReferenceDeviceGateAllowsSpeech(readyDevice.copy(detectorAvailable = false)))
        assertFalse(main.crosswalkReferenceDeviceGateAllowsSpeech(readyDevice.copy(arSessionRunning = false)))
    }

    @Test
    fun missingObservationOutputRejectionAndUnmatchedRouteNeverReachTransport() {
        val main = MainActivity()
        var calls = 0
        val speak: (CrosswalkReferenceNotice, () -> Boolean, () -> Unit, () -> Unit) -> Boolean =
            { _, _, _, _ -> calls += 1; true }
        assertFalse(main.dispatchCrosswalkReference(owner, { 1_000L }, { true }, routeCurrent, speak = speak))
        main.updateCrosswalkReferenceObservation(notice(), owner, 1_000L)
        assertFalse(main.dispatchCrosswalkReference(owner, { 1_000L }, { false }, routeCurrent, speak = speak))
        assertFalse(main.dispatchCrosswalkReference(owner, { 1_000L }, { true }, {
            it.isValidForRoute("other-route", progress)
        }, speak = speak))
        assertEquals(0, calls)
        assertTrue(main.dispatchCrosswalkReference(owner, { 1_000L }, { true }, routeCurrent) { _, valid, _, _ -> valid() })
    }

    @Test
    fun changedRouteIdVersionSegmentOrMissingProgressRejectTheHeldActualStart() {
        for (change in listOf("route_id", "route_version", "segment", "missing_progress")) {
            val main = MainActivity()
            var activeId: String? = "route"
            var currentProgress: TactileActiveRouteProgress? = progress
            var start: (() -> Boolean)? = null
            main.updateCrosswalkReferenceObservation(notice(), owner, 1_000L)
            assertTrue(main.dispatchCrosswalkReference(owner, { 1_000L }, { true }, {
                it.isValidForRoute(activeId, currentProgress)
            }) { _, valid, _, _ -> start = valid; true })
            assertTrue(requireNotNull(start).invoke())
            when (change) {
                "route_id" -> activeId = "replacement-route"
                "route_version" -> currentProgress = TactileActiveRouteProgress(2L, 0)
                "segment" -> currentProgress = TactileActiveRouteProgress(1L, 1)
                "missing_progress" -> currentProgress = null
            }
            assertFalse(change, requireNotNull(start).invoke())
        }
    }

    @Test
    fun aNewSameTrackFrameCannotExtendTheIssuedInclusiveStartDeadline() {
        val main = MainActivity()
        var now = 1_000L
        var outputAllowed = true
        var start: (() -> Boolean)? = null
        var spoken: CrosswalkReferenceNotice? = null
        main.updateCrosswalkReferenceObservation(notice(), owner, now)
        assertTrue(main.dispatchCrosswalkReference(owner, { now }, { outputAllowed }, routeCurrent) { issued, valid, _, _ ->
            spoken = issued; start = valid; true
        })
        now = 1_200L
        main.updateCrosswalkReferenceObservation(notice(2L, now, 1_500L), owner, now)
        assertEquals(1L, requireNotNull(spoken).captureFrameId)
        assertEquals(1_300L, requireNotNull(spoken).validUntilElapsedRealtimeMs)
        now = 1_300L
        assertTrue(requireNotNull(start).invoke())
        outputAllowed = false
        assertFalse(requireNotNull(start).invoke())
        outputAllowed = true
        now = 1_301L
        assertFalse(requireNotNull(start).invoke())
    }

    @Test
    fun latestNullRejectsTheHeldStartAndCannotRestoreItsLostCapture() {
        val main = MainActivity()
        var now = 1_000L
        var start: (() -> Boolean)? = null
        var failed: (() -> Unit)? = null
        main.updateCrosswalkReferenceObservation(notice(), owner, now)
        assertTrue(main.dispatchCrosswalkReference(owner, { now }, { true }, routeCurrent) { _, valid, _, failure ->
            start = valid; failed = failure; true
        })
        now = 1_100L
        main.updateCrosswalkReferenceObservation(null, owner, now)
        assertFalse(requireNotNull(start).invoke())
        requireNotNull(failed).invoke()
        main.updateCrosswalkReferenceObservation(notice(), owner, now)
        assertFalse(main.dispatchCrosswalkReference(owner, { now }, { true }, routeCurrent) { _, _, _, _ ->
            fail("A lost capture must not be restored at the UI consumer"); true
        })
        main.updateCrosswalkReferenceObservation(notice(2L, now, now + 300L), owner, now)
        assertTrue(main.dispatchCrosswalkReference(owner, { now }, { true }, routeCurrent) { _, valid, _, _ -> valid() })
    }

    @Test
    fun eachReplacementOwnerRejectsTheOldStartAndOldOwnerDispatch() {
        for (replacement in listOf(
            owner.copy(epoch = WalkRuntimeEpoch("new-crosswalk-walk", 0L)),
            owner.copy(epoch = WalkRuntimeEpoch("crosswalk-walk", 1L)),
            owner.copy(feedbackLifecycleGeneration = 2),
            owner.copy(frameGeneration = 3),
            owner.copy(arGeneration = 4L),
        )) {
            val main = MainActivity()
            var now = 1_000L
            var start: (() -> Boolean)? = null
            main.updateCrosswalkReferenceObservation(notice(), owner, now)
            assertTrue(main.dispatchCrosswalkReference(owner, { now }, { true }, routeCurrent) { _, valid, _, _ ->
                start = valid; true
            })
            now = 1_002L
            main.updateCrosswalkReferenceObservation(notice(2L, now, 1_302L), replacement, now)
            assertFalse(replacement.toString(), requireNotNull(start).invoke())
            assertFalse(main.dispatchCrosswalkReference(owner, { now }, { true }, routeCurrent) { _, _, _, _ ->
                fail("Old owner reached transport"); true
            })
            assertTrue(main.dispatchCrosswalkReference(replacement, { now }, { true }, routeCurrent) { _, valid, _, _ -> valid() })
        }
    }

    @Test
    fun onePendingAndOldTerminalCallbacksCannotClearAReplacementWithTheSameOwnerAndNotice() {
        val main = MainActivity()
        var oldFailed: (() -> Unit)? = null
        var oldDone: (() -> Unit)? = null
        main.updateCrosswalkReferenceObservation(notice(), owner, 1_000L)
        assertTrue(main.dispatchCrosswalkReference(owner, { 1_000L }, { true }, routeCurrent) { _, _, done, failed ->
            oldDone = done; oldFailed = failed; true
        })
        assertFalse(main.dispatchCrosswalkReference(owner, { 1_000L }, { true }, routeCurrent) { _, _, _, _ ->
            fail("Duplicate pending reached transport"); true
        })
        requireNotNull(oldFailed).invoke()
        var nextStart: (() -> Boolean)? = null
        assertTrue(main.dispatchCrosswalkReference(owner, { 1_000L }, { true }, routeCurrent) { _, valid, _, _ ->
            nextStart = valid; true
        })
        requireNotNull(oldFailed).invoke()
        requireNotNull(oldDone).invoke()
        assertTrue(requireNotNull(nextStart).invoke())
        assertFalse(main.dispatchCrosswalkReference(owner, { 1_000L }, { true }, routeCurrent) { _, _, _, _ ->
            fail("Old terminal receipt cleared the newer pending token"); true
        })
    }

    @Test
    fun actualDoneAfterSourceAndStartGatesExpireKeepsTheSameOwnersEightSecondInterval() {
        val main = MainActivity()
        var now = 1_000L
        var outputAllowed = true
        var activeId: String? = "route"
        var done: (() -> Unit)? = null
        main.updateCrosswalkReferenceObservation(notice(), owner, now)
        assertTrue(main.dispatchCrosswalkReference(owner, { now }, { outputAllowed }, {
            it.isValidForRoute(activeId, progress)
        }) { _, valid, completed, _ -> assertTrue(valid()); done = completed; true })
        now = 2_000L
        outputAllowed = false
        activeId = null
        main.updateCrosswalkReferenceObservation(null, owner, now)
        requireNotNull(done).invoke()
        now = 9_999L
        outputAllowed = true
        activeId = "route"
        main.updateCrosswalkReferenceObservation(notice(2L, now, now + 300L), owner, now)
        assertFalse(main.dispatchCrosswalkReference(owner, { now }, { outputAllowed }, {
            it.isValidForRoute(activeId, progress)
        }) { _, _, _, _ -> fail("Actual DONE interval was lost with start-only freshness"); true })
        now = 10_000L
        assertTrue(main.dispatchCrosswalkReference(owner, { now }, { outputAllowed }, {
            it.isValidForRoute(activeId, progress)
        }) { _, valid, _, _ -> valid() })
    }

    @Test
    fun revokedTerminalOwnerReleasesTheReceiptWithoutRecordingCompletion() {
        val main = MainActivity()
        var ownerCurrent = true
        var done: (() -> Unit)? = null
        main.updateCrosswalkReferenceObservation(notice(), owner, 1_000L)
        assertTrue(main.dispatchCrosswalkReference(
            owner, { 1_000L }, { true }, routeCurrent, completionOwnerIsCurrent = { ownerCurrent },
        ) { _, _, completed, _ -> done = completed; true })
        ownerCurrent = false
        requireNotNull(done).invoke()
        assertTrue(main.dispatchCrosswalkReference(owner, { 1_000L }, { true }, routeCurrent) { _, valid, _, _ -> valid() })
    }

    @Test
    fun synchronousRejectionAndActualFailureAllowRetryWithoutAnInterval() {
        val main = MainActivity()
        main.updateCrosswalkReferenceObservation(notice(), owner, 1_000L)
        assertFalse(main.dispatchCrosswalkReference(owner, { 1_000L }, { true }, routeCurrent) { _, _, _, _ -> false })
        var failed: (() -> Unit)? = null
        assertTrue(main.dispatchCrosswalkReference(owner, { 1_000L }, { true }, routeCurrent) { _, valid, _, failure ->
            assertTrue(valid()); failed = failure; true
        })
        requireNotNull(failed).invoke()
        assertTrue(main.dispatchCrosswalkReference(owner, { 1_000L }, { true }, routeCurrent) { _, valid, _, _ -> valid() })
    }

    @Test
    fun SOURCE_ONLY_glPublishesCurrentOrNullNoticeBeforeMetricExitAndUiUsesAdvisoryLease() {
        val source = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt")
        val draw = MainActivityRefactorSource.member(source, "onDrawFrame")
        assertTrue(draw.contains("framePipelineController.draw()"))
        val composition = MainActivityRefactorSource.property(source, "framePipelineController")
        assertTrue(composition.contains("val tactile = frameTactileResults"))
        assertTrue(composition.contains("val publication = FramePublicationController("))
        assertTrue(composition.contains("FramePipelineController("))
        assertTrue(composition.contains("tactile, risk, recording, { framePresentation }, publication,"))
        val pipeline = readOwner("runtime/vision/frame/FramePipelineController.kt")
        val transaction = MainActivityRefactorSource.member(pipeline, "draw")
        assertTrue(transaction.indexOf("synthesize(qualified)") >= 0)
        assertTrue(transaction.indexOf("publication.publish(outputs)") > transaction.indexOf("synthesize(qualified)"))
        val synthesis = MainActivityRefactorSource.member(pipeline, "synthesize")
        val publication = synthesis.indexOf("tactile.publishReference(accepted, sourceLease.owner, token)")
        val dispatch = synthesis.indexOf("tactile.crosswalkReference(sourceLease.owner, lease, sourceCurrent)")
        assertTrue("SOURCE_ONLY: current notice/null must be published before advisory dispatch",
            publication >= 0 && dispatch > publication)
        assertTrue(synthesis.indexOf("return FramePublicationPacket(") > dispatch)
        val outputTransaction = MainActivityRefactorSource.member(
            readOwner("runtime/vision/frame/FramePublicationController.kt"), "publish")
        assertTrue(outputTransaction.contains("if (!metricOutputAllowed(scope.arGeneration))"))
        assertTrue(outputTransaction.contains("publishQualitative(input, frameTimestampNs)"))
        val tactilePort = MainActivityRefactorSource.property(source, "frameTactileResults")
            .replace(Regex("\\s+"), "")
        assertTrue(tactilePort.contains("overridefunpublishReference(frame:TactileSnapshotFrameResult,owner:TactileDotReferenceDeliveryOwner,receipt:TactileResultReceipt)=" +
            "publishConsumedTactileFrameReferenceObservation(frame,owner,receipt)"))
        assertTrue(tactilePort.contains("overridefuncrosswalkReference(owner:TactileDotReferenceDeliveryOwner,lease:ArSessionLease,current:()->Boolean)=" +
            "maybeSpeakCrosswalkReference(CrosswalkReferenceDeliveryOwner(owner.epoch,owner.feedbackLifecycleGeneration,owner.frameGeneration,owner.arGeneration),lease,current)"))
        val publishEntry = MainActivityRefactorSource.member(source, "publishConsumedTactileFrameReferenceObservation")
        assertTrue(publishEntry.contains("tactileReferenceDelivery.publishConsumedTactileFrameReferenceObservation(tactileFrame, owner, sourceReceipt, sourceIsCurrent, nowElapsedRealtimeMs)"))
        assertTrue(MainActivityRefactorSource.property(source, "tactileReferenceDelivery")
            .contains("by lazy { createTactileReferenceDelivery() }"))
        val referenceFactory = MainActivityRefactorSource.factory(source, "createTactileReferenceDelivery")
        assertTrue(referenceFactory.contains("= TactileReferenceDeliveryController("))
        assertTrue(referenceFactory.contains("frameStateLock = frameStateLock"))
        assertTrue(referenceFactory.contains("override fun currentFrame(generation: Int, epoch: WalkRuntimeEpoch) = isCurrentFrameGeneration(generation, epoch)"))
        assertTrue(referenceFactory.contains("override fun currentReceipt(receipt: TactileResultReceipt) = tactileResultIsCurrent(receipt)"))
        val publish = MainActivityRefactorSource.member(
            readOwner("feedback/delivery/TactileReferenceDeliveryController.kt"), "publishConsumedTactileFrameReferenceObservation")
        assertTrue(publish.contains("updateCrosswalkReferenceObservation("))
        assertTrue(publish.contains("tactileFrame.guidance.crosswalkReferenceNotice"))
        val capture = MainActivityRefactorSource.function(source, "internal fun consumeTactileFrameResult(")
        assertTrue(capture.contains("crosswalkReferenceNotice = if (conflicting) null else guidance.crosswalkReferenceNotice"))
        assertTrue(capture.contains("safeFrame to receipt"))
        assertTrue(capture.contains("consume(admitted.first, admitted.second)"))
        val ui = ReportStaticSourceInspector.functionBlock(source, "private fun maybeSpeakCrosswalkReference")
        for (token in listOf(
            "runOnUiThread", "dispatchCrosswalkReference(", "currentArCoreQualitativeFeedbackAllowsWork(nowMs)",
            "crosswalkReferenceDeviceGateAllowsSpeech(", "qualitativeRuntimeReady = true",
            "isRouteActive && !routeAcquisitionController.requestPending && latestTmapOnRoute",
            "notice.isValidForRoute(routeNavigator.currentRouteId(), routeNavigator.currentTactileRouteProgress())",
            "completionOwnerIsCurrent = ownerIsCurrent", "ensureFeedbackActuator().speakAdvisory(",
            "validUntilMs = notice.validUntilElapsedRealtimeMs", "isStillValid = current",
            "onCompleted = completed", "onFailed = failed",
        )) assertTrue("SOURCE_ONLY: missing production connection $token", ui.contains(token))
        assertTrue(MainActivityRefactorSource.property(
            readOwner("navigation/session/RouteAcquisitionController.kt"), "requestPending")
            .contains("routeRequestInFlight.get()"))
        assertFalse(ui.contains("currentRuntimeMetricOutputAllowsWork("))
        assertFalse(ui.contains(".alertsAllowed"))
        assertFalse(ui.contains(".emit("))
        assertFalse(ui.contains(".speakNavigation("))
    }
    private fun readOwner(path: String): String =
        ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/$path")

}
