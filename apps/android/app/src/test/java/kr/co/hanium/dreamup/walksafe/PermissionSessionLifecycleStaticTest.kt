package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionSessionLifecycleStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun accountLogoutDoesNotMutatePermissionsOrConsentPreferences() {
        val logout = MainActivityRefactorSource.function(source, "private fun onAccountLogoutClicked()")

        assertTrue(logout.contains("identity.explicitLogout()"))
        assertTrue(logout.contains("identity.forgetStoredReporterActor()"))
        val binding = MainActivityRefactorSource.composition(source, "accountAccessInteractionOwner")
        assertTrue(binding.contains("override fun explicitLogout() { permissionSessionPolicy.explicitLogout() }"))
        assertTrue(binding.contains("override fun forgetStoredReporterActor() { stepLengthPrefs.edit().remove(PREF_REPORTER_USER_ID_KEY).commit() }"))
        assertFalse(logout.contains("withdrawReportPrivacyConsent"))
        assertFalse(logout.contains("PREF_REPORT_PRIVACY_CONSENT_KEY"))
        assertFalse(logout.contains("PREF_AUTOMATIC_REPORT_CONSENT_KEY"))
        assertFalse(logout.contains("requestPermissions"))
    }

    @Test
    fun lifecycleBoundariesCancelTransfersButKeepTheConsentDecision() {
        val pause = MainActivityRefactorSource.function(source, "internal fun pauseWalkSafeRuntime()")
        val destroy = MainActivityRefactorSource.function(source, "override fun onDestroy()")

        assertTrue(pause.contains("reportPrivacyConsentSession.cancelActiveCalls()"))
        assertFalse(pause.contains("withdrawReportPrivacyConsent"))
        assertTrue(destroy.contains("reportPrivacyConsentSession.cancelActiveCalls()"))
        assertFalse(destroy.contains("withdrawReportPrivacyConsent"))
        assertTrue(
            destroy.contains(
                "privacyStartupResources.releaseOnUiThread(gatewaySessionController)",
            ),
        )
        assertFalse(destroy.contains("clearGatewaySession("))
        val gateway = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/gateway/GatewaySessionController.kt").readText()
        assertTrue(MainActivityRefactorSource.member(gateway, "detach").contains("GatewaySessionProcessCoordinator.detach(owner)"))
        assertTrue(MainActivityRefactorSource.property(gateway, "owner").contains("private val owner = Any()"))
    }

    @Test
    fun protectedNetworkEntryPointsApplyTheIndependentNetworkPreference() {
        fun navigationInput(startMarker: String, endMarker: String): String {
            val factory = MainActivityRefactorSource.factory(source, "createNavigationSessions")
            assertTrue(Regex(Regex.escape(startMarker)).findAll(factory).count() == 1)
            assertTrue(Regex(Regex.escape(endMarker)).findAll(factory).count() == 1)
            val start = factory.indexOf(startMarker)
            val end = factory.indexOf(endMarker, start + startMarker.length)
            assertTrue(start >= 0 && end > start)
            return factory.substring(start, end)
        }

        assertTrue(manifest.contains("android.permission.ACCESS_NETWORK_STATE"))
        assertTrue(
            MainActivityRefactorSource.function(source, "private fun onGatewaySessionButtonClicked()")
                .contains("runtime.gatewayNetworkAllowed(reason = \"gateway_login\")"),
        )
        val search = MainActivityRefactorSource.member(MainActivityRefactorSource.destination, "search")
        assertTrue(MainActivityRefactorSource.composition(source, "destinationSearchController").contains("DestinationSearchController("))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("destinationSearchController.search(reset = true)"))
        assertTrue(search.contains("if (!actions.networkAllowed()) return false"))
        assertTrue(source.contains("override fun networkAllowed() = isGatewayNetworkAllowed(reason = \"destination_search\")"))
        assertTrue(
            MainActivityRefactorSource.function(source, "private fun requestRoute(")
                .contains("effects.networkAllowed(reason = \"walking_route\")"),
        )
        assertTrue(MainActivityRefactorSource.composition(source, "gatewaySessionController").contains("isGatewayNetworkAllowed(reason = reason)"))
        assertTrue(navigationInput("routes = AcquisitionAssemblyBindings(", "resources = NavigationAssemblyResources(").contains("isGatewayNetworkAllowed(reason = reason)"))
        assertTrue(MainActivityRefactorSource.composition(source, "routeAcquisitionController").contains("override fun networkAllowed(reason: String): Boolean = activityEffects.networkAllowed(reason)"))
        assertTrue(MainActivityRefactorSource.composition(source, "reportQueueDrainController").contains("override fun currentBinding() = networkStateProbe.currentIntegratedConsentBinding()"))
        val process = MainActivityRefactorSource.function(source, "private fun processReportCandidate(")
        assertTrue(process.contains("queue.enqueue("))
        assertFalse(process.contains("uploadCall("))
        val capture = MainActivityRefactorSource.function(source, "private fun buildReportQueueDrainTrigger(")
        assertTrue(capture.contains("AndroidNetworkTransferPolicy.isAllowed("))
        assertTrue(capture.contains("network.currentBinding() ?: return null"))
        assertTrue(capture.contains("networkBinding.matches(networkTransport)"))
        val context = MainActivityRefactorSource.function(source, "private fun reportQueueDrainContext(")
        assertTrue(context.contains("trigger.networkBinding.isSameNetworkBinding(currentBinding)"))
        assertTrue(context.contains("AndroidNetworkTransferPolicy.isAllowed("))
        assertTrue(context.contains("runtime.networkGeneration == trigger.networkGeneration"))
    }

    @Test
    fun permissionsAreReobservedWithoutClearingUnrelatedState() {
        val application = MainActivityRefactorSource.function(source, "private fun applyObservedPermissionStateChange(")

        assertTrue(source.contains("applyObservedPermissionStateChange(\"app_resumed\")"))
        assertTrue(application.contains("runtime.stopDepth()"))
        assertTrue(application.contains("runtime.stopCameraFallback()"))
        assertTrue(application.contains("runtime.stopLocation()"))
        assertTrue(application.contains("runtime.cancelVoiceRecognition()"))
        assertTrue(application.contains("runtime.stopSteps()"))
        assertTrue(application.contains("해당 기능만 중지하고 나머지 기능은 계속 사용합니다."))
        val binding = MainActivityRefactorSource.composition(source, "permissionFlowController")
        assertTrue(binding.contains("override fun stopDepth() { stopDepthSession(closeSession = true) }"))
        assertTrue(binding.contains("override fun stopCameraFallback() { stopCameraFallbackSession(updateUi = false) }"))
        assertTrue(binding.contains("override fun stopLocation() { stopLocationUpdates() }"))
        assertTrue(binding.contains("override fun cancelVoiceRecognition() { cancelVoiceCommandRecognition() }"))
        assertTrue(binding.contains("override fun stopSteps() { stopStepTracking() }"))
        assertTrue(binding.contains("override fun safetyStop(reason: String) { enterWalkSessionSafetyStopAndCancelOutputs(reason) }"))
        assertTrue(application.contains("PermissionDependencyPolicy.evaluate("))
        assertTrue(application.contains(
            ").requiresWholeWalkSafetyStop\n        ) {\n            runtime.safetyStop(\"core_guidance_permissions_unavailable\")",
        ))
        val limitation = application.substringAfter("val stateAfterRefresh =")
            .substringBefore("else if (")
        assertTrue(limitation.contains("stateAfterRefresh in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)"))
        assertTrue(limitation.contains("readiness.hasDeviceRestrictions()"))
        assertFalse(limitation.contains("runtime.safetyStop("))
        assertFalse(application.contains("permissionSessionPolicy"))
        assertFalse(application.contains("withdrawReportPrivacyConsent"))
    }

    @Test
    fun startWalkRequiresOnlyActiveCoreFeaturesAndKeepsOptionalFeaturesSeparate() {
        val required = functionBlock("private fun missingRequiredWalkSessionPermissions(")
        val optional = functionBlock("private fun missingWalkSessionPermissions(")

        assertTrue(required.contains("Manifest.permission.CAMERA"))
        assertTrue(required.contains("Manifest.permission.ACCESS_FINE_LOCATION"))
        // S02-14: FP-014-DETAIL-01 makes the microphone a walk-start permission.
        assertTrue(required.contains("Manifest.permission.RECORD_AUDIO"))
        assertFalse(required.contains("Manifest.permission.ACTIVITY_RECOGNITION"))
        assertTrue(optional.contains("Manifest.permission.RECORD_AUDIO"))
        assertTrue(optional.contains("Manifest.permission.ACTIVITY_RECOGNITION"))
    }

    @Test
    fun settingsReturnRequiresFullRecheckWithoutAutomaticResourceStart() {
        val resume = MainActivityRefactorSource.function(source, "override fun onResume()")
        val resumeRuntime =
            MainActivityRefactorSource.function(source, "private fun resumeWalkSafeRuntimeAfterPrivacyStartupInspection()")
        val recheck = MainActivityRefactorSource.function(source, "private fun completePermissionRecoveryRecheckIfPossible()")
        val prerequisites = MainActivityRefactorSource.function(source, "private fun permissionRecoveryPrerequisitesReady(")

        assertTrue(resume.contains("resumeWalkSafeRuntimeAfterPrivacyStartupInspection()"))
        assertTrue(resumeRuntime.contains("permissionFlowController.requirePermissionRecoveryRecheckAfterPrivacyInspection()"))
        val privacyRecheck = MainActivityRefactorSource.function(source, "fun requirePermissionRecoveryRecheckAfterPrivacyInspection(")
        assertTrue(privacyRecheck.contains("if (permissionRecoveryGate.blocksAutomaticResourceStart)"))
        assertTrue(privacyRecheck.contains("permissionRecoveryGate.recheckRequired()"))
        assertTrue(privacyRecheck.contains("persistPermissionRecoveryGate()"))
        assertTrue(resumeRuntime.contains("updateIntegratedConsentUi()"))
        assertTrue(resumeRuntime.contains("updateReportPrivacyConsentUi()"))
        assertTrue(resumeRuntime.contains("completePermissionRecoveryRecheckIfPossible()"))
        assertTrue(recheck.contains("permissionRecoveryPrerequisitesReady(decision)"))
        assertTrue(prerequisites.contains("authority.firstRunComplete()"))
        assertTrue(prerequisites.contains("authority.currentActorId()"))
        assertTrue(prerequisites.contains("readiness.gatewayReady()"))
        assertTrue(prerequisites.contains("decision.mayConfirmAndStart"))
        assertTrue(prerequisites.contains("readiness.blockReason(decision)"))
        val binding = MainActivityRefactorSource.composition(source, "permissionFlowController")
        assertTrue(binding.contains("override fun firstRunComplete() = firstRunOnboardingComplete()"))
        assertTrue(binding.contains("override fun currentActorId() = currentReporterUserId()"))
        assertTrue(binding.contains("override fun gatewayReady() = isGatewaySessionReadyForCurrentActor()"))
        assertTrue(binding.contains("walkSessionReadinessBlockReason(decision)"))
        assertTrue(recheck.contains("completeFullRecheck("))
        val transitionIndex =
            recheck.indexOf("permissionRecoveryGate.completeFullRecheck(")
        val persistIndex =
            recheck.indexOf("persistPermissionRecoveryGate()", transitionIndex)
        val awaitingIndex =
            recheck.indexOf(
                "PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME",
                persistIndex,
            )
        val renderIndex =
            recheck.indexOf("renderAwaitingExplicitResumeControl()", awaitingIndex)
        val recoveryUiIndex =
            recheck.indexOf("updatePermissionRecoveryUi()", renderIndex)
        assertTrue(transitionIndex >= 0)
        assertTrue(
            transitionIndex < persistIndex &&
                persistIndex < awaitingIndex &&
                awaitingIndex < renderIndex &&
                renderIndex < recoveryUiIndex,
        )
        assertFalse(recheck.contains("refreshStartupCapabilityUi()"))
        assertFalse(recheck.contains("activateWalkSessionRuntime()"))
        assertFalse(recheck.contains("startLocationUpdatesIfAllowed("))
        assertFalse(recheck.contains("startVoiceCommandRecognition("))
    }

    @Test
    fun walkPermissionDenialLimitsFeaturesWithoutBlockingOrExitingTheApp() {
        val create = MainActivityRefactorSource.function(source, "override fun onCreate(savedInstanceState: Bundle?)")
        val request = MainActivityRefactorSource.function(source, "private fun requestPermissionsWithLease(")
        val callback = permissionResultOwner()
        val barrier = MainActivityRefactorSource.function(source, "private fun enterPermissionRecoveryBarrier(")
        val confirmation = MainActivityRefactorSource.function(source, "private fun onPermissionDenialConfirmed()")
        val fieldToggle = MainActivityRefactorSource.function(source, "private fun toggleFieldSessionLog()")

        assertTrue(create.contains("restorePermissionRecoveryGateFromPrefs()"))
        assertTrue(create.contains("fieldSessionLog.blockActiveSessionRestore()"))
        assertTrue(request.indexOf("persistPermissionRecoveryGate()") < request.indexOf("platform.launchRequest("))
        assertTrue(callback.contains("isPermissionRequestLeaseCurrent(lease)"))
        assertTrue(callback.contains("permission !in lease.requestedAndroidPermissions"))
        assertTrue(callback.contains("PermissionRequestPurpose.WALK_SESSION -> handleWalkSessionPermissionResult()"))
        val walkResult = MainActivityRefactorSource.function(source, "private fun handleWalkSessionPermissionResult()")
        assertTrue(walkResult.contains("readiness.missingRequired(action)"))
        assertTrue(walkResult.contains("readiness.missingForDecision(decision)"))
        assertFalse(callback.contains("enterPermissionRecoveryBarrier("))
        assertTrue(callback.contains("showPermissionDenialPanel("))
        assertTrue(callback.contains("permissionRecoveryGate.recheckRequired()"))
        assertTrue(callback.contains("PermissionRecoveryGate()"))
        assertTrue(barrier.contains("runtime.blockFieldSessionRestore()"))
        val binding = MainActivityRefactorSource.composition(source, "permissionFlowController")
        assertTrue(binding.contains("override fun blockFieldSessionRestore() { if (::fieldSessionLog.isInitialized) fieldSessionLog.blockActiveSessionRestore() }"))
        assertTrue(binding.contains("missingRequiredWalkSessionPermissions(action)"))
        assertTrue(binding.contains("missingWalkSessionPermissions(decision)"))
        val ui = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/AndroidPermissionUi.kt").readText()
        assertTrue(MainActivityRefactorSource.member(ui, "hidePanel").contains("permissionDenialPanel.visibility = View.GONE"))
        assertFalse(confirmation.contains("finishAndRemoveTask()"))
        assertTrue(confirmation.contains("permissionRecoveryGate.recheckBinding != null"))
        assertTrue(confirmation.contains("permissionRecoveryGate.recheckRequired()"))
        assertTrue(confirmation.contains("PermissionRecoveryGate()"))
        assertTrue(confirmation.contains("ui.hidePanel()"))
        assertTrue(fieldToggle.contains("permissionRecoveryGate.blocksAutomaticResourceStart"))
        assertTrue(fieldToggle.contains("startAfterUserConfirmation()"))
        assertFalse(fieldToggle.contains("fieldSessionLog.start()"))
    }

    @Test
    fun reportBoundaryChecksPermissionsBeforeFreezingAndUsesGatedQueueDrain() {
        val prepare = MainActivityRefactorSource.function(source, "private fun prepareReportCandidate(")
        val process = MainActivityRefactorSource.function(source, "private fun processReportCandidate(")
        val drain = MainActivityRefactorSource.function(source, "private fun drainInitialExactReportQueue(")

        assertTrue(prepare.contains("reportPermissionsAllowWork()"))
        assertTrue(process.contains("reportPermissionsAllowWork()"))
        val permissionGate = process.indexOf("reportPermissionsAllowWork()")
        val enqueue = process.indexOf("queue.enqueue(")
        assertTrue(
            permissionGate >= 0 && enqueue > permissionGate,
        )
        assertFalse(process.contains("uploadCall("))
        assertTrue(drain.contains("transportFactory(trigger)"))
        assertTrue(MainActivityRefactorSource.composition(source, "reportQueueDrainController").contains("AndroidReportQueueTransport("))
        assertTrue(MainActivityRefactorSource.composition(source, "reportCandidateController").contains("reportQueueStore.enqueue("))
        val baseGate = drain.indexOf("if (!context.allRequiredBaseGatesAllowed()) break")
        val startNext = drain.indexOf("reportQueueDrainCoordinator.startNextWithResult(")
        val execute = drain.indexOf("call.execute()")
        assertTrue(baseGate >= 0 && startNext > baseGate)
        assertTrue(execute > startNext)
    }

    @Test
    fun permissionRecheckAcknowledgementNeverStartsOrAdvancesTheRuntime() {
        val confirmation = MainActivityRefactorSource.function(source, "private fun onPermissionDenialConfirmed()")
        val actionButtonMarker = "actionButton = runtimeComposer.createDepthAction("
        val debugUploadButtonMarker = "debugUploadButton = runtimeComposer.createDebugUploadAction("
        assertTrue(source.contains(actionButtonMarker))
        assertTrue(source.contains(debugUploadButtonMarker))
        val actionControl = source
            .substringAfter(actionButtonMarker)
            .substringBefore(debugUploadButtonMarker)
        val startMarker = "ActionMode.START -> {"
        val openSettingsMarker = "ActionMode.OPEN_SETTINGS ->"
        assertTrue(actionControl.contains(startMarker))
        assertTrue(actionControl.contains(openSettingsMarker))
        val startAction = actionControl
            .substringAfter(startMarker)
            .substringBefore(openSettingsMarker)
        val awaitingMarker =
            "PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME -> {"
        val otherStatesMarker = "PermissionRecoveryGateState.SETTINGS_PENDING,"
        assertTrue(confirmation.contains(awaitingMarker))
        assertTrue(confirmation.contains(otherStatesMarker))
        val awaitingConfirmation = confirmation
            .substringAfter(awaitingMarker)
            .substringBefore(otherStatesMarker)

        assertTrue(awaitingConfirmation.contains("ui.hidePanel()"))
        assertTrue(awaitingConfirmation.contains("runtime.status("))
        assertFalse(awaitingConfirmation.contains("acknowledgeExplicitResume("))
        assertFalse(awaitingConfirmation.contains("persistPermissionRecoveryGate()"))
        assertFalse(awaitingConfirmation.contains("maybeAdvanceFirstRunOnboarding()"))
        assertFalse(awaitingConfirmation.contains("refreshStartupCapabilityUi()"))
        assertTrue(awaitingConfirmation.contains("renderAwaitingExplicitResumeControl()"))
        assertFalse(awaitingConfirmation.contains("ensurePermissionsThenStart()"))
        assertFalse(awaitingConfirmation.contains("runtime.startAfterPermissionRecovery("))
        assertFalse(awaitingConfirmation.contains("runtime.refreshStartup("))
        val permissionUi = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/AndroidPermissionUi.kt").readText()
        assertTrue(MainActivityRefactorSource.member(permissionUi, "hidePanel").contains("permissionDenialPanel.visibility = View.GONE"))
        assertTrue(MainActivityRefactorSource.composition(source, "permissionFlowController").contains("permissionFlowStatusPresenter(status, detail)"))
        assertTrue(MainActivityRefactorSource.property(source, "permissionFlowStatusPresenter").contains("= ::updateStatus"))
        val runtimeViews = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRuntimeViewComposer.kt").readText()
        assertTrue(MainActivityRefactorSource.member(runtimeViews, "createDepthAction").contains("setOnClickListener { onClick() }"))

        val explicitResume =
            MainActivityRefactorSource.function(source, "private fun resumePermissionRecoveryFromExplicitUserAction(): Boolean")
        assertTrue(startAction.contains("resumePermissionRecoveryFromExplicitUserAction()"))
        assertTrue(startAction.contains("ensurePermissionsThenStart()"))
        assertFalse(startAction.contains("acknowledgeExplicitResume("))
        assertFalse(startAction.contains("persistPermissionRecoveryGate()"))
        assertTrue(explicitResume.contains("PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME"))
        val clearIndex = explicitResume.indexOf("acknowledgeExplicitResume(")
        assertTrue(explicitResume.contains("observed = currentObservedPermissionSnapshot()"))
        val persistIndex = explicitResume.indexOf("persistPermissionRecoveryGate()")
        val ensureIndex = explicitResume.indexOf("runtime.startAfterPermissionRecovery()")
        assertTrue(MainActivityRefactorSource.composition(source, "permissionFlowController").contains("override fun startAfterPermissionRecovery() { ensurePermissionsThenStart() }"))
        assertTrue(clearIndex < persistIndex && persistIndex < ensureIndex)
        assertTrue(
            Regex(Regex.escape("acknowledgeExplicitResume(")).findAll(File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/PermissionFlowController.kt").readText()).count() == 1,
        )
        assertTrue(
            Regex(
                "(?<![.])" + Regex.escape("resumePermissionRecoveryFromExplicitUserAction()"),
            ).findAll(source).count() == 3,
        )
    }

    @Test
    fun awaitingPermissionRecoveryUsesTheReachableExternalResumeControl() {
        val panel = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/OnboardingReadinessPanel.kt").readText()
        val externalButtonMarker = "startupCapabilityConfirmButton = Button(context).apply"
        val externalButtonEnd = "permissionDenialPanel = LinearLayout(context).apply"
        val clickMarker = "setOnClickListener {"
        val normalClickMarker = "val session = lifecycle().snapshot()"
        val refreshButtonMarker = "ui.render("
        val refreshButtonEnd = "ui.updatePriority(decision)"
        val runtimeVisibilityMarker = "runtimeVisible ="
        for (marker in listOf(externalButtonMarker, externalButtonEnd, clickMarker)) {
            assertTrue("Missing actual SDK control marker: $marker", panel.contains(marker))
        }
        assertTrue(MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/interaction/StartupConfirmationInteractionController.kt").readText(), "handleStartupCapabilityConfirmAction").contains(normalClickMarker))
        assertTrue(MainActivityRefactorSource.function(source, "private fun refreshStartupCapabilityUi()").contains(refreshButtonMarker))
        assertTrue(MainActivityRefactorSource.function(source, "private fun refreshStartupCapabilityUi()").contains(refreshButtonEnd))
        assertTrue(MainActivityRefactorSource.function(source, "private fun updateFirstRunOnboardingUi()").contains(runtimeVisibilityMarker))
        val externalButton = panel.substringAfter(externalButtonMarker).substringBefore(externalButtonEnd)
        assertTrue(externalButton.contains("setOnClickListener { actions.readiness.onStartupConfirmed() }"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("onStartupConfirmed = { handleStartupCapabilityConfirmAction() }"))
        val explicitClick =
            MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/interaction/StartupConfirmationInteractionController.kt").readText(), "handleStartupCapabilityConfirmAction")
        val refresh = MainActivityRefactorSource.function(source, "private fun refreshStartupCapabilityUi()")
        val renderer = MainActivityRefactorSource.function(source, "private fun renderStartupCapabilityFacts(")
        val rendererBinding = MainActivityRefactorSource.composition(source, "startupCapabilityFactsRenderer")
        val refreshButton = rendererBinding
        assertTrue(renderer.contains("if (effects.render(startupUiFacts)) effects.renderExplicitResume()"))
        val runtimeVisibility =
            MainActivityRefactorSource.function(source, "private fun updateFirstRunOnboardingUi()")
                .substringAfter(runtimeVisibilityMarker)
        val automaticRecheck =
            MainActivityRefactorSource.function(source, "private fun completePermissionRecoveryRecheckIfPossible()")
        val automaticAdvance =
            MainActivityRefactorSource.function(source, "private fun maybeAdvanceWalkSessionAfterCapabilityCheck()")
        val restoredGate = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/AndroidPermissionPreferences.kt").readText(), "restoreRecovery")
        assertTrue(MainActivityRefactorSource.function(source, "private fun restorePermissionRecoveryGateFromPrefs()").contains("preferences.restoreRecovery()"))
        val pureRender = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/AndroidPermissionUi.kt").readText(), "renderResumeControl")
        assertTrue(MainActivityRefactorSource.function(source, "private fun renderAwaitingExplicitResumeControl()").contains("ui.renderResumeControl()"))

        assertTrue(explicitClick.contains("effects.resumePermissionRecovery()"))
        assertTrue(
            explicitClick.contains(
                "if (effects.resumePermissionRecovery()) return",
            ),
        )
        assertTrue(
            explicitClick.indexOf("retryRequiresUserAction = false") <
                explicitClick.indexOf("effects.resumePermissionRecovery()"),
        )
        assertTrue(
            explicitClick.indexOf("effects.prepareResumeRetry()") <
                explicitClick.indexOf("effects.resumePermissionRecovery()"),
        )
        val interactionFactoryStart = source.indexOf("private fun createStartupConfirmationInteraction(): StartupConfirmationInteractionController =")
        val interactionFactoryEnd = source.indexOf("\nprivate fun handleStartupCapabilityConfirmAction()", interactionFactoryStart)
        assertTrue(Regex(Regex.escape("private fun createStartupConfirmationInteraction(): StartupConfirmationInteractionController =")).findAll(source).count() == 1)
        assertTrue(Regex(Regex.escape("\nprivate fun handleStartupCapabilityConfirmAction()")).findAll(source).count() == 1)
        assertTrue(interactionFactoryStart >= 0 && interactionFactoryEnd > interactionFactoryStart)
        val clickBinding = MainActivityRefactorSource.composition(source, "startupConfirmationInteraction")
        assertTrue(clickBinding.contains("StartupConfirmationInteractionController("))
        assertTrue(clickBinding.contains("lifecycle = { walkSessionLifecycle }"))
        assertTrue(clickBinding.contains("override fun prepareResumeRetry() = walkSessionResumeController.prepareConfirmationRetry()"))
        assertTrue(clickBinding.contains("override fun resumePermissionRecovery() = resumePermissionRecoveryFromExplicitUserAction()"))
        assertTrue(refreshButton.contains("PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME"))
        assertTrue(refreshButton.contains("renderAwaitingExplicitResumeControl()"))
        assertTrue(pureRender.contains("startupCapabilityConfirmButton.visibility = View.VISIBLE"))
        assertTrue(pureRender.contains("startupCapabilityConfirmButton.isEnabled = true"))
        assertTrue(pureRender.contains("startupCapabilityConfirmButton.text = \"보행 안내 다시 시작\""))
        assertTrue(pureRender.contains("runtimeControls.visibility = View.GONE"))
        assertTrue(runtimeVisibility.contains("!facts.permissionRecoveryBlocksStart()"))
        assertTrue(MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/FirstRunOnboardingPresenter.kt").readText(), "renderRuntime").contains("View.GONE"))

        listOf(refresh, automaticRecheck, restoredGate).forEach { automatic ->
            assertFalse(automatic.contains("acknowledgeExplicitResume("))
            assertFalse(automatic.contains("resumePermissionRecoveryFromExplicitUserAction()"))
        }
        assertFalse(refresh.contains("permissionRecoveryGate ="))
        assertFalse(refresh.contains("ensurePermissionsThenStart()"))
        listOf(
            "maybeAdvanceWalkSessionAfterCapabilityCheck()",
            "completePermissionRecoveryRecheckIfPossible()",
            "acknowledgeExplicitResume()",
            "ensurePermissionsThenStart()",
            "refreshStartupCapabilityUi()",
            "runtime.refreshStartup(",
            "runtime.startAfterPermissionRecovery(",
        ).forEach { forbiddenCall ->
            assertFalse(pureRender.contains(forbiddenCall))
        }
        assertFalse(refresh.contains("completePermissionRecoveryRecheckIfPossible()"))
        assertTrue(MainActivityRefactorSource.composition(source, "firstRunPresentationCoordinator").contains("permissionRecoveryGate.blocksAutomaticResourceStart"))
        assertTrue(MainActivityRefactorSource.composition(source, "walkSessionAdmissionController").contains("completePermissionRecoveryRecheckIfPossible()"))
        assertTrue(MainActivityRefactorSource.composition(source, "permissionFlowController").contains("ui = permissionFlowUi"))
        assertTrue(MainActivityRefactorSource.composition(source, "permissionFlowUi").contains("AndroidPermissionUi(this,"))
        assertTrue(automaticAdvance.contains("if (createdConfirmationToken)"))
        assertTrue(automaticAdvance.contains("effects.refreshReadinessUi()"))
        assertTrue(automaticAdvance.contains("effects.completePermissionRecoveryRecheck()"))
        assertFalse(automaticRecheck.contains("refreshStartupCapabilityUi()"))
        assertFalse(automaticRecheck.contains("runtime.refreshStartup("))
        assertFalse(automaticRecheck.contains("runtime.startAfterPermissionRecovery("))
        assertFalse(automaticRecheck.contains("maybeAdvanceWalkSessionAfterCapabilityCheck()"))
        assertTrue(automaticAdvance.contains("state.permissionRecoveryBlocksStart()"))
        assertFalse(source.contains("maybeAdvanceFirstRunOnboarding"))
        assertTrue(
            restoredGate.contains(
                "persisted == PermissionRecoveryGateState.CLEAR",
            ),
        )
        assertTrue(restoredGate.contains("PermissionRecoveryGate.restoredBlocked(affected)"))
    }

    @Test
    fun automaticRecoveryCallbacksAndRestoreCannotClearOrBypassTheGate() {
        val createSignature = "override fun onCreate(savedInstanceState: Bundle?)"
        val resumeSignature = "override fun onResume()"
        val permissionResultSignature = "override fun onRequestPermissionsResult("
        val cameraResultSignature = "private fun handleCameraPermissionResult()"
        val navigationResultSignature = "private fun handleNavigationPermissionResult()"
        val voiceResultSignature = "private fun handleVoicePermissionResult()"
        val refreshSignature = "private fun refreshStartupCapabilityUi()"
        val advanceSignature = "private fun maybeAdvanceWalkSessionAfterCapabilityCheck()"
        val recheckSignature = "private fun completePermissionRecoveryRecheckIfPossible()"
        val restoreSignature = "private fun restorePermissionRecoveryGateFromPrefs()"
        listOf(
            createSignature,
            resumeSignature,
            permissionResultSignature,
            cameraResultSignature,
            navigationResultSignature,
            voiceResultSignature,
            refreshSignature,
            advanceSignature,
            recheckSignature,
            restoreSignature,
        ).forEach { marker ->
            assertTrue("Missing exact source marker: $marker", MainActivityRefactorSource.function(source, marker).contains("fun "))
        }

        val create = MainActivityRefactorSource.function(source, createSignature)
        val restore = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/AndroidPermissionPreferences.kt").readText(), "restoreRecovery")
        assertTrue(MainActivityRefactorSource.function(source, restoreSignature).contains("preferences.restoreRecovery()"))
        val nonClearRestoreMarker = "val affected = preferences()"
        assertTrue(restore.contains(nonClearRestoreMarker))
        val nonClearRestore = restore.substringAfter(nonClearRestoreMarker)
        val advance = MainActivityRefactorSource.function(source, advanceSignature)
        val activation =
            MainActivityRefactorSource.function(source, "private fun completeGatewayWalkActivation(")
        val permissionResult = permissionResultOwner()
        val automaticSlices = listOf(
            MainActivityRefactorSource.function(source, resumeSignature),
            MainActivityRefactorSource.function(source, cameraResultSignature),
            MainActivityRefactorSource.function(source, navigationResultSignature),
            MainActivityRefactorSource.function(source, voiceResultSignature),
            MainActivityRefactorSource.function(source, refreshSignature),
            advance,
            MainActivityRefactorSource.function(source, recheckSignature),
            nonClearRestore,
        )
        listOf(
            "permissionRecoveryGate = PermissionRecoveryGate()",
            "resumePermissionRecoveryFromExplicitUserAction()",
            "acknowledgeExplicitResume(",
            "ensurePermissionsThenStart()",
            "runtime.startAfterPermissionRecovery(",
        ).forEach { forbidden ->
            automaticSlices.forEach { automatic ->
                assertFalse(automatic.contains(forbidden))
            }
        }
        assertTrue(permissionResult.contains("if (!isPermissionRequestLeaseCurrent(lease)) return"))
        assertTrue(
            Regex(
                """permissionRecoveryGate = if \(permissionRecoveryGate\.recheckBinding != null\) \{\s*""" +
                    """permissionRecoveryGate\.recheckRequired\(\)\s*\} else \{\s*PermissionRecoveryGate\(\)\s*\}""",
            ).containsMatchIn(permissionResult),
        )
        assertFalse(permissionResult.contains("acknowledgeExplicitResume("))
        assertFalse(permissionResult.contains("ensurePermissionsThenStart()"))
        assertFalse(permissionResult.contains("runtime.startAfterPermissionRecovery("))
        val explicitResume = MainActivityRefactorSource.function(source, "private fun resumePermissionRecoveryFromExplicitUserAction(")
        assertTrue(explicitResume.contains("val confirmedBinding = permissionRecoveryGate.recheckBinding"))
        assertTrue(explicitResume.contains("currentPermissionRecoveryBinding(it.recheckRequestId)"))
        assertTrue(explicitResume.contains("acknowledgeExplicitResume("))
        assertTrue(explicitResume.contains("observed = currentObservedPermissionSnapshot()"))
        assertTrue(explicitResume.contains("currentBinding = currentBinding"))
        val explicitGate = explicitResume.indexOf("if (permissionRecoveryGate.blocksAutomaticResourceStart)")
        val explicitStart = explicitResume.indexOf("runtime.startAfterPermissionRecovery()")
        assertTrue(explicitGate >= 0 && explicitStart > explicitGate)
        assertTrue(nonClearRestore.contains("PermissionRecoveryGate.restoredBlocked(affected)"))

        val guardMarker =
            "if (state.permissionRecoveryBlocksStart() && !state.developmentBypassEnabled()) {"
        assertTrue(
            Regex(
                """private val developmentGuidanceStartBypassEnabled:\s*Boolean\s+""" +
                    """get\(\)\s*=\s*BuildConfig\.DEBUG\s*&&\s*""" +
                    """BuildConfig\.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS\s*&&""",
            ).containsMatchIn(source),
        )
        assertTrue(
            activation.contains(
                "runtime.blocksAutomaticStart()",
            ),
        )
        val recheckCallMarker = "effects.completePermissionRecoveryRecheck()"
        val blockedReturnMarker =
            "if (state.permissionRecoveryBlocksStart()) return"
        val requestWalkLeaseMarker = "requestGatewayWalkStart(token)"
        listOf(
            guardMarker,
            recheckCallMarker,
            blockedReturnMarker,
            requestWalkLeaseMarker,
        ).forEach { marker ->
            assertTrue("Missing guarded advance marker: $marker", advance.contains(marker))
        }
        val guardIndex = advance.indexOf(guardMarker)
        val recheckIndex = advance.indexOf(recheckCallMarker, guardIndex)
        val blockedReturnIndex = advance.indexOf(blockedReturnMarker, recheckIndex)
        val requestWalkLeaseIndex =
            advance.indexOf(requestWalkLeaseMarker, blockedReturnIndex)
        assertTrue(
            guardIndex < recheckIndex &&
                recheckIndex < blockedReturnIndex &&
                blockedReturnIndex < requestWalkLeaseIndex,
        )

        val permissionGateMarker =
            "runtime.blocksAutomaticStart()"
        val startRequestedMarker = "runtime.startRequested(token, currentReadiness)"
        val activateMarker = "runtime.activate()"
        listOf(
            permissionGateMarker,
            startRequestedMarker,
            activateMarker,
        ).forEach { marker ->
            assertTrue(
                "Missing guarded activation marker: $marker",
                activation.contains(marker),
            )
        }
        val permissionGateIndex = activation.indexOf(permissionGateMarker)
        val startRequestedIndex =
            activation.indexOf(startRequestedMarker, permissionGateIndex)
        val activateIndex = activation.indexOf(activateMarker, startRequestedIndex)
        assertTrue(
            permissionGateIndex < startRequestedIndex &&
                startRequestedIndex < activateIndex,
        )

        val admissionBinding = MainActivityRefactorSource.composition(source, "walkSessionAdmissionController")
        assertTrue(admissionBinding.contains("override fun permissionRecoveryBlocksStart() = permissionRecoveryGate.blocksAutomaticResourceStart"))
        assertTrue(admissionBinding.contains("override fun developmentBypassEnabled() = developmentGuidanceStartBypassEnabled"))
        assertTrue(admissionBinding.contains("completePermissionRecoveryRecheckIfPossible()"))
        val gatewayBinding = MainActivityRefactorSource.composition(source, "gatewayWalkSessionController")
        assertTrue(gatewayBinding.contains("permissionRecoveryGate.blocksAutomaticResourceStart && !developmentGuidanceStartBypassEnabled"))
        assertTrue(gatewayBinding.contains("activateWalkSessionRuntime()"))
        assertTrue(gatewayBinding.contains("transitionWalkSession(WalkSessionEvent.StartRequested(token, readiness))"))
        val restoreCallMarker = "restorePermissionRecoveryGateFromPrefs()"
        val fieldLogBlockMarker = "fieldSessionLog.blockActiveSessionRestore()"
        val probeMarker = "startupCapabilityProbe = privacyStartupResources.acquireOnUiThread(create = { AndroidStartupCapabilityProbe(this) {"
        val progressionMarker = "maybeStartFirstRunDeviceCheckProbes()"
        val refreshMarker = "refreshStartupCapabilityUi()"
        listOf(
            restoreCallMarker,
            fieldLogBlockMarker,
            probeMarker,
            progressionMarker,
            refreshMarker,
        ).forEach { marker ->
            assertTrue("Missing startup restore marker: $marker", create.contains(marker))
        }
        val restoreIndex = create.indexOf(restoreCallMarker)
        val fieldLogBlockIndex = create.indexOf(fieldLogBlockMarker, restoreIndex)
        val probeIndex = create.indexOf(probeMarker, fieldLogBlockIndex)
        val progressionIndex = create.indexOf(progressionMarker, probeIndex)
        val refreshIndex = create.indexOf(refreshMarker, progressionIndex)
        assertTrue(
            restoreIndex < fieldLogBlockIndex &&
                fieldLogBlockIndex < probeIndex &&
                probeIndex < progressionIndex &&
                progressionIndex < refreshIndex,
        )
    }

    @Test
    fun rightsRequestSurfaceDoesNotRequireAnInstalledLoginSession() {
        val rights = MainActivityRefactorSource.function(source, "private fun openPrivacyRightsPage()")

        assertTrue(rights.contains("Intent.ACTION_VIEW"))
        assertTrue(rights.contains("/privacy/rights"))
        assertFalse(rights.contains("gatewaySessionOrNull"))
        assertFalse(rights.contains("requireReporterUserId"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("onPrivacyRights = { openPrivacyRightsPage() }"))
        assertTrue(MainActivityRefactorSource.property(File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/SettingsPrivacyPanel.kt").readText(), "privacyRightsButton").contains("개인정보 열람·동의 철회·서버 자료 삭제 요청"))
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing function: $signature" }
        val next = source.indexOf("\n    private fun ", start + signature.length)
        return if (next < 0) source.substring(start) else source.substring(start, next)
    }

    @Test
    fun acquiredGatewayRetiresItsOriginalObserverWithoutClearingSession() {
        val source = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val create = MainActivityRefactorSource.member(source, "onCreate")
        assertTrue(create.contains("{ gatewaySessionController }, { try { it.detach() } finally { it.unregisterGatewayCapacityNetworkObserver() } }"))
        val resources = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/PrivacyStartupResources.kt").readText()
        val release = MainActivityRefactorSource.member(resources, "release")
        assertTrue(release.contains("it.resource === resource"))
        assertTrue(release.indexOf("lane.entries.removeAt(index)") < release.indexOf("entry?.release?.invoke()"))
        assertFalse(release.contains("clearGatewaySession"))
    }


    private fun permissionResultOwner(mainSource: String = source): String {
        MainActivityRefactorSource.composition(mainSource, "permissionFlowController")
        val main = MainActivityRefactorSource.factory(mainSource, "onRequestPermissionsResult")
        // Whole declaration comparison preserves guards, argument forwarding and effect order.
        val expectedMain = """
override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        invalidateRouteSnapshotResponseLease()
        invalidateSpeechOutputStorageBinding()
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionFlowController.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
        """.trimIndent()
        org.junit.Assert.assertEquals("Main permission callback must retain invalidation, super and owner order", expectedMain.filterNot(Char::isWhitespace),
            main.filterNot(Char::isWhitespace))
        val tokens = listOf("invalidateRouteSnapshotResponseLease()", "invalidateSpeechOutputStorageBinding()",
            "super.onRequestPermissionsResult(requestCode, permissions, grantResults)",
            "permissionFlowController.onRequestPermissionsResult(requestCode, permissions, grantResults)")
        var previous = -1
        tokens.forEach { token ->
            val next = main.indexOf(token, previous + 1)
            assertTrue("Main permission ordering: $token", next > previous)
            previous = next
        }
        return MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/session/permission/PermissionFlowController.kt",
        ).readText(), "onRequestPermissionsResult")
    }


    @Test
    fun permissionResultOwnerRejectsNonExecutingOrReorderedCallers() {
        val mainSource = source
        permissionResultOwner(mainSource)
        val caller = MainActivityRefactorSource.factory(mainSource, "onRequestPermissionsResult")
        val call = "permissionFlowController.onRequestPermissionsResult(requestCode, permissions, grantResults)"
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
            "invalidateRouteSnapshotResponseLease()", "invalidateRouteSnapshotResponseLease()\n" + call,
        )

        val accepted = variants.filter { (_, changed) ->
            val mainSource = mainSource.replace(caller, changed)
            runCatching { permissionResultOwner(mainSource) }.isSuccess
        }.keys
        org.junit.Assert.assertTrue("Non-equivalent Main callers accepted: $accepted", accepted.isEmpty())
    }

}
