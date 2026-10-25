package kr.co.hanium.dreamup.walksafe.session.gateway

import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.onboarding.*
import org.junit.Assert.*
import org.junit.Test

class ActorLifecycleCoordinatorTest {
    @Test fun constructorDoesNotReadAnyPeerOrCreateActualOwner() {
        val f = Fixture()
        assertTrue(f.events.isEmpty())
        assertEquals(0, f.ownerReads)
    }

    @Test fun observerRetiresAuthorityBeforeQueuedUiDelivery() {
        val f = Fixture(); f.consentChanged = true
        f.core.dispatchGatewayProcessSessionChanged(f.process)
        assertEquals(listOf("speech.retireGatewayAuthorityIfChanged", "gateway.snapshot", "consent.fenceAuthorityChanged",
            "reports.withdrawPrivacy", "reports.cancelQueueDrain", "runtime.cancelActivityOriginalUploads",
            "raw.initialized", "raw.cancelActiveUpload", "consent.reapplyActorBound", "runtime.dispatchObserverInlineIfMain"), f.events)
        assertEquals(1, f.queue.size)
        assertEquals(0, f.ownerReads)
    }

    @Test fun staleGenerationCallbackCannotPublishOrResumeRenewal() {
        val f = Fixture(); val old = f.process
        f.core.dispatchGatewayProcessSessionChanged(old)
        f.process = snapshot(generation = old.generation + 1)
        f.events.clear(); f.queue.removeAt(0).run()
        assertFalse(f.events.contains("gateway.processSessionChanged"))
        assertFalse(f.events.contains("gateway.retryPendingRenewal"))
        assertEquals(0, f.ownerReads)
    }

    @Test fun equalActorWithDifferentSessionInstanceRejectsLateUiDelivery() {
        val f = Fixture(); val old = snapshot(session = session())
        f.process = old; f.core.dispatchGatewayProcessSessionChanged(old)
        f.process = snapshot(session = session())
        f.events.clear(); f.queue.removeAt(0).run()
        assertFalse(f.events.contains("gateway.processSessionChanged"))
        assertEquals(0, f.ownerReads)
    }

    @Test fun restoredSnapshotOrPrivacyFlagsChangedRejectLateUiDelivery() {
        for (replacement in listOf(snapshot(firstRun = FirstRunOnboardingPolicy.initialEmailAccount()),
            snapshot(blocked = true), snapshot(recoveryOnly = true))) {
            val f = Fixture(); f.core.dispatchGatewayProcessSessionChanged(f.process)
            f.process = replacement; f.events.clear(); f.queue.removeAt(0).run()
            assertFalse(f.events.contains("gateway.processSessionChanged"))
            assertFalse(f.events.contains("gateway.retryPendingRenewal"))
        }
    }

    @Test fun lostActivityLeaseRejectsUiBeforeReadingLatestSession() {
        val f = Fixture(); f.core.dispatchGatewayProcessSessionChanged(f.process)
        f.live = false; f.events.clear(); f.queue.removeAt(0).run()
        assertEquals(listOf("privacy.callbackOwnerLive"), f.events)
    }

    @Test fun directSessionCallbackRetiresRuntimeEvenWhenPrivacyOwnerIsExpired() {
        val f = Fixture(); f.live = false
        f.core.onGatewayProcessSessionChanged(f.process, false)
        assertTrue(f.events.indexOf("gateway.processSessionChanged") < f.events.indexOf("privacy.callbackOwnerLive"))
        assertTrue(f.events.contains("raw.cancelActiveUpload"))
        assertTrue(f.events.contains("reports.clearAuthorityUiFence"))
        assertFalse(f.events.contains("gateway.fenceCapacityGeneration"))
        assertEquals(0, f.ownerReads)
    }

    @Test fun renewalRetryRemainsAfterTheAcceptedDeliveryEvenIfInnerOwnerExpires() {
        val f = Fixture(); f.liveAnswers.addAll(listOf(true, false))
        f.core.dispatchGatewayProcessSessionChanged(f.process)
        f.queue.removeAt(0).run()
        assertTrue(f.events.contains("gateway.retryPendingRenewal"))
        assertFalse(f.events.contains("gateway.fenceCapacityGeneration"))
    }

