package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityDeviceCheckRecoveryContractStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val coordinatorSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/network/" +
            "GatewaySessionProcessCoordinator.kt",
    ).readText()
    private val deviceCheckResultStoreSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/device/" +
            "AndroidDeviceCheckResultStore.kt",
    ).readText()

    @Test
    fun acceptedDeviceCheckAndTrainingTransitionsPublishTheLatestFirstRunSnapshot() {
        val deviceCheck = functionBlock("private fun completePostLoginDeviceCheckPass(")
        assertInOrder(
            deviceCheck,
            "if (!transition.accepted) return",
            "GatewaySessionProcessCoordinator.updateFirstRunSnapshot(",
            "firstRunOnboardingSnapshot = transition.current",
        )
        assertCoordinatorProgressPublishReachable(
            code = deviceCheck.substringAfterRequired(
                "firstRunOnboardingSnapshot = transition.current",
            ),
            transition = "DEVICE_CHECK -> FP004_TRAINING",
        )

        val training = functionBlock("private fun completeFirstRunFp004TrainingIfReady(")
        assertInOrder(
            training,
            "if (!completed.accepted) return false",
            "GatewaySessionProcessCoordinator.updateFirstRunSnapshot(",
            "firstRunOnboardingSnapshot = completed.current",
        )
        assertCoordinatorProgressPublishReachable(
            code = training.substringAfterRequired(
                "firstRunOnboardingSnapshot = completed.current",
            ),
            transition = "FP004_TRAINING -> COMPLETE",
        )

        val coordinatorUpdate = functionBlockFrom(
            coordinatorSource,
            "fun updateFirstRunSnapshot(",
        )
        assertTrue(coordinatorUpdate.contains("generation != expectedGeneration"))
        assertTrue(coordinatorUpdate.contains("restoredFirstRunSnapshot = firstRunSnapshot"))
        assertFalse(
            "publishing onboarding progress must not invalidate the device-check session binding",
            coordinatorUpdate.contains("advanceGenerationLocked("),
        )
    }

    @Test
    fun restoredProcessSnapshotCannotOverwriteANewerLocalRevisionFromTheSameEpoch() {
        val sessionChange = functionBlock("private fun onGatewayProcessSessionChanged(")
        val overwrite = "firstRunOnboardingSnapshot = firstRun"
        val beforeOverwrite = sessionChange.substringBeforeRequired(overwrite)

        assertTrue(
            "restored/local epochs must be compared before applying restored first-run state",
            beforeOverwrite.contains("firstRun.epoch") &&
                beforeOverwrite.contains("firstRunOnboardingSnapshot.epoch"),
        )
        assertTrue(
            "restored/local revisions must be compared before applying restored first-run state",
            beforeOverwrite.contains("firstRun.revision") &&
                beforeOverwrite.contains("firstRunOnboardingSnapshot.revision"),
        )
        assertTrue(
            "the revision comparison must be part of a freshness guard",
            restoredAndLocalRevisionComparison.containsMatchIn(beforeOverwrite),
        )
    }

    @Test
    fun gatewaySessionChangesAreAppliedOnMainOnlyWhenStillCurrent() {
        assertTrue(source.contains("::dispatchGatewayProcessSessionChanged"))
        val dispatch = functionBlock("private fun dispatchGatewayProcessSessionChanged(")

        assertTrue(dispatch.contains("Looper.myLooper() == Looper.getMainLooper()"))
        assertTrue(dispatch.contains("runOnUiThread(deliver)"))
        assertTrue(dispatch.contains("GatewaySessionProcessCoordinator.snapshot()"))
        assertTrue(dispatch.contains("snapshot.generation != latest.generation"))
        assertTrue(dispatch.contains("snapshot.session !== latest.session"))
        assertInOrder(
            dispatch,
            "val latest = GatewaySessionProcessCoordinator.snapshot()",
            "onGatewayProcessSessionChanged(snapshot)",
        )
    }

    @Test
    fun enteringFp004RestoresTheActorProfileBeforeReconcilingEducationConsent() {
        val stateChange = functionBlock("private fun onFirstRunOnboardingStateChanged(")
        assertInOrder(
            stateChange,
            "bindFirstRunVerifiedActorForTraining()",
            "completeFirstRunFp004TrainingIfReady(",
        )

        val binding = functionBlock("private fun bindFirstRunVerifiedActorForTraining()")
        val restoreIndex = binding.indexOf("restorePriorityUserOnboardingFromPrefs()")
        val alreadyBoundReturnIndex = binding.indexOf("reporterUserId == actorId")
        val stateChangeRestoresDirectly =
            stateChange.indexOf("restorePriorityUserOnboardingFromPrefs()") in
                0 until stateChange.indexOf("completeFirstRunFp004TrainingIfReady(")
        val bindingRestoresBeforeSkippingAlreadyBoundActor =
            restoreIndex >= 0 &&
                (alreadyBoundReturnIndex < 0 || restoreIndex < alreadyBoundReturnIndex)

        assertTrue(
            "the account-scoped profile must be restored even when the actor was already bound",
            stateChangeRestoresDirectly || bindingRestoresBeforeSkippingAlreadyBoundActor,
        )

        val completion = functionBlock("private fun completeFirstRunFp004TrainingIfReady(")
        val educationEvidence = source
            .substringAfter("private fun currentFirstRunEducationEvidenceComplete()")
            .substringBefore("private fun ")
        assertTrue(completion.contains("priorityUserOnboardingActorId != actorId"))
        assertTrue(completion.contains("firstRunOnboardingSnapshot.verifiedActorBinding?.value != actorId"))
        assertTrue(completion.contains("if (!postLoginDeviceCheckPassesFeatureGate()) return false"))
        assertTrue(completion.contains("if (!currentFirstRunEducationEvidenceComplete()) return false"))
        assertTrue(educationEvidence.contains("FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4"))
        assertTrue(educationEvidence.contains("priorityUserOnboardingPolicy.snapshot().nativeEducationComplete"))
        assertTrue(binding.contains("restored.usageConditionsAcknowledged"))
        assertTrue(binding.contains("restored.appUsageReviewed"))
        assertTrue(binding.contains("restored.appUsageAccepted"))
        assertTrue(binding.contains("preservePostLoginDeviceCheck = true"))
    }

    @Test
    fun restoredFullOrLimitedResultAdvancesOnboardingWithoutRemeasurement() {
        val bind = functionBlock("private fun bindPostLoginDeviceCheckSession(")
        val advance = functionBlock("private fun advanceEmailStagesWithRestoredDeviceCheck()")
        val revalidate = functionBlock(
            "private fun revalidateCompletedPostLoginDeviceCheckPrerequisites()",
        )
        val resume = functionBlock("private fun resumeWalkSafeRuntimeAfterPrivacyStartupInspection()")
        val sessionChange = functionBlock("private fun onGatewayProcessSessionChanged(")
        val postedRestore = sessionChange.substringAfterRequired("window.decorView.post {")

        assertTrue(bind.contains("currentPostLoginDeviceCheckResultBinding(actorId)"))
        assertTrue(bind.contains("postLoginDeviceCheckResultStore::restore"))
        assertTrue(bind.contains("state = result.state"))
        assertTrue(bind.contains("disabledFeatures = result.disabledFeatures"))
        assertTrue(bind.contains("metricDistanceCapabilityOverride ="))
        assertTrue(bind.contains("onDeviceSpeechRecognitionCapabilityOverride ="))
        assertTrue(bind.contains("offlineKoreanTextToSpeechCapabilityOverride ="))
        assertTrue(bind.contains("postLoginMetricDepthState = restored.metricDepthState"))
        assertTrue(bind.contains("else when (restored.metricDepthState)"))
        assertTrue(bind.contains("PostLoginMetricDepthState.SUPPORTED -> true"))
        assertTrue(bind.contains("PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED -> false"))
        assertTrue(bind.contains("else -> null"))
        assertTrue(bind.contains("HANDS_FREE_VOICE in restored.disabledFeatures"))
        assertTrue(bind.contains("VOICE_GUIDANCE in restored.disabledFeatures"))
        assertFalse(bind.contains("startPostLoginDeviceCheckRuntime("))
        assertFalse(bind.contains("beginPostLoginDeviceCheckFromUserAction("))
        assertTrue(revalidate.contains("currentPostLoginDeviceCheckResultBinding"))
        assertTrue(revalidate.contains("postLoginDeviceCheckResultStore::restore"))
        assertTrue(revalidate.contains("state = PostLoginDeviceCheckState.NOT_RUN"))
        val resultBinding = functionBlock(
            "private fun currentPostLoginDeviceCheckResultBinding(",
        )
        assertTrue(resultBinding.contains("actorId = actorId"))
        assertTrue(resultBinding.contains("getOrCreateInstallDeviceId()"))
        assertTrue(resultBinding.contains("installationId = installationId"))
        assertTrue(resultBinding.contains("osSdkInt = Build.VERSION.SDK_INT"))
        assertTrue(resultBinding.contains("osBuildFingerprint = Build.FINGERPRINT"))
        assertTrue(resultBinding.contains("appVersionCode = BuildConfig.VERSION_CODE.toLong()"))
        assertTrue(resultBinding.contains("POST_LOGIN_DEVICE_CHECK_PROBE_POLICY_VERSION"))
        assertTrue(resultBinding.contains("environmentProfileRevision ="))
        assertTrue(resultBinding.contains("missingRequiredPermissions ="))
        assertTrue(resultBinding.contains("offlineKoreanTextToSpeechAvailable ="))
        assertTrue(resultBinding.contains("onDeviceSpeechRecognitionAvailable ="))
        assertTrue(advance.contains("postLoginDeviceCheckSnapshot.passesFeatureGate"))
        assertTrue(advance.contains("recordEmailJitPermissionObservation("))
        assertTrue(advance.contains("recordEmailDeviceCheckPassed("))
        assertFalse(advance.contains("startPostLoginDeviceCheckRuntime("))
        assertFalse(revalidate.contains("PostLoginDeviceCheckPolicy.invalidatePassedGate("))
        assertInOrder(
            resume,
            "revalidateCompletedPostLoginDeviceCheckPrerequisites()",
            "if (advanceEmailStagesWithRestoredDeviceCheck())",
            "onFirstRunOnboardingStateChanged(",
            "preservePostLoginDeviceCheck = true",
        )
        assertInOrder(
            sessionChange,
            "bindPostLoginDeviceCheckSession(",
            "val restoredDeviceCheckBinding = postLoginDeviceCheckSnapshot.bindingOrNull",
            "postLoginDeviceCheckSnapshot.passesFeatureGate",
            "window.decorView.post {",
        )
        assertInOrder(
            postedRestore,
            "postLoginDeviceCheckPassesFeatureGate()",
            "advanceEmailStagesWithRestoredDeviceCheck()",
            "onFirstRunOnboardingStateChanged(",
            "preservePostLoginDeviceCheck = true",
        )
        assertFalse(postedRestore.contains("startPostLoginDeviceCheckRuntime("))
        val lateRestore = functionBlock(
            "private fun restoreBoundPostLoginDeviceCheckResultIfPossible()",
        )
        assertTrue(lateRestore.contains("PostLoginDeviceCheckState.NOT_RUN"))
        assertTrue(lateRestore.contains("currentPostLoginDeviceCheckResultBinding"))
        assertTrue(lateRestore.contains("bindPostLoginDeviceCheckSession("))
        assertFalse(lateRestore.contains("startPostLoginDeviceCheckRuntime("))
        val storedValidation = functionBlock(
            "private fun maybeStartStoredDeviceCheckBindingValidation()",
        )
        assertTrue(bind.contains("maybeStartStoredDeviceCheckBindingValidation()"))
        assertTrue(storedValidation.contains("hasCurrentPolicyResultCandidate()"))
        assertTrue(storedValidation.contains("PostLoginDeviceCheckState.NOT_RUN"))
        assertTrue(storedValidation.contains("currentPostLoginDeviceCheckSessionBinding()"))
        assertTrue(storedValidation.contains("startupCapabilityProbe.start()"))
        assertTrue(storedValidation.contains("storedDeviceCheckBindingValidationPending = true"))
        assertTrue(storedValidation.contains("restoreBoundPostLoginDeviceCheckResultIfPossible()"))
        assertTrue(storedValidation.contains("storedDeviceCheckBindingValidationPending = false"))
        assertFalse(storedValidation.contains("startPostLoginDeviceCheckRuntime("))
    }

    @Test
    fun mutableRuntimePrerequisitesDoNotResetARestoredFullOrLimitedGate() {
        val bindingDigest = functionBlockFrom(
            deviceCheckResultStoreSource,
            "private fun PostLoginDeviceCheckResultBinding.sha256OrNull()",
        )
        val revalidate = functionBlock(
            "private fun revalidateCompletedPostLoginDeviceCheckPrerequisites()",
        )
        val resultBinding = functionBlock(
            "private fun currentPostLoginDeviceCheckResultBinding(",
        )
        val bind = functionBlock("private fun bindPostLoginDeviceCheckSession(")
        val currentDisabled = functionBlock("private fun currentPostLoginDisabledFeatures()")
        val mutableVoiceBinding = resultBinding
            .substringAfterRequired("val offlineKoreanTextToSpeechAvailable =")
            .substringBeforeRequired("val environmentProfile =")
        val handsFreeOverride = bind
            .substringAfterRequired("onDeviceSpeechRecognitionCapabilityOverride = if (")
            .substringBeforeRequired("offlineKoreanTextToSpeechCapabilityOverride = if (")
        val voiceGuidanceOverride = bind
            .substringAfterRequired("offlineKoreanTextToSpeechCapabilityOverride = if (")
            .substringBeforeRequired("} else if (sessionBindingChanged)")

        listOf(
            "missingRequiredPermissions",
            "locationServiceEnabled",
            "voiceDisclosureAccepted",
            "offlineKoreanTextToSpeechAvailable",
            "onDeviceSpeechRecognitionAvailable",
        ).forEach { mutablePrerequisite ->
            assertFalse(
                "$mutablePrerequisite must not invalidate the persisted measurement binding",
                bindingDigest.contains(mutablePrerequisite),
            )
        }
        assertFalse(mutableVoiceBinding.contains("?: return null"))
        assertInOrder(
            revalidate,
            "restored != null",
            ") return false",
            "cancelPostLoginDeviceCheckRuntime(\"saved_result_prerequisites_changed\")",
            "state = PostLoginDeviceCheckState.NOT_RUN",
        )
        assertTrue(handsFreeOverride.contains("HANDS_FREE_VOICE in restored.disabledFeatures"))
        assertTrue(handsFreeOverride.contains("false"))
        assertTrue(handsFreeOverride.contains("else"))
        assertTrue(handsFreeOverride.contains("null"))
        assertTrue(voiceGuidanceOverride.contains("VOICE_GUIDANCE in restored.disabledFeatures"))
        assertTrue(voiceGuidanceOverride.contains("false"))
        assertTrue(voiceGuidanceOverride.contains("else"))
        assertTrue(voiceGuidanceOverride.contains("null"))
        assertTrue(
            currentDisabled.contains("onDeviceSpeechRecognitionAvailable == false"),
        )
        assertTrue(
            currentDisabled.contains("offlineKoreanTextToSpeechAvailable == false"),
        )
    }

    @Test
    fun missingStoredResultCanBeRemeasuredAfterOnboardingAlreadyAdvanced() {
        val capabilityUi = functionBlock("private fun refreshStartupCapabilityUi()")

        assertTrue(capabilityUi.contains("FirstRunOnboardingStage.FP004_TRAINING"))
        assertTrue(capabilityUi.contains("FirstRunOnboardingStage.COMPLETE"))
        assertTrue(capabilityUi.contains("!postLoginDeviceCheckSnapshot.passesFeatureGate"))
        assertTrue(capabilityUi.contains("isEnabled = showDeviceCheckAction &&"))
        assertTrue(capabilityUi.contains("!storedDeviceCheckBindingValidationPending"))
        assertTrue(capabilityUi.contains("저장된 기기 점검 결과 확인 중"))
    }

    @Test
    fun environmentPreflightTimeoutInvalidatesMeasurementsWithoutHidingDiagnostics() {
        val timeout = functionBlock("private fun scheduleOfficialEnvironmentPreflightTimeout(")
        val stop = functionBlock("private fun stopOfficialEnvironmentCameraPreflight(")

        assertInOrder(
            timeout,
            "stopOfficialEnvironmentCameraPreflight()",
            "officialEnvironmentGpsEvidence = null",
            "officialEnvironmentCameraEvidence = null",
            "OfficialEnvironmentPreflightPhase.TIMED_OUT",
        )
        assertFalse(timeout.contains("frameAvailable = null"))
        assertFalse(timeout.contains("officialEnvironmentGpsDiagnosticDetail = null"))
        assertFalse(timeout.contains("officialEnvironmentCameraDiagnosticReason = null"))
        assertTrue(stop.contains("synchronized(phoneMountingObservationLock)"))
        assertTrue(stop.contains("stopOfficialEnvironmentGpsPreflight()"))
        assertTrue(stop.contains("detectorExecutor.execute"))
        assertTrue(stop.contains("CAMERA_ANALYZER_RELEASE_BARRIER_TIMEOUT_MS"))
        assertTrue(stop.contains("awaitCameraXClosed(ownedCamera)"))
        assertTrue(stop.contains("cameraXReleaseBlocked = true"))
    }

    private fun assertCoordinatorProgressPublishReachable(
        code: String,
        transition: String,
    ) {
        assertTrue(
            "$transition must publish the accepted snapshot to the process coordinator",
            coordinatorProgressPublishReachable(code, depth = 0, visited = emptySet()),
        )
    }

    private fun coordinatorProgressPublishReachable(
        code: String,
        depth: Int,
        visited: Set<String>,
    ): Boolean {
        if (hasDirectCoordinatorProgressPublish(code)) return true
        if (depth >= 2) return false

        return localFunctionCall.findAll(code)
            .map { it.groupValues[1] }
            .filterNot(visited::contains)
            .any { functionName ->
                val block = functionBlockOrNull("private fun $functionName(") ?: return@any false
                coordinatorProgressPublishReachable(
                    code = block,
                    depth = depth + 1,
                    visited = visited + functionName,
                )
            }
    }

    private fun hasDirectCoordinatorProgressPublish(code: String): Boolean {
        if (!code.contains("firstRunOnboardingSnapshot")) return false
        return coordinatorCall.findAll(code).any { match ->
            match.groupValues[1] !in nonProgressCoordinatorMethods
        }
    }

    private fun functionBlock(signature: String): String =
        checkNotNull(functionBlockOrNull(signature)) { "missing function: $signature" }

    private fun functionBlockOrNull(signature: String): String? {
        return functionBlockOrNull(source, signature)
    }

    private fun functionBlockFrom(text: String, signature: String): String =
        checkNotNull(functionBlockOrNull(text, signature)) { "missing function: $signature" }

    private fun functionBlockOrNull(text: String, signature: String): String? {
        val start = text.indexOf(signature)
        if (start < 0) return null
        var braceDepth = 0
        var sawOpeningBrace = false
        for (index in start until text.length) {
            when (text[index]) {
                '{' -> {
                    braceDepth += 1
                    sawOpeningBrace = true
                }
                '}' -> {
                    braceDepth -= 1
                    if (sawOpeningBrace && braceDepth == 0) {
                        return text.substring(start, index + 1)
                    }
                }
            }
        }
        error("unterminated function: $signature")
    }

    private fun String.substringAfterRequired(delimiter: String): String {
        check(contains(delimiter)) { "missing delimiter: $delimiter" }
        return substringAfter(delimiter)
    }

    private fun String.substringBeforeRequired(delimiter: String): String {
        check(contains(delimiter)) { "missing delimiter: $delimiter" }
        return substringBefore(delimiter)
    }

    private fun assertInOrder(text: String, vararg snippets: String) {
        var cursor = -1
        snippets.forEach { snippet ->
            val next = text.indexOf(snippet, cursor + 1)
            assertTrue("missing or out of order: $snippet", next > cursor)
            cursor = next
        }
    }

    private companion object {
        val localFunctionCall = Regex("\\b([a-z][A-Za-z0-9_]*)\\s*\\(")
        val coordinatorCall = Regex(
            "GatewaySessionProcessCoordinator\\.([A-Za-z][A-Za-z0-9_]*)\\s*\\(",
        )
        val restoredAndLocalRevisionComparison = Regex(
            "(?:firstRun\\.revision\\s*(?:<|<=|>|>=|==)\\s*" +
                "firstRunOnboardingSnapshot\\.revision)|" +
                "(?:firstRunOnboardingSnapshot\\.revision\\s*(?:<|<=|>|>=|==)\\s*" +
                "firstRun\\.revision)",
        )
        val nonProgressCoordinatorMethods = setOf(
            "snapshot",
            "beginOperation",
            "clear",
            "markStorageBlocked",
            "publishRestoredUnverified",
            "publishVerified",
            "publishDeletionRecoveryVerified",
            "publishPendingRevocation",
        )
    }
}
