package kr.co.hanium.dreamup.walksafe.session.gateway

import java.util.ArrayDeque
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionActivityLease
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingPolicy
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingSnapshot
import kr.co.hanium.dreamup.walksafe.session.FirstRunOpaqueActorBinding
import kr.co.hanium.dreamup.walksafe.session.FirstRunReceiptHash
import kr.co.hanium.dreamup.walksafe.session.PermissionSessionPolicy
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.session.onboarding.onboardingStateForTest
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.session.deletion.runtime.AccountDeletionPasswordRecoveryBinding
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GatewaySessionControllerTest {
    @Before
    @After
    fun clearSharedAuthority() {
        val current = GatewaySessionProcessCoordinator.snapshot()
        current.session?.let { GatewayInRunRenewalProcessState.gate.finish(it.lease) }
        sessions.forEach { GatewayInRunRenewalProcessState.gate.finish(it.lease) }
        val dummy = GatewayFieldSession.verified(ORIGIN, ACTOR, "walksafe_field_session=cleanup-test-token-000001", Long.MAX_VALUE)
        GatewayInRunRenewalProcessState.gate.tryBegin(dummy.lease, Long.MAX_VALUE)
        GatewayInRunRenewalProcessState.gate.finish(dummy.lease)
        sessions.clear()
        GatewaySessionProcessCoordinator.clear(current.generation)
        GatewayCapacityProcessState.resetForTests()
    }

    @Test
    fun constructionDoesNotReadAuthorityOrStartObserversTimersAndNetwork() {
        val f = Fixture()
        assertTrue(f.events.isEmpty())
        assertFalse(f.controller.backendRestorePending())
        assertTrue(f.worker.isEmpty())
    }

    @Test
    fun unsupportedOriginClearsTheInputTokenWithoutDispatchOrPublication() {
        val f = Fixture()
        f.url = "ftp://gateway.example.test"
        f.controller.onGatewaySessionButtonClicked()
        assertTrue(f.events.contains("clear-token"))
        assertTrue(f.events.any { it.startsWith("gateway=login_input_invalid") })
        assertTrue(f.worker.isEmpty())
        assertNull(GatewaySessionProcessCoordinator.snapshot().session)
    }

    @Test
    fun anExpiredActivityLeaseBeforeWorkerDispatchCannotLogin() {
        val f = Fixture()
        f.controller.onGatewaySessionButtonClicked()
        f.lease = AccountDeletionActivityLease(2L)
        f.runWorker()
        assertFalse(f.events.contains("remote-login"))
        assertNull(GatewaySessionProcessCoordinator.snapshot().inFlightOperationId)
    }

    @Test
    fun anExpiredActivityLeaseCannotDeliverTheQueuedUiCallback() {
        val f = Fixture()
        var delivered = false
        f.controller.postGatewayActivityCallback(f.lease) { delivered = true }
        f.lease = AccountDeletionActivityLease(2L)
        f.runMain()
        assertFalse(delivered)
    }

    @Test
    fun committingStorageFailureFailsClosedBeforeAnySessionPublish() {
        val f = Fixture(longLived = true)
        f.initialSaveResult = GatewaySessionStoreResult.STORAGE_FAILURE
        f.controller.onGatewaySessionButtonClicked()
        f.runWorker()
        assertTrue(GatewaySessionProcessCoordinator.snapshot().storageBlocked)
        assertNull(GatewaySessionProcessCoordinator.snapshot().session)
        assertTrue(f.events.contains("downgrade:gateway_session_storage_blocked"))
    }

    @Test
    fun aDifferentSessionInstanceCannotClearThePublishedLogin() {
        val f = Fixture()
        val published = f.session()
        f.publish(published)
        val unrelated = f.session()
        assertNull(f.controller.clearGatewaySession(true, unrelated))
        assertSame(published, GatewaySessionProcessCoordinator.snapshot().session)
        assertFalse(f.events.contains("stage-revocation"))
        assertFalse(f.events.contains("remote-logout"))
    }

    @Test
    fun durableLocalRevocationPrecedesRemoteLogoutAndCompletion() {
        val f = Fixture()
        val session = f.session()
        f.publish(session)
        val owner = Any()
        GatewaySessionProcessCoordinator.attach(owner) {
            if (it.session == null) f.events.add("local-session-closed")
        }
        f.events.clear()
        try {
            f.controller.clearGatewaySession(true, session, { f.events.add("remote-result:$it") })
            assertInOrder(f.events, "stage-revocation", "local-session-closed", "downgrade:gateway_session_cleared", "cancel-report")
            assertFalse(f.events.contains("remote-logout"))
            assertFalse(f.policy.isAuthenticatedFor(ACTOR))
            f.runWorker()
            f.runAllMain()
            assertInOrder(f.events, "remote-pending-logout", "complete-revocation", "remote-result:true")
        } finally {
            GatewaySessionProcessCoordinator.detach(owner)
        }
    }

    @Test
    fun failedLocalRevocationKeepsStorageBlockedAndNeverPublishesAnActiveGrant() {
        val f = Fixture()
        val session = f.session()
        f.publish(session)
        f.moveResult = GatewaySessionStoreResult.STORAGE_FAILURE
        f.controller.clearGatewaySession(false, session)
        assertTrue(GatewaySessionProcessCoordinator.snapshot().storageBlocked)
        assertNull(GatewaySessionProcessCoordinator.snapshot().session)
        assertFalse(f.events.contains("remote-logout"))
    }

    @Test
    fun queuedCapacityWorkForASupersededSessionDoesNotRevalidateIt() {
        val f = Fixture()
        f.publish(f.session())
        f.controller.requestGatewayCapacityRefresh("test")
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
        f.runWorker()
        assertEquals(0, f.capacityCalls)
    }

    @Test
    fun simultaneousCapacityRequestsCoalesceWithoutChangingTheGlobalGate() {
        val f = Fixture()
        f.publish(f.session())
        f.controller.requestGatewayCapacityRefresh("first")
        f.controller.requestGatewayCapacityRefresh("latest")
        assertEquals(1, f.worker.size)
        f.runWorker()
        assertEquals(1, f.worker.size)
        f.runWorker()
        assertEquals(2, f.capacityCalls)
        assertTrue(f.worker.isEmpty())
    }

    @Test
    fun observerRegistrationIsIdempotentAndKeepsTheOriginalNetworkEffectOrder() {
        val f = Fixture()
        f.controller.registerGatewayCapacityNetworkObserver()
        f.controller.registerGatewayCapacityNetworkObserver()
        assertEquals(1, f.events.count { it == "register-network" })
        f.events.clear()
        requireNotNull(f.networkEvents).lost()
        assertEquals(listOf("reset-network-filter", "network-drain", "network-policy"), f.events)
        f.controller.unregisterGatewayCapacityNetworkObserver()
        f.controller.unregisterGatewayCapacityNetworkObserver()
        assertEquals(1, f.events.count { it == "unregister-network" })
    }

    @Test
    fun restorationStoresRenewalBeforePublishAndKeepsTheRestoredActorAndEpoch() {
        val f = Fixture(longLived = true)
        val original = f.session()
        val restored = requireNotNull(GatewayFieldSession.restore(
            requireNotNull(original.persistenceSnapshotOrNull()), ORIGIN, ACTOR, DEVICE, f.wall,
        ))
        f.restored = RestoredGatewayLoginBundle(f.firstRunSnapshot, restored, requireNotNull(restored.versionOrNull))
        f.controller.restoreGatewaySessionFromPrefs()
        assertTrue(f.events.contains("reserve-renewal"))
        f.runWorker()
        f.runAllMain()
        assertEquals(ACTOR, GatewaySessionProcessCoordinator.snapshot().session?.actorId)
        assertEquals(f.firstRunSnapshot.epoch, GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot?.epoch)
        assertInOrder(f.events, "reserve-renewal", "remote-renew", "commit-renewal")
        assertEquals(1L, GatewaySessionProcessCoordinator.snapshot().session?.rotation)
    }

    @Test
    fun activeDiskRestoreCannotPublishAfterLocalEnrollmentRestartDuringTheRead() {
        val f = Fixture(longLived = true)
        val original = f.session()
        val restored = requireNotNull(GatewayFieldSession.restore(
            requireNotNull(original.persistenceSnapshotOrNull()), ORIGIN, ACTOR, DEVICE, f.wall,
        ))
        f.restored = RestoredGatewayLoginBundle(f.firstRunSnapshot, restored, requireNotNull(restored.versionOrNull))
        f.beforeActiveRestore = { f.onboarding.restartEmailAccount { 1L } }
        f.controller.restoreGatewaySessionFromPrefs()
        assertEquals(kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT, f.firstRunSnapshot.stage)
        assertNull(GatewaySessionProcessCoordinator.snapshot().session)
        assertNull(GatewaySessionProcessCoordinator.snapshot().inFlightOperationId)
        assertFalse(f.events.contains("reserve-renewal"))
        assertTrue(f.worker.isEmpty())
    }

    @Test
    fun backendDiskRestoreCannotQueueVerificationAfterLocalEnrollmentRestartDuringTheRead() {
        val f = Fixture(longLived = true)
        val original = f.backendSession()
        val restored = requireNotNull(GatewayFieldSession.restoreBackendAccountDevice(
            requireNotNull(original.backendDevicePersistenceSnapshotOrNull(f.wall)), ORIGIN, ACTOR, DEVICE, f.wall,
        ))
        f.backendRestored = RestoredGatewayLoginBundle(f.firstRunSnapshot, restored, requireNotNull(restored.versionOrNull))
        f.beforeBackendRestore = { f.onboarding.restartEmailAccount { 1L } }
        f.controller.restoreGatewaySessionFromPrefs()
        assertEquals(kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT, f.firstRunSnapshot.stage)
        assertNull(GatewaySessionProcessCoordinator.snapshot().session)
        assertNull(GatewaySessionProcessCoordinator.snapshot().inFlightOperationId)
        assertFalse(f.controller.backendRestorePending())
        assertTrue(f.worker.isEmpty())
    }

    @Test
    fun leaseMismatchNeverCommitsRenewalAndAbandonsTheOneUseProof() {
        val f = Fixture(longLived = true)
        val original = f.session()
        val restored = requireNotNull(GatewayFieldSession.restore(requireNotNull(original.persistenceSnapshotOrNull()), ORIGIN, ACTOR, DEVICE, f.wall))
        f.restored = RestoredGatewayLoginBundle(f.firstRunSnapshot, restored, requireNotNull(restored.versionOrNull))
        f.renewWrongLease = true
        f.controller.restoreGatewaySessionFromPrefs()
        f.runWorker()
        f.runAllMain()
        assertFalse(f.events.contains("commit-renewal"))
        assertTrue(f.events.contains("abandon-renewal"))
        assertNull(GatewaySessionProcessCoordinator.snapshot().session)
    }

    @Test
    fun renewalFailureUsesLiveStatusQueriesAndAvoidsDuplicateScreenReaderSpeech() {
        val f = Fixture(longLived = true)
        val original = f.session()
        val restored = requireNotNull(GatewayFieldSession.restore(requireNotNull(original.persistenceSnapshotOrNull()), ORIGIN, ACTOR, DEVICE, f.wall))
        f.restored = RestoredGatewayLoginBundle(f.firstRunSnapshot, restored, requireNotNull(restored.versionOrNull))
        f.renewFailure = GatewaySessionHttpException(403, "refresh_failed", serverCode = "refresh_token_reuse_detected")
        f.screenReader = true
        f.controller.restoreGatewaySessionFromPrefs()
        f.runWorker()
        f.runAllMain()
        assertInOrder(f.events, "status-read:", "set-notice", "refresh-first-run", "screen-reader-query", "status-shown-query", "status-read:" + GatewayRenewalFailureNotice.REUSE_DETECTED.speech)
        assertFalse(f.events.contains("speech:" + GatewayRenewalFailureNotice.REUSE_DETECTED.speech))
    }

    @Test
    fun expiredBackendRestoreRetainsOldProgressUntilANewVerifiedActorPreparesFreshLogin() {
        val f = Fixture(longLived = true)
        val oldProgress = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(7L, listOf(
            kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingEvidence.VerifiedLogin(
                FirstRunOpaqueActorBinding.fromProvider(ACTOR), FirstRunReceiptHash.fromSha256Hex("a".repeat(64))),
            kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingEvidence.PurposeAndSafety(
                FirstRunReceiptHash.fromSha256Hex("b".repeat(64))),
            kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingEvidence.Fp004Training(
                FirstRunReceiptHash.fromSha256Hex("c".repeat(64))),
        ), kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingEvidenceVerifier { _, _ -> true })
        assertTrue(oldProgress.fullyRestored)
        val original = f.backendSession()
        val restored = requireNotNull(GatewayFieldSession.restoreBackendAccountDevice(
            requireNotNull(original.backendDevicePersistenceSnapshotOrNull(f.wall)), ORIGIN, ACTOR, DEVICE, f.wall))
        f.backendRestored = RestoredGatewayLoginBundle(oldProgress.snapshot, restored, requireNotNull(restored.versionOrNull))
        f.backendVerificationFailure = GatewaySessionHttpException(401, "backend_session_expired")
        f.controller.restoreGatewaySessionFromPrefs()
        assertTrue(f.controller.backendRestorePending())
        assertSame(oldProgress.snapshot, f.firstRunSnapshot)
        f.runWorker()
        f.runAllMain()
        assertFalse(f.controller.backendRestorePending())
        assertNull(GatewaySessionProcessCoordinator.snapshot().session)
        assertNull(GatewaySessionProcessCoordinator.snapshot().inFlightOperationId)
        assertSame(oldProgress.snapshot, f.firstRunSnapshot)
        assertTrue(f.events.contains("message:저장된 로그인이 만료되었거나 해제되었습니다. 다시 로그인하세요."))
        val newActor = "123e4567-e89b-42d3-a456-426614174001"
        val newReceipt = FirstRunReceiptHash.fromSha256Hex("f".repeat(64))
        val prepared = requireNotNull(f.onboarding.prepareVerifiedEmailLogin(
            FirstRunOpaqueActorBinding.fromProvider(newActor), newReceipt, null))
        assertSame(oldProgress.snapshot, f.firstRunSnapshot)
        assertFalse(prepared.reauthenticatesExistingProgress)
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        val session = GatewayFieldSession.verified(ORIGIN, newActor, "walksafe_field_session=new-verified-account-test", Long.MAX_VALUE)
        assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, prepared.snapshot))
        assertTrue(f.onboarding.applyVerifiedEmailLogin(prepared))
        assertEquals(kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage.PURPOSE_AND_SAFETY, f.firstRunSnapshot.stage)
        assertEquals(oldProgress.snapshot.epoch + 1L, f.firstRunSnapshot.epoch)
        assertEquals(mapOf(kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage.VERIFIED_LOGIN to newReceipt),
            f.firstRunSnapshot.completedReceiptHashes)
        assertEquals(newActor, GatewaySessionProcessCoordinator.snapshot().session?.actorId)
    }

    @Test
    fun passwordRecoveryPublishesOnlyRecoveryWithoutResettingEducationOrPersistingGeneralLogin() {
        val f = Fixture(longLived = true)
        val old = f.session()
        f.publish(old)
        f.preparePasswordRecovery()
        val progress = f.firstRunSnapshot
        f.controller.onDeletionPasswordLoginClicked()
        assertEquals("", f.recoveryPassword)
        assertSame(old, GatewaySessionProcessCoordinator.snapshot().session)
        f.runWorker()
        f.runAllMain()
        val published = requireNotNull(GatewaySessionProcessCoordinator.snapshot().session)
        assertEquals(GatewaySessionScope.ACCOUNT_DELETION_RECOVERY, published.sessionScope)
        assertTrue(GatewaySessionProcessCoordinator.snapshot().deletionRecoveryOnly)
        assertSame(progress, f.firstRunSnapshot)
        assertEquals(0, f.initialSaveCalls)
        assertFalse(f.events.contains("persist-origin"))
        assertFalse(f.events.contains("remote-login"))
        assertFalse(f.events.contains("remote-logout"))
        assertEquals(1, f.passwordLoginCalls)
        assertInOrder(f.events, "clear-recovery-password", "remote-password-login")
    }

    @Test
    fun wrongPasswordEndsOnlyItsOperationAndKeepsGeneralSessionProgressAndDeletionBinding() {
        val f = Fixture()
        val old = f.session()
        f.publish(old)
        f.preparePasswordRecovery()
        val before = GatewaySessionProcessCoordinator.snapshot()
        val binding = f.passwordBinding
        f.passwordLoginFailure = GatewaySessionHttpException(401, "account_password_login_failed")
        f.controller.onDeletionPasswordLoginClicked()
        f.runWorker()
        f.runAllMain()
        val after = GatewaySessionProcessCoordinator.snapshot()
        assertSame(old, after.session)
        assertTrue(old.isUsableFor(ACTOR))
        assertSame(before.restoredFirstRunSnapshot, after.restoredFirstRunSnapshot)
        assertEquals(before.storageBlocked, after.storageBlocked)
        assertEquals(before.deletionRecoveryOnly, after.deletionRecoveryOnly)
        assertEquals(binding, f.passwordBinding)
        assertNull(after.inFlightOperationId)
        assertFalse(f.events.contains("remote-logout"))
        assertEquals(0, f.initialSaveCalls)
    }

    @Test
    fun staleActivityBeforePasswordDispatchDoesNotAuthenticateOrClearOldSession() {
        val f = Fixture()
        val old = f.session()
        f.publish(old)
        f.preparePasswordRecovery()
        f.controller.onDeletionPasswordLoginClicked()
        f.lease = AccountDeletionActivityLease(2L)
        f.runWorker()
        f.runAllMain()
        assertEquals(0, f.passwordLoginCalls)
        assertSame(old, GatewaySessionProcessCoordinator.snapshot().session)
        assertNull(GatewaySessionProcessCoordinator.snapshot().inFlightOperationId)
    }

    @Test
    fun changedFullMarkerDuringAuthenticationDiscardsOnlyTheNewRecoverySession() {
        val f = Fixture()
        val old = f.session()
        f.publish(old)
        f.preparePasswordRecovery()
        f.beforePasswordResponse = { f.changeRecoverySecret() }
        f.controller.onDeletionPasswordLoginClicked()
        f.runWorker()
        f.runAllMain()
        assertSame(old, GatewaySessionProcessCoordinator.snapshot().session)
        assertTrue(old.isUsableFor(ACTOR))
        assertFalse(requireNotNull(f.passwordResult).isUsableFor(ACTOR))
        assertEquals(1, f.events.count { it == "remote-logout" })
        assertNull(GatewaySessionProcessCoordinator.snapshot().inFlightOperationId)
    }

    @Test
    fun fullBindingChangedByCommitEffectsCannotPublishOrFallBackToGeneralPersistence() {
        val f = Fixture(longLived = true)
        val old = f.session()
        f.publish(old)
        f.preparePasswordRecovery()
        f.beforeSpeechInvalidation = { f.changeRecoverySecret() }
        f.controller.onDeletionPasswordLoginClicked()
        f.runWorker()
        f.runAllMain()
        assertSame(old, GatewaySessionProcessCoordinator.snapshot().session)
        assertFalse(requireNotNull(f.passwordResult).isUsableFor(ACTOR))
        assertEquals(0, f.initialSaveCalls)
    }

    @Test
    fun changedActivityAfterPasswordResponseDisposesNewSessionAndKeepsTheOriginal() {
        val f = Fixture()
        val old = f.session()
        f.publish(old)
        f.preparePasswordRecovery()
        f.beforePasswordResponse = { f.lease = AccountDeletionActivityLease(2L) }
        f.controller.onDeletionPasswordLoginClicked()
        f.runWorker()
        f.runAllMain()
        assertSame(old, GatewaySessionProcessCoordinator.snapshot().session)
        assertFalse(requireNotNull(f.passwordResult).isUsableFor(ACTOR))
        assertNull(GatewaySessionProcessCoordinator.snapshot().inFlightOperationId)
    }

    @Test
    fun aSupersedingOperationIsNotCancelledByTheOldPasswordResult() {
        val f = Fixture()
        val old = f.session()
        f.publish(old)
        f.preparePasswordRecovery()
        var next: GatewaySessionOperation? = null
        f.beforePasswordResponse = {
            val current = GatewaySessionProcessCoordinator.snapshot()
            assertTrue(GatewaySessionProcessCoordinator.cancelOperationPreservingSession(
                GatewaySessionOperation(current.generation, requireNotNull(current.inFlightOperationId))))
            next = GatewaySessionProcessCoordinator.beginGeneralSessionOperationIfCurrent(
                GatewaySessionProcessCoordinator.snapshot().generation, old)
        }
        f.controller.onDeletionPasswordLoginClicked()
        f.runWorker()
        f.runAllMain()
        assertEquals(requireNotNull(next).operationId, GatewaySessionProcessCoordinator.snapshot().inFlightOperationId)
        assertSame(old, GatewaySessionProcessCoordinator.snapshot().session)
        assertFalse(requireNotNull(f.passwordResult).isUsableFor(ACTOR))
    }

    @Test
    fun otherActorGeneralExpiredOrWrongGenerationResponsesCannotReplaceTheOldSession() {
        for (kind in listOf("other-actor", "general", "expired", "generation")) {
            clearSharedAuthority()
            val f = Fixture()
            val old = f.session()
            f.publish(old)
            f.preparePasswordRecovery()
            f.passwordBinding = requireNotNull(f.passwordBinding).let {
                it.copy(markerRecord = it.markerRecord.copy(accountGeneration = 7L))
            }
            f.passwordResult = when (kind) {
                "general" -> f.session()
                "other-actor" -> f.recoverySession(actor = "223e4567-e89b-42d3-a456-426614174000")
                "expired" -> f.recoverySession(expiry = f.wall - 1L)
                else -> f.recoverySession(generation = 8L)
            }
            f.controller.onDeletionPasswordLoginClicked()
            f.runWorker()
            f.runAllMain()
            assertSame(old, GatewaySessionProcessCoordinator.snapshot().session)
            assertTrue(old.isUsableFor(ACTOR))
            assertFalse(requireNotNull(f.passwordResult).isUsableFor(f.passwordResult!!.actorId))
            assertEquals(0, f.initialSaveCalls)
        }
    }

    @Test
    fun aRemoteReturningTheOldGeneralObjectCannotCauseItsLogoutOrRelabel() {
        val f = Fixture()
        val old = f.session()
        f.publish(old)
        f.preparePasswordRecovery()
        f.passwordResult = old
        f.controller.onDeletionPasswordLoginClicked()
        f.runWorker()
        f.runAllMain()
        assertSame(old, GatewaySessionProcessCoordinator.snapshot().session)
        assertTrue(old.isUsableFor(ACTOR))
        assertEquals(GatewaySessionScope.GENERAL, old.sessionScope)
        assertFalse(f.events.contains("remote-logout"))
    }

    @Test
    fun passwordRecoveryOnCellularDoesNotRequireOptionalFeatureTransferConsent() {
        val f = Fixture()
        f.preparePasswordRecovery()
        f.transport = ActiveNetworkTransport.CELLULAR
        f.featureNetworkAllowed = false
        f.controller.onDeletionPasswordLoginClicked()
        f.runWorker()
        f.runAllMain()
        assertEquals(1, f.passwordLoginCalls)
        assertTrue(GatewaySessionProcessCoordinator.snapshot().deletionRecoveryOnly)
        assertEquals(GatewaySessionScope.ACCOUNT_DELETION_RECOVERY,
            GatewaySessionProcessCoordinator.snapshot().session?.sessionScope)
    }

    @Test
    fun passwordRecoveryWithoutReadyInternetKeepsTheOldSessionAndDoesNotAuthenticate() {
        for (probeReady in listOf(false, true)) {
            clearSharedAuthority()
            val f = Fixture()
            val old = f.session()
            f.publish(old)
            f.preparePasswordRecovery()
            f.probeReady = probeReady
            f.transport = if (probeReady) ActiveNetworkTransport.OFFLINE else ActiveNetworkTransport.WIFI
            f.controller.onDeletionPasswordLoginClicked()
            assertEquals(0, f.passwordLoginCalls)
            assertTrue(f.worker.isEmpty())
            assertSame(old, GatewaySessionProcessCoordinator.snapshot().session)
            assertNull(GatewaySessionProcessCoordinator.snapshot().inFlightOperationId)
            assertTrue(f.events.contains("message:인터넷 연결을 확인한 뒤 다시 시도하세요."))
            assertEquals("", f.recoveryPassword)
        }
    }

    @Test
    fun missingBindingOrInvalidPasswordClearsOnlyPasswordAndMakesNoRemoteCall() {
        for (missingBinding in listOf(false, true)) {
            clearSharedAuthority()
            val f = Fixture()
            f.preparePasswordRecovery()
            if (missingBinding) f.passwordBinding = null else f.recoveryPassword = "short"
            f.controller.onDeletionPasswordLoginClicked()
            assertEquals("", f.recoveryPassword)
            assertEquals(0, f.passwordLoginCalls)
            assertTrue(f.worker.isEmpty())
            assertNull(GatewaySessionProcessCoordinator.snapshot().inFlightOperationId)
        }
    }

    private class Fixture(longLived: Boolean = false) : GatewaySessionController.Store,
        GatewaySessionController.Remote, GatewaySessionController.Profile, GatewaySessionController.ActivityAuthority,
        GatewaySessionController.DeletionAuthority, GatewaySessionController.Inputs, GatewaySessionController.RuntimeReader,
        GatewaySessionController.Effects, GatewaySessionController.Ui, GatewaySessionController.NetworkObserver,
        GatewaySessionController.NetworkEffects {
        val events = mutableListOf<String>()
        val worker = ArrayDeque<Runnable>()
        val main = ArrayDeque<() -> Unit>()
        val wall = System.currentTimeMillis()
        var elapsed = 10_000L
        var lease = AccountDeletionActivityLease(1L)
        var destroyed = false
        var url: String? = ORIGIN
        var initialSaveResult = GatewaySessionStoreResult.COMMITTED
        var moveResult = GatewaySessionStoreResult.COMMITTED
        var pending: GatewayPendingRevocation? = null
        var restored: RestoredGatewayLoginBundle? = null
        var backendRestored: RestoredGatewayLoginBundle? = null
        var beforeActiveRestore: () -> Unit = {}
        var beforeBackendRestore: () -> Unit = {}
        var capacityCalls = 0
        var networkEvents: GatewaySessionController.NetworkEvents? = null
        var renewWrongLease = false
        var renewFailure: RuntimeException? = null
        var backendVerificationFailure: RuntimeException? = null
        var screenReader = false
        var notice: String? = null
        var statusText = ""
        var policy = PermissionSessionPolicy().apply { rememberActor(ACTOR) }
        var passwordBinding: AccountDeletionPasswordRecoveryBinding? = null
        var recoveryEmail = "walker@example.test"
        var recoveryPassword = "Valid-password-123!"
        var passwordLoginCalls = 0
        var initialSaveCalls = 0
        var passwordLoginFailure: RuntimeException? = null
        var passwordResult: GatewayFieldSession? = null
        var beforePasswordResponse: () -> Unit = {}
        var beforeSpeechInvalidation: () -> Unit = {}
        var probeReady = true
        var transport = ActiveNetworkTransport.WIFI
        var featureNetworkAllowed = true
        val onboarding = onboardingStateForTest(FirstRunOnboardingPolicy.recordVerifiedEmailLogin(
            FirstRunOnboardingPolicy.initialEmailAccount(epoch = 7L),
            FirstRunOpaqueActorBinding.fromProvider(ACTOR), FirstRunReceiptHash.fromSha256Hex("a".repeat(64)),
        ).current)
        override val firstRunSnapshot get() = onboarding.snapshot
        override fun restoreFirstRun(snapshot: FirstRunOnboardingSnapshot, expected: FirstRunOnboardingSnapshot?) =
            onboarding.restoreSessionSnapshot(snapshot, expected)
        override var reporterActorId: String? = ACTOR
        private val restorationTimer = FakeTimer(events)
        private val renewalTimer = FakeTimer(events)
        val controller = GatewaySessionController(
            config = GatewaySessionController.Configuration(true, longLived, ORIGIN),
            remote = this, profile = this, permissions = { policy }, activity = this, deletion = this,
            inputs = this, runtime = this, effects = this, ui = this, networkObserver = this, networkEffects = this,
            restorationTimer = restorationTimer, renewalTimer = renewalTimer,
            executor = Executor { worker.add(it) }, postToMain = { main.add(it) }, isMainThread = { true },
            elapsedRealtimeMs = { elapsed }, wallClockMs = { wall }, warn = { _, _ -> events.add("warning") },
        ).also { it.initializeStorage(this) }

        fun backendSession(): GatewayFieldSession {
            val expiresAt = ((wall + 900_000L) / 1_000L) * 1_000L
            val sessionId = "i".repeat(43)
            fun encoded(value: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
            val cookie = listOf("v7", encoded(ACTOR), "7", "3", encoded(DEVICE), "general",
                (expiresAt / 1_000L).toString(), sessionId, "s".repeat(43)).joinToString(".")
            return GatewayFieldSession.backendAccountDeviceSession(ORIGIN,
                BackendAccountDeviceCookieBinding(ACTOR, 7L, 3L, DEVICE, sessionId, expiresAt),
                "${GatewayFieldSession.COOKIE_NAME}=$cookie", expiresAt).also { sessions.add(it) }
        }

        fun session(rotation: Long = 0L): GatewayFieldSession = GatewayFieldSession.longLivedSession(
            ORIGIN, ACTOR, DEVICE, FAMILY, rotation, "walksafe_field_session=access-token-gateway-$rotation-00001", "r".repeat(64),
            wall + 900_000L, Long.MAX_VALUE, Long.MAX_VALUE,
        ).also { sessions.add(it) }
        fun recoverySession(actor: String = ACTOR, generation: Long = 7L, expiry: Long = wall + 900_000L) =
            GatewayFieldSession.deletionRecoveryVerified(ORIGIN, actor, generation,
                "walksafe_field_session=password-recovery-test-cookie-0001", expiry).also { sessions.add(it) }
        fun preparePasswordRecovery() {
            passwordBinding = AccountDeletionPasswordRecoveryBinding(ACTOR,
                AndroidAccountDeletionFallbackMarker.Record(
                    identity = AndroidAccountDeletionFallbackMarker.Identity("deletion-request-test-0001", "a".repeat(64)),
                    recoveryActorId = ACTOR, gatewayOrigin = ORIGIN, installationId = "installation-test-0001",
                    accessSecret = "s".repeat(48), clientRevision = 1L, phase = AndroidAccountDeletionFallbackMarker.Phase.PREPARED,
                ), journal = null)
        }
        fun changeRecoverySecret() {
            passwordBinding = requireNotNull(passwordBinding).let { it.copy(markerRecord = it.markerRecord.copy(accessSecret = "t".repeat(48))) }
        }
        fun publish(session: GatewayFieldSession) {
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, firstRunSnapshot))
        }
        fun runWorker() = worker.removeFirst().run()
        fun runMain() = main.removeFirst().invoke()
        fun runAllMain() { while (main.isNotEmpty()) runMain() }
        override fun getOrCreateInstallDeviceId() = DEVICE
        override fun restoreBackendDevice(expectedGatewayBaseUrl: String): RestoredGatewayLoginBundle? {
            beforeBackendRestore()
            return backendRestored
        }
        override fun isStorageBlocked() = false
        override fun recoverRenewingToPendingRevocation() = GatewaySessionStoreResult.COMMITTED
        override fun restorePendingRevocation(expectedGatewayBaseUrl: String) = pending
        override fun restoreActive(expectedGatewayBaseUrl: String): RestoredGatewayLoginBundle? {
            beforeActiveRestore()
            return restored
        }
        override fun purgeDisabled() = true
        override fun moveActiveToPendingRevocation(expectedVersion: GatewaySessionVersion, operationId: String): GatewaySessionStoreResult {
            events.add("stage-revocation")
            if (moveResult == GatewaySessionStoreResult.COMMITTED) pending = GatewayPendingRevocation(operationId, expectedVersion, "r".repeat(64))
            return moveResult
        }
        override fun removeBackendDeviceIfCurrent(expectedVersion: GatewaySessionVersion) = moveResult
        override fun completePendingRevocation(expectedVersion: GatewaySessionVersion, operationId: String): GatewaySessionStoreResult { events.add("complete-revocation"); pending = null; return GatewaySessionStoreResult.COMMITTED }
        override fun reserveRenewal(expectedVersion: GatewaySessionVersion, operationId: String): GatewaySessionStoreResult { events.add("reserve-renewal"); return GatewaySessionStoreResult.COMMITTED }
        override fun commitRenewal(expectedVersion: GatewaySessionVersion, operationId: String, renewedSession: GatewayFieldSession, firstRunSnapshot: FirstRunOnboardingSnapshot): GatewaySessionStoreResult { events.add("commit-renewal"); return GatewaySessionStoreResult.COMMITTED }
        override fun rollbackRenewalForRetry(expectedVersion: GatewaySessionVersion, operationId: String, reservation: GatewayRefreshRetryReservation) = GatewaySessionStoreResult.COMMITTED
        override fun abandonRenewal(expectedVersion: GatewaySessionVersion, operationId: String): GatewaySessionStoreResult {
            events.add("abandon-renewal"); pending = GatewayPendingRevocation(operationId, expectedVersion, "r".repeat(64)); return GatewaySessionStoreResult.COMMITTED
        }
        override fun saveInitialIfAbsent(session: GatewayFieldSession, firstRunSnapshot: FirstRunOnboardingSnapshot, expectedGatewayBaseUrl: String): GatewaySessionStoreResult { initialSaveCalls++; return initialSaveResult }
        override fun persistGatewayOrigin(origin: String) { events.add("persist-origin") }
        override fun login(gatewayBaseUrl: String, actorId: String?, token: String?, enableLongLivedSession: Boolean, deviceId: String?, sessionScope: GatewaySessionScope): GatewayFieldSession { events.add("remote-login"); return session() }
        override fun loginDeletionRecoveryWithPassword(gatewayBaseUrl: String, email: String, password: String, expectedActorId: String, expectedAccountGeneration: Long?): GatewayFieldSession {
            events.add("remote-password-login"); passwordLoginCalls++
            assertEquals(ORIGIN, gatewayBaseUrl); assertEquals(recoveryEmail, email)
            assertEquals("Valid-password-123!", password); assertEquals(ACTOR, expectedActorId)
            passwordLoginFailure?.let { throw it }
            val result = passwordResult ?: recoverySession().also { passwordResult = it }
            beforePasswordResponse()
            return result
        }
        override fun revalidateBackendAccountDeviceSession(session: GatewayFieldSession): GatewayFieldSession {
            backendVerificationFailure?.let { throw it }
            return session
        }
        override fun revalidate(session: GatewayFieldSession, actorId: String?, capacitySessionGeneration: Long?): GatewaySessionRevalidation { capacityCalls++; return GatewaySessionRevalidation(GatewaySessionRevalidationStatus.READY, "test") }
        override fun renew(session: GatewayFieldSession, actorId: String?): GatewaySessionRenewal {
            events.add("remote-renew"); renewFailure?.let { throw it }
            return GatewaySessionRenewal(if (renewWrongLease) session().lease else session.lease, session(1L))
        }
        override fun logout(session: GatewayFieldSession) { events.add("remote-logout") }
        override fun logout(pending: GatewayPendingRevocation) { events.add("remote-pending-logout") }
        override fun restorePriorityProfile() { events.add("restore-profile") }
        override fun priorityActorId() = ACTOR
        override fun accountBlocked() = false
        override fun loginActorId() = ACTOR
        override fun currentReporterActorId() = reporterActorId
        override fun currentLease() = lease
        override fun leaseIsCurrent(lease: AccountDeletionActivityLease) = this.lease === lease
        override fun destroyed() = destroyed
        override fun processingBlocked() = passwordBinding != null
        override fun durableConfirmationRecoveryRequired() = passwordBinding != null
        override fun recoveryLoginRequired() = passwordBinding != null
        override fun passwordRecoveryBindingOrNull() = passwordBinding
        override fun recoverySessionOrNull(session: GatewayFieldSession?) = session?.takeIf {
            passwordBinding?.actorId == it.actorId && it.sessionScope == GatewaySessionScope.ACCOUNT_DELETION_RECOVERY }
        override fun isRecoveryLoginTarget(actorId: String, gatewayBaseUrl: String) =
            passwordBinding?.let { it.actorId == actorId && it.gatewayOrigin == gatewayBaseUrl } == true
        override fun configuredGatewayOrigin() = ORIGIN
        override fun debugGatewayUrl() = url
        override fun hasTokenInput() = true
        override fun token() = "field-account-token-gateway-0001"
        override fun clearTokenInput() { events.add("clear-token") }
        override fun deletionRecoveryEmail() = recoveryEmail
        override fun deletionRecoveryPassword() = recoveryPassword
        override fun clearDeletionRecoveryPasswordInput() { recoveryPassword = ""; events.add("clear-recovery-password") }
        override fun foreground() = true
        override fun startupInspectionComplete() = true
        override fun networkProbeReady() = probeReady
        override fun networkTransport() = transport
        override fun feedbackGeneration() = 1
        override fun feedbackGenerationIsCurrent(generation: Int) = generation == 1
        override fun developmentQuickStartEnabled() = false
        override fun gatewayNetworkAllowed(reason: String) = featureNetworkAllowed
        override fun screenReaderActive(): Boolean { events.add("screen-reader-query"); return screenReader }
        override fun walkState(): WalkSessionState? = null
        override fun invalidateSpeechStorage() { events.add("invalidate-speech"); beforeSpeechInvalidation() }
        override fun downgradeSession(reason: String, expectedActivityLease: AccountDeletionActivityLease) {
            if (reason == "gateway_session_cleared") assertFalse(policy.isAuthenticatedFor(ACTOR))
            events.add("downgrade:$reason")
        }
        override fun cancelDestinationSearchLocation() { events.add("cancel-search-location") }
        override fun cancelReportTransfers() { events.add("cancel-report") }
        override fun cancelGatewayNetworkCalls() { events.add("cancel-network") }
        override fun clearGatewayNavigationState() { events.add("clear-navigation") }
        override fun resetReportAttemptFailures() { events.add("reset-report-attempts") }
        override fun recordCapacitySignal(trigger: String, version: Long?, adminWarning: Boolean, participantRestricted: Boolean) { events.add("capacity-signal") }
        override fun showAccountMessage(message: String) { events.add("message:$message") }
        override fun refreshFirstRun() { events.add("refresh-first-run"); statusText = notice.orEmpty() }
        override fun firstRunChanged(message: String) { events.add("first-run:$message") }
        override fun refreshIntegratedConsent() { events.add("refresh-consent") }
        override fun refreshReportPrivacy() { events.add("refresh-privacy") }
        override fun refreshAccountDeletion() { events.add("refresh-deletion") }
        override fun refreshBackendAuthButton() { events.add("refresh-auth-button") }
        override fun status(message: String) { events.add(message) }
        override fun speakInteraction(message: String) { events.add("speech:$message") }
        override fun setAccountAccessNotice(message: String?) { notice = message; events.add("set-notice") }
        override fun accountStatusInitialized() = true
        override fun accountStatusText(): String { events.add("status-read:$statusText"); return statusText }
        override fun accountStatusShown(): Boolean { events.add("status-shown-query"); return true }
        override fun register(events: GatewaySessionController.NetworkEvents): GatewaySessionController.Registration {
            this.events.add("register-network"); networkEvents = events
            return GatewaySessionController.Registration { this.events.add("unregister-network") }
        }
        override fun reportDrainNetworkChanged() { events.add("network-drain") }
        override fun refreshFeatureNetworkPolicy() { events.add("network-policy") }
        override fun resetNetworkChangeFilter() { events.add("reset-network-filter") }
        override fun networkCapabilitiesChanged(network: Any, transport: ActiveNetworkTransport, validated: Boolean) = true
    }

    private class FakeTimer(private val events: MutableList<String>) : GatewaySessionController.Timer {
        override fun isReady() = true
        override fun remove(runnable: Runnable) { events.add("remove-timer") }
        override fun postDelayed(runnable: Runnable, delayMs: Long) { events.add("post-timer:$delayMs") }
    }
    private fun assertInOrder(events: List<String>, vararg wanted: String) {
        var previous = -1
        wanted.forEach { token ->
            val at = (previous + 1 until events.size).firstOrNull { events[it] == token } ?: -1
            assertTrue("Missing/out of order $token in $events", at > previous)
            previous = at
        }
    }
    private companion object {
        const val ORIGIN = "https://gateway.example.test"
        const val ACTOR = "123e4567-e89b-42d3-a456-426614174000"
        const val DEVICE = "android-gateway-controller-01"
        const val FAMILY = "abcdefghijklmnopqrstuvwxabcdefab"
        val sessions = mutableListOf<GatewayFieldSession>()
    }
}
