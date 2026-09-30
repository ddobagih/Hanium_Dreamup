package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * w20 Android review fixes wired in MainActivity (behavior of the pure helpers is covered by
 * WalkReadinessStopGuidanceTest, HandsFreeVoiceCommandCompletionContractTest and
 * DestinationCandidateSpeechTest).
 */
class MainActivityW20ReviewFixStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing function: $signature" }
        var depth = 0
        var opened = false
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> {
                    depth += 1
                    opened = true
                }
                '}' -> {
                    depth -= 1
                    if (opened && depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("unterminated function: $signature")
    }

    /** S03-6 P2: the stop sentence names the lost feature; raw codes go to diagnostics only. */
    @Test fun aReadinessSafetyStopSpeaksAMappedCauseNotTheRawCodes() {
        val readiness = functionBlock("private fun applyRuntimeReadinessIfActive(")
        val stop = readiness.substringAfter("transition.current.state == WalkSessionState.SAFE_STOP")
        assertTrue(stop.contains("val reason = walkReadinessStopReasonKo(readiness)"))
        assertTrue(stop.contains("\"walk=runtime_readiness_safe_stop reasons=\" +"))
        assertTrue(stop.contains("readiness.blockingReasons.joinToString(\",\")"))
        // The codes reach updateNavigationStatus only, never the status card or the speech.
        val spoken = stop.substringAfter("val reason = walkReadinessStopReasonKo(readiness)")
        assertFalse(spoken.contains("blockingReasons"))
        assertTrue(spoken.contains("updateStatus(\"필수 기능 변경 · 안전 중지\", reason)"))
        assertTrue(spoken.contains("\"보행 기능을 안전 중지했습니다. \$reason\""))
        assertFalse(readiness.contains("readiness.blockingReasons.joinToString(\", \")"))
    }

    /** S03-6 P2: a runtime model-load failure is re-checkable and says so, instead of a process-long block. */
    @Test fun aRuntimeDetectorLoadFailureCanBeRecheckedAndIsExplained() {
        val retryable = source.substringAfter("private fun deferredCameraDependentChecksCanBeRetried(): Boolean =")
            .substringBefore("\n    private fun ")
        assertTrue(retryable.contains("runtimeObstacleDetectionCapabilityOverride == false"))
        assertTrue(retryable.contains("hasCameraPermission()"))
        val primary = functionBlock("private fun handlePostLoginDeviceCheckPrimaryAction()")
        val reset = primary.indexOf("if (!detectorAvailable && !detectorClosing) detectorLoadAttempted = false")
        assertTrue(reset >= 0)
        assertTrue(reset > primary.indexOf("if (!deferredCameraDependentChecksCanBeRetried()) return"))
        assertTrue(reset < primary.indexOf("startPostLoginDeviceCheckFromPrimaryAction()"))
        val load = functionBlock("private fun loadDetectorForCurrentProcess()")
        assertTrue(load.contains("if (detectorLoadAttempted) return"))
        val block = functionBlock("private fun walkSessionReadinessBlockReason(")
        val camera = block.substringAfter("if (!cameraAnalysisFeaturesEnabled()) {")
        assertTrue(
            camera.indexOf("if (detectorLoadAttempted && !detectorAvailable) {") in
                0 until camera.indexOf("카메라 장애물 안내를 사용할 수 없어 보행 안내를 시작하지 않습니다."),
        )
        assertTrue(camera.contains("장애물 탐지 모델을 불러오지 못해 보행 안내를 시작하지 않습니다."))
        assertTrue(camera.contains("'카메라·거리 기능 다시 점검'을 누르거나 앱을 완전히 종료한 뒤 다시 열어 주세요."))
    }

    /** S03-6/S03-21 P3: every transition into SAFE_STOP ends the server walk unless an end already began. */
    @Test fun aReadinessDrivenSafetyStopAlsoEndsTheServerWalk() {
        val transition = functionBlock("private fun transitionWalkSession(")
        val stop = transition.substringAfter("transition.current.state == WalkSessionState.SAFE_STOP\n        ) {")
            .substringBefore("gatewayWalkResumeRevalidationPending = false")
        assertTrue(stop.contains("gatewayWalkRenewalHandler.removeCallbacks(gatewayWalkRenewalRunnable)"))
        assertTrue(stop.contains("val stopEnd = if (endOperation == null) {"))
        assertTrue(stop.contains("gatewayWalkAuthorityController.beginEnd(transition.previous.epoch)"))
        assertTrue(stop.contains("endGatewayWalkBestEffort(stopSession, stopEnd)"))
        // Without a lease to end (already ended by the safety-stop helper) the local drop stays.
        assertTrue(stop.contains("gatewayWalkAuthorityController.invalidate(transition.previous.epoch)"))
        // The explicit safety-stop helper begins its end before the transition, so no second end.
        val cleanup = functionBlock("private fun performWalkSessionSafetyStopCleanup(")
        assertTrue(
            cleanup.indexOf("gatewayWalkAuthorityController.beginEnd(it.epoch)") in
                0 until cleanup.indexOf("transitionWalkSession(WalkSessionEvent.SafetyStopRequested)"),
        )
    }

    /** S03-6 P3: no "다른 기능은 계속" notice right before a stop. */
    @Test fun noContinuationNoticeIsShownJustBeforeASafetyStop() {
        val metricLoss = functionBlock("private fun handleRuntimeMetricLoss(")
        assertFalse(metricLoss.contains("현재 사용 가능한 다른 기능은 계속됩니다."))
        assertTrue(metricLoss.contains("requestCameraFallbackStart(CameraFallbackStartReason.RUNTIME_METRIC_LOST, availability)"))
        val observed = functionBlock("private fun applyObservedPermissionStateChange(")
        assertTrue(
            observed.indexOf("refreshStartupCapabilityUi()") in
                0 until observed.indexOf("나머지 기능은 계속 사용합니다."),
        )
        assertTrue(observed.contains("stateAfterRefresh in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)"))
        val fallback = functionBlock("private fun requestCameraFallbackStart(")
        val stopReturn = fallback.substringAfter("walkAfterChange.state == WalkSessionState.SAFE_STOP")
            .substringBefore("val automaticTransitionReady")
        assertTrue(stopReturn.contains("pendingCameraFallbackStart = null"))
        assertTrue(stopReturn.contains("return"))
        assertFalse(stopReturn.contains("updateStatus("))
    }

    /** S04-11 P3: a command whose result never arrived is said, not dropped silently. */
    @Test fun aCommandResultTimeoutIsAnnouncedBeforeTheRestart() {
        val failure = source.substringAfter("HandsFreeVoiceTerminalFailure.CommandResultTimeout,\n            -> {")
            .substringBefore("\n    private fun ")
        assertTrue(
            failure.indexOf("speakInteraction(\"요청한 결과를 받지 못했습니다. 다시 말해 주세요.\")") in
                0 until failure.indexOf("scheduleHandsFreeVoiceRestart()"),
        )
        assertTrue(failure.contains("if (failure == HandsFreeVoiceTerminalFailure.CommandResultTimeout) {"))
    }

    /** S06-31: the list TalkBack reads and the confirmation screen use the origin-aware approximation. */
    @Test fun onScreenCandidateDistanceIsApproximateAndCarriesTheOriginNotice() {
        val list = functionBlock("private fun updateDestinationSearchUi()")
        assertTrue(list.contains("approximateDestinationDistanceKo(result.distanceM, originAccuracyM)"))
        assertTrue(list.contains("val originAccuracyM = destinationSearchOrigin?.accuracyM"))
        assertFalse(list.contains("formatDestinationDistance(result.distanceM)"))
        val notice = list.indexOf("destinationOriginUncertaintyNoticeKo(destinationSearchOrigin)?.let { notice ->")
        assertTrue(notice in 0 until list.indexOf("destinationSearchResults.forEachIndexed"))
        val confirmation = functionBlock("private fun nativeDestinationConfirmationMessage()")
        assertTrue(confirmation.contains("approximateDestinationDistanceKo(it, destinationSearchOrigin?.accuracyM)"))
        assertTrue(confirmation.contains("destinationOriginUncertaintyNoticeKo(destinationSearchOrigin)"))
        assertFalse(confirmation.contains("약 \${it}미터"))
    }

    /** S07-41: production reads Settings.Global.BOOT_COUNT; -1 keeps the monotonic fallback. */
    @Test fun theRouteSnapshotStoreReadsTheBootCount() {
        val create = source.substringAfter("routeSnapshotStore = EncryptedRouteSnapshotStore(")
            .substringBefore("val routeSnapshotNowEpochMs")
        assertTrue(create.contains("bootCount = {"))
        assertTrue(create.contains("Settings.Global.getInt(applicationContext.contentResolver, Settings.Global.BOOT_COUNT, -1)"))
    }
}
