package kr.co.hanium.dreamup.walksafe.session

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.report.AndroidReportQueueStore
import kr.co.hanium.dreamup.walksafe.report.QueuedReport
import kr.co.hanium.dreamup.walksafe.report.ReportQueueDrainCoordinator
import kr.co.hanium.dreamup.walksafe.report.ReportQueueReceipt
import kr.co.hanium.dreamup.walksafe.report.ReportQueueTransport
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegratedConsentMutationSubmissionTest {
    private val raw = IntegratedConsentItem.RAW_SOURCE_COLLECTION
    private val automatic = IntegratedConsentItem.AUTOMATIC_REPORTING
    private val actor = "a".repeat(64)

    @Test
    fun rejectedGrantNeverReplacesDurableWithdrawalOrReachesTransport() {
        val session = confirmedSession()
        session.withdrawImmediately(raw)
        val withdrawal = mutation(original(), original().selections.copy(rawSourceCollection = false), setOf(raw))
        val effects = Effects(session)
        assertEquals(IntegratedConsentSubmissionResult.APPLIED, effects.submit(withdrawal, retry = true))
        val grant = mutation(original(), original().selections, emptySet(), revision = 3L)

        assertEquals(IntegratedConsentSubmissionResult.REJECTED, effects.submit(grant))

        assertEquals(listOf(withdrawal), effects.writes)
        assertEquals(listOf(withdrawal), effects.transport)
        assertEquals(withdrawal, effects.disk)
        assertEquals(withdrawal, session.pendingMutationOrNull())
        assertEquals(PurposeConsentSyncState.WITHDRAWAL_RETRY, session.status(raw))
        assertTrue(session.isAllowed(automatic))
    }

    @Test
    fun unchangedExactCandidateStillSchedulesRetry() {
        val session = confirmedSession()
        val candidate = mutation(original(), original().selections.copy(rawSourceCollection = false), setOf(raw))
        val effects = Effects(session)
        assertEquals(IntegratedConsentSubmissionResult.APPLIED, effects.submit(candidate, retry = true))

        assertEquals(IntegratedConsentSubmissionResult.UNCHANGED, effects.submit(candidate.copy(), retry = true))

        assertEquals(listOf(candidate, candidate), effects.transport)
        assertEquals(candidate, session.pendingMutationOrNull())
        assertEquals(PurposeConsentSyncState.WITHDRAWAL_RETRY, session.status(raw))
    }

    @Test
    fun failedDurableWriteDoesNotPublishOrSendCandidate() {
        val session = confirmedSession()
        session.withdrawImmediately(raw)
        val candidate = mutation(original(), original().selections.copy(rawSourceCollection = false), setOf(raw))
        val effects = Effects(session).apply { storageSucceeds = false }

        assertEquals(IntegratedConsentSubmissionResult.STORAGE_FAILED, effects.submit(candidate))

        assertNull(effects.disk)
        assertNull(session.pendingMutationOrNull())
        assertTrue(effects.transport.isEmpty())
        assertEquals(PurposeConsentSyncState.WITHDRAWAL_PENDING, session.status(raw))
        assertTrue(session.isAllowed(automatic))
    }

    @Test
    fun failedTransportReservationRetainsDurableRequestAndRetriesSameCandidate() {
        val session = confirmedSession()
        val candidate = mutation(original(), original().selections.copy(rawSourceCollection = false), setOf(raw))
        val effects = Effects(session).apply { reservationSucceeds = false }

        assertEquals(IntegratedConsentSubmissionResult.DISPATCH_FAILED, effects.submit(candidate))
        assertEquals(candidate, effects.disk)
        assertEquals(candidate, effects.appPending)
        assertEquals(candidate, session.pendingMutationOrNull())
        assertEquals(PurposeConsentSyncState.WITHDRAWAL_RETRY, session.status(raw))
        assertTrue(effects.transport.isEmpty())
        effects.reservationSucceeds = true

        assertEquals(IntegratedConsentSubmissionResult.UNCHANGED, effects.submit(candidate, retry = true))
        assertEquals(listOf(candidate), effects.transport)
    }

    @Test
    fun orphanRecoveryPersistsDenialAndRequiresExactConfirmationBeforeRegrant() {
        val session = confirmedSession()
        session.withdrawImmediately(raw)
        assertNull(session.pendingMutationOrNull())
        val recovery = requireNotNull(session.withdrawalRecoverySelectionsOrNull())
        assertFalse(recovery.rawSourceCollection)
        assertTrue(recovery.automaticReporting)
        val denial = mutation(original(), recovery, session.localWithdrawalItems())
        val effects = Effects(session)
        assertEquals(IntegratedConsentSubmissionResult.APPLIED, effects.submit(denial))
        assertEquals(listOf(denial), effects.transport)
        assertFalse(session.isAllowed(raw))

        // Re-create the process-local session from the same confirmed receipt and durable request.
        assertTrue(denial.preservesRestoredConfirmation(original(), actor, denial.clientRevision))
        val restored = confirmedSession()
        assertTrue(restored.restorePendingMutation(requireNotNull(effects.disk), retry = true))
        val confirmed = response(original(), denial)
        assertFalse(restored.applyExactPendingConfirmation(confirmed.copy(requestId = "different_request_0001")))
        assertFalse(restored.isAllowed(raw))
        assertEquals(denial, restored.pendingMutationOrNull())
        assertTrue(restored.applyExactPendingConfirmation(confirmed))
        assertEquals(PurposeConsentSyncState.CONFIRMED_DENIED, restored.status(raw))
        assertTrue(restored.localWithdrawalItems().isEmpty())
        assertNull(restored.withdrawalRecoverySelectionsOrNull())
        assertTrue(restored.isAllowed(automatic))

        val laterGrant = mutation(confirmed, confirmed.selections.copy(rawSourceCollection = true), emptySet())
        assertEquals(IntegratedConsentSubmissionResult.APPLIED, Effects(restored).submit(laterGrant))
        assertFalse(restored.isAllowed(raw))
        assertTrue(restored.applyExactPendingConfirmation(response(confirmed, laterGrant)))
        assertTrue(restored.isAllowed(raw))
    }

    @Test
    fun orphanWithAlreadyDeniedServerValueStillCarriesExplicitWithdrawalItems() {
        val denied = original().copy(selections = original().selections.copy(rawSourceCollection = false))
        val session = IntegratedConsentSession().also { assertTrue(it.accept(denied)) }
        session.withdrawImmediately(raw)
        val recovery = requireNotNull(session.withdrawalRecoverySelectionsOrNull())
        val candidate = mutation(denied, recovery, session.localWithdrawalItems())
        assertTrue(candidate.withdrawalItems.contains(raw))
        assertEquals(IntegratedConsentSubmissionResult.APPLIED, Effects(session).submit(candidate))
        assertTrue(session.applyExactPendingConfirmation(response(denied, candidate)))
        assertEquals(PurposeConsentSyncState.CONFIRMED_DENIED, session.status(raw))
    }

    @Test
    fun restoredPendingMustExplainExactIdentityReceiptAndRequestFingerprint() {
        val original = original()
        val candidate = mutation(original, original.selections.copy(rawSourceCollection = false), setOf(raw))
        assertTrue(candidate.preservesRestoredConfirmation(original, actor, 2L))
        assertFalse(candidate.preservesRestoredConfirmation(original, "b".repeat(64), 2L))
        assertFalse(candidate.preservesRestoredConfirmation(original, actor, 3L))
        assertFalse(candidate.copy(installationId = "other-installation").preservesRestoredConfirmation(original, actor, 2L))
        assertFalse(candidate.copy(previousServerRevision = 0L).preservesRestoredConfirmation(original, actor, 2L))
        assertFalse(candidate.copy(expectedPreviousBackendReceiptSha256 = "b".repeat(64)).preservesRestoredConfirmation(original, actor, 2L))
        assertFalse(candidate.copy(requestId = "different_request_0002").preservesRestoredConfirmation(original, actor, 2L))
        assertFalse(candidate.copy(desiredSelections = candidate.desiredSelections.copy(trainingReuse = false))
            .preservesRestoredConfirmation(original, actor, 2L))
    }

    @Test
    fun dispatchFailureAfterAccountResetDoesNotResurrectOldPending() {
        val session = confirmedSession()
        val candidate = mutation(original(), original().selections.copy(rawSourceCollection = false), setOf(raw))
        val result = session.submitPendingMutation(candidate, persist = { true }, dispatch = {
            session.resetForNewEnrollment()
            throw RejectedExecutionException("old account reservation cancelled")
        })
        assertEquals(IntegratedConsentSubmissionResult.DISPATCH_FAILED, result)
        assertNull(session.pendingMutationOrNull())
        assertEquals(PurposeConsentSyncState.UNCONFIRMED, session.status(raw))
    }

    @Test
    fun exactWithdrawalRetryCompletesBeforeRecoveringASecondWithdrawal() {
        val session = confirmedSession()
        val first = mutation(original(), original().selections.copy(rawSourceCollection = false), setOf(raw))
        val effects = Effects(session)
        assertEquals(IntegratedConsentSubmissionResult.APPLIED, effects.submit(first, retry = true))
        session.withdrawImmediately(automatic)
        val combined = mutation(original(), first.desiredSelections.copy(automaticReporting = false),
            setOf(raw, automatic), revision = 3L)
        assertEquals(IntegratedConsentSubmissionResult.REJECTED, effects.submit(combined))

        assertTrue(session.isPendingMutationCurrent(first))
        assertEquals(IntegratedConsentSubmissionResult.UNCHANGED, effects.submit(first, retry = true))
        val firstReceipt = response(original(), first)
        assertTrue(session.applyExactPendingConfirmation(firstReceipt))
        assertFalse(session.isAllowed(automatic))
        assertEquals(setOf(automatic), session.localWithdrawalItems())

        val recovery = requireNotNull(session.withdrawalRecoverySelectionsOrNull())
        assertFalse(recovery.rawSourceCollection)
        assertFalse(recovery.automaticReporting)
        val second = mutation(firstReceipt, recovery, session.localWithdrawalItems())
        assertEquals(IntegratedConsentSubmissionResult.APPLIED, effects.submit(second))
        assertTrue(session.applyExactPendingConfirmation(response(firstReceipt, second)))
        assertTrue(session.localWithdrawalItems().isEmpty())
        assertFalse(session.isAllowed(automatic))
    }

    @Test
    fun restartRestoresValidatedPendingBeforeLaterWithdrawalMarkerAndSendsBothDenialsInOrder() {
        val storedConfirmation = original()
        val durablePending = mutation(storedConfirmation,
            storedConfirmation.selections.copy(rawSourceCollection = false), setOf(raw))
        val laterMarker = setOf(automatic)
        // Main first validates the confirmed receipt and the durable pending fingerprint.
        assertTrue(durablePending.preservesRestoredConfirmation(storedConfirmation, actor, 2L))
        val restored = IntegratedConsentSession()
        assertTrue(restored.restoreCurrentConfirmation(storedConfirmation))
        assertTrue(restored.restorePendingMutation(durablePending, retry = true))
        laterMarker.forEach(restored::withdrawImmediately)
        assertEquals(setOf(raw, automatic), restored.localWithdrawalItems())
        assertFalse(restored.isAllowed(raw))
        assertFalse(restored.isAllowed(automatic))
        assertEquals(durablePending, restored.pendingMutationOrNull())

        val effects = Effects(restored).apply { disk = durablePending }
        assertEquals(IntegratedConsentSubmissionResult.UNCHANGED, effects.submit(durablePending, retry = true))
        val firstReceipt = response(storedConfirmation, durablePending)
        assertTrue(restored.applyExactPendingConfirmation(firstReceipt))
        assertEquals(setOf(automatic), restored.localWithdrawalItems())
        assertFalse(restored.isAllowed(automatic))
        val laterDenial = mutation(firstReceipt,
            requireNotNull(restored.withdrawalRecoverySelectionsOrNull()), restored.localWithdrawalItems())
        assertEquals(IntegratedConsentSubmissionResult.APPLIED, effects.submit(laterDenial))
        assertEquals(listOf(durablePending, laterDenial), effects.transport)
        assertTrue(restored.applyExactPendingConfirmation(response(firstReceipt, laterDenial)))
        assertFalse(restored.isAllowed(automatic))
        assertTrue(restored.localWithdrawalItems().isEmpty())
    }

    @Test
    fun dispatchRechecksOwnerAfterReentrantAccountResetBeforeTransportReservation() {
        val session = confirmedSession()
        val candidate = mutation(original(), original().selections.copy(rawSourceCollection = false), setOf(raw))
        var reservations = 0
        val result = session.submitPendingMutation(candidate, persist = { true }, dispatch = {
            session.resetForNewEnrollment()
            if (!session.isPendingMutationCurrent(it)) false else {
                reservations += 1
                true
            }
        })
        assertEquals(IntegratedConsentSubmissionResult.DISPATCH_FAILED, result)
        assertEquals(0, reservations)
        assertNull(session.pendingMutationOrNull())
    }

    @Test
    fun dispatchDoesNotHoldSessionLockWhileReportQueueContextReadsConsent() {
        val session = confirmedSession()
        val candidate = mutation(original(), original().selections.copy(rawSourceCollection = false), setOf(raw))
        // The real coordinator acquires its monitor before invoking contextProvider. Storage
        // and transport must never be reached in this bounded lock-order regression.
        val unusedAead = object : LocalAead {
            override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits): AeadSealResult =
                error("storage must not run")
            override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits): AeadOpenResult =
                error("storage must not run")
            override fun destroyVersion(version: Int): Boolean = error("storage must not run")
            override fun destroyKnownVersions(): Boolean = error("storage must not run")
            override fun createFreshAfterVerifiedPurge(): Boolean = error("storage must not run")
        }
        val coordinator = ReportQueueDrainCoordinator(AndroidReportQueueStore(
            capacityProfile = null,
            storageFactory = { error("storage must not run") },
            aead = unusedAead,
            idFactory = UUID::randomUUID,
            nowMillis = { 1_000L },
            testOnly = Unit,
        ))
        val unusedTransport = object : ReportQueueTransport {
            override fun statusCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt?> =
                error("transport must not run")
            override fun uploadCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt> =
                error("transport must not run")
        }
        val contextEntered = CountDownLatch(1)
        val dispatchEntered = CountDownLatch(1)
        val contextRead = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val queue = workers.submit {
                try {
                    coordinator.startNext(contextProvider = {
                        contextEntered.countDown()
                        check(dispatchEntered.await(3, TimeUnit.SECONDS))
                        assertEquals(original(), session.currentConfirmationOrNull())
                        contextRead.countDown()
                        throw QueueContextReadFinished()
                    }, transport = unusedTransport)
                } catch (_: QueueContextReadFinished) {
                    // End the real context callback without needing queue/storage fixtures.
                }
            }
            assertTrue(contextEntered.await(3, TimeUnit.SECONDS))
            val submission = workers.submit<IntegratedConsentSubmissionResult> {
                session.submitPendingMutation(candidate, persist = { true }, dispatch = {
                    dispatchEntered.countDown()
                    // With the former dispatch-under-session-lock, the queue cannot read
                    // consent. Time out before cancelActive so the regression cannot deadlock.
                    assertTrue("queue context blocked by dispatch-held session lock",
                        contextRead.await(2, TimeUnit.SECONDS))
                    coordinator.cancelActive()
                    true
                })
            }
            assertEquals(IntegratedConsentSubmissionResult.APPLIED, submission.get(5, TimeUnit.SECONDS))
            queue.get(5, TimeUnit.SECONDS)
            assertEquals(candidate, session.pendingMutationOrNull())
        } finally {
            dispatchEntered.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun mainCreationAndRetryUseSubmissionBeforeStorageAndTransport() {
        val source = listOf(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
            File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        ).first { it.isFile }.readText()
        val creation = source.substringAfter("private fun persistIntegratedConsentDraft(")
            .substringBefore("private fun retryPendingIntegratedConsentMutation(")
        val retry = source.substringAfter("private fun retryPendingIntegratedConsentMutation(")
            .substringBefore("private fun startIntegratedConsentRequest(")
        assertTrue(creation.indexOf("submitPendingMutation(") < creation.indexOf("sensitivePrefs.edit()"))
        assertTrue(creation.indexOf("submitPendingMutation(") < creation.indexOf("integratedConsentClient.saveCall("))
        assertTrue(retry.indexOf("submitPendingMutation(") < retry.indexOf("integratedConsentClient.saveCall("))
        assertFalse(creation.contains("restorePendingMutation("))
        assertFalse(retry.contains("restorePendingMutation("))
        assertTrue(creation.contains("withdrawalRecoverySelectionsOrNull()"))
        assertTrue(source.contains("RejectedConsentCasJournalCodec.restoredPendingPreservesConfirmation("))
        val pendingRestore = source.indexOf(
            "val restoredPendingRaw = sensitivePrefs.getString(")
        val markerRestore = source.indexOf(
            "sensitivePrefs.getString(PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED, null)\n            ?.let { raw ->")
        assertTrue(pendingRestore >= 0)
        assertTrue(markerRestore > pendingRestore)
        val restore = source.substring(pendingRestore, markerRestore)
        assertTrue(restore.contains("restoredPendingRaw?.let(::pendingIntegratedConsentMutationOrNull)"))
        assertTrue(restore.contains("restoredPendingPreservesConfirmation("))
        assertTrue(restore.contains("restorePendingMutation(mutation, retry = true)"))
    }

    private class Effects(private val session: IntegratedConsentSession) {
        var disk: PendingIntegratedConsentMutation? = null
        var appPending: PendingIntegratedConsentMutation? = null
        var storageSucceeds = true
        var reservationSucceeds = true
        val writes = mutableListOf<PendingIntegratedConsentMutation>()
        val transport = mutableListOf<PendingIntegratedConsentMutation>()

        fun submit(candidate: PendingIntegratedConsentMutation, retry: Boolean = false) =
            session.submitPendingMutation(candidate, retry, persist = {
                if (!storageSucceeds) false else {
                    disk = it.copy(withdrawalItems = it.withdrawalItems.toSet())
                    writes += it
                    true
                }
            }, dispatch = {
                assertEquals(it, disk)
                assertEquals(it, session.pendingMutationOrNull())
                appPending = it
                if (!reservationSucceeds) throw RejectedExecutionException("executor stopped")
                transport += it
                true
            })
    }

    private class QueueContextReadFinished : RuntimeException()

    private fun confirmedSession() = IntegratedConsentSession().also { assertTrue(it.accept(original())) }

    private fun original() = IntegratedConsentConfirmation(
        schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
        policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
        installationId = "test-installation-0001",
        requestId = "initial_confirmation_0001",
        itemVersions = IntegratedConsentItemVersions(),
        clientRevision = 1L,
        revision = 1L,
        selections = IntegratedConsentSelections(true, true, true, true),
        confirmedAt = "2026-09-26T00:00:00.000Z",
        gatewayAuditRecordSha256 = "1".repeat(64),
        backendConsentReceiptSha256 = "2".repeat(64),
        controlSecret = "3".repeat(64),
    )

    private fun mutation(
        previous: IntegratedConsentConfirmation,
        selections: IntegratedConsentSelections,
        withdrawals: Set<IntegratedConsentItem>,
        revision: Long = previous.clientRevision + 1L,
    ): PendingIntegratedConsentMutation {
        val identity = "FP013|${previous.installationId}|$revision|${previous.backendConsentReceiptSha256}|$selections"
        val requestId = "consent_" + MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return PendingIntegratedConsentMutation(
            installationId = previous.installationId,
            actorSha256 = actor,
            requestId = requestId,
            policyVersion = previous.policyVersion,
            clientRevision = revision,
            previousServerRevision = previous.revision,
            expectedPreviousBackendReceiptSha256 = previous.backendConsentReceiptSha256,
            desiredSelections = selections,
            withdrawalItems = withdrawals,
            createdAtEpochMs = 1_000L,
        )
    }

    private fun response(previous: IntegratedConsentConfirmation, mutation: PendingIntegratedConsentMutation) =
        previous.copy(
            requestId = mutation.requestId,
            clientRevision = mutation.clientRevision,
            revision = previous.revision + 1L,
            selections = mutation.desiredSelections,
            backendConsentReceiptSha256 = (previous.revision + 3L).toString().repeat(64),
        )
}
