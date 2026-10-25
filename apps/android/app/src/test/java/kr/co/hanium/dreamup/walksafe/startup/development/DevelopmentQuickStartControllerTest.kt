package kr.co.hanium.dreamup.walksafe.startup.development

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/** Actual quick-start state and lifecycle/token decisions; SDK/account/consent effects are controlled. */
class DevelopmentQuickStartControllerTest {
    @Test fun constructionAndDisabledEntryDoNotQueryOwnersOrScheduleWork() {
        val f = Fixture(); assertTrue(f.events.isEmpty())
        f.enabled = false
        f.controller.startDevelopmentQuickStart(); f.controller.loginDevelopmentQuickStartAccount()
        assertNull(f.controller.developmentQuickStartSessionOrNull()); f.controller.showAccountStatusIfEnabled("hidden")
        assertTrue(f.events.isEmpty()); assertFalse(f.controller.requested); assertTrue(f.tasks.isEmpty())
    }

    @Test fun sessionRequiresTheVerifiedActorAndEveryOriginalProcessBoundary() {
        val f = Fixture(); f.session = f.verified
        assertNull(f.controller.developmentQuickStartSessionOrNull())
        f.controller.noteAuthenticatedActor(f.verified.actorId)
        assertSame(f.verified, f.controller.developmentQuickStartSessionOrNull())
        f.storageBlocked = true; assertNull(f.controller.developmentQuickStartSessionOrNull()); f.storageBlocked = false
        f.deletionOnly = true; assertNull(f.controller.developmentQuickStartSessionOrNull()); f.deletionOnly = false
        f.deletionBlocked = true; assertNull(f.controller.developmentQuickStartSessionOrNull()); f.deletionBlocked = false
        f.origin = "https://different.example.test"; assertNull(f.controller.developmentQuickStartSessionOrNull()); f.origin = f.verified.gatewayBaseUrl
        f.session = GatewayFieldSession.legacyVerified(f.origin, f.verified.actorId, "device", "walksafe_field_session=test",
            Long.MAX_VALUE, GatewaySessionScope.ACCOUNT_DELETION_RECOVERY)
        assertNull(f.controller.developmentQuickStartSessionOrNull())
        f.session = GatewayFieldSession.verified(f.origin, f.verified.actorId, "walksafe_field_session=expired", 1L)
        assertNull(f.controller.developmentQuickStartSessionOrNull())
    }

    @Test fun loginRetiresOldActorAndInitializesFirstRunBeforeConfiguredLogin() {
        val f = Fixture(); f.session = f.verified
        f.controller.loginDevelopmentQuickStartAccount()
        assertEquals(listOf("process", "clear-local-session", "initial-email", "status", "login"), f.events)
        assertTrue(f.emailInFlight)
        f.session = f.verified
        assertNull(f.controller.developmentQuickStartSessionOrNull())
        f.controller.noteAuthenticatedActor(f.verified.actorId)
        assertSame(f.verified, f.controller.developmentQuickStartSessionOrNull())
    }

    @Test fun missingConfiguredCredentialsDoNotClearSessionOrAttemptLogin() {
        val f = Fixture(); f.credentials = false
        f.controller.startDevelopmentQuickStart()
        assertFalse(f.controller.requested)
        assertEquals(1, f.events.count { it == "account-message" })
        assertFalse(f.events.contains("clear-local-session")); assertFalse(f.events.contains("login"))
        assertTrue(f.status.contains("이메일과 비밀번호를 입력")); assertTrue(f.tasks.isEmpty())
    }

    @Test fun pollingUsesOneRunnableAndNeverMovesTheOriginalExpiry() {
        val f = Fixture()
        f.controller.startDevelopmentQuickStart()
        val originalTask = f.tasks.keys.single()
        assertEquals(1_500L, f.tasks[originalTask])
        f.runAt(1_500L)
        assertSame(originalTask, f.tasks.keys.single())
        assertEquals(1, f.events.count { it == "login" })
        f.foreground = false
        f.runAt(45_500L)
        assertTrue(f.controller.requested); assertEquals(46_000L, f.tasks[originalTask])
        val waiting = f.status
        f.runAt(46_000L)
        assertFalse(f.controller.requested); assertTrue(f.tasks.isEmpty())
        assertEquals("테스트 준비 시간 초과. $waiting 다시 시도하세요.", f.status)
    }

