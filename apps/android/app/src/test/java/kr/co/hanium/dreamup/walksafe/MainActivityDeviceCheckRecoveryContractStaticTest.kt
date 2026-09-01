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
    fun enteringFp004RestoresTheActorProfileBeforeReconcilingCompletedTraining() {
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
        assertTrue(completion.contains("priorityUserOnboardingActorId != actorId"))
        assertTrue(completion.contains("priorityUserOnboardingPolicy.snapshot().trainingComplete"))
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

        assertTrue(bind.contains("postLoginDeviceCheckResultStore.restore()"))
        assertTrue(bind.contains("state = result.state"))
        assertTrue(bind.contains("disabledFeatures = result.disabledFeatures"))
        assertTrue(bind.contains("metricDistanceCapabilityOverride ="))
        assertTrue(bind.contains("onDeviceSpeechRecognitionCapabilityOverride ="))
        assertTrue(bind.contains("offlineKoreanTextToSpeechCapabilityOverride ="))
        assertTrue(bind.contains("METRIC_DISTANCE_GUIDANCE !in"))
        assertTrue(bind.contains("HANDS_FREE_VOICE !in"))
        assertTrue(bind.contains("VOICE_GUIDANCE !in"))
        assertFalse(bind.contains("startPostLoginDeviceCheckRuntime("))
        assertFalse(bind.contains("beginPostLoginDeviceCheckFromUserAction("))
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
    }

    @Test
    fun missingStoredResultCanBeRemeasuredAfterOnboardingAlreadyAdvanced() {
        val capabilityUi = functionBlock("private fun refreshStartupCapabilityUi()")

        assertTrue(capabilityUi.contains("FirstRunOnboardingStage.FP004_TRAINING"))
        assertTrue(capabilityUi.contains("FirstRunOnboardingStage.COMPLETE"))
        assertTrue(capabilityUi.contains("!postLoginDeviceCheckSnapshot.passesFeatureGate"))
        assertTrue(capabilityUi.contains("isEnabled = showDeviceCheckAction &&"))
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
