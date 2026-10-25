package kr.co.hanium.dreamup.walksafe.session.permission

import android.Manifest
import android.content.pm.PackageManager
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PermissionFlowControllerTest {
    @After fun clearSession() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }

    @Test fun constructionDoesNotQueryOrLaunchAnyPermissionWork() {
        val f = PermissionFlowTestFixture()
        assertTrue(f.events.isEmpty())
        assertTrue(f.launches.isEmpty())
        assertEquals(PermissionRecoveryGateState.CLEAR, f.flow.permissionRecoveryGate.state)
    }

    @Test fun deniedFirstRunAdmissionDoesNotAllocateOrLaunch() {
        val f = PermissionFlowTestFixture(); f.firstRunReady = false
        assertNull(f.flow.requestPermissionsWithLease(arrayOf(Manifest.permission.CAMERA), PermissionRequestPurpose.WALK_SESSION))
        assertTrue(f.events.isEmpty()); assertTrue(f.launches.isEmpty())
    }

    @Test fun restoredLaunchAttemptPreventsRepeatingAnInitialPermissionPrompt() {
        val f = PermissionFlowTestFixture(); f.camera = false; f.completed = true
        f.flow.restoreInitialAppPermissionReRequestedThisLaunch(true)
        assertTrue(f.events.isEmpty())
        f.flow.requestInitialAppEntryPermissionsIfNeeded()
        assertTrue(f.launches.isEmpty())
        assertTrue(f.flow.initialAppPermissionReRequestedThisLaunch)
    }

    @Test fun developmentRequestOwnsWalkResultWithoutConsumingTheNormalWalkAttempt() {
        val f = PermissionFlowTestFixture()
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val code = f.flow.requestDevelopmentWalkPermissionsWithLease(permissions)!!
        assertEquals(listOf("persist:REQUEST_PENDING", "blockFieldRestore", "launch:$code"), f.events)
        assertEquals(code to permissions.toList(), f.launches.single())
        assertEquals(setOf(ObservedPermission.CAMERA, ObservedPermission.MICROPHONE), f.flow.permissionRecoveryGate.affectedPermissions)
        assertEquals(code, f.flow.walkSessionPermissionRequestCode)
        assertTrue(f.flow.walkSessionPermissionRequestInFlight)
        assertFalse(f.flow.walkSessionPermissionRequestAttempted)
    }

    @Test fun developmentAdmissionAndLaunchFailurePreserveTheExistingFlagContract() {
        val denied = PermissionFlowTestFixture(); denied.firstRunReady = false
        assertNull(denied.flow.requestDevelopmentWalkPermissionsWithLease(arrayOf(Manifest.permission.CAMERA)))
        assertTrue(denied.flow.walkSessionPermissionRequestInFlight)
        assertFalse(denied.flow.walkSessionPermissionRequestAttempted)
        assertTrue(denied.events.isEmpty())

        val failed = PermissionFlowTestFixture(); failed.launchFailure = SecurityException("fixture")
        assertThrows(SecurityException::class.java) {
            failed.flow.requestDevelopmentWalkPermissionsWithLease(arrayOf(Manifest.permission.CAMERA))
        }
        assertTrue(failed.flow.walkSessionPermissionRequestInFlight)
        assertNull(failed.flow.walkSessionPermissionRequestCode)
        assertFalse(failed.flow.walkSessionPermissionRequestAttempted)
        assertFalse(failed.flow.ownsRequest(failed.launches.single().first))
        assertEquals(PermissionRecoveryGateState.BLOCKED, failed.flow.permissionRecoveryGate.state)
    }

    @Test fun privacyInspectionDoesNotCreateRecoveryForAClearGate() {
        val f = PermissionFlowTestFixture()
        f.flow.requirePermissionRecoveryRecheckAfterPrivacyInspection()
        assertEquals(PermissionRecoveryGateState.CLEAR, f.flow.permissionRecoveryGate.state)
        assertTrue(f.events.isEmpty())
    }

    @Test fun privacyInspectionRequiresRecheckWithTheExistingBindingAndPersistsBeforeAnyNewAction() {
        val f = PermissionFlowTestFixture(); f.prepareRecovery()
        val binding = f.flow.permissionRecoveryGate.recheckBinding
        assertNotNull(binding)
        val affected = f.flow.permissionRecoveryGate.affectedPermissions
        f.flow.requirePermissionRecoveryRecheckAfterPrivacyInspection()
        assertEquals(PermissionRecoveryGateState.RECHECK_REQUIRED, f.flow.permissionRecoveryGate.state)
        assertEquals(binding, f.flow.permissionRecoveryGate.recheckBinding)
        assertEquals(affected, f.flow.permissionRecoveryGate.affectedPermissions)
        assertEquals(f.flow.permissionRecoveryGate, f.storedGate)
        assertEquals(listOf("persist:RECHECK_REQUIRED"), f.events)
    }

    @Test fun walkRequestPersistsPendingAndBlocksRestoreBeforeOsLaunch() {
        val f = PermissionFlowTestFixture()
        val code = f.flow.requestPermissionsWithLease(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), PermissionRequestPurpose.WALK_SESSION)!!
        assertEquals(listOf("persist:REQUEST_PENDING", "blockFieldRestore", "launch:$code"), f.events)
        assertEquals(setOf(ObservedPermission.PRECISE_LOCATION), f.flow.permissionRecoveryGate.affectedPermissions)
        assertTrue(f.flow.ownsRequest(code))
    }

    @Test fun aNewerRequestOfSamePurposeRejectsOlderResultWithoutClearingNewWalkFlags() {
        val f = PermissionFlowTestFixture()
        val old = f.flow.requestDevelopmentWalkPermissionsWithLease(arrayOf(Manifest.permission.CAMERA))!!
        val newer = f.flow.requestDevelopmentWalkPermissionsWithLease(arrayOf(Manifest.permission.CAMERA))!!
        f.events.clear()
        f.flow.onRequestPermissionsResult(old, arrayOf(Manifest.permission.CAMERA), intArrayOf(0))
        assertTrue(f.events.isEmpty()); assertTrue(f.flow.ownsRequest(newer)); assertFalse(f.flow.ownsRequest(old))
        assertEquals(newer, f.flow.walkSessionPermissionRequestCode); assertTrue(f.flow.walkSessionPermissionRequestInFlight)
        f.flow.onRequestPermissionsResult(newer, arrayOf(Manifest.permission.CAMERA), intArrayOf(0))
        assertNull(f.flow.walkSessionPermissionRequestCode); assertFalse(f.flow.walkSessionPermissionRequestInFlight)
        assertFalse(f.flow.ownsRequest(newer)); assertTrue(f.events.contains("refreshStartup"))
    }

    @Test fun canceledRequestCannotHandleALateOsCallback() {
        val f = PermissionFlowTestFixture()
        val code = f.flow.requestPermissionsWithLease(arrayOf(Manifest.permission.CAMERA), PermissionRequestPurpose.WALK_SESSION)!!
        f.flow.cancelRequest(code); f.events.clear()
        f.flow.onRequestPermissionsResult(code, arrayOf(Manifest.permission.CAMERA), intArrayOf(0))
        assertTrue(f.events.isEmpty())
    }

    @Test fun changedWalkEpochRejectsResult() {
        val f = PermissionFlowTestFixture()
        val code = f.flow.requestPermissionsWithLease(arrayOf(Manifest.permission.CAMERA), PermissionRequestPurpose.WALK_SESSION)!!
        val previousEpoch = f.lifecycle.snapshot().epoch
        f.lifecycle.handle(WalkSessionEvent.NewWalkRequested); f.events.clear()
        assertNotEquals(previousEpoch, f.lifecycle.snapshot().epoch)
        f.flow.onRequestPermissionsResult(code, arrayOf(Manifest.permission.CAMERA), intArrayOf(0))
        assertTrue(f.events.isEmpty()); assertFalse(f.flow.ownsRequest(code))
    }

    @Test fun changedOnboardingLeaseRejectsResult() {
        val f = PermissionFlowTestFixture()
        val code = f.flow.requestDevelopmentWalkPermissionsWithLease(arrayOf(Manifest.permission.CAMERA))!!
        f.firstToken++; f.events.clear()
        f.flow.onRequestPermissionsResult(code, arrayOf(Manifest.permission.CAMERA), intArrayOf(0))
        assertTrue(f.events.isEmpty()); assertNull(f.flow.walkSessionPermissionRequestCode); assertFalse(f.flow.walkSessionPermissionRequestInFlight)
    }

    @Test fun postLoginBindingMustRemainLiveAtCallback() {
        val f = PermissionFlowTestFixture(); f.preparePostLoginRequest()
        val code = f.flow.requestPermissionsWithLease(arrayOf(Manifest.permission.CAMERA), PermissionRequestPurpose.POST_LOGIN_DEVICE_CHECK)!!
        f.postBindingLive = false; f.events.clear()
        f.flow.onRequestPermissionsResult(code, arrayOf(Manifest.permission.CAMERA), intArrayOf(0))
        assertTrue(f.events.isEmpty()); assertFalse(f.flow.ownsRequest(code))
    }

    @Test fun currentPostLoginResultIsDeliveredWithTheCapturedBinding() {
        val f = PermissionFlowTestFixture(); f.preparePostLoginRequest()
        val code = f.flow.requestPermissionsWithLease(arrayOf(Manifest.permission.CAMERA), PermissionRequestPurpose.POST_LOGIN_DEVICE_CHECK)!!
        f.events.clear(); f.flow.onRequestPermissionsResult(code, arrayOf(Manifest.permission.CAMERA), intArrayOf(0))
        assertEquals("postLogin:${f.actor}", f.events.first())
    }

    @Test fun runtimeCallbackRequiresActiveCurrentEpoch() {
        val f = PermissionFlowTestFixture()
        val old = f.flow.requestPermissionsWithLease(arrayOf(Manifest.permission.CAMERA), PermissionRequestPurpose.RUNTIME_CAMERA)!!
        f.events.clear(); f.flow.onRequestPermissionsResult(old, arrayOf(Manifest.permission.CAMERA), intArrayOf(0))
        assertTrue(f.events.isEmpty())
        f.activateWalk()
        val live = f.flow.requestPermissionsWithLease(arrayOf(Manifest.permission.CAMERA), PermissionRequestPurpose.RUNTIME_CAMERA)!!
        f.events.clear(); f.flow.onRequestPermissionsResult(live, arrayOf(Manifest.permission.CAMERA), intArrayOf(0))
        assertEquals("runtimeCameraResult", f.events.first())
    }

    @Test fun initialCallbackDoesNotDependOnOnboardingTokenButDoesDependOnPrivacyLifetime() {
        val f = PermissionFlowTestFixture(); f.firstRunReady = false
        val code = f.flow.requestPermissionsWithLease(arrayOf(Manifest.permission.CAMERA), PermissionRequestPurpose.INITIAL_APP_ENTRY)!!
        f.firstToken++; f.events.clear(); f.flow.onRequestPermissionsResult(code, arrayOf(Manifest.permission.CAMERA), intArrayOf(0))
        assertTrue(f.events.contains("saveInitialCompleted"))
        val later = f.flow.requestPermissionsWithLease(arrayOf(Manifest.permission.CAMERA), PermissionRequestPurpose.INITIAL_APP_ENTRY)!!
        f.privacyDestroyed = true; f.events.clear(); f.flow.onRequestPermissionsResult(later, arrayOf(Manifest.permission.CAMERA), intArrayOf(0))
        assertTrue(f.events.isEmpty())
    }

    @Test fun thrownAndroidRequestRemovesLeaseAndEntersPersistedRecovery() {
        val f = PermissionFlowTestFixture(); f.launchFailure = SecurityException("fixture denied")
        assertThrows(SecurityException::class.java) { f.flow.requestPermissionsWithLease(arrayOf(Manifest.permission.CAMERA), PermissionRequestPurpose.WALK_SESSION) }
        val code = f.launches.single().first
        assertFalse(f.flow.ownsRequest(code)); assertEquals(PermissionRecoveryGateState.BLOCKED, f.flow.permissionRecoveryGate.state)
        assertTrue(f.events.indexOf("invalidateSpeech") < f.events.indexOf("persist:BLOCKED"))
        assertTrue(f.events.indexOf("persist:BLOCKED") < f.events.indexOf("denial"))
    }

    @Test fun selectedRequestUsesFreshObservationDeduplicatesAndRetainsAttemptAfterException() {
        val f = PermissionFlowTestFixture(); var observed = 0; var sent: List<String>? = null
        assertThrows(IllegalStateException::class.java) {
            f.flow.requestSelectedWalkSessionPermissions({ observed++; f.flow.currentObservedPermissionSnapshot() },
                { listOf(Manifest.permission.CAMERA, Manifest.permission.CAMERA) },
                { sent = it.toList(); throw IllegalStateException("fixture") })
        }
        assertEquals(1, observed); assertEquals(listOf(Manifest.permission.CAMERA), sent)
        assertTrue(f.flow.walkSessionPermissionRequestAttempted); assertFalse(f.flow.walkSessionPermissionRequestInFlight)
        assertFalse(f.flow.requestSelectedWalkSessionPermissions({ error("not called") }, { error("not called") }, { error("not called") }))
    }

    @Test fun unknownPermissionAnswersAreNotExplainedAsRequestedPermissions() {
        val f = PermissionFlowTestFixture(); f.microphone = false
        val code = f.flow.requestPermissionsWithLease(arrayOf(Manifest.permission.RECORD_AUDIO), PermissionRequestPurpose.WALK_SESSION)!!
        f.events.clear(); f.flow.onRequestPermissionsResult(code, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), intArrayOf(-1, -1))
        assertEquals(listOf("rationale:${Manifest.permission.RECORD_AUDIO}"), f.events.filter { it.startsWith("rationale:") })
        assertTrue(f.denials.last().contains("마이크 권한이 필요합니다.")); assertFalse(f.denials.last().contains("카메라 권한이 필요합니다."))
    }

    @Test fun mismatchedAnswerArraysDoNotInventRequestExplanation() {
        val f = PermissionFlowTestFixture()
        val code = f.flow.requestPermissionsWithLease(arrayOf(Manifest.permission.CAMERA), PermissionRequestPurpose.WALK_SESSION)!!
        f.flow.onRequestPermissionsResult(code, arrayOf(Manifest.permission.CAMERA), intArrayOf())
        assertTrue(f.denials.isEmpty()); assertFalse(f.events.any { it.startsWith("rationale:") })
    }

    @Test fun initialGrantResultRechecksCurrentOsPermissionInsteadOfTrustingResultArray() {
        val f = PermissionFlowTestFixture(); f.camera = false
        val code = f.flow.requestPermissionsWithLease(arrayOf(Manifest.permission.CAMERA), PermissionRequestPurpose.INITIAL_APP_ENTRY)!!
        f.flow.onRequestPermissionsResult(code, arrayOf(Manifest.permission.CAMERA), intArrayOf(PackageManager.PERMISSION_GRANTED))
        assertTrue(f.flow.initialAppPermissionExitRequired); assertTrue(f.events.any { it.startsWith("showExit:") })
    }

    @Test fun failedInitialAttemptPersistenceNeverLaunchesAndroidRequest() {
        val f = PermissionFlowTestFixture(); f.camera = false; f.saveStarted = false
        f.flow.requestInitialAppEntryPermissionsIfNeeded()
        assertTrue(f.flow.initialAppPermissionReRequestedThisLaunch); assertTrue(f.launches.isEmpty())
        assertEquals(listOf("saveInitialStarted", "status:앱 권한 요청 준비 실패"), f.events)
    }

    @Test fun requiredExitCancellationAndSafetyStopPrecedeShowingAndDuplicateDialogIsAvoided() {
        val f = PermissionFlowTestFixture(); f.runtimeIsActive = true
        f.flow.showRequiredAppPermissionExit(setOf(Manifest.permission.CAMERA))
        assertEquals(listOf("cancelPrewalk", "safetyStop:required_app_permission_denied", "showExit:${Manifest.permission.CAMERA}"), f.events)
        f.events.clear(); f.flow.showRequiredAppPermissionExit(setOf(Manifest.permission.CAMERA))
        assertFalse(f.events.any { it.startsWith("showExit:") }); assertTrue(f.flow.initialAppPermissionExitRequired)
    }

    @Test fun finishingOrExplicitDebugBypassDoesNotShowPermissionExitDialog() {
        val f = PermissionFlowTestFixture(); f.isFinishing = true
        f.flow.showRequiredAppPermissionExit(setOf(Manifest.permission.CAMERA)); assertTrue(f.events.isEmpty())
        f.isFinishing = false; f.bypass = true
        f.flow.showRequiredAppPermissionExit(setOf(Manifest.permission.CAMERA))
        assertFalse(f.flow.initialAppPermissionExitRequired); assertEquals(1, f.events.size); assertTrue(f.events.single().startsWith("navigation:"))
    }

    @Test fun completeRecheckPersistsAwaitingButDoesNotAutomaticallyStart() {
        val f = PermissionFlowTestFixture(); f.publishGateway()
        f.flow.enterPermissionRecoveryBarrier(setOf(ObservedPermission.CAMERA), "test"); f.events.clear()
        f.flow.completePermissionRecoveryRecheckIfPossible()
        assertEquals(PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME, f.flow.permissionRecoveryGate.state)
        assertTrue(f.events.indexOf("persist:AWAITING_EXPLICIT_RESUME") < f.events.indexOf("resumeControl"))
        assertFalse(f.events.contains("startAfterRecovery"))
    }

    @Test fun explicitRecheckStartsPreparationWhileKeepingTheRecoveryBarrier() {
        val f = PermissionFlowTestFixture(); f.publishGateway(); f.block = "환경과 장착 미확인"
        f.flow.enterPermissionRecoveryBarrier(setOf(ObservedPermission.CAMERA), "test")
        f.flow.completePermissionRecoveryRecheckIfPossible(); f.events.clear()
        f.flow.onPermissionDenialConfirmed()
        assertTrue(f.events.contains("recheckPrewalkReadiness"))
        assertEquals(PermissionRecoveryGateState.RECHECK_REQUIRED, f.flow.permissionRecoveryGate.state)
        assertTrue(f.flow.permissionRecoveryGate.blocksAutomaticResourceStart)
        assertFalse(f.events.contains("startAfterRecovery"))
    }

    @Test fun explicitRecheckDoesNotPrepareWithRevokedPermissionsOrBackgroundOwnership() {
        for (background in listOf(false, true)) {
            val f = PermissionFlowTestFixture(); f.publishGateway(); f.block = "환경과 장착 미확인"
            f.flow.enterPermissionRecoveryBarrier(setOf(ObservedPermission.CAMERA), "test")
            f.flow.completePermissionRecoveryRecheckIfPossible(); f.events.clear()
            if (background) f.foreground = false else f.camera = false
            f.flow.onPermissionDenialConfirmed()
            assertFalse(f.events.contains("recheckPrewalkReadiness"))
            assertTrue(f.flow.permissionRecoveryGate.blocksAutomaticResourceStart)
            assertFalse(f.events.contains("startAfterRecovery"))
        }
    }

    @Test fun explicitResumeWithCurrentBindingPersistsClearBeforeRuntimeStart() {
        val f = PermissionFlowTestFixture(); f.prepareRecovery()
        assertTrue(f.flow.resumePermissionRecoveryFromExplicitUserAction())
        assertEquals(PermissionRecoveryGateState.CLEAR, f.flow.permissionRecoveryGate.state)
        assertEquals(listOf("persist:CLEAR", "startAfterRecovery"), f.events)
    }

    @Test fun permissionRevokedAfterRecheckCannotBeReleasedByResumeTap() {
        val f = PermissionFlowTestFixture(); f.prepareRecovery(); f.microphone = false
        assertTrue(f.flow.resumePermissionRecoveryFromExplicitUserAction())
        assertTrue(f.flow.permissionRecoveryGate.blocksAutomaticResourceStart)
        assertFalse(f.events.contains("startAfterRecovery")); assertTrue(f.flow.permissionRecoveryGate.affectedPermissions.contains(ObservedPermission.MICROPHONE))
    }

    @Test fun accountOrGatewayGenerationChangeCannotReleaseOldResumeBinding() {
        val f = PermissionFlowTestFixture(); f.prepareRecovery()
        val old = f.flow.permissionRecoveryGate.recheckBinding
        f.actor = "actor_0123456789abcdef0123456789abcdef"; f.publishGateway()
        assertTrue(f.flow.resumePermissionRecoveryFromExplicitUserAction())
        assertTrue(f.flow.permissionRecoveryGate.blocksAutomaticResourceStart); assertFalse(f.events.contains("startAfterRecovery"))
        assertNotEquals(old, f.flow.permissionRecoveryGate.recheckBinding)
    }

    @Test fun readinessChangeOnResumeTapCannotReleaseGate() {
        val f = PermissionFlowTestFixture(); f.prepareRecovery(); f.block = "device changed"
        assertTrue(f.flow.resumePermissionRecoveryFromExplicitUserAction())
        assertTrue(f.flow.permissionRecoveryGate.blocksAutomaticResourceStart); assertFalse(f.events.contains("startAfterRecovery"))
    }

    @Test fun bindingChangeDuringFullRecheckDoesNotAdmitItsOldResult() {
        val f = PermissionFlowTestFixture(); f.publishGateway(); f.flow.enterPermissionRecoveryBarrier(setOf(ObservedPermission.CAMERA), "test")
        f.onBlockReason = { f.lifecycle.handle(WalkSessionEvent.NewWalkRequested) }
        f.flow.completePermissionRecoveryRecheckIfPossible()
        assertEquals(PermissionRecoveryGateState.RECHECK_REQUIRED, f.flow.permissionRecoveryGate.state)
        assertFalse(f.events.contains("startAfterRecovery"))
    }

    @Test fun awaitingAcknowledgmentOnlyFocusesLiveResumeControlAndDoesNotClearGate() {
        val f = PermissionFlowTestFixture(); f.prepareRecovery()
        f.flow.onPermissionDenialConfirmed()
        assertEquals(PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME, f.flow.permissionRecoveryGate.state)
        assertTrue(checkNotNull(f.pendingFocus).invoke()); assertFalse(f.events.any { it.startsWith("persist:") })
        f.flow.requirePermissionRecoveryRecheckAfterPrivacyInspection()
        assertFalse(checkNotNull(f.pendingFocus).invoke())
    }

    @Test fun featureOnlyUnboundDenialAcknowledgmentKeepsItsExistingClearContract() {
        val f = PermissionFlowTestFixture()
        f.storedGate = PermissionRecoveryGate().blocked(setOf(ObservedPermission.MICROPHONE))
        f.flow.restorePermissionRecoveryGateFromPrefs()
        f.flow.onPermissionDenialConfirmed()
        assertEquals(PermissionRecoveryGateState.CLEAR, f.flow.permissionRecoveryGate.state)
        assertTrue(f.events.indexOf("blockFieldRestore") < f.events.indexOf("persist:CLEAR"))
        assertFalse(f.events.contains("startAfterRecovery"))
    }

    @Test fun corePermissionLossStopsResourcesBeforeWholeWalkSafetyStopAndNeverRestarts() {
        val f = PermissionFlowTestFixture(); f.activateWalk(); f.camera = false; f.location = false; f.routeIsActive = true
        f.flow.applyObservedPermissionStateChange("revoked")
        assertEquals(listOf("cancelCameraHandoff", "stopDepth", "stopCameraFallback", "cancelDestinationLocation", "clearReportLocationRequest", "stopLocation", "cancelRouteRequest", "resetRoute"), f.events.take(8))
        val stopped = f.events.indexOf("safetyStop:core_guidance_permissions_unavailable")
        assertTrue(stopped > 7); assertTrue(stopped < f.events.indexOf("persist:BLOCKED"))
        assertFalse(f.events.contains("refreshStartup")); assertFalse(f.events.contains("startLocationIfAllowed"))
    }

    @Test fun startupRefreshSafetyStopPreventsCameraOrNavigationContinuation() {
        val f = PermissionFlowTestFixture(); f.activateWalk(); f.probeReady = true
        f.afterRefresh = { f.lifecycle.handle(WalkSessionEvent.SafetyStopRequested) }
        f.flow.applyObservedPermissionStateChange("changed")
        assertTrue(f.events.contains("refreshStartup")); assertFalse(f.events.contains("startNavigationServices"))
        assertFalse(f.events.contains("startWithoutCamera")); assertFalse(f.events.contains("startHandsFreeIfAllowed"))
        assertEquals("startLocationIfAllowed", f.events.last())
    }

    @Test fun missingMicrophoneStopsOnlyItsResourcesAndRetainsDependencyPolicyDecision() {
        val f = PermissionFlowTestFixture(); f.activateWalk(); f.microphone = false; f.deviceRestrictions = true
        f.flow.applyObservedPermissionStateChange("microphone")
        assertTrue(f.events.contains("stopHandsFree")); assertTrue(f.events.contains("cancelVoiceRecognition"))
        assertFalse(f.events.any { it.startsWith("safetyStop:") }); assertFalse(f.events.contains("stopDepth"))
        assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
    }

    @Test fun settingsPreparationPersistsBarrierBeforeReturningToAndroidLauncher() {
        val f = PermissionFlowTestFixture(); f.camera = false
        f.flow.prepareForAppSettings()
        assertEquals(PermissionRecoveryGateState.SETTINGS_PENDING, f.flow.permissionRecoveryGate.state)
        assertEquals("persist:SETTINGS_PENDING", f.events.last())
        assertTrue(f.events.indexOf("persist:BLOCKED") < f.events.indexOf("persist:SETTINGS_PENDING"))
    }

    @Test fun backgroundWalkCallbackIsRejectedEvenWhenEpochAndOnboardingTokenMatch() {
        val f = PermissionFlowTestFixture()
        val code = f.flow.requestPermissionsWithLease(arrayOf(Manifest.permission.CAMERA), PermissionRequestPurpose.WALK_SESSION)!!
        f.lifecycle.handle(WalkSessionEvent.EnteredBackground); f.events.clear()
        f.flow.onRequestPermissionsResult(code, arrayOf(Manifest.permission.CAMERA), intArrayOf(0))
        assertTrue(f.events.isEmpty())
    }

    @Test fun replacingGatewaySessionForSameActorDoesNotReleaseEarlierResumeBinding() {
        val f = PermissionFlowTestFixture(); f.prepareRecovery()
        val earlier = f.flow.permissionRecoveryGate.recheckBinding!!
        f.publishGateway()
        assertTrue(f.flow.resumePermissionRecoveryFromExplicitUserAction())
        assertTrue(f.flow.permissionRecoveryGate.blocksAutomaticResourceStart)
        assertFalse(f.events.contains("startAfterRecovery"))
        assertNotEquals(earlier.gatewaySessionGeneration, f.flow.permissionRecoveryGate.recheckBinding!!.gatewaySessionGeneration)
    }

    @Test fun missingGatewaySessionCannotCompleteARecoveryRecheck() {
        val f = PermissionFlowTestFixture()
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
        f.flow.enterPermissionRecoveryBarrier(setOf(ObservedPermission.CAMERA), "missing-session")
        f.flow.completePermissionRecoveryRecheckIfPossible()
        assertTrue(f.flow.permissionRecoveryGate.blocksAutomaticResourceStart)
        assertNotEquals(PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME, f.flow.permissionRecoveryGate.state)
        assertFalse(f.events.contains("startAfterRecovery"))
    }
}
