package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Real Main admission/fencing leaves and real memory authority owners. READY observations below
 * are explicit typed JVM inputs, not physical device/backend evidence. SOURCE checks separately
 * cover the private listener caller. No Activity.onCreate, Android dialog, crypto or native I/O.
 */
class MainActivitySpeechOutputStorageBindingBehaviorTest {
    @Before fun clearGateway() {
        assertNotNull(GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation))
    }

    @After fun releaseGateway() {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }

    @Test fun realReductionFencesOldEpochBeforePublicationAndSamplesEachClockOnce() {
        var wallSamples = 0
        var elapsedSamples = 0
        val lifecycle = activeLifecycle(
            wall = { wallSamples++; 1_000L }, elapsed = { elapsedSamples++; 1_000L },
        )
        wallSamples = 0
        elapsedSamples = 0
        val before = lifecycle.snapshot()
        val main = MainActivity()
        var fences = 0
        var callbacks = 0
        val transition = lifecycle.handle(WalkSessionEvent.SafetyStopRequested) { previous, next ->
            callbacks++
            assertSame(before, previous)
            assertSame(before, lifecycle.snapshot())
            main.fenceSpeechOutputStorageBeforeWalkPublication(previous, next) { fences++ }
            assertEquals(1, fences)
        }
        assertSame(transition.current, lifecycle.snapshot())
        assertEquals(WalkSessionState.SAFE_STOP, transition.current.state)
        assertNotEquals(before.epoch, transition.current.epoch)
        assertEquals(1, callbacks)
        assertEquals(1, wallSamples)
        assertEquals(1, elapsedSamples)
    }

    @Test fun sameActiveReadinessRefreshDoesNotEndThePrivacyLifetime() {
        var now = 1_000L
        val lifecycle = activeLifecycle(wall = { now }, elapsed = { now })
        val main = MainActivity()
        val original = lifecycle.snapshot()
        now = 1_001L
        val refreshed = ready(lifecycle, now)
        var fences = 0
        var callbacks = 0
        lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(refreshed)) { previous, next ->
            callbacks++
            assertSame(original, lifecycle.snapshot())
            main.fenceSpeechOutputStorageBeforeWalkPublication(previous, next) { fences++ }
        }
        assertEquals(1, callbacks)
        assertEquals(0, fences)
        assertEquals(original.epoch, lifecycle.snapshot().epoch)
        assertSame(refreshed, lifecycle.snapshot().readiness)
    }

    @Test fun backgroundReductionFencesBeforeTheNewForegroundOwnerIsPublished() {
        val lifecycle = activeLifecycle()
        val previous = lifecycle.snapshot()
        val main = MainActivity()
        var fences = 0
        lifecycle.handle(WalkSessionEvent.EnteredBackground) { before, next ->
            assertSame(previous, lifecycle.snapshot())
            main.fenceSpeechOutputStorageBeforeWalkPublication(before, next) { fences++ }
            assertEquals(1, fences)
        }
        assertFalse(lifecycle.snapshot().isForeground)
        assertNotEquals(previous.epoch, lifecycle.snapshot().epoch)
    }

    @Test fun callbackExceptionPreservesTheOriginalSnapshotAndCauseWithoutPublishing() {
        val lifecycle = activeLifecycle()
        val previous = lifecycle.snapshot()
        val failure = IllegalStateException("memory fence observer failed")
        var callbacks = 0
        try {
            lifecycle.handle(WalkSessionEvent.EnteredBackground) { before, next ->
                callbacks++
                assertSame(previous, before)
                assertFalse(next.isForeground)
                throw failure
            }
            fail("The callback's cause must remain observable")
        } catch (observed: IllegalStateException) { assertSame(failure, observed) }
        assertEquals(1, callbacks)
        assertSame(previous, lifecycle.snapshot())
        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        assertFalse(lifecycle.snapshot().isForeground)
    }

    @Test fun defaultHandleKeepsTheSameReducerResultAsAnExplicitNoopCallback() {
        val default = activeLifecycle()
        val explicit = activeLifecycle()
        val first = default.handle(WalkSessionEvent.EnteredBackground)
        val second = explicit.handle(WalkSessionEvent.EnteredBackground) { _, _ -> }
        assertEquals(first, second)
        assertEquals(default.snapshot(), explicit.snapshot())
    }

    @Test fun actualAdmissionKeepsActivityOptionalAndRequiresCameraPreciseLocationAndMic() {
        val fixture = AuthorityFixture()
        val main = MainActivity()
        assertTrue(fixture.admitted(main))
        assertTrue(fixture.admitted(main, ObservedPermissionSnapshot(true, true, true, false)))
        for (missing in listOf(
            ObservedPermissionSnapshot(false, true, true, true),
            ObservedPermissionSnapshot(true, false, true, true),
            ObservedPermissionSnapshot(true, true, false, true),
        )) assertFalse(fixture.admitted(main, missing))
        assertFalse(fixture.admitted(main, device = fixture.device.copy(
            state = PostLoginDeviceCheckState.NOT_RUN,
        )))
        assertFalse(fixture.admitted(main, device = fixture.device.copy(actorId = "other-account")))
        assertFalse(fixture.admitted(main, device = fixture.device.copy(sessionGeneration = fixture.generation + 1L)))
    }

    @Test fun permissionPolicyChangesRevokeCurrentnessWithoutAnOsOrMainGraphCallback() {
        val fixture = AuthorityFixture()
        assertTrue(fixture.authority.privacyIsCurrent())
        fixture.permissions.setRawCollectionConsent(false)
        assertFalse(fixture.authority.privacyIsCurrent())
        fixture.permissions.setRawCollectionConsent(true)
        fixture.permissions.authenticationExpired()
        assertFalse(fixture.authority.privacyIsCurrent())
    }

    @Test fun unrelatedReportAndNetworkChoicesDoNotRevokeTheRawPrivacyGrant() {
        val fixture = AuthorityFixture()
        fixture.permissions.setAutomaticReportConsent(true)
        fixture.permissions.setTrainingReuseConsent(true)
        fixture.permissions.setMobileNetworkPreference(MobileNetworkPreference.ALLOW_CELLULAR)
        assertTrue(fixture.authority.privacyIsCurrent())
    }

    @Test fun currentRawReceiptDeniesAfterWithdrawalOrBackendAuthorityLoss() {
        val fixture = AuthorityFixture()
        assertTrue(fixture.authority.privacyIsCurrent())
        assertTrue(fixture.consent.invalidateBackendAuthority())
        assertNotNull(fixture.consent.cachedConfirmationOrNull())
        assertFalse(fixture.authority.privacyIsCurrent())
        assertEquals(IntegratedConsentApplyResult.DUPLICATE,
            fixture.consent.applyConfirmation(fixture.receipt, fixture.guard))
        assertTrue(fixture.consent.withdrawImmediately(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertFalse(fixture.authority.privacyIsCurrent())
    }

    @Test fun foreignGatewayAndOldWalkEpochCannotKeepAQueuedBindingCurrent() {
        val fixture = AuthorityFixture()
        assertTrue(fixture.authority.privacyIsCurrent())
        fixture.lifecycle.handle(WalkSessionEvent.EnteredBackground)
        assertFalse(fixture.authority.privacyIsCurrent())
        val other = AuthorityFixture()
        assertTrue(other.authority.privacyIsCurrent())
        assertNotNull(GatewaySessionProcessCoordinator.clear(other.generation))
        assertFalse(other.authority.privacyIsCurrent())
    }

    @Test fun realAccountDeletionAuthorityAndPermanentBindingFenceBothFailClosed() {
        val fixture = AuthorityFixture()
        val lease = fixture.deletion.attachActivity()
        assertTrue(fixture.deletion.enforceFailClosedAuthority(lease, "privacy_fixture_account_intent"))
        assertFalse(fixture.authority.privacyIsCurrent())
        val fresh = AuthorityFixture()
        fresh.binding.invalidate()
        assertFalse(fresh.authority.privacyIsCurrent())
    }

    @Test fun engineReadinessIsNotAConsentInputAndSameActiveRefreshKeepsAdmittedErrorPrivacy() {
        val fixture = AuthorityFixture()
        val before = fixture.binding
        fixture.now = 1_001L
        fixture.lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(ready(fixture.lifecycle, fixture.now)))
        assertSame(before, fixture.binding)
        assertTrue(fixture.authority.privacyIsCurrent())
        // This observes the privacy decision only; actual ENGINE_ERROR disk delivery is separate.
        fixture.lifecycle.handle(WalkSessionEvent.SafetyStopRequested)
        assertFalse(fixture.authority.privacyIsCurrent())
    }

    @Test fun staleRawFalseEvaluationCannotRetireOrPurgeTheCurrentGrantedCollection() {
        val fixture = AuthorityFixture(initialReceipt = confirmation().copy(revision = 3L, clientRevision = 3L))
        val stale = fixture.receipt.copy(revision = 2L, selections = IntegratedConsentSelections())
        val evaluation = fixture.consent.evaluateConfirmation(stale)
        assertEquals(IntegratedConsentApplyResult.STALE_IGNORED, evaluation)
        var purges = 0
        var fences = 0
        var receiptReads = 0
        val accepted = MainActivity().prepareSpeechOutputStorageConsentApply(
            stale, evaluation, fixture.receipt,
            { receiptReads++; fixture.consent.currentConfirmationOrNull(setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION)) },
            withdrawRaw = { purges++ }, invalidate = { fences++ },
        )
        assertFalse(accepted)
        assertEquals("Rejected stale response must not submit the actual RAW withdrawal/purge callback", 0, purges)
        assertEquals(0, fences)
        assertEquals(0, receiptReads)
        assertSame(fixture.receipt, fixture.consent.currentConfirmationOrNull(setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION)))
        assertTrue(fixture.authority.privacyIsCurrent())
    }

    @Test fun acceptedRawWithdrawalKeepsTheOriginalFenceBeforeConsentMutation() {
        val fixture = AuthorityFixture()
        val withdrawal = fixture.receipt.copy(revision = 2L, clientRevision = 2L,
            selections = IntegratedConsentSelections())
        val evaluation = fixture.consent.evaluateConfirmation(withdrawal)
        assertEquals(IntegratedConsentApplyResult.APPLIED, evaluation)
        var purges = 0
        var fences = 0
        var receiptReads = 0
        val accepted = MainActivity().prepareSpeechOutputStorageConsentApply(
            withdrawal, evaluation, fixture.receipt,
            { receiptReads++; fixture.consent.currentConfirmationOrNull(setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION)) },
            withdrawRaw = {
                assertSame(fixture.receipt, fixture.consent.currentConfirmationOrNull(setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION)))
                fixture.binding.invalidate()
                purges++
            }, invalidate = { fences++ },
        )
        assertTrue(accepted)
        assertEquals(1, purges)
        assertEquals(0, fences)
        assertEquals(0, receiptReads)
        assertFalse(fixture.authority.privacyIsCurrent())
        assertEquals(IntegratedConsentApplyResult.APPLIED,
            fixture.consent.applyConfirmation(withdrawal, fixture.guard))
        assertNull(fixture.consent.currentConfirmationOrNull(setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION)))
    }

    @Test fun purgeReasonUsesRawAdapterAndNeverDowngradesStickyAccountIntent() {
        val storage = PurgeCounterStorage()
        var rawPurges = 0
        val adapter = SpeechOutputStoragePurgeAdapter(storage) { rawPurges++; true }
        assertFalse(adapter.purgeAll())
        assertEquals(0, storage.accountPurges)
        adapter.markRawWithdrawal()
        assertTrue(adapter.purgeAll())
        assertEquals(1, rawPurges)
        assertEquals(0, storage.accountPurges)
        adapter.markAccountDeletion()
        adapter.markRawWithdrawal()
        assertTrue(adapter.purgeAll())
        assertEquals(1, rawPurges)
        assertEquals(1, storage.accountPurges)
        assertEquals(0, storage.cryptoGetterCalls)
    }

    @Test fun purgeFailureIsReturnedAsFalseRatherThanDeletionAcknowledgement() {
        val storage = PurgeCounterStorage(accountResult = false)
        val adapter = SpeechOutputStoragePurgeAdapter(storage) { false }
        adapter.markRawWithdrawal()
        assertFalse(adapter.purgeAll())
        adapter.markAccountDeletion()
        assertFalse(adapter.purgeAll())
        assertEquals(1, storage.accountPurges)
    }

    @Test fun cleanupWaitsForBothActualFuturesAndPendingNeverBecomesASuccessAck() {
        val main = MainActivity()
        val purge = CompletableFuture<Boolean>()
        val drain = CompletableFuture<Boolean>()
        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val result = AtomicReference<Boolean?>()
        val worker = Thread {
            entered.countDown()
            result.set(main.awaitSpeechOutputStorageCleanup(purge, drain, requiresPurge = true))
            returned.countDown()
        }
        worker.start()
        try {
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            assertNull(result.get())
            purge.complete(true)
            assertFalse(returned.await(20L, TimeUnit.MILLISECONDS))
            drain.complete(true)
            assertTrue(returned.await(2L, TimeUnit.SECONDS))
            assertEquals(true, result.get())
        } finally {
            purge.complete(false)
            drain.complete(false)
            worker.join(2_000L)
        }
        assertFalse(worker.isAlive)
    }

    @Test fun falseOrExceptionalPurgeAndFalseDrainNeverPermitDeletionOrFreshReset() {
        val main = MainActivity()
        val yes = CompletableFuture.completedFuture(true)
        val no = CompletableFuture.completedFuture(false)
        assertFalse(main.awaitSpeechOutputStorageCleanup(no, yes, requiresPurge = true))
        assertFalse(main.awaitSpeechOutputStorageCleanup(yes, no, requiresPurge = true))
        assertFalse(main.awaitSpeechOutputStorageCleanup(null, yes, requiresPurge = true))
        val failed = CompletableFuture<Boolean>()
        val cause = IllegalStateException("real purge future failed")
        failed.completeExceptionally(cause)
        assertFalse(main.awaitSpeechOutputStorageCleanup(failed, yes, requiresPurge = true))
        assertTrue(failed.isCompletedExceptionally)
        assertSame(cause, runCatching { failed.get() }.exceptionOrNull()?.cause)
    }

    @Test fun lifetimeCloseConsumesDrainWithoutClaimingOrRequiringADeletionAck() {
        val main = MainActivity()
        assertTrue(main.awaitSpeechOutputStorageCleanup(null,
            CompletableFuture.completedFuture(true), requiresPurge = false))
        assertFalse(main.awaitSpeechOutputStorageCleanup(null,
            CompletableFuture.completedFuture(false), requiresPurge = false))
    }

    @Test fun interruptionIsPreservedAndDoesNotTurnPendingCleanupIntoASuccess() {
        val result = AtomicReference<Boolean?>()
        val interruptPreserved = AtomicReference<Boolean?>()
        val entered = CountDownLatch(1)
        val main = MainActivity()
        val worker = Thread {
            entered.countDown()
            result.set(main.awaitSpeechOutputStorageCleanup(CompletableFuture(), CompletableFuture(), true))
            interruptPreserved.set(Thread.currentThread().isInterrupted)
        }
        worker.start()
        try {
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(2_000L)
            assertFalse(worker.isAlive)
            assertEquals(false, result.get())
            assertEquals(true, interruptPreserved.get())
        } finally { worker.interrupt(); worker.join(2_000L) }
    }

    @Test fun sourceStartupPreparationWaitsOnceOutsideBothLocksAndRechecksExactOwners() {
        val source = mainSource()
        val controller = File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/PrivacyStartupInspectionController.kt").readText()
        val startup = controller.substringAfter("fun startInspection(").substringBefore("fun isCurrent(")
        assertTrue(source.contains("privacyStartupInspectionController.startInspection(prepare, inspect, onReady)"))
        assertEquals(1, Regex("(?<![.A-Za-z])prepare\\(\\)").findAll(startup).count())
        assertEquals(1, Regex("resetPreparation.prepare\\(\\)").findAll(startup).count())
        val prepareProcessLock = startup.indexOf("synchronized(PRIVACY_STARTUP_PROCESS_LOCK)")
        val prepareResourceLock = startup.indexOf("synchronized(privacyStartupResourceLock)")
        val waitStart = startup.indexOf("preparedReset?.let { preparation ->")
        val wait = startup.indexOf("preparation.awaitOriginalAcknowledgements()")
        val resumedProcessLock = startup.indexOf("synchronized(PRIVACY_STARTUP_PROCESS_LOCK)", wait)
        val resumedResourceLock = startup.indexOf("synchronized(privacyStartupResourceLock)", wait)
        val ownerCheck = startup.indexOf("!preparation.isCurrent()", wait)
        val inspect = startup.indexOf("inspect(purgedAndDrained)", wait)
        assertTrue(prepareProcessLock >= 0 && prepareResourceLock > prepareProcessLock)
        assertTrue(waitStart > prepareResourceLock && wait > waitStart)
        assertTrue(resumedProcessLock > wait && resumedResourceLock > resumedProcessLock)
        assertTrue(ownerCheck > resumedResourceLock && inspect > ownerCheck)
        assertTrue(startup.contains("!isCurrent(generation)"))
        assertTrue(startup.contains("stateMachine().activityLeaseIsCurrent(activityLease())"))
        assertTrue(wait < startup.indexOf("closeResourcesOnWorker()"))
        val capture = source.substringAfter("private fun capturePrivacyStartupResetPreparation(): PreparedPrivacyStartupReset")
            .substringBefore("private fun drainPrivacyStartupSpeechOutputOnWorker()")
        assertTrue(capture.contains("PreparedPrivacyStartupReset.capture("))
        for (binding in listOf("prepareSpeech = ::prepareSpeechOutputAccountPurgeOnWorker",
            "prepareRoute = ::prepareRouteSnapshotAccountPurge", "awaitSpeech = ::awaitPreparedSpeechOutputAccountPurge",
            "awaitRoute = ::awaitRouteSnapshotAccountPurgeOnWorker", "speechIsCurrent = ::speechOutputResetPreparationIsCurrent",
            "routeIsCurrent = ::routeSnapshotResetPreparationIsCurrent")) {
            assertTrue(capture.contains(binding))
        }
        val factory = controller.substringAfter("fun <S, R> capture(")
            .substringBefore("internal interface PrivacyStartupResetPreparation")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(factory, "val speech = prepareSpeech()",
            "val route = runCatching(prepareRoute).getOrNull()", "awaitSpeech(speech)", "awaitRoute(route)",
            "return speechPurgedAndDrained && routePurged"))
        assertTrue(factory.contains("speechIsCurrent(speech) && route?.let(routeIsCurrent) == true"))
        assertFalse(capture.contains("synchronized("))
        val resetController = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/reset/AccountDeletionInstallationResetController.kt").readText()
        val reset = ReportStaticSourceInspector.functionBlock(resetController, "fun reconcileResetAtStartup")
        val resetBinding = MainActivityRefactorSource.composition(source, "accountDeletionInstallationResetController")
        assertTrue(source.contains("private val accountDeletionInstallationResetController = privacyAuthority.createAccountDeletionInstallationResetController()"))
        assertTrue(resetBinding.contains("storage = accountDeletionResetStorage"))
        val inspectCallback = source.substringAfter("inspect = inspection@{ speechOutputPurgeCompleted ->")
            .substringBefore("restoreEmailEnrollmentPartialAtStartup()")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(inspectCallback,
            "accountDeletionInstallationResetController.inspectStartupReset(speechOutputPurgeCompleted)",
            "InstallationResetStartupResult.FINISH", "return@inspection PrivacyStartupInspectionResult.FINISH"))
        val resetInspection = ReportStaticSourceInspector.functionBlock(resetController, "fun inspectStartupReset")
        assertTrue(resetInspection.contains("when (reconcileResetAtStartup(acknowledged))"))
        assertTrue(reset.contains("speechOutputPurgeCompleted == false"))
        assertTrue(reset.contains("speechOutputPurgeCompleted != true"))
        assertFalse(reset.contains(".get()"))
        assertFalse(reset.contains("awaitSpeechOutput"))
    }

    @Test fun sourceVerifiedAccountAndRawRecoveryUseTheCapturedParticipantAndNewProviderOnly() {
        val source = mainSource()
        val storage = speechStorageSource()
        assertTrue(source.contains("private fun resetSpeechOutputFieldStorageOnWorker(): Boolean =\n        speechOutputStorageCoordinator.resetFieldStorageOnWorker()"))
        assertTrue(source.contains("speechOutputStorageCoordinator.recoverOnWorker(previousCollection, capture, actuator)"))
        assertTrue(source.contains("speechOutputStorageCoordinator.resetPreparationIsCurrent(preparation)"))
        assertTrue(source.contains("override fun deviceCheck() = postLoginDeviceCheckSnapshot"))
        val reset = ReportStaticSourceInspector.functionBlock(storage, "fun resetFieldStorageOnWorker")
        assertTrue(reset.contains("resetPreparationIsCurrent(preparation)"))
        assertTrue(reset.contains("fieldParticipant is PersistentFieldSessionLog"))
        assertTrue(reset.contains("fieldParticipant.purgeAll() && fieldParticipant.resetForNewEnrollment()"))
        assertTrue(reset.contains("preparation.collection.provider.purgeAll() && preparation.collection.provider.resetForNewEnrollment()"))
        assertFalse(reset.contains("NoopFieldSessionLog"))
        val raw = ReportStaticSourceInspector.functionBlock(storage, "fun recoverOnWorker")
        assertTrue(raw.indexOf("awaitCleanup(") >= 0)
        assertTrue(raw.indexOf("awaitCleanup(") <
            raw.indexOf("previousCollection.provider.resetAfterConfirmedRawConsent"))
        assertTrue(raw.indexOf("previousCollection.provider.resetAfterConfirmedRawConsent") <
            raw.indexOf("fieldParticipant?.resetRawSourceAfterConfirmedConsent()"))
        assertTrue(raw.contains("replacement = createCollection(previousCollection.filesDirectory)"))
        assertTrue(raw.contains("speechOutputAdmissionToken.get() !== capture.admissionToken"))
        assertTrue(raw.contains("!capture.privacyIsCurrent()"))
        assertTrue(raw.contains("queries.deviceCheck() != capture.permissions.deviceCheck"))
        assertTrue(raw.contains("refreshBinding(actuator)"))
    }

    @Test fun sourceActualListenerCapturesItsOwnBindingAndCreationRefreshUsesTheSameEntry() {
        val source = mainSource()
        val refresh = ReportStaticSourceInspector.functionBlock(speechStorageSource(), "fun refreshBinding")
        assertTrue(source.contains("private fun refreshSpeechOutputStorageBinding(actuator: AndroidFeedbackActuator) =\n        speechOutputStorageCoordinator.refreshBinding(actuator)"))
        assertTrue(source.contains("speechOutputStorageCoordinator.captureAllowed(walk, gateway, device, permissions, observed)"))
        assertTrue(source.contains("override fun observedPermissions() = currentObservedPermissionSnapshot()"))
        assertTrue(refresh.contains("queries.observedPermissions()"))
        assertTrue(refresh.contains("captureAllowed(walk, gateway, device, permissions, observed)"))
        assertTrue(refresh.contains("actuator.speechOutputListener = { event -> collection.store.enqueue(binding, event) }"))
        val controller = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/output/SpeechOutputController.kt").readText()
        val creation = ReportStaticSourceInspector.functionBlock(controller, "fun ensureFeedbackActuator")
        assertTrue(source.contains("speechOutputController.ensureFeedbackActuator()"))
        assertTrue(source.contains("override fun refresh(actuator: AndroidFeedbackActuator) = refreshSpeechOutputStorageBinding(actuator)"))
        assertTrue(source.contains("override fun invalidate() = invalidateSpeechOutputStorageBinding()"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(creation, "storage.refresh(current)", "return current"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(creation, "createdActuator = it", "feedbackActuator = it", "storage.refresh(it)"))
        val setter = controller.substringAfter("private var feedbackActuator:").substringBefore("private val commandSpeechResponseCallbacks")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(setter, "if (field !== value) storage.invalidate()", "field = value"))
    }

    @Test fun sourceCurrentnessContainsOnlyCapturedOwnersAndMemoryChecks() {
        val source = mainSource()
        val authority = File("src/main/java/kr/co/hanium/dreamup/walksafe/feedback/SpeechOutputStorageAuthority.kt")
            .readText().substringAfter("internal class SpeechOutputStorageAuthority(")
        assertTrue(authority.contains("consentSession.currentConfirmationOrNull("))
        assertTrue(authority.contains("permissionPolicy.snapshot()"))
        assertTrue(authority.contains("lifecycle.snapshot()"))
        assertTrue(authority.contains("GatewaySessionProcessCoordinator.isCurrentGeneralSession("))
        assertFalse(authority.contains("checkSelfPermission"))
        assertFalse(authority.contains("readySpeechEngineGenerationOrNull"))
        assertFalse(authority.contains("postLoginDeviceCheckSnapshot"))
        assertFalse(authority.contains("currentRawCollectionRuntimeContextOrNull"))
        val beforePublish = source.substringAfter("val transition = walkSessionLifecycle.handle(event)")
            .substringBefore("if (\n            event == WalkSessionEvent.SafetyStopRequested")
        assertTrue(beforePublish.contains("fenceSpeechOutputStorageBeforeWalkPublication(previous, next)"))
    }

    private class AuthorityFixture(initialReceipt: IntegratedConsentConfirmation = confirmation()) {
        var now = 1_000L
        val lifecycle = activeLifecycle(wall = { now }, elapsed = { now })
        val session = GatewayFieldSession.verified(
            gatewayBaseUrl = "https://gateway.example.test", actorId = "speech-store-fixture",
            cookiePair = "walksafe_field_session=v2.test.session.cookie", expiresAtEpochMs = Long.MAX_VALUE,
        )
        val generation: Long
        val permissions = PermissionSessionPolicy(PermissionSessionSnapshot(
            actorId = session.actorId, authentication = AuthenticationState.ACTIVE,
            rawCollectionConsentGranted = true,
        ))
        val consent = IntegratedConsentSession()
        val deletion = AccountDeletionStateMachine()
        val receipt = initialReceipt
        val guard: IntegratedConsentAuthorityGuard
        val device: PostLoginDeviceCheckSnapshot
        val binding: SpeechOutputStorageBinding
        val authority: SpeechOutputStorageAuthority
        init {
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            check(GatewaySessionProcessCoordinator.publishVerified(operation, session, FirstRunOnboardingPolicy.initial()))
            generation = GatewaySessionProcessCoordinator.snapshot().generation
            guard = object : IntegratedConsentAuthorityGuard {
                override fun <T : Any> withCurrent(action: () -> T): T? =
                    GatewaySessionProcessCoordinator.withCurrentGeneralSession(generation, session, action)
            }
            check(consent.applyConfirmation(receipt, guard) == IntegratedConsentApplyResult.APPLIED)
            device = PostLoginDeviceCheckSnapshot(PostLoginDeviceCheckState.FULL, session.actorId, generation, 1L)
            val permissionAuthority = SpeechOutputPermissionAuthority(
                permissions.snapshot(), ObservedPermissionSnapshot(true, true, true, false), device,
            )
            binding = SpeechOutputStorageBinding(receipt, permissionAuthority, lifecycle.snapshot().epoch,
                session, generation, Any(), SpeechOutputFeedbackLifetime(7), Any(), Any())
            authority = SpeechOutputStorageAuthority(binding, Any(), permissionAuthority, 7,
                consent, permissions, lifecycle, deletion)
        }
        fun admitted(
            main: MainActivity,
            observed: ObservedPermissionSnapshot = ObservedPermissionSnapshot(true, true, true, false),
            device: PostLoginDeviceCheckSnapshot = this.device,
        ): Boolean = main.speechOutputStorageCaptureAllowed(lifecycle.snapshot(),
            GatewaySessionProcessCoordinator.snapshot(), device, permissions.snapshot(), observed)
    }

    private class PurgeCounterStorage(private val accountResult: Boolean = true) : SpeechOutputEncryptedStorage {
        var accountPurges = 0
        var cryptoGetterCalls = 0
        override val aead: LocalAead get() { cryptoGetterCalls++; error("purge routing must not access crypto") }
        override val limits: AeadLimits get() = error("purge routing must not read limits")
        override fun associatedData(): ByteArray = error("purge routing must not prepare an event")
        override fun appendEncrypted(envelope: String): Boolean = error("purge routing must not append")
        override fun purgeAll(): Boolean { accountPurges++; return accountResult }
    }

    companion object {
        private fun activeLifecycle(
            wall: () -> Long = { 1_000L }, elapsed: () -> Long = { 1_000L },
        ): WalkSessionLifecycle = WalkSessionLifecycle(
            sessionIdFactory = { "speech-store-walk" }, clock = wall, elapsedRealtimeClock = elapsed,
        ).also { lifecycle ->
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(ready(lifecycle)))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
        }
        private fun ready(lifecycle: WalkSessionLifecycle, at: Long = 1_000L): WalkSessionReadinessSnapshot {
            val collector = WalkSessionReadinessCollector(lifecycle.snapshot().epoch,
                WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
            collector.plan.requiredRequirements.forEach { requirement ->
                collector.record(WalkSessionReadinessObservation(lifecycle.snapshot().epoch, requirement,
                    WalkSessionReadinessStatus.READY))
            }
            return collector.build(at)
        }
        private fun confirmation() = IntegratedConsentConfirmation(
            schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
            policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
            installationId = "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
            requestId = "speech_storage_consent_fixture_0001", itemVersions = IntegratedConsentItemVersions(),
            clientRevision = 1L, revision = 1L, selections = IntegratedConsentSelections(rawSourceCollection = true),
            confirmedAt = "2026-07-25T12:00:00.000Z", gatewayAuditRecordSha256 = "9".repeat(64),
            backendConsentReceiptSha256 = "a".repeat(64), controlSecret = "b".repeat(64),
        )
        private fun mainSource(): String {
            val relative = "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"
            return listOf(File(relative), File("app/$relative"), File("apps/android/app/$relative"))
                .first { it.isFile }.readText()
        }
        private fun speechStorageSource(): String = ReportStaticSourceInspector.read(
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/SpeechOutputStorageCoordinator.kt",
        )
    }
}
