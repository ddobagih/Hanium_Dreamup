package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.device.DeviceGateState
import kr.co.hanium.dreamup.walksafe.feedback.TactileDotReferenceDeliveryOwner
import kr.co.hanium.dreamup.walksafe.navigation.TactileDotReferenceNotice
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Executes the Main consumer with a controllable speech transport, without launching Activity. */
class MainActivityTactileDotReferenceDeliveryTest {
    private val owner = TactileDotReferenceDeliveryOwner(WalkRuntimeEpoch("dot-walk", 0L), 1, 2, 3L)
    private fun notice(frame: Long = 1L, captured: Long = 1_000L, until: Long = 1_300L) =
        TactileDotReferenceNotice("dot-1", frame, captured, until)
    private val readyDevice = DeviceGateState(
        cameraPermissionGranted = true, arCoreSupported = true, depthSupported = true,
        tfliteConfigLoaded = true, detectorAvailable = true, arSessionRunning = true,
    )

    @Test
    fun normalDotWithoutARiskAlertStillUsesReadyActuators() {
        val main = MainActivity()
        assertTrue(readyDevice.actuatorsAllowed)
        assertFalse(readyDevice.alertsAllowed)
        main.updateTactileDotReferenceObservation(notice(), owner, 1_000L)
        var calls = 0
        assertTrue(main.dispatchTactileDotReference(owner, { 1_000L }, {
            main.dotReferenceDeviceGateAllowsSpeech(readyDevice)
        }) { _, valid, _, _ -> calls++; valid() })
        assertEquals(1, calls)
        assertFalse(main.dotReferenceDeviceGateAllowsSpeech(readyDevice.copy(cameraPermissionGranted = false)))
        assertFalse(main.dotReferenceDeviceGateAllowsSpeech(readyDevice.copy(detectorAvailable = false)))
        assertFalse(main.dotReferenceDeviceGateAllowsSpeech(readyDevice.copy(arSessionRunning = false)))
    }

    @Test
    fun completedDotReferenceKeepsItsIntervalWhenNoRiskOutputRemains() {
        val main = MainActivity()
        var now = 1_000L
        var device = readyDevice.copy(freshDepthObject = true)
        val current = { main.dotReferenceDeviceGateAllowsSpeech(device) }
        main.updateTactileDotReferenceObservation(notice(), owner, now)
        var done: (() -> Unit)? = null
        assertTrue(main.dispatchTactileDotReference(owner, { now }, current) { _, valid, completed, _ ->
            assertTrue(valid()); done = completed; true
        })
        now = 2_000L
        device = readyDevice
        main.updateTactileDotReferenceObservation(null, owner, now)
        requireNotNull(done).invoke()
        now = 9_999L
        main.updateTactileDotReferenceObservation(notice(2L, now, now + 300L), owner, now)
        assertFalse(main.dispatchTactileDotReference(owner, { now }, { true }) { _, _, _, _ ->
            fail("A completed reference must keep its interval after risk output disappears"); true
        })
    }

    @Test
    fun actualCompletionAfterTheStartGateExpiresKeepsTheCurrentOwnersInterval() {
        val main = MainActivity()
        var now = 1_000L
        var startAllowed = true
        main.updateTactileDotReferenceObservation(notice(), owner, now)
        var done: (() -> Unit)? = null
        assertTrue(main.dispatchTactileDotReference(owner, { now }, { startAllowed }) { _, valid, completed, _ ->
            assertTrue(valid()); done = completed; true
        })
        // The same published owner still owns native DONE; a start-only observation has expired.
        now = 2_000L
        startAllowed = false
        main.updateTactileDotReferenceObservation(null, owner, now)
        requireNotNull(done).invoke()
        now = 9_999L
        startAllowed = true
        main.updateTactileDotReferenceObservation(notice(2L, now, now + 300L), owner, now)
        assertFalse(main.dispatchTactileDotReference(owner, { now }, { startAllowed }) { _, _, _, _ ->
            fail("Start-only freshness must not erase an actual completion of the current owner"); true
        })
    }

    @Test
    fun terminalReceiptRequiresTheCapturedLifecycleOwner() {
        val main = MainActivity()
        var ownerCurrent = true
        main.updateTactileDotReferenceObservation(notice(), owner, 1_000L)
        var done: (() -> Unit)? = null
        assertTrue(main.dispatchTactileDotReference(owner, { 1_000L }, { true }, { ownerCurrent }) { _, _, completed, _ ->
            done = completed; true
        })
        ownerCurrent = false
        requireNotNull(done).invoke()
        // A terminal callback from a retired lifecycle must not claim completion/cooldown.
        assertTrue(main.dispatchTactileDotReference(owner, { 1_000L }, { true }) { _, valid, _, _ -> valid() })
    }

