package kr.co.hanium.dreamup.walksafe.session

import java.io.File
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionScope
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionVerificationState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthenticatedLoginRestorationTest {
    private val owner = Any()

    @Before
    fun clearProcessState() {
        assertNotNull(
            GatewaySessionProcessCoordinator.clear(
                GatewaySessionProcessCoordinator.snapshot().generation,
            ),
        )
    }

    @After
    fun detachOwnerAndClearProcessState() {
        GatewaySessionProcessCoordinator.detach(owner)
        GatewaySessionProcessCoordinator.clear(
            GatewaySessionProcessCoordinator.snapshot().generation,
        )
    }

    @Test
    fun unconditionalRememberAfterVerifiedPublishReproducesConsentSessionDeletion() {
        val fixture = LoginFixture()
        val session = fixture.publishVerifiedLogin()
        assertTrue(fixture.policy.isAuthenticatedFor(ACTOR_ID))

        fixture.policy.rememberActor(session.actorId)

        assertEquals(
            AuthenticationState.REAUTHENTICATION_REQUIRED,
            fixture.policy.snapshot().authentication,
        )
        assertNull(fixture.consentRefreshSessionOrNull())
        assertNull(GatewaySessionProcessCoordinator.snapshot().session)
        assertFalse(session.isUsableFor(ACTOR_ID))
        assertEquals(1, fixture.clearCount)
    }

    @Test
    fun mainLoginPreservesObserverAuthenticationBeforeRefreshingConsent() {
        assertMainLoginUsesTheGuardedCommitAfterPublish()
        val fixture = LoginFixture()
        val session = fixture.publishVerifiedLogin()
        assertTrue(fixture.policy.isAuthenticatedFor(ACTOR_ID))

        fixture.finishLogin(session)

        assertEquals(AuthenticationState.ACTIVE, fixture.policy.snapshot().authentication)
        assertSame(session, fixture.consentRefreshSessionOrNull())
        assertSame(session, GatewaySessionProcessCoordinator.snapshot().session)
        assertEquals(0, fixture.clearCount)
    }

    @Test
    fun activeAuthenticationForAnotherActorCannotBeInherited() {
        val fixture = LoginFixture()
        val session = fixture.publishVerifiedLogin()
        fixture.policy.authenticated(OTHER_ACTOR_ID)

        fixture.finishLogin(session)

        assertEquals(ACTOR_ID, fixture.policy.snapshot().actorId)
        assertFalse(fixture.policy.isAuthenticatedFor(ACTOR_ID))
        assertFalse(fixture.policy.isAuthenticatedFor(OTHER_ACTOR_ID))
        assertNull(fixture.consentRefreshSessionOrNull())
        assertNull(GatewaySessionProcessCoordinator.snapshot().session)
        assertEquals(1, fixture.clearCount)
    }

    @Test
    fun incompleteOnboardingKeepsLimitedConsentAccessWithoutProtectedFeatureAccess() {
        val fixture = LoginFixture(completedOnboarding = false)
        val session = fixture.publishVerifiedLogin()

        fixture.finishLogin(session)

        assertEquals(
            AuthenticationState.REAUTHENTICATION_REQUIRED,
            fixture.policy.snapshot().authentication,
        )
        assertFalse(fixture.policy.snapshot().mayUseProtectedServerFeature)
        assertSame(session, fixture.consentRefreshSessionOrNull())
        assertEquals(0, fixture.clearCount)
    }

    @Test
    fun invalidatedSessionCannotBeRecoveredByPreservingLocalAuthentication() {
        val fixture = LoginFixture()
        val session = fixture.publishVerifiedLogin()
        fixture.finishLogin(session)
        session.invalidate()

        assertNull(fixture.consentRefreshSessionOrNull())

        assertNull(GatewaySessionProcessCoordinator.snapshot().session)
        assertFalse(fixture.policy.isAuthenticatedFor(ACTOR_ID))
        assertEquals(1, fixture.clearCount)
    }

    private fun assertMainLoginUsesTheGuardedCommitAfterPublish() {
        val source = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
        ).readText()
        val login = source.substringAfter("private fun loginEmailAccount(")
            .substringBefore("private fun postAccountFailure(")
            .replace(Regex("\\s+"), " ")
        val guardedCommit =
            "if (!permissionSessionPolicy.isAuthenticatedFor(session.actorId)) { " +
                "permissionSessionPolicy.rememberActor(session.actorId) }"
        val publish = login.indexOf("GatewaySessionProcessCoordinator.publishVerified(")
        val commit = login.indexOf(guardedCommit)
        val refresh = login.indexOf("refreshIntegratedConsentFromServer()")
        assertTrue("The behavioral fixture must match MainActivity's login commit", commit >= 0)
        assertTrue("Verified publication must still precede the local commit", publish >= 0 && publish < commit)
        assertTrue("Consent refresh must still follow the guarded commit", commit < refresh)
        assertEquals(
            "No additional unguarded rememberActor may undo the verified observer",
            1,
            Regex("permissionSessionPolicy\\.rememberActor\\(session\\.actorId\\)")
                .findAll(login).count(),
        )
    }

    /**
     * Uses the real process coordinator, permission policy, session and onboarding snapshot.
     * Models MainActivity's verified observer and consent admission with the returning user's
     * other local readiness checks satisfied; no Android UI or network calls are involved.
     */
    private inner class LoginFixture(completedOnboarding: Boolean = true) {
        val policy = PermissionSessionPolicy()
        val firstRun = verifiedEmailFirstRun(completedOnboarding)
        var clearCount = 0
            private set

        init {
            policy.rememberActor(ACTOR_ID)
            GatewaySessionProcessCoordinator.attach(owner) { process ->
                val session = process.session
                if (session == null || process.storageBlocked || process.deletionRecoveryOnly ||
                    session.verificationState != GatewaySessionVerificationState.VERIFIED ||
                    !session.isUsableFor(ACTOR_ID)
                ) {
                    policy.authenticationExpired()
                } else if (process.restoredFirstRunSnapshot?.isComplete == true) {
                    policy.authenticated(ACTOR_ID)
                } else {
                    policy.rememberActor(ACTOR_ID)
                }
            }
        }

        fun publishVerifiedLogin(): GatewayFieldSession {
            val session = GatewayFieldSession.legacyVerified(
                gatewayBaseUrl = GATEWAY_ORIGIN,
                actorId = ACTOR_ID,
                deviceId = "device-installation-00000001",
                cookiePair = "${GatewayFieldSession.COOKIE_NAME}=test-access-value-${"a".repeat(32)}",
                expiresAtEpochMs = System.currentTimeMillis() + 60_000L,
            )
            assertTrue(
                GatewaySessionProcessCoordinator.publishVerified(
                    operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation()),
                    session = session,
                    firstRunSnapshot = firstRun,
                ),
            )
            return session
        }

        // The source assertion above binds this behavioral step to MainActivity's actual guard.
        fun finishLogin(session: GatewayFieldSession) {
            if (!policy.isAuthenticatedFor(session.actorId)) {
                policy.rememberActor(session.actorId)
            }
        }

        fun consentRefreshSessionOrNull(): GatewayFieldSession? {
            val process = GatewaySessionProcessCoordinator.snapshot()
            val session = process.session ?: return null
            val actor = firstRun.verifiedActorBinding?.value
            val usable = !process.storageBlocked && !process.deletionRecoveryOnly &&
                session.sessionScope == GatewaySessionScope.GENERAL &&
                session.verificationState == GatewaySessionVerificationState.VERIFIED &&
                session.actorId == actor && session.isUsableFor(actor) &&
                session.gatewayBaseUrl == GATEWAY_ORIGIN
            val protectedAccess = firstRun.isComplete && policy.isAuthenticatedFor(actor)
            val onboardingAccess = firstRun.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 &&
                !firstRun.isComplete && actor == ACTOR_ID
            if (usable && (protectedAccess || onboardingAccess)) return session
            clearCount += 1
            GatewaySessionProcessCoordinator.clear(process.generation)
            return null
        }
    }

    private fun verifiedEmailFirstRun(completed: Boolean): FirstRunOnboardingSnapshot {
        val receipt = FirstRunReceiptHash.fromSha256Hex("1".repeat(64))
        val verified = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(
            snapshot = FirstRunOnboardingPolicy.initialEmailAccount(epoch = 1L),
            actorBinding = FirstRunOpaqueActorBinding.fromProvider(ACTOR_ID),
            receiptHash = receipt,
        ).current
        if (!completed) return verified
        return verified.copy(
            stage = FirstRunOnboardingStage.COMPLETE,
            completedReceiptHashes = verified.completedReceiptHashes + mapOf(
                FirstRunOnboardingStage.PURPOSE_AND_SAFETY to receipt,
                FirstRunOnboardingStage.FP004_TRAINING to receipt,
            ),
        )
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-4000-8000-000000000001"
        const val OTHER_ACTOR_ID = "00000000-0000-4000-8000-000000000002"
        const val GATEWAY_ORIGIN = "https://gateway.example.test"
    }
}