    @Test fun destroyedCallbackAndPausedButtonCannotStartANewWalk() {
        val f = Fixture(); f.controller.startDevelopmentQuickStart()
        val pending = f.tasks.keys.single(); f.destroyed = true; val count = f.events.size
        f.runAt(1_500L); assertEquals(count, f.events.size)
        f.destroyed = false; f.activateWalk(); f.lifecycle.handle(WalkSessionEvent.EnteredBackground)
        assertEquals(WalkSessionState.PAUSED, f.lifecycle.snapshot().state)
        f.foreground = true; f.controller.startDevelopmentQuickStart()
        assertFalse(f.controller.requested); assertEquals(1, f.events.count { it == "existing-confirmation" })
        assertTrue(f.events.contains("remove")); assertTrue(f.gatewayTokens.isEmpty())
        pending.run(); assertTrue(f.gatewayTokens.isEmpty())
    }

    @Test fun permissionsAreRequestedOnceAndDenialEndsTheOwnedAttempt() {
        val f = Fixture(); f.bindSession(); f.missing = listOf("camera", "location")
        f.controller.startDevelopmentQuickStart()
        assertEquals(listOf(f.missing), f.permissionRequests)
        f.runAt(1_500L); assertEquals(1, f.permissionRequests.size)
        assertTrue(f.status.contains("Android 권한"))
        f.permissionInFlight = false; f.runAt(2_000L)
        assertFalse(f.controller.requested); assertEquals(1, f.permissionRequests.size)
        assertTrue(f.status.contains("camera-label, location-label")); assertTrue(f.tasks.isEmpty())
        assertFalse(f.events.contains("probe")); assertTrue(f.gatewayTokens.isEmpty())
    }

    @Test fun consentBootstrapWaitAndSingleSubmissionKeepTheOriginalServerOwner() {
        val f = Fixture(); f.bindSession(); f.confirmation = null; f.bootstrap = false
        f.controller.startDevelopmentQuickStart(); assertEquals(1, f.events.count { it == "consent-refresh" })
        f.consentInFlight = true; f.runAt(1_500L)
        assertEquals(0, f.events.count { it == "consent-draft" })
        f.consentInFlight = false; f.bootstrap = true; f.runAt(2_000L)
        assertEquals(listOf<IntegratedConsentConfirmation?>(null), f.selectedDrafts)
        assertEquals(1, f.events.count { it == "consent-persist" })
        f.runAt(2_500L)
        assertFalse(f.controller.requested); assertEquals(1, f.selectedDrafts.size)
        assertTrue(f.gatewayTokens.isEmpty())
    }

    @Test fun realReadyTokenIsHandedOffOnceAfterRecordAndConfirmation() {
        val f = Fixture(); f.bindSession()
        f.controller.startDevelopmentQuickStart()
        assertFalse(f.controller.requested); assertEquals(1, f.gatewayTokens.size)
        assertEquals(f.lifecycle.snapshot().confirmationToken, f.gatewayTokens.single())
        assertSame(f.lifecycle.snapshot().readiness, f.gatewayTokens.single().readiness)
        val filtered = f.events.filter { it in setOf("bind-actor", "monitor", "probe", "resolve", "record", "readiness", "transition", "confirm", "gateway-start", "refresh") }
        assertEquals(listOf("bind-actor", "monitor", "probe", "resolve", "record", "readiness", "transition", "confirm", "gateway-start", "refresh"), filtered)
        assertEquals(WalkSessionState.READY, f.lifecycle.snapshot().state)
        f.controller.advanceDevelopmentQuickStart(); assertEquals(1, f.gatewayTokens.size)
        f.controller.startDevelopmentQuickStart(); assertEquals(1, f.gatewayTokens.size)
        assertTrue(f.status.contains("시작 응답"))
    }

    @Test fun alreadyActiveButtonDoesNotReadAccountOrScheduleAnotherStart() {
        val f = Fixture(); f.activateWalk()
        val epoch = f.lifecycle.snapshot().epoch
        f.controller.startDevelopmentQuickStart()
        assertEquals(listOf("status"), f.events)
        assertEquals(epoch, f.lifecycle.snapshot().epoch)
        assertTrue(f.status.contains("실행 중")); assertFalse(f.controller.requested)
        assertTrue(f.tasks.isEmpty()); assertTrue(f.gatewayTokens.isEmpty())
    }