    @Test fun actorLossCancelsActualEducationThenResetsSameProfileOwner() {
        val f = Fixture(); f.profiles.actorId = ACTOR; val oldPolicy = f.profiles.policy
        f.core.onGatewayProcessSessionChanged(f.process, false)
        assertNull(f.reporter)
        assertNull(f.profiles.actorId)
        assertNotSame(oldPolicy, f.profiles.policy)
        assertTrue(f.events.indexOf("runtime.cancelEducation") < f.events.indexOf("permissions.setReporterActorId"))
        assertTrue(f.events.indexOf("permissions.expireAuthentication") < f.events.indexOf("runtime.startLocationIfAllowed"))
    }

    @Test fun staleRestoredOnboardingCannotRollBackLiveActorProgress() {
        val f = Fixture(); val live = deviceStage()
        f.firstRun.restoreSessionSnapshot(live)
        val earlier = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(FirstRunOnboardingPolicy.initialEmailAccount(),
            FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt()).current
        f.process = snapshot(session = session(), firstRun = earlier)
        f.core.onGatewayProcessSessionChanged(f.process, false)
        assertSame(live, f.firstRun.snapshot)
        assertEquals(ACTOR, f.reporter)
    }

    @Test fun actorPublicationPrecedesPermissionExpiryAndGpsStart() {
        val f = Fixture(); f.process = snapshot(session = session(), firstRun = deviceStage())
        f.core.onGatewayProcessSessionChanged(f.process, true)
        assertEquals(ACTOR, f.firstRun.snapshot.verifiedActorBinding?.value)
        assertEquals(ACTOR, f.reporter)
        assertTrue(f.events.indexOf("device.bindSession") < f.events.indexOf("permissions.remember"))
        assertTrue(f.events.indexOf("speech.invalidateStorage") < f.events.indexOf("permissions.remember"))
        assertTrue(f.events.indexOf("consent.reapplyActorBound") < f.events.indexOf("runtime.startLocationIfAllowed"))
        assertTrue(f.events.indexOf("runtime.startLocationIfAllowed") < f.events.indexOf("consent.refreshFromServer"))
    }

    @Test fun queuedBackendViewRefreshRechecksActivityLease() {
        val f = Fixture(); f.backendUi = true
        f.core.onGatewayProcessSessionChanged(f.process, false)
        assertEquals(1, f.uiQueue.size)
        f.live = false; f.events.clear(); f.uiQueue.removeAt(0).run()
        assertEquals(listOf("privacy.callbackOwnerLive"), f.events)
    }

    @Test fun failedGatewayPublicationDoesNotAdvanceOrAnnounceOnboarding() {
        val f = Fixture(); val before = deviceStage(); f.firstRun.restoreSessionSnapshot(before)
        f.process = snapshot(session = session()); f.publishAccepted = false
        val result = f.core.completePostLoginDeviceCheckPassEffects(passed())
        assertEquals(PostLoginDeviceCheckPassContinuation.SESSION_CHANGED, result)
        assertSame(before, f.firstRun.snapshot)
        assertFalse(f.events.any { it.startsWith("onboarding.") })
        assertTrue(f.events.indexOf("raw.resetEnrollmentInTask") < f.events.indexOf("gateway.publishFirstRun"))
        assertTrue(f.events.indexOf("runtime.scheduleTransfers") < f.events.indexOf("gateway.publishFirstRun"))
    }

    @Test fun successfulDevicePassPublishesBeforeActualFirstRunStateEffects() {
        val f = Fixture(); f.firstRun.restoreSessionSnapshot(deviceStage()); f.process = snapshot(session = session())
        assertEquals(PostLoginDeviceCheckPassContinuation.COMPLETED, f.core.completePostLoginDeviceCheckPassEffects(passed()))
        assertEquals(FirstRunOnboardingStage.FP004_TRAINING, f.firstRun.snapshot.stage)
        assertTrue(f.events.indexOf("gateway.publishFirstRun") < f.events.indexOf("onboarding.quickStartEnabled"))
        assertFalse(f.events.contains("onboarding.invalidateMetric"))
    }

    @Test fun rawResetUsesLiveDeviceSnapshotRatherThanCompletedArgument() {
        val f = Fixture(); f.process = snapshot(session = session()); f.deviceSnapshot = passed().copy(attemptGeneration = 99)
        f.core.completePostLoginDeviceCheckPassEffects(passed())
        assertSame(f.deviceSnapshot, f.rawResetDevice)
    }

    @Test fun privacyBlockedDevicePassDoesNotResetRawEnrollment() {
        val f = Fixture(); f.process = snapshot(session = session(), blocked = true)
        f.core.completePostLoginDeviceCheckPassEffects(passed())
        assertFalse(f.events.contains("raw.resetEnrollmentInTask"))
    }