    @Test
    fun missingObservationAndCurrentOutputRejectionNeverReachTheSpeechTransport() {
        val main = MainActivity()
        var calls = 0
        assertFalse(main.dispatchTactileDotReference(owner, { 1_000L }, { true }) { _, _, _, _ -> calls++; true })
        main.updateTactileDotReferenceObservation(notice(), owner, 1_000L)
        assertFalse(main.dispatchTactileDotReference(owner, { 1_000L }, { false }) { _, _, _, _ -> calls++; true })
        assertEquals(0, calls)
    }

    @Test
    fun actualStartKeepsTheIssuedDeadlineWhenANewerSameTrackFrameArrives() {
        val main = MainActivity()
        var now = 1_000L
        var allowed = true
        var start: (() -> Boolean)? = null
        var spoken: TactileDotReferenceNotice? = null
        main.updateTactileDotReferenceObservation(notice(), owner, now)
        assertTrue(main.dispatchTactileDotReference(owner, { now }, { allowed }) { issued, valid, _, _ ->
            spoken = issued; start = valid; true
        })
        now = 1_200L
        main.updateTactileDotReferenceObservation(notice(2L, 1_200L, 1_500L), owner, now)
        assertEquals(1L, requireNotNull(spoken).captureFrameId)
        assertEquals(1_300L, requireNotNull(spoken).validUntilElapsedRealtimeMs)
        now = 1_300L
        assertTrue(requireNotNull(start).invoke())
        now = 1_301L
        assertFalse(requireNotNull(start).invoke())
        now = 1_250L
        allowed = false
        assertFalse(requireNotNull(start).invoke())
    }

    @Test
    fun missingLatestObservationAndReplacementOwnerRejectTheHeldStart() {
        val main = MainActivity()
        var start: (() -> Boolean)? = null
        main.updateTactileDotReferenceObservation(notice(), owner, 1_000L)
        assertTrue(main.dispatchTactileDotReference(owner, { 1_000L }, { true }) { _, valid, _, _ -> start = valid; true })
        main.updateTactileDotReferenceObservation(null, owner, 1_001L)
        assertFalse(requireNotNull(start).invoke())
        val replacement = owner.copy(epoch = WalkRuntimeEpoch("new-dot-walk", 0L))
        main.updateTactileDotReferenceObservation(notice(2L, 1_002L, 1_302L), replacement, 1_002L)
        assertFalse(requireNotNull(start).invoke())
        assertFalse(main.dispatchTactileDotReference(owner, { 1_002L }, { true }) { _, _, _, _ -> fail("stale owner reached transport"); true })
    }

    @Test
    fun onePendingAndLateOldFailureCannotClearANewOwnersSpeech() {
        val main = MainActivity()
        var oldFailed: (() -> Unit)? = null
        main.updateTactileDotReferenceObservation(notice(), owner, 1_000L)
        assertTrue(main.dispatchTactileDotReference(owner, { 1_000L }, { true }) { _, _, _, failed -> oldFailed = failed; true })
        assertFalse(main.dispatchTactileDotReference(owner, { 1_000L }, { true }) { _, _, _, _ -> fail("duplicate pending"); true })
        val next = owner.copy(frameGeneration = 3)
        main.updateTactileDotReferenceObservation(notice(2L, 1_001L, 1_301L), next, 1_001L)
        var nextStart: (() -> Boolean)? = null
        assertTrue(main.dispatchTactileDotReference(next, { 1_001L }, { true }) { _, valid, _, _ -> nextStart = valid; true })
        requireNotNull(oldFailed).invoke()
        assertTrue(requireNotNull(nextStart).invoke())
        assertFalse(main.dispatchTactileDotReference(next, { 1_001L }, { true }) { _, _, _, _ -> fail("old failure cleared new pending"); true })
    }

    @Test
    fun rejectedSpeechHasNoCooldownAndCompletedSpeechUsesTheActualCompletionTime() {
        val main = MainActivity()
        var now = 1_000L
        main.updateTactileDotReferenceObservation(notice(), owner, now)
        assertFalse(main.dispatchTactileDotReference(owner, { now }, { true }) { _, _, _, _ -> false })
        var completed: (() -> Unit)? = null
        assertTrue(main.dispatchTactileDotReference(owner, { now }, { true }) { _, valid, done, _ ->
            assertTrue(valid()); completed = done; true
        })
        now = 2_000L
        main.updateTactileDotReferenceObservation(null, owner, now)
        requireNotNull(completed).invoke()
        now = 9_999L
        main.updateTactileDotReferenceObservation(notice(2L, now, now + 300L), owner, now)
        assertFalse(main.dispatchTactileDotReference(owner, { now }, { true }) { _, _, _, _ -> fail("completion cooldown lost"); true })
        now = 10_000L
        assertTrue(main.dispatchTactileDotReference(owner, { now }, { true }) { _, valid, _, _ -> valid() })
    }

