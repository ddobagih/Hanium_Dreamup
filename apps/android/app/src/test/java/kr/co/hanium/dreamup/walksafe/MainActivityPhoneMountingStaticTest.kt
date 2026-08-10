package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityPhoneMountingStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun chestAndNecklaceChoicesAreAccessibleAndPlacedInTheStartupOverlay() {
        val content = functionBlock("private fun buildContentView(): FrameLayout")
        val statusView = blockStartingAt(
            content,
            "phoneMountingStatusText = TextView(this).apply",
        )
        val overlay = blockStartingAt(content, "val overlay = LinearLayout(this).apply")

        assertTrue(statusView.contains("contentDescription = text"))
        assertTrue(statusView.contains("View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(statusView.contains("View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(content.contains("ViewCompat.setAccessibilityHeading(phoneMountingStatusText"))
        assertTrue(content.contains("phoneMountingChestConfirmButton ="))
        assertTrue(content.contains("confirmPhoneMounting(PhoneMountingMethod.CHEST_FORWARD)"))
        assertTrue(content.contains("phoneMountingNecklaceConfirmButton ="))
        assertTrue(
            content.contains("confirmPhoneMounting(PhoneMountingMethod.NECKLACE_FORWARD)"),
        )
        assertInOrder(
            overlay,
            "addView(officialEnvironmentConfirmButton)",
            "addView(phoneMountingStatusText)",
            "addView(phoneMountingChestConfirmButton)",
            "addView(phoneMountingNecklaceConfirmButton)",
            "addView(startupCapabilityText)",
        )
    }

    @Test
    fun confirmationIsMemoryOnlyEpochBoundAndExplicitAfterAFault() {
        val confirmation = functionBlock("private fun confirmPhoneMounting(")

        assertTrue(confirmation.contains("PhoneMountingUserConfirmation("))
        assertTrue(confirmation.contains("epoch = snapshot.epoch"))
        assertTrue(confirmation.contains("confirmedAtElapsedRealtimeMs ="))
        assertTrue(confirmation.contains("method = method"))
        assertTrue(confirmation.contains("postFaultCorrectionConfirmed ="))
        assertTrue(confirmation.contains("correctionRequiredSinceElapsedRealtimeMs"))
        assertFalse(confirmation.contains("phoneMountingOutputsAllowed = true"))
        assertFalse(confirmation.contains("WalkSessionEvent.Resume"))
        assertFalse(confirmation.contains("activateWalkSessionRuntime()"))
        assertFalse(confirmation.contains("stepLengthPrefs"))
        assertFalse(source.contains("PREF_PHONE_MOUNTING"))
        assertFalse(confirmation.contains("runtimeRetryRequested = true"))
        assertInOrder(
            confirmation,
            "synchronized(phoneMountingObservationLock)",
            "val value = SystemClock.elapsedRealtime()",
            "phoneMountingUserConfirmation = PhoneMountingUserConfirmation(",
            "phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs = value",
            "phoneMountingAppliedFaultSequence = maxOf(",
            "schedulePhoneMountingRuntimeRetryTimeout(",
            "새 카메라 검사를 진행합니다.",
            "return\n        } else {",
        )
    }

    @Test
    fun mountingReadinessAndRuntimeActivationFailClosed() {
        val readiness = functionBlock("private fun phoneMountingReadiness(")
        val capture = functionBlock("private fun captureWalkSessionReadiness(")
        val blockReason = functionBlock("private fun walkSessionReadinessBlockReason(")
        val refresh = functionBlock("private fun refreshStartupCapabilityUi()")
        val activation = functionBlock("private fun activateWalkSessionRuntime()")

        assertTrue(readiness.contains("PhoneMountingPolicy.productionProfile"))
        assertTrue(readiness.contains("WalkSessionReadinessStatus.UNAVAILABLE"))
        assertTrue(readiness.contains("PhoneMountingStatus.SUITABLE"))
        assertTrue(readiness.contains("WalkSessionReadinessStatus.READY"))
        assertTrue(readiness.contains("PhoneMountingStatus.CORRECTION_REQUIRED"))
        assertTrue(readiness.contains("WalkSessionReadinessStatus.PENDING"))
        assertTrue(readiness.contains("PhoneMountingStatus.UNUSABLE"))
        assertTrue(
            capture.contains(
                "WalkSessionReadinessRequirement.PHONE_MOUNTING",
            ),
        )
        assertTrue(capture.contains("phoneMountingReadiness(epoch)"))
        assertTrue(blockReason.contains("phoneMountingBlockReason()?.let { return it }"))
        assertTrue(
            blockReason.indexOf("phoneMountingBlockReason()") <
                blockReason.indexOf("missingRequiredWalkSessionPermissions(action)"),
        )
        assertTrue(refresh.contains("phoneMountingReady"))
        assertTrue(
            refresh.indexOf("phoneMountingReady") <
                refresh.indexOf("startupCapabilityConfirmButton.apply"),
        )
        assertTrue(refresh.substringAfter("startupCapabilityConfirmButton.apply")
            .contains("phoneMountingReady"))
        assertTrue(activation.contains("activatePhoneMountingRuntime()"))
        assertTrue(
            activation.indexOf("activatePhoneMountingRuntime()") <
                activation.indexOf("startNavigationServicesIfNeeded()"),
        )
    }

    @Test
    fun mountingConsumesTheSharedCameraAssessmentWithoutNewThresholds() {
        val observation = functionBlock("private fun observeOfficialEnvironmentCameraFrame(")
        val currentAssessment = declarationRegion("private fun currentPhoneMountingAssessment(")
        val runtimeActivation = functionBlock("private fun activatePhoneMountingRuntime()")
        val runtimeReassessment =
            functionBlock("private fun applyCurrentPhoneMountingRuntimeAssessment(")
        val cameraField = phoneMountingCameraAssessmentField()

        assertTrue(observation.contains("CameraFrameQualityPolicy.assess("))
        assertTrue(
            "the camera frame must be assessed once and shared",
            observation.countOccurrences("CameraFrameQualityPolicy.assess(") == 1,
        )
        assertTrue(observation.contains("$cameraField ="))
        assertTrue(observation.contains(".asMeasuredEnvironmentEvidence()"))
        assertTrue(currentAssessment.contains("PhoneMountingPolicy.assess("))
        assertTrue(
            currentAssessment.contains(
                "phase: PhoneMountingAssessmentPhase = PhoneMountingAssessmentPhase.PREFLIGHT",
            ),
        )
        assertTrue(runtimeActivation.contains("phase = PhoneMountingAssessmentPhase.ACTIVE"))
        assertTrue(runtimeReassessment.contains("phase = PhoneMountingAssessmentPhase.ACTIVE"))
        assertTrue(
            currentAssessment.contains(
                "cameraFrameQualityOverride ?: $cameraField",
            ),
        )
        assertTrue(currentAssessment.contains("cameraFrameQuality = cameraFrameQuality"))
        assertTrue(currentAssessment.contains("PhoneMountingPolicy.productionProfile"))

        val mountingIntegration = listOf(
            functionBlock("private fun confirmPhoneMounting("),
            currentAssessment,
            functionBlock("private fun phoneMountingReadiness("),
            functionBlock("private fun applyCurrentPhoneMountingRuntimeAssessment("),
        ).joinToString("\n")
        assertFalse(mountingIntegration.contains("ApprovedCameraFrameQualityProfile("))
        listOf(
            "minimumNormalizedBrightness",
            "maximumOccludedFraction",
            "maximumAngularShakeDegreesPerSecond",
            "minimumMountPitchDegrees",
            "maximumMountPitchDegrees",
        ).forEach { threshold ->
            assertFalse(
                "duplicated camera threshold: $threshold",
                mountingIntegration.contains(threshold),
            )
        }
    }

    @Test
    fun sessionIdentityBoundariesInvalidateMountingEvidence() {
        val manualReporterInput = functionBlock("private fun persistReporterUserFromInput()")
        assertTrue(
            manualReporterInput.contains(
                "updateNavigationStatus(\"login=blocked manual_reporter_id_disallowed\")",
            ),
        )
        assertFalse(manualReporterInput.contains("reporterUserId ="))

        val invalidations = listOf(
            "internal fun pauseWalkSafeRuntime()" to "app_paused",
            "override fun onDestroy()" to "app_destroyed",
            "private fun bindFirstRunVerifiedActorForTraining()" to "first_run_actor_bound",
            "private fun onAccountLogoutClicked()" to "priority_user_account_logged_out",
            "private fun resetPriorityUserTraining()" to "priority_user_training_reset",
            "private fun startFreshWalk(" to "new_walk:",
            "private fun enterWalkSessionSafetyStopAndCancelOutputs(" to "safety_stop:",
            "private fun applyRuntimeReadinessIfActive(" to "runtime_readiness_changed",
        )
        invalidations.forEach { (signature, reason) ->
            assertTrue(
                "$signature must invalidate mounting evidence for $reason",
                functionBlock(signature).contains("invalidatePhoneMountingEvidence(\"$reason"),
            )
        }

        val invalidate = functionBlock("private fun invalidatePhoneMountingEvidence(")
        assertTrue(invalidate.contains("phoneMountingObservationGeneration += 1L"))
        assertTrue(invalidate.contains("phoneMountingWatchdogGeneration += 1L"))
        assertTrue(invalidate.contains("phoneMountingUserConfirmation = null"))
        assertTrue(invalidate.contains("${phoneMountingCameraAssessmentField()} = null"))
        assertTrue(invalidate.contains("phoneMountingRuntimeState = null"))
        assertTrue(invalidate.contains("phoneMountingOutputsAllowed = false"))
    }

    @Test
    fun runtimeFaultSuppressesOutputsRequiresExplicitRetryAndCannotAutoResume() {
        val currentRuntime =
            functionBlock("private fun applyCurrentPhoneMountingRuntimeAssessment(")
        val reportRevocation =
            functionBlock("private fun revokePhoneMountingReportOutputLease(")
        val runtime = currentRuntime +
            "\n" +
            functionBlock("private fun applyPhoneMountingRuntimeAssessment(")
        val correction = runtime
            .substringAfter("PhoneMountingStatus.CORRECTION_REQUIRED ->")
            .substringBefore("PhoneMountingStatus.UNUSABLE ->")
        val unusable = runtime.substringAfter("PhoneMountingStatus.UNUSABLE ->")
        val watchdog = phoneMountingWatchdogBlock()
        val retryTimeout =
            functionBlock("private fun schedulePhoneMountingRuntimeRetryTimeout(")
        val confirmation = functionBlock("private fun confirmPhoneMounting(")
        val observation = functionBlock("private fun observeOfficialEnvironmentCameraFrame(")

        assertTrue(currentRuntime.contains("runtimeRetryRequested = runtimeRetryRequested"))
        assertTrue(currentRuntime.contains("revokePhoneMountingReportOutputLease()"))
        assertTrue(reportRevocation.contains("synchronized(phoneMountingObservationLock)"))
        assertTrue(reportRevocation.contains("phoneMountingOutputsAllowed = false"))
        assertTrue(reportRevocation.contains("synchronized(reportUploadSafetyLock)"))
        assertTrue(reportRevocation.contains("reportUploadSafetyGeneration += 1L"))
        assertTrue(reportRevocation.contains("reportPrivacyConsentSession.cancelActiveCalls()"))
        assertTrue(correction.contains("invalidatePhoneMountingDetectionOutputs(epoch)"))
        assertTrue(correction.contains("cancelActiveRouteRequest()"))
        assertTrue(correction.contains("cancelDestinationSearch()"))
        assertTrue(correction.contains("phoneMountingWatchdogGeneration += 1L"))
        assertTrue(correction.contains("playPhoneMountingCorrectionVibration()"))
        assertTrue(correction.contains("speakInteraction("))
        assertFalse(correction.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertFalse(correction.contains("stopCameraFallbackSession("))
        assertFalse(correction.contains("stopDepthSession("))
        assertTrue(unusable.contains("phoneMountingOutputsAllowed = false"))
        assertTrue(unusable.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertTrue(unusable.contains("playPhoneMountingSafetyStopVibration()"))
        assertFalse(unusable.contains("playPhoneMountingCorrectionVibration()"))
        assertTrue(watchdog.contains("phoneMountingWatchdogGeneration"))
        assertTrue(watchdog.contains("walkSessionLifecycle.isRuntimeEpochCurrent(epoch)"))
        assertTrue(watchdog.contains("runtimeRetryRequested = false"))
        assertFalse(confirmation.contains("runtimeRetryRequested = true"))
        assertTrue(observation.contains("runtimeRetryRequested = runtimeRetryRequested"))
        assertTrue(observation.contains("cameraAssessment.observedAtElapsedRealtimeMs"))
        assertTrue(observation.contains("it > retryArmedAt"))
        assertTrue(retryTimeout.contains("runtimeRetryRequested = true"))
        assertTrue(retryTimeout.contains("phoneMountingRuntimeRetryGeneration"))
        assertFalse(observation.contains("phoneMountingOutputsAllowed = true"))
        assertFalse(observation.contains("WalkSessionEvent.Resume"))
        assertTrue(observation.contains("phoneMountingObservationGeneration"))
        assertTrue(observation.contains("phoneMountingOutputsAllowed = false"))
        assertTrue(observation.contains("cameraFrameQualityOverride = cameraAssessment"))
        assertTrue(observation.contains("phoneMountingPendingFaultSequence"))
        assertTrue(observation.contains("phoneMountingAppliedFaultSequence"))
        assertTrue(observation.contains("currentObservedAt <= retryArmedAt"))
        assertTrue(observation.contains("currentObservedAt >= latestObservedAt"))
        assertTrue(observation.contains("observationSequence = observationSequence"))
        assertTrue(observation.contains("observationIsFault = mountingFault"))
        assertTrue(currentRuntime.contains("observationSequence <= phoneMountingAppliedFaultSequence"))
        assertInOrder(
            observation.substringAfter("if (runtimeRetryRequested && assessment != null)"),
            "phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs = null",
            "updatePhoneMountingUi()",
        )
        assertInOrder(
            currentRuntime,
            "phoneMountingPendingFaultSequence >",
            "currentPhoneMountingAssessment(",
            "revokePhoneMountingReportOutputLease()",
            "phoneMountingRuntimeState = assessment.nextState",
        )
        val fallback = functionBlock("private fun analyzeCameraFallbackFrame(")
        assertInOrder(
            fallback,
            "observeOfficialEnvironmentCameraFrame(",
            "if (!walkSafetyOutputsAllowed()) return",
            "frameDetector.detect(",
        )
        val afterEvaluation = fallback.substringAfter("nonMetricAdvisoryPolicy.evaluate(")
        assertTrue(afterEvaluation.contains("isCurrentFrameGeneration("))
        assertTrue(afterEvaluation.contains("!walkSafetyOutputsAllowed()"))
        assertTrue(afterEvaluation.contains("nonMetricAdvisoryPolicy.reset()"))
        assertInOrder(
            afterEvaluation,
            "nonMetricAdvisoryPolicy.reset()",
            "emitCameraFallbackAdvisory(",
        )
        val fallbackEmitter =
            functionBlock("private fun emitCameraFallbackAdvisory(")
        assertInOrder(
            fallbackEmitter.substringAfter("onDelivered = {"),
            "isCameraFallbackAdvisoryStillDeliverable(",
            "nonMetricAdvisoryPolicy.isDeliveryCurrent(action)",
            "confirmCameraFallbackAdvisoryDelivery(",
        )
        assertTrue(
            declarationRegion("internal fun publishDetectionSnapshot(")
                .contains("!walkSafetyOutputsAllowed()"),
        )
    }

    @Test
    fun asynchronousWalkOutputsUseTheCombinedSafetyGate() {
        val combinedGate = declarationRegion("private fun walkSafetyOutputsAllowed()")
        val feedbackCompletion = functionBlock("private fun confirmFeedbackDelivery(")
        val navigationSpeech = functionBlock("private fun speakNavigation(")
        val routeGuidance = functionBlock("private fun updateRouteGuidance(")

        assertTrue(combinedGate.contains("officialEnvironmentOutputsAllowed"))
        assertTrue(combinedGate.contains("phoneMountingOutputsAllowed"))
        listOf(
            "private fun currentNavigationCollectionAllowsWork()",
            "private fun isCameraFallbackAdvisoryStillDeliverable(",
            "private fun currentFeedbackDeviceGateAllowsAlerts()",
            "private fun speakNavigation(",
            "private fun dispatchNavigationTalkBackFallback(",
            "private fun maybePlayProgressBeep(",
            "private fun isDestinationSearchLeaseCurrent(",
            "private fun isRouteRequestLeaseCurrent(",
        ).forEach { signature ->
            assertUsesCombinedSafetyGate(functionBlock(signature), signature)
        }
        assertTrue(feedbackCompletion.contains("isFeedbackActionStillDeliverable(action)"))
        assertTrue(feedbackCompletion.contains("synchronized(phoneMountingObservationLock)"))
        assertTrue(feedbackCompletion.contains("if (!phoneMountingOutputsAllowed)"))
        val fallbackConfirmation =
            functionBlock("private fun confirmCameraFallbackAdvisoryDelivery(")
        assertTrue(
            fallbackConfirmation.contains(
                "isCameraFallbackAdvisoryStillDeliverable(",
            ),
        )
        assertTrue(
            fallbackConfirmation.contains("synchronized(phoneMountingObservationLock)"),
        )
        assertTrue(fallbackConfirmation.contains("if (!phoneMountingOutputsAllowed)"))
        assertTrue(navigationSpeech.substringAfter("val mainThreadCompletion")
            .contains("walkSafetyOutputsAllowed()"))
        assertInOrder(
            routeGuidance.substringAfter("speakNavigation(instruction)"),
            "!walkSafetyOutputsAllowed()",
            "routeNavigator.acknowledgeInstruction(",
        )

        val report = functionBlock("private fun processReportCandidate(")
        assertTrue(
            "report callbacks must re-check the combined gate before asynchronous output",
            report.countOccurrences("runIfReportUploadTerminalCurrent(") >= 8,
        )
        assertUsesCombinedSafetyGate(
            report.substringBefore("reportUploaderExecutor.execute"),
            "report pre-dispatch",
        )
        assertUsesCombinedSafetyGate(
            report.substringAfter("reportUploaderExecutor.execute")
                .substringBefore("val response = uploadCall.execute()"),
            "report executor preflight",
        )
        assertTrue(
            "report response and callback handling must use the guarded terminal callback",
            report.substringAfter("val response = uploadCall.execute()")
                .contains("runIfReportUploadTerminalCurrent("),
        )

        val reportTerminalGate =
            declarationRegion("private fun isReportUploadTerminalCurrent(")
        assertTrue(reportTerminalGate.contains("reportUploadSafetyGeneration"))
        assertTrue(reportTerminalGate.contains("automaticReportUploadSafetyGeneration"))
        assertTrue(reportTerminalGate.contains("!uploadCall.isCancelled()"))
        assertTrue(
            reportTerminalGate.contains(
                "walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch)",
            ),
        )
        assertTrue(reportTerminalGate.contains("isCurrentGatewaySession(gatewaySession)"))
        assertTrue(reportTerminalGate.contains("reportPrivacyConsentSession.isGranted()"))
        assertTrue(reportTerminalGate.contains("isWalkSessionRuntimeActive()"))
        assertTrue(reportTerminalGate.contains("walkSafetyOutputsAllowed()"))
        val terminalMutation = functionBlock("private fun runIfReportUploadTerminalCurrent(")
        assertTrue(terminalMutation.contains("synchronized(phoneMountingObservationLock)"))
        assertTrue(terminalMutation.contains("synchronized(reportUploadSafetyLock)"))
        assertTrue(report.contains("automaticReportConsentGranted"))
        val automaticConsent =
            functionBlock("private fun updateIntegratedConsentDraft(")
        assertTrue(
            automaticConsent.contains("applyImmediateConsentWithdrawals(setOf(item))"),
        )
        val immediateConsentWithdrawals =
            functionBlock("private fun applyImmediateConsentWithdrawals(")
        assertTrue(
            immediateConsentWithdrawals.contains(
                "automaticReportUploadSafetyGeneration += 1L",
            ),
        )
        assertFalse(automaticConsent.contains("\n                reportUploadSafetyGeneration += 1L"))
    }

    private fun phoneMountingCameraAssessmentField(): String {
        val match = Regex(
            """private var ([A-Za-z0-9_]*PhoneMounting[A-Za-z0-9_]*): """ +
                """CameraFrameQualityAssessment\?""",
        ).find(source)
        checkNotNull(match) { "missing phone mounting CameraFrameQualityAssessment field" }
        return match.groupValues[1]
    }

    private fun phoneMountingWatchdogBlock(): String {
        val match = Regex(
            """private fun (schedulePhoneMounting[A-Za-z0-9_]*Watchdog)\(""",
        ).find(source)
        checkNotNull(match) { "missing phone mounting evidence watchdog" }
        return functionBlock("private fun ${match.groupValues[1]}(")
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing function: $signature" }
        return balancedBlock(source, start)
    }

    private fun declarationRegion(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing declaration: $signature" }
        val end = listOf(
            "\n    private fun ",
            "\n    internal fun ",
            "\n    override fun ",
        ).map { marker -> source.indexOf(marker, start + signature.length) }
            .filter { it >= 0 }
            .minOrNull()
            ?: source.length
        return source.substring(start, end)
    }

    private fun blockStartingAt(text: String, marker: String): String {
        val start = text.indexOf(marker)
        check(start >= 0) { "missing block: $marker" }
        return balancedBlock(text, start)
    }

    private fun balancedBlock(text: String, start: Int): String {
        var braceDepth = 0
        var sawOpeningBrace = false
        for (index in start until text.length) {
            when (text[index]) {
                '{' -> {
                    braceDepth += 1
                    sawOpeningBrace = true
                }
                '}' -> if (sawOpeningBrace) {
                    braceDepth -= 1
                    if (braceDepth == 0) return text.substring(start, index + 1)
                }
            }
        }
        error("unterminated block at offset $start")
    }

    private fun assertInOrder(text: String, vararg fragments: String) {
        var previous = -1
        fragments.forEach { fragment ->
            val index = text.indexOf(fragment)
            assertTrue("missing or out of order: $fragment", index > previous)
            previous = index
        }
    }

    private fun assertUsesCombinedSafetyGate(text: String, boundary: String) {
        val usesHelper = text.contains("walkSafetyOutputsAllowed()")
        val usesBothInputs =
            text.contains("officialEnvironmentOutputsAllowed") &&
                text.contains("phoneMountingOutputsAllowed")
        assertTrue(
            "$boundary must combine official environment and phone mounting output gates",
            usesHelper || usesBothInputs,
        )
    }

    private fun String.countOccurrences(fragment: String): Int =
        windowed(fragment.length).count { it == fragment }
}