    @Test fun consentRefreshNeedsActualCachedAuthorityAndNoPendingMutation() {
        for ((cached, pending) in listOf(false to false, true to true)) {
            val f = Fixture(); f.cached = cached; f.pending = pending
            f.process = snapshot(session = session(), firstRun = deviceStage())
            f.core.onGatewayProcessSessionChanged(f.process, true)
            assertFalse(f.events.contains("consent.refreshFromServer"))
        }
    }

    @Test fun completedCurrentActorAuthenticatesAfterSpeechActorFence() {
        val f = Fixture(); f.profiles.actorId = ACTOR
        f.profiles.policy = PriorityUserOnboardingPolicy(PriorityUserOnboardingSnapshot(ageBand = PriorityUserAgeBand.VERIFIED_14_PLUS))
        var training = deviceStage()
        training = FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(training, training.epoch, training.revision).current
        val begun = FirstRunOnboardingPolicy.beginAttempt(training,
            FirstRunOnboardingAttemptRequest.forSnapshot(training, "req_" + "d".repeat(64), "att_" + "e".repeat(64)))
        val completed = FirstRunOnboardingPolicy.completeAttempt(begun.current, begun.current.pendingAttempt!!,
            FirstRunOnboardingEvidence.Fp004Training(receipt()), FirstRunOnboardingEvidenceVerifier { _, _ -> true }).current
        assertTrue(completed.isComplete)
        f.process = snapshot(session = session(), firstRun = completed)
        f.core.onGatewayProcessSessionChanged(f.process, false)
        assertTrue(f.events.contains("permissions.authenticate"))
        assertTrue(f.events.indexOf("speech.invalidatePermissionActorIfChanged") < f.events.indexOf("permissions.authenticate"))
        assertFalse(f.events.contains("permissions.expireAuthentication"))
    }

    @Test fun blockedOrRecoveryOnlyActorCannotAuthenticateFromCompletedEnrollment() {
        for (process in listOf(snapshot(session = session(), firstRun = deviceStage(), blocked = true),
            snapshot(session = session(), firstRun = deviceStage(), recoveryOnly = true))) {
            val f = Fixture(); f.core.onGatewayProcessSessionChanged(process, false)
            assertFalse(f.events.contains("permissions.authenticate"))
            assertNull(f.reporter)
            assertNull(f.profiles.actorId)
            assertTrue(f.events.contains("runtime.cancelEducation"))
        }
    }

    @Test fun savedDeviceResultQueueRejectsReplacedAttemptBeforeAdvancingActualOwner() {
        val f = Fixture(); f.deviceSnapshot = passed()
        f.process = snapshot(session = session(), firstRun = deviceStage())
        f.core.onGatewayProcessSessionChanged(f.process, false)
        assertEquals(1, f.decorQueue.size)
        val before = f.firstRun.snapshot
        f.deviceSnapshot = passed().copy(attemptGeneration = 2)
        f.events.clear(); f.decorQueue.removeAt(0).run()
        assertSame(before, f.firstRun.snapshot)
        assertEquals(listOf("device.snapshot"), f.events)
    }

