package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityOfficialEnvironmentStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun confirmationIsMemoryOnlyAndBoundToCurrentEpoch() {
        val confirmation = functionBlock("private fun confirmOfficialEnvironmentConditions()")

        assertTrue(confirmation.contains("epoch = snapshot.epoch"))
        assertTrue(confirmation.contains("brightTime = EnvironmentEvidenceStatus.PASS"))
        assertTrue(confirmation.contains("dryWeather = EnvironmentEvidenceStatus.PASS"))
        assertTrue(confirmation.contains("noDenseFog = EnvironmentEvidenceStatus.PASS"))
        assertTrue(confirmation.contains("ordinaryUrbanSidewalk = EnvironmentEvidenceStatus.PASS"))
        assertTrue(confirmation.contains("noConstruction = EnvironmentEvidenceStatus.PASS"))
        assertTrue(confirmation.contains("noSevereCrowding = EnvironmentEvidenceStatus.PASS"))
        assertTrue(
            confirmation.contains(
                "supportLimitsNoticeAcknowledged = EnvironmentEvidenceStatus.PASS",
            ),
        )
        assertFalse(confirmation.contains("stepLengthPrefs"))
        assertFalse(source.contains("PREF_OFFICIAL_ENVIRONMENT"))
    }

    @Test
    fun gpsPreflightIsCancellationGenerationAndEpochBound() {
        val request = functionBlock("private fun requestOfficialEnvironmentGpsPreflight(")
        val lease = functionBlock("private fun isOfficialEnvironmentGpsPreflightCurrent(")
        val invalidate = functionBlock("private fun invalidateOfficialEnvironmentEvidence(")

        assertTrue(request.contains("CancellationTokenSource()"))
        assertTrue(request.contains("getCurrentLocation("))
        assertTrue(request.contains("Priority.PRIORITY_HIGH_ACCURACY"))
        assertTrue(request.contains("isOfficialEnvironmentGpsPreflightCurrent("))
        assertTrue(lease.contains("officialEnvironmentGpsCancellation === cancellation"))
        assertTrue(lease.contains("generation == officialEnvironmentPreflightGeneration"))
        assertTrue(lease.contains("snapshot.epoch == epoch"))
        assertTrue(invalidate.contains("officialEnvironmentPreflightGeneration += 1L"))
        assertTrue(invalidate.contains("officialEnvironmentGpsCancellation?.cancel()"))
        assertTrue(invalidate.contains("officialEnvironmentGpsCancellation = null"))
        assertFalse(request.contains("activateWalkSessionRuntime()"))
        assertFalse(request.contains("confirmedStartupCapabilityDecision ="))
    }

    @Test
    fun officialEnvironmentMapsIntoWalkReadinessFailClosed() {
        val readiness = functionBlock("private fun officialEnvironmentReadiness(")
        val capture = functionBlock("private fun captureWalkSessionReadiness(")
        val blockReason = functionBlock("private fun walkSessionReadinessBlockReason(")

        assertTrue(readiness.contains("OfficialEnvironmentSupport.SUPPORTED"))
        assertTrue(readiness.contains("WalkSessionReadinessStatus.READY"))
        assertTrue(
            readiness.contains(
                "OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY",
            ),
        )
        assertTrue(readiness.contains("OfficialEnvironmentSupport.LIMITED"))
        assertTrue(readiness.contains("WalkSessionReadinessStatus.PENDING"))
        assertTrue(readiness.contains("OfficialEnvironmentSupport.UNSUPPORTED"))
        assertTrue(readiness.contains("WalkSessionReadinessStatus.UNAVAILABLE"))
        assertTrue(
            capture.contains(
                "WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT ->\n" +
                    "                    officialEnvironmentReadiness(epoch)",
            ),
        )
        assertTrue(blockReason.contains("officialEnvironmentBlockReason()?.let { return it }"))
        assertTrue(
            blockReason.indexOf("officialEnvironmentBlockReason()") <
                blockReason.indexOf("missingRequiredWalkSessionPermissions(action)"),
        )
    }

    @Test
    fun sessionIdentityBoundariesInvalidateEnvironmentEvidence() {
        val manualReporterInput = functionBlock("private fun persistReporterUserFromInput()")
        val verifiedActorBinding =
            functionBlock("private fun bindFirstRunVerifiedActorForTraining()")

        assertTrue(
            manualReporterInput.contains(
                "updateNavigationStatus(\"login=blocked manual_reporter_id_disallowed\")",
            ),
        )
        assertFalse(manualReporterInput.contains("reporterUserId ="))
        assertTrue(
            functionBlock("internal fun pauseWalkSafeRuntime()")
                .contains("invalidateOfficialEnvironmentEvidence(\"app_paused\")"),
        )
        assertTrue(
            functionBlock("override fun onDestroy()")
                .contains("invalidateOfficialEnvironmentEvidence(\"app_destroyed\")"),
        )
        assertTrue(
            verifiedActorBinding
                .contains("invalidateOfficialEnvironmentEvidence(\"first_run_actor_bound\")"),
        )
        assertTrue(
            functionBlock("private fun onAccountLogoutClicked()")
                .contains(
                    "invalidateOfficialEnvironmentEvidence(\"priority_user_account_logged_out\")",
                ),
        )
        assertTrue(
            functionBlock("private fun resetPriorityUserTraining()")
                .contains("invalidateOfficialEnvironmentEvidence(\"priority_user_training_reset\")"),
        )
        assertTrue(
            functionBlock("private fun startFreshWalk(")
                .contains("invalidateOfficialEnvironmentEvidence(\"new_walk:\$reason\")"),
        )
        assertTrue(
            functionBlock("private fun enterWalkSessionSafetyStopAndCancelOutputs(")
                .contains("invalidateOfficialEnvironmentEvidence(\"safety_stop:\$reason\")"),
        )
        assertTrue(
            functionBlock("private fun applyRuntimeReadinessIfActive(")
                .contains("invalidateOfficialEnvironmentEvidence(\"runtime_readiness_changed\")"),
        )
        val invalidate = functionBlock("private fun invalidateOfficialEnvironmentEvidence(")
        assertTrue(invalidate.contains("officialEnvironmentUserConfirmation = null"))
        assertTrue(invalidate.contains("officialEnvironmentGpsEvidence = null"))
        assertTrue(invalidate.contains("officialEnvironmentCameraEvidence = null"))
        assertTrue(invalidate.contains("officialEnvironmentRuntimeGuard = null"))
    }

    @Test
    fun runtimeRetrySuppressesOutputsWithoutStoppingMeasurement() {
        val decision = functionBlock("private fun applyOfficialEnvironmentRuntimeDecision(")
        val retry = decision
            .substringAfter("OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY ->")
            .substringBefore("OfficialEnvironmentRuntimeAction.SAFE_STOP ->")
        val terminal = decision.substringAfter("OfficialEnvironmentRuntimeAction.SAFE_STOP ->")

        assertTrue(retry.contains("officialEnvironmentOutputsAllowed = false"))
        assertTrue(retry.contains("reportPrivacyConsentSession.cancelActiveCalls()"))
        assertTrue(retry.contains("scheduleOfficialEnvironmentRuntimeWatchdog"))
        assertTrue(
            retry.indexOf("cancelVoiceCommandRecognition()") <
                retry.indexOf("speakInteraction("),
        )
        assertFalse(retry.contains("cancelWalkSessionOutputs("))
        assertFalse(retry.contains("stopLocationUpdates("))
        assertFalse(retry.contains("stopCameraFallbackSession("))
        assertFalse(retry.contains("stopDepthSession("))
        assertTrue(
            terminal.contains(
                "enterWalkSessionSafetyStopAndCancelOutputs(",
            ),
        )
        assertTrue(
            functionBlock("private fun isCameraFallbackAdvisoryStillDeliverable(")
                .contains("officialEnvironmentOutputsAllowed"),
        )
        assertTrue(
            functionBlock("private fun processReportCandidate(")
                .contains(
                    "if (!officialEnvironmentOutputsAllowed || !phoneMountingOutputsAllowed)",
                ),
        )
        val preflightFrame =
            functionBlock("private fun handleRuntimeMetricPreflightFrame(")
        assertTrue(
            preflightFrame.indexOf("walkSessionLifecycle.snapshot().epoch != expectedWalkEpoch") <
                preflightFrame.indexOf("observeOfficialEnvironmentCameraFrame("),
        )
        val frameFailure = functionBlock("private fun handleRuntimeMetricFrameFailure(")
        assertTrue(frameFailure.contains("arSessionGeneration != expectedArSessionGeneration"))
        assertTrue(frameFailure.contains("walkSessionLifecycle.snapshot().epoch != expectedWalkEpoch"))
        val watchdog =
            functionBlock("private fun scheduleOfficialEnvironmentRuntimeWatchdog(")
        assertTrue(watchdog.contains("evidence.observedAtElapsedRealtimeMs"))
        assertTrue(watchdog.contains("evidence.maximumEvidenceAgeMs"))
        assertTrue(
            watchdog.contains(
                "minOf(profile.maximumMeasuredEvidenceAgeMs, evidenceMaximumAgeMs)",
            ),
        )
    }

    @Test
    fun centralSafetyStopClosesBothCameraPipelines() {
        val safetyStop =
            functionBlock("private fun enterWalkSessionSafetyStopAndCancelOutputs(")

        assertInOrder(
            safetyStop,
            "invalidateOfficialEnvironmentEvidence(\"safety_stop:\$reason\")",
            "cancelWalkSessionOutputs(reason)",
            "stopCameraFallbackSession(updateUi = false)",
            "stopDepthSession(closeSession = true)",
        )
    }

    @Test
    fun environmentStatusExposesCauseAndNextActionAccessibly() {
        val statusView = source.substringAfter(
            "officialEnvironmentStatusText = TextView(this).apply",
        ).substringBefore("startupCapabilityText = TextView(this).apply")
        // 준비 표면은 walkReadinessControls 안에 순서 그대로 들어간다.
        val overlay = source.substringAfter("walkReadinessControls = LinearLayout(this).apply")
            .substringBefore("\n        }\n")
        val update = functionBlock("private fun updateOfficialEnvironmentUi()")
        val message = functionBlock("private fun officialEnvironmentStatusMessage(")

        assertTrue(statusView.contains("View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(update.contains("officialEnvironmentStatusText.contentDescription = message"))
        assertTrue(update.contains("OfficialEnvironmentPolicy.productionProfile != null"))
        assertTrue(update.contains("승인된 환경 프로필 없음"))
        assertTrue(message.contains("원인:"))
        assertTrue(message.contains("다음 행동:"))
        assertInOrder(
            overlay,
            "addView(priorityUserOnboardingControls)",
            "addView(officialEnvironmentStatusText)",
            "addView(officialEnvironmentConfirmButton)",
            "addView(startupCapabilityText)",
        )
    }

    @Test
    fun activeLocationPermissionRevocationEntersSafetyStop() {
        val permissionChange =
            functionBlock("private fun applyObservedPermissionStateChange(")

        assertTrue(permissionChange.contains("else -> \"location_permission_revoked\""))
        assertTrue(
            permissionChange.contains(
                "enterWalkSessionSafetyStopAndCancelOutputs(safetyStopReason)",
            ),
        )
        assertTrue(permissionChange.contains("공식 사용환경의 위치 품질"))
        assertFalse(permissionChange.contains("WalkSessionEvent.Resume"))
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing function: $signature" }
        var braceDepth = 0
        var sawOpeningBrace = false
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> {
                    braceDepth += 1
                    sawOpeningBrace = true
                }
                '}' -> if (sawOpeningBrace) {
                    braceDepth -= 1
                    if (braceDepth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("unterminated function: $signature")
    }

    private fun assertInOrder(text: String, vararg fragments: String) {
        var previous = -1
        fragments.forEach { fragment ->
            val index = text.indexOf(fragment)
            assertTrue("missing or out of order: $fragment", index > previous)
            previous = index
        }
    }
}
