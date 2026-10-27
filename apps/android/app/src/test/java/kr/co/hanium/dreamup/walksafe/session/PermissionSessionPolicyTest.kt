package kr.co.hanium.dreamup.walksafe.session

import kr.co.hanium.dreamup.walksafe.network.MobileNetworkPreference
import kr.co.hanium.dreamup.walksafe.network.ActiveNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.AndroidNetworkTransferPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionSessionPolicyTest {
    @Test
    fun exactActorMustOwnTheActiveAuthentication() {
        val policy = PermissionSessionPolicy()

        assertFalse(policy.isAuthenticatedFor("walker-1"))
        policy.rememberActor("walker-1")
        assertFalse(policy.isAuthenticatedFor("walker-1"))
        policy.authenticated("walker-1")
        assertTrue(policy.isAuthenticatedFor("walker-1"))
        assertTrue(policy.isAuthenticatedFor(" walker-1 "))
        assertFalse(policy.isAuthenticatedFor("walker-2"))
        policy.authenticationExpired()
        assertFalse(policy.isAuthenticatedFor("walker-1"))
    }

    @Test
    fun explicitLogoutClearsTheFormerAccountsEffectiveConsents() {
        val policy = PermissionSessionPolicy(
            PermissionSessionSnapshot(
                actorId = "walksafe-user",
                authentication = AuthenticationState.ACTIVE,
                rawCollectionConsentGranted = true,
                automaticReportConsentGranted = true,
                mobileNetworkPreference = MobileNetworkPreference.ALLOW_CELLULAR,
            ),
        )

        val after = policy.explicitLogout()

        assertNull(after.actorId)
        assertEquals(AuthenticationState.SIGNED_OUT, after.authentication)
        assertFalse(after.rawCollectionConsentGranted)
        assertFalse(after.automaticReportConsentGranted)
        assertEquals(MobileNetworkPreference.ALLOW_CELLULAR, after.mobileNetworkPreference)
    }

    @Test
    fun logoutThenAnotherActorDoesNotInheritAnyConsent() {
        val policy = authenticatedPolicyWithAllConsents()

        val loggedOut = policy.explicitLogout()

        assertEquals(
            PermissionSessionSnapshot(mobileNetworkPreference = MobileNetworkPreference.ALLOW_CELLULAR),
            loggedOut,
        )
        assertEquals(loggedOut, policy.snapshot())
        assertFalse(policy.isAuthenticatedFor("actor-A"))
        assertFalse(loggedOut.mayUseProtectedServerFeature)
        assertFalse(loggedOut.mayCreateAutomaticReport)
        assertFalse(loggedOut.mayReuseForTraining(TrainingSourceKind.WALK_RAW_DIAGNOSTIC))
        assertFalse(loggedOut.mayReuseForTraining(TrainingSourceKind.REPORT_ORIGINAL))

        val authenticatedB = policy.authenticated("actor-B")

        assertEquals(
            PermissionSessionSnapshot(
                actorId = "actor-B",
                authentication = AuthenticationState.ACTIVE,
                mobileNetworkPreference = MobileNetworkPreference.ALLOW_CELLULAR,
            ),
            authenticatedB,
        )
        assertEquals(authenticatedB, policy.snapshot())
        assertFalse(policy.isAuthenticatedFor("actor-A"))
        assertTrue(policy.isAuthenticatedFor("actor-B"))
        assertTrue(authenticatedB.mayUseProtectedServerFeature)
        assertFalse(authenticatedB.mayCreateAutomaticReport)
        assertFalse(authenticatedB.mayReuseForTraining(TrainingSourceKind.WALK_RAW_DIAGNOSTIC))
        assertFalse(authenticatedB.mayReuseForTraining(TrainingSourceKind.REPORT_ORIGINAL))
    }

    @Test
    fun directAuthenticationOfAnotherActorResetsEveryConsent() {
        val policy = authenticatedPolicyWithAllConsents()

        val authenticatedB = policy.authenticated("actor-B")

        assertEquals(
            PermissionSessionSnapshot(
                actorId = "actor-B",
                authentication = AuthenticationState.ACTIVE,
                mobileNetworkPreference = MobileNetworkPreference.ALLOW_CELLULAR,
            ),
            authenticatedB,
        )
        assertEquals(authenticatedB, policy.snapshot())
        assertFalse(policy.isAuthenticatedFor("actor-A"))
        assertTrue(policy.isAuthenticatedFor("actor-B"))
        assertTrue(authenticatedB.mayUseProtectedServerFeature)
        assertFalse(authenticatedB.mayCreateAutomaticReport)
        assertFalse(authenticatedB.mayReuseForTraining(TrainingSourceKind.WALK_RAW_DIAGNOSTIC))
        assertFalse(authenticatedB.mayReuseForTraining(TrainingSourceKind.REPORT_ORIGINAL))
    }

    @Test
    fun rememberActorPreservesSameOwnerButResetsChangedOwner() {
        val policy = authenticatedPolicyWithAllConsents()
        val grantedA = policy.snapshot()

        val rememberedA = policy.rememberActor(" actor-A ")

        assertEquals(
            grantedA.copy(authentication = AuthenticationState.REAUTHENTICATION_REQUIRED),
            rememberedA,
        )
        assertEquals(rememberedA, policy.snapshot())
        assertFalse(policy.isAuthenticatedFor("actor-A"))
        assertFalse(rememberedA.mayUseProtectedServerFeature)

        val rememberedB = policy.rememberActor(" actor-B ")

        assertEquals(
            PermissionSessionSnapshot(
                actorId = "actor-B",
                authentication = AuthenticationState.REAUTHENTICATION_REQUIRED,
                mobileNetworkPreference = MobileNetworkPreference.ALLOW_CELLULAR,
            ),
            rememberedB,
        )
        assertEquals(rememberedB, policy.snapshot())
        assertFalse(policy.isAuthenticatedFor("actor-A"))
        assertFalse(policy.isAuthenticatedFor("actor-B"))
        assertFalse(rememberedB.mayUseProtectedServerFeature)
        assertFalse(rememberedB.mayCreateAutomaticReport)
        assertFalse(rememberedB.mayReuseForTraining(TrainingSourceKind.WALK_RAW_DIAGNOSTIC))
        assertFalse(rememberedB.mayReuseForTraining(TrainingSourceKind.REPORT_ORIGINAL))

        val authenticatedB = policy.authenticated("actor-B")

        assertEquals(
            PermissionSessionSnapshot(
                actorId = "actor-B",
                authentication = AuthenticationState.ACTIVE,
                mobileNetworkPreference = MobileNetworkPreference.ALLOW_CELLULAR,
            ),
            authenticatedB,
        )
        assertEquals(authenticatedB, policy.snapshot())
        assertFalse(policy.isAuthenticatedFor("actor-A"))
        assertTrue(policy.isAuthenticatedFor("actor-B"))
        assertTrue(authenticatedB.mayUseProtectedServerFeature)
        assertFalse(authenticatedB.mayCreateAutomaticReport)
        assertFalse(authenticatedB.mayReuseForTraining(TrainingSourceKind.WALK_RAW_DIAGNOSTIC))
        assertFalse(authenticatedB.mayReuseForTraining(TrainingSourceKind.REPORT_ORIGINAL))
    }

    @Test
    fun authenticationExpiryPreservesIndependentPreferencesAndRequiresReauthentication() {
        val policy = PermissionSessionPolicy(
            PermissionSessionSnapshot(
                actorId = "walksafe-user",
                authentication = AuthenticationState.ACTIVE,
                rawCollectionConsentGranted = true,
                automaticReportConsentGranted = false,
                mobileNetworkPreference = MobileNetworkPreference.WIFI_ONLY,
            ),
        )

        val after = policy.authenticationExpired()

        assertEquals("walksafe-user", after.actorId)
        assertEquals(AuthenticationState.REAUTHENTICATION_REQUIRED, after.authentication)
        assertTrue(after.rawCollectionConsentGranted)
        assertFalse(after.automaticReportConsentGranted)
        assertEquals(MobileNetworkPreference.WIFI_ONLY, after.mobileNetworkPreference)
        assertFalse(after.mayUseProtectedServerFeature)
    }

    @Test
    fun automaticReportConsentDoesNotDependOnRawDiagnosticConsent() {
        val policy = PermissionSessionPolicy()

        policy.authenticated("actor-A")
        policy.setAutomaticReportConsent(true)
        assertTrue(policy.snapshot().automaticReportConsentGranted)
        assertTrue(policy.snapshot().mayCreateAutomaticReport)

        policy.setRawCollectionConsent(true)
        assertTrue(policy.snapshot().mayCreateAutomaticReport)

        policy.setRawCollectionConsent(false)
        assertFalse(policy.snapshot().rawCollectionConsentGranted)
        assertTrue(policy.snapshot().automaticReportConsentGranted)
        assertTrue(policy.snapshot().mayCreateAutomaticReport)
    }

    @Test
    fun automaticReportRequiresActiveAuthenticationEvenWithStoredConsent() {
        val policy = PermissionSessionPolicy()

        policy.setAutomaticReportConsent(true)
        assertFalse(policy.snapshot().mayCreateAutomaticReport)

        policy.authenticated("actor-A")
        assertFalse(policy.snapshot().automaticReportConsentGranted)
        policy.setAutomaticReportConsent(true)
        assertTrue(policy.snapshot().mayCreateAutomaticReport)

        policy.authenticationExpired()
        assertFalse(policy.snapshot().mayCreateAutomaticReport)
    }

    @Test
    fun immediateLocalMobileWithdrawalBlocksCellularEvenBeforeServerReceiptRefresh() {
        val policy = PermissionSessionPolicy(
            PermissionSessionSnapshot(
                actorId = "walker-1",
                authentication = AuthenticationState.ACTIVE,
                mobileNetworkPreference = MobileNetworkPreference.ALLOW_CELLULAR,
            ),
        )
        policy.setMobileNetworkPreference(MobileNetworkPreference.WIFI_ONLY)
        val localPreference = policy.snapshot().mobileNetworkPreference

        assertFalse(
            AndroidNetworkTransferPolicy.isAllowed(
                localPreference,
                ActiveNetworkTransport.CELLULAR,
            ),
        )
        assertTrue(
            AndroidNetworkTransferPolicy.isAllowed(
                localPreference,
                ActiveNetworkTransport.WIFI,
            ),
        )
    }

    @Test
    fun locationRevocationStopsOnlyNavigationAndReportTransmission() {
        val decision = PermissionDependencyPolicy.evaluate(
            ObservedPermissionSnapshot(
                cameraGranted = true,
                preciseLocationGranted = false,
                microphoneGranted = true,
                activityRecognitionGranted = true,
            ),
        )

        assertFalse(decision.requiresWholeWalkSafetyStop)
        assertFalse(PermissionDependentFeature.METRIC_DISTANCE in decision.stoppedFeatures)
        assertTrue(PermissionDependentFeature.ROUTE_NAVIGATION in decision.stoppedFeatures)
        assertTrue(PermissionDependentFeature.REPORT_TRANSMISSION in decision.stoppedFeatures)
        assertFalse(PermissionDependentFeature.CAMERA_HAZARD_GUIDANCE in decision.stoppedFeatures)
        assertFalse(PermissionDependentFeature.VOICE_COMMAND in decision.stoppedFeatures)
    }

    @Test
    fun cameraRevocationStopsOnlyCameraGuidanceAndReportTransmission() {
        val decision = PermissionDependencyPolicy.evaluate(
            ObservedPermissionSnapshot(
                cameraGranted = false,
                preciseLocationGranted = true,
                microphoneGranted = true,
                activityRecognitionGranted = true,
            ),
        )

        assertFalse(decision.requiresWholeWalkSafetyStop)
        assertTrue(PermissionDependentFeature.CAMERA_HAZARD_GUIDANCE in decision.stoppedFeatures)
        assertTrue(PermissionDependentFeature.METRIC_DISTANCE in decision.stoppedFeatures)
        assertTrue(PermissionDependentFeature.REPORT_TRANSMISSION in decision.stoppedFeatures)
    }

    @Test
    fun losingCameraAndPreciseLocationStopsWholeWalkEvenWithMicrophone() {
        val decision = PermissionDependencyPolicy.evaluate(
            ObservedPermissionSnapshot(
                cameraGranted = false,
                preciseLocationGranted = false,
                microphoneGranted = true,
                activityRecognitionGranted = true,
            ),
        )

        assertTrue(decision.requiresWholeWalkSafetyStop)
        assertTrue(PermissionDependentFeature.CAMERA_HAZARD_GUIDANCE in decision.stoppedFeatures)
        assertTrue(PermissionDependentFeature.ROUTE_NAVIGATION in decision.stoppedFeatures)
    }

    @Test
    fun losingEveryObservedPermissionStopsWholeWalk() {
        val decision = PermissionDependencyPolicy.evaluate(
            ObservedPermissionSnapshot(
                cameraGranted = false,
                preciseLocationGranted = false,
                microphoneGranted = false,
                activityRecognitionGranted = false,
            ),
        )

        assertTrue(decision.requiresWholeWalkSafetyStop)
    }

    @Test
    fun losingMicrophoneAndStepTrackingKeepsAvailableCoreGuidance() {
        val decision = PermissionDependencyPolicy.evaluate(
            ObservedPermissionSnapshot(
                cameraGranted = true,
                preciseLocationGranted = true,
                microphoneGranted = false,
                activityRecognitionGranted = false,
            ),
        )

        assertFalse(decision.requiresWholeWalkSafetyStop)
        assertTrue(PermissionDependentFeature.VOICE_COMMAND in decision.stoppedFeatures)
        assertTrue(PermissionDependentFeature.STEP_TRACKING in decision.stoppedFeatures)
    }

    @Test
    fun alreadyGrantedPermissionsAreNeverReturnedAsMissing() {
        val observed = ObservedPermissionSnapshot(
            cameraGranted = true,
            preciseLocationGranted = true,
            microphoneGranted = false,
            activityRecognitionGranted = true,
        )

        assertEquals(
            setOf(ObservedPermission.MICROPHONE),
            PermissionDependencyPolicy.missing(
                required = ObservedPermission.entries.toSet(),
                observed = observed,
            ),
        )
    }

    @Test
    fun startWalkBundleFailsClosedWhenAnyRequiredPermissionIsMissing() {
        val observed = ObservedPermissionSnapshot(
            cameraGranted = true,
            preciseLocationGranted = true,
            microphoneGranted = false,
            activityRecognitionGranted = true,
        )

        assertEquals(
            setOf(ObservedPermission.MICROPHONE),
            WalkStartPermissionPolicy.missing(
                observed = observed,
                activityRecognitionRequired = true,
            ),
        )
        assertEquals(
            ObservedPermission.entries.toSet(),
            WalkStartPermissionPolicy.required(activityRecognitionRequired = true),
        )
    }

    @Test
    fun settingsRecoveryRequiresFullRecheckAndExplicitAcknowledgement() {
        val required = WalkStartPermissionPolicy.required(activityRecognitionRequired = true)
        val blocked = PermissionRecoveryGate()
            .requestPending(required)
            .blocked(setOf(ObservedPermission.MICROPHONE))
            .settingsPending()
            .recheckRequired()

        val awaiting = blocked.completeFullRecheck(
            observed = ObservedPermissionSnapshot(
                cameraGranted = true,
                preciseLocationGranted = true,
                microphoneGranted = true,
                activityRecognitionGranted = true,
            ),
            requiredPermissions = required,
            allPrerequisitesReady = true,
        )

        assertEquals(
            PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME,
            awaiting.state,
        )
        assertTrue(awaiting.blocksAutomaticResourceStart)
        assertEquals(
            PermissionRecoveryGateState.CLEAR,
            awaiting.acknowledgeExplicitResume(
                observed = ObservedPermissionSnapshot(true, true, true, true),
                requiredPermissions = required,
            ).state,
        )
    }

    @Test
    fun runtimeRevocationStopsOnlyDependentFeaturesAndEscalatesUnsafeRemainder() {
        val microphone = PermissionDependencyPolicy.evaluate(
            ObservedPermissionSnapshot(
                cameraGranted = true,
                preciseLocationGranted = true,
                microphoneGranted = false,
                activityRecognitionGranted = true,
            ),
        )
        val camera = PermissionDependencyPolicy.evaluate(
            ObservedPermissionSnapshot(
                cameraGranted = false,
                preciseLocationGranted = true,
                microphoneGranted = true,
                activityRecognitionGranted = true,
            ),
        )

        assertEquals(setOf(PermissionDependentFeature.VOICE_COMMAND), microphone.stoppedFeatures)
        assertFalse(microphone.requiresWholeWalkSafetyStop)
        assertFalse(camera.requiresWholeWalkSafetyStop)
        assertTrue(PermissionDependentFeature.REPORT_TRANSMISSION in camera.stoppedFeatures)
    }

    private fun authenticatedPolicyWithAllConsents(): PermissionSessionPolicy {
        val policy = PermissionSessionPolicy()
        val authenticatedA = policy.authenticated("actor-A")
        assertEquals(
            PermissionSessionSnapshot(
                actorId = "actor-A",
                authentication = AuthenticationState.ACTIVE,
            ),
            authenticatedA,
        )
        assertEquals(authenticatedA, policy.snapshot())
        assertTrue(policy.isAuthenticatedFor("actor-A"))

        val grantedA = policy.applyIntegratedConsentSelections(
            IntegratedConsentSelections(
                rawSourceCollection = true,
                automaticReporting = true,
                mobileNetworkTransfer = true,
                trainingReuse = true,
            ),
        )
        assertEquals(
            PermissionSessionSnapshot(
                actorId = "actor-A",
                authentication = AuthenticationState.ACTIVE,
                rawCollectionConsentGranted = true,
                automaticReportConsentGranted = true,
                mobileNetworkPreference = MobileNetworkPreference.ALLOW_CELLULAR,
                trainingReuseConsentGranted = true,
            ),
            grantedA,
        )
        assertEquals(grantedA, policy.snapshot())
        assertTrue(grantedA.mayUseProtectedServerFeature)
        assertTrue(grantedA.mayCreateAutomaticReport)
        assertTrue(grantedA.mayReuseForTraining(TrainingSourceKind.WALK_RAW_DIAGNOSTIC))
        assertTrue(grantedA.mayReuseForTraining(TrainingSourceKind.REPORT_ORIGINAL))
        return policy
    }
}
