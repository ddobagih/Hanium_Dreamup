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
    fun explicitLogoutEndsOnlyTheAccountSession() {
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
        assertTrue(after.rawCollectionConsentGranted)
        assertTrue(after.automaticReportConsentGranted)
        assertEquals(MobileNetworkPreference.ALLOW_CELLULAR, after.mobileNetworkPreference)
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
    fun locationRevocationRequiresWholeWalkSafetyStop() {
        val decision = PermissionDependencyPolicy.evaluate(
            ObservedPermissionSnapshot(
                cameraGranted = true,
                preciseLocationGranted = false,
                microphoneGranted = true,
                activityRecognitionGranted = true,
            ),
        )

        assertTrue(decision.requiresWholeWalkSafetyStop)
        assertTrue(PermissionDependentFeature.METRIC_DISTANCE in decision.stoppedFeatures)
        assertTrue(PermissionDependentFeature.ROUTE_NAVIGATION in decision.stoppedFeatures)
        assertTrue(PermissionDependentFeature.REPORT_TRANSMISSION in decision.stoppedFeatures)
        assertFalse(PermissionDependentFeature.CAMERA_HAZARD_GUIDANCE in decision.stoppedFeatures)
        assertFalse(PermissionDependentFeature.VOICE_COMMAND in decision.stoppedFeatures)
    }

    @Test
    fun cameraRevocationRequiresWholeWalkSafetyStop() {
        val decision = PermissionDependencyPolicy.evaluate(
            ObservedPermissionSnapshot(
                cameraGranted = false,
                preciseLocationGranted = true,
                microphoneGranted = true,
                activityRecognitionGranted = true,
            ),
        )

        assertTrue(decision.requiresWholeWalkSafetyStop)
        assertTrue(PermissionDependentFeature.CAMERA_HAZARD_GUIDANCE in decision.stoppedFeatures)
        assertTrue(PermissionDependentFeature.REPORT_TRANSMISSION in decision.stoppedFeatures)
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
            awaiting.acknowledgeExplicitResume().state,
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
        assertTrue(camera.requiresWholeWalkSafetyStop)
        assertTrue(PermissionDependentFeature.REPORT_TRANSMISSION in camera.stoppedFeatures)
    }
}
