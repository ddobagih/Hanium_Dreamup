package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityFirstRunRegistrationStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    private val onboardingPanelSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/OnboardingReadinessPanel.kt",
    ).readText()
    private val presenterSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/FirstRunOnboardingPresenter.kt",
    ).readText()
    private val onboardingSource = ownerSource("session/onboarding/FirstRunOnboardingController.kt")
    private val readinessSource = ownerSource("session/readiness/WalkReadinessCoordinator.kt")
    private val producerSource = ownerSource("session/readiness/WalkReadinessProducers.kt")

    @Test
    fun progressReflectsTheActiveFlowWhileLegacyMappingKeepsTwelveStages() {
        val registration = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        val bindRegistration = MainActivityRefactorSource.member(registration, "bindRegistration")
        val registrationConstruction = MainActivityRefactorSource.property(source, "nativeRegistrationComposition")
        assertTrue(registrationConstruction.contains("NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication) { accountAccessPresenter }"))
        assertTrue(MainActivityRefactorSource.property(source, "firstRunOnboardingPresenter").contains("nativeRegistrationComposition.firstRunPresenter()"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        assertTrue(bindRegistration.contains("if (!::firstRun.isInitialized)"))
        assertTrue(bindRegistration.contains("FirstRunOnboardingPresenter(context, style, panel)"))
        assertTrue(bindRegistration.contains("firstRun.bindOnboardingPanel(panel)"))
        assertTrue(bindRegistration.contains("deviceCheck = DeviceCheckPresenter(panel, style)"))
        assertTrue(bindRegistration.contains("firstRun.publishRegistrationTargets()"))

        assertTrue(source.contains("const val FIRST_RUN_STAGE_COUNT = 12"))
        assertTrue(source.contains("const val EMAIL_FIRST_RUN_STAGE_COUNT = 7"))
        val progress = sourceSection(
            "firstRunProgressBar = LinearLayout(context).apply",
            "firstRunOnboardingStatusText = TextView(context).apply",
            onboardingPanelSource,
        )
        assertTrue(
            progress.contains(
                "repeat(props.stageCount)",
            ),
        )

        assertTrue(source.contains("stageCount = firstRunStageCount(firstRunOnboardingSnapshot)"))
        assertTrue(bindRegistration.contains("publication.publish(NativeUiPublication.Target.FIRST_RUN_PROGRESS_BAR)"))
        assertTrue(bindRegistration.contains("firstRun.publishRegistrationTargets()"))
        assertTrue(MainActivityRefactorSource.member(presenterSource, "publishRegistrationTargets").contains("publishedRegistration = onboarding"))
        assertTrue(MainActivityRefactorSource.member(presenterSource, "registrationBinding").contains("= publishedRegistration"))
        assertTrue(onboardingPanelSource.contains("val firstRunProgressSegments: List<View> get() = progressSegments"))
        assertTrue(presenterBlock("fun renderProgress(").contains("firstRunProgressSegments.forEachIndexed"))

        val mapping = functionBlock("private fun firstRunStageNumber(")
        assertInOrder(
            mapping,
            "FirstRunOnboardingStage.PURPOSE_AND_SAFETY -> 1",
            "FirstRunOnboardingStage.INTEGRATED_CONSENT -> 3",
            "FirstRunOnboardingStage.LOCAL_CREDENTIAL_PHONE_SUBMISSION -> 4",
            "FirstRunOnboardingStage.FP004_TRAINING -> 11",
            "FirstRunOnboardingStage.COMPLETE -> 12",
            "FirstRunOnboardingStage.BLOCKED_UNDER_14 -> 2",
        )

        val update = functionBlock("private fun updateFirstRunOnboardingUi()")
        assertTrue(update.contains("val stageNumber = firstRunStageNumber(snapshot)"))
        assertTrue(update.contains("val stageCount = firstRunStageCount(snapshot)"))
        assertTrue(update.contains("effects.renderProgress {"))
        assertTrue(presentationBinding().contains("firstRunOnboardingPresenter.renderProgress(facts())"))
        assertTrue(update.contains("stageNumber = stageNumber"))
        assertTrue(presenterBlock("fun renderProgress(").contains("if (index < facts.stageNumber)"))
    }

    @Test
    fun freshProcessAndLegacyReporterIdentityFailClosedUntilFirstRunCompletes() {
        val create = functionBlock("override fun onCreate(savedInstanceState: Bundle?)")
        assertInOrder(
            create,
            "firstRunOnboardingController.initializeEmailAccount(SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L))",
            "restorePriorityUserOnboardingFromPrefs()",
            "scheduleGatewaySessionRestoreAfterPrivacyStartupInspection()",
        )

        val manualReporter = functionBlock("private fun persistReporterUserFromInput()")
        assertTrue(manualReporter.contains("login=blocked manual_reporter_id_disallowed"))
        assertFalse(manualReporter.contains("reporterUserId ="))

        val legacyRestore =
            functionBlock("private fun restorePermissionSessionStateFromPrefs()")
        assertInOrder(
            legacyRestore,
            "reporterUserId = null",
            "remove(PREF_REPORTER_USER_ID_KEY).commit()",
        )
        assertFalse(legacyRestore.contains("getString(PREF_REPORTER_USER_ID_KEY"))

        val reporter = functionBlock("private fun currentReporterUserId()")
        assertInOrder(
            reporter,
            "if (!firstRunOnboardingComplete()) return null",
            "firstRunOnboardingSnapshot.reporterActorBinding?.value ?: return null",
            "return reporterUserId.takeIf",
        )
        val processSession = actorSessionChange(source)
        assertInOrder(
            processSession,
            "val restoredFirstRun = snapshot.restoredFirstRunSnapshot",
            "val effectiveFirstRun = firstRun.effectiveSessionSnapshot(restoredFirstRun)",
            "val reporterActorId = effectiveFirstRun?.reporterActorBinding?.value",
            "val verifiedEmailActorId = effectiveFirstRun?.takeIf",
            "val actorId = reporterActorId ?: verifiedEmailActorId",
            "val verifiedActorSession = session?.takeIf",
            "actorId == current.actorId",
            "current.verificationState == GatewaySessionVerificationState.VERIFIED",
            "current.isUsableFor(current.actorId)",
            "if (verifiedActorSession == null)",
            "runtime.cancelEducation()",
            "permissions.reporterActorId = null",
            "if (\n            verifiedActorSession != null",
            "effectiveFirstRun != null",
            "actorId != null",
            "actorId == verifiedActorSession.actorId",
            "if (restoredFirstRun != null)",
            "firstRun.restoreSessionSnapshot(restoredFirstRun)",
            "val appliedFirstRun = firstRun.snapshot",
            "permissions.reporterActorId = actorId",
            "appliedFirstRun.isComplete",
            "} else {\n            speech.invalidateStorage()\n            permissions.expireAuthentication()",
        )
        val expiredBranch = sourceSection(
            "        } else {\n            speech.invalidateStorage()",
            "\n        consent.reapplyActorBound()",
            processSession,
        )
        assertInOrder(expiredBranch, "speech.invalidateStorage()", "permissions.expireAuthentication()")
        assertTrue(processSession.contains("if (!privacy.callbackOwnerLive()) return"))
        val actorBinding = MainActivityRefactorSource.composition(source, "actorSessionLifecycleCoordinator")
        assertTrue(actorBinding.contains("override fun invalidateStorage() { invalidateSpeechOutputStorageBinding() }"))
        assertTrue(actorBinding.contains("override fun expireAuthentication() { permissionSessionPolicy.authenticationExpired() }"))
        assertTrue(actorBinding.contains("!privacyStartupInspectionDestroyed && accountDeletionStateMachine.activityLeaseIsCurrent(accountDeletionActivityLease)"))
        assertTrue(actorBinding.contains("firstRunSource = { firstRunOnboardingController }"))
        assertTrue(actorBinding.contains("profilesSource = { priorityUserProfileController }"))
        assertTrue(MainActivityRefactorSource.member(source, "invalidateSpeechOutputStorageBinding")
            .contains("speechOutputStorageCoordinator.invalidateBinding()"))
        val storageBinding = MainActivityRefactorSource.factory(source, "createPrivacySpeechStorage")
        assertTrue(MainActivityRefactorSource.property(source, "speechOutputStorageCoordinator").contains("= createPrivacySpeechStorage()"))
        assertTrue(storageBinding.contains("privacyAuthority.createSpeechOutputStorageCoordinator("))
        assertTrue(storageBinding.contains("permissionPolicy = { permissionSessionPolicy }, lifecycle = { walkSessionLifecycle }"))
        val privacyGraphSource = ownerSource("session/privacy/PrivacyAuthorityComposition.kt")
        val graphFactory = MainActivityRefactorSource.factory(source, "createPrivacyAuthorityComposition")
        assertTrue(MainActivityRefactorSource.property(source, "privacyAuthority").contains("= createPrivacyAuthorityComposition()"))
        assertTrue(graphFactory.contains("stateMachine = { accountDeletionStateMachine }, activityLease = { accountDeletionActivityLease }"))
        val speechFactory = MainActivityRefactorSource.member(privacyGraphSource, "createSpeechOutputStorageCoordinator")
        assertTrue(speechFactory.contains("permissionPolicy = permissionPolicy"))
        assertTrue(speechFactory.contains("lifecycle = lifecycle"))
        assertTrue(speechFactory.contains("activityLease = { activityLease() }"))
        assertTrue(speechFactory.contains("consentSession = { integratedConsentSession }"))
        assertTrue(speechFactory.contains(".also { speechOutputStorageCoordinator = it }"))
        val privacyBlock = MainActivityRefactorSource.member(privacyGraphSource, "blockPrivacyForAccountDeletionAuthority")
        assertInOrder(privacyBlock, "speechOutputStorageCoordinator.blockForAccountDeletion()", "integratedConsentSession.blockForAccountDeletion()", "reportPrivacyConsentSession.blockForAccountDeletion()", "fieldLogOrNull()?.blockForAccountDeletion()")
        val invalidate = MainActivityRefactorSource.member(ownerSource("feedback/SpeechOutputStorageCoordinator.kt"), "invalidateBinding")
        assertInOrder(invalidate, "speechOutputAdmissionToken.set(Any())",
            "speechOutputStorageAuthority.getAndSet(null)", "previous.binding.invalidate()",
            "store?.invalidate(previous.binding)")
        assertInOrder(create, "{ gatewaySessionController }, { try { it.detach() } finally { it.unregisterGatewayCapacityNetworkObserver() } },",
            ").attach(::dispatchGatewayProcessSessionChanged)")
        assertTrue(MainActivityRefactorSource.member(source, "dispatchGatewayProcessSessionChanged")
            .contains("actorSessionLifecycleCoordinator.dispatchGatewayProcessSessionChanged(snapshot)"))
        assertTrue(MainActivityRefactorSource.member(ownerSource("session/gateway/ActorSessionLifecycleCoordinator.kt"), "dispatchGatewayProcessSessionChanged")
            .contains("onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)"))
        assertTrue(
            functionBlock("private fun onAccountLogoutClicked()")
                .contains("onboarding.restartEmailAccount()"),
        )
    }

    @Test
    fun startupProbeUsesTheDeviceCheckGateAndResourceMonitoringAlsoStartsForActiveWalks() {
        val create = functionBlock("override fun onCreate(savedInstanceState: Bundle?)")
        assertInOrder(
            create,
            "startupCapabilityProbe = privacyStartupResources.acquireOnUiThread(create = { AndroidStartupCapabilityProbe(this)",
            "maybeStartFirstRunDeviceCheckProbes()",
        )
        assertFalse(create.contains("walkSessionResourceProbe.start"))
        assertFalse(create.contains("startupCapabilityProbe.start()"))

        val start = functionBlock("private fun maybeStartFirstRunDeviceCheckProbes()")
        assertInOrder(
            start,
            "if (!firstRunDeviceCheckAllowsPreflight()) return",
            "ensureWalkSessionResourceMonitoring()",
            "startupCapabilityProbe.start()",
        )
        val resourceMonitoring =
            functionBlock("private fun ensureWalkSessionResourceMonitoring()")
        assertInOrder(
            resourceMonitoring,
            "walkSessionResourceProbe.start {",
            "val resources = walkSessionResourceProbe.snapshot()",
            "logWalkSessionResourceDiagnostics(\"resource_event\", resources)",
            "observeWalkRuntimeResourceSafety(resources = resources)",
            "refreshStartupCapabilityUi()",
        )
        assertTrue(
            functionBlock("private fun activateWalkSessionRuntime()")
                .contains("services.ensureResourceMonitoring()"),
        )
        assertTrue(MainActivityRefactorSource.composition(source, "walkRuntimeActivationController")
            .contains("ensureWalkSessionResourceMonitoring()"))
        val gate = functionBlock("private fun firstRunDeviceCheckAllowsPreflight()")
        assertTrue(gate.contains("PostLoginDeviceCheckState.RUNNING"))
        assertTrue(gate.contains("PostLoginDeviceCheckPolicy.isCurrent"))
    }

    @Test
    fun trainingResetRestartsCompletedFirstRunAtFp004AndPublishesTheStateChange() {
        assertTrue(
            onboardingSource.contains(
                "@Volatile private lateinit var state: FirstRunOnboardingSnapshot",
            ),
        )
        assertTrue(MainActivityRefactorSource.property(source, "firstRunOnboardingSnapshot")
            .contains("get() = firstRunOnboardingController.snapshot"))
        assertFalse(MainActivityRefactorSource.property(source, "firstRunOnboardingSnapshot").contains("set(value)"))
        assertTrue(MainActivityRefactorSource.property(onboardingSource, "snapshot").contains("private set(value)"))
        assertTrue(MainActivityRefactorSource.member(onboardingSource, "initializeEmailAccount")
            .contains("snapshot = FirstRunOnboardingPolicy.initialEmailAccount(epoch)"))
        assertTrue(MainActivityRefactorSource.member(onboardingSource, "restartEmailAccount")
            .contains("snapshot = FirstRunOnboardingPolicy.initialEmailAccount(epoch)"))
        val reset = functionBlock("private fun resetPriorityUserTraining()")
        assertInOrder(
            reset,
            "profiles.policy.resetTraining()",
            "FirstRunOnboardingPolicy.restartFp004Training(",
            "snapshot,",
            "if (firstRunRestart.accepted)",
            "snapshot = firstRunRestart.current",
            "onFirstRunOnboardingStateChanged(",
        )

        val stateChange =
            functionBlock("private fun onFirstRunOnboardingStateChanged(")
        assertInOrder(
            stateChange,
            "if (!effects.onboardingComplete())",
            "gateway.clearSession()",
            "if (rawWalkWasActive)",
            "bindFirstRunVerifiedActorForTraining()",
        )
    }

    @Test
    fun gatewayAuthenticationCommitAndDowngradeUseExactCoordinatorOperations() {
        val login = functionBlock("private fun onGatewaySessionButtonClicked()")
        assertInOrder(
            login,
            "val operation = GatewaySessionProcessCoordinator.beginOperation() ?: return",
            "commitLoggedInGatewaySession(",
        )
        val commit = functionBlock("private fun commitLoggedInGatewaySession(")
        assertInOrder(
            commit,
            "GatewaySessionProcessCoordinator.snapshot().inFlightOperationId",
            "operation.operationId",
            "profile.priorityActorId() != session.actorId",
            "store.saveInitialIfAbsent(",
            "GatewaySessionProcessCoordinator.publishVerified(",
        )

        val clear = functionBlock("private fun clearGatewaySession(")
        assertInOrder(
            clear,
            "val operation = if (expectedProcessGeneration != null && expectedSession != null)",
            "GatewaySessionProcessCoordinator.beginGeneralSessionOperationIfCurrent(",
            "GatewaySessionProcessCoordinator.beginOperation()",
            "} ?: return null",
            "store.moveActiveToPendingRevocation(",
            "GatewaySessionProcessCoordinator.publishPendingRevocation(operation)",
            "permissionSessionPolicy.authenticationExpired()",
        )
    }

    @Test
    fun firstRunReadinessIsTheEarliestGateAndDetectorLoadingFailsClosed() {
        val readiness = functionBlock("private fun captureWalkSessionReadiness(")
        val prepareDetector = MainActivityRefactorSource.member(producerSource, "prepareDetector")
        assertInOrder(
            prepareDetector,
            "firstRunComplete() &&",
            "detector.ensureLoadedAfterCameraGate()",
        )
        assertTrue(readiness.contains("producers.features.prepareDetector(effectiveDecision, action, producers.firstRun::isComplete)"))
        assertTrue(MainActivityRefactorSource.property(source, "walkReadinessCoordinator")
            .contains("override fun ensureLoadedAfterCameraGate() { loadDetectorAfterCameraGate() }"))
        val requirements = MainActivityRefactorSource.member(
            producerSource.substringAfter("internal class WalkReadinessProducers("), "evaluate",
        )
        assertInOrder(
            requirements,
            "WalkSessionReadinessRequirement.FIRST_RUN_ONBOARDING ->",
            "WalkSessionReadinessRequirement.PRIORITY_USER_ONBOARDING ->",
            "WalkSessionReadinessRequirement.PERMISSIONS ->",
            "WalkSessionReadinessRequirement.MODEL ->",
        )
        assertTrue(producerSource.contains("first_run_onboarding_incomplete:"))

        val blockReason = functionBlock("private fun walkSessionReadinessBlockReason(")
        assertInOrder(
            blockReason,
            "if (!sources.onboardingComplete())",
            "if (!decision.mayConfirmAndStart)",
            "sources.priorityOnboarding(decision)",
            "sources.missingRequiredPermissions(action)",
        )

        val detectorLoad = functionBlock("private fun loadDetectorAfterCameraGate()")
        assertInOrder(
            detectorLoad,
            "if (!firstRunOnboardingComplete()) return",
            "loadDetectorForCurrentProcess()",
        )
        assertInOrder(
            functionBlock("private fun loadDetectorForCurrentProcess()"),
            "loadAttempted = true",
            "detector.tuningLane.execute",
            "platform.select(",
        )
        assertTrue(MainActivityRefactorSource.composition(source, "primaryDetectorLifecycleController")
            .contains("AndroidPrimaryLifecyclePlatform { applicationContext }"))
        assertTrue(MainActivityRefactorSource.member(ownerSource("runtime/vision/primary/AndroidPrimaryLifecyclePlatform.kt"), "select")
            .contains("AndroidFixedFixtureRuntimeSelector.load"))
    }

    @Test
    fun permissionsRequireCompletionExceptForDeviceCheckPreflightAndUseFirstRunLeases() {
        val policy = functionBlock("private fun firstRunPermissionRequestAllowed(")
        assertInOrder(
            policy,
            "PermissionRequestPurpose.POST_LOGIN_DEVICE_CHECK ->",
            "FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION",
            "PermissionRequestPurpose.METRIC_PREFLIGHT_CAMERA ->",
            "authority.metricPreflightAllowed()",
            "PermissionRequestPurpose.WALK_SESSION,",
            "PermissionRequestPurpose.RUNTIME_CAMERA,",
            "PermissionRequestPurpose.NAVIGATION,",
            "PermissionRequestPurpose.VOICE_COMMAND,",
            "authority.firstRunComplete()",
        )

        val request = functionBlock("private fun requestPermissionsWithLease(")
        assertInOrder(
            request,
            "if (!firstRunPermissionRequestAllowed(purpose)) return null",
            "val postLoginBinding =",
            "authority.postLoginBindingLive(it)",
            "firstRunLease = authority.firstRunLease()",
            "platform.launchRequest(permissions, requestCode)",
        )

        val current = functionBlock("private fun isPermissionRequestLeaseCurrent(")
        assertInOrder(
            current,
            "if (!authority.firstRunLeaseCurrent(lease.firstRunLease)) return false",
            "if (!firstRunPermissionRequestAllowed(lease.purpose)) return false",
            "lease.postLoginBinding?.let(authority::postLoginBindingLive)",
            "val snapshot = authority.lifecycleOrNull()!!.snapshot()",
        )

        val lease = sourceSection(
            "internal data class PermissionRequestLease<L>(",
            "/** Queries stay live",
            ownerSource("session/permission/PermissionFlowContracts.kt"),
        )
        assertTrue(lease.contains("val firstRunLease: L"))
        assertTrue(lease.contains("val postLoginBinding: PostLoginDeviceCheckBinding?"))
        val binding = MainActivityRefactorSource.composition(source, "permissionFlowController")
        assertTrue(binding.contains("PermissionFlowController<FirstRunAsyncLease>("))
        assertTrue(binding.contains("override fun firstRunLease() = currentFirstRunAsyncLease()"))
        assertTrue(binding.contains("override fun firstRunLeaseCurrent(lease: FirstRunAsyncLease) = isFirstRunAsyncLeaseCurrent(lease)"))
        assertTrue(binding.contains("firstRunDeviceCheckAllowsPreflight()"))
        assertTrue(binding.contains("postLoginDeviceCheckController.isSnapshotBindingLive(binding)"))
        assertTrue(binding.contains("requestPermissions(permissions, requestCode)"))
        assertTrue(binding.contains("platform = AndroidPermissionPlatform(this,"))
        assertTrue(MainActivityRefactorSource.member(ownerSource("session/permission/AndroidPermissionPlatform.kt"), "launchRequest")
            .contains("launch(permissions, requestCode)"))
    }

    @Test
    fun deviceCheckPreflightIsStageBoundLeaseBoundAndWorkloadIsolated() {
        val stageGate = functionBlock("private fun firstRunDeviceCheckAllowsPreflight()")
        assertInOrder(
            stageGate,
            "PostLoginDeviceCheckState.RUNNING",
            "PostLoginDeviceCheckPolicy.isCurrent",
        )

        val eligibility = functionBlock("private fun canBeginRuntimeMetricPreflight()")
        assertTrue(eligibility.contains("if (!device.firstRunAllowsPreflight) return false"))

        val begin = functionBlock("private fun beginRuntimeMetricPreflight()")
        assertInOrder(
            begin,
            "if (!canBeginRuntimeMetricPreflight()) return",
            "val owner = DeviceCheckOwner<L>(binding, device.firstRunLease())",
            "effects.requestCameraPermission()",
            "effects.startNativePreflight(attempt.generation, attempt.lifecycleGeneration)",
        )

        val current = functionBlock("private fun isRuntimeMetricPreflightAttemptCurrent(")
        assertInOrder(
            current,
            "attempt.ownerCurrent(device)",
            "device.bindingLive(binding)",
            "generation == attempt.generation",
        )
        val deviceOwner = ownerSource("runtime/metric/RuntimeMetricSessionController.kt")
            .substringAfter("private class DeviceCheckOwner").substringBefore("private class StoredRefreshOwner")
        assertTrue(deviceOwner.contains("firstRunLease?.let(device::firstRunLeaseCurrent) == true && device.firstRunAllowsPreflight"))
        val invalidation = ownerSource("runtime/metric/RuntimeMetricInvalidationController.kt")
        assertTrue(MainActivityRefactorSource.member(invalidation, "invalidate").contains("metric().invalidateEvidence()"))
        assertTrue(MainActivityRefactorSource.member(source, "pauseWalkSafeRuntime").contains("runtimeMetricInvalidation.invalidate(\"app_paused\")"))
        assertTrue(MainActivityRefactorSource.member(ownerSource("runtime/metric/RuntimeMetricSessionController.kt"), "invalidateEvidence")
            .contains("metricPreflightAttempt = metricPreflightAttempt.retired()"))
        assertTrue(MainActivityRefactorSource.composition(source, "runtimeMetricInvalidation")
            .contains("runtimeMetricStateLock, { runtimeMetricSession }, { arSessionOwner }"))
        assertTrue(
            functionBlock("private fun onFirstRunOnboardingStateChanged(")
                .contains("effects.invalidateMetric(\"first_run_stage_changed\")"),
        )

        val start = MainActivityRefactorSource.member(ownerSource("runtime/metric/RuntimeMetricPreflightCoordinator.kt"), "startRuntimeMetricPreflightSession")
        assertTrue(start.contains("native().publish(preflightSession, provider, preflightPurpose)"))
        assertTrue(start.contains("metric().publishPreflightEvaluator(evaluator, arGeneration)"))
        val preflightBinding = MainActivityRefactorSource.property(source, "runtimeMetricPreflight")
        assertTrue(preflightBinding.contains("{ runtimeMetricSession }, { arSessionOwner }"))
        assertTrue(preflightBinding.contains("ArSessionPurpose.PREFLIGHT"))
        assertTrue(MainActivityRefactorSource.member(source, "startRuntimeMetricPreflightSession").contains("runtimeMetricPreflight.startRuntimeMetricPreflightSession(preflightSession, provider, generation, lifecycleGeneration)"))
        assertTrue(MainActivityRefactorSource.member(ownerSource("runtime/metric/RuntimeMetricSessionController.kt"), "publishPreflightEvaluator")
            .contains("runtimeMetricOutputAllowed = false"))
        listOf(
            "loadDetectorAfterCameraGate(",
            "startNavigationServicesIfNeeded(",
            "startCameraFallbackSession(",
            "prepareReportCandidate(",
            "publishCurrentFrameReportState(",
            "dispatchFeedback(",
        ).forEach { forbiddenCall ->
            assertFalse(
                "device-check preflight must not call $forbiddenCall",
                start.contains(forbiddenCall),
            )
        }

        val frame = MainActivityRefactorSource.member(source, "handleRuntimeMetricPreflightFrame")
        assertTrue(frame.contains("runtimeMetricSession.observePreflightFrame(expectedArSessionGeneration, expectedWalkEpoch)"))
        assertTrue(frame.contains("val validSamples = snapshot.runtimeMetricValidSampleCount(requireFreshRaw = true)"))
        assertFalse(frame.contains("observeOfficialEnvironmentCameraFrame("))

        val failure = functionBlock("private fun handleRuntimeMetricFrameFailure(")
        assertInOrder(
            failure,
            "expectedPurpose == runtimePurpose",
            "scope.onboardingComplete",
            "effects.cameraFrameUnavailable(",
            "when (expectedPurpose)",
        )
        val metricBinding = MainActivityRefactorSource.composition(source, "runtimeMetricSession")
        assertTrue(metricBinding.contains("currentFirstRunAsyncLease()"))
        assertTrue(metricBinding.contains("isFirstRunAsyncLeaseCurrent(lease)"))
        assertTrue(metricBinding.contains("requestPermissionsWithLease(arrayOf(Manifest.permission.CAMERA), PermissionRequestPurpose.METRIC_PREFLIGHT_CAMERA)"))
        assertTrue(metricBinding.contains("observeOfficialEnvironmentCameraFrame("))
    }

    @Test
    fun deviceCheckMovesFocusToVisiblePostureOrEducationWithoutForcingHomeFocus() {
        val changed = functionBlock("private fun onFirstRunOnboardingStateChanged(")
        val focus = presenterBlock("fun focusCurrentFirstRunStage(")
        val focusBinding = functionBlock("private fun focusCurrentFirstRunStage(")
        val update = functionBlock("private fun updateFirstRunOnboardingUi()")
        assertInOrder(changed.substringAfter("bindFirstRunVerifiedActorForTraining()"),
            "effects.updateOnboardingUi()", "effects.focusStage(snapshot.stage)")
        assertTrue(MainActivityRefactorSource.composition(source, "firstRunOnboardingController")
            .contains("override fun focusStage(stage: FirstRunOnboardingStage) { this@MainActivity.focusCurrentFirstRunStage(stage) }"))
        assertTrue(focus.contains("FirstRunOnboardingStage.FP004_TRAINING ->"))
        assertTrue(focus.contains("if (facts.showPosture) priorityUserEducationProgressText"))
        assertTrue(focusBinding.contains("val showPosture = shouldShowFirstRunPhonePosture()"))
        assertTrue(focusBinding.contains("showPosture = showPosture"))
        assertTrue(focus.contains("else priorityUserOnboardingStatusText"))
        assertTrue(focus.contains("FirstRunOnboardingStage.COMPLETE -> return"))
        assertTrue(focus.contains("target.post"))
        assertTrue(focus.contains("!actions.isStageCurrent(stage)"))
        assertTrue(focus.contains("!actions.hasControlsScroll()"))
        assertTrue(focusBinding.contains("isStageCurrent = { firstRunOnboardingSnapshot.stage == it }"))
        assertTrue(focusBinding.contains("hasControlsScroll = { ::controlsScroll.isInitialized }"))
        assertTrue(focus.contains("!target.isShown"))
        assertTrue(focus.contains("target.requestRectangleOnScreen("))
        assertTrue(focus.contains("ACTION_ACCESSIBILITY_FOCUS"))
        assertTrue(update.contains("snapshot.stage == FirstRunOnboardingStage.FP004_TRAINING"))
        assertTrue(update.contains("val showWalkPreparation = snapshot.isComplete"))
    }

    @Test
    fun completionGateCoversWalkSensorsCameraLocationRouteFeedbackAndReports() {
        assertTrue(
            functionBlock("private fun isWalkSessionRuntimeActive()")
                .contains("return firstRunOnboardingComplete() &&"),
        )
        val activation = functionBlock("private fun activateWalkSessionRuntime()")
        val startAfterCameraRelease =
            functionBlock("private fun startWalkSessionRuntimeAfterCameraRelease(")
        assertInOrder(
            activation,
            "if (!admission.runtimeActive()) {",
            "effects.authorityLost(\"walk_lease_missing_before_runtime\")",
            "return false",
            "if (!effects.persistInterruptionMarker()) {",
            "camera.beginHandoff(runtimeEpoch)",
            "camera.stopEnvironmentPreflight(",
            "camera.startRuntimeAfterRelease(runtimeEpoch)",
        )
        assertInOrder(
            startAfterCameraRelease,
            "!admission.isRuntimeEpochCurrent(expectedEpoch)",
            "effects.startOrientationIfInitialized()",
            "effects.startNavigation()",
        )
        val activationBinding = MainActivityRefactorSource.composition(source, "walkRuntimeActivationController")
        listOf("isWalkSessionRuntimeActive()", "handleGatewayWalkAuthorityLost(reason)",
            "persistWalkSessionInterruptionMarker()", "beginRuntimeCameraHandoff(epoch)",
            "stopOfficialEnvironmentCameraPreflight(", "startWalkSessionRuntimeAfterCameraRelease(epoch)")
            .forEach { call -> assertTrue(activationBinding.contains(call)) }
        val cameraBinding = MainActivityRefactorSource.composition(source, "walkRuntimeCameraAdmissionController")
        assertTrue(cameraBinding.contains("earthOrientationTracker.start()"))
        assertTrue(cameraBinding.contains("startNavigationServicesIfNeeded()"))
        listOf(
            "private fun startDepthSession()",
            "private fun startCameraFallbackSession(",
        ).forEach { marker ->
            assertInOrder(
                functionBlock(marker),
                "if (!admission.firstRunComplete()) return",
                "if (!admission.runtimeActive()) return",
            )
        }
        listOf(
            "private fun currentStepTrackingCollectionAllowsWork()",
            "private fun currentFeedbackDeviceGateAllowsAlerts()",
        ).forEach { marker ->
            assertTrue(
                functionBlock(marker).contains("if (!firstRunOnboardingComplete()) return false"),
            )
        }
        assertTrue(
            functionBlock("private fun currentNavigationCollectionAllowsWork()")
                .contains("return navigationEnvironmentOutputsAllowed() && currentStepTrackingCollectionAllowsWork()"),
        )
        assertTrue(
            functionBlock("private fun currentRuntimeMetricOutputAllowsWork(")
                .contains("scope.onboardingComplete &&"),
        )
        assertTrue(
            functionBlock("private fun startLocationUpdatesIfAllowed(")
                .contains("scope.currentOwner()"),
        )
        val navigationFactory = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertTrue(navigationFactory.contains("override fun currentOwner() = currentLocationCollectionOwnerOrNull()"))
        assertTrue(MainActivityRefactorSource.property(source, "positioningSessionController").contains("by navigationSessions.positioning"))
        assertTrue(MainActivityRefactorSource.property(source, "navigationSessions").contains("= createNavigationSessions()"))
        val navigationGraph = ownerSource("navigation/session/NavigationSessionComposition.kt")
        val positioning = MainActivityRefactorSource.property(navigationGraph, "positioning")
        assertTrue(positioning.contains("val scope = positions.scope()"))
        assertTrue(positioning.contains("PositioningSessionController(run, scope, sources, mounting,"))
        assertTrue(
            functionBlock("private fun startStepTrackingIfAllowed()")
                .contains("currentStepTrackingCollectionAllowsWork()"),
        )
        assertTrue(
            functionBlock("private fun requestRoute(")
                .contains("if (!facts.navigationAllowed) return"),
        )
        val acquisitionFacts = sourceSection("facts = { object : AcquisitionActivityFacts {", "effects = { object : AcquisitionActivityEffects {", navigationFactory)
        assertTrue(acquisitionFacts.contains("override val navigationAllowed get() = currentNavigationCollectionAllowsWork()"))
        assertTrue(MainActivityRefactorSource.property(source, "routeAcquisitionController").contains("by navigationSessions.acquisition"))
        val acquisition = MainActivityRefactorSource.property(navigationGraph, "acquisition")
        assertTrue(acquisition.contains("val activityFacts = routes.facts()"))
        assertTrue(acquisition.contains("override val navigationAllowed: Boolean get() = activityFacts.navigationAllowed"))
        val explicit = functionBlock("private fun requestExplicitReport(")
        val reportAuthority = functionBlock("private fun ensureExplicitReportAuthorityPreconditions(")
        assertInOrder(
            reportAuthority,
            "if (!authority.reportPermissionsAllowWork())",
            "if (!authority.requireReporterUserId(",
            "!reportPrivacyConsentSession.isGranted()",
            "integratedConsentSession.currentConfirmationOrNull() == null",
            "currentExplicitReportConfirmationContextOrNull() == null",
        )
        assertInOrder(
            explicit,
            "if (!ensureExplicitReportAuthorityPreconditions())",
            "val context = currentExplicitReportConfirmationContextOrNull()",
            "explicitReportConfirmationPolicy.consumeIfConfirmed(",
            "if (confirmed != null) {",
            "submitFrozenExplicitReportAfterConfirmation(confirmed)",
            "return",
            "val captureRejection = explicitReportCaptureRejectionOrNull()",
            "if (captureRejection != null) {",
            "return",
            "val status = prepareExplicitReportCandidateIfCurrent(",
        )
        assertInOrder(
            functionBlock("private fun explicitReportCaptureRejectionOrNull("),
            "if (!frames.runtimeMetricAllowsWork())",
            "clearFrameState(\"runtime_metric_unavailable\")",
            "return status",
        )
        assertInOrder(
            functionBlock("private fun prepareExplicitReportCandidateIfCurrent("),
            "synchronized(frameStateLock)",
            "synchronized(runtimeMetricStateLock)",
            "if (!frames.runtimeMetricAllowsWork())",
            "prepareReportCandidate(",
        )
        val frozenSubmission = functionBlock("private fun submitFrozenExplicitReportAfterConfirmation(")
        assertInOrder(
            frozenSubmission,
            "!ensureExplicitReportAuthorityPreconditions()",
            "!authority.walkSafetyOutputsAllowed()",
            "currentExplicitReportConfirmationContextOrNull() != confirmed.context",
            "confirmed.useExactBytes { metadataUtf8, imageJpeg ->",
            "queue.enqueue(",
        )
        assertFalse(frozenSubmission.contains("prepareExplicitReportCandidateIfCurrent("))
        assertTrue(MainActivityRefactorSource.composition(source, "reportCandidateController")
            .contains("currentRuntimeMetricOutputAllowsWork("))
        assertInOrder(
            functionBlock("private fun processReportCandidate("),
            "authority.isRuntimeEpochCurrent(expectedWalkEpoch)",
            "!authority.walkSafetyOutputsAllowed()",
            "queue.enqueue(",
        )
        assertTrue(MainActivityRefactorSource.composition(source, "reportCandidateController")
            .contains("reportQueueStore.enqueue("))
        assertInOrder(
            functionBlock("private fun drainInitialExactReportQueue("),
            "val context = reportQueueDrainContext(trigger)",
            "if (!context.allRequiredBaseGatesAllowed()) break",
            "reportQueueDrainCoordinator.startNextWithResult(",
            "call.execute()",
        )
    }

    @Test
    fun firstRunStatusAndAccountContentKeepTheVisibleNativeTraversalOrder() {
        val registration = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        val bindRegistration = MainActivityRefactorSource.member(registration, "bindRegistration")
        val registrationConstruction = MainActivityRefactorSource.property(source, "nativeRegistrationComposition")
        assertTrue(registrationConstruction.contains("NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication) { accountAccessPresenter }"))
        assertTrue(MainActivityRefactorSource.property(source, "firstRunOnboardingPresenter").contains("nativeRegistrationComposition.firstRunPresenter()"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        assertTrue(bindRegistration.contains("if (!::firstRun.isInitialized)"))
        assertTrue(bindRegistration.contains("FirstRunOnboardingPresenter(context, style, panel)"))
        assertTrue(bindRegistration.contains("firstRun.bindOnboardingPanel(panel)"))
        assertTrue(bindRegistration.contains("deviceCheck = DeviceCheckPresenter(panel, style)"))
        assertTrue(bindRegistration.contains("firstRun.publishRegistrationTargets()"))

        val status = sourceSection("firstRunOnboardingStatusText = TextView(context).apply", "ViewCompat.setAccessibilityHeading(firstRunOnboardingStatusText, true)", onboardingPanelSource)
        assertTrue(status.contains("contentDescription = text"))
        assertTrue(status.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(status.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE"))
        val controls = sourceSection("firstRunOnboardingControls = LinearLayout(context).apply", "priorityUserOnboardingStatusText = TextView(context).apply", onboardingPanelSource)
        assertInOrder(controls, "addView(firstRunOnboardingStatusText)", "addView(productPurposeText)", "addView(firstRunPurposeButton)")
        assertTrue(onboardingPanelSource.contains("firstRunOnboardingControls.addView(accountContent, 2)"))
        assertTrue(source.contains("onboardingPanel.setAccountContent(accountAccessPresenter.rootTarget())"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("nativeRegistrationComposition.bindAccountInputs(accountPanel)"))
        val bindInputs = MainActivityRefactorSource.member(registration, "bindAccountInputs")
        assertInOrder(bindInputs, "accountSource().bindPanel(panel)", "firstRun.bindAccountPanel(panel)", "accountSource().publishStatusTarget()", "accountSource().publishInputTargets()")
        assertTrue(bindRegistration.contains("firstRun.publishRegistrationTargets()"))
        assertTrue(MainActivityRefactorSource.member(presenterSource, "publishRegistrationTargets").contains("publishedRegistration = onboarding"))
        assertTrue(MainActivityRefactorSource.member(presenterSource, "registrationBinding").contains("= publishedRegistration"))
        assertFalse(controls.contains("addView(firstRunAgeButtons.getValue(ageBand))"))
        val overlay = sourceSection("val overlay = contentComposer.createOverlayContainer(", "nativeRootOverlay = overlay")
        assertInOrder(overlay, "addView(onboardingPanel.firstRunOnboardingControls)",
            "addView(firstRunOnboardingPresenter.readinessBinding().walkReadinessControls)",
            "addView(nativeScreenPresenter.searchBinding().runtimeControls)")
        val readiness = sourceSection("walkReadinessControls = LinearLayout(context).apply", "fun setAccountContent(", onboardingPanelSource)
        assertInOrder(readiness, "addView(priorityUserOnboardingControls)", "addView(startupCapabilityText)", "addView(nativeDeviceCheckPanel)", "addView(startupMetricPreflightButton)", "addView(startupCapabilityConfirmButton)")
        val traversal = presenterBlock("fun linkFirstRunAccessibilityTraversal(")
        assertTrue(functionBlock("private fun linkFirstRunAccessibilityTraversal()").contains(
            "firstRunOnboardingPresenter.linkFirstRunAccessibilityTraversal(SIGNUP_DOCUMENT_VERSIONS.keys)",
        ))
        assertInOrder(traversal, "add(firstRunOnboardingStatusText)", "add(accountAccessStatusText)", "add(accountEmailInput)", "add(accountPasswordInput)", "add(accountConsentClauseTexts.getValue(key))", "add(accountConsentListenButtons.getValue(key))", "current.accessibilityTraversalAfter = previous.id")
        assertTrue(traversal.contains("previous.nextFocusForwardId = current.id"))
        assertTrue(traversal.contains("previous.nextFocusDownId = current.id"))
        assertTrue(traversal.contains("current.nextFocusUpId = previous.id"))
        val update = presenterBlock("fun renderStage(")
        assertInOrder(update, "firstRunOnboardingStatusText.text = message", "firstRunOnboardingStatusText.contentDescription = message")
    }

    @Test
    fun firstRunPresenterKeepsExpansionStateAndPolicyEvaluationBetweenExistingUiCallbacks() {
        val registration = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        val bindRegistration = MainActivityRefactorSource.member(registration, "bindRegistration")
        val registrationConstruction = MainActivityRefactorSource.property(source, "nativeRegistrationComposition")
        assertTrue(registrationConstruction.contains("NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication) { accountAccessPresenter }"))
        assertTrue(MainActivityRefactorSource.property(source, "firstRunOnboardingPresenter").contains("nativeRegistrationComposition.firstRunPresenter()"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        assertTrue(bindRegistration.contains("if (!::firstRun.isInitialized)"))
        assertTrue(bindRegistration.contains("FirstRunOnboardingPresenter(context, style, panel)"))
        assertTrue(bindRegistration.contains("firstRun.bindOnboardingPanel(panel)"))
        assertTrue(bindRegistration.contains("deviceCheck = DeviceCheckPresenter(panel, style)"))
        assertTrue(bindRegistration.contains("firstRun.publishRegistrationTargets()"))

        val content = functionBlock("private fun buildContentView()")
        val update = functionBlock("private fun updateFirstRunOnboardingUi()")
        assertInOrder(bindRegistration, "FirstRunOnboardingPresenter(context, style, panel)",
            "publication.publish(NativeUiPublication.Target.FIRST_RUN_ONBOARDING_STATUS_TEXT)")
        assertTrue(bindRegistration.contains("firstRun.publishRegistrationTargets()"))
        assertTrue(MainActivityRefactorSource.member(presenterSource, "publishRegistrationTargets").contains("publishedRegistration = onboarding"))
        assertTrue(MainActivityRefactorSource.member(presenterSource, "registrationBinding").contains("= publishedRegistration"))
        assertTrue(content.contains("nativeRegistrationComposition.bindAccountInputs(accountPanel)"))
        assertTrue(MainActivityRefactorSource.member(registration, "bindAccountInputs").contains("firstRun.bindAccountPanel(panel)"))
        assertTrue(content.contains("nativeRegistrationComposition.bindLegacyLogin("))
        assertTrue(MainActivityRefactorSource.member(registration, "bindLegacyLogin").contains("firstRun.bindLegacyLoginViews(views)"))
        assertTrue(content.contains("firstRunOnboardingPresenter.toggleNotice("))
        assertTrue(content.contains("firstRunOnboardingPresenter.toggleDisclosure(::updateFirstRunOnboardingUi)"))
        assertFalse(source.contains("private var firstRunNoticeExpandedByUser"))
        assertFalse(source.contains("private var firstRunDisclosureExpanded"))
        assertTrue(presenterSource.contains("private var firstRunNoticeExpandedByUser = false"))
        assertTrue(bindRegistration.contains("if (!::firstRun.isInitialized)"))
        assertTrue(MainActivityRefactorSource.member(registration, "registrationInitialized").contains("::firstRun.isInitialized"))
        assertTrue(MainActivityRefactorSource.member(source, "updateFirstRunWaitingDots").contains("nativeRegistrationComposition.registrationInitialized()"))
        assertInOrder(content, "nativeRegistrationComposition.bindAccountInputs(accountPanel)", "stopAccountConsentSpeech()", "resetAccountConsentSpeechContextAfterBinding()", "nativeRegistrationComposition.publishAccountConsentAndRoot()", "emailEnrollmentPartial?.selections?.let(::applyAccountConsentSelections)", "onboardingPanel.setAccountContent(accountAccessPresenter.rootTarget())", "nativeRegistrationComposition.publishEducationAndReadiness()")
        assertTrue(MainActivityRefactorSource.member(registration, "publishAccountConsentAndRoot").contains("accountSource().publishRootTarget()"))
        assertTrue(presenterSource.contains("private var firstRunDisclosureExpanded = false"))
        assertInOrder(presenterBlock("fun toggleDisclosure("),
            "firstRunDisclosureExpanded = !firstRunDisclosureExpanded", "refresh()", "if (firstRunDisclosureExpanded)",
            "firstRunIntegratedConsentDisclosureText.post")
        assertInOrder(update, "effects.renderStage {", "effects.updateAccount(snapshot)",
            "effects.renderConsentStage {", "effects.updateConsent()",
            "effects.renderWaiting {", "val mayTrain", "val mayUseWalk = facts.complete()",
            "effects.renderPreparation {", "effects.updateReadiness()",
            "effects.renderRuntime {", "effects.updateFeatureAvailability()",
            "effects.refreshHome()", "effects.refreshNotice()", "effects.renderProgress {",
            "effects.updatePrivacyVisibility()", "effects.renderMain()")
        assertTrue(update.contains("disclosureTextReady = facts.published(NativeUiPublication.Target.FIRST_RUN_INTEGRATED_CONSENT_DISCLOSURE_TEXT)"))
        assertTrue(update.contains("readinessControlsReady = facts.published(NativeUiPublication.Target.WALK_READINESS_CONTROLS)"))
        val binding = presentationBinding()
        listOf("renderStage", "renderConsentStage", "renderWaiting", "renderPreparation", "renderRuntime", "renderProgress")
            .forEach { method -> assertTrue(binding.contains("firstRunOnboardingPresenter.$method(facts())")) }
        listOf("updateEmailAccountAccessUi(snapshot)", "updateIntegratedConsentUi()", "updateWalkReadinessSection()",
            "updateWalkFeatureAvailabilityUi()", "refreshHomeCards()", "refreshFirstRunNoticeUi()",
            "updatePrivacySectionVisibility()", "renderMainUi()")
            .forEach { call -> assertTrue(binding.contains(call)) }
        assertFalse(presenterSource.contains("FirstRunOnboardingSnapshot"))
        assertFalse(presenterSource.contains("priorityUserOnboardingPolicy"))
        assertFalse(presenterSource.contains("permissionRecoveryGate"))
    }

    private fun presenterBlock(signature: String): String =
        ReportStaticSourceInspector.functionBlock(presenterSource, signature.substringBefore('('))

    private fun ownerSource(path: String): String =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText()

    private fun presentationBinding(): String =
        MainActivityRefactorSource.composition(source, "firstRunPresentationCoordinator")

    private fun assertRegistrationPublication() {
        val content = MainActivityRefactorSource.member(source, "buildContentView")
        assertTrue(content.contains("FirstRunOnboardingPresenter(this, viewStyle, onboardingPanel)"))
        assertTrue(content.contains("firstRunOnboardingPresenter.bindOnboardingPanel(onboardingPanel)"))
        assertTrue(content.contains("firstRunOnboardingPresenter.publishRegistrationTargets()"))
        assertTrue(MainActivityRefactorSource.member(presenterSource, "publishRegistrationTargets")
            .contains("publishedRegistration = onboarding"))
        assertTrue(MainActivityRefactorSource.member(presenterSource, "registrationBinding")
            .contains("= publishedRegistration"))
    }

    private fun sourceSection(startMarker: String, endMarker: String, ownerSource: String = source): String {
        val start = ownerSource.indexOf(startMarker)
        assertTrue("missing source marker: $startMarker", start >= 0)
        val end = ownerSource.indexOf(endMarker, start + startMarker.length)
        assertTrue("missing source marker: $endMarker", end > start)
        return ownerSource.substring(start, end)
    }

    private fun functionBlock(marker: String): String {
        if (marker.contains("captureWalkSessionReadiness(")) {
            assertTrue(MainActivityRefactorSource.member(source, "captureWalkSessionReadiness")
                .contains("walkReadinessCoordinator.capture(decision, action, recordResourceDiagnostics, checkEntryPermissions)"))
            assertTrue(MainActivityRefactorSource.property(source, "walkReadinessCoordinator").contains("WalkReadinessCoordinator("))
            return MainActivityRefactorSource.member(readinessSource, "capture")
        }
        return MainActivityRefactorSource.function(source, marker)
    }

    private fun assertInOrder(section: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val current = section.indexOf(token, previous + 1)
            assertTrue("missing or out-of-order token: $token", current > previous)
            previous = current
        }
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