    @Test
    fun productionConnectionUsesOnlyAdvisoryAndClearsTheEndingLifecycle() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val wrapper = MainActivityRefactorSource.function(source, "internal fun maybeSpeakTactileDotReference(")
        val dispatchWire = MainActivityRefactorSource.composition(source, "tactileDotDispatch")
        val reference = File("src/main/java/kr/co/hanium/dreamup/walksafe/feedback/delivery/TactileReferenceDeliveryController.kt").readText()
        assertTrue(wrapper.contains("val actor = actor.ensureActor()"))
        assertTrue(wrapper.contains("actor.speakAdvisoryWithReceipt("))
        assertTrue(wrapper.contains("reference().registerTactileDotReferenceSpeech(reservation, actor, receipt, ownerIsCurrent)"))
        assertTrue(wrapper.contains("onCancelled = failed"))
        assertTrue(wrapper.contains("validUntilMs = notice.validUntilElapsedRealtimeMs"))
        assertTrue(wrapper.contains("isStillValid = current"))
        assertTrue(wrapper.contains("completionOwnerIsCurrent = ownerIsCurrent"))
        assertTrue(MainActivityRefactorSource.member(source, "maybeSpeakTactileDotReference", occurrence = 0).contains("RuntimeArObservation(arLease.session, arLease.provider, arLease.purpose, arLease.generation)"))
        assertTrue(dispatchWire.contains("isRuntimeArLeaseCurrent(lease, observe)"))
        val native = source.substringAfter("internal fun isRuntimeArLeaseCurrent(").substringBefore("internal fun currentRuntimeMetricOutputAllowsWork(")
        assertTrue(native.contains("synchronized(runtimeMetricStateLock) { observeRuntimeAr().matchesLease(lease) }"))
        val facts = source.substringAfter("internal data class RuntimeArObservation(").substringBefore("private data class ArSessionLease(")
        assertTrue(facts.contains("session === lease.session && provider === lease.provider"))
        assertTrue(facts.contains("purpose == lease.purpose && generation == lease.generation"))
        assertTrue(wrapper.contains("authority.frameCurrent(owner.frameGeneration, owner.epoch)"))
        assertTrue(wrapper.contains("safety.epoch == owner.epoch && safety.safetyOutputsAllowed"))
        assertFalse(wrapper.contains(".emit("))
        val capture = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/capture/TactileFrameCaptureController.kt").readText()
        assertTrue(MainActivityRefactorSource.composition(source, "tactileCapture").contains("TactileFrameCaptureController("))
        assertTrue(capture.contains("dotReferenceNotice = if (conflicting) null else guidance.dotReferenceNotice"))
        assertTrue(capture.contains("val conflicting = AndroidRiskSelectionPolicy.hasConflictingTactileState(guidance.outputs)"))
        assertTrue(MainActivityRefactorSource.member(source, "resetVisualTrackingState").contains("resetTactileDotReferenceDelivery()"))
        val cleanup = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/runtime/WalkSessionCleanupController.kt").readText()
        assertTrue(MainActivityRefactorSource.composition(source, "walkSessionCleanupController").contains("WalkSessionCleanupController("))
        assertTrue(cleanup.substringAfter("stage(\"feedback_generation\") {").substringBefore("stage(\"guidance_preparation\")")
            .contains("feedback.advanceGenerationAndResetReferences()"))
        val retirement = ReportStaticSourceInspector.functionBlock(source, "override fun advanceGenerationAndResetReferences")
        assertTrue(retirement.contains("resetTactileDotReferenceDelivery()"))
        val reset = MainActivityRefactorSource.member(reference, "resetTactileDotReferenceDelivery")
        assertTrue(source.contains("tactileReferenceDelivery.resetTactileDotReferenceDelivery(owner)"))
        assertTrue(reset.contains("detachTactileDotReferenceSpeechLocked(tactileDotReferenceDeliveryPolicy.reset(owner))"))
        assertTrue(reset.indexOf("retired?.let(::cancelRetiredTactileDotReferenceSpeech)") > reset.indexOf("val retired = synchronized(frameStateLock)"))
        val cancel = MainActivityRefactorSource.member(reference, "cancelRetiredTactileDotReferenceSpeech")
        assertTrue(cancel.contains("if (Thread.holdsLock(frameStateLock) || !output.isMainThread())"))
        assertTrue(cancel.contains("output.postRetiredCancellation { speech.actor.cancelAdvisorySpeech(speech.receipt) }"))
        assertTrue(MainActivityRefactorSource.member(source, "createTactileReferenceDelivery").contains("detectorWatchdogHandler.post(action)"))
        assertTrue(cancel.contains("speech.actor.cancelAdvisorySpeech(speech.receipt)"))
    }
}
