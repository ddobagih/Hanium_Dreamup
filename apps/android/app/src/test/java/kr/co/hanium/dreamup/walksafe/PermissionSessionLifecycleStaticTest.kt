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
        val logout = functionBlock("private fun onAccountLogoutClicked()")

        assertTrue(logout.contains("permissionSessionPolicy.explicitLogout()"))
        assertTrue(logout.contains("remove(PREF_REPORTER_USER_ID_KEY)"))
        assertFalse(logout.contains("withdrawReportPrivacyConsent"))
        assertFalse(logout.contains("PREF_REPORT_PRIVACY_CONSENT_KEY"))
        assertFalse(logout.contains("PREF_AUTOMATIC_REPORT_CONSENT_KEY"))
        assertFalse(logout.contains("requestPermissions"))
    }

    @Test
    fun lifecycleBoundariesCancelTransfersButKeepTheConsentDecision() {
        val pause = functionBlock("internal fun pauseWalkSafeRuntime()")
        val destroy = functionBlock("override fun onDestroy()")

        assertTrue(pause.contains("reportPrivacyConsentSession.cancelActiveCalls()"))
        assertFalse(pause.contains("withdrawReportPrivacyConsent"))
        assertTrue(destroy.contains("reportPrivacyConsentSession.cancelActiveCalls()"))
        assertFalse(destroy.contains("withdrawReportPrivacyConsent"))
        assertTrue(
            destroy.contains(
                "GatewaySessionProcessCoordinator.detach(gatewaySessionOwner)",
            ),
        )
        assertFalse(destroy.contains("clearGatewaySession("))
    }

    @Test
    fun protectedNetworkEntryPointsApplyTheIndependentNetworkPreference() {
        assertTrue(manifest.contains("android.permission.ACCESS_NETWORK_STATE"))
        assertTrue(
            functionBlock("private fun onGatewaySessionButtonClicked()")
                .contains("isGatewayNetworkAllowed(reason = \"gateway_login\")"),
        )
        assertTrue(
            functionBlock("private fun performDestinationSearch(")
                .contains("isGatewayNetworkAllowed(reason = \"destination_search\")"),
        )
        assertTrue(
            functionBlock("private fun requestRoute(")
                .contains("isGatewayNetworkAllowed(reason = \"walking_route\")"),
        )
        val process = functionBlock("private fun processReportCandidate(")
        assertTrue(process.contains("reportQueueStore.enqueue("))
        assertFalse(process.contains("uploadCall("))
        val capture = functionBlock("private fun buildReportQueueDrainTrigger(")
        assertTrue(capture.contains("AndroidNetworkTransferPolicy.isAllowed("))
        assertTrue(capture.contains("currentIntegratedConsentBinding() ?: return null"))
        assertTrue(capture.contains("networkBinding.matches(networkTransport)"))
        val context = functionBlock("private fun reportQueueDrainContext(")
        assertTrue(context.contains("trigger.networkBinding.isSameNetworkBinding(currentBinding)"))
        assertTrue(context.contains("AndroidNetworkTransferPolicy.isAllowed("))
        assertTrue(context.contains("runtime.networkGeneration == trigger.networkGeneration"))
    }

    @Test
    fun permissionsAreReobservedWithoutClearingUnrelatedState() {
        val application = functionBlock("private fun applyObservedPermissionStateChange(")

        assertTrue(source.contains("applyObservedPermissionStateChange(\"app_resumed\")"))
        assertTrue(application.contains("stopDepthSession(closeSession = true)"))
        assertTrue(application.contains("stopCameraFallbackSession(updateUi = false)"))
        assertTrue(application.contains("stopLocationUpdates()"))
        assertTrue(application.contains("cancelVoiceCommandRecognition()"))
        assertTrue(application.contains("stopStepTracking()"))
        assertTrue(application.contains("해당 기능만 중지하고 나머지 기능은 계속 사용합니다."))
        assertFalse(application.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertFalse(application.contains("permissionSessionPolicy"))
        assertFalse(application.contains("withdrawReportPrivacyConsent"))
    }

    @Test
    fun startWalkRequiresOnlyActiveCoreFeaturesAndKeepsOptionalFeaturesSeparate() {
        val required = functionBlock("private fun missingRequiredWalkSessionPermissions(")
        val optional = functionBlock("private fun missingWalkSessionPermissions(")

        assertTrue(required.contains("Manifest.permission.CAMERA"))
        assertTrue(required.contains("Manifest.permission.ACCESS_FINE_LOCATION"))
        assertFalse(required.contains("Manifest.permission.RECORD_AUDIO"))
        assertFalse(required.contains("Manifest.permission.ACTIVITY_RECOGNITION"))
        assertTrue(optional.contains("Manifest.permission.RECORD_AUDIO"))
        assertTrue(optional.contains("Manifest.permission.ACTIVITY_RECOGNITION"))
    }

    @Test
    fun settingsReturnRequiresFullRecheckWithoutAutomaticResourceStart() {
        val resume = functionBlock("override fun onResume()")
        val resumeRuntime =
            functionBlock("private fun resumeWalkSafeRuntimeAfterPrivacyStartupInspection()")
        val recheck = functionBlock("private fun completePermissionRecoveryRecheckIfPossible()")

        assertTrue(resume.contains("resumeWalkSafeRuntimeAfterPrivacyStartupInspection()"))
        assertTrue(resumeRuntime.contains("permissionRecoveryGate.recheckRequired()"))
        assertTrue(resumeRuntime.contains("updateIntegratedConsentUi()"))
        assertTrue(resumeRuntime.contains("updateReportPrivacyConsentUi()"))
        assertTrue(resumeRuntime.contains("completePermissionRecoveryRecheckIfPossible()"))
        assertTrue(recheck.contains("firstRunOnboardingComplete()"))
        assertTrue(recheck.contains("currentReporterUserId()"))
        assertTrue(recheck.contains("isGatewaySessionReadyForCurrentActor()"))
        assertTrue(recheck.contains("walkSessionReadinessBlockReason(decision)"))
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
        val create = functionBlock("override fun onCreate(savedInstanceState: Bundle?)")
        val request = functionBlock("private fun requestPermissionsWithLease(")
        val callback = functionBlock("override fun onRequestPermissionsResult(")
        val barrier = functionBlock("private fun enterPermissionRecoveryBarrier(")
        val confirmation = functionBlock("private fun onPermissionDenialConfirmed()")
        val fieldToggle = functionBlock("private fun toggleFieldSessionLog()")

        assertTrue(create.contains("restorePermissionRecoveryGateFromPrefs()"))
        assertTrue(create.contains("fieldSessionLog.blockActiveSessionRestore()"))
        assertTrue(request.indexOf("persistPermissionRecoveryGate()") < request.indexOf("requestPermissions("))
        assertTrue(callback.contains("lease.requestedPermissions"))
        assertTrue(callback.contains("filterNot(observed::isGranted)"))
        assertFalse(callback.contains("enterPermissionRecoveryBarrier("))
        assertTrue(callback.contains("showPermissionDenialPanel("))
        assertTrue(callback.contains("permissionRecoveryGate = PermissionRecoveryGate()"))
        assertTrue(barrier.contains("fieldSessionLog.blockActiveSessionRestore()"))
        assertFalse(confirmation.contains("finishAndRemoveTask()"))
        assertTrue(confirmation.contains("permissionRecoveryGate = PermissionRecoveryGate()"))
        assertTrue(confirmation.contains("permissionDenialPanel.visibility = View.GONE"))
        assertTrue(fieldToggle.contains("permissionRecoveryGate.blocksAutomaticResourceStart"))
        assertTrue(fieldToggle.contains("startAfterUserConfirmation()"))
        assertFalse(fieldToggle.contains("fieldSessionLog.start()"))
    }

    @Test
    fun reportBoundaryChecksPermissionsBeforeFreezingAndUsesGatedQueueDrain() {
        val prepare = functionBlock("private fun prepareReportCandidate(")
        val process = functionBlock("private fun processReportCandidate(")
        val drain = functionBlock("private fun drainInitialExactReportQueue(")

        assertTrue(prepare.contains("reportPermissionsAllowWork()"))
        assertTrue(process.contains("reportPermissionsAllowWork()"))
        val permissionGate = process.indexOf("reportPermissionsAllowWork()")
        val enqueue = process.indexOf("reportQueueStore.enqueue(")
        assertTrue(
            permissionGate >= 0 && enqueue > permissionGate,
        )
        assertFalse(process.contains("uploadCall("))
        assertTrue(drain.contains("AndroidReportQueueTransport("))
        val baseGate = drain.indexOf("if (!context.allRequiredBaseGatesAllowed()) break")
        val startNext = drain.indexOf("reportQueueDrainCoordinator.startNext(")
        val execute = drain.indexOf("call.execute()")
        assertTrue(baseGate >= 0 && startNext > baseGate)
        assertTrue(execute > startNext)
    }

    @Test
    fun permissionRecheckAcknowledgementNeverStartsOrAdvancesTheRuntime() {
        val confirmation = functionBlock("private fun onPermissionDenialConfirmed()")
        val actionButtonMarker = "actionButton = Button(this).apply"
        val debugUploadButtonMarker = "debugUploadButton = Button(this).apply"
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

        assertTrue(awaitingConfirmation.contains("permissionDenialPanel.visibility = View.GONE"))
        assertTrue(awaitingConfirmation.contains("updateStatus("))
        assertFalse(awaitingConfirmation.contains("acknowledgeExplicitResume()"))
        assertFalse(awaitingConfirmation.contains("persistPermissionRecoveryGate()"))
        assertFalse(awaitingConfirmation.contains("maybeAdvanceFirstRunOnboarding()"))
        assertFalse(awaitingConfirmation.contains("refreshStartupCapabilityUi()"))
        assertTrue(awaitingConfirmation.contains("renderAwaitingExplicitResumeControl()"))
        assertFalse(awaitingConfirmation.contains("ensurePermissionsThenStart()"))

        val explicitResume =
            functionBlock("private fun resumePermissionRecoveryFromExplicitUserAction(): Boolean")
        assertTrue(startAction.contains("resumePermissionRecoveryFromExplicitUserAction()"))
        assertTrue(startAction.contains("ensurePermissionsThenStart()"))
        assertFalse(startAction.contains("acknowledgeExplicitResume()"))
        assertFalse(startAction.contains("persistPermissionRecoveryGate()"))
        assertTrue(explicitResume.contains("PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME"))
        val clearIndex = explicitResume.indexOf("acknowledgeExplicitResume()")
        val persistIndex = explicitResume.indexOf("persistPermissionRecoveryGate()")
        val ensureIndex = explicitResume.indexOf("ensurePermissionsThenStart()")
        assertTrue(clearIndex < persistIndex && persistIndex < ensureIndex)
        assertTrue(
            Regex(Regex.escape("acknowledgeExplicitResume()")).findAll(source).count() == 1,
        )
        assertTrue(
            Regex(
                Regex.escape("resumePermissionRecoveryFromExplicitUserAction()"),
            ).findAll(source).count() == 3,
        )
    }

    @Test
    fun awaitingPermissionRecoveryUsesTheReachableExternalResumeControl() {
        val externalButtonMarker = "startupCapabilityConfirmButton = Button(this).apply"
        val externalButtonEnd = "permissionDenialPanel = LinearLayout(this).apply"
        val clickMarker = "setOnClickListener {"
        val normalClickMarker = "val session = walkSessionLifecycle.snapshot()"
        val refreshButtonMarker = "startupCapabilityConfirmButton.apply {"
        val refreshButtonEnd = "updatePriorityUserOnboardingUi(decision)"
        val runtimeVisibilityMarker = "runtimeControls.visibility ="
        listOf(
            externalButtonMarker,
            externalButtonEnd,
            clickMarker,
            normalClickMarker,
            refreshButtonMarker,
            refreshButtonEnd,
            runtimeVisibilityMarker,
        ).forEach { marker ->
            assertTrue("Missing exact source marker: $marker", source.contains(marker))
        }

        val externalButton = source
            .substringAfter(externalButtonMarker)
            .substringBefore(externalButtonEnd)
        val explicitClick =
            functionBlock("private fun handleStartupCapabilityConfirmAction()")
        val refresh = functionBlock("private fun refreshStartupCapabilityUi()")
        val refreshButton = refresh
            .substringAfter(refreshButtonMarker)
            .substringBefore(refreshButtonEnd)
        val runtimeVisibility =
            functionBlock("private fun updateFirstRunOnboardingUi()")
                .substringAfter(runtimeVisibilityMarker)
        val automaticRecheck =
            functionBlock("private fun completePermissionRecoveryRecheckIfPossible()")
        val automaticAdvance =
            functionBlock("private fun maybeAdvanceWalkSessionAfterCapabilityCheck()")
        val restoredGate =
            functionBlock("private fun restorePermissionRecoveryGateFromPrefs()")
        val pureRender =
            functionBlock("private fun renderAwaitingExplicitResumeControl()")

        assertTrue(explicitClick.contains("resumePermissionRecoveryFromExplicitUserAction()"))
        assertTrue(
            explicitClick.contains(
                "if (resumePermissionRecoveryFromExplicitUserAction()) return",
            ),
        )
        assertTrue(
            explicitClick.indexOf("startupCapabilityRetryRequiresUserAction = false") <
                explicitClick.indexOf("resumePermissionRecoveryFromExplicitUserAction()"),
        )
        assertTrue(
            explicitClick.indexOf("walkSessionResumeRetryRequiresUserAction = false") <
                explicitClick.indexOf("resumePermissionRecoveryFromExplicitUserAction()"),
        )
        assertTrue(refreshButton.contains("PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME"))
        assertTrue(refreshButton.contains("renderAwaitingExplicitResumeControl()"))
        assertTrue(pureRender.contains("startupCapabilityConfirmButton.visibility = View.VISIBLE"))
        assertTrue(pureRender.contains("startupCapabilityConfirmButton.isEnabled = true"))
        assertTrue(pureRender.contains("startupCapabilityConfirmButton.text = \"보행 안내 다시 시작\""))
        assertTrue(pureRender.contains("runtimeControls.visibility = View.GONE"))
        assertTrue(runtimeVisibility.contains("!permissionRecoveryGate.blocksAutomaticResourceStart"))
        assertTrue(runtimeVisibility.contains("View.GONE"))

        listOf(refresh, automaticRecheck, restoredGate).forEach { automatic ->
            assertFalse(automatic.contains("acknowledgeExplicitResume()"))
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
        ).forEach { forbiddenCall ->
            assertFalse(pureRender.contains(forbiddenCall))
        }
        assertFalse(refresh.contains("completePermissionRecoveryRecheckIfPossible()"))
        assertTrue(automaticAdvance.contains("if (createdConfirmationToken)"))
        assertTrue(automaticAdvance.contains("refreshStartupCapabilityUi()"))
        assertTrue(automaticAdvance.contains("completePermissionRecoveryRecheckIfPossible()"))
        assertFalse(automaticRecheck.contains("refreshStartupCapabilityUi()"))
        assertFalse(automaticRecheck.contains("maybeAdvanceWalkSessionAfterCapabilityCheck()"))
        assertTrue(automaticAdvance.contains("permissionRecoveryGate.blocksAutomaticResourceStart"))
        assertFalse(source.contains("maybeAdvanceFirstRunOnboarding"))
        assertTrue(
            restoredGate.contains(
                "persistedState == PermissionRecoveryGateState.CLEAR",
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
            assertTrue("Missing exact source marker: $marker", source.contains(marker))
        }

        val create = functionBlock(createSignature)
        val restore = functionBlock(restoreSignature)
        val nonClearRestoreMarker = "val affected = stepLengthPrefs"
        assertTrue(restore.contains(nonClearRestoreMarker))
        val nonClearRestore = restore.substringAfter(nonClearRestoreMarker)
        val advance = functionBlock(advanceSignature)
        val activation =
            functionBlock("private fun completeGatewayWalkActivation(")
        val permissionResult = functionBlock(permissionResultSignature)
        val automaticSlices = listOf(
            functionBlock(resumeSignature),
            functionBlock(cameraResultSignature),
            functionBlock(navigationResultSignature),
            functionBlock(voiceResultSignature),
            functionBlock(refreshSignature),
            advance,
            functionBlock(recheckSignature),
            nonClearRestore,
        )
        listOf(
            "permissionRecoveryGate = PermissionRecoveryGate()",
            "resumePermissionRecoveryFromExplicitUserAction()",
            "acknowledgeExplicitResume()",
            "ensurePermissionsThenStart()",
        ).forEach { forbidden ->
            automaticSlices.forEach { automatic ->
                assertFalse(automatic.contains(forbidden))
            }
        }
        assertTrue(permissionResult.contains("permissionRecoveryGate = PermissionRecoveryGate()"))
        assertFalse(permissionResult.contains("ensurePermissionsThenStart()"))
        assertTrue(nonClearRestore.contains("PermissionRecoveryGate.restoredBlocked(affected)"))

        val guardMarker =
            "if (permissionRecoveryGate.blocksAutomaticResourceStart) {"
        val recheckCallMarker = "completePermissionRecoveryRecheckIfPossible()"
        val blockedReturnMarker =
            "if (permissionRecoveryGate.blocksAutomaticResourceStart) return"
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
            "permissionRecoveryGate.blocksAutomaticResourceStart"
        val startRequestedMarker = "WalkSessionEvent.StartRequested"
        val activateMarker = "activateWalkSessionRuntime()"
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

        val restoreCallMarker = "restorePermissionRecoveryGateFromPrefs()"
        val fieldLogBlockMarker = "fieldSessionLog.blockActiveSessionRestore()"
        val probeMarker = "startupCapabilityProbe = AndroidStartupCapabilityProbe(this) {"
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
        val rights = functionBlock("private fun openPrivacyRightsPage()")

        assertTrue(rights.contains("Intent.ACTION_VIEW"))
        assertTrue(rights.contains("/privacy/rights"))
        assertFalse(rights.contains("gatewaySessionOrNull"))
        assertFalse(rights.contains("requireReporterUserId"))
        assertTrue(source.contains("개인정보 열람·동의 철회·서버 자료 삭제 요청"))
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing function: $signature" }
        val next = source.indexOf("\n    private fun ", start + signature.length)
        return if (next < 0) source.substring(start) else source.substring(start, next)
    }
}