    private class Fixture {
        val events = mutableListOf<String>()
        val queue = mutableListOf<Runnable>(); val uiQueue = mutableListOf<Runnable>(); val decorQueue = mutableListOf<Runnable>()
        var process = snapshot()
        var live = true; val liveAnswers = mutableListOf<Boolean>()
        var consentChanged = false; var backendUi = false; var cached = true; var pending = false
        var reporter: String? = null; var publishAccepted = true; var ownerReads = 0
        var deviceSnapshot = PostLoginDeviceCheckPolicy.initial(); var rawResetDevice: PostLoginDeviceCheckSnapshot? = null
        val profiles = PriorityUserProfileController(
            storage = port<PriorityUserProfileController.Storage>("storage", events) { method, _ -> when(method.name) {
                "initialized" -> true; "blocked", "contains", "boolean" -> false; else -> null
            } },
            actors = port<PriorityUserProfileController.Actors>("profile", events) { method, _ -> when(method.name) {
                "reporterActorId" -> reporter; "deviceGatePassed" -> false; else -> null
            } }, digest = { "a".repeat(64) }, config = PriorityUserProfileController.Config("p", "i", 1, "probe"),
        ).apply { policy = PriorityUserOnboardingPolicy() }
        val firstRun = FirstRunOnboardingController(profiles,
            port<FirstRunOnboardingController.Gateway>("onboardingGateway", events) { method, _ -> when(method.name) {
                "fieldActorId" -> ACTOR; "storeInitialized" -> false; else -> null
            } },
            port<FirstRunOnboardingController.Education>("education", events) { _, _ -> false },
            port<FirstRunOnboardingController.Effects>("onboarding", events) { method, args -> when(method.name) {
                "reporterActorId" -> reporter
                "setReporterActor" -> { reporter = args[0] as String; null }
                "authenticatedFor", "screenReaderActive" -> true
                else -> null
            } }, { "a".repeat(64) },
        ).apply { restoreSessionSnapshot(FirstRunOnboardingPolicy.initial()) }
        val core = ActorSessionLifecycleCoordinator(
            gateway = port("gateway", events) { method, _ -> when(method.name) {
                "snapshot" -> process; "publishFirstRun" -> publishAccepted; else -> null
            } },
            routes = port("routes", events) { _, _ -> null },
            reports = port("reports", events) { method, _ -> method.name == "initialized" },
            raw = port("raw", events) { method, args -> when(method.name) {
                "initialized" -> true
                "resetEnrollmentInTask" -> { rawResetDevice = args[2] as PostLoginDeviceCheckSnapshot; null }
                else -> null
            } },
            consent = port("consent", events) { method, _ -> when(method.name) {
                "fenceAuthorityChanged" -> consentChanged; "cachedConfirmationPresent" -> cached
                "noPendingMutation" -> !pending; else -> null
            } },
            speech = port("speech", events) { _, _ -> null },
            permissions = port("permissions", events) { method, args -> when(method.name) {
                "getReporterActorId" -> reporter
                "setReporterActorId" -> { reporter = args[0] as String?; null }
                else -> null
            } },
            device = port("device", events) { method, _ -> when(method.name) {
                "snapshot" -> deviceSnapshot; "disabledFeatureText" -> "카메라"; else -> null
            } },
            privacy = port("privacy", events) { method, _ -> if(method.name == "callbackOwnerLive") {
                if(liveAnswers.isEmpty()) live else liveAnswers.removeAt(0)
            } else null },
            runtime = port("runtime", events) { method, args -> when(method.name) {
                "dispatchObserverInlineIfMain" -> { queue += args[0] as Runnable; null }
                "runOnUiThread" -> { uiQueue += args[0] as Runnable; null }
                "postDecor" -> { decorQueue += args[0] as Runnable; null }
                "backendUiReady" -> backendUi; else -> null
            } },
            firstRunSource = { ownerReads++; firstRun }, profilesSource = { ownerReads++; profiles },
        )
    }

    private companion object {
        const val ACTOR = "actor_abcdef0123456789abcdef0123456789"
        fun snapshot(session: GatewayFieldSession? = null, firstRun: FirstRunOnboardingSnapshot? = null,
            generation: Long = 7, blocked: Boolean = false, recoveryOnly: Boolean = false) =
            GatewaySessionProcessSnapshot(session, firstRun, generation, blocked, null, recoveryOnly)
        fun session() = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
            "walksafe_field_session=fixture", Long.MAX_VALUE)
        fun receipt() = FirstRunReceiptHash.fromSha256Hex("a".repeat(64))
        fun deviceStage(): FirstRunOnboardingSnapshot {
            var s = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(FirstRunOnboardingPolicy.initialEmailAccount(),
                FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt()).current
            s = FirstRunOnboardingPolicy.acknowledgePurposeAndSafety(s,
                FirstRunOnboardingAttemptRequest.forSnapshot(s, "req_" + "b".repeat(64), "att_" + "c".repeat(64)), receipt()).current
            return FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(s, s.epoch, s.revision).current
        }
        fun passed() = PostLoginDeviceCheckSnapshot(PostLoginDeviceCheckState.FULL, ACTOR, 7, 1)
    }
}

internal inline fun <reified T> port(label: String, events: MutableList<String>,
    crossinline action: (Method, Array<out Any?>) -> Any?): T = Proxy.newProxyInstance(T::class.java.classLoader,
    arrayOf(T::class.java)) { _, method, args ->
        events += "$label.${method.name}"
        val result = action(method, args ?: emptyArray())
        result ?: when(method.returnType) {
            java.lang.Boolean.TYPE -> false; java.lang.Long.TYPE -> 0L; java.lang.Integer.TYPE -> 0; else -> null
        }
    } as T
