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
        val deviceEffects = functionBlock("private fun completePostLoginDeviceCheckPass(")
        assertTrue(deviceEffects.contains("firstRun.completeEmailDeviceCheck("))
        assertTrue(deviceEffects.contains("gateway.publishFirstRun(gateway.snapshot().generation, actorId, completedSnapshot)"))
        val deviceCheck = onboardingOwner("completeEmailDeviceCheck")
        assertInOrder(
            deviceCheck,
            "if (!transition.accepted) return kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckPassContinuation.TRANSITION_REJECTED",
            "!publish(actor, transition.current)",
            "(snapshot !== before && snapshot != transition.current)",
            "snapshot = transition.current",
        )
        assertCoordinatorProgressPublishReachable(
            code = deviceCheck.substringAfterRequired(
                "snapshot = transition.current",
            ),
            transition = "DEVICE_CHECK -> FP004_TRAINING",
        )

        val training = functionBlock("private fun completeFirstRunFp004TrainingIfReady(")
        assertInOrder(
            training,
            "if (!completed.accepted) return false",
            "gateway.publishCompletedSnapshot(actorId, snapshot = completed.current)",
            "snapshot = completed.current",
        )
        assertCoordinatorProgressPublishReachable(
            code = training.substringAfterRequired(
                "snapshot = completed.current",
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
        val sessionChange = actorSessionChange(source)
        assertInOrder(sessionChange, "firstRun.effectiveSessionSnapshot(restoredFirstRun)",
            "firstRun.restoreSessionSnapshot(restoredFirstRun)", "val appliedFirstRun = firstRun.snapshot")
        val freshness = onboardingOwner("effectiveSessionSnapshot")
        assertInOrder(freshness, "val sameActor =", "restored.flow == current.flow",
            "restored.verifiedActorBinding == current.verifiedActorBinding", "restored.epoch < current.epoch")
        assertTrue(freshness.contains("restored.epoch == current.epoch && restored.revision < current.revision"))
        assertTrue(freshness.contains("restored.revision == current.revision && restored != current"))
        assertTrue(freshness.contains("current else restored"))
        val restore = onboardingOwner("restoreSessionSnapshot")
        assertInOrder(restore, "snapshot !== expected && snapshot != restored", "return false",
            "effectiveSessionSnapshot(restored) !== restored", "return true", "snapshot = restored")
        assertFalse(sessionChange.contains("firstRun.snapshot ="))
    }

    @Test
    fun gatewaySessionChangesAreAppliedOnMainOnlyWhenStillCurrent() {
        assertTrue(source.contains("::dispatchGatewayProcessSessionChanged"))
        val dispatch = functionBlock("private fun dispatchGatewayProcessSessionChanged(")

        val binding = MainActivityRefactorSource.composition(source, "actorSessionLifecycleCoordinator")
        assertTrue(binding.contains("Looper.myLooper() == Looper.getMainLooper()"))
        assertTrue(dispatch.contains("runtime.dispatchObserverInlineIfMain(deliver)"))
        assertTrue(binding.contains("else this@MainActivity.runOnUiThread(action)"))
        assertTrue(dispatch.contains("gateway.snapshot()"))
        assertTrue(dispatch.contains("snapshot.generation != latest.generation"))
        assertTrue(dispatch.contains("snapshot.session !== latest.session"))
        assertTrue(dispatch.contains("snapshot.restoredFirstRunSnapshot != latest.restoredFirstRunSnapshot"))
        assertTrue(dispatch.contains("snapshot.storageBlocked != latest.storageBlocked"))
        assertTrue(dispatch.contains("snapshot.deletionRecoveryOnly != latest.deletionRecoveryOnly"))
        assertTrue(binding.contains("accountDeletionStateMachine.activityLeaseIsCurrent("))
        assertTrue(binding.contains("integratedConsentController.fenceGatewayAuthorityChange(snapshot)"))
        val fence = owner("session/consent/IntegratedConsentController.kt", "fenceGatewayAuthorityChange")
        assertTrue(fence.contains("synchronized(integratedConsentLock)"))
        assertTrue(fence.contains("integratedConsentSession.invalidateBackendAuthority()"))
        assertInOrder(
            dispatch,
            "val current = gateway.snapshot()",
            "snapshot.generation == current.generation",
            "snapshot.session === current.session",
            "consentAuthorityChanged = consent.fenceAuthorityChanged(snapshot)",
            "if (consentAuthorityChanged)",
            "val deliver = Runnable {",
            "val latest = gateway.snapshot()",
            "snapshot.generation != latest.generation",
            "snapshot.session !== latest.session",
            "onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)",
        )
        val applied = actorSessionChange(source)
        assertInOrder(applied, "consentAuthorityChanged &&", "consent.refreshFromServer()")
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
        val alreadyBoundReturnIndex = binding.indexOf("effects.reporterActorId() == actorId")
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
        assertTrue(completion.contains("profiles.actorId != actorId"))
        assertTrue(completion.contains("snapshot.verifiedActorBinding?.value != actorId"))
        assertTrue(completion.contains("if (!effects.deviceGatePassed()) return false"))
        assertTrue(completion.contains("if (!education.evidenceComplete()) return false"))
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
        val sessionChange = actorSessionChange(source)
        val postedRestore = sessionChange.substringAfterRequired("runtime.postDecor(Runnable {")

        assertTrue(bind.contains("facts.resultBinding(actorId)"))
        assertTrue(bind.contains("savedResults::restore"))
        assertTrue(bind.contains("PostLoginDeviceCheckSnapshot(it.state, actorId, sessionGeneration,"))
        assertTrue(bind.contains("disabledFeatures = it.disabledFeatures"))
        assertTrue(bind.contains("metricDistance = if (restored.cameraDependentChecksDeferred)"))
        assertTrue(MainActivityRefactorSource.member(source, "createPostLoginDeviceCheckController").contains("onDeviceSpeechRecognitionCapabilityOverride = null"))
        assertTrue(bind.contains("koreanSpeech = if ("))
        assertTrue(bind.contains("probes.restoreMetricDepth(restored.metricDepthState)"))
        assertTrue(bind.contains("else when (restored.metricDepthState)"))
        assertTrue(bind.contains("PostLoginMetricDepthState.SUPPORTED -> true"))
        assertTrue(bind.contains("PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED -> false"))
        assertTrue(bind.contains("else -> null"))
        // S14-16-b: a stored wake-word test miss (HANDS_FREE_VOICE) is no longer an STT override.
        assertFalse(bind.contains("HANDS_FREE_VOICE in restored.disabledFeatures"))
        assertTrue(bind.contains("VOICE_GUIDANCE in restored.disabledFeatures"))
        assertFalse(bind.contains("startRuntime("))
        assertFalse(bind.contains("beginFromUserAction("))
        assertTrue(revalidate.contains("facts::resultBinding"))
        assertTrue(revalidate.contains("savedResults::restore"))
        assertTrue(revalidate.contains("state = PostLoginDeviceCheckState.NOT_RUN"))
        val resultBinding = functionBlock(
            "private fun currentPostLoginDeviceCheckResultBinding(",
        )
        assertTrue(resultBinding.contains("actorId = actorId"))
        assertTrue(MainActivityRefactorSource.property(source, "postLoginDeviceCheckBindingFactory").contains("getOrCreateInstallDeviceId()"))
        assertTrue(resultBinding.contains("installationId = facts.installationId"))
        assertTrue(resultBinding.contains("osSdkInt = Build.VERSION.SDK_INT"))
        assertTrue(resultBinding.contains("osBuildFingerprint = Build.FINGERPRINT"))
        assertTrue(resultBinding.contains("appVersionCode = BuildConfig.VERSION_CODE.toLong()"))
        assertTrue(resultBinding.contains("POST_LOGIN_DEVICE_CHECK_PROBE_POLICY_VERSION"))
        assertTrue(resultBinding.contains("environmentProfileRevision ="))
        assertTrue(resultBinding.contains("missingRequiredPermissions ="))
        assertTrue(resultBinding.contains("offlineKoreanTextToSpeechAvailable ="))
        assertTrue(resultBinding.contains("onDeviceSpeechRecognitionAvailable ="))
        assertTrue(advance.contains("!effects.deviceGatePassed()"))
        val featureGate = functionBlock("private fun postLoginDeviceCheckPassesFeatureGate()")
        listOf(
            "!actor.foreground",
            "!current.passesFeatureGate",
            "binding.actorId == actorId",
            "binding.sessionGeneration == generation",
            "actor.sessionVerified",
            "actor.sessionUsable",
            "!actor.storageBlocked",
            "!actor.deletionRecoveryOnly",
        ).forEach { guard -> assertTrue("Missing restored device-check gate: $guard", featureGate.contains(guard)) }
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
            "device.bindSession(",
            "val restoredDeviceCheckBinding = device.snapshot().bindingOrNull",
            "device.snapshot().passesFeatureGate",
            "runtime.postDecor(Runnable {",
        )
        assertInOrder(
            postedRestore,
            "device.passesGate()",
            "firstRun.advanceEmailStagesWithRestoredDeviceCheck()",
            "onFirstRunOnboardingStateChanged(",
            "preservePostLoginDeviceCheck = true",
        )
        assertFalse(postedRestore.contains("startPostLoginDeviceCheckRuntime("))
        val lateRestore = functionBlock(
            "private fun restoreBoundPostLoginDeviceCheckResultIfPossible()",
        )
        assertTrue(lateRestore.contains("PostLoginDeviceCheckState.NOT_RUN"))
        assertTrue(lateRestore.contains("facts.resultBinding"))
        assertTrue(lateRestore.contains("bindSession("))
        assertFalse(lateRestore.contains("startRuntime("))
        val storedValidation = functionBlock(
            "private fun maybeStartStoredDeviceCheckBindingValidation()",
        )
        assertTrue(bind.contains("maybeValidateStoredBinding()"))
        assertTrue(storedValidation.contains("savedResults.candidatePresent()"))
        assertTrue(storedValidation.contains("PostLoginDeviceCheckState.NOT_RUN"))
        assertTrue(storedValidation.contains("!current.passesFeatureGate"))
        assertTrue(storedValidation.contains("currentSessionBinding()"))
        assertTrue(MainActivityRefactorSource.property(source, "postLoginDeviceProbesHolder").contains("startupCapabilityProbe.start()"))
        assertTrue(owner("device/postlogin/AndroidPostLoginDeviceProbes.kt", "startStoredBindingValidation").contains("storedBindingValidationPending = true"))
        assertTrue(storedValidation.contains("restoreBoundResultIfPossible()"))
        assertTrue(owner("device/postlogin/AndroidPostLoginDeviceProbes.kt", "clearStoredBindingValidation").contains("storedBindingValidationPending = false"))
        assertFalse(storedValidation.contains("startRuntime("))
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
        val factsWire = MainActivityRefactorSource.composition(source, "postLoginDeviceInteraction")
        assertTrue(factsWire.contains("override fun startupSnapshot() = startupCapabilityProbe.snapshot()"))
        assertTrue(factsWire.contains("override val probeInitialized get() = ::startupCapabilityProbe.isInitialized"))
        assertTrue(currentDisabled.contains("facts.probeInitialized"))
        val mutableVoiceBinding = resultBinding.substringAfterRequired("missingRequiredPermissions =")
        val handsFreeOverride = ReportStaticSourceInspector.functionBlock(MainActivityRefactorSource.member(source, "createPostLoginDeviceCheckController"), "override fun applyCapabilityOverrides")
        val voiceGuidanceOverride = bind.substringAfterRequired("koreanSpeech = if (").substringBeforeRequired("} else if (next != previous)")

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
            "cancel(\"saved_result_prerequisites_changed\")",
            "state = PostLoginDeviceCheckState.NOT_RUN",
        )
        // S14-16-b: the restored STT override is cleared, never derived from HANDS_FREE_VOICE.
        assertTrue(handsFreeOverride.contains("onDeviceSpeechRecognitionCapabilityOverride = null"))
        assertFalse(handsFreeOverride.contains("HANDS_FREE_VOICE"))
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
        val capabilityUi = presenter("startupPresentation")
        assertTrue(MainActivityRefactorSource.composition(source, "startupCapabilityFactsRenderer").contains("override fun storedBindingValidationPending() = storedDeviceCheckBindingValidationPending"))

        assertTrue(capabilityUi.contains("FirstRunOnboardingStage.FP004_TRAINING"))
        assertTrue(capabilityUi.contains("FirstRunOnboardingStage.COMPLETE"))
        assertTrue(capabilityUi.contains("!check.passesFeatureGate"))
        assertTrue(capabilityUi.contains("enabled = showDeviceCheckAction &&"))
        assertTrue(capabilityUi.contains("!check.storedBindingValidationPending"))
        assertTrue(capabilityUi.contains("저장된 기기 점검 결과 확인 중"))
    }

    @Test
    fun environmentPreflightTimeoutInvalidatesMeasurementsWithoutHidingDiagnostics() {
        val timeout = owner("device/environment/OfficialEnvironmentPreflightController.kt", "scheduleCameraTimeout")
        assertTrue(MainActivityRefactorSource.member(source, "stopOfficialEnvironmentCameraPreflight")
            .contains("officialEnvironmentPreflightController.stopCamera(releaseMountingSensor, onReleased)"))
        MainActivityRefactorSource.composition(source, "officialEnvironmentPreflightController")
        val stop = owner("device/environment/OfficialEnvironmentPreflightController.kt", "stopCamera")

        assertInOrder(
            timeout,
            "stopCamera()",
            "evidence.clearGpsEvidenceOnly()",
            "evidence.clearCameraEvidence()",
            "OfficialEnvironmentPreflightPhase.TIMED_OUT",
        )
        assertFalse(timeout.contains("frameAvailable = null"))
        assertFalse(timeout.contains("evidence.clearGpsEvidence()"))
        assertFalse(timeout.contains("evidence.clearCameraDiagnostics()"))
        assertTrue(stop.contains("synchronized(evidence.observationLock)"))
        assertTrue(stop.contains("stopGps()"))
        assertTrue(owner("device/environment/AndroidOfficialEnvironmentPreflightSources.kt", "awaitAnalyzerBarrier").contains("detectorExecutor.execute"))
        val start = owner("device/environment/OfficialEnvironmentPreflightController.kt", "startCamera")
        assertTrue(start.contains("scheduleCameraTimeout(epoch, generation, config.maximumCameraDurationMs)"))
        assertTrue(timeout.contains("timeout !== action"))
        assertTrue(timeout.contains("isCameraCurrent(epoch, generation)"))
        val sdkWire = MainActivityRefactorSource.property(source, "officialEnvironmentCameraSource")
        assertTrue(sdkWire.contains("by lazy(LazyThreadSafetyMode.NONE)"))
        assertTrue(sdkWire.contains("detectorExecutor = primaryDetectorOwner.nativeLane"))
        assertTrue(sdkWire.contains("mainExecutor = { ContextCompat.getMainExecutor(this) }"))
        assertTrue(sdkWire.contains("fallbackController = { cameraFallbackController }"))
        assertTrue(stop.contains("config.analyzerReleaseBarrierTimeoutMs"))
        assertTrue(MainActivityRefactorSource.composition(source, "officialEnvironmentPreflightController").contains("CAMERA_ANALYZER_RELEASE_BARRIER_TIMEOUT_MS))"))
        assertTrue(stop.contains("camera.awaitClosed(resources)"))
        assertTrue(owner("device/environment/AndroidOfficialEnvironmentPreflightSources.kt", "awaitClosed").contains("slot.awaitClosed(resources, completion)"))
        assertTrue(stop.contains("camera.markSharedReleaseBlocked()"))
    }

    private fun owner(path: String, name: String): String = MainActivityRefactorSource.member(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText().let { text ->
            when (path) {
                "device/postlogin/AndroidPostLoginDeviceProbes.kt" -> text.substringAfter("internal class AndroidPostLoginDeviceProbes(")
                "device/environment/AndroidOfficialEnvironmentPreflightSources.kt" -> text.substringAfter("internal class AndroidOfficialEnvironmentCameraSource(")
                else -> text
            }
        }, name,
    )
    private fun device(name: String): String {
        assertTrue(MainActivityRefactorSource.member(source, "createPostLoginDeviceCheckController").contains("PostLoginDeviceCheckController("))
        return MainActivityRefactorSource.member(MainActivityRefactorSource.device, name)
    }
    private fun presenter(name: String) = ReportStaticSourceInspector.functionBlock(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DeviceCheckPresenter.kt").readText(), "fun $name",
    )

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
        if (code.contains("gateway.publishStageSnapshot(firstRunActorId) { snapshot }")) {
            val binding = MainActivityRefactorSource.composition(source, "firstRunOnboardingController")
            return binding.contains("override fun publishStageSnapshot(") &&
                binding.contains("GatewaySessionProcessCoordinator.updateFirstRunSnapshot(")
        }
        if (!code.contains("firstRunOnboardingSnapshot")) return false
        return coordinatorCall.findAll(code).any { match ->
            match.groupValues[1] !in nonProgressCoordinatorMethods
        }
    }

    private fun functionBlock(signature: String): String =
        when {
            signature.contains("completePostLoginDeviceCheckPass(") -> MainActivityRefactorSource.function(source, "private fun completePostLoginDeviceCheckPassEffects(")
            signature.contains("currentPostLoginDeviceCheckResultBinding(") -> {
                assertTrue(MainActivityRefactorSource.member(source, "currentPostLoginDeviceCheckResultBinding").contains("postLoginDeviceCheckBindingFactory.create(actorId)"))
                owner("device/AndroidPostLoginDeviceCheckBindingFactory.kt", "create")
            }
            signature.contains("restoreBoundPostLoginDeviceCheckResultIfPossible(") -> device("restoreBoundResultIfPossible")
            signature.contains("maybeStartStoredDeviceCheckBindingValidation(") -> device("maybeValidateStoredBinding")
            else -> MainActivityRefactorSource.function(source, signature)
        }

    private fun functionBlockOrNull(signature: String): String? {
        return runCatching { functionBlock(signature) }.getOrNull()
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

    private fun actorSessionChange(mainSource: String): String {
        MainActivityRefactorSource.composition(mainSource, "actorSessionLifecycleCoordinator")
        val main = MainActivityRefactorSource.factory(mainSource, "onGatewayProcessSessionChanged")
        // Whole declaration comparison preserves guards, argument forwarding and effect order.
        val expectedMain = """
private fun onGatewayProcessSessionChanged(
        snapshot: GatewaySessionProcessSnapshot,
        consentAuthorityChanged: Boolean,
    ) {
        actorSessionLifecycleCoordinator.onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)
        refreshGuidanceDiagnosticAuthority()
    }
        """.trimIndent()
        org.junit.Assert.assertEquals("Main gateway callback must invoke the owner before diagnostic refresh", expectedMain.filterNot(Char::isWhitespace),
            main.filterNot(Char::isWhitespace))
        val call = main.indexOf("actorSessionLifecycleCoordinator.onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)")
        org.junit.Assert.assertTrue(call >= 0)
        org.junit.Assert.assertTrue(main.indexOf("refreshGuidanceDiagnosticAuthority()") > call)
        return MainActivityRefactorSource.member(java.io.File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/session/gateway/ActorSessionLifecycleCoordinator.kt",
        ).readText(), "onGatewayProcessSessionChanged")
    }


    private fun onboardingOwner(name: String): String {
        MainActivityRefactorSource.composition(source, "firstRunOnboardingController")
        return MainActivityRefactorSource.member(java.io.File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/session/onboarding/FirstRunOnboardingController.kt",
        ).readText(), name)
    }


    @Test
    fun actorSessionChangeRejectsNonExecutingOrReorderedCallers() {
        val mainSource = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        actorSessionChange(mainSource)
        val caller = MainActivityRefactorSource.factory(mainSource, "onGatewayProcessSessionChanged")
        val call = "actorSessionLifecycleCoordinator.onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)"
        org.junit.Assert.assertTrue(caller.contains(call))
        val variants = linkedMapOf(
            "conditional" to caller.replace(call, "if (false) { $call }"),
            "uninvoked lambda" to caller.replace(call, "{ $call }"),
            "comment" to caller.replace(call, "/* $call */"),
            "additional effect" to caller.replace(call, "$call\nunexpectedEffect()"),
            "omitted" to caller.replace(call, ""),
            "early return" to caller.replace(call, "return\n$call"),
        )

        variants["reordered effects"] = caller.replace(call, "").replace(
            "refreshGuidanceDiagnosticAuthority()", "refreshGuidanceDiagnosticAuthority()\n" + call,
        )

        val accepted = variants.filter { (_, changed) ->
            val mainSource = mainSource.replace(caller, changed)
            runCatching { actorSessionChange(mainSource) }.isSuccess
        }.keys
        org.junit.Assert.assertTrue("Non-equivalent Main callers accepted: $accepted", accepted.isEmpty())
    }

}
