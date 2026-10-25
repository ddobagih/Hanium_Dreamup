package kr.co.hanium.dreamup.walksafe.session.consent

import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.security.SensitivePreferenceSpec
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_INTEGRATED_CONSENT_ACTOR_SHA256
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_INTEGRATED_CONSENT_BACKEND_RECEIPT_SHA256
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_INTEGRATED_CONSENT_CLIENT_REVISION
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_INTEGRATED_CONSENT_CONTROL_SECRET
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_INTEGRATED_CONSENT_GATEWAY_AUDIT_SHA256
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_INTEGRATED_CONSENT_POLICY_VERSION
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_INTEGRATED_CONSENT_REVISION
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_PENDING_INTEGRATED_CONSENT_MUTATION
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_RAW_SOURCE_FIELD_LOG_BLOCKED
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence.Companion.PREF_REJECTED_CONSENT_CAS_JOURNAL
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class IntegratedConsentControllerTest {
    @Before @After fun clearProcess() {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }

    @Test fun failedAutomaticCleanupIsDurableAndRegrantWaitsForWorkerCompletion() {
        val f = Fixture(productionSensitiveSpec()).confirmed()
        f.automaticCleanupSucceeded = false
        f.controller.updateIntegratedConsentDraft(IntegratedConsentItem.AUTOMATIC_REPORTING, false)
        assertNotNull(f.store.values["pending_automatic_report_cleanup_v1"])
        assertFalse(f.controller.automaticReportCleanupReadyForCurrentActor())
        assertFalse(f.events.contains("auto-revoked")) // I/O is not run by the initiating UI action.
        f.runWorker(); f.runAllMain()
        assertNotNull(f.store.values["pending_automatic_report_cleanup_v1"])
        assertFalse(f.policy.snapshot().mayCreateAutomaticReport)
        assertTrue(f.cleanupRetries.single().second > 0L)
        f.controller.updateIntegratedConsentDraft(IntegratedConsentItem.AUTOMATIC_REPORTING, true)
        f.controller.persistIntegratedConsentDraft(announce = false)
        f.runWorker(); f.runAllMain()
        assertFalse(f.controller.automaticReportCleanupReadyForCurrentActor())
        assertFalse(f.policy.snapshot().mayCreateAutomaticReport)
        f.automaticCleanupSucceeded = true
        while (f.work.isNotEmpty()) f.runWorker()
        f.cleanupRetries.last().first.invoke()
        while (f.work.isNotEmpty()) f.runWorker()
        f.runAllMain()
        assertNull(f.store.values["pending_automatic_report_cleanup_v1"])
        assertTrue(f.controller.automaticReportCleanupReadyForCurrentActor())
    }

    @Test fun deniedConfirmationQueuesAutomaticCleanupThroughProductionSensitiveDomain() {
        val f = Fixture(productionSensitiveSpec()).confirmed()
        f.automaticCleanupSucceeded = false
        val denied = f.initial.copy(clientRevision = 2L, revision = 2L,
            requestId = "controller_consent_deny_0002", backendConsentReceiptSha256 = "b".repeat(64),
            selections = f.initial.selections.copy(automaticReporting = false))
        assertTrue(f.apply(denied))
        assertNotNull(f.store.values["pending_automatic_report_cleanup_v1"])
        assertEquals(PurposeConsentSyncState.CONFIRMED_GRANTED, f.authority.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertEquals(PurposeConsentSyncState.CONFIRMED_DENIED, f.authority.status(IntegratedConsentItem.AUTOMATIC_REPORTING))
        assertFalse(f.events.contains("status:integratedConsent=blocked:automatic_cleanup_pending_storage"))
        assertFalse(f.controller.automaticReportCleanupReadyForCurrentActor())
        assertEquals(1, f.work.size)
    }

    @Test fun productionSensitiveDomainStillRejectsUnregisteredKeys() {
        val store = MemoryStore(mutableListOf(), "sensitive", productionSensitiveSpec())
        val outsideKey = "unregistered_consent_test_key"
        assertThrows(IllegalArgumentException::class.java) { store.getString(outsideKey, null) }
        assertThrows(IllegalArgumentException::class.java) { store.edit().putString(outsideKey, "value") }
        assertTrue(store.values.isEmpty())
    }

    @Test fun aRestartRestoresAutomaticCleanupAndAnUnknownActorCannotConsumeItsMarker() {
        val f = Fixture().confirmed()
        val marker = org.json.JSONObject().put("pending", org.json.JSONArray().put(org.json.JSONObject()
            .put("actor_sha256", "f".repeat(64)).put("receipt", "a".repeat(64)))).toString()
        f.store.values["pending_automatic_report_cleanup_v1"] = marker
        f.controller.retryPendingAutomaticReportCleanup()
        assertTrue(f.work.isEmpty())
        assertEquals(marker, f.store.values["pending_automatic_report_cleanup_v1"])
        f.store.values["pending_automatic_report_cleanup_v1"] = org.json.JSONObject().put("pending", org.json.JSONArray().put(org.json.JSONObject()
            .put("actor_sha256", actorHash(f.session)).put("receipt", "a".repeat(64)))).toString()
        f.controller.retryPendingAutomaticReportCleanup()
        assertEquals(1, f.work.size)
        f.runWorker(); f.runAllMain()
        assertNull(f.store.values["pending_automatic_report_cleanup_v1"])
    }

    @Test fun constructorDoesNotQueryStorePolicyActorUiOrGateway() {
        val f = Fixture()
        assertTrue(f.events.isEmpty())
        assertFalse(f.controller.screenState().requestInFlight)
        assertTrue(f.work.isEmpty())
        assertEquals(0, f.store.reads)
    }

    @Test fun cancelledOldResponseCannotClaimOrClearTheNewActiveCall() {
        val f = Fixture().confirmed()
        val old = FakeCall<IntegratedConsentConfirmation?> { f.initial }
        val newer = FakeCall<IntegratedConsentConfirmation?> { f.initial }
        old.action = {
            f.nextFetch = newer
            f.controller.refreshIntegratedConsentFromServer()
            f.initial
        }
        f.nextFetch = old
        f.controller.refreshIntegratedConsentFromServer()
        f.runWorker()
        assertTrue(f.controller.screenState().requestInFlight)
        assertFalse(newer.cancelled)
        assertFalse(f.events.any { it == "commit-sensitive" })
        f.runWorker()
        assertFalse(f.controller.screenState().requestInFlight)
        assertEquals(1, newer.executions)
    }

    @Test fun queuedWorkerForAnotherSessionInstanceCannotExecuteOrCommit() {
        val f = Fixture().confirmed()
        val call = FakeCall<IntegratedConsentConfirmation?> { f.initial }
        f.nextFetch = call
        f.controller.refreshIntegratedConsentFromServer()
        f.publish(f.newSession())
        f.runWorker()
        assertEquals(0, call.executions)
        assertTrue(call.cancelled)
        assertFalse(f.events.contains("commit-sensitive"))
    }

    @Test fun sessionChangesInsideNetworkResponseBeforeClaimCannotWriteAuthority() {
        val f = Fixture().confirmed()
        f.nextFetch = FakeCall { f.publish(f.newSession()); f.initial }
        f.controller.refreshIntegratedConsentFromServer()
        f.runWorker()
        assertFalse(f.events.contains("commit-sensitive"))
        assertNull(f.authority.currentConfirmationOrNull())
    }

    @Test fun expiredActivityLeaseAfterApplySuppressesCompletionAndSpeech() {
        val f = Fixture().confirmed()
        f.onboarding = true
        f.controller.updateIntegratedConsentDraft(IntegratedConsentItem.TRAINING_REUSE, true)
        f.events.clear()
        f.controller.persistIntegratedConsentDraft(announce = true)
        f.runWorker()
        f.leaseCurrent = false
        f.runAllMain()
        assertFalse(f.events.contains("complete-first-run"))
        assertFalse(f.events.any { it.startsWith("speech:") })
        assertNull(f.controller.screenState().pendingMutation)
    }

    @Test fun rejectedExecutorKeepsTheDurableOutboxAndWithdrawalAndCancelsItsCall() {
        val f = Fixture().confirmed()
        f.controller.updateIntegratedConsentDraft(IntegratedConsentItem.AUTOMATIC_REPORTING, false)
        f.rejectExecutor = true
        f.controller.persistIntegratedConsentDraft(announce = false)
        assertNotNull(f.controller.screenState().pendingMutation)
        assertNotNull(f.store.values[PREF_PENDING_INTEGRATED_CONSENT_MUTATION])
        assertFalse(f.authority.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
        assertTrue(requireNotNull(f.lastSave).cancelled)
        assertFalse(f.controller.screenState().requestInFlight)
    }

    @Test fun rawMarkerCommitFailureCannotWriteWithdrawalMarkerOrReopenGrants() {
        val f = Fixture().confirmed()
        f.plain.failNextCommit = true
        f.controller.updateIntegratedConsentDraft(IntegratedConsentItem.RAW_SOURCE_COLLECTION, false)
        assertNull(f.store.values[PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED])
        assertFalse(f.authority.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertEquals(PurposeConsentSyncState.FAIL_CLOSED, f.authority.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        f.controller.persistIntegratedConsentDraft(announce = false)
        assertNull(f.lastSave)
    }

    @Test fun sensitiveWithdrawalCommitFailureKeepsThePlainFenceAndFailsClosed() {
        val f = Fixture().confirmed()
        f.store.failNextCommit = true
        f.controller.updateIntegratedConsentDraft(IntegratedConsentItem.RAW_SOURCE_COLLECTION, false)
        assertEquals(true, f.plain.values[PREF_RAW_SOURCE_FIELD_LOG_BLOCKED])
        assertEquals(PurposeConsentSyncState.FAIL_CLOSED, f.authority.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertTrue(f.events.indexOf("commit-plain") < f.events.indexOf("commit-sensitive"))
    }

    @Test fun pendingMismatchCannotRemoveOutboxOrRawFence() {
        val f = Fixture().confirmed()
        f.controller.updateIntegratedConsentDraft(IntegratedConsentItem.RAW_SOURCE_COLLECTION, false)
        f.controller.persistIntegratedConsentDraft(announce = false)
        val pending = requireNotNull(f.controller.screenState().pendingMutation)
        val unexpected = f.initial.copy(requestId = "different_request_0000002", clientRevision = pending.clientRevision,
            revision = 2L, backendConsentReceiptSha256 = "c".repeat(64), selections = pending.desiredSelections)
        assertTrue(f.apply(unexpected))
        assertNotNull(f.controller.screenState().pendingMutation)
        assertNotNull(f.store.values[PREF_PENDING_INTEGRATED_CONSENT_MUTATION])
        assertEquals(true, f.plain.values[PREF_RAW_SOURCE_FIELD_LOG_BLOCKED])
        assertFalse(f.authority.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
    }

    @Test fun exactPendingCommitIsTheOnlyOutboxJournalAndRawFenceRemoval() {
        val f = Fixture().confirmed(rawGranted = false)
        f.plain.values[PREF_RAW_SOURCE_FIELD_LOG_BLOCKED] = true
        f.controller.updateIntegratedConsentDraft(IntegratedConsentItem.RAW_SOURCE_COLLECTION, true)
        f.controller.persistIntegratedConsentDraft(announce = false)
        f.store.values[PREF_REJECTED_CONSENT_CAS_JOURNAL] = "existing-journal"
        f.events.clear()
        f.runWorker()
        assertNull(f.controller.screenState().pendingMutation)
        assertNull(f.store.values[PREF_PENDING_INTEGRATED_CONSENT_MUTATION])
        assertNull(f.store.values[PREF_REJECTED_CONSENT_CAS_JOURNAL])
        assertNull(f.plain.values[PREF_RAW_SOURCE_FIELD_LOG_BLOCKED])
        assertTrue(f.authority.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        inOrder(f.events, "prepare:APPLIED", "commit-sensitive", "commit-plain", "hold-old:true", "report-confirm:2", "raw-change:2")
    }

    @Test fun sameActorReceiptDuplicatePreservesReportPermitButChangedReceiptCancelsIt() {
        val f = Fixture().confirmed()
        f.events.clear()
        assertTrue(f.apply(f.initial))
        assertFalse(f.events.contains("cancel-report-all"))
        assertTrue(f.reportGranted)
        f.events.clear()
        assertTrue(f.apply(f.initial.copy(clientRevision = 2L, revision = 2L, requestId = "new_request_00000002", backendConsentReceiptSha256 = "b".repeat(64))))
        assertTrue(f.events.contains("cancel-report-all"))
    }

    @Test fun confirmationCommitFailureDoesNotHoldRetireOrPublishTheNewRawReceipt() {
        val f = Fixture().confirmed()
        f.store.failNextCommit = true
        val changed = f.initial.copy(clientRevision = 2L, revision = 2L, requestId = "new_request_00000002", backendConsentReceiptSha256 = "b".repeat(64))
        assertFalse(f.apply(changed))
        assertFalse(f.events.any { it.startsWith("hold-old") || it.startsWith("raw-change") })
        assertEquals(f.initial.backendConsentReceiptSha256, f.store.values[PREF_INTEGRATED_CONSENT_BACKEND_RECEIPT_SHA256])
        assertNull(f.authority.cachedConfirmationOrNull())
        assertFalse(f.authority.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
    }

    @Test fun corruptExistingControlSecretIsNotReplacedAndNeverStartsHttp() {
        val f = Fixture()
        f.publish(f.session)
        f.store.values[PREF_INTEGRATED_CONSENT_CONTROL_SECRET] = "corrupt"
        f.controller.refreshIntegratedConsentFromServer()
        assertTrue(f.store.blocked)
        assertFalse(f.events.contains("random"))
        assertTrue(f.work.isEmpty())
        assertEquals("corrupt", f.store.values[PREF_INTEGRATED_CONSENT_CONTROL_SECRET])
    }

    @Test fun startupCorruptOutboxDoesNotPromoteCachedConfirmationOrGrant() {
        val f = Fixture().seedStoredConfirmation()
        f.store.values[PREF_PENDING_INTEGRATED_CONSENT_MUTATION] = "corrupt"
        f.controller.restoreConsentState()
        assertEquals(PurposeConsentSyncState.FAIL_CLOSED, f.authority.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertFalse(f.policy.snapshot().rawCollectionConsentGranted)
        assertEquals("corrupt", f.store.values[PREF_PENDING_INTEGRATED_CONSENT_MUTATION])
    }

    @Test fun firstGatewaySnapshotFencesAndSameSnapshotDoesNotFenceTwice() {
        val f = Fixture().confirmed()
        val snapshot = GatewaySessionProcessCoordinator.snapshot()
        assertTrue(f.controller.fenceGatewayAuthorityChange(snapshot))
        assertNull(f.authority.currentConfirmationOrNull())
        val invalidations = f.events.count { it == "invalidate-speech" }
        assertFalse(f.controller.fenceGatewayAuthorityChange(snapshot))
        assertEquals(invalidations, f.events.count { it == "invalidate-speech" })
    }

    @Test fun replacementPermissionPolicyIsReadAtOriginalApplyTime() {
        val f = Fixture().confirmed()
        val old = f.policy
        f.policy = PermissionSessionPolicy().apply { authenticated(ACTOR) }
        f.controller.reapplyActorBoundIntegratedConsent()
        assertTrue(f.policy.snapshot().rawCollectionConsentGranted)
        assertNotSame(old, f.policy)
    }

    @Test fun casRecoveryCommitsSuccessorFloorOutboxAndJournalWithoutExpandingDenials() {
        val f = Fixture().confirmed()
        f.controller.updateIntegratedConsentDraft(IntegratedConsentItem.AUTOMATIC_REPORTING, false)
        f.runWorker() // Complete the distinct automatic-queue cleanup before the server CAS request.
        f.saveFailure = IntegratedConsentHttpException(409, """{"detail":{"code":"privacy_consent_previous_receipt_conflict"}}""")
        f.controller.persistIntegratedConsentDraft(announce = false)
        val rejected = requireNotNull(f.controller.screenState().pendingMutation)
        f.runSaveWorker()
        f.saveFailure = null
        f.bootstrap = IntegratedConsentBootstrap(IntegratedConsentBootstrapStatus.READY, IntegratedConsentBootstrapSource.CURRENT_CONSENT,
            DEVICE, 2L, f.initial.selections.copy(rawSourceCollection = false), "c".repeat(64), "c".repeat(64))
        f.runWorker()
        val successor = requireNotNull(f.controller.screenState().pendingMutation)
        assertEquals(3L, successor.clientRevision)
        assertNotEquals(rejected.requestId, successor.requestId)
        assertFalse(successor.desiredSelections.rawSourceCollection)
        assertFalse(successor.desiredSelections.automaticReporting)
        assertEquals(successor.clientRevision, f.store.values[PREF_INTEGRATED_CONSENT_CLIENT_REVISION])
        assertNotNull(f.store.values[PREF_REJECTED_CONSENT_CAS_JOURNAL])
        assertEquals(successor, f.persistence.pendingIntegratedConsentMutationOrNull(requireNotNull(f.store.values[PREF_PENDING_INTEGRATED_CONSENT_MUTATION] as String?)))
    }

    @Test fun corruptCasRecoveryJournalKeepsRejectedOutboxAndFailsClosed() {
        val f = Fixture().confirmed()
        f.controller.updateIntegratedConsentDraft(IntegratedConsentItem.AUTOMATIC_REPORTING, false)
        f.runWorker() // Complete the distinct automatic-queue cleanup before the server CAS request.
        f.saveFailure = IntegratedConsentHttpException(409, """{"detail":{"code":"privacy_consent_previous_receipt_conflict"}}""")
        f.controller.persistIntegratedConsentDraft(announce = false)
        val rejected = f.controller.screenState().pendingMutation
        f.runSaveWorker()
        f.store.values[PREF_REJECTED_CONSENT_CAS_JOURNAL] = "corrupt"
        f.bootstrap = IntegratedConsentBootstrap(IntegratedConsentBootstrapStatus.READY, IntegratedConsentBootstrapSource.CURRENT_CONSENT,
            DEVICE, 2L, f.initial.selections, "c".repeat(64), "c".repeat(64))
        f.runWorker()
        assertEquals(rejected, f.controller.screenState().pendingMutation)
        assertEquals("corrupt", f.store.values[PREF_REJECTED_CONSENT_CAS_JOURNAL])
        assertEquals(PurposeConsentSyncState.FAIL_CLOSED, f.authority.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
    }

    @Test fun gatewayChangeAfterDurableApplyClosesReceiptAndSuppressesOldUiCompletion() {
        val f = Fixture().confirmed()
        f.onboarding = true
        f.controller.updateIntegratedConsentDraft(IntegratedConsentItem.TRAINING_REUSE, true)
        f.controller.persistIntegratedConsentDraft(announce = true)
        f.afterReportApply = { f.publish(f.newSession()) }
        f.runWorker()
        f.runAllMain()
        assertFalse(f.events.contains("complete-first-run"))
        assertFalse(f.events.contains("schedule-transfer"))
        assertNull(f.store.values[PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION])
        assertFalse(f.authority.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertFalse(f.controller.screenState().bootstrapReady)
    }

    @Test fun unknownStoredPolicyFailsClosedAndPreservesItsUnsettledEvidence() {
        val f = Fixture().confirmed()
        f.store.values[PREF_INTEGRATED_CONSENT_POLICY_VERSION] = "FP-013-unknown"
        f.store.values[PREF_PENDING_INTEGRATED_CONSENT_MUTATION] = "pending-evidence"
        assertTrue(f.controller.migrateKnownIntegratedConsentPolicyAtStartup())
        assertEquals("pending-evidence", f.store.values[PREF_PENDING_INTEGRATED_CONSENT_MUTATION])
        assertEquals(PurposeConsentSyncState.FAIL_CLOSED, f.authority.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
    }

    @Test fun knownPolicyMigrationCannotDeleteAnUnsettledWithdrawalJournal() {
        val f = Fixture().confirmed()
        f.store.values[PREF_INTEGRATED_CONSENT_POLICY_VERSION] = PREVIOUS_INTEGRATED_CONSENT_POLICY_VERSION
        f.store.values[PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED] = "[\"automatic_reporting\"]"
        assertFalse(f.controller.migrateKnownIntegratedConsentPolicyAtStartup())
        assertEquals("[\"automatic_reporting\"]", f.store.values[PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED])
        assertFalse(f.authority.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
    }

    @Test fun exhaustedClientRevisionCannotOverflowOrPublishANewMutation() {
        val f = Fixture().confirmed()
        f.store.values[PREF_INTEGRATED_CONSENT_CLIENT_REVISION] = Long.MAX_VALUE
        f.controller.restoreDraftAndClientFloorFromStorage()
        f.controller.persistIntegratedConsentDraft(announce = false)
        assertNull(f.lastSave)
        assertNull(f.controller.screenState().pendingMutation)
        assertTrue(f.events.contains("status:integratedConsent=blocked:client_revision_exhausted"))
    }

    private fun productionSensitiveSpec(): SensitivePreferenceSpec =
        MainActivity::class.java.getDeclaredField("SENSITIVE_PREF_SPEC").run {
            isAccessible = true
            get(null) as SensitivePreferenceSpec
        }

    private class Fixture(sensitiveSpec: SensitivePreferenceSpec? = null) : IntegratedConsentController.Remote, IntegratedConsentController.Gateway,
        IntegratedConsentController.Actor, IntegratedConsentController.Activity, IntegratedConsentController.SpeechStorage,
        IntegratedConsentController.Reports, IntegratedConsentController.RawStorage, IntegratedConsentController.Network,
        IntegratedConsentController.Ui {
        val events = mutableListOf<String>()
        val work = ArrayDeque<Runnable>()
        val main = ArrayDeque<() -> Unit>()
        val store = MemoryStore(events, "sensitive", sensitiveSpec)
        val plain = MemoryStore(events, "plain")
        val persistence = IntegratedConsentPersistence(store, plain)
        val authority = IntegratedConsentSession()
        var policy = PermissionSessionPolicy().apply { authenticated(ACTOR) }
        var session = newSession()
        var initial = confirmation()
        var nextFetch: FakeCall<IntegratedConsentConfirmation?>? = null
        var lastSave: FakeCall<IntegratedConsentConfirmation?>? = null
        var saveFailure: Exception? = null
        var bootstrap = IntegratedConsentBootstrap(IntegratedConsentBootstrapStatus.RECONSENT_REQUIRED, IntegratedConsentBootstrapSource.NONE, DEVICE, 0L, null, null, null)
        var onboarding = false
        var leaseCurrent = true
        var rejectExecutor = false
        var reportGranted = false
        var automaticCleanupSucceeded = true
        val cleanupRetries = mutableListOf<Pair<() -> Unit, Long>>()
        var afterReportApply: (() -> Unit)? = null
        val controller = IntegratedConsentController(authority, this, this, { events.add("policy-read"); policy }, this, this, this, this, this, this, this,
            Executor { if (rejectExecutor) throw RejectedExecutionException(); work.add(it) }, { main.add(it) },
            wallClockMs = { 1_000L }, randomBytes = { events.add("random"); it.fill(1) }).also { it.initializePersistence(persistence) }
        fun newSession() = GatewayFieldSession.verified(ORIGIN, ACTOR, "walksafe_field_session=consent-controller-test-token-00001", Long.MAX_VALUE)
        fun publish(value: GatewayFieldSession) {
            session = value
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            val snapshot = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(FirstRunOnboardingPolicy.initialEmailAccount(epoch = 7L),
                FirstRunOpaqueActorBinding.fromProvider(ACTOR), FirstRunReceiptHash.fromSha256Hex("a".repeat(64))).current
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, value, snapshot))
        }
        fun seedStoredConfirmation(): Fixture {
            publish(session)
            store.values.putAll(mapOf(PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION to persistence.integratedConsentConfirmationJson(initial),
                PREF_INTEGRATED_CONSENT_POLICY_VERSION to initial.policyVersion, PREF_INTEGRATED_CONSENT_CLIENT_REVISION to initial.clientRevision,
                PREF_INTEGRATED_CONSENT_REVISION to initial.revision, PREF_INTEGRATED_CONSENT_BACKEND_RECEIPT_SHA256 to initial.backendConsentReceiptSha256,
                PREF_INTEGRATED_CONSENT_GATEWAY_AUDIT_SHA256 to initial.gatewayAuditRecordSha256,
                PREF_INTEGRATED_CONSENT_ACTOR_SHA256 to actorHash(session), PREF_INTEGRATED_CONSENT_CONTROL_SECRET to initial.controlSecret,
                "report_privacy_consent_granted" to initial.selections.rawSourceCollection,
                "automatic_report_consent_granted" to initial.selections.automaticReporting, "mobile_network_preference" to "allow_cellular",
                "training_reuse_consent_granted" to initial.selections.trainingReuse))
            return this
        }
        fun confirmed(rawGranted: Boolean = true): Fixture {
            initial = initial.copy(selections = initial.selections.copy(rawSourceCollection = rawGranted))
            seedStoredConfirmation()
            controller.restoreDraftAndClientFloorFromStorage()
            controller.restoreConsentState()
            assertTrue(apply(initial))
            events.clear(); main.clear(); work.clear()
            return this
        }
        // Exercise the real private durable-apply path for protocol branches which public reads fence first.
        fun apply(value: IntegratedConsentConfirmation): Boolean {
            val method = IntegratedConsentController::class.java.getDeclaredMethod("applyIntegratedConsentConfirmation",
                IntegratedConsentConfirmation::class.java, String::class.java, IntegratedConsentAuthorityGuard::class.java)
            method.isAccessible = true
            val generation = GatewaySessionProcessCoordinator.snapshot().generation
            val guard = object : IntegratedConsentAuthorityGuard {
                override fun <T : Any> withCurrent(action: () -> T): T? = GatewaySessionProcessCoordinator.withCurrentGeneralSession(generation, session, action)
            }
            return method.invoke(controller, value, actorHash(session), guard) as Boolean
        }
        fun runWorker() = work.removeFirst().run()
        fun runSaveWorker() {
            repeat(10) { if (lastSave?.executions == 0 && work.isNotEmpty()) runWorker() }
            assertEquals(1, requireNotNull(lastSave).executions)
        }
        fun runAllMain() { while (main.isNotEmpty()) main.removeFirst().invoke() }
        override fun fetchCurrentCall(gatewayBaseUrl: String, session: GatewayFieldSession, installationId: String, controlSecret: String) = (nextFetch ?: FakeCall<IntegratedConsentConfirmation?> { initial }).call
        override fun fetchBootstrapCall(gatewayBaseUrl: String, session: GatewayFieldSession, installationId: String) = FakeCall { bootstrap }.call
        override fun saveCall(gatewayBaseUrl: String, session: GatewayFieldSession, installationId: String, controlSecret: String, requestId: String, clientRevision: Long, selections: IntegratedConsentSelections, expectedPreviousBackendReceiptSha256: String?) = FakeCall<IntegratedConsentConfirmation?> {
            saveFailure?.let { throw it }
            initial.copy(requestId = requestId, clientRevision = clientRevision, revision = 2L,
                selections = selections, backendConsentReceiptSha256 = "b".repeat(64))
        }.also { lastSave = it }.call
        override fun configuredOrigin() = ORIGIN
        override fun installationId() = DEVICE
        override fun sessionOrNull(reason: String, speak: Boolean) = session
        override fun refreshSessionOrNull() = session
        override fun isCurrentSession(session: GatewayFieldSession) = GatewaySessionProcessCoordinator.snapshot().let {
            it.session === session && !it.storageBlocked && !it.deletionRecoveryOnly && session.isUsableFor(ACTOR)
        }
        override fun currentReporterActorId() = ACTOR
        override fun isIntegratedConsentOnboardingStage() = onboarding
        override fun destroyed() = false
        override fun processingBlocked() = false
        override fun currentLeaseIsCurrent() = leaseCurrent
        override fun invalidate() { events.add("invalidate-speech") }
        override fun blockRawWithdrawal() { events.add("block-speech-raw") }
        override fun prepareConfirmationApply(confirmation: IntegratedConsentConfirmation, evaluation: IntegratedConsentApplyResult, previousRawConfirmation: IntegratedConsentConfirmation?, currentRawConfirmation: () -> IntegratedConsentConfirmation?): Boolean {
            events.add("prepare:$evaluation")
            if (evaluation !in setOf(IntegratedConsentApplyResult.APPLIED, IntegratedConsentApplyResult.DUPLICATE)) return false
            if (!confirmation.selections.rawSourceCollection) blockRawWithdrawal()
            else if (evaluation != IntegratedConsentApplyResult.DUPLICATE || currentRawConfirmation() !== previousRawConfirmation) invalidate()
            return true
        }
        override fun withdrawAuthority() { reportGranted = false; events.add("withdraw-report") }
        override fun cancelDrain() { events.add("cancel-drain") }
        override fun automaticRevoked(receipt: String, reporterActorId: String, accountGeneration: Long?): Boolean { events.add("auto-revoked"); return automaticCleanupSucceeded }
        override fun cancelAutomaticCalls() { events.add("cancel-report-auto") }
        override fun scheduleLegacyPurge() { events.add("purge-legacy") }
        override fun cancelAllCallsForMobileWithdrawal() { events.add("cancel-report-mobile") }
        override fun applyConfirmationAuthority(unchanged: Boolean, guard: IntegratedConsentAuthorityGuard) {
            if (!unchanged || !reportGranted) events.add("cancel-report-all")
            reportGranted = true
            events.add("report-confirm:${authority.cachedConfirmationOrNull()?.revision}")
            afterReportApply?.invoke()
        }
        override fun invalidateExplicitReport() { events.add("invalidate-explicit") }
        override fun cancelOriginalUploads() { events.add("cancel-original") }
        override fun cancelActiveUploadIfReady() { events.add("cancel-raw") }
        override fun withdrawReceiptIfReady(previousReceipt: String?) { events.add("revoke-raw:$previousReceipt") }
        override fun disableMetadataIfReady() { events.add("disable-metadata") }
        override fun blockFieldLogIfReady() { events.add("block-field-if-ready") }
        override fun blockFieldLog() { events.add("block-field") }
        override fun holdReceiptUntilDecidedIfReady(receipt: String) { events.add("hold-old:${authority.cachedConfirmationOrNull()?.backendConsentReceiptSha256 == receipt}") }
        override fun resetFieldLogAfterConfirmedConsentIfActive() { events.add("reset-field") }
        override fun confirmedReceiptChangedIfReady(previousReceipt: String?, previousConfirmation: IntegratedConsentConfirmation?, confirmation: IntegratedConsentConfirmation, rawEffective: Boolean) { events.add("raw-change:${confirmation.revision}") }
        override fun refreshFeaturePolicy() { events.add("refresh-network") }
        override fun isCellularIfReady() = false
        override fun cancelGatewayCalls() { events.add("cancel-gateway") }
        override fun postRefresh() { main.add { refresh() } }
        override fun refresh() { events.add("refresh-ui") }
        override fun status(message: String) { events.add("status:$message") }
        override fun speakInteraction(message: String) { events.add("speech:$message") }
        override fun completeFirstRunIfReady(confirmation: IntegratedConsentConfirmation) { events.add("complete-first-run") }
        override fun schedulePostWalkTransfers() { events.add("schedule-transfer") }
        override fun scheduleAutomaticReportCleanupRetry(action: () -> Unit, delayMs: Long) { cleanupRetries.add(action to delayMs) }
    }

    private class FakeCall<T>(var action: () -> T) {
        var cancelled = false
        var executions = 0
        val call = CancellableNetworkCall(executeBlock = { executions++; action() }, cancelBlock = { cancelled = true })
    }
    private class MemoryStore(val events: MutableList<String>, val label: String,
        private val sensitiveSpec: SensitivePreferenceSpec? = null) : IntegratedConsentPersistence.SensitiveStore, IntegratedConsentPersistence.PlainStore {
        val values = mutableMapOf<String, Any?>()
        var blocked = false
        var failNextCommit = false
        var reads = 0
        override fun isBlocked(): Boolean { reads++; return blocked }
        override fun failClosed() { blocked = true; events.add("blocked-store") }
        private fun requireAllowed(key: String) {
            require(sensitiveSpec?.allows(key) != false) { "key is outside the sensitive preference domain" }
        }
        override fun contains(key: String): Boolean { requireAllowed(key); reads++; return values.containsKey(key) }
        override fun getString(key: String, defaultValue: String?): String? { requireAllowed(key); reads++; return values[key] as? String ?: defaultValue }
        override fun getLong(key: String, defaultValue: Long): Long { requireAllowed(key); reads++; return values[key] as? Long ?: defaultValue }
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean { requireAllowed(key); reads++; return values[key] as? Boolean ?: defaultValue }
        override fun edit(): IntegratedConsentPersistence.Editor = object : IntegratedConsentPersistence.Editor {
            val updates = mutableMapOf<String, Any?>()
            override fun putString(key: String, value: String?) = apply { requireAllowed(key); updates[key] = value }
            override fun putLong(key: String, value: Long) = apply { requireAllowed(key); updates[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { requireAllowed(key); updates[key] = value }
            override fun remove(key: String) = apply { requireAllowed(key); updates[key] = null }
            override fun commit(): Boolean {
                events.add("commit-$label")
                if (failNextCommit) { failNextCommit = false; return false }
                updates.forEach { (k,v) -> if (v == null) values.remove(k) else values[k] = v }
                return true
            }
        }
    }
    companion object {
        private const val ORIGIN = "https://gateway.example.test"
        private const val ACTOR = "123e4567-e89b-42d3-a456-426614174000"
        private const val DEVICE = "installation_controller_0001"
        private fun actorHash(session: GatewayFieldSession) = MessageDigest.getInstance("SHA-256")
            .digest(("walksafe.integrated-consent-actor.v1\u0000${session.actorId}\u0000" +
                (session.lease.backendAccountGeneration?.toString() ?: "legacy")).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun confirmation() = IntegratedConsentConfirmation(INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
            INTEGRATED_CONSENT_POLICY_VERSION, DEVICE, "controller_consent_request_0001", IntegratedConsentItemVersions(),
            1L, 1L, IntegratedConsentSelections(true, true, true, false), "2026-10-06T00:00:00.000Z", "d".repeat(64), "a".repeat(64), "e".repeat(64))
        private fun inOrder(events: List<String>, vararg expected: String) {
            var last = -1
            expected.forEach { event ->
                val index = events.withIndex().firstOrNull { it.index > last && it.value == event }?.index ?: -1
                assertTrue("Missing $event after $last in $events", index > last)
                last = index
            }
        }
    }
}