    @Test fun endedWalkCannotReuseOldApprovalBeforeTheNewWalkEntersForeground() {
        val f = Fixture(); f.activateWalk()
        val oldEpoch = f.lifecycle.snapshot().epoch
        f.lifecycle.handle(WalkSessionEvent.EndRequested)
        assertEquals(WalkSessionState.ENDED, f.lifecycle.snapshot().state)
        f.bindSession(); f.controller.startDevelopmentQuickStart()
        assertEquals(1, f.events.count { it == "fresh-walk" })
        assertNotEquals(oldEpoch, f.lifecycle.snapshot().epoch)
        assertFalse(f.lifecycle.snapshot().isForeground)
        assertNull(f.lifecycle.snapshot().confirmationToken)
        assertTrue(f.gatewayTokens.isEmpty()); assertFalse(f.events.contains("confirm"))
        assertTrue(f.events.indexOf("fresh-walk") < f.events.indexOf("readiness"))
        assertTrue(f.status.contains("시작 상태를 만들지 못했습니다"))
        assertFalse(f.controller.requested); assertTrue(f.tasks.isEmpty())
    }

    @Test fun actualReadinessFailureWaitsWithoutConfirmingOrStartingAndCanRecover() {
        val f = Fixture(); f.bindSession(); f.readinessBlocked = true
        f.controller.startDevelopmentQuickStart()
        assertTrue(f.controller.requested); assertTrue(f.gatewayTokens.isEmpty())
        assertFalse(f.events.contains("confirm")); assertTrue(f.status.contains("실제 실행 준비"))
        f.readinessBlocked = false; f.runAt(1_500L)
        assertFalse(f.controller.requested); assertEquals(1, f.gatewayTokens.size)
        assertEquals(2, f.events.count { it == "readiness" })
    }

    private class Fixture {
        var enabled = true; var foreground = true; var destroyed = false; var now = 1_000L
        var status = ""; var emailInFlight = false; var credentials = true; var accountMessage: String? = null
        var storageBlocked = false; var deletionOnly = false; var deletionBlocked = false
        var origin = "https://gateway.example.test"
        val verified = GatewayFieldSession.verified(origin, "actor_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "walksafe_field_session=quick-start-test", Long.MAX_VALUE)
        var session: GatewayFieldSession? = null
        var permissionInFlight = false; var missing = emptyList<String>(); var readinessBlocked = false
        var consentInFlight = false; var bootstrap = true
        var confirmation: IntegratedConsentConfirmation? = confirmedConsent()
        val events = mutableListOf<String>(); val tasks = linkedMapOf<Runnable, Long>()
        val permissionRequests = mutableListOf<List<String>>(); val selectedDrafts = mutableListOf<IntegratedConsentConfirmation?>()
        val gatewayTokens = mutableListOf<WalkSessionConfirmationToken>()
        var nextWalkId = 1
        val lifecycle = WalkSessionLifecycle({ "quick-start-${nextWalkId++}" }, { now }, { now }).apply { handle(WalkSessionEvent.EnteredForeground) }
        val controller = DevelopmentQuickStartController(object : DevelopmentQuickStartPlatform {
            override val enabled get() = this@Fixture.enabled
            override val foreground get() = this@Fixture.foreground
            override val destroyed get() = this@Fixture.destroyed
            override fun nowMs() = now
            override fun statusText() = status
            override fun showStatus(message: String) { status = message; events += "status" }
            override fun remove(task: Runnable) { tasks.remove(task); events += "remove" }
            override fun postDelayed(task: Runnable, delayMs: Long) { tasks[task] = now + delayMs; assertEquals(500L, delayMs); events += "schedule" }
            override fun refreshFirstRunUi() { events += "refresh" }
        }, object : DevelopmentQuickStartAccount {
            override fun processSnapshot() = GatewaySessionProcessSnapshot(session, null, 1L, storageBlocked, null, deletionOnly).also { events += "process" }
            override fun deletionBlocksProcessing() = deletionBlocked
            override fun configuredOrigin() = origin
            override fun requestInFlight() = emailInFlight
            override fun credentialsAvailable() = credentials
            override fun accountNotice() = accountMessage
            override fun showAccountMessage(message: String) { accountMessage = message; events += "account-message" }
            override fun hasGatewaySession() = session != null
            override fun clearGatewaySessionWithoutRemoteLogout() { session = null; events += "clear-local-session" }
            override fun prepareInitialEmailAccount() { events += "initial-email" }
            override fun loginConfiguredAccount() { emailInFlight = true; events += "login" }
            override fun bindAuthenticatedActor(actorId: String) { assertEquals(verified.actorId, actorId); events += "bind-actor" }
        }, object : DevelopmentQuickStartDevice {
            override fun missingPermissions() = missing
            override fun permissionRequestInFlight() = permissionInFlight
            override fun requestPermissionsWithOwnedLease(permissions: List<String>) { permissionRequests += permissions; permissionInFlight = true }
            override fun permissionLabel(permission: String) = "$permission-label"
            override fun ensureResourceMonitoring() { events += "monitor" }
            override fun ensureCapabilityProbeStarted() { events += "probe" }
        }, object : DevelopmentQuickStartConsent {
            override fun currentConfirmation() = confirmation
            override fun requestInFlight() = consentInFlight
            override fun bootstrapReady() = bootstrap
            override fun refreshFromServer() { events += "consent-refresh" }
            override fun selectDevelopmentDraft(confirmation: IntegratedConsentConfirmation?) { selectedDrafts += confirmation; events += "consent-draft" }
            override fun persistDraftWithoutAnnouncement() { events += "consent-persist" }
        }, object : DevelopmentQuickStartWalk {
            override fun snapshot() = lifecycle.snapshot()
            override fun runtimeActive() = lifecycle.currentRuntimeEpochOrNull() != null
            override fun gatewayStartPending() = gatewayTokens.isNotEmpty()
            override fun handleExistingConfirmationAction() { events += "existing-confirmation" }
            override fun startFreshWalk(reason: String) { lifecycle.handle(WalkSessionEvent.NewWalkRequested); events += "fresh-walk" }
            override fun resolveCapability() = WalkSafeStartupCapabilityDecision(WalkSafeStartupCapabilityTier.FULL, emptyList(), emptyList(), "준비됨").also { events += "resolve" }
            override fun recordCapability(decision: WalkSafeStartupCapabilityDecision) { events += "record" }
            override fun captureStartReadiness(decision: WalkSafeStartupCapabilityDecision): WalkSessionReadinessSnapshot {
                events += "readiness"
                return readinessSnapshot()
            }
            override fun transition(event: WalkSessionEvent) { lifecycle.handle(event); events += "transition" }
            override fun confirmCapabilityForGatewayStart(decision: WalkSafeStartupCapabilityDecision) { events += "confirm" }
            override fun requestGatewayStart(token: WalkSessionConfirmationToken) { gatewayTokens += token; events += "gateway-start" }
        })
        fun readinessSnapshot(): WalkSessionReadinessSnapshot {
                val collector = WalkSessionReadinessCollector(lifecycle.snapshot().epoch, WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
                collector.plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(lifecycle.snapshot().epoch, it,
                    if (readinessBlocked && it == WalkSessionReadinessRequirement.LOCATION) WalkSessionReadinessStatus.PENDING else WalkSessionReadinessStatus.READY,
                    if (readinessBlocked && it == WalkSessionReadinessRequirement.LOCATION) "location_pending" else "")) }
                return collector.build(now)
        }
        fun activateWalk() {
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readinessSnapshot()))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
        }
        fun bindSession() { session = verified; controller.noteAuthenticatedActor(verified.actorId) }
        fun runAt(at: Long) { now = at; val task = tasks.keys.single(); tasks.remove(task); task.run() }
        companion object {
            fun confirmedConsent() = IntegratedConsentConfirmation(
                INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION, INTEGRATED_CONSENT_POLICY_VERSION,
                "501e3ad4-e74f-4433-820f-72ac2fdd42ad", "integrated_consent_request_0001", IntegratedConsentItemVersions(),
                1L, 1L, IntegratedConsentSelections(rawSourceCollection = true, automaticReporting = true),
                "2026-07-25T12:00:00.000Z", "9".repeat(64), "a".repeat(64), "b".repeat(64),
            )
        }
    }
}
